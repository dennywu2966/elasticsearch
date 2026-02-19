# Opus Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement all actions in `docs/opus_review_feedback_0209_actions.md` in severity order with test-first validation and no regressions.

**Architecture:** Apply fail-fast behavior where silent degradation exists, tighten input validation and resource/concurrency safety, and preserve existing public contracts unless they currently return incorrect results silently. Each change is introduced via RED→GREEN cycles using existing test suites plus targeted new tests.

**Tech Stack:** Java 21, Gradle, Elasticsearch plugin framework, Arrow/Lance JNI integrations, JUnit/ESTestCase.

---

### Task 1: Blocker Batch A (`#2 #3 #4`)

**Files:**
- Modify: `plugins/lance-vector/build.gradle`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistry.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/RealLanceDataset.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistryTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/RealLanceDatasetTests.java`

**Step 1: Write failing tests**
- Add tests that require:
  - non-Lance/unsupported URIs in production path fail fast (no Fake fallback),
  - missing distance column raises failure instead of returning synthetic scores.

**Step 2: Run tests to verify RED**
Run: `./gradlew :plugins:lance-vector:test --tests "*LanceDatasetRegistryTests" --tests "*RealLanceDatasetTests" --console=plain`

**Step 3: Write minimal implementation**
- Jackson: replace hardcoded `2.17.2` with `${versions.jackson}`.
- Lance dataset loading: reject non-Lance production URI fallback.
- Candidate extraction: throw when distance column is absent.

**Step 4: Run tests to verify GREEN**
Run: same command as Step 2.

---

### Task 2: Blocker Batch B (`#7 #8`)

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistry.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistryTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/LanceDatasetConcurrencyStressTests.java`

**Step 1: Write failing tests**
- Add tests covering:
  - no `String.intern()`/spin-wait path assumptions (single-load under concurrency),
  - invalidate path closes exactly once.

**Step 2: Run tests to verify RED**
Run: `./gradlew :plugins:lance-vector:test --tests "*LanceDatasetRegistryTests" --tests "*LanceDatasetConcurrencyStressTests" --console=plain`

**Step 3: Write minimal implementation**
- Replace per-URI synchronization/spin-wait with `ConcurrentHashMap<String, CompletableFuture<LanceDataset>>`.
- Remove explicit close in `invalidate()`, rely on removal listener.

**Step 4: Run tests to verify GREEN**
Run: same command as Step 2.

---

### Task 3: Security/Correctness Batch (`#6 #9 #11 #12 #18 #19 #20`)

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/EsToLanceFilterConverter.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/RealLanceDataset.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/OssStorageAdapter.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Modify: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealm.java`
- Modify: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamToken.java`
- Modify: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/OAuthTokenValidator.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/EsToLanceFilterConverterTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/OssStorageAdapterTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceSearchMetricsTests.java`
- Test: `plugins/security-realm-cloud-iam/src/test/java/org/elasticsearch/plugin/security/cloudiam/CloudIamTokenTests.java`
- Test: `plugins/security-realm-cloud-iam/src/test/java/org/elasticsearch/plugin/security/cloudiam/CloudIamRealmTests.java`

**Step 1: Write failing tests**
- Validate identifier sanitization and finite numeric handling in SQL conversion.
- Ensure listObjects unsupported behavior and credentials stream safety path.
- Ensure metrics are recorded on all exits.
- Add/adjust tests for logging/no stderr behavior where practical and OAuth exception mapping boundaries.

**Step 2: Run tests to verify RED**
Run: `./gradlew :plugins:lance-vector:test --tests "*EsToLanceFilterConverterTests" --tests "*OssStorageAdapterTests" --tests "*LanceSearchMetricsTests" :plugins:security-realm-cloud-iam:test --tests "*CloudIamTokenTests" --tests "*CloudIamRealmTests" --console=plain`

**Step 3: Write minimal implementation**
- Add column-name regex validation and reject NaN/Infinity numeric values.
- Narrow broad catches where feasible; improve warning/error visibility.
- Convert stderr prints to Log4j logger calls.
- Ensure metrics increment on early-return path.
- Wrap credentials file stream in try-with-resources.

**Step 4: Run tests to verify GREEN**
Run: same command as Step 2.

---

### Task 4: Quality/Consistency Batch (`#14 #17 #21 #22`)

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceRefreshService.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/VersionedDataset.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java`
- Modify: `plugins/security-realm-cloud-iam/src/main/java/org/elasticsearch/plugin/security/cloudiam/CloudIamToken.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/VersionedDatasetTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfigTests.java`
- Test: `plugins/security-realm-cloud-iam/src/test/java/org/elasticsearch/plugin/security/cloudiam/CloudIamTokenTests.java`

**Step 1: Write failing tests**
- Add tests for defensive-copy map exposure and atomic version snapshot semantics.
- Add comment/doc assertion tests if available for routing math consistency.

**Step 2: Run tests to verify RED**
Run: `./gradlew :plugins:lance-vector:test --tests "*VersionedDatasetTests" --tests "*LanceStorageConfigTests" :plugins:security-realm-cloud-iam:test --tests "*CloudIamTokenTests" --console=plain`

**Step 3: Write minimal implementation**
- Include throwable when logging refresh-cycle failures.
- Atomic snapshot for dataset/version reads.
- Correct routing-doc wording (`Math.floorMod`).
- Return immutable copies/views for exposed maps.

**Step 4: Run tests to verify GREEN**
Run: same command as Step 2.

---

### Task 5: Deferred Hardening Batch (`#1 #5 #10 #13 #15 #16`)

**Files:**
- Modify: `plugins/lance-vector/build.gradle`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/RealLanceDataset.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/OssStorageAdapter.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/RealLanceDatasetTests.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/storage/OssStorageAdapterTests.java`

**Step 1: Write failing tests**
- Add tests for explicit unsupported pre-filter behavior in RealLanceDataset path.
- Add tests for unsupported `listObjects()` behavior.
- Add tests for search timeout/guard behavior and vector close-on-failure paths where practical.

**Step 2: Run tests to verify RED**
Run: `./gradlew :plugins:lance-vector:test --tests "*LanceKnnQueryTests" --tests "*RealLanceDatasetTests" --tests "*OssStorageAdapterTests" --console=plain`

**Step 3: Write minimal implementation**
- Keep Arrow strategy safe for current branch constraints (avoid speculative major upgrade without compatibility evidence).
- Make unsupported behavior explicit instead of silent fallback.
- Add bounded execution/timeout safeguards in native search path.
- Improve `setEnvIfChanged()` exception context.
- Ensure Arrow vectors close on exceptional setup paths.

**Step 4: Run tests to verify GREEN**
Run: same command as Step 2.

---

### Task 6: Regression Verification

**Files:**
- No new files

**Step 1: Run full relevant plugin suites**
Run: `./gradlew :plugins:lance-vector:test :plugins:security-realm-cloud-iam:test --console=plain`

**Step 2: Build verification**
Run: `./gradlew :plugins:lance-vector:build :plugins:security-realm-cloud-iam:build --console=plain`

**Step 3: Document results**
- Update `progress.md` test table and error log.
- Update `task_plan.md` phase statuses.

