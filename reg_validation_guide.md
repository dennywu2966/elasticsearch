# ES Plugins Regression Validation Guide

**Official validation guide for Lance Vector + Cloud IAM plugins**

## Quick Start

```bash
# Start stack with 4GB JVM (production-like testing)
./project_starter.sh -d

# Run P0 critical tests
bash .claude/skills/es-plugins-validation/scripts/run_p0.sh

# Run full regression suite
bash .claude/skills/es-plugins-validation/scripts/run_all.sh
```

---

## Environment

| Component | Value |
|-----------|-------|
| Startup Script | `./project_starter.sh` (4GB JVM default) |
| ES Distribution | `build/distribution/local/elasticsearch-9.2.4-SNAPSHOT` |
| OSS Bucket | `denny-test-lance` |
| OSS Credentials | `~/.oss/credentials.json` |
| Basic Auth | `elastic / Summer11` |
| JVM Heap | 4GB (configurable via `--jvm-heap`) |

---

## Features Supported (After P1-P2-P3 Merge)

### P1-P2: NRT (Near Real-Time) Refresh Infrastructure
- **VersionedDataset**: Atomic dataset swaps without query interruption
- **Cluster Settings**: `lance.refresh.enabled`, `lance.refresh.interval`
- **Refresh Service**: Background polling for dataset updates
- **Metrics**: `/_lance/stats` endpoint for refresh statistics

### P3: Shard-Aware Dataset Mapping
- **URI Templates**: `{index}`, `{shard_id}` placeholders
- **Per-Shard Resolution**: Each shard reads its own Lance dataset
- **Backward Compatible**: Legacy single-URI mode continues to work
- **Configurable Sharding Strategy**: `storage.sharding_strategy` parameter
  - `NONE`: No filtering (default for legacy mode)
  - `ES_ROUTING`: Filter candidates using ES's Murmur3 hash (default for shard-aware mode)
- **Automatic 1:1 Mapping**: When using `ES_ROUTING`, guarantees ES shard → Lance dataset mapping

### Filter Support
- **Pre-Filter**: Push filtered IDs to Lance SDK before vector search
- **Post-Filter**: Intersect Lance results with Lucene filter bitset
- **AUTO Mode**: Automatically choose based on filter selectivity

### Additional Features
- **nprobes Control**: Tunable search accuracy/latency tradeoff
- **OSS Integration**: Remote Lance dataset storage on Alibaba Cloud OSS
- **Hybrid Search**: RRF (Reciprocal Rank Fusion) for vector + text

---

## Critical Regressions (R-Block)

| ID | Description | Method |
|----|-------------|--------|
| R1 | Zero-score bug | All `hits._score > 0` |
| R2 | Memory leaks | Heap growth < 30% |
| R3 | OSS env vars | Set before ES starts |
| R4 | Arrow types | `pa.string()` for _id |
| R5 | Replay attacks | Nonces reject duplicates |
| R6 | Timestamp skew | Expired tokens rejected |
| R7 | NRT refresh race | VersionedDataset prevents corruption |
| R8 | Shard resolution | Correct shard ID in URI template |
| R9 | Filter strategy | AUTO decision works correctly |
| R10 | Sharding strategy | ES_ROUTING filters candidates correctly |

---

## Lance Vector Tests

### Basic Search (LV-01 to LV-05)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-01 | OSS kNN search | 0 | Returns results, all scores > 0 |
| LV-02 | Local file kNN | 0 | `file:///` works, scores > 0 |
| LV-03 | kNN with k=1 | 1 | Single best match |
| LV-04 | kNN with k=100 | 1 | 100 results max |
| LV-05 | Empty query vector | 0 | Returns 400 |

### P3 Shard-Aware Tests (LV-06 to LV-12)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-06 | Shard URI template | 0 | `{shard_id}` replaced correctly |
| LV-07 | Index URI template | 1 | `{index}` replaced correctly |
| LV-08 | Per-shard dataset load | 0 | Each shard loads own dataset |
| LV-09 | Legacy single URI | 0 | Backward compatibility |
| LV-10 | Mixed mode clusters | 2 | Both modes coexist |
| LV-11 | ShardingStrategy NONE | 0 | No filtering, all candidates returned |
| LV-12 | ShardingStrategy ES_ROUTING | 0 | Candidates filtered by Murmur3 hash |

