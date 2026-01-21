# Lance Vector OSS POC Demo Instructions

This guide provides step-by-step instructions to set up and run a proof-of-concept demo of the Lance Vector integration with Alibaba Cloud OSS storage.

## Prerequisites

### 1. Environment Requirements
- Java 21 (OpenJDK or Oracle JDK)
- Elasticsearch source code with lance-vector plugin
- Alibaba Cloud OSS bucket with public read access (for demo)
- OSS Access Key and Secret Key

### 2. OSS Bucket Setup
1. Create an OSS bucket in Alibaba Cloud console
2. Set bucket permissions to allow public read access (for demo only)
3. Note the bucket name, region, and endpoint

## Setup Instructions

### Step 1: Configure OSS Credentials

Create a credentials file at `~/.oss/credentials.json`:

```bash
mkdir -p ~/.oss
cat > ~/.oss/credentials.json << 'EOF'
{
  "access_key_id": "your-access-key-here",
  "access_key_secret": "your-secret-key-here", 
  "endpoint": "oss-cn-hangzhou.aliyuncs.com",
  "region": "cn-hangzhou"
}
EOF

# Set appropriate permissions
chmod 600 ~/.oss/credentials.json
```

**Credential File Format:**
- `access_key_id`: Your OSS Access Key ID
- `access_key_secret`: Your OSS Access Key Secret  
- `endpoint`: OSS endpoint for your region (e.g., `oss-cn-hangzhou.aliyuncs.com`)
- `region`: OSS region code (e.g., `cn-hangzhou`)

### Step 2: Prepare Dataset in OSS

Upload a test vector dataset to your OSS bucket. Create a JSON file with the following format:

```json
[
  { "id": "doc1", "vector": [0.1, 0.2, 0.3, 0.4, 0.5] },
  { "id": "doc2", "vector": [0.2, 0.1, 0.3, 0.5, 0.4] },
  { "id": "doc3", "vector": [0.9, 0.1, 0.0, 0.1, 0.2] },
  { "id": "doc4", "vector": [0.3, 0.8, 0.2, 0.1, 0.4] },
  { "id": "doc5", "vector": [0.1, 0.9, 0.1, 0.3, 0.2] }
]
```

Upload this file to your OSS bucket at a path like `datasets/embeddings.json`.

**Upload via OSS Console:**
1. Go to OSS Console → Your Bucket → Files
2. Create folder `datasets/`
3. Upload the JSON file as `embeddings.json`
4. Verify the file is accessible

**Upload via OSS CLI (ossutil):**
```bash
# Install ossutil and configure with your credentials
ossutil cp embeddings.json oss://your-bucket-name/datasets/embeddings.json
```

### Step 3: Build and Start Elasticsearch

```bash
# Build the lance-vector plugin
./gradlew :plugins:lance-vector:build

# Start Elasticsearch with the plugin
./gradlew run
```

**Note:** If build fails due to environment issues, refer to the manual validation approach in `docs/phase1-validation.md`.

### Step 4: Create Index with OSS Storage

Create an Elasticsearch index that uses the OSS dataset:

```bash
# Create index with lance_vector field pointing to OSS
curl -X PUT "localhost:9200/oss-vectors" -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "title": {
        "type": "text"
      },
      "category": {
        "type": "keyword"
      },
      "embedding": {
        "type": "lance_vector",
        "dims": 5,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri": "oss://your-bucket-name/datasets/embeddings.json",
          "lance_id_column": "id",
          "lance_vector_column": "vector",
          "read_only": true
        }
      }
    }
  }
}'
```

**Replace `your-bucket-name` with your actual OSS bucket name.**

### Step 5: Index Metadata Documents

Index metadata documents in Elasticsearch (these provide the document content for search results):

```bash
# Index documents with same IDs as in the OSS dataset
curl -X POST "localhost:9200/oss-vectors/_doc/doc1" -H "Content-Type: application/json" -d '{
  "title": "Machine Learning Basics",
  "category": "tech"
}'

curl -X POST "localhost:9200/oss-vectors/_doc/doc2" -H "Content-Type: application/json" -d '{
  "title": "Deep Learning Fundamentals", 
  "category": "tech"
}'

curl -X POST "localhost:9200/oss-vectors/_doc/doc3" -H "Content-Type: application/json" -d '{
  "title": "Quantum Physics Introduction",
  "category": "science"
}'

curl -X POST "localhost:9200/oss-vectors/_doc/doc4" -H "Content-Type: application/json" -d '{
  "title": "Data Science Methods",
  "category": "tech"
}'

curl -X POST "localhost:9200/oss-vectors/_doc/doc5" -H "Content-Type: application/json" -d '{
  "title": "Astrophysics Research",
  "category": "science"
}'

# Refresh index to make documents searchable
curl -X POST "localhost:9200/oss-vectors/_refresh"
```

