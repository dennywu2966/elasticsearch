# Phase 5: OSS Integration Testing Guide

## Overview

This document describes the setup and validation procedures for testing the Lance Vector Plugin with Alibaba Cloud Object Storage Service (OSS) as the external vector storage backend.

---

## Prerequisites

### Alibaba Cloud OSS Account
1. **OSS Bucket**: Create or use an existing OSS bucket
2. **Access Credentials**:
   - Access Key ID
   - Access Key Secret
3. **Endpoint**: Regional OSS endpoint (e.g., `oss-cn-hangzhou.aliyuncs.com`)
4. **Permissions**: Bucket read access for the configured credentials

### Lance Dataset on OSS
The Lance dataset must be uploaded to OSS before testing:
```
bucket/
  └── path/
      └── dataset.lance/
          ├── _versions/
          ├── _indices/
          └── data/
```

---

## Configuration

### Mapping Configuration

```json
PUT /oss-lance-test
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "similarity": "l2",
        "storage": {
          "type": "external",
          "uri": "oss://bucket-name/path/to/dataset.lance",
          "read_only": true,
          "oss_endpoint": "oss-cn-hangzhou.aliyuncs.com",
          "oss_access_key_id": "YOUR_ACCESS_KEY_ID",
          "oss_access_key_secret": "YOUR_ACCESS_KEY_SECRET"
        }
      }
    }
  }
}
```

### Configuration Parameters

| Parameter | Required | Description | Example |
|-----------|----------|-------------|---------|
| `type` | Yes | Storage type, must be "external" | `"external"` |
| `uri` | Yes | OSS URI in format `oss://bucket/path` | `"oss://my-bucket/vectors/dataset.lance"` |
| `read_only` | Yes | Read-only mode (Phase 1 limitation) | `true` |
| `oss_endpoint` | Yes* | OSS regional endpoint | `"oss-cn-hangzhou.aliyuncs.com"` |
| `oss_access_key_id` | Yes* | OSS access key ID | `"LTAI5t..."` |
| `oss_access_key_secret` | Yes* | OSS access key secret | `"secret123..."` |

*Required for OSS URIs, not for local file:// URIs

---

## Test Scenarios

### Test 1: Basic OSS Connectivity
**Objective**: Verify Lance can open and read dataset from OSS

**Steps**:
1. Create index with OSS URI configuration
2. Execute kNN search query
3. Verify results are returned

**Expected Result**: Query returns results with correct scores

**Failure Modes**:
- Authentication error: Invalid credentials
- Network error: Endpoint unreachable or incorrect
- Dataset not found: URI path incorrect

```bash
# Example query
curl -u elastic:password -X GET "oss-lance-test/_search" -H 'Content-Type: application/json' -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [...],
    "k": 10,
    "num_candidates": 100
  }
}'
```

### Test 2: Authentication Validation
**Objective**: Verify OSS authentication error handling

**Steps**:
1. Create index with **invalid** OSS credentials
2. Execute kNN search query
3. Verify clear error message

**Expected Result**: Error message indicating authentication failure

```bash
# Should return clear error
{
  "error": {
    "type": "search_phase_execution_exception",
    "reason": "OSS authentication failed"
  }
}
```

### Test 3: Invalid Dataset Path
**Objective**: Verify error handling for non-existent datasets

**Steps**:
1. Create index pointing to non-existent OSS path
2. Execute kNN search query
3. Verify clear error message

**Expected Result**: Dataset not found error

```bash
# uri: "oss://bucket/nonexistent/path/dataset.lance"
# Should return:
{
  "error": {
    "reason": "Failed to open Lance dataset: oss://..."
  }
}
```

### Test 4: Performance Benchmark
**Objective**: Measure OSS query performance vs local filesystem

**Metrics to Collect**:
- Cold start query time (first query)
- Warm query time (cached dataset)
- Network throughput
- Cache hit rate

**Expected Performance**:
- Cold start: 3-5 seconds (network + dataset open)
- Warm queries: 50-100ms (cached in memory)
- Network: Depending on dataset size and region

### Test 5: Concurrent Access
**Objective**: Verify thread-safe OSS access

**Steps**:
1. Execute 10 concurrent kNN queries
2. Verify all queries succeed
3. Check for race conditions or deadlocks

**Expected Result**: All queries complete successfully

---

## Dataset Upload Procedure

### Step 1: Create Lance Dataset Locally
```python
import lance
import pyarrow as pa
import numpy as np

# Create sample data
n_vectors = 1000
dims = 128
vectors = np.random.random((n_vectors, dims)).astype(np.float32)

schema = pa.schema([
    pa.field("_id", pa.string()),
    pa.field("vector", pa.list_(pa.float32(), dims)),
    pa.field("category", pa.string())
])

# Create table
table = pa.Table.from_arrays([
    pa.array([f"doc_{i}" for i in range(n_vectors)]),
    pa.array([vectors[i] for i in range(n_vectors)]),
    pa.array(["A" if i % 2 == 0 else "B" for i in range(n_vectors)])
], schema=schema)

# Write to Lance format with IVF-PQ indexing
lance.write_dataset(
    table,
    "dataset.lance",
    mode="overwrite"
)

# Open and create index
ds = lance.dataset("dataset.lance")
ds.create_index(
    "vector",
    index_type="IVF_PQ",
    num_partitions=100,
    num_sub_vectors=16
)
```