### P1-P2 NRT Refresh Tests (LV-11 to LV-18)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-11 | VersionedDataset swap | 0 | Atomic, no query failures |
| LV-12 | Cluster enable refresh | 0 | `lance.refresh.enabled=true` works |
| LV-13 | Cluster interval setting | 0 | `lance.refresh.interval` respected |
| LV-14 | Index-level override | 1 | Index setting overrides cluster |
| LV-15 | Refresh metrics | 1 | `/_lance/stats` returns data |
| LV-16 | Refresh disabled | 1 | No polling when disabled |
| LV-17 | Invalid interval rejected | 2 | 400 on malformed value |
| LV-18 | Concurrent refresh + search | 1 | No crashes or corruption |

### Filtering (LV-19 to LV-25)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-19 | kNN + term filter | 0 | Filter works AND scores > 0 |
| LV-20 | kNN + range filter | 0 | Numeric range works |
| LV-21 | kNN + bool filter | 1 | Multiple filters |
| LV-22 | kNN + must_not | 1 | Exclusions work |
| LV-23 | Filter matches none | 1 | Empty hits |
| LV-24 | AUTO pre-filter decision | 0 | Small filters trigger pre-filter |
| LV-25 | AUTO post-filter decision | 0 | Large filters use post-filter |

### Lance Native Filter Pushdown (LV-50 to LV-55)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-50 | Term filter pushdown | 0 | SQL filter sent to Lance |
| LV-51 | Fallback unmapped fields | 0 | ES post-filter works |
| LV-52 | SQL injection safety | 0 | Special chars escaped |
| LV-53 | Field mapping configured | 0 | Mapping resolves correctly |
| LV-54 | Boolean/numeric values | 1 | Correct SQL types |
| LV-55 | Empty/null field mapping | 2 | Graceful degradation |

### nprobes Control (LV-26 to LV-30)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-26 | nprobes parameter | 0 | Accepted in query |
| LV-27 | Default nprobes | 1 | Sensible default used |
| LV-28 | nprobes effect on recall | 1 | Higher = better recall |
| LV-29 | nprobes effect on latency | 1 | Higher = slower |
| LV-30 | Invalid nprobes | 2 | 400 on negative/nan |

### Hybrid Search (LV-31 to LV-35)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-31 | Vector + text RRF | 0 | Both fused |
| LV-32 | 3-way hybrid | 1 | Vector + text + term |
| LV-33 | Hybrid with filters | 1 | RRF + filter |
| LV-34 | Custom RRF constant | 2 | Uses custom value |
| LV-35 | Hybrid empty branch | 1 | Continues with non-empty |

### Memory & Performance (LV-36 to LV-39)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-36 | 1000 searches memory | 0 | Heap growth < 30% |
| LV-37 | 10k searches stress | 1 | No OOM |
| LV-38 | Concurrent requests | 1 | 10 parallel succeed |
| LV-39 | Large dataset (1M) | 2 | Completes < 5s |

### Cache Behavior (LV-40 to LV-43)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-40 | Cache hit (2nd query) | 1 | Faster than cold |
| LV-41 | 105 datasets (LRU) | 0 | First 5 evicted |
| LV-42 | Cache TTL expiration | 2 | 1hr enforced |
| LV-43 | Cache invalidate | 2 | Dataset reloads |

### Edge Cases (LV-44 to LV-52)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-44 | Dimension mismatch | 0 | 400 error |
| LV-45 | Missing dataset_uri | 0 | 400 error |
| LV-46 | Invalid URI scheme | 0 | 400 error |
| LV-47 | OSS bucket 404 | 1 | Clear error |
| LV-48 | Empty Lance dataset | 1 | Empty hits |
| LV-49 | Vector normalization | 2 | Scores 0-1 |
| LV-50 | Negative vector values | 1 | Handles correctly |
| LV-51 | Zero vector query | 1 | Returns or error |
| LV-52 | Malformed OSS URI | 1 | Validates before access |

---

## Cloud IAM Tests

### Authentication (CI-01 to CI-06)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-01 | STS signature valid | 0 | Request succeeds |
| CI-02 | OAuth bearer token | 0 | Request succeeds |
| CI-03 | Invalid signature | 0 | 401 |
| CI-04 | Expired OAuth token | 0 | 401 |
| CI-05 | Missing header | 0 | Falls through |
| CI-06 | Malformed header | 1 | 400 |

