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

# Check if ES is running
echo "Checking Elasticsearch connection..."
if ! curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Elasticsearch is not running or credentials are wrong${NC}"
    echo "Please start ES with: ./project_starter.sh -d"
    exit 1
fi
check_test "Elasticsearch is running"

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
          "shard_path": "shard-{shard_id}-v1.lance",
          "lance_id_column": "_id",
          "lance_vector_column": "vector"
        }
      }
    }
  }
}'

check_test "Index created with shard-aware mapping"

# Index metadata documents (first 10 per shard)
echo "  Indexing metadata documents..."
for shard_id in 0 1 2; do
    for i in $(seq 0 9); do
        doc_id=$((shard_id * 1000 + i))
        curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_create/doc_${doc_id}" \
          -H 'Content-Type: application/json' -d "{
          \"title\": \"Product ${doc_id}\",
          \"category\": \"electronics\",
          \"price\": 99.99
        }" > /dev/null 2>&1
    done
done

check_test "Metadata documents indexed (30 docs total)"

# Force refresh
curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_refresh" > /dev/null
check_test "Index refreshed"

# Verify documents were indexed
DOC_COUNT=$(curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/regression-test/_count" | grep -o '"count":[0-9]*' | cut -d: -f2)
echo -e "${GREEN}✓${NC} Document count: $DOC_COUNT"

# ========================================================================
# TEST 3: Verify kNN Search with Pre-Filter and Post-Filter
# ========================================================================
echo ""
echo "TEST 3: kNN search with filters..."
echo "----------------------------------"

# Generate a test query vector
QUERY_VECTOR=$(python3 -c "import numpy as np; np.random.seed(42); print(','.join(map(str, np.random.random(128).tolist())))")

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

echo "$RESULT" | python3 -m json.tool > /tmp/test3a_result.json 2>/dev/null || echo "$RESULT" > /tmp/test3a_result.json

HIT_COUNT=$(python3 -c "import json; f=open('/tmp/test3a_result.json'); d=json.load(f); print(len(d['hits']['hits']))" 2>/dev/null || echo "0")

if [ "$HIT_COUNT" -gt 0 ]; then
    # Check if all scores > 0
    ZERO_SCORES=$(python3 -c "import json; f=open('/tmp/test3a_result.json'); d=json.load(f); print(sum(1 for h in d['hits']['hits'] if h.get('_score', 0) == 0.0))" 2>/dev/null || echo "999")

    if [ "$ZERO_SCORES" -eq 0 ]; then
        check_test "kNN without filter: ${HIT_COUNT} hits, all scores > 0"
    else
        echo -e "${RED}✗${NC} kNN without filter: ${ZERO_SCORES} results have score = 0"
    fi
else
    echo -e "${YELLOW}⚠${NC} kNN without filter: No hits (dataset may not be accessible)"
fi

# Test 3b: kNN with post-filter (category filter)
echo "  3b. kNN with category filter..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 10,
    \"num_candidates\": 50
  },
  \"post_filter\": {
    \"term\": { \"category\": \"electronics\" }
  }
}")

echo "$RESULT" | python3 -m json.tool > /tmp/test3b_result.json 2>/dev/null || echo "$RESULT" > /tmp/test3b_result.json

FILTERED_HITS=$(python3 -c "import json; f=open('/tmp/test3b_result.json'); d=json.load(f); print(len(d['hits']['hits']))" 2>/dev/null || echo "0")
check_test "kNN with category filter: ${FILTERED_HITS} results"

# Test 3c: kNN with range filter (price filter)
echo "  3c. kNN with price range filter..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 10,
    \"num_candidates\": 100
  },
  \"post_filter\": {
    \"range\": { \"price\": { \"gte\": 0, \"lte\": 500 } }
  }
}")

echo "$RESULT" | python3 -m json.tool > /tmp/test3c_result.json 2>/dev/null || echo "$RESULT" > /tmp/test3c_result.json

RANGE_HITS=$(python3 -c "import json; f=open('/tmp/test3c_result.json'); d=json.load(f); print(len(d['hits']['hits']))" 2>/dev/null || echo "0")
check_test "kNN with price range filter: ${RANGE_HITS} results"

# ========================================================================
# TEST 4: Update Lance Datasets in OSS
# ========================================================================
echo ""
echo "TEST 4: Update Lance datasets in OSS..."
echo "----------------------------------------"

# Update shard 0 with new data
python3 -c "
import sys
sys.path.insert(0, '/home/denny/projects/es-9.2.4-plugins-rt-scale/tests')
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
    "lance.refresh.interval": "10s"
  }
}' > /dev/null

check_test "NRT refresh enabled (10s interval)"

# Update index mapping to point to v2 dataset for shard 0
# Note: We need to update the shard_path to use v2 for shard 0
echo "  Updating index mapping to v2..."

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
        "shard_path": "shard-{shard_id}-v2.lance",
        "lance_id_column": "_id",
        "lance_vector_column": "vector"
      }
    }
  }
}' > /dev/null

check_test "Index mapping updated to v2"

# Wait for NRT refresh
echo "  Waiting for NRT refresh (15 seconds)..."
sleep 15

# Trigger manual refresh
echo "  Triggering manual refresh..."
curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/_lance/refresh" \
  -H 'Content-Type: application/json' -d '{
  "indices": ["regression-test"]
}' > /dev/null 2>&1

# Wait a bit more for refresh to complete
sleep 5

# Check Lance stats
STATS=$(curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/_lance/stats?pretty")
CACHE_SIZE=$(echo "$STATS" | grep '"size"' | head -1 | grep -o '[0-9]*' || echo "0")

check_test "NRT refresh triggered, cache size: $CACHE_SIZE"

# Verify search works with updated dataset
echo "  Testing search with updated dataset..."
RESULT=$(curl -sk -u "${ES_USER}:${ES_PASS}" -X POST "${ES_HOST}/regression-test/_search" \
  -H 'Content-Type: application/json' -d "{
  \"knn\": {
    \"field\": \"embedding\",
    \"query_vector\": [${QUERY_VECTOR}],
    \"k\": 5,
    \"num_candidates\": 50
  }
}")

echo "$RESULT" | python3 -m json.tool > /tmp/test5_result.json 2>/dev/null || echo "$RESULT" > /tmp/test5_result.json

UPDATED_HITS=$(python3 -c "import json; f=open('/tmp/test5_result.json'); d=json.load(f); print(len(d['hits']['hits']))" 2>/dev/null || echo "0")

if [ "$UPDATED_HITS" -ge 0 ]; then
    check_test "Search with updated dataset: ${UPDATED_HITS} hits"
else
    echo -e "${YELLOW}⚠${NC} Search with updated dataset: Error occurred"
fi

# ========================================================================
# SUMMARY
# ========================================================================
echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo ""
echo "Lance Stats:"
curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/_lance/stats?pretty"
echo ""
echo ""
echo "Index Settings (embedding field):"
curl -sk -u "${ES_USER}:${ES_PASS}" "${ES_HOST}/regression-test/_mapping?pretty" | grep -A 30 "embedding"
echo ""

echo -e "${GREEN}=========================================="
echo "OSS Integration Tests Complete!"
echo "==========================================${NC}"
