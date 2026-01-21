# Complete Lance Vector OSS POC Guide

This guide provides every single step needed to run the Lance Vector integration with Alibaba Cloud OSS, from initial setup to querying data.

## Part 1: Environment Setup

### Step 1: Verify Java 21

```bash
# Check Java version
java -version

# Should show OpenJDK 21.x.x
# If not Java 21, install it:
sudo apt update && sudo apt install -y openjdk-21-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' >> ~/.bashrc
```

### Step 2: Set Environment Variables

```bash
# Set project root directory
export ES_HOME=/home/dennywu2966/projects/es-lance-claude
cd $ES_HOME

# Verify we're in the right directory
ls -la | grep gradlew
# Should show: -rwxr-xr-x 1 user user gradlew

# Optional: Add to bashrc for persistence
echo "export ES_HOME=$ES_HOME" >> ~/.bashrc
```

### Step 3: Set OSS Variables (Replace with your bucket details)

```bash
# Set your OSS bucket details
export OSS_BUCKET_NAME="your-bucket-name"
export OSS_REGION="cn-hangzhou"  
export OSS_ENDPOINT="oss-cn-hangzhou.aliyuncs.com"
export OSS_ACCESS_KEY="your-access-key-id"
export OSS_SECRET_KEY="your-access-secret"
export OSS_DATASET_PATH="datasets/lance-vectors.json"

# Full OSS URI for the dataset
export OSS_DATASET_URI="oss://${OSS_BUCKET_NAME}/${OSS_DATASET_PATH}"

echo "OSS Dataset URI: $OSS_DATASET_URI"
```

## Part 2: Configure OSS Credentials

### Step 4: Create OSS Credentials File

```bash
# Create OSS credentials directory
mkdir -p ~/.oss

# Create credentials file with your actual values
cat > ~/.oss/credentials.json << EOF
{
  "access_key_id": "${OSS_ACCESS_KEY}",
  "access_key_secret": "${OSS_SECRET_KEY}",
  "endpoint": "${OSS_ENDPOINT}",
  "region": "${OSS_REGION}"
}
EOF

# Set secure permissions
chmod 600 ~/.oss/credentials.json

# Verify credentials file
echo "Created OSS credentials:"
cat ~/.oss/credentials.json
```

## Part 3: Prepare Vector Dataset

### Step 5: Create Test Vector Dataset

```bash
# Create local test dataset first
mkdir -p /tmp/oss-dataset

# Create a sample vector dataset
cat > /tmp/oss-dataset/lance-vectors.json << 'EOF'
[
  {
    "id": "doc1",
    "vector": [0.1, 0.2, 0.3, 0.4, 0.5],
    "metadata": {
      "title": "Introduction to Machine Learning",
      "category": "tech",
      "author": "Dr. Smith"
    }
  },
  {
    "id": "doc2", 
    "vector": [0.2, 0.1, 0.3, 0.5, 0.4],
    "metadata": {
      "title": "Deep Learning Fundamentals",
      "category": "tech", 
      "author": "Prof. Johnson"
    }
  },
  {
    "id": "doc3",
    "vector": [0.9, 0.1, 0.0, 0.1, 0.2],
    "metadata": {
      "title": "Quantum Physics Basics",
      "category": "science",
      "author": "Dr. Chen"
    }
  },
  {
    "id": "doc4",
    "vector": [0.3, 0.8, 0.2, 0.1, 0.4],
    "metadata": {
      "title": "Data Science Methods",
      "category": "tech",
      "author": "Ms. Williams"
    }
  },
  {
    "id": "doc5",
    "vector": [0.1, 0.9, 0.1, 0.3, 0.2],
    "metadata": {
      "title": "Astrophysics Research",
      "category": "science",
      "author": "Dr. Brown"
    }
  }
]
EOF

echo "Created test dataset:"
cat /tmp/oss-dataset/lance-vectors.json | head -20
```

### Step 6: Upload Dataset to OSS

**Option A: Using OSS Console (Web UI)**
1. Go to OSS Console: https://oss.console.aliyun.com/
2. Select your bucket
3. Create folder `datasets/`
4. Upload `/tmp/oss-dataset/lance-vectors.json` as `datasets/lance-vectors.json`
5. Verify file is accessible

**Option B: Using ossutil CLI (if installed)**
```bash
# Configure ossutil (if not already done)
ossutil config -e ${OSS_ENDPOINT} -i ${OSS_ACCESS_KEY} -k ${OSS_SECRET_KEY}

# Upload dataset
ossutil cp /tmp/oss-dataset/lance-vectors.json oss://${OSS_BUCKET_NAME}/${OSS_DATASET_PATH}

# Verify upload
ossutil ls oss://${OSS_BUCKET_NAME}/datasets/
```