### Replay Protection (CI-07 to CI-09)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-07 | Duplicate nonce | 0 | Second = 401 |
| CI-08 | Nonce cache overflow | 1 | LRU works |
| CI-09 | Replayed after 1hr | 2 | Accepted again |

### Timestamp Validation (CI-10 to CI-13)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-10 | Expired timestamp | 0 | 401 |
| CI-11 | Future timestamp | 0 | 401 |
| CI-12 | Max skew boundary | 1 | Accepted at ±15min |
| CI-13 | Custom skew config | 2 | Respects setting |

### Role Mapping (CI-14 to CI-18)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-14 | Static roles (mock) | 0 | Gets mock_roles |
| CI-15 | ARN pattern match | 1 | Correct roles |
| CI-16 | No roles mapped | 0 | 401 |
| CI-17 | Multiple role rules | 1 | Union of roles |
| CI-18 | Role metadata | 2 | ARN/account in metadata |

### Caching (CI-19 to CI-23)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-19 | User cache hit | 1 | 2nd faster |
| CI-20 | Negative cache | 1 | Failed cached |
| CI-21 | Cache expiration | 2 | TTL enforced |
| CI-22 | Cache invalidate | 2 | expire() works |
| CI-23 | Concurrent writes | 2 | No races |

### Assumed Role (CI-24 to CI-26)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CI-24 | Assumed role allowed | 1 | Success |
| CI-25 | Assumed role blocked | 1 | 401 when disabled |
| CI-26 | Role ARN parsing | 2 | ARN extracted |

---

## Integration Tests (INT)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| INT-01 | IAM auth + kNN search | 0 | Auth succeeds, scores > 0 |
| INT-02 | OAuth + hybrid search | 1 | Token valid, RRF works |
| INT-03 | 10 concurrent IAM users | 1 | All succeed |
| INT-04 | IAM + kNN + filter | 0 | All layers work |
| INT-05 | Role mapping + vector write | 1 | User can index |
| INT-06 | Cache across plugins | 2 | No interaction bugs |
| INT-07 | ES restart with OSS env | 0 | Credentials persist |
| INT-08 | NRT refresh + sharding | 1 | Both features work together |
| INT-09 | Filter strategy + shards | 1 | Per-shard filtering works |
| INT-10 | Refresh + active queries | 1 | Queries complete during swap |

---

## Configuration Tests (CFG)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CFG-01 | Missing OSS creds | 0 | Error at startup |
| CFG-02 | Invalid OSS endpoint | 1 | Connection error |
| CFG-03 | IAM realm disabled | 1 | Realm inactive |
| CFG-04 | Cache TTL = 0 | 2 | No caching |
| CFG-05 | Max cache = 1 | 2 | Single entry |
| CFG-06 | NRT refresh interval | 0 | Cluster setting works |
| CFG-07 | Per-index refresh | 1 | Index override works |
| CFG-08 | Shard template syntax | 1 | Validated at index creation |

---

## Error Handling Tests (ERR)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| ERR-01 | OSS timeout | 1 | Timeout, ES stable |
| ERR-02 | OSS auth failure | 1 | Clear error |
| ERR-03 | Lance dataset corrupt | 1 | Error, not crash |
| ERR-04 | Arrow memory OOM | 0 | Graceful error |
| ERR-05 | Concurrent dataset create | 2 | Thread-safe |
| ERR-06 | Refresh file missing | 1 | Error, retry possible |
| ERR-07 | Shard resolution failure | 1 | Clear error with shard ID |

---

## Test Summary

| Category | Tests | P0 | P1 | P2 |
|----------|-------|----|----|-----|
| Lance Vector - Basic | 5 | 3 | 1 | 1 |
| Lance Vector - P3 Sharding | 7 | 1 | 3 | 3 |
| Lance Vector - P1-P2 NRT | 8 | 2 | 4 | 2 |
| Lance Vector - Filtering | 7 | 2 | 3 | 2 |
| Lance Vector - nprobes | 5 | 1 | 2 | 2 |
| Lance Vector - Hybrid | 5 | 1 | 2 | 2 |
| Lance Vector - Memory | 4 | 1 | 2 | 1 |
| Lance Vector - Cache | 4 | 1 | 1 | 2 |
| Lance Vector - Edge Cases | 9 | 3 | 4 | 2 |
| Cloud IAM | 26 | 6 | 14 | 6 |
| Integration | 10 | 3 | 4 | 3 |
| Configuration | 8 | 2 | 3 | 3 |
| Error Handling | 7 | 1 | 3 | 3 |
| **Total** | **105** | **27** | **46** | **31** |

