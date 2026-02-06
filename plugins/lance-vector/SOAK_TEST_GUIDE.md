# 7-Day Soak Test Guide - Lance Vector Plugin

**Purpose**: Validate production readiness through extended stability testing

---

## Overview

The 7-day soak test (P0.4) is designed to:
1. Detect memory leaks that only manifest over time
2. Validate resource cleanup under sustained load
3. Identify performance degradation patterns
4. Verify stability under realistic production conditions

---

## Prerequisites

### 1. Environment Setup

```bash
# Export OSS credentials
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# Verify credentials are set
env | grep OSS
```

### 2. Start Elasticsearch

```bash
cd /home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p elasticsearch.pid

# Wait for startup (check logs)
tail -f logs/elasticsearch.log | grep "started"

# Verify ES is running
curl -k -u elastic:Summer11 https://localhost:9200/_cluster/health
```

### 3. Prepare Test Data

```bash
# Create test index with Lance vector field
curl -k -u elastic:Summer11 -X POST "https://localhost:9200/test-soak" \
  -H "Content-Type: application/json" -d '
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "content": { "type": "text" },
      "embedding": {
        "type": "lance_vector",
        "dims": 128,
        "storage": {
          "uri": "oss://denny-test-lance/soak-test-7d/data.lance"
        }
      }
    }
  }
}'

# Index some test documents
for i in {1..1000}; do
  curl -k -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_doc/$i" \
    -H "Content-Type: application/json" -d "{
      \"content\": \"Test document $i\",
      \"embedding\": [$(printf '0.1,' && seq 2 127 | xargs -I{} printf '0.1,')]
    }"
done
```

---

## Test Scenarios

### Scenario 1: Sustained Query Load

**Duration**: 7 days
**Rate**: 10 queries/second
**Concurrent Clients**: 10

```bash
# Create query script
cat > /tmp/soak_query.sh <<'EOF'
#!/bin/bash
VECTOR="[$(printf '0.1,' && seq 2 127 | xargs -I{} printf '0.1,')]"

while true; do
  curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_search" \
    -H "Content-Type: application/json" -d "{
      \"query\": {
        \"bool\": {
          \"must\": [
            { \"match\": { \"content\": \"test\" }}
          ]
        }
      },
      \"knn\": {
        \"field\": \"embedding\",
        \"query_vector\": $VECTOR,
        \"k\": 10,
        \"num_candidates\": 100
      }
    }" > /dev/null

  sleep 0.1  # 10 qps
done
EOF

chmod +x /tmp/soak_query.sh

# Run 10 concurrent clients
for i in {1..10}; do
  /tmp/soak_query.sh &
done
```

### Scenario 2: Burst Load Pattern

**Duration**: 7 days
**Pattern**: 5 min at 100 qps, 5 min at 1 qps (repeat)

```bash
cat > /tmp/soak_burst.sh <<'EOF'
#!/bin/bash
VECTOR="[$(printf '0.1,' && seq 2 127 | xargs -I{} printf '0.1,')]"

while true; do
  # Burst: 100 qps for 5 minutes
  for i in {1..30000}; do
    curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_search" \
      -H "Content-Type: application/json" -d "{
        \"query\": { \"match_all\": {} },
        \"knn\": {
          \"field\": \"embedding\",
          \"query_vector\": $VECTOR,
          \"k\": 10,
          \"num_candidates\": 100
        }
      }" > /dev/null
  done

  # Cooldown: 1 qps for 5 minutes
  for i in {1..300}; do
    curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-soak/_search" \
      -H "Content-Type: application/json" -d "{
        \"query\": { \"match_all\": {} },
        \"knn\": {
          \"field\": \"embedding\",
          \"query_vector\": $VECTOR,
          \"k\": 10,
          \"num_candidates\": 100
        }
      }" > /dev/null
    sleep 1
  done
done
EOF

chmod +x /tmp/soak_burst.sh
/tmp/soak_burst.sh &
```

### Scenario 3: Cache Eviction Stress

**Duration**: 7 days
**Pattern**: Load 200 different datasets to trigger LRU eviction

```bash
# Requires 200 pre-created datasets on OSS
for i in {1..200}; do
  curl -k -u elastic:Summer11 -X POST "https://localhost:9200/test-cache-$i" \
    -H "Content-Type: application/json" -d "{
      \"mappings\": {
        \"properties\": {
          \"embedding\": {
            \"type\": \"lance_vector\",
            \"dims\": 128,
            \"storage\": {
              \"uri\": \"oss://denny-test-lance/cache-test-$i/data.lance\"
            }
          }
        }
      }
    }"
done

# Query all indexes continuously
cat > /tmp/soak_cache.sh <<'EOF'
#!/bin/bash
VECTOR="[$(printf '0.1,' && seq 2 127 | xargs -I{} printf '0.1,')]"

while true; do
  for i in {1..200}; do
    curl -k -s -u elastic:Summer11 -X POST "https://localhost:9200/test-cache-$i/_search" \
      -H "Content-Type: application/json" -d "{
        \"query\": { \"match_all\": {} },
        \"knn\": {
          \"field\": \"embedding\",
          \"query_vector\": $VECTOR,
          \"k\": 10,
          \"num_candidates\": 100
        }
      }" > /dev/null
  done
done
EOF

chmod +x /tmp/soak_cache.sh
/tmp/soak_cache.sh &
```