**Option C: Using curl (for public buckets)**
```bash
# This requires additional OSS signature setup - use Console or ossutil instead
echo "Please use OSS Console or ossutil CLI to upload the dataset"
```

### Step 7: Verify OSS Dataset Access

```bash
# Test OSS access (requires public read or proper auth setup)
echo "Testing OSS dataset accessibility..."
echo "OSS URI: $OSS_DATASET_URI"

# If bucket allows public access, you can test with curl:
# curl "https://${OSS_BUCKET_NAME}.${OSS_ENDPOINT}/${OSS_DATASET_PATH}"
```

## Part 4: Build and Start Elasticsearch

### Step 8: Build Lance Vector Plugin

```bash
cd $ES_HOME

# Build the plugin (this may take several minutes)
echo "Building lance-vector plugin..."
./gradlew :plugins:lance-vector:build

# Check build status
echo "Build completed. Checking plugin files..."
find plugins/lance-vector/build -name "*.jar" | head -5
```

### Step 9: Start Elasticsearch with Plugin

```bash
# Start Elasticsearch in the background
echo "Starting Elasticsearch with lance-vector plugin..."
nohup ./gradlew run > elasticsearch.log 2>&1 &

# Store the PID for later cleanup
ES_PID=$!
echo $ES_PID > elasticsearch.pid
echo "Elasticsearch PID: $ES_PID"

# Wait for Elasticsearch to start
echo "Waiting for Elasticsearch to start..."
for i in {1..60}; do
    if curl -s "http://localhost:9200" > /dev/null 2>&1; then
        echo "Elasticsearch is ready!"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "Elasticsearch failed to start within 60 seconds"
        exit 1
    fi
    sleep 1
    echo -n "."
done
```

### Step 10: Verify Elasticsearch Status

```bash
# Check Elasticsearch health
echo "Checking Elasticsearch status..."
curl -s "http://localhost:9200" | jq '.'

# Check cluster health
curl -s "http://localhost:9200/_cluster/health" | jq '.'

# List installed plugins
curl -s "http://localhost:9200/_nodes/plugins" | jq '.nodes[].plugins[] | select(.name == "lance-vector")'
```

## Part 5: Create Lance Vector Index

### Step 11: Create Index with OSS Storage

```bash
# Create index mapping with lance_vector field pointing to OSS
echo "Creating lance_vector index with OSS storage..."

curl -X PUT "localhost:9200/lance-vectors" \
  -H "Content-Type: application/json" \
  -d "{
    \"settings\": {
      \"number_of_shards\": 1,
      \"number_of_replicas\": 0
    },
    \"mappings\": {
      \"properties\": {
        \"title\": {
          \"type\": \"text\",
          \"analyzer\": \"english\"
        },
        \"category\": {
          \"type\": \"keyword\"
        },
        \"author\": {
          \"type\": \"keyword\"
        },
        \"content\": {
          \"type\": \"text\"
        },
        \"embedding\": {
          \"type\": \"lance_vector\",
          \"dims\": 5,
          \"similarity\": \"cosine\",
          \"storage\": {
            \"type\": \"external\",
            \"uri\": \"$OSS_DATASET_URI\",
            \"lance_id_column\": \"id\",
            \"lance_vector_column\": \"vector\",
            \"read_only\": true
          }
        }
      }
    }
  }"

echo -e "\nIndex creation response above."
```

### Step 12: Verify Index Creation

```bash
# Check index exists
echo "Verifying index creation..."
curl -s "http://localhost:9200/_cat/indices/lance-vectors?v"

# Get index mapping
echo -e "\nIndex mapping:"
curl -s "http://localhost:9200/lance-vectors/_mapping" | jq '.["lance-vectors"].mappings.properties.embedding'

# Check index health
curl -s "http://localhost:9200/_cluster/health/lance-vectors" | jq '.'
```

## Part 6: Index Metadata Documents

### Step 13: Index Document Metadata

These documents provide the metadata and content that will be returned in search results. The vectors come from OSS, but document fields come from Elasticsearch.