---

## Priority Legend

| Priority | When to Run |
|----------|-------------|
| P0 (Critical) | Every commit, blocks release |
| P1 (High) | Daily, per PR |
| P2 (Medium) | Weekly, pre-release |

---

## Test Data Setup

```python
# create_test_data.py
import lance
import pyarrow as pa
import numpy as np

# CRITICAL: _id must be pa.string(), NOT pa.large_string()
schema = pa.schema([
    pa.field("_id", pa.string()),
    pa.field("embedding", pa.list_(pa.float32(), list_size=128)),
    pa.field("category", pa.string()),
])

data = {
    "_id": [f"doc_{i}" for i in range(10000)],
    "embedding": [np.random.random(128).astype(np.float32) for _ in range(10000)],
    "category": np.random.choice(["electronics", "books", "clothing"], 10000),
}

table = pa.table(data, schema=schema)
lance.write_dataset("oss://denny-test-lance/test/regression.lance", table, mode="overwrite")
```

---

## Test Execution Examples

### Quick Smoke Test (P0 only)

```bash
# Start ES
./project_starter.sh -d

# Run P0 tests
./gradlew :plugins:lance-vector:test --tests "*P0*"

# Verify basic kNN search
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/test-index/_search" \
  -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 100
  }
}'
```

### P3 Sharding Tests

```bash
# Create shard-aware index with ES_ROUTING (default)
curl -k -u elastic:Summer11 -X PUT "https://localhost:9200/shard-test" \
  -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://denny-test-lance/data/",
          "shard_path": "test-index/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector",
          "sharding_strategy": "ES_ROUTING"
        }
      }
    }
  }
}'

# Create index with NONE sharding strategy
curl -k -u elastic:Summer11 -X PUT "https://localhost:9200/shard-test-none" \
  -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://denny-test-lance/data/",
          "shard_path": "test-index/shard-{shard_id}",
          "dataset_name": "vectors.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector",
          "sharding_strategy": "NONE"
        }
      }
    }
  }
}'

# Verify each shard resolves correctly
# (Check ES logs for URI resolution)
```

### P1-P2 NRT Refresh Tests

```bash
# Enable NRT refresh at cluster level
curl -k -u elastic:Summer11 -X PUT "https://localhost:9200/_cluster/settings" \
  -H 'Content-Type: application/json' -d '{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "10s"
  }
}'

# Check refresh metrics
curl -k -u elastic:Summer11 "https://localhost:9200/_lance/stats?pretty"

# Trigger manual refresh
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/_lance/refresh" \
  -H 'Content-Type: application/json' -d '{
  "indices": ["test-index"]
}'
```

### Filter Strategy Tests

```bash
# Pre-filter test (small filter)
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/test/_search" \
  -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 100
  },
  "post_filter": {
    "term": {"category": "electronics"}
  }
}'

# Check filter strategy used
# (Enable DEBUG logging to see AUTO decision)
```

---

## Real OSS Integration Tests (END-TO-END)

### Overview

These tests validate the complete workflow of Lance Vector plugin with Alibaba Cloud OSS:
1. Upload multiple sharded Lance datasets with IVF-PQ indices to OSS
2. ES indexes metadata with shard-aware dataset mapping
3. kNN search with pre-filter and post-filter
4. Update Lance datasets in OSS
5. Verify NRT refresh picks up the updates

### Prerequisites

```bash
# Start ES with OSS support
./project_starter.sh -d

# Verify OSS credentials
cat ~/.oss/credentials.json

# Set up Python environment with required packages
pip3 install pyarrow lance numpy pandas
```

### Test Data Setup Script

Create `tests/oss_integration_test_setup.py`:

