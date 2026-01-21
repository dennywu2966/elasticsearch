# Lance Vector Plugin - Handoff Document

**Date:** 2026-01-21
**Branch:** `es-lance-claude`
**Status:** Real Lance SDK integration validated and working

---

## Project Overview

This project integrates the **real lance-java SDK** into an Elasticsearch plugin (`lance-vector`) to enable IVF-PQ indexed vector search against external Lance format datasets.

### Key Achievement
- Successfully integrated `com.lancedb:lance-core:1.0.0-beta.2` with Elasticsearch
- kNN search working against real `.lance` datasets with IVF-PQ indexing
- Validated with 1,000 vectors, 128 dimensions, search latency ~15ms (after warm-up)

---

## Implementation Summary

### Files Modified

| File | Changes |
|------|---------|
| `plugins/lance-vector/build.gradle` | Added lance-java, Arrow, FlatBuffers, Eclipse Collections dependencies |
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/RealLanceDataset.java` | Full implementation using lance-java SDK |
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistry.java` | Unified cache for datasets |
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/query/LanceKnnQuery.java` | Uses LanceDatasetRegistry |
| `plugins/lance-vector/src/main/plugin-metadata/entitlement-policy.yaml` | Added `load_native_libraries` permission |
| `gradle/verification-metadata.xml` | Added checksums for all new dependencies |

### New Files Created

| File | Purpose |
|------|---------|
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDataset.java` | Interface for dataset abstraction |
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetConfig.java` | Configuration record |
| `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/storage/LanceDatasetRegistry.java` | Unified caching registry |
| `plugins/lance-vector/licenses/*.txt` | License files for all dependencies |

### Dependencies Added (build.gradle)

```gradle
dependencies {
  // Lance Java SDK
  implementation('com.lancedb:lance-core:1.0.0-beta.2') {
    exclude group: 'com.google.guava', module: 'guava'
    exclude group: 'com.google.guava', module: 'failureaccess'
    exclude group: 'org.apache.arrow', module: 'arrow-memory-netty'  // IMPORTANT: use unsafe instead
  }

  // Guava (runtime only - ES restriction)
  runtimeOnly 'com.google.guava:guava:33.4.0-jre'
  runtimeOnly 'com.google.guava:failureaccess:1.0.2'

  // Apache Arrow (use unsafe allocator, not netty)
  implementation 'org.apache.arrow:arrow-vector:15.0.0'
  implementation 'org.apache.arrow:arrow-memory-unsafe:15.0.0'
  implementation 'org.apache.arrow:arrow-c-data:15.0.0'
  implementation 'org.apache.arrow:arrow-format:15.0.0'

  // FlatBuffers
  implementation 'com.google.flatbuffers:flatbuffers-java:24.3.25'

  // Eclipse Collections (required by Arrow)
  implementation 'org.eclipse.collections:eclipse-collections-api:11.1.0'
  implementation 'org.eclipse.collections:eclipse-collections:11.1.0'
}
```

---

## Critical Configuration

### JVM Options Required

A file must exist at `$ES_HOME/config/jvm.options.d/arrow.options` with:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
```

This is required for Apache Arrow's memory management to work with Java 21+.

### Why arrow-memory-unsafe instead of arrow-memory-netty

The `arrow-memory-netty` module causes ES to crash because:
1. Netty's `PlatformDependent` tries to access filesystem paths during initialization
2. ES's entitlement/security system blocks these file reads
3. This causes `ExceptionInInitializerError` at runtime

Solution: Use `arrow-memory-unsafe` which uses Java's `Unsafe` directly without Netty dependencies.

---

## Validation Results

### Test Dataset Created

Location: `/tmp/test-vectors.lance`

Created with Python:
```python
import lance
import numpy as np
import pyarrow as pa

n_vectors = 1000
dims = 128
vectors = np.random.randn(n_vectors, dims).astype(np.float32)
vectors = vectors / np.linalg.norm(vectors, axis=1, keepdims=True)

table = pa.table({
    "_id": [f"doc{i}" for i in range(n_vectors)],
    "vector": [v.tolist() for v in vectors]
})

dataset = lance.write_dataset(table, "/tmp/test-vectors.lance")
dataset.create_index(
    column="vector",
    index_type="IVF_PQ",
    metric="cosine",
    num_partitions=32,
    num_sub_vectors=16
)
```

### ES Index Created

```bash
curl -X PUT "localhost:9201/lance-test" -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "storage": {
          "uri": "file:///tmp/test-vectors.lance"
        }
      },
      "title": {"type": "text"}
    }
  }
}'
```

### kNN Search Working

```bash
curl -X POST "localhost:9201/lance-test/_search" -H "Content-Type: application/json" -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],  # 128-dim vector
    "k": 5,
    "num_candidates": 50
  }
}'
```

**Results:**
- First search: ~2.3s (cold start, dataset loading)
- Subsequent searches: ~15ms
- Returns ranked documents with similarity scores

---

## Issues Encountered and Resolutions

| Issue | Cause | Resolution |
|-------|-------|------------|
| Guava compile classpath error | ES doesn't allow Guava on compile classpath | Exclude from lance-core, add as `runtimeOnly` |
| Netty `ExceptionInInitializerError` | arrow-memory-netty conflicts with ES security | Switch to `arrow-memory-unsafe` |
| Arrow `InaccessibleObjectException` | Java 21 module system | Add `--add-opens=java.base/java.nio=ALL-UNNAMED` |
| `NoClassDefFoundError: flatbuffers/Table` | Missing FlatBuffers dependency | Add `flatbuffers-java` |
| `NoClassDefFoundError: arrow/flatbuf/Null` | Missing Arrow format classes | Add `arrow-format` |
| `NoClassDefFoundError: eclipse/collections` | Missing Eclipse Collections | Add `eclipse-collections` and `eclipse-collections-api` |
| Jar hell (netty + unsafe) | Both allocators included transitively | Exclude `arrow-memory-netty` from lance-core |

---

## Build and Test Commands

```bash
# Build the plugin
./gradlew :plugins:lance-vector:assemble

# Run unit tests
./gradlew :plugins:lance-vector:test

# Update verification metadata (after adding deps)
./gradlew --write-verification-metadata sha256 :plugins:lance-vector:compileJava

# Build local ES distribution
./gradlew localDistro

# Install plugin
ES_HOME=build/distribution/local/elasticsearch-9.4.0-SNAPSHOT
$ES_HOME/bin/elasticsearch-plugin install --batch file://$(pwd)/plugins/lance-vector/build/distributions/lance-vector-9.4.0-SNAPSHOT.zip

# Start ES (with security disabled for testing)
$ES_HOME/bin/elasticsearch -E http.port=9201 -E xpack.security.enabled=false
```

---

## Current State

### What's Working
- RealLanceDataset opens `.lance` format datasets via JNI
- Vector search uses native IVF-PQ index
- Results returned with similarity scores
- Dataset caching via LanceDatasetRegistry
- FakeLanceDataset still works for JSON format (testing)

### Known Limitations
- Deprecated JVM warnings about `sun.misc.Unsafe` (Arrow issue, not critical)
- SLF4J "no providers found" warning (cosmetic)
- Score calculation may need tuning for different similarity metrics

### Not Yet Tested
- OSS (Alibaba Cloud Object Storage) URI support
- S3 URI support
- Large-scale performance benchmarks
- Multi-shard scenarios

---

## Next Steps (Suggested)

1. **Test OSS Integration**: Verify `oss://bucket/path.lance` URIs work with credentials
2. **Performance Benchmarking**: Test with larger datasets (100K+, 1M+ vectors)
3. **Score Normalization**: Review distance-to-score conversion for accuracy
4. **Error Handling**: Add graceful degradation when native libs unavailable
5. **Documentation**: Update plugin README with usage instructions

---

## File Locations

```
/home/denny/projects/es-lance-claude/
├── plugins/lance-vector/
│   ├── build.gradle                    # Dependencies
│   ├── licenses/                       # License files for all deps
│   └── src/main/java/org/elasticsearch/plugin/lance/
│       ├── storage/
│       │   ├── RealLanceDataset.java   # Main implementation
│       │   ├── LanceDatasetRegistry.java
│       │   ├── LanceDataset.java       # Interface
│       │   └── LanceDatasetConfig.java
│       └── query/
│           └── LanceKnnQuery.java      # Uses registry
├── gradle/verification-metadata.xml    # Dependency checksums
└── build/distribution/local/elasticsearch-9.4.0-SNAPSHOT/
    ├── config/jvm.options.d/arrow.options  # JVM options for Arrow
    └── plugins/lance-vector/           # Installed plugin
```

---

## Plan File Reference

The original implementation plan is at:
`/home/denny/.claude/plans/partitioned-marinating-bear.md`

---

## Contact/Context

This work was done to integrate the real lance-java SDK for production-quality IVF-PQ vector search, replacing the previous `FakeLanceDataset` brute-force implementation.

Key insight: The lance-java SDK uses JNI to call native Rust code, providing O(nprobes × centroids) search instead of O(n) brute-force.