```bash
echo "Indexing metadata documents..."

# Index document 1
curl -X POST "localhost:9200/lance-vectors/_doc/doc1" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Introduction to Machine Learning",
    "category": "tech",
    "author": "Dr. Smith",
    "content": "This document covers the fundamental concepts of machine learning including supervised and unsupervised learning algorithms.",
    "published_date": "2024-01-15",
    "tags": ["machine-learning", "ai", "algorithms"]
  }'

# Index document 2  
curl -X POST "localhost:9200/lance-vectors/_doc/doc2" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Deep Learning Fundamentals", 
    "category": "tech",
    "author": "Prof. Johnson",
    "content": "An in-depth exploration of neural networks, backpropagation, and deep learning architectures.",
    "published_date": "2024-02-01",
    "tags": ["deep-learning", "neural-networks", "ai"]
  }'

# Index document 3
curl -X POST "localhost:9200/lance-vectors/_doc/doc3" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Quantum Physics Basics",
    "category": "science", 
    "author": "Dr. Chen",
    "content": "Introduction to quantum mechanics, wave-particle duality, and quantum entanglement phenomena.",
    "published_date": "2024-01-20",
    "tags": ["quantum-physics", "physics", "science"]
  }'

# Index document 4
curl -X POST "localhost:9200/lance-vectors/_doc/doc4" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Data Science Methods",
    "category": "tech",
    "author": "Ms. Williams", 
    "content": "Statistical analysis, data visualization, and predictive modeling techniques for data scientists.",
    "published_date": "2024-02-10",
    "tags": ["data-science", "statistics", "analytics"]
  }'

# Index document 5
curl -X POST "localhost:9200/lance-vectors/_doc/doc5" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Astrophysics Research",
    "category": "science",
    "author": "Dr. Brown",
    "content": "Current research in stellar formation, black holes, and cosmic microwave background radiation.",
    "published_date": "2024-01-25", 
    "tags": ["astrophysics", "cosmology", "research"]
  }'

echo -e "\nIndexed 5 documents."
```

### Step 14: Refresh Index and Verify Documents

```bash
# Refresh index to make documents searchable
echo "Refreshing index..."
curl -X POST "localhost:9200/lance-vectors/_refresh"

# Count total documents
echo -e "\nDocument count:"
curl -s "http://localhost:9200/lance-vectors/_count" | jq '.'

# List all document IDs
echo -e "\nDocument IDs:"
curl -s "http://localhost:9200/lance-vectors/_search?size=10&_source=false" | jq -r '.hits.hits[]._id'

# Get a sample document
echo -e "\nSample document:"
curl -s "http://localhost:9200/lance-vectors/_doc/doc1" | jq '._source'
```

## Part 7: Test Lance Vector Queries

### Step 15: Basic kNN Search

```bash
echo "=== Testing Basic kNN Search ==="

# Search for documents similar to query vector [0.9, 0.1, 0.0, 0.1, 0.2]
# This should match doc3 most closely (which has vector [0.9, 0.1, 0.0, 0.1, 0.2])
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "knn": {
      "field": "embedding",
      "query_vector": [0.9, 0.1, 0.0, 0.1, 0.2],
      "k": 3,
      "num_candidates": 5
    },
    "size": 3
  }' | jq '{
    total_hits: .hits.total.value,
    results: [.hits.hits[] | {
      id: ._id,
      score: ._score,
      title: ._source.title,
      category: ._source.category,
      author: ._source.author
    }]
  }'
```

### Step 16: kNN Search with Category Filter

```bash
echo -e "\n=== Testing kNN Search with Filter ==="

# Search for tech documents similar to query vector
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "knn": {
      "field": "embedding", 
      "query_vector": [0.2, 0.1, 0.3, 0.5, 0.4],
      "k": 3,
      "num_candidates": 5
    },
    "query": {
      "term": {
        "category": "tech"
      }
    },
    "size": 3
  }' | jq '{
    total_hits: .hits.total.value,
    results: [.hits.hits[] | {
      id: ._id,
      score: ._score,
      title: ._source.title,
      category: ._source.category,
      author: ._source.author
    }]
  }'
```

### Step 17: Hybrid Search (kNN + Text Query)

```bash
echo -e "\n=== Testing Hybrid Search (kNN + Text) ==="

# Combine vector similarity with text matching
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.2, 0.3, 0.4, 0.5],
      "k": 5,
      "num_candidates": 5
    },
    "query": {
      "bool": {
        "should": [
          {
            "match": {
              "content": "learning algorithms"
            }
          },
          {
            "match": {
              "title": "machine learning"
            }
          }
        ]
      }
    },
    "size": 3
  }' | jq '{
    total_hits: .hits.total.value,
    results: [.hits.hits[] | {
      id: ._id,
      score: ._score,
      title: ._source.title,
      category: ._source.category,
      relevance_factors: {
        vector_similarity: "from lance dataset",
        text_match: "from elasticsearch"
      }
    }]
  }'
```

### Step 18: Multi-Field Search with Aggregations