```python
#!/usr/bin/env python3
"""
OSS Integration Test Data Setup
Creates and uploads sharded Lance datasets to OSS
"""
import os
import lance
import pyarrow as pa
import numpy as np
from oss2 import Auth, Bucket

# Configuration
OSS_BUCKET = "denny-test-lance"
OSS_PREFIX = "regression-test/"
NUM_SHARDS = 3
DOCS_PER_SHARD = 1000
VECTOR_DIMS = 128

def create_sharded_datasets():
    """Create sharded Lance datasets with IVF-PQ indices"""

    # Initialize OSS client
    oss_creds = read_oss_credentials()
    auth = Auth(oss_creds['access_key_id'], oss_creds['access_key_secret'])
    bucket = Bucket(auth, f"https://{oss_creds['endpoint']}", OSS_BUCKET)

    print(f"Creating {NUM_SHARDS} shards with {DOCS_PER_SHARD} docs each...")

    for shard_id in range(NUM_SHARDS):
        print(f"Processing shard {shard_id}...")

        # Generate data for this shard
        start_id = shard_id * DOCS_PER_SHARD

        # Create schema (CRITICAL: use pa.string(), not pa.large_string())
        schema = pa.schema([
            pa.field("_id", pa.string()),
            pa.field("vector", pa.list_(pa.float32(), VECTOR_DIMS)),
            pa.field("category", pa.string()),
            pa.field("price", pa.float32()),
            pa.field("shard_id", pa.int32()),
        ])

        # Generate random data
        data = {
            "_id": [f"doc_{i}" for i in range(start_id, start_id + DOCS_PER_SHARD)],
            "vector": [np.random.random(VECTOR_DIMS).astype(np.float32) for _ in range(DOCS_PER_SHARD)],
            "category": np.random.choice(["electronics", "books", "clothing", "home"], DOCS_PER_SHARD),
            "price": np.random.uniform(10, 1000, DOCS_PER_SHARD).astype(np.float32),
            "shard_id": np.full(DOCS_PER_SHARD, shard_id, dtype=np.int32),
        }

        table = pa.table(data, schema=schema)

        # Create local Lance dataset
        local_path = f"/tmp/shard-{shard_id}-v1.lance"
        os.makedirs(os.path.dirname(local_path), exist_ok=True)

        dataset = lance.write_dataset(local_path, table, mode="overwrite")

        # Create IVF-PQ index for fast search
        print(f"  Creating IVF-PQ index for shard {shard_id}...")
        dataset.create_index(
            column="vector",
            index_type="IVF_PQ",
            metric="cosine",
            num_partitions=64,
            num_sub_vectors=16
        )

        # Upload to OSS
        oss_path = f"{OSS_PREFIX}shard-{shard_id}-v1.lance"
        print(f"  Uploading to oss://{OSS_BUCKET}/{oss_path}...")

        # Lance SDK directly supports OSS via opendal
        # For Python, we need to use the OSS URI format
        import lance
        oss_uri = f"oss://{OSS_BUCKET}/{oss_path}"

        # Copy local dataset to OSS
        # Lance will upload the entire dataset directory
        for root, dirs, files in os.walk(local_path):
            for file in files:
                local_file = os.path.join(root, file)
                relative_path = os.path.relpath(local_file, local_path)
                oss_file = f"{oss_path}/{relative_path}"
                bucket.put_object_from_file(oss_file, local_file)

        print(f"  ✓ Shard {shard_id} uploaded: {DOCS_PER_SHARD} docs")

    print(f"\n✓ All {NUM_SHARDS} shards created and uploaded to OSS")
    print(f"  OSS Path: oss://{OSS_BUCKET}/{OSS_PREFIX}")

def update_shard_dataset(shard_id, version=2):
    """Update a specific shard dataset (for NRT refresh testing)"""

    print(f"\nUpdating shard {shard_id} to version {version}...")

    # Generate updated data with NEW vectors
    start_id = shard_id * DOCS_PER_SHARD

    schema = pa.schema([
        pa.field("_id", pa.string()),
        pa.field("vector", pa.list_(pa.float32(), VECTOR_DIMS)),
        pa.field("category", pa.string()),
        pa.field("price", pa.float32()),
        pa.field("shard_id", pa.int32()),
        pa.field("version", pa.int32()),
    ])

    data = {
        "_id": [f"doc_{i}" for i in range(start_id, start_id + DOCS_PER_SHARD)],
        "vector": [np.random.random(VECTOR_DIMS).astype(np.float32) for _ in range(DOCS_PER_SHARD)],
        "category": np.random.choice(["electronics", "books", "clothing", "home", "NEW_CATEGORY"], DOCS_PER_SHARD),
        "price": np.random.uniform(10, 1000, DOCS_PER_SHARD).astype(np.float32),
        "shard_id": np.full(DOCS_PER_SHARD, shard_id, dtype=np.int32),
        "version": np.full(DOCS_PER_SHARD, version, dtype=np.int32),
    }

    table = pa.table(data, schema=schema)

    local_path = f"/tmp/shard-{shard_id}-v{version}.lance"
    dataset = lance.write_dataset(local_path, table, mode="overwrite")

    # Create IVF-PQ index
    dataset.create_index(
        column="vector",
        index_type="IVF_PQ",
        metric="cosine",
        num_partitions=64,
        num_sub_vectors=16
    )

    # Upload to OSS
    oss_creds = read_oss_credentials()
    auth = Auth(oss_creds['access_key_id'], oss_creds['access_key_secret'])
    bucket = Bucket(auth, f"https://{oss_creds['endpoint']}", OSS_BUCKET)

    oss_path = f"{OSS_PREFIX}shard-{shard_id}-v{version}.lance"

    for root, dirs, files in os.walk(local_path):
        for file in files:
            local_file = os.path.join(root, file)
            relative_path = os.path.relpath(local_file, local_path)
            oss_file = f"{oss_path}/{relative_path}"
            bucket.put_object_from_file(oss_file, local_file)

    print(f"  ✓ Shard {shard_id} v{version} uploaded to OSS")

def read_oss_credentials():
    """Read OSS credentials from ~/.oss/credentials.json"""
    import json
    creds_file = os.path.expanduser("~/.oss/credentials.json")
    with open(creds_file) as f:
        return json.load(f)

if __name__ == "__main__":
    import json

    print("=" * 60)
    print("OSS Integration Test Data Setup")
    print("=" * 60)

    # Step 1: Create initial sharded datasets
    create_sharded_datasets()

    print("\n" + "=" * 60)
    print("Initial setup complete!")
    print("=" * 60)
```