### Step 6: Test kNN Search

Perform vector similarity searches using the OSS-stored embeddings:

#### Basic kNN Search
```bash
curl -X POST "localhost:9200/oss-vectors/_search" -H "Content-Type: application/json" -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.9, 0.1, 0.0, 0.1, 0.2],
    "k": 3,
    "num_candidates": 5
  }
}'
```

#### kNN with Filter
```bash
curl -X POST "localhost:9200/oss-vectors/_search" -H "Content-Type: application/json" -d '{
  "knn": {
    "field": "embedding", 
    "query_vector": [0.1, 0.9, 0.1, 0.3, 0.2],
    "k": 2,
    "num_candidates": 5
  },
  "query": {
    "term": {
      "category": "tech"
    }
  }
}'
```

#### Hybrid Search (kNN + text query)
```bash
curl -X POST "localhost:9200/oss-vectors/_search" -H "Content-Type: application/json" -d '{
  "knn": {
    "field": "embedding",
    "query_vector": [0.2, 0.1, 0.3, 0.5, 0.4],
    "k": 3,
    "num_candidates": 5
  },
  "query": {
    "bool": {
      "should": [
        {
          "match": {
            "title": "learning"
          }
        }
      ]
    }
  }
}'
```

## Expected Results

### Successful Index Creation
- Index creation should complete without errors
- Check index health: `curl "localhost:9200/_cluster/health/oss-vectors"`

### Successful kNN Queries  
- Queries should return hits with `_score` based on vector similarity
- Results should include document `_source` from Elasticsearch
- Vectors are retrieved from OSS and joined to ES documents by `_id`

### Query Response Format
```json
{
  "hits": {
    "total": { "value": 2, "relation": "eq" },
    "hits": [
      {
        "_id": "doc3",
        "_score": 0.9234,
        "_source": {
          "title": "Quantum Physics Introduction",
          "category": "science"
        }
      },
      {
        "_id": "doc1", 
        "_score": 0.7891,
        "_source": {
          "title": "Machine Learning Basics",
          "category": "tech"
        }
      }
    ]
  }
}
```

## Troubleshooting

### Common Issues

#### 1. Credentials Not Found
**Error:** `OSS credentials file not found`

**Solution:** Ensure `~/.oss/credentials.json` exists with correct format and permissions.

#### 2. OSS Access Denied
**Error:** `Failed to read OSS object: HTTP response code: 403`

**Solutions:**
- Verify Access Key/Secret Key are correct
- Check bucket permissions allow public read access
- Verify endpoint matches your bucket's region

#### 3. Dataset Not Found
**Error:** `Failed to read OSS object: HTTP response code: 404`

**Solutions:**
- Verify bucket name and object path in URI
- Check the file exists in OSS console
- Ensure object key doesn't have extra leading slashes

#### 4. Empty Search Results
**Possible causes:**
- ES documents and OSS dataset have mismatched IDs
- Dataset file is malformed JSON
- Vector dimensions don't match mapping

### Debug Steps

1. **Check index mapping:**
   ```bash
   curl "localhost:9200/oss-vectors/_mapping"
   ```

2. **Verify document count:**
   ```bash
   curl "localhost:9200/oss-vectors/_count"
   ```

3. **Test dataset file directly:**
   Download and verify your OSS JSON file is properly formatted.

4. **Check ES logs:**
   Look for lance-vector plugin logs in Elasticsearch output.

## Production Considerations

### Security
- **Do not use public read buckets in production**
- Use proper OSS RAM policies with least-privilege access
- Consider VPC endpoints for internal traffic
- Rotate access keys regularly

### Performance
- Place vector datasets in same region as ES cluster
- Use OSS CDN for global deployments
- Monitor OSS request rates and bandwidth
- Consider partitioning large datasets across multiple files

### Monitoring
- Monitor OSS API request success rates
- Track vector query latency metrics
- Set up alerts for credential expiration
- Monitor dataset file changes

## Next Steps

After successful POC:
1. **Implement real Lance SDK integration** (replace FakeLanceDataset)
2. **Add proper OSS authentication** (implement OSS signature v4)
3. **Production security** (remove public bucket access)
4. **Optimize for scale** (implement per-shard dataset partitioning)
5. **Add caching** (local file cache for frequently accessed data)
6. **Monitoring integration** (metrics and alerting)

## Support

For issues specific to this POC:
- Check Elasticsearch logs for plugin errors
- Verify OSS bucket accessibility via web browser
- Test credentials with OSS CLI tools
- Review Phase 1 validation guide: `docs/phase1-validation.md`