```bash
echo -e "\n=== Testing Search with Aggregations ==="

# kNN search with category aggregations
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "knn": {
      "field": "embedding",
      "query_vector": [0.5, 0.5, 0.3, 0.3, 0.3],
      "k": 5,
      "num_candidates": 5
    },
    "aggs": {
      "categories": {
        "terms": {
          "field": "category"
        }
      },
      "authors": {
        "terms": {
          "field": "author"
        }
      }
    },
    "size": 5
  }' | jq '{
    total_hits: .hits.total.value,
    results: [.hits.hits[] | {
      id: ._id,
      score: ._score,
      title: ._source.title,
      category: ._source.category
    }],
    aggregations: {
      categories: [.aggregations.categories.buckets[] | {category: .key, count: .doc_count}],
      authors: [.aggregations.authors.buckets[] | {author: .key, count: .doc_count}]
    }
  }'
```

## Part 8: Performance and Monitoring

### Step 19: Check Query Performance

```bash
echo -e "\n=== Performance Testing ==="

# Enable search profiling
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "profile": true,
    "knn": {
      "field": "embedding",
      "query_vector": [0.1, 0.9, 0.1, 0.3, 0.2],
      "k": 2,
      "num_candidates": 5
    },
    "size": 2
  }' | jq '{
    timing: .profile.shards[0].searches[0].query[0].time_in_nanos,
    results: [.hits.hits[] | {
      id: ._id,
      score: ._score,
      title: ._source.title
    }]
  }'
```

### Step 20: Monitor Index Statistics

```bash
echo -e "\n=== Index Statistics ==="

# Get index stats
curl -s "http://localhost:9200/lance-vectors/_stats" | jq '{
  index_size: .indices["lance-vectors"].total.store.size_in_bytes,
  document_count: .indices["lance-vectors"].total.docs.count,
  search_count: .indices["lance-vectors"].total.search.query_total,
  search_time: .indices["lance-vectors"].total.search.query_time_in_millis
}'

# Check cluster stats
echo -e "\nCluster overview:"
curl -s "http://localhost:9200/_cluster/stats" | jq '{
  cluster_name: .cluster_name,
  indices_count: .indices.count,
  total_docs: .indices.docs.count,
  total_size: .indices.store.size_in_bytes
}'
```

## Part 9: Data Validation

### Step 21: Verify Vector-Document Join

```bash
echo -e "\n=== Validating Vector-Document Join ==="

# Get specific document and verify it exists in both ES and vector dataset
echo "Document doc3 from Elasticsearch:"
curl -s "http://localhost:9200/lance-vectors/_doc/doc3" | jq '{
  id: ._id,
  found: ._found,
  source: ._source
}'

echo -e "\nSearching for doc3 specifically by vector similarity:"
curl -X POST "localhost:9200/lance-vectors/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "knn": {
      "field": "embedding",
      "query_vector": [0.9, 0.1, 0.0, 0.1, 0.2],
      "k": 1,
      "num_candidates": 5
    },
    "size": 1
  }' | jq '{
    found_doc: .hits.hits[0]._id,
    similarity_score: .hits.hits[0]._score,
    title: .hits.hits[0]._source.title
  }'
```

## Part 10: Cleanup

### Step 22: Cleanup Commands

```bash
echo -e "\n=== Cleanup Commands (run when done) ==="

# Stop Elasticsearch
if [ -f elasticsearch.pid ]; then
    ES_PID=$(cat elasticsearch.pid)
    echo "Stopping Elasticsearch (PID: $ES_PID)..."
    kill $ES_PID
    rm elasticsearch.pid
fi

# Remove test index
echo "To remove test index:"
echo "curl -X DELETE 'localhost:9200/lance-vectors'"

# Remove temporary files
echo "Removing temporary files..."
rm -f /tmp/oss-dataset/lance-vectors.json
rmdir /tmp/oss-dataset 2>/dev/null || true

echo "Cleanup completed."
```

## Expected Results Summary

After running all steps, you should see:

1. **Elasticsearch starts successfully** with lance-vector plugin loaded
2. **Index creation succeeds** with OSS storage configuration
3. **Document indexing completes** with 5 documents
4. **kNN searches return results** with Lance similarity scores
5. **Filtering works** with both vector similarity and text/keyword filters
6. **Performance profiling** shows query execution details

### Key Success Indicators:

- **Index health**: `green` status
- **Document count**: 5 documents indexed
- **kNN results**: Returns documents ranked by vector similarity
- **OSS integration**: Vectors loaded from your OSS bucket
- **Join correctness**: Document metadata from ES, vectors from OSS

### Troubleshooting:

If any step fails, check:
1. Java 21 is properly installed
2. OSS credentials are correct and bucket is accessible  
3. Elasticsearch logs for specific errors: `tail -f elasticsearch.log`
4. Index mapping configuration matches dataset schema

This completes the full end-to-end POC demonstration! 🎉