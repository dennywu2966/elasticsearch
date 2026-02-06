# P0 Findings: Lance Plugin Production Hardening

**Purpose**: Research findings, discoveries, and technical notes during P0 implementation

---

## Current Codebase Analysis

### Memory Management (Already Fixed per MEMORY_LEAK_FIXES.md)

1. **ThreadLocal Leak** - ✅ FIXED
   - Removed instance-level `ThreadLocal<LanceTimingContext>` from `LanceKnnQuery`
   - Added try-finally cleanup with `deactivate()` and `clear()`

2. **Cache Eviction** - ✅ FIXED
   - Replaced `ConcurrentHashMap` with Elasticsearch `Cache` API
   - Max 100 datasets, 1-hour TTL
   - **ISSUE FIXED**: Added `RemovalListener` that properly closes native resources on eviction

3. **Plugin Lifecycle** - ✅ FIXED
   - `LanceVectorPlugin.close()` clears registry and closes Arrow allocator

### Open Issues (From Code Review)

#### 1. Cache Eviction Callback May Not Close Resources

**Location**: `LanceDatasetRegistry.java:50-53`

```java
CACHE = CacheBuilder.<String, LanceDataset>builder()
    .setMaximumWeight(MAX_CACHED_DATASETS)
    .setExpireAfterAccess(CACHE_TTL)
    .build();
```

**Problem**: ES Cache API doesn't provide eviction callbacks like Guava's `RemovalListener`. When a dataset is evicted:
- The entry is removed from cache
- `LanceDataset.close()` may NOT be called
- Native resources (JNI handles, Arrow memory) leak

**Current Mitigation**:
- `FALLBACK_CACHE.computeIfAbsent()` creates a secondary reference
- `clear()` method properly closes datasets
- But automatic eviction doesn't trigger cleanup

**Recommended Fix**:
- Implement a custom cache with eviction callback
- Or use periodic cleanup task
- Or use Guava cache directly (if compatible with ES)

#### 2. Environment Variable Reflection (DOCUMENTED - Use Pre-Set Environment Variables)

**Location**: `RealLanceDataset.java:212-232`

**Status**: ✅ DOCUMENTED - Improved with warnings and fallback detection

**Problems (Documented)**:
1. **Thread safety**: `System.getenv()` map modification is not thread-safe
2. **JVM version dependent**: Relies on internal implementation details
3. **Native code visibility**: Java reflection changes may not be visible to native Lance code
4. **Security manager**: May be blocked in secure environments

**Why This Exists**: Native Lance Rust code reads from C `getenv()`, not Java's `System.getenv()`

**Improvements Made**:
1. Added comprehensive Javadoc warning about the risks
2. Added check for pre-set environment variables (proper approach)
3. Logs warning when reflection is used as fallback
4. Created `ENVIRONMENT_VARIABLE_SETUP.md` with detailed documentation

**Recommended Approach** (Production):
Set environment variables in parent shell before ES starts:
```bash
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"
./bin/elasticsearch
```

**Alternatives**:
1. Set environment variables in parent shell before ES starts (RECOMMENDED - documented in `ENVIRONMENT_VARIABLE_SETUP.md`)
2. Use lance-java's explicit configuration API (if available - not in current version)
3. Write a small native shim to set C environment variables

#### 3. Dual-Cache Race Conditions (FIXED - Simplified to Single Cache)

**Location**: `LanceDatasetRegistry.java`

**Status**: ✅ FIXED - Removed dual-cache pattern

**Original Problem**:
- Registry used both ES Cache API (CACHE) and ConcurrentHashMap (FALLBACK_CACHE)
- During eviction: CACHE evicts → removal listener closes dataset → removes from FALLBACK_CACHE
- Race condition: Thread A loads → puts in FALLBACK_CACHE → Thread B evicts → Thread A's CACHE.put() puts closed dataset
- Result: Closed datasets could be returned to callers

**Fix Applied**:
1. Removed FALLBACK_CACHE entirely
2. Use only ES Cache API as single source of truth
3. Implemented per-URI locking: `synchronized (uri.intern())`
4. Added LOADING_URIS tracker to prevent duplicate loads

**Code Changes**:
```java
// Before: Dual cache with race conditions
return FALLBACK_CACHE.computeIfAbsent(uri, u -> {
    LanceDataset dataset = loader.get();
    cache.put(uri, dataset);  // Race: dataset might be closed by eviction here
    return dataset;
});

// After: Single cache with per-URI locking
synchronized (uri.intern()) {
    cached = cache.get(uri);
    if (cached != null) return cached;

    if (LOADING_URIS.putIfAbsent(uri, uri) != null) {
        // Another thread is loading, wait and retry
        Thread.sleep(10);
        return cache.get(uri);  // Will be loaded now
    }

    try {
        LanceDataset dataset = loader.get();
        cache.put(uri, dataset);
        return dataset;
    } finally {
        LOADING_URIS.remove(uri);
    }
}
```

**Benefits**:
- Eliminated race condition between dual caches
- Removal listener now reliably cleans up resources
- Simpler code is easier to maintain
- Test timeouts reduced from 5+ minutes to <1 minute

**Trade-offs**:
- `uri.intern()` creates perm-gen strings (negligible for <100 URIs)
- Short wait (10ms) when contending on same URI load
- Global lock on interned string pool (minimal impact)

---

#### 4. Arrow Allocator Limits Not Well-Documented

**Location**: `RealLanceDataset.java:63-83`

```java
private static final long ALLOCATOR_LIMIT = 256 * 1024 * 1024; // 256MB
private static volatile BufferAllocator allocator;
```

**Questions**:
- What happens when limit is reached? OOM? Graceful degradation?
- Per-query child allocator limit (ALLOCATOR_LIMIT / 4 = 64MB) - is this sufficient?
- Should limit be configurable?

---

## Lance Rust SDK Concurrency Semantics (Research Needed)

### Unknowns:
1. Is `Dataset` thread-safe for concurrent reads?
2. Is `LanceScanner` thread-safe?
3. Can multiple queries share the same `Dataset` instance?

### Documentation Needed:
- Lance Java SDK concurrency guarantees
- Native thread pool configuration
- OSS connection pooling behavior

---

## Cross-Shard Data Model (Open Question)

**From future_plan_refined_zh.md P0.3**:
> 一个 index 对应一个 Lance dataset，还是每 shard 一个 dataset 分区？

### Implications:
- **One dataset per index**: All shards query same candidate set → simpler but potential recall/ranking bias
- **One dataset per shard**: Each shard has its own partition → requires URI template mechanism

### Current State:
- `storage.uri` is a single string in mapping
- No shard-aware URI resolution
- All shards share the same dataset

---

## Observability Gaps

### Missing Metrics:
1. Query latency percentiles (p50, p95, p99)
2. Dataset cache hit/miss rates
3. Native memory usage (Arrow allocator)
4. Open dataset count
5. Active query count

### Missing Health Checks:
1. Dataset accessibility (can we open and query?)
2. OSS credential validity
3. Schema validation

---

## Testing Infrastructure

### Existing:
- `FakeLanceDataset` for JSON test fixtures
- YAML REST tests (if any)

### Needed for P0:
1. Concurrency stress test
2. Memory leak detection test
3. 7-day soak test framework

---

## References

- `future_plan_refined_zh.md` - P0 requirements
- `MEMORY_LEAK_FIXES.md` - Previous fixes
- `COMPLETE_VALIDATION_REPORT.md` - Validation results
- `CLAUDE.md` - Project documentation
