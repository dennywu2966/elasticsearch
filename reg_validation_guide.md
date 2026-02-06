# ES Plugins Regression Validation Guide

**Official validation guide for Lance Vector + Cloud IAM plugins**

## Quick Start

```bash
# Start stack
./project_starter.sh -d

# Run P0 critical tests
bash .claude/skills/es-plugins-validation/scripts/run_p0.sh
```

---

## Environment

| Component | Value |
|-----------|-------|
| Startup Script | `./project_starter.sh` (official) |
| ES Distribution | `build/distribution/local/elasticsearch-9.2.4-SNAPSHOT` |
| OSS Bucket | `denny-test-lance` |
| OSS Credentials | `~/.oss/credentials.json` |
| Basic Auth | `elastic / Summer11` |

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

### Filtering (LV-06 to LV-10)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-06 | kNN + term filter | 0 | Filter works AND scores > 0 |
| LV-07 | kNN + range filter | 0 | Numeric range works |
| LV-08 | kNN + bool filter | 1 | Multiple filters |
| LV-09 | kNN + must_not | 1 | Exclusions work |
| LV-10 | Filter matches none | 1 | Empty hits |

### Hybrid Search (LV-11 to LV-15)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-11 | Vector + text RRF | 0 | Both fused |
| LV-12 | 3-way hybrid | 1 | Vector + text + term |
| LV-13 | Hybrid with filters | 1 | RRF + filter |
| LV-14 | Custom RRF constant | 2 | Uses custom value |
| LV-15 | Hybrid empty branch | 1 | Continues with non-empty |

### Memory & Performance (LV-16 to LV-19)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-16 | 1000 searches memory | 0 | Heap growth < 30% |
| LV-17 | 10k searches stress | 1 | No OOM |
| LV-18 | Concurrent requests | 1 | 10 parallel succeed |
| LV-19 | Large dataset (1M) | 2 | Completes < 5s |

### Cache Behavior (LV-20 to LV-23)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-20 | Cache hit (2nd query) | 1 | Faster than cold |
| LV-21 | 105 datasets (LRU) | 0 | First 5 evicted |
| LV-22 | Cache TTL expiration | 2 | 1hr enforced |
| LV-23 | Cache invalidate | 2 | Dataset reloads |

### Edge Cases (LV-24 to LV-32)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| LV-24 | Dimension mismatch | 0 | 400 error |
| LV-25 | Missing dataset_uri | 0 | 400 error |
| LV-26 | Invalid URI scheme | 0 | 400 error |
| LV-27 | OSS bucket 404 | 1 | Clear error |
| LV-28 | Empty Lance dataset | 1 | Empty hits |
| LV-29 | Vector normalization | 2 | Scores 0-1 |
| LV-30 | Negative vector values | 1 | Handles correctly |
| LV-31 | Zero vector query | 1 | Returns or error |
| LV-32 | Malformed OSS URI | 1 | Validates before access |

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

---

## Configuration Tests (CFG)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| CFG-01 | Missing OSS creds | 0 | Error at startup |
| CFG-02 | Invalid OSS endpoint | 1 | Connection error |
| CFG-03 | IAM realm disabled | 1 | Realm inactive |
| CFG-04 | Cache TTL = 0 | 2 | No caching |
| CFG-05 | Max cache = 1 | 2 | Single entry |

---

## Error Handling Tests (ERR)

| ID | Test | P | Success Criteria |
|----|------|---|------------------|
| ERR-01 | OSS timeout | 1 | Timeout, ES stable |
| ERR-02 | OSS auth failure | 1 | Clear error |
| ERR-03 | Lance dataset corrupt | 1 | Error, not crash |
| ERR-04 | Arrow memory OOM | 0 | Graceful error |
| ERR-05 | Concurrent dataset create | 2 | Thread-safe |

---

## Test Summary

| Category | Tests | P0 | P1 | P2 |
|----------|-------|----|----|-----|
| Lance Vector | 32 | 10 | 14 | 8 |
| Cloud IAM | 26 | 6 | 14 | 6 |
| Integration | 7 | 3 | 3 | 1 |
| Configuration | 5 | 1 | 2 | 2 |
| Error Handling | 5 | 1 | 3 | 1 |
| **Total** | **75** | **21** | **38** | **16** |

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

## Sync with Validation Skill

The validation skill is kept in sync with this guide:

```bash
# After editing this guide, sync the skill:
python3 .claude/skills/es-plugins-validation/scripts/sync_from_plan.py
```

**Note:** The sync script uses `reg_validation_guide.md` as the source of truth.