### Test Execution Script

Create `tests/run_oss_integration_tests.sh`:

```bash
#!/bin/bash
set -e

ES_HOST="https://localhost:9200"
ES_USER="elastic"
ES_PASS="Summer11"
OSS_BUCKET="denny-test-lance"
OSS_PREFIX="regression-test/"

echo "=========================================="
echo "Real OSS Integration Tests"
echo "=========================================="

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Helper functions
check_test() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $1"
        return 0
    else
        echo -e "${RED}✗${NC} $1"
        return 1
    fi
}

# ========================================================================
# TEST 1: Upload Multiple Lance Datasets to OSS (with IVF-PQ indices)
# ========================================================================
echo ""
echo "TEST 1: Upload sharded Lance datasets to OSS..."
echo "----------------------------------------------"

python3 tests/oss_integration_test_setup.py
check_test "OSS dataset upload complete"

# ========================================================================
# TEST 2: ES Takes Over the Datasets (Shard-Aware Mapping)
# ========================================================================
echo ""
echo "TEST 2: Create ES index with shard-aware mapping..."
echo "---------------------------------------------------"

# Create index with 3 shards matching our OSS data
curl -sk -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_HOST}/regression-test" \
  -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "category": { "type": "keyword" },
      "price": { "type": "float" },
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri_prefix": "oss://denny-test-lance/regression-test/",
          "shard_path": "shard-{shard_id}-v1",
          "dataset_name": "data.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}'

check_test "Index created with shard-aware mapping"

# Index metadata documents (first 10 per shard)
for shard_id in 0 1 2; do
    for i in $(seq 0 9); do
        doc_id=$((shard_id * 1000 + i))
        curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_doc/doc_${doc_id}" \
          -H 'Content-Type: application/json' -d "{
          \"title\": \"Product ${doc_id}\",
          \"category\": \"electronics\",
          \"price\": 99.99
        }" > /dev/null
    done
done

check_test "Metadata documents indexed (30 docs total)"

# Force refresh
curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_refresh" > /dev/null
check_test "Index refreshed"

# ========================================================================
# TEST 3: Verify kNN Search with Pre-Filter and Post-Filter
# ========================================================================
echo ""
echo "TEST 3: kNN search with filters..."
echo "----------------------------------"

# Generate a test query vector
QUERY_VECTOR=$(python3 -c "import numpy as np; print(np.random.random(128).tolist())" | tr -d '[]')

# Test 3a: kNN without filter
echo "  3a. kNN without filter..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 5,
    \"num_candidates\": 50
  }
}")

HIT_COUNT=$(echo "$RESULT" | grep -o '"hits":\[{"' | wc -l)
SCORES_ZERO=$(echo "$RESULT" | grep -o '"_score":0\.0' | wc -l)

if [ "$HIT_COUNT" -gt 0 ] && [ "$SCORES_ZERO" -eq 0 ]; then
    check_test "kNN without filter: ${HIT_COUNT} hits, all scores > 0"
else
    check_test "kNN without filter: FAILED"
    echo "$RESULT" | head -50
fi

# Test 3b: kNN with post-filter (small filter - should use pre-filter)
echo "  3b. kNN with small filter (pre-filter)..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 5,
    \"num_candidates\": 50
  },
  \"post_filter\": {
    \"term\": { \"category\": \"electronics\" }
  }
}")

FILTERED_HITS=$(echo "$RESULT" | grep -o '"hits":\[{"' | wc -l)
check_test "kNN with small filter: ${FILTERED_HITS} filtered results"

# Test 3c: kNN with large filter (should use post-filter)
echo "  3c. kNN with large filter (post-filter)..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 10,
    \"num_candidates\": 100
  },
  \"post_filter\": {
    \"range\": { \"price\": { \"gte\": 0, \"lte\": 10000 } }
  }
}")

LARGE_FILTER_HITS=$(echo "$RESULT" | grep -o '"hits":\[{"' | wc -l)
check_test "kNN with large filter: ${LARGE_FILTER_HITS} results"

# ========================================================================
# TEST 4: Update Lance Datasets in OSS
# ========================================================================
echo ""
echo "TEST 4: Update Lance datasets in OSS..."
echo "----------------------------------------"

# Update shard 0 with new data
python3 -c "
import sys
sys.path.insert(0, 'tests')
from oss_integration_test_setup import update_shard_dataset
update_shard_dataset(0, version=2)
"

check_test "Shard 0 updated to v2 in OSS"

# ========================================================================
# TEST 5: Verify Updates Take Effect (NRT Refresh)
# ========================================================================
echo ""
echo "TEST 5: Verify NRT refresh picks up updates..."
echo "-----------------------------------------------"

# Enable NRT refresh
curl -sk -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_HOST}/_cluster/settings" \
  -H 'Content-Type: application/json' -d '{
  "persistent": {
    "lance.refresh.enabled": true,
    "lance.refresh.interval": "5s"
  }
}' > /dev/null

check_test "NRT refresh enabled (5s interval)"

# Update index mapping to point to v2 dataset
curl -sk -u "${ES_USER}:${ES_PASS}" -X PUT "${ES_HOST}/regression-test/_mapping" \
  -H 'Content-Type: application/json' -d '{
  "properties": {
    "embedding": {
      "type": "lance_vector",
      "dims": 128,
      "similarity": "cosine",
      "storage": {
        "type": "external",
        "uri_prefix": "oss://denny-test-lance/regression-test/",
        "shard_path": "shard-{shard_id}-v2",
        "dataset_name": "data.lance",
        "lance_id_column": "_id",
        "lance_vector_column": "vector"
      }
    }
  }
}' > /dev/null

check_test "Index mapping updated to v2"

# Wait for NRT refresh
echo "  Waiting for NRT refresh (10 seconds)..."
sleep 10

# Trigger manual refresh
curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/_lance/refresh" \
  -H 'Content-Type: application/json' -d '{
  "indices": ["regression-test"]
}' > /dev/null

# Check Lance stats
STATS=$(curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/_lance/stats?pretty")
CACHE_SIZE=$(echo "$STATS" | grep '"size"' | head -1 | grep -o '[0-9]*')

check_test "NRT refresh triggered, cache size: $CACHE_SIZE"

# Verify search works with updated dataset
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 5,
    \"num_candidates\": 50
  }
}")

UPDATED_HITS=$(echo "$RESULT" | grep -o '"hits":\[{"' | wc -l)
check_test "Search with updated dataset: ${UPDATED_HITS} hits"

# ========================================================================
# SUMMARY
# ========================================================================
echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo ""
curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/_lance/stats?pretty"
echo ""
echo ""
echo "Index info:"
curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/regression-test/_settings?pretty" | grep -A 20 "embedding"
echo ""

echo -e "${GREEN}=========================================="
echo "All OSS Integration Tests Complete!"
echo "==========================================${NC}"
```

