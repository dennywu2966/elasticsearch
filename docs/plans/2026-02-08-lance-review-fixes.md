# Lance Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix correctness and performance regressions in shard-aware Lance query execution, and wire nprobes/refresh/mapping behavior end-to-end.

**Architecture:** Keep query semantics unchanged for legacy mode, but make shard-aware mode explicit and deterministic: resolve shard once, search once per weight, and reuse candidates across leaf segments. Route filtering must follow Elasticsearch routing math (`routing_num_shards` + routing factor). Mapping and plugin wiring should round-trip user intent without silent fallback.

**Tech Stack:** Elasticsearch plugin Java, Lucene query/scorer APIs, ES plugin REST handlers, ESSingleNodeTestCase / ESTestCase.

---

### Task 1: Guard shard-aware mode and cache native search once per weight

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`

**Step 1: Write failing tests**
- Add test for shard-aware query with unknown shard ID (`-1`) expecting explicit failure (no shard0 fallback).
- Add test on multi-segment index verifying shard-aware dataset search executes once per query/weight, not once per segment.

**Step 2: Run targeted tests to verify failure**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests"`

**Step 3: Implement minimal fix**
- In `createWeight`, resolve and execute shard-aware search once when shard ID is known.
- Reuse shared candidates in `scorerSupplier` for all leaves.
- Reject shard-aware mode when shard ID is unknown.

**Step 4: Re-run targeted tests and keep green**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests"`

### Task 2: Align shard routing math with Elasticsearch

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceStorageConfig.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`

**Step 1: Write failing tests**
- Add test for routing hash conversion handling `Integer.MIN_VALUE` safely.
- Add test verifying routing factor logic (`routing_num_shards > number_of_shards`) maps to expected primary shard.

**Step 2: Run targeted tests to verify failure**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests"`

**Step 3: Implement minimal fix**
- Replace `Math.abs(hash) % numShards` with `Math.floorMod(hash, routingNumShards) / routingFactor`.
- Parse and store routing shard metadata in storage config from index settings.

**Step 4: Re-run targeted tests**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests"`

### Task 3: Persist storage.sharding_strategy through mapping serialization

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/LanceVectorExternalMountTests.java`

**Step 1: Write failing integration test**
- Create index with `storage.sharding_strategy: NONE` in shard-aware mapping.
- Retrieve mapping and assert `storage.sharding_strategy` remains present and equals `NONE`.

**Step 2: Run targeted test to verify failure**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.LanceVectorExternalMountTests.testShardAwareMappingPreservesShardingStrategy"`

**Step 3: Implement minimal fix**
- Emit `sharding_strategy` in `doXContentBody` storage serialization.

**Step 4: Re-run targeted test**
- Run same test command as Step 2.

### Task 4: Wire nprobes from DSL to query execution and remove console prints

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryBuilder.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java`
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryBuilderTests.java`

**Step 1: Write failing test**
- Add test that `LanceKnnQueryBuilder#doToQuery` produces a `LanceKnnQuery` carrying requested `nprobes`.

**Step 2: Run targeted tests to verify failure**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryBuilderTests"`

**Step 3: Implement minimal fix**
- Remove `System.out`/`System.err` debug prints.
- Pass `nprobes` through builder → field type → query.
- Use query `nprobes` in dataset search call.

**Step 4: Re-run targeted tests**
- Run same test command as Step 2.

### Task 5: Register refresh REST endpoint in plugin handlers

**Files:**
- Modify: `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/LanceVectorPlugin.java`
- Test: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/LanceVectorPluginSettingsTests.java`

**Step 1: Write failing test**
- Assert plugin rest handlers include both `/_lance/stats` and `/_lance/refresh` handlers.

**Step 2: Run targeted tests to verify failure**
- Run: `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.LanceVectorPluginSettingsTests"`

**Step 3: Implement minimal fix**
- Register `RestLanceRefreshAction` in `getRestHandlers`.

**Step 4: Re-run targeted tests**
- Run same test command as Step 2.

### Task 6: Final verification pass

**Files:**
- N/A (verification)

**Step 1: Run focused suite for touched test classes**
- Run:
  - `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryTests"`
  - `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.query.LanceKnnQueryBuilderTests"`
  - `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.LanceVectorPluginSettingsTests"`
  - `./gradlew :plugins:lance-vector:test --tests "org.elasticsearch.plugin.lance.LanceVectorExternalMountTests.testShardAwareMappingPreservesShardingStrategy"`

**Step 2: Optional broader confidence run (if time permits)**
- Run: `./gradlew :plugins:lance-vector:test`

