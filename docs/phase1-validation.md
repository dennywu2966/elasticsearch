# Phase 1 Validation Guide

## Overview

This document provides a comprehensive guide to validate the Phase 1 implementation of Lance Vector integration for Elasticsearch.

## Prerequisites

- JDK 21
- Elasticsearch source code with lance-vector plugin
- Gradle build system working

## Build and Test Commands

### 1. Build the Plugin

```bash
# Build the entire project
./gradlew build

# Build just the lance-vector plugin
./gradlew :plugins:lance-vector:build

# Compile without running tests
./gradlew :plugins:lance-vector:compileJava
```

### 2. Run Unit Tests

```bash
# Run all tests for the plugin
./gradlew :plugins:lance-vector:test

# Run specific test class
./gradlew :plugins:lance-vector:test --tests LanceVectorExternalMountIT
```

### 3. Format Code

```bash
# Apply Spotless formatting
./gradlew :plugins:lance-vector:spotlessApply

# Check formatting
./gradlew :plugins:lance-vector:spotlessCheck
```

## Manual Validation Steps

Since automated tests may not be available in all environments, here's how to manually validate the implementation:

### Step 1: Verify File Structure

```bash
# Check that all required files exist
find plugins/lance-vector -type f -name "*.java" | sort
```

Expected files:
- `LanceVectorPlugin.java`
- `LanceVectorFieldMapper.java`
- `LanceStorageConfig.java`
- `LanceKnnQuery.java`
- `FakeLanceDataset.java`
- `FakeLanceDatasetRegistry.java`
- `LanceVectorExternalMountIT.java`

### Step 2: Verify Test Dataset

```bash
# Check test dataset exists
cat plugins/lance-vector/src/test/resources/datasets/simple.json
```

Expected content:
```json
[
  { "id": "doc1", "vector": [0.1, 0.2, 0.3] },
  { "id": "doc2", "vector": [0.2, 0.1, 0.3] },
  { "id": "doc3", "vector": [0.9, 0.1, 0.0] }
]
```

### Step 3: Run Integration Tests

The integration tests validate:
1. **Basic kNN join**: Queries external Lance dataset and joins results to ES docs by `_id`
2. **kNN with filter**: Applies post-filtering on Lucene-indexed fields

Expected behavior:
- `testKnnBasicJoin`: Returns 2 docs, with `doc3` ranked first (closest to query vector [0.9, 0.1, 0.0])
- `testKnnWithFilter`: Returns only docs matching `category=a`, filtered after kNN

### Step 4: Code Review Checklist

Review the implementation against Phase 1 requirements:

#### Mapping & Configuration
- ✅ `lance_vector` field type registered
- ✅ Required fields: `dims`, `storage.type`, `storage.uri`, `storage.lance_id_column`, `storage.lance_vector_column`
- ✅ `read_only=true` enforced (Phase 1 constraint)
- ✅ Validation for missing required fields

#### Query Path
- ✅ `KnnVectorQueryable` interface allows custom field types in kNN queries
- ✅ `KnnVectorQueryBuilder` supports both `DenseVectorFieldType` and `KnnVectorQueryable` implementations
- ✅ `LanceVectorFieldType` implements `createKnnQuery()`
- ✅ `LanceKnnQuery` performs candidate generation + join

#### Join Logic
- ✅ Candidates from Lance dataset joined to Lucene docs by `_id`
- ✅ Filter applied as BitSet for correctness (avoid iterator reuse bug)
- ✅ Top-k selection after join
- ✅ Score assignment from Lance similarity

#### Storage & Test Infrastructure
- ✅ `FakeLanceDataset` loads vectors from JSON
- ✅ `FakeLanceDatasetRegistry` caches datasets
- ✅ Cosine and dot-product similarity metrics

## Phase 1 Acceptance Criteria

Verify each criterion from `docs/phase1-design.md`:

### P1-G1: Mount existing Lance dataset
- ✅ `PUT /{index}` with `storage.type=external` accepted
- ✅ Mapping validation completes quickly (control-plane only)
- ✅ Error on invalid configuration (missing uri, read_only=false, etc.)

### P1-G2: Read-only kNN works
- ✅ `POST /{index}/_search` with `knn` returns results
- ✅ Join to existing ES docs by `_id`
- ✅ Top-k results from Lance candidates

### P1-G3: Schema validation
- ✅ `dims` validated at mount time
- ✅ Column existence checked (via FakeLanceDataset)
- ✅ Actionable error messages

### P1-G4: Shard routing correctness
- ⚠️  Phase 1 uses single dataset (no per-shard partitioning in fake impl)
- ✅ Production impl will map shards to partitions/ranges

### P1-G5: Operational visibility
- ⚠️  Metrics and logging not yet implemented (deferred)
- ✅ Basic error reporting via exceptions

## Known Limitations (Phase 1)

1. **No write path**: Indexing vectors to Lance is not supported
2. **No delete sync**: Deletes in ES don't propagate to Lance
3. **Post-filtering only**: Filters applied in Lucene after kNN, not pushed to Lance
4. **Single-shard test dataset**: Multi-shard partitioning tested via mapping, not runtime
5. **No production storage**: Uses fake in-memory dataset loader, not real S3/Lance

## Troubleshooting

### Build Failures

If Gradle fails with "Service 'SystemInfo' is not available":
- This is an environment issue (e.g., PRoot-Distro)
- Validate code manually using the checklist above
- Run tests on a different machine with native Linux

### Test Failures

If `testKnnBasicJoin` fails:
- Check that dataset file exists at correct path
- Verify `doc3` has vector `[0.9, 0.1, 0.0]`
- Ensure query vector is `[0.9, 0.1, 0.0]`

If `testKnnWithFilter` fails:
- Check filter logic in `LanceKnnQuery.buildDocScores()`
- Verify BitSet is built correctly from filter query
- Ensure filter is applied before score collection

## Next Steps (Phase 2+)

After Phase 1 validation passes:
1. Add real S3 storage adapter using Lance Java SDK
2. Implement per-shard dataset partitioning
3. Add filter pushdown to Lance
4. Implement metrics and logging
5. Add production-grade caching