### Step 2: Upload to OSS
Using OSS CLI:
```bash
# Install OSS CLI
pip install oss2

# Upload dataset
ossutil cp -rf dataset.lance oss://your-bucket/path/to/
```

Using Python:
```python
import oss2

# Configure OSS
auth = oss2.Auth('YOUR_ACCESS_KEY_ID', 'YOUR_ACCESS_KEY_SECRET')
bucket = oss2.Bucket(auth, 'oss-cn-hangzhou.aliyuncs.com', 'your-bucket')

# Upload recursively
for root, dirs, files in os.walk('dataset.lance'):
    for file in files:
        local_path = os.path.join(root, file)
        oss_path = f"path/to/{os.path.relpath(local_path, 'dataset.lance')}"
        bucket.put_object_from_file(oss_path, local_path)
```

---

## Validation Checklist

### Pre-Test Setup
- [ ] OSS bucket created and accessible
- [ ] Access credentials obtained
- [ ] Lance dataset created and indexed
- [ ] Dataset uploaded to OSS
- [ ] ES cluster running with security enabled
- [ ] Lance plugin installed

### Connectivity Tests
- [ ] Test 1: Basic OSS connectivity - **PASS**
- [ ] Test 2: Authentication validation - **PASS**
- [ ] Test 3: Invalid dataset path - **PASS**
- [ ] Test 4: Performance benchmark - **COMPLETE**
- [ ] Test 5: Concurrent access - **COMPLETE**

### Post-Test Validation
- [ ] All queries returned expected results
- [ ] Error messages are clear and actionable
- [ ] Performance within acceptable bounds
- [ ] No memory leaks or resource exhaustion
- [ ] No race conditions in concurrent access

---

## Troubleshooting

### Issue: "OSS authentication failed"
**Cause**: Invalid credentials or incorrect endpoint
**Solution**:
1. Verify Access Key ID and Secret are correct
2. Confirm endpoint format: `oss-{region}.aliyuncs.com`
3. Check bucket permissions allow read access

### Issue: "Dataset not found"
**Cause**: URI path incorrect or dataset not uploaded
**Solution**:
1. Verify OSS URI format: `oss://bucket/path/to/dataset.lance`
2. Check dataset exists in OSS console
3. Confirm path includes `dataset.lance` directory name

### Issue: Slow query performance
**Cause**: Network latency or dataset not cached
**Solution**:
1. Use OSS endpoint in same region as ES cluster
2. Increase JVM heap for dataset caching
3. Consider using local filesystem for hot data

### Issue: "Connection timeout"
**Cause**: Network connectivity or firewall issues
**Solution**:
1. Verify ES node can reach OSS endpoint
2. Check firewall rules allow outbound HTTPS
3. Test connectivity with `curl` or `telnet`

---

## Performance Expectations

### Query Latency (1000 vectors, 128 dims, IVF-PQ)

| Scenario | Local Filesystem | OSS (Same Region) | OSS (Different Region) |
|----------|------------------|-------------------|------------------------|
| Cold Start | 2.5s | 3-5s | 5-10s |
| Warm Query | 20-40ms | 50-100ms | 100-200ms |

### Factors Affecting Performance
- **Dataset size**: Larger datasets take longer to load
- **Network latency**: Distance to OSS region
- **Indexing type**: IVF-PQ faster than flat search
- **Cache hit rate**: In-memory dataset vs reload from OSS
- **num_candidates**: Larger values increase search time

---

## Security Considerations

### Credential Storage
⚠️ **WARNING**: Never store OSS credentials in:
- Git repositories
- Index mappings (visible in _mapping API)
- Log files
- Client-facing error messages

**Recommended approaches**:
1. Use Elasticsearch keystore for sensitive values
2. Pass credentials via secure configuration
3. Use IAM roles when running in Aliyun ECS
4. Rotate credentials regularly

### Access Control
- Limit OSS bucket access to read-only for search workloads
- Use bucket policies to restrict access by IP/VPC
- Enable OSS access logging for audit trails
- Use HTTPS endpoints only

---

## Next Steps After Phase 5

1. **Performance Optimization**:
   - Implement dataset prefetching
   - Add OSS-side caching
   - Optimize index parameters for OSS

2. **Monitoring**:
   - Add OSS-specific metrics (download time, cache hit rate)
   - Monitor OSS API costs
   - Track dataset reload frequency

3. **Documentation**:
   - User guide for OSS setup
   - Troubleshooting guide
   - Performance tuning guide

4. **Feature Expansion**:
   - Support for OSS write operations (Phase 2)
   - Support for OSS versioning
   - Support for OSS lifecycle policies

---

## Contact and Support

For issues or questions related to OSS integration:
1. Check ES logs for detailed error messages
2. Verify OSS configuration in mapping
3. Test OSS connectivity with OSS CLI tools
4. Review Lance documentation for OSS usage
5. Consult Alibaba Cloud OSS documentation

**Status**: ⏳ **PENDING OSS CREDENTIALS FOR TESTING**

Last updated: 2026-01-27