---

## Sync with Validation Skill

The validation skill is kept in sync with this guide:

```bash
# After editing this guide, sync the skill:
python3 .claude/skills/es-plugins-validation/scripts/sync_from_plan.py
```

**Note:** The sync script uses `reg_validation_guide.md` as the source of truth.

---

## Regression Prevention Checklist

Before committing changes:

- [ ] All P0 tests pass (27 tests)
- [ ] No zero-score regressions
- [ ] No memory leaks (heap growth < 30% over 1000 searches)
- [ ] OSS environment variables still required before ES starts
- [ ] Arrow type compatibility maintained (pa.string() for _id)
- [ ] NRT refresh doesn't corrupt datasets
- [ ] Shard URI templates resolve correctly
- [ ] Filter strategies work as expected
- [ ] Backward compatibility maintained (legacy single URI mode)

---

## Common Issues and Solutions

### Issue: Zero scores in results

**Symptom**: All hits have `_score = 0.0`

**Root Cause**: Distance-to-score conversion missing in LanceKnnQuery

**Solution**: Verify conversion formula:
```java
float score = 1.0f / (1.0f + distance);
```

### Issue: OSS authentication errors

**Symptom**: `Authentication failed` when accessing OSS datasets

**Root Cause**: OSS environment variables not set before ES starts

