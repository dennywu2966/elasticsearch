# P1 & P2 Implementation Plan — Lance Vector Plugin

> Generated from `future_plan_refined_zh.md` with full codebase analysis.
> Target branch: `es-9.2.4-lance-real-time`

---

## Table of Contents

- [P1: Query Semantics & Performance](#p1-query-semantics--performance)
  - [P1.1: Filter Support (DSL Alignment)](#p11-filter-support-dsl-alignment)
  - [P1.2: Parameter Control](#p12-parameter-control)
  - [P1.3: Pre-filtering Strategies](#p13-pre-filtering-strategies)
  - [P1.4: _id Join Optimization & Performance Baselines](#p14-_id-join-optimization--performance-baselines)
- [P2: NRT / Refresh](#p2-nrt--refresh)
  - [P2.1: Refresh Interval Setting](#p21-refresh-interval-setting)
  - [P2.2: Manifest Change Detection](#p22-manifest-change-detection)
  - [P2.3: Smooth Reload (Atomic Swap)](#p23-smooth-reload-atomic-swap)
  - [P2.4: Manual Invalidation API](#p24-manual-invalidation-api)
  - [P2.5: Refresh Metrics](#p25-refresh-metrics)
- [Execution Order & Dependencies](#execution-order--dependencies)
- [Risk Assessment](#risk-assessment)
- [Definition of Done](#definition-of-done)

---

## P1: Query Semantics & Performance

**Goal**: filter 可用 + 参数可控 + 性能基线可复现
**Priority**: High

### P1.1: Filter Support (DSL Alignment)

**Current State**:
- `LanceKnnQueryBuilder.doToQuery()` passes `null` for filter (line 124)
- `LanceKnnQuery` already has full filter infrastructure: bitset building (lines 277-293), per-doc filter checks (lines 296-323)
- `LanceVectorFieldMapper.createKnnQuery()` accepts filter parameter in its signature but it's never populated from the builder
- Existing `PREFILTERING-PLAN.md` covers the hybrid pre/post approach

**Implementation Steps**:

#### Step 1: Parse filter from DSL

**File**: `LanceKnnQueryBuilder.java`

Add filter parsing to the query builder:

```java
// New field
private QueryBuilder filterBuilder;

// In fromXContent():
// After parsing num_candidates, add:
} else if ("filter".equals(currentFieldName)) {
    filterBuilder = parseTopLevelQuery(parser);
}

// In doToQuery():
// Replace null filter with:
Query filter = filterBuilder != null
    ? filterBuilder.toQuery(context)
    : null;
```

**DSL Example** (target):
```json
{
  "lance_knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 100,
    "filter": {
      "term": { "category": "electronics" }
    }
  }
}
```

**Serialization**: Add filter to `writeTo()`/`StreamInput` constructor with version check for BWC.

#### Step 2: Wire filter through to LanceKnnQuery

**File**: `LanceKnnQuery.java`

No changes needed — filter is already wired in the constructor and used in `buildDocScores()`. The existing post-filter path works: Lance returns `num_candidates`, then `buildDocScores()` applies the Lucene filter bitset and keeps top-k.

#### Step 3: Tests

| Test | File | What it verifies |
|------|------|-----------------|
| DSL parsing | `LanceKnnQueryBuilderTests.java` | filter round-trips through XContent and StreamInput |
| Post-filter correctness | `LanceKnnQueryTests.java` | filter reduces result set correctly |
| Empty filter | `LanceKnnQueryBuilderTests.java` | null filter = no filtering |
| Filter + k interaction | `LanceKnnQueryTests.java` | with filter, may return < k results |

#### Step 4: Documentation

Add to REST API docs:
- `filter` parameter: Optional Elasticsearch query to post-filter Lance candidates
- Behavior: Lance returns `num_candidates` results, filter applied in Lucene, top `k` returned
- Warning: High-selectivity filters may return fewer than `k` results

**Effort**: ~2 days
**Risk**: Low — post-filter path already implemented, just needs wiring

---

### P1.2: Parameter Control

**Current State**:
- `k` (default 10) and `num_candidates` (default 100) parsed from DSL
- `nprobes` hardcoded to 20 in `RealLanceDataset.java` line ~475, overridable only via `LANCE_NPROBES` env var
- No index-level or query-level settings for Lance-specific parameters
- `similarity` comes from field mapping, not overridable per-query

**Implementation Steps**:

#### Step 1: Add nprobes as index setting

**File**: `LanceVectorPlugin.java`

```java
public static final Setting<Integer> LANCE_DEFAULT_NPROBES = Setting.intSetting(
    "index.lance.default_nprobes",
    20,
    1,
    Setting.Property.IndexScope,
    Setting.Property.Dynamic
);
```

Register in `getSettings()`.

#### Step 2: Add nprobes to query DSL

**File**: `LanceKnnQueryBuilder.java`

```json
{
  "lance_knn": {
    "field": "embedding",
    "query_vector": [...],
    "k": 10,
    "num_candidates": 200,
    "nprobes": 40
  }
}
```

**Resolution order**: query-level → index setting → env var → hardcoded default (20)

#### Step 3: Pass nprobes through the call chain

**File flow**: `LanceKnnQueryBuilder` → `LanceVectorFieldMapper.createKnnQuery()` → `LanceKnnQuery` → `LanceDataset.search()`

**LanceDataset interface change**:
```java
// New overload (default delegates to old for backward compat)
default List<Candidate> search(float[] query, int numCandidates, String similarity, int nprobes) {
    return search(query, numCandidates, similarity);
}
```

**RealLanceDataset**: Use the new nprobes parameter instead of hardcoded value.

#### Step 4: Add oversampling factor setting

For filter use cases, oversampling prevents too-few results:

```java
public static final Setting<Float> LANCE_FILTER_OVERSAMPLE = Setting.floatSetting(
    "index.lance.filter_oversample_factor",
    3.0f,   // num_candidates multiplied by this when filter is present
    1.0f,
    Setting.Property.IndexScope,
    Setting.Property.Dynamic
);
```

**Logic in LanceKnnQuery.createWeight()**:
```java
int effectiveCandidates = numCandidates;
if (filter != null) {
    effectiveCandidates = (int)(numCandidates * oversampleFactor);
}
```

#### Step 5: Fix per-candidate INFO logging

**File**: `RealLanceDataset.java` line ~536

Change `logger.info("Candidate: ...")` → `logger.trace("Candidate: ...")` or remove entirely. This is a P0 carry-over item flagged in the plan.

#### Step 6: Tests

| Test | What |
|------|------|
| nprobes DSL parsing | Round-trip through XContent |
| nprobes resolution | query > index setting > env > default |
| Dynamic setting update | Change nprobes at runtime |
| Oversampling with filter | More candidates fetched when filter present |

**Effort**: ~3 days
**Risk**: Medium — nprobes changes can affect recall/latency tradeoff. Need benchmarks.

---

### P1.3: Pre-filtering Strategies

**Current State**: Full design in `PREFILTERING-PLAN.md`. Post-filter works. Pre-filter requires Lance SDK support for filtered search.

**Prerequisite**: Verify Lance Java SDK 1.0.0-beta.2 supports `Scanner.filter()` or equivalent. If not, skip to P1.3-Alternative.

#### Strategy A: Post-filter + Adaptive Oversampling (Implement First)

Already partially implemented. Complete with:

1. **Selectivity estimation**: Before Lance search, estimate filter selectivity from Lucene:
   ```java
   long totalDocs = leafReader.maxDoc();
   long filteredDocs = filterWeight.count(leafReaderContext);
   double selectivity = (double) filteredDocs / totalDocs;
   ```

2. **Adaptive oversampling formula**:
   ```java
   int adaptiveCandidates = (int) Math.min(
       numCandidates / selectivity,  // scale inversely with selectivity
       totalDocs * 0.1               // cap at 10% of dataset
   );
   ```

3. **Warning log** if post-filter returns < k results after max oversampling.

#### Strategy B: Selectivity-driven Routing (Future, needs SDK support)

```java
FilterStrategy decideFilterStrategy(double selectivity, int k, int totalDocs) {
    if (selectivity < 0.10 && filteredDocs < k * 2) {
        return PRE_FILTER;   // Small result set → push filter to Lance
    }
    return POST_FILTER;       // Large result set → oversample + post-filter
}
```

This requires `LanceDataset.search()` to accept a filter predicate:
```java
List<Candidate> search(float[] query, int numCandidates, String similarity,
                       int nprobes, IntPredicate docFilter);
```

#### Strategy C: Query-level Heuristic Override

```json
{
  "lance_knn": {
    "prefilter_heuristic": "auto" | "always" | "never"
  }
}
```

**Effort**: Strategy A: ~2 days, Strategy B: ~5 days (depends on SDK), Strategy C: ~1 day
**Risk**: Strategy B has SDK dependency risk. Strategy A is safe to ship first.

---

### P1.4: _id Join Optimization & Performance Baselines

**Current State**: `buildDocScores()` does per-candidate `_id` term lookup via postings. This is O(M × log N) where M = candidates, N = docs in segment.

#### Optimization 1: Batch _id Lookup

Instead of one-by-one term lookup, build a hash set of candidate IDs and do a single pass:

```java
// Current: O(M × log N) - M postings seeks
for (Candidate c : candidates) {
    PostingsEnum postings = terms.postings(new BytesRef(c.id()));
    // ... seek per candidate
}

// Optimized: O(N + M) - single terms enum iteration
Map<BytesRef, Float> candidateMap = new HashMap<>();
for (Candidate c : candidates) {
    candidateMap.put(new BytesRef(c.id()), c.score());
}
TermsEnum termsEnum = terms.iterator();
while (termsEnum.next() != null) {
    Float score = candidateMap.get(termsEnum.term());
    if (score != null) {
        // found match
    }
}
```

**When to use**: When `M > threshold` (e.g., M > 1000). For small M, current approach is fine.

**Decision**: This is a performance optimization. Profile first, then decide. See baselines below.

#### Performance Baseline Plan

Create reproducible benchmarks:

| Benchmark | Dataset | Vectors | Dims | Index | Metric |
|-----------|---------|---------|------|-------|--------|
| Small-brute | synthetic | 10K | 128 | none | latency p50/p99, recall@10 |
| Medium-IVF | synthetic | 100K | 768 | IVF-PQ | latency p50/p99, recall@10 |
| Large-IVF | real (if available) | 1M | 768 | IVF-PQ | latency p50/p99, recall@10 |
| Filter-low | synthetic | 100K | 768 | IVF-PQ | 1% selectivity |
| Filter-high | synthetic | 100K | 768 | IVF-PQ | 50% selectivity |

**Benchmark tool**: JMH microbenchmarks or custom test harness.

**File**: `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/benchmark/`

**Metrics to capture**:
- End-to-end query latency (p50, p95, p99)
- Lance search time vs _id join time vs filter time (via profiling)
- Recall@k vs brute-force ground truth
- nprobes sensitivity curve (10, 20, 40, 80 vs latency/recall)
- Memory: Arrow allocator usage, GC pressure

**Effort**: ~3 days for baseline infrastructure + initial results
**Risk**: Low — benchmarks don't change production code

---

## P2: NRT / Refresh

**Goal**: 可配置刷新 + reload 不影响线上查询 + 指标可观测
**Priority**: Medium

### Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                  LanceVectorPlugin                    │
│                                                       │
│  ┌─────────────────┐   ┌──────────────────────────┐  │
│  │  RefreshScheduler │──▶│  LanceDatasetRegistry     │  │
│  │  (per-index)      │   │  (singleton cache)         │  │
│  │                   │   │                            │  │
│  │  - interval       │   │  ┌─────────────────────┐  │  │
│  │  - detectChange() │   │  │ VersionedEntry        │  │  │
│  │  - triggerReload()│   │  │ - dataset (AtomicRef) │  │  │
│  └─────────────────┘   │  │ - version/etag        │  │  │
│                         │  │ - lastRefresh         │  │  │
│  ┌─────────────────┐   │  └─────────────────────┘  │  │
│  │  REST API         │   │                            │  │
│  │ /_lance/refresh   │──▶│  invalidate() / reload()  │  │
│  └─────────────────┘   └──────────────────────────┘  │
│                                                       │
│  ┌─────────────────┐                                  │
│  │  Metrics          │                                  │
│  │  - refresh_count  │                                  │
│  │  - refresh_latency│                                  │
│  │  - active_version │                                  │
│  └─────────────────┘                                  │
└─────────────────────────────────────────────────────┘
```

### P2.1: Refresh Interval Setting

**File**: `LanceVectorPlugin.java`

```java
public static final Setting<TimeValue> LANCE_REFRESH_INTERVAL = Setting.timeSetting(
    "index.lance.refresh_interval",
    TimeValue.MINUS_ONE,   // -1 = disabled (default for read-only phase)
    TimeValue.MINUS_ONE,   // minimum = -1 (disabled)
    Setting.Property.IndexScope,
    Setting.Property.Dynamic
);
```

**Values**:
- `-1`: Disabled (default — backward compatible with current behavior)
- `0`: Refresh on every search (expensive, for testing only)
- `30s`, `1m`, etc.: Periodic refresh

Register in `getSettings()`.

**Effort**: ~0.5 day

---

### P2.2: Manifest Change Detection

Lance datasets have a manifest file that tracks versions. When new data is appended or the index is rebuilt, the manifest changes.

#### Detection Mechanisms

| Storage | Detection Method | Latency |
|---------|-----------------|---------|
| Local FS | File modification time on `_latest.manifest` | ~0ms |
| OSS | HTTP HEAD on manifest → ETag/Last-Modified | ~50-200ms |
| S3 | HTTP HEAD on manifest → ETag | ~50-200ms |

#### Implementation

**New class**: `storage/LanceManifestChecker.java`

```java
public interface LanceManifestChecker {
    /**
     * Check if the dataset at the given URI has changed since lastVersion.
     * @return new version string if changed, null if unchanged
     */
    String checkForUpdate(String uri, String lastVersion) throws IOException;
}
```

**Implementations**:

1. **`LocalManifestChecker`**: Reads `_latest.manifest` file mtime
2. **`OssManifestChecker`**: HTTP HEAD on `oss://bucket/path/_latest.manifest`, compare ETag
3. **`S3ManifestChecker`** (future): Similar to OSS

**File**: `RealLanceDataset.java` — add `getManifestVersion()`:
```java
public String getManifestVersion() {
    // Use Lance SDK if available, otherwise check manifest file
    return manifestVersion;
}
```

**Effort**: ~2 days (local + OSS)
**Risk**: Medium — network latency for OSS HEAD requests. Mitigate with async checking.

---

### P2.3: Smooth Reload (Atomic Swap)

**Critical requirement**: In-flight queries must NOT be disrupted during reload.

#### Design: AtomicReference + Reference Counting

**Modified**: `LanceDatasetRegistry.java`

Replace simple `Cache<String, LanceDataset>` value with a versioned wrapper:

```java
class VersionedDataset implements Closeable {
    final LanceDataset dataset;
    final String version;          // manifest version / ETag
    final long loadedAtMillis;
    final AtomicInteger refCount;  // active query references

    void acquire() { refCount.incrementAndGet(); }

    void release() {
        if (refCount.decrementAndGet() == 0 && markedForClose) {
            dataset.close();
        }
    }
}
```

**Reload flow**:

```
1. RefreshScheduler.tick()
2.   → ManifestChecker.checkForUpdate(uri, currentVersion)
3.   → if changed:
4.       newDataset = RealLanceDataset.open(uri, config)
5.       oldEntry = cache.get(uri)
6.       cache.put(uri, new VersionedDataset(newDataset, newVersion))
7.       oldEntry.markForClose()
8.       // oldEntry closes when refCount reaches 0
```

**Query path** (in `LanceKnnQuery.createWeight()`):

```java
VersionedDataset vd = registry.acquire(uri);
try {
    List<Candidate> results = vd.dataset.search(...);
    // build scores
} finally {
    vd.release();
}
```

This ensures:
- In-flight queries continue using the old dataset until they complete
- New queries immediately use the new dataset
- Old dataset is closed only when last reference is released

**Effort**: ~3 days
**Risk**: High — reference counting bugs can leak native resources. Need thorough testing.

---

### P2.4: Manual Invalidation API

**New REST endpoint**: `POST /_lance/refresh`

**File**: `rest/RestLanceRefreshAction.java`

```
POST /_lance/refresh
{
  "index": "my-index",           // optional: specific index
  "uri": "oss://bucket/path",   // optional: specific URI
  "force": true                   // optional: force reload even if version unchanged
}

Response:
{
  "refreshed": ["oss://bucket/path/dataset.lance"],
  "skipped": [],
  "errors": {},
  "took_ms": 150
}
```

**Alternative**: `POST /_lance/refresh/{index}` for index-specific refresh.

Also add to existing `/_lance/stats` response:

```json
{
  "datasets": {
    "oss://bucket/path/dataset.lance": {
      "version": "v42",
      "loaded_at": "2025-01-15T10:30:00Z",
      "last_refresh_check": "2025-01-15T10:31:00Z",
      "active_queries": 3,
      "refresh_count": 12,
      "size": 100000,
      "has_index": true
    }
  }
}
```

**Effort**: ~1.5 days
**Risk**: Low

---

### P2.5: Refresh Metrics

**New class**: `metrics/LanceRefreshMetrics.java`

```java
public class LanceRefreshMetrics {
    // Counters
    private final LongAdder refreshCount = new LongAdder();
    private final LongAdder refreshFailureCount = new LongAdder();
    private final LongAdder refreshSkipCount = new LongAdder();  // version unchanged

    // Gauges
    private volatile long lastRefreshDurationMs;
    private volatile long lastRefreshTimestamp;
    private volatile String activeVersion;

    // Histogram (approximate)
    private final LongAdder totalRefreshTimeMs = new LongAdder();

    public Map<String, Object> toMap() {
        return Map.of(
            "refresh_count", refreshCount.sum(),
            "refresh_failure_count", refreshFailureCount.sum(),
            "refresh_skip_count", refreshSkipCount.sum(),
            "last_refresh_duration_ms", lastRefreshDurationMs,
            "last_refresh_timestamp", lastRefreshTimestamp,
            "active_version", activeVersion,
            "avg_refresh_duration_ms", refreshCount.sum() > 0
                ? totalRefreshTimeMs.sum() / refreshCount.sum() : 0
        );
    }
}
```

Exposed via `/_lance/stats` endpoint (already exists from P0).

**Effort**: ~1 day
**Risk**: Low

---

### P2.6: Refresh Scheduler

**New class**: `refresh/LanceRefreshScheduler.java`

```java
public class LanceRefreshScheduler implements Closeable {
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> activeSchedules;
    private final LanceDatasetRegistry registry;
    private final LanceRefreshMetrics metrics;

    /**
     * Start periodic refresh for a dataset URI.
     */
    public void schedule(String uri, TimeValue interval, LanceDatasetConfig config) {
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
            () -> refreshIfChanged(uri, config),
            interval.millis(),
            interval.millis(),
            TimeUnit.MILLISECONDS
        );
        activeSchedules.put(uri, future);
    }

    private void refreshIfChanged(String uri, LanceDatasetConfig config) {
        try {
            String newVersion = manifestChecker.checkForUpdate(uri, currentVersion);
            if (newVersion != null) {
                metrics.recordRefreshStart();
                long start = System.nanoTime();
                registry.reload(uri, config);
                metrics.recordRefreshSuccess(newVersion, System.nanoTime() - start);
            } else {
                metrics.recordRefreshSkip();
            }
        } catch (Exception e) {
            metrics.recordRefreshFailure();
            logger.warn("Failed to refresh dataset {}: {}", uri, e.getMessage());
        }
    }
}
```

**Lifecycle**: Created in `LanceVectorPlugin`, closed in `close()`.

**Trigger**: When an index with `index.lance.refresh_interval != -1` uses a lance_vector field, the scheduler is activated for that field's dataset URI.

**Thread pool**: Use ES `generic` thread pool or create a dedicated `lance_refresh` pool with 1-2 threads.

**Effort**: ~2 days
**Risk**: Medium — scheduler lifecycle management with ES node lifecycle.

---

## Execution Order & Dependencies

```
Week 1-2: P1.1 + P1.2 (parallel tracks)
  ├── P1.1: Filter support (DSL → wire → test)        [2 days]
  ├── P1.2: Parameter control (nprobes + oversampling)  [3 days]
  └── P1 logging fix (per-candidate INFO → TRACE)       [0.5 day]

Week 2-3: P1.3-A + P1.4 (parallel tracks)
  ├── P1.3-A: Adaptive oversampling with selectivity    [2 days]
  └── P1.4: Performance baselines                       [3 days]

Week 3-4: P2.1 + P2.2 + P2.5 (foundation)
  ├── P2.1: Refresh interval setting                    [0.5 day]
  ├── P2.2: Manifest change detection                   [2 days]
  └── P2.5: Refresh metrics                             [1 day]

Week 4-5: P2.3 + P2.4 + P2.6 (core NRT)
  ├── P2.3: Atomic swap (VersionedDataset + refcount)   [3 days]
  ├── P2.4: Manual invalidation API                     [1.5 days]
  └── P2.6: Refresh scheduler                           [2 days]

Week 5-6: Integration testing + documentation
  ├── End-to-end NRT tests                              [2 days]
  ├── Performance baselines with refresh                [1 day]
  └── Documentation + runbook                           [1 day]
```

**Total estimated effort**: ~24 days

**Dependencies**:
- P1.1 blocks P1.3-A (filter must work before adaptive oversampling)
- P2.2 blocks P2.3 (need version detection before atomic swap)
- P2.3 blocks P2.6 (scheduler triggers reload, reload needs atomic swap)
- P1.2 is independent — can start immediately
- P2.5 is independent — can start any time

---

## Risk Assessment

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|-----------|
| Lance SDK doesn't support filtered search | P1.3-B blocked | Medium | Ship P1.3-A (post-filter) first, which works with any SDK |
| Reference counting leak (P2.3) | Native memory leak | Medium | Extensive tests + PhantomReference safety net |
| OSS HEAD request latency spikes (P2.2) | Refresh scheduler blocks | Low | Async checking + timeout + backoff |
| nprobes change degrades recall | User-facing quality issue | Low | Default stays 20, document recall/latency tradeoff |
| Refresh scheduler thread leak | Node instability | Low | Bounded thread pool + proper shutdown in close() |
| BWC: new fields in StreamInput | Rolling upgrade breaks | Medium | Version-gated serialization for all new fields |

---

## Definition of Done

### P1 DoD
- [ ] `filter` parameter parseable in `lance_knn` DSL with full XContent round-trip tests
- [ ] Post-filter correctly reduces results (unit + integration tests)
- [ ] `nprobes` configurable at query-level and index-level with proper resolution order
- [ ] `index.lance.filter_oversample_factor` dynamic setting
- [ ] Per-candidate INFO logging demoted to TRACE
- [ ] Performance baselines documented and reproducible
- [ ] Adaptive oversampling formula implemented for filtered queries
- [ ] All new parameters documented in REST API docs

### P2 DoD
- [ ] `index.lance.refresh_interval` dynamic setting (default: disabled)
- [ ] Manifest change detection for local FS and OSS
- [ ] Atomic dataset swap via VersionedDataset + reference counting
- [ ] In-flight queries not disrupted during refresh (proven by concurrent test)
- [ ] `POST /_lance/refresh` manual invalidation endpoint
- [ ] Refresh metrics in `/_lance/stats` response
- [ ] Refresh scheduler with proper lifecycle management
- [ ] Documentation + runbook for operating NRT refresh

---

## P3+ Post-P2: Configurable Sharding Strategy

**Status**: ✅ Completed

**Goal**: Ensure ES can adapt to any Lance dataset sharding algorithm, not just ES's Murmur3 hash

### Implementation Summary

Added `ShardingStrategy` enum to `LanceStorageConfig` with two modes:

1. **NONE** (default for legacy mode): No candidate filtering
   - Use when: Lance dataset is unsharded, or uses unknown sharding algorithm
   - Behavior: All candidates from Lance search are returned

2. **ES_ROUTING** (default for shard-aware mode): Filter using ES's Murmur3 hash
   - Use when: Lance dataset sharded using ES's Murmur3 hash function
   - Behavior: `shardId = Math.abs(Murmur3HashFunction.hash(document_id)) % numShards`
   - Guarantees: 1:1 mapping between ES shards and Lance datasets

### Files Modified

| File | Change |
|------|--------|
| `LanceStorageConfig.java` | Added `ShardingStrategy` enum, `numShards` field, `getShardingStrategy()` accessor |
| `LanceVectorFieldMapper.java` | Parse `storage.sharding_strategy` from mapping, pass `numShards` from index settings |
| `LanceKnnQuery.java` | Updated `filterCandidatesByShard()` to respect configured strategy |

### Configuration Example

```json
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "storage": {
          "type": "external",
          "uri_prefix": "oss://bucket/data",
          "shard_path": "{index}/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "sharding_strategy": "ES_ROUTING"
        }
      }
    }
  }
}
```

### Creating Lance Datasets Matching ES Routing

When creating Lance datasets, use ES's Murmur3 hash function:

```python
from org.elasticsearch.cluster.routing.Murmur3HashFunction import hash

def shard_document(document_id: str, num_shards: int) -> int:
    """Shard a document using ES's Murmur3 hash function."""
    hash_val = hash(document_id)
    return abs(hash_val) % num_shards

# Create sharded dataset
for doc in documents:
    shard_id = shard_document(doc['_id'], num_shards=3)
    shard_datasets[shard_id].add(doc)
```

### Testing

Added tests in `LanceStorageConfigTests.java`:
- `testDefaultNumShardsAndShardingStrategy()` - Verify defaults
- `testShardAwareConfigDefaultsToEsRouting()` - Verify shard-aware uses ES_ROUTING
- `testExplicitNoneShardingStrategy()` - Verify NONE can be explicitly set

All 273 unit tests pass.

### Validation

The feature is validated in `reg_validation_guide.md`:
- Added LV-11: ShardingStrategy NONE test
- Added LV-12: ShardingStrategy ES_ROUTING test
- Added R10: Sharding strategy critical regression check

---

## Files Modified Summary

### P1 Changes
| File | Change |
|------|--------|
| `LanceKnnQueryBuilder.java` | Add filter, nprobes parsing + serialization |
| `LanceKnnQuery.java` | Adaptive oversampling logic |
| `LanceVectorFieldMapper.java` | Pass new params to createKnnQuery |
| `LanceDataset.java` | New search overload with nprobes |
| `RealLanceDataset.java` | Configurable nprobes, fix logging |
| `LanceVectorPlugin.java` | New settings: nprobes, oversample_factor |
| `LanceKnnQueryBuilderTests.java` | Filter + nprobes tests |
| `LanceKnnQueryTests.java` | Filter correctness tests |

### P2 Changes (New Files)
| File | Purpose |
|------|---------|
| `storage/VersionedDataset.java` | Reference-counted dataset wrapper |
| `storage/LanceManifestChecker.java` | Interface for version detection |
| `storage/LocalManifestChecker.java` | Local FS manifest checking |
| `storage/OssManifestChecker.java` | OSS ETag-based checking |
| `refresh/LanceRefreshScheduler.java` | Periodic refresh coordination |
| `metrics/LanceRefreshMetrics.java` | Refresh counters/gauges |
| `rest/RestLanceRefreshAction.java` | Manual refresh REST endpoint |

### P2 Changes (Modified Files)
| File | Change |
|------|--------|
| `LanceDatasetRegistry.java` | VersionedDataset, acquire/release, reload() |
| `LanceKnnQuery.java` | acquire/release around search |
| `LanceVectorPlugin.java` | Refresh interval setting, scheduler lifecycle |
| `RestLanceStatsAction.java` | Add refresh metrics to stats |