---

## Monitoring

### Start Metrics Collection

```bash
cd /home/denny/projects/es-9.2.4-plugins-real-time/plugins/lance-vector/scripts
export ES_PID=$(cat /home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT/elasticsearch.pid)
export OUTPUT_DIR=/tmp/lance-soak-$(date +%Y%m%d)
./collect-baseline-metrics.sh
```

### Monitor Progress

```bash
# Check current metrics
tail -f $OUTPUT_DIR/metrics.csv

# Check for alerts
tail -f $OUTPUT_DIR/alerts.log

# Get summary
cat $OUTPUT_DIR/summary.txt
```

### Key Metrics to Watch

| Metric | Tool | Alert Threshold |
|--------|------|-----------------|
| Heap Usage | `jstat -gc` | >90% |
| FD Count | `ls /proc/$PID/fd \| wc -l` | >10000 |
| Thread Count | `ls /proc/$PID/task \| wc -l` | >2000 |
| Native Memory | Arrow allocator | >500MB |

---

## Success Criteria

| Metric | Pass Criteria | How to Verify |
|--------|---------------|---------------|
| Memory Leak | <10 MB/day | Check metrics.csv slope |
| FD Leak | 0 FD/day | Check metrics.csv slope |
| Thread Leak | 0 threads/day | Check metrics.csv slope |
| Query Latency | <20% drift | Compare day 1 vs day 7 |
| Error Rate | <0.1% | Check alerts.log |
| Uptime | 100% | No crashes in 7 days |

---

## Troubleshooting

### Issue: Memory Leak Detected

**Symptoms**: Heap usage grows steadily

**Actions**:
1. Capture heap dump: `jmap -dump:format=b,file=heap.bin $ES_PID`
2. Analyze with Eclipse MAT or VisualVM
3. Look for `LanceDataset`, `ArrowAllocator` instances
4. Check if removal listener is firing

### Issue: FD Leak Detected

**Symptoms**: FD count grows steadily

**Actions**:
1. Check open files: `lsof -p $ES_PID | wc -l`
2. Look for unclosed `.lance` files
3. Verify `dataset.close()` is being called
4. Check removal listener logs

### Issue: Thread Leak Detected

**Symptoms**: Thread count grows steadily

**Actions**:
1. Thread dump: `jstack $ES_PID > thread_dump.txt`
2. Look for stuck threads in pool-*
3. Check for waiting on locks

### Issue: Query Latency Drift

**Symptoms**: Queries get slower over time

**Actions**:
1. Profile with async-profiler
2. Check GC pause times
3. Look for cache misses increasing

---

## Abort Criteria

Stop the soak test immediately if:
1. **Heap >95%** for 5+ minutes
2. **FD count >10000** and growing
3. **Thread count >2000** and growing
4. **ES crashes** or becomes unresponsive
5. **Error rate >1%** for sustained period

---

## Completion

### After 7 Days

1. **Stop load clients**: `pkill -f soak_`
2. **Generate final report**:
   ```bash
   cd /home/denny/projects/es-9.2.4-plugins-real-time/plugins/lance-vector/scripts
   ./collect-baseline-metrics.sh  # This will generate summary
   ```
3. **Archive results**:
   ```bash
   tar -czf lance-soak-results-$(date +%Y%m%d).tar.gz \
     $OUTPUT_DIR \
     /tmp/soak_*.sh \
     logs/elasticsearch.log
   ```
4. **Update baseline report** with actual results
5. **Document findings** in `findings.md`

---

## Quick Start

```bash
# 1. Set credentials
export OSS_ACCESS_KEY_ID=$(grep '"access_key_id"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ACCESS_KEY_SECRET=$(grep '"access_key_secret"' ~/.oss/credentials.json | cut -d'"' -f4)
export OSS_ENDPOINT="oss-ap-southeast-1.aliyuncs.com"

# 2. Start ES
cd /home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT
./bin/elasticsearch -d -p elasticsearch.pid

# 3. Start monitoring
cd /home/denny/projects/es-9.2.4-plugins-real-time/plugins/lance-vector/scripts
export ES_PID=$(cat /home/denny/projects/es-9.2.4-plugins-real-time/build/distribution/local/elasticsearch-9.2.4-SNAPSHOT/elasticsearch.pid)
./collect-baseline-metrics.sh &

# 4. Start load (choose scenario)
# ... (see scenarios above)

# 5. Monitor for 7 days
watch -n 60 'tail -5 /tmp/lance-soak-$(date +%Y%m%d)/metrics.csv'

# 6. After 7 days, generate final report
# Collect output from metrics script
```

---

**Document Version**: 1.0
**Last Updated**: 2026-02-06
**Status**: Ready for Execution