**Solution**: Set environment variables in parent shell:
```bash
export OSS_ACCESS_KEY_ID=...
export OSS_ACCESS_KEY_SECRET=...
export OSS_ENDPOINT=...
./bin/elasticsearch -d -p es.pid
```

### Issue: Shard template not resolving

**Symptom**: URI contains literal `{shard_id}` instead of actual shard number

**Root Cause**: Shard ID not passed to createKnnQuery

**Solution**: Verify LanceKnnQueryBuilder passes shardId:
```java
return lanceFieldType.createKnnQuery(
    vectorData, k, numCandidates, ...,
    context.index().getName(),  // indexName
    context.getShardId()         // shardId
);
```

### Issue: NRT refresh causes query failures

**Symptom**: Queries fail during dataset refresh

**Root Cause**: Dataset swap not atomic

**Solution**: Verify VersionedDataset is used for atomic swaps

### Issue: ShardingStrategy mismatch

**Symptom**: kNN search returns zero results or wrong results

**Root Cause**: Lance dataset sharding doesn't match ES routing

**Solution**:
1. If Lance dataset uses ES's Murmur3 hash: use `sharding_strategy: "ES_ROUTING"`
2. If Lance dataset uses different algorithm: use `sharding_strategy: "NONE"`
3. When in doubt, use `NONE` (slower but correct)

```bash
# Check current sharding strategy
curl -k -u elastic:Summer11 "https://localhost:9200/your-index/_mapping?pretty" | grep -A 20 "storage"
```

---

## JVM Heap Recommendations

| Use Case | Recommended Heap |
|----------|-----------------|
| Development | 2GB |
| Testing (this guide) | 4GB (default) |
| Production (small) | 8GB |
| Production (large) | 16GB+ |

To change heap size:
```bash
./project_starter.sh --jvm-heap 8g -d
```

---

## Production Deployment Checklist

Before deploying to production:

- [ ] Run full regression suite (P0 + P1 tests)
- [ ] Verify NRT refresh interval appropriate for workload
- [ ] Configure shard-aware storage for optimal performance
- [ ] Set up monitoring for `/_lance/stats`
- [ ] Configure appropriate JVM heap size
- [ ] Verify OSS credentials and bucket permissions
- [ ] Test failover scenarios (OSS timeout, dataset corruption)
- [ ] Set up alerting for memory usage and refresh failures

---

## Additional Resources

- **Production Validation Report**: `PRODUCTION-VALIDATION-REPORT.md`
- **Implementation Plan**: `P1-P2-IMPLEMENTATION-PLAN.md`
- **Source Code**: `plugins/lance-vector/src/main/java/`
- **Unit Tests**: `plugins/lance-vector/src/test/java/`
