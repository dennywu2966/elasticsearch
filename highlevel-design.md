# Lance Vector Integration for Elasticsearch

**Status:** Reviewed (v0.6)
**Authors:** ES Lance Integration Team
**Created:** 2026-01-05
**Last Updated:** 2026-01-07
**Reviewers:** Tech Lead Review Complete (4 passes), PM Review Complete, Dry Run Verified

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Background](#2-background)
3. [Goals & Non-Goals](#3-goals--non-goals)
4. [Design Overview](#4-design-overview)
5. [Detailed Design](#5-detailed-design)
6. [Consistency Model](#6-consistency-model)
7. [Code Locations](#7-code-locations)
8. [Observability](#8-observability)
9. [Debugging](#9-debugging)
10. [Testing Strategy](#10-testing-strategy)
11. [Security](#11-security)
12. [Risks & Concerns](#12-risks--concerns)
13. [Execution Plan](#13-execution-plan)
14. [Rollout Strategy](#14-rollout-strategy)
15. [Appendix](#15-appendix)

---

## 1. Problem Statement

### 1.1 Current State

Organizations building AI/ML applications face a critical challenge: they have **massive vector datasets (10B+ vectors)** stored in data lakes (S3) using Lance format, but cannot efficiently query them through Elasticsearch without:

1. **Data duplication**: Copying vectors from S3 to ES native storage, doubling storage costs
2. **Reindexing overhead**: Days-to-weeks of reindexing time for billion-scale datasets
3. **Data staleness**: Data lake updates require re-sync, creating consistency gaps
4. **Cost explosion**: ES native vector storage at $0.10/GB/month vs S3 at $0.023/GB/month

### 1.2 User Pain Points

| Pain Point | Impact | Current Workaround |
|------------|--------|-------------------|
| Cannot query data lake vectors via ES | Lost ES ecosystem benefits (filters, aggregations, scoring) | Maintain separate vector DB |
| 10B+ vectors don't fit in ES memory | Cannot use ES for large-scale vector search | Shard across multiple clusters |
| S3 vectors must be copied to ES | 2x storage cost, days of migration | Accept cost or skip ES |
| Data lake updates break ES sync | Manual re-sync, data inconsistency | Scheduled batch updates |

### 1.3 Desired Outcome

Users should be able to:
- **Mount** existing Lance vector indices from S3 into ES **without copying data**
- **Query** 10B+ vectors using standard ES `knn` API with sub-100ms latency
- **Trust** S3 as the source of truth while ES provides caching and query coordination
- **Update** vectors via ES with changes persisted to S3

---

## 2. Background

### 2.1 Lance Format Overview

[Lance](https://lancedb.github.io/lance/) is a columnar data format optimized for ML workloads:

```
Lance Dataset Structure:
├── _latest.manifest          # Current version pointer
├── _versions/
│   ├── 1.manifest           # Immutable version snapshots
│   └── N.manifest
├── data/
│   ├── fragment_000.lance   # Immutable data files (~1M rows each)
│   └── fragment_NNN.lance
├── _indices/
│   └── vector_idx.lance     # IVF-PQ or other index
└── _deletions/
    └── bitmap_N.arrow       # Deletion vectors
```

**Key Properties:**
- **Immutable fragments**: Data files never modified, only appended
- **Versioned manifests**: Atomic updates via manifest pointer swap
- **Arrow-based**: Zero-copy interop with Arrow ecosystem
- **Cloud-native**: Designed for S3/GCS/Azure Blob

### 2.2 Elasticsearch Vector Architecture

ES stores vectors via Lucene's `KnnVectorsFormat`:

```
ES Index
└── Shard
    └── Lucene Segment
        ├── .vec files (raw vectors)
        ├── .vex files (HNSW graph)
        └── .vem files (metadata)
```

**Current Limitations:**
- Vectors stored on local disk (not S3)
- Per-segment memory overhead for HNSW graphs
- No native data lake integration

### 2.3 Why Lance + ES?

| Capability | ES Native | Lance on S3 | Lance + ES (This Design) |
|------------|-----------|-------------|--------------------------|
| Query API | ES `knn` | Custom SDK | ES `knn` (unchanged) |
| Storage cost | High (ESSD) | Low (S3) | Low (S3) |
| Scale | ~100M/shard | 10B+ | 10B+ |
| Data lake integration | None | Native | Native via ES |
| Filters/aggregations | Full | Limited | Full |

### 2.4 Prior Art

- **LanceDB**: Serverless vector database using Lance format
- **Pinecone/Weaviate**: Managed vector DBs (no ES integration)
- **ES dense_vector**: Native ES vectors (no S3 support)

---

## 3. Goals & Non-Goals

### 3.1 Goals

| ID | Goal | Success Criteria |
|----|------|------------------|
| G1 | Mount existing Lance indices from S3 | User can query S3 Lance index via ES within 10 seconds of mount |
| G2 | Scale to 10B+ vectors | Query latency p99 < 200ms at 10B scale |
| G3 | S3 as source of truth | All vector data persists in S3, survives ES cluster loss |
| G4 | Transparent ES API | Existing `knn` queries work without modification |
| G5 | Local ESSD caching | 90%+ cache hit rate for hot queries |
| G6 | Support IVF-PQ indexing | Memory footprint < 10 bytes/vector |
| G7 | Full read-write support | Index, update, delete vectors via ES APIs |

### 3.2 Non-Goals

| ID | Non-Goal | Rationale |
|----|----------|-----------|
| NG1 | Migration tool from `dense_vector` | Users can re-index; not in scope |
| NG2 | HNSW index support (Phase 1) | IVF-PQ prioritized for 10B scale; HNSW later |
| NG3 | Multi-region S3 replication | Defer to future; use S3 native replication |
| NG4 | Real-time sub-second indexing | Batched writes (100ms-1s latency acceptable) |
| NG5 | Cross-cluster Lance sharing | Single cluster per Lance index for simplicity |
| NG6 | ILM (Index Lifecycle Management) integration | Lance has its own versioning; ILM phases (warm/cold/frozen) don't map cleanly to S3 storage tiers. Consider S3 Intelligent-Tiering instead. Searchable snapshots not applicable. |
| NG7 | Aggregations on vector fields | Vector aggregations (avg, sum) not meaningful; use scalar fields |

### 3.3 Constraints

- **Dependency**: Requires `org.lancedb:lance-core` Java SDK (JNI to Rust)
- **Storage**: S3 (MVP), GCS/Azure (future)
- **ES Version**: 8.x+ (relies on modern KnnVectorsFormat)
- **Java**: 21+ (required by lance-java SDK)

---

## 4. Design Overview

### 4.1 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Elasticsearch Cluster                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐    ┌───────────────────┐    ┌─────────────────────┐  │
│  │   REST API       │    │  Transport Layer  │    │  Coordination Node  │  │
│  │   (unchanged)    │───▶│                   │───▶│  (scatter/gather)   │  │
│  └──────────────────┘    └───────────────────┘    └──────────┬──────────┘  │
│                                                               │             │
│  ┌───────────────────────────────────────────────────────────┼───────────┐ │
│  │                         Data Node                         │           │ │
│  │  ┌─────────────────────────────────────────────────────────▼────────┐ │ │
│  │  │                    LanceVectorPlugin                             │ │ │
│  │  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │ │ │
│  │  │  │FieldMapper   │  │VectorsFormat │  │ S3StorageAdapter       │ │ │ │
│  │  │  │(lance_vector)│  │(LanceFormat) │  │ ┌────────────────────┐ │ │ │ │
│  │  │  └──────────────┘  └──────────────┘  │ │ FragmentCache      │ │ │ │ │
│  │  │                                       │ │ (ESSD LRU)        │ │ │ │ │
│  │  │  ┌──────────────┐  ┌──────────────┐  │ └────────────────────┘ │ │ │ │
│  │  │  │DocIdMapper   │  │SyncStateMach │  │ ┌────────────────────┐ │ │ │ │
│  │  │  │(ordinal-map) │  │(mount/sync)  │  │ │ ManifestManager    │ │ │ │ │
│  │  │  └──────────────┘  └──────────────┘  │ └────────────────────┘ │ │ │ │
│  │  │                                       └────────────────────────┘ │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │                    Lucene Index (per shard)                      │  │ │
│  │  │  - _id → doc_id mapping                                          │  │ │
│  │  │  - Scalar fields (text, keyword, etc.)                           │  │ │
│  │  │  - Lance fragment references (in segment metadata)               │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTPS (AWS SDK)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       S3 Data Lake (SOURCE OF TRUTH)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│  s3://bucket/vectors/<index>/<shard>/                                        │
│    ├── _latest.manifest                                                      │
│    ├── _versions/1.manifest, 2.manifest, ...                                │
│    ├── data/fragment_*.lance                                                │
│    ├── _indices/ivf_pq.lance                                                │
│    └── _deletions/bitmap_*.arrow                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Key Components

| Component | Responsibility |
|-----------|---------------|
| **LanceVectorPlugin** | Plugin entry point; registers field type, codec, actions |
| **LanceVectorFieldMapper** | Parses mapping, creates field type |
| **LanceVectorsFormat** | Implements `KnnVectorsFormat` for Lance storage |
| **S3StorageAdapter** | S3 I/O, manifest management, fragment upload/download |
| **FragmentCache** | LRU cache on ESSD for hot fragments |
| **DocIdMapper** | Maps Lance results to Lucene doc_ids |
| **SyncStateMachine** | Coordinates mount and sync operations |

### 4.3 Data Flow Summary

**Query Flow:**
```
1. POST /my-index/_search { "knn": { "field": "embedding", ... } }
2. KnnVectorQueryBuilder detects lance_vector field type
3. LanceVectorsReader loads fragments (cache → S3)
4. Lance IVF-PQ search returns (lance_row_id, score, _es_doc_id)
5. LanceDocIdMapper reads _es_doc_id column (or lance_id_column for external)
6. Results merged across shards, returned to client with ES _id
```

**Index Flow:**
```
1. POST /my-index/_doc { "embedding": [...] }
2. LanceVectorsWriter buffers (esDocId, vector) pair
3. Write to WAL for durability
4. On flush (100K vectors): write fragment to S3 with _es_doc_id column
5. Update Lance manifest, Lucene commit completes
```

**External Mount Flow:**
```
1. PUT /my-index with storage.type=external
2. Read manifest from S3, discover schema
3. Validate lance_id_column and lance_vector_column exist
4. Map shards to Lance partitions (1:1 or virtual)
5. Queries use lance_id_column as ES _id (no sync needed for read-only)
```

---

## 5. Detailed Design

### 5.1 Data Model

#### 5.1.1 Lance Schema

**For ES-created indices:**

```
Schema: lance_vector_field
├── _lance_row_id: UInt64 (auto-generated, immutable, Lance's internal row ID)
├── _es_doc_id: Utf8 (ES document ID - used for query result mapping)
├── _vector: FixedSizeList[Float32, dims] (the actual vector)
└── [optional scalar columns for filter pushdown]
```

**Example Row (ES-created):**
```json
{
  "_lance_row_id": 12345678,
  "_es_doc_id": "doc_12345",
  "_vector": [0.1, 0.2, ..., 0.768]  // 768 floats
}
```

**For externally mounted indices:**

External Lance datasets have their own schema. The mapping specifies which columns to use:

```
External Schema (user-defined):
├── {lance_id_column}: Utf8 (e.g., "product_id" - maps to ES _id)
├── {lance_vector_column}: FixedSizeList[Float32, dims] (e.g., "embedding")
└── [other columns available for filter pushdown]
```

**Example Row (External):**
```json
{
  "product_id": "prod_12345",
  "embedding": [0.1, 0.2, ..., 0.768],
  "category": "electronics",
  "price": 99.99
}
```

**ID Mapping Strategy:**

| Index Type | Lance ID Column | ES _id | Mapping |
|------------|-----------------|--------|---------|
| ES-created | `_es_doc_id` | Same value | Direct (O(1) read) |
| External (matching) | `lance_id_column` | Same value | Direct (O(1) read) |
| External (transform) | `lance_id_column` | Transformed | Hash index (O(1) lookup) |

**Note:** This design decouples ES document identity from Lucene's internal segment structure, enabling both write-path correctness and external mount support.

#### 5.1.2 ES Mapping Schema

```json
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "index_options": {
          "type": "ivf_pq",
          "num_partitions": 4096,
          "num_sub_vectors": 96,
          "num_bits": 8
        },
        "storage": {
          "type": "s3",
          "bucket": "my-vectors",
          "prefix": "embeddings/",
          "region": "us-east-1",
          "cache_size": "10gb"
        }
      }
    }
  }
}
```

#### 5.1.3 Cluster State Metadata

```java
public class LanceIndexMetadata implements Metadata.Custom {
    private final String indexName;
    private final String fieldName;
    private final String s3Uri;
    private final long manifestVersion;
    private final Map<Integer, ShardLanceMetadata> shardMetadata;

    public static class ShardLanceMetadata {
        String manifestPath;
        long version;
        SyncState syncState;
        long lastSyncedRow;
        Set<String> fragmentIds;
    }
}
```

#### 5.1.4 Doc ID Resolution (LanceDocIdMapper)

**Problem:** Lance KNN returns `(lance_row_id, score)`, but ES needs to return documents with ES `_id`.

**Solution:** Direct ID mapping without coupling to Lucene segment internals.

```java
public class LanceDocIdMapper {
    private final IndexType indexType;
    private final String lanceIdColumn;  // For external mount

    // For external mount with ID transformation (optional)
    private final Map<String, String> idTransformCache;  // lance_id → es_doc_id

    public enum IndexType {
        ES_CREATED,      // Lance has _es_doc_id column
        EXTERNAL_DIRECT, // Use lance_id_column directly as es_doc_id
        EXTERNAL_MAPPED  // Requires ID transformation/lookup
    }

    /**
     * Resolve ES document ID from Lance query result.
     *
     * @param lanceResult Result from Lance KNN search
     * @return ES document ID
     */
    public String resolveEsDocId(LanceSearchResult lanceResult) {
        switch (indexType) {
            case ES_CREATED:
                // Read _es_doc_id column directly from Lance result
                return lanceResult.getString("_es_doc_id");

            case EXTERNAL_DIRECT:
                // Use the lance_id_column value as ES doc ID
                return lanceResult.getString(lanceIdColumn);

            case EXTERNAL_MAPPED:
                // Lookup in transformation cache (built during mount/sync)
                String lanceId = lanceResult.getString(lanceIdColumn);
                return idTransformCache.getOrDefault(lanceId, lanceId);

            default:
                throw new IllegalStateException("Unknown index type");
        }
    }

    /**
     * Resolve Lance row ID from ES document ID.
     * Used for delete/update operations.
     *
     * @param esDocId ES document ID
     * @return Lance row ID for deletion/update
     */
    public long resolveLanceRowId(String esDocId) {
        switch (indexType) {
            case ES_CREATED:
                // Query Lance for row with matching _es_doc_id
                return lanceDataset.findRowByColumn("_es_doc_id", esDocId);

            case EXTERNAL_DIRECT:
            case EXTERNAL_MAPPED:
                // Query Lance using the lance_id_column
                String lanceId = reverseTransform(esDocId);
                return lanceDataset.findRowByColumn(lanceIdColumn, lanceId);

            default:
                throw new IllegalStateException("Unknown index type");
        }
    }

    /**
     * For external mount: build ID mapping during sync if transformation needed.
     */
    public void buildIdMapping(LanceDataset dataset, IdTransformer transformer) {
        if (indexType != IndexType.EXTERNAL_MAPPED) return;

        dataset.scan(lanceIdColumn).forEach(row -> {
            String lanceId = row.getString(lanceIdColumn);
            String esDocId = transformer.transform(lanceId);
            idTransformCache.put(lanceId, esDocId);
        });
    }
}
```

**Performance Characteristics:**

| Index Type | Query Lookup | Delete/Update Lookup | Memory |
|------------|--------------|---------------------|--------|
| ES_CREATED | O(1) column read | O(log N) index scan | None |
| EXTERNAL_DIRECT | O(1) column read | O(log N) index scan | None |
| EXTERNAL_MAPPED | O(1) hash lookup | O(1) reverse lookup | ~50 bytes/row |

**Key Design Decision:** This approach decouples ES document identity from Lucene segment internals. Benefits:
- Works at write time (no need to wait for segment UUID assignment)
- Works for external mounts (no ES-specific columns required in external data)
- Survives Lucene merges without mapping updates
- Survives Lance compaction without mapping updates
- Crash recovery maintains consistency

#### 5.1.5 Lance Compaction Coordination

**Problem:** Lance compaction can remove/merge fragments while queries are in flight.

**Solution:** Simple read-write coordination without segment mapping complexity.

```java
public class LanceCompactionCoordinator {
    private final ReentrantReadWriteLock compactionLock = new ReentrantReadWriteLock();
    private final FragmentCache fragmentCache;

    /**
     * Called before query execution.
     * Acquires read lock to prevent compaction during query.
     */
    public void beforeQuery() {
        compactionLock.readLock().lock();
    }

    public void afterQuery() {
        compactionLock.readLock().unlock();
    }

    /**
     * Perform Lance compaction with proper coordination.
     * Waits for in-flight queries to complete before compacting.
     */
    public CompactionResult performCompaction(LanceDataset dataset) {
        compactionLock.writeLock().lock();
        try {
            // 1. Perform Lance compaction
            CompactionResult result = dataset.compact();

            // 2. Invalidate stale cache entries
            fragmentCache.invalidateFragments(result.removedFragments());

            // 3. Optionally warm cache with new fragments
            if (settings.warmCacheAfterCompaction()) {
                fragmentCache.warmFragments(result.newFragments());
            }

            return result;
        } finally {
            compactionLock.writeLock().unlock();
        }
    }
}
```

**Invariants:**
- Compaction never runs concurrently with queries
- Cache is invalidated atomically with compaction
- No mapping rebuild needed (IDs are stable across compaction)

**Note:** Unlike the segment-based approach, this design doesn't require tracking Lucene merges because the ID mapping is independent of Lucene segment structure.

### 5.2 API Design

#### 5.2.1 Index Creation

```http
PUT /product-vectors
{
  "settings": {
    "number_of_shards": 100,
    "index.lance.enabled": true
  },
  "mappings": {
    "properties": {
      "product_id": { "type": "keyword" },
      "name": { "type": "text" },
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "index_options": {
          "type": "ivf_pq",
          "num_partitions": 4096
        },
        "storage": {
          "type": "s3",
          "bucket": "my-data-lake",
          "prefix": "vectors/product/",
          "region": "us-east-1"
        }
      }
    }
  }
}
```

**Response:**
```json
{
  "acknowledged": true,
  "shards_acknowledged": true,
  "index": "product-vectors"
}
```

#### 5.2.2 External Index Mount

```http
PUT /product-vectors
{
  "settings": {
    "number_of_shards": 100,
    "index.lance.mount": true
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "dims": 768,
        "similarity": "cosine",
        "storage": {
          "type": "external",
          "uri": "s3://datalake/vectors/",
          "shard_path_template": "shard-{shard_id:03d}/",
          "lance_id_column": "id",
          "lance_vector_column": "vector",
          "read_only": false,
          "lazy_sync": true
        }
      }
    }
  }
}
```

**Response:**
```json
{
  "acknowledged": true,
  "index": "product-vectors",
  "_lance": {
    "mode": "mount",
    "source_uri": "s3://datalake/vectors/",
    "detected_schema": {
      "id": "Utf8",
      "vector": "FixedSizeList[Float32, 768]",
      "category": "Utf8"
    },
    "total_rows": 10000000000,
    "shards_mapped": 100,
    "validation": {
      "status": "passed",
      "checks": [
        {"check": "vector_column_exists", "status": "passed"},
        {"check": "dimensions_match", "status": "passed", "expected": 768, "actual": 768},
        {"check": "vector_type", "status": "passed", "type": "Float32"}
      ]
    }
  }
}
```

**Schema Validation Rules:**

| Check | Condition | Behavior on Failure |
|-------|-----------|---------------------|
| Vector column exists | `lance_vector_column` found in schema | Error: Cannot mount |
| Dimensions match | Lance dims == mapping `dims` | Error: Dimension mismatch |
| Vector type compatible | Float32, Float16, or Int8 | Error: Unsupported type |
| ID column exists (optional) | `lance_id_column` found if specified | Warning: Auto-generate IDs |
| Multiple vector columns | Only one vector column expected | Warning: Using first match |
| Schema stability | No schema changes since last mount | Warning: Re-validation needed |

**Validation API:**

```http
# Validate external index before mounting
POST /_lance/validate
{
  "uri": "s3://datalake/vectors/",
  "expected_dims": 768,
  "expected_vector_column": "embedding"
}

# Response
{
  "valid": true,
  "schema": { ... },
  "warnings": [],
  "errors": []
}
```

#### 5.2.3 KNN Query (Unchanged ES API)

```http
POST /product-vectors/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 100
  },
  "filter": {
    "term": { "category": "electronics" }
  }
}
```

**Response:**
```json
{
  "took": 45,
  "hits": {
    "total": { "value": 10, "relation": "eq" },
    "max_score": 0.95,
    "hits": [
      {
        "_index": "product-vectors",
        "_id": "prod_12345",
        "_score": 0.95,
        "_source": { "product_id": "prod_12345", "name": "Widget Pro" }
      }
    ]
  },
  "_lance": {
    "cache_hit_rate": 0.92,
    "partitions_searched": 10,
    "fragments_accessed": 3
  }
}
```

#### 5.2.4 Sync Status API

```http
GET /product-vectors/_lance/sync/status
```

**Response:**
```json
{
  "index": "product-vectors",
  "sync_state": "syncing",
  "progress": {
    "overall_percent": 68.2,
    "shards": {
      "0": { "state": "synced", "percent": 100 },
      "1": { "state": "syncing", "percent": 75, "rows_synced": 75000000 },
      "2": { "state": "pending", "percent": 0 }
    }
  },
  "started_at": "2026-01-06T10:00:00Z",
  "estimated_completion": "2026-01-06T10:45:00Z"
}
```

#### 5.2.5 Trigger Sync API

```http
POST /product-vectors/_lance/sync
{
  "mode": "full",           // or "incremental"
  "batch_size": 100000,
  "include_metadata": true,
  "async": true
}
```

**Response:**
```json
{
  "acknowledged": true,
  "task_id": "abc123",
  "status": "started"
}
```

#### 5.2.6 Refresh External Index

```http
POST /product-vectors/_lance/refresh
```

**Response:**
```json
{
  "acknowledged": true,
  "previous_version": 5,
  "current_version": 6,
  "new_fragments": ["fragment_042.lance", "fragment_043.lance"],
  "new_rows": 150000
}
```

#### 5.2.7 Error Response Format

All Lance API errors follow a user-friendly format that explains **what happened**, **why**, **what to do**, and **where to learn more**:

```json
{
  "error": {
    "type": "lance_error",
    "reason": "Human-readable description of what went wrong",
    "suggestion": "Actionable next step the user can take",
    "retry_after_seconds": 360,
    "docs": "https://elastic.co/docs/lance/relevant-topic"
  }
}
```

**Error Response Guidelines:**

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | Error category (e.g., `rate_limit_exception`, `s3_unavailable`, `mount_failed`) |
| `reason` | Yes | What happened, in plain language |
| `suggestion` | Yes | What the user should do next |
| `retry_after_seconds` | Conditional | Required for rate limits and temporary failures |
| `docs` | Recommended | Link to relevant documentation |
| `details` | Optional | Technical details for debugging |

**Example Error Responses:**

```http
# Rate limit exceeded
HTTP/1.1 429 Too Many Requests
{
  "error": {
    "type": "rate_limit_exception",
    "reason": "Sync rate limit exceeded for index 'my-vectors'",
    "suggestion": "Wait 6 minutes before retrying, or contact support to increase your limit",
    "retry_after_seconds": 360,
    "docs": "https://elastic.co/docs/lance/rate-limits"
  }
}

# S3 unavailable
HTTP/1.1 503 Service Unavailable
{
  "error": {
    "type": "s3_unavailable",
    "reason": "Cannot connect to S3 bucket 'my-bucket' in region 'us-east-1'",
    "suggestion": "Check your S3 bucket permissions and network connectivity. Cached data is still available.",
    "retry_after_seconds": 30,
    "docs": "https://elastic.co/docs/lance/s3-troubleshooting",
    "details": {
      "s3_error_code": "AccessDenied",
      "bucket": "my-bucket",
      "region": "us-east-1"
    }
  }
}

# Mount validation failed
HTTP/1.1 400 Bad Request
{
  "error": {
    "type": "mount_validation_failed",
    "reason": "Vector dimension mismatch: expected 768, found 384 in Lance index",
    "suggestion": "Update your mapping to use 'dims': 384, or verify you're mounting the correct S3 path",
    "docs": "https://elastic.co/docs/lance/mounting-indices",
    "details": {
      "expected_dims": 768,
      "actual_dims": 384,
      "lance_uri": "s3://bucket/vectors/"
    }
  }
}

# Circuit breaker open
HTTP/1.1 503 Service Unavailable
{
  "error": {
    "type": "circuit_breaker_open",
    "reason": "S3 circuit breaker is open due to recent failures",
    "suggestion": "The system is protecting itself from S3 overload. Queries using cached data will still work. Wait for automatic recovery.",
    "retry_after_seconds": 30,
    "docs": "https://elastic.co/docs/lance/circuit-breakers"
  }
}
```

**Implementation Note:** All error messages should be localization-ready and avoid technical jargon where possible.

### 5.3 Cache Design

#### 5.3.1 Cache Architecture

```java
public class LanceFragmentCache {
    private final LoadingCache<FragmentKey, CachedFragment> cache;
    private final AsyncCache<FragmentKey, CachedFragment> asyncCache;
    private final S3StorageAdapter s3Storage;
    private final Path localCacheDir;  // ESSD mount
    private final MeterRegistry metrics;

    // Backpressure: limit concurrent S3 downloads
    private final Semaphore downloadSemaphore;
    private final CircuitBreaker s3CircuitBreaker;

    // Async loading executor with bounded queue
    private final ExecutorService asyncLoader;

    public LanceFragmentCache(LanceCacheSettings settings) {
        this.localCacheDir = settings.getCacheDir();

        // Backpressure controls
        this.downloadSemaphore = new Semaphore(settings.getMaxConcurrentDownloads()); // default: 10
        this.s3CircuitBreaker = CircuitBreaker.of("s3-downloads",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(20)
                .build());

        // Bounded async loader to prevent OOM
        // Pool sizes are configurable based on node resources
        int corePoolSize = settings.getAsyncLoaderCoreThreads();      // default: availableProcessors / 2
        int maxPoolSize = settings.getMaxConcurrentDownloads();        // default: 10
        int queueCapacity = settings.getAsyncLoaderQueueCapacity();   // default: 100

        this.asyncLoader = new ThreadPoolExecutor(
            corePoolSize, maxPoolSize,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new ThreadPoolExecutor.CallerRunsPolicy()  // Backpressure: caller blocks
        );

        this.cache = Caffeine.newBuilder()
            .maximumWeight(settings.getMaxSizeBytes())
            .weigher((key, fragment) -> fragment.sizeBytes())
            .expireAfterAccess(settings.getTtl())
            .evictionListener(this::onEviction)
            .recordStats()
            .build(this::loadFromS3WithBackpressure);

        this.asyncCache = Caffeine.newBuilder()
            .maximumWeight(settings.getMaxSizeBytes())
            .weigher((key, fragment) -> fragment.sizeBytes())
            .expireAfterAccess(settings.getTtl())
            .buildAsync();
    }

    /**
     * Synchronous get with backpressure.
     * @throws S3UnavailableException if circuit breaker is open
     * @throws TimeoutException if download takes too long
     */
    public CachedFragment get(FragmentKey key) throws S3UnavailableException {
        return cache.get(key);
    }

    /**
     * Async get for non-blocking queries. Returns immediately with:
     * - Cached fragment if available
     * - CompletableFuture for pending download
     * - Empty if circuit breaker open (fail-fast mode)
     */
    public CompletableFuture<CachedFragment> getAsync(FragmentKey key, FailureMode mode) {
        CachedFragment cached = cache.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        if (!s3CircuitBreaker.tryAcquirePermission()) {
            if (mode == FailureMode.FAIL_FAST) {
                return CompletableFuture.failedFuture(
                    new S3UnavailableException("Circuit breaker open"));
            }
            // WAIT mode: queue for later
            return asyncCache.get(key, (k, executor) ->
                CompletableFuture.supplyAsync(() -> loadFromS3WithBackpressure(k), asyncLoader));
        }

        return asyncCache.get(key, (k, executor) ->
            CompletableFuture.supplyAsync(() -> loadFromS3WithBackpressure(k), asyncLoader));
    }

    private CachedFragment loadFromS3WithBackpressure(FragmentKey key) {
        // Check circuit breaker first
        if (!s3CircuitBreaker.tryAcquirePermission()) {
            throw new S3UnavailableException("S3 circuit breaker is open");
        }

        // Acquire semaphore with timeout (backpressure)
        boolean acquired = false;
        try {
            acquired = downloadSemaphore.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                metrics.counter("lance.cache.backpressure_timeout").increment();
                throw new S3BackpressureException("Too many concurrent S3 downloads");
            }

            metrics.counter("lance.cache.miss").increment();
            Path localPath = localCacheDir.resolve(key.toPath());

            long startTime = System.nanoTime();
            s3Storage.downloadFragment(key.s3Uri(), localPath);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            metrics.timer("lance.s3.download.latency").record(latencyMs, TimeUnit.MILLISECONDS);
            s3CircuitBreaker.onSuccess();

            return new CachedFragment(localPath, Files.size(localPath));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S3BackpressureException("Download interrupted", e);
        } catch (S3Exception e) {
            s3CircuitBreaker.onError(e);
            throw e;
        } finally {
            if (acquired) {
                downloadSemaphore.release();
            }
        }
    }

    private void onEviction(FragmentKey key, CachedFragment frag, RemovalCause cause) {
        metrics.counter("lance.cache.eviction", "cause", cause.name()).increment();
        frag.close();
        Files.deleteIfExists(localCacheDir.resolve(key.toPath()));
    }

    public enum FailureMode {
        FAIL_FAST,  // Return error immediately if S3 unavailable
        WAIT        // Queue request and wait for S3 to recover
    }
}
```

#### 5.3.2 Cache Invalidation Strategy

| Event | Invalidation Action |
|-------|---------------------|
| New fragment written | Add to cache (warm) |
| Manifest update | Invalidate stale fragments |
| Compaction | Invalidate old fragments, warm new |
| Delete | Invalidate affected fragments |
| TTL expiry | Automatic eviction |
| Memory pressure | LRU eviction |

**Race Condition Prevention:**

Cache reads include manifest version to ensure consistency:

```java
public CachedFragment getWithVersion(FragmentKey key, long expectedManifestVersion) {
    CachedFragment fragment = cache.get(key);
    if (fragment.manifestVersion() < expectedManifestVersion) {
        // Fragment may be stale, re-validate
        cache.invalidate(key);
        return cache.get(key);
    }
    return fragment;
}
```

### 5.4 Deletion Handling

#### 5.4.1 Delete Flow: ES → Lance

When a document is deleted via ES API:

```
1. DELETE /my-index/_doc/doc123
2. ES marks document deleted in Lucene (soft delete)
3. LanceDeleteHandler resolves doc123 → lance_row_id using LanceDocIdMapper
4. On next flush:
   a. Write deletion to Lance deletion bitmap (_deletions/bitmap_N.arrow)
   b. Update manifest to reference new bitmap
5. Lance queries automatically filter deleted vectors
```

```java
public class LanceDeleteHandler {
    private final Set<Long> pendingDeletes = ConcurrentHashMap.newKeySet();
    private final LanceDocIdMapper docIdMapper;
    private final S3StorageAdapter s3Storage;

    public void onDocumentDelete(String esDocId) {
        // Resolve ES doc ID to Lance row ID using the doc ID mapper
        long lanceRowId = docIdMapper.resolveLanceRowId(esDocId);
        pendingDeletes.add(lanceRowId);
    }

    public void flushDeletes(LanceDataset dataset) {
        if (pendingDeletes.isEmpty()) return;

        // Write deletion bitmap to S3
        DeletionBitmap bitmap = DeletionBitmap.from(pendingDeletes);
        String bitmapPath = dataset.getNextDeletionPath();
        s3Storage.writeDeletionBitmap(bitmapPath, bitmap);

        // Update manifest atomically
        dataset.updateManifest(manifest ->
            manifest.addDeletionFile(bitmapPath, pendingDeletes.size()));

        pendingDeletes.clear();
    }
}
```

#### 5.4.2 Delete Flow: Lance → ES (External Updates)

When Lance index is modified externally (e.g., data lake ETL):

```
1. External process adds deletion bitmap to Lance
2. ES detects manifest version change (polling or notification)
3. POST /_lance/refresh triggers sync
4. ES reads new deletion bitmap
5. Resolve deleted lance_row_ids → ES doc IDs using LanceDocIdMapper
6. Mark corresponding Lucene docs as deleted by ES _id
7. Lucene merge eventually removes deleted docs
```

```java
public class ExternalDeleteSyncHandler {
    private final LanceDocIdMapper docIdMapper;

    public void syncDeletions(LanceDataset dataset, IndexWriter indexWriter) {
        // Get deletions added since last sync
        long lastSyncedVersion = getLastSyncedManifestVersion();
        DeletionBitmap newDeletions = dataset.getDeletionsSince(lastSyncedVersion);

        for (long lanceRowId : newDeletions) {
            // Read the ID column value for this row from Lance
            String lanceId = dataset.readColumn(lanceRowId, docIdMapper.getLanceIdColumn());

            // Resolve to ES document ID
            String esDocId = docIdMapper.resolveEsDocIdFromLanceId(lanceId);

            // Delete by ES _id (using term query)
            indexWriter.deleteDocuments(new Term("_id", esDocId));
        }

        updateLastSyncedManifestVersion(dataset.getManifestVersion());
    }
}
```

**Note:** This approach uses ES _id for deletion, which is independent of Lucene's internal segment structure. The deletion works correctly regardless of Lucene merges.

#### 5.4.3 Delete Consistency Guarantees

| Scenario | Guarantee |
|----------|-----------|
| ES delete, then query | Deleted doc not returned (after flush) |
| ES delete, crash before flush | Delete lost, doc reappears |
| External delete, ES query | Deleted doc returned until refresh |
| Concurrent ES + external delete | Both applied, no duplicates |

**Delete Visibility Latency:**
- ES → Lance: Visible after flush (configurable, default 1s)
- Lance → ES: Visible after `_lance/refresh` (manual or scheduled)

#### 5.4.4 Update Flow

Updates to documents with vectors are handled as **delete + insert** operations:

```
1. POST /my-index/_update/doc123 { "doc": { "embedding": [...] } }
2. LanceUpdateHandler receives (esDocId, newVector)
3. LanceUpdateHandler:
   a. Resolve esDocId → lance_row_id using LanceDocIdMapper
   b. Record deletion for old vector (add lance_row_id to deletion bitmap)
   c. Buffer new vector with esDocId for insertion
4. On flush:
   a. Write deletion bitmap update
   b. Write new fragment with updated vector (includes _es_doc_id)
   c. Update manifest atomically (both changes)
5. Query sees updated vector (old deleted, new visible)
```

```java
public class LanceUpdateHandler {
    private final LanceDeleteHandler deleteHandler;
    private final LanceWriteBuffer writeBuffer;
    private final LanceDocIdMapper docIdMapper;

    public void onDocumentUpdate(String esDocId, float[] newVector) {
        // Step 1: Mark old vector as deleted (uses esDocId → lance_row_id lookup)
        deleteHandler.onDocumentDelete(esDocId);

        // Step 2: Buffer new vector with ES doc ID
        // The new row will have _es_doc_id = esDocId
        writeBuffer.addVector(esDocId, newVector);
    }

    /**
     * Partial update: only non-vector fields changed.
     * No Lance operation needed - vector remains unchanged.
     */
    public void onPartialUpdate(String esDocId, Map<String, Object> fields) {
        if (!fields.containsKey(vectorFieldName)) {
            // No vector update - nothing to do in Lance
            return;
        }
        // Vector included - treat as full update
        float[] newVector = (float[]) fields.get(vectorFieldName);
        onDocumentUpdate(esDocId, newVector);
    }
}
```

**Note:** The update flow no longer requires resolving Lucene segment information. The ES doc ID is sufficient to identify the old vector (for deletion) and tag the new vector (for future lookups).

**Update Semantics:**

| Operation | Vector Changed | Lance Action |
|-----------|----------------|--------------|
| `_update` with vector | Yes | Delete old + Insert new |
| `_update` without vector | No | No Lance operation |
| `_update_by_query` with vector | Yes | Batch delete + insert |
| `index` (overwrite) | Yes | Delete old + Insert new |

**Atomicity Guarantee:** Delete and insert are committed together in the same manifest update. Queries never see a state where the old vector is deleted but the new one isn't visible.

### 5.5 Durability and Write Buffer Recovery

#### 5.5.1 Write Buffer Architecture

Vectors are buffered in memory before flushing to S3:

```java
public class LanceWriteBuffer {
    private final List<PendingVector> buffer = new ArrayList<>();
    private final int flushThreshold;  // default: 100,000 vectors
    private final Path walPath;        // Write-ahead log on local disk

    /**
     * Add a vector to the write buffer.
     * Only requires ES doc ID and vector - no Lucene segment information needed.
     */
    public void addVector(String esDocId, float[] vector) {
        PendingVector pv = new PendingVector(esDocId, vector);

        // Write to WAL first (durability)
        appendToWAL(pv);

        buffer.add(pv);

        if (buffer.size() >= flushThreshold) {
            flush();
        }
    }

    private void appendToWAL(PendingVector pv) {
        try (var wal = new FileOutputStream(walPath.toFile(), true)) {
            wal.write(pv.serialize());
            wal.getFD().sync();  // fsync for durability
        }
    }

    public void flush() {
        if (buffer.isEmpty()) return;

        // Write fragment to S3 with _es_doc_id column
        LanceFragment fragment = LanceFragment.builder()
            .addColumn("_es_doc_id", buffer.stream().map(PendingVector::esDocId).toList())
            .addColumn("_vector", buffer.stream().map(PendingVector::vector).toList())
            .build();
        s3Storage.writeFragment(fragment);

        // Update manifest
        updateManifest(fragment);

        // Clear buffer and truncate WAL
        buffer.clear();
        truncateWAL();
    }
}
```

**Key Design Point:** The write buffer only stores `(esDocId, vector)` pairs. No Lucene segment information is needed because:
1. Lance row IDs are auto-generated by Lance
2. The `_es_doc_id` column enables lookup from ES doc ID
3. Query results read `_es_doc_id` directly from Lance

#### 5.5.2 Crash Recovery

On node startup, recover unflushed vectors from WAL:

```java
public class LanceRecoveryHandler {
    public void recover(Path walPath, LanceWriteBuffer writeBuffer) {
        if (!Files.exists(walPath)) return;

        logger.info("Recovering Lance vectors from WAL: {}", walPath);

        try (var wal = new FileInputStream(walPath.toFile())) {
            while (wal.available() > 0) {
                PendingVector pv = PendingVector.deserialize(wal);

                // Verify vector not already in Lance (query by _es_doc_id)
                if (!isVectorInLance(pv.esDocId())) {
                    writeBuffer.addVectorWithoutWAL(pv);
                }
            }
        }

        // Flush recovered vectors
        if (writeBuffer.size() > 0) {
            logger.info("Flushing {} recovered vectors", writeBuffer.size());
            writeBuffer.flush();
        }

        // Truncate WAL after successful recovery
        Files.delete(walPath);
    }

    /**
     * Check if a vector with this ES doc ID already exists in Lance.
     * Uses a scan with filter on _es_doc_id column.
     */
    private boolean isVectorInLance(String esDocId) {
        return lanceDataset.count("_es_doc_id = '" + esDocId + "'") > 0;
    }
}
```

**Recovery Consistency:** After recovery, the state is consistent because:
1. WAL contains `(esDocId, vector)` pairs - no segment references
2. Recovered vectors are written with their original `_es_doc_id`
3. Queries use `_es_doc_id` for lookup - no stale segment references
4. Duplicate detection uses `_es_doc_id` column scan in Lance

#### 5.5.3 Durability Guarantees

| Configuration | Durability | Data at Risk | Latency | Use Case |
|---------------|------------|--------------|---------|----------|
| WAL + sync every write | No data loss | None | +1-2ms/write | Critical data |
| WAL + sync interval (default) | Loss up to sync_interval | Up to 1s of writes | ~0.5ms/write | Production |
| WAL disabled | Loss up to flush interval | Up to 30s of writes | Fastest | Bulk import |
| Sync flush | S3-durable immediately | None | +50-100ms/write | Financial data |

**Durability Modes Explained:**

```yaml
# Mode 1: Maximum durability (fsync every write)
index.lance.wal.enabled: true
index.lance.wal.sync_on_write: true     # fsync after every vector
# Risk: None. Latency: +1-2ms per write.

# Mode 2: Balanced (default) - fsync periodically
index.lance.wal.enabled: true
index.lance.wal.sync_interval: 1s       # fsync every 1 second
# Risk: Up to 1s of writes on crash. Latency: minimal.

# Mode 3: Performance - no WAL
index.lance.wal.enabled: false
index.lance.flush_interval: 30s
# Risk: Up to 30s of writes on crash. Latency: best.

# Mode 4: S3-durable immediately
index.lance.flush_on_write: true        # Flush to S3 after every batch
# Risk: None after S3 ACK. Latency: +50-100ms per batch.
```

**Settings:**
```yaml
index.lance.wal.enabled: true           # Enable write-ahead log
index.lance.wal.sync_on_write: false    # fsync every write (highest durability)
index.lance.wal.sync_interval: 1s       # WAL fsync interval (if sync_on_write=false)
index.lance.flush_threshold: 100000     # Vectors before S3 flush
index.lance.flush_interval: 30s         # Max time before flush
```

### 5.6 Sync State Machine

#### 5.6.1 States

```
                                    external update
                              ┌──────────────────────────┐
                              │                          │
                              ▼                          │
┌─────────────┐    sync start    ┌──────────┐    success    ┌────────┐
│ NOT_SYNCED  │─────────────────▶│ SYNCING  │──────────────▶│ SYNCED │
└─────────────┘                  └──────────┘               └────────┘
       │                              │                          │
       │      already synced          │ failure                  │
       │    ┌─────────────────────────┼──────────────────────────┤
       │    │                         ▼                          │
       │    │                   ┌───────────┐                    │
       │    │                   │  FAILED   │                    │
       │    │                   └───────────┘                    │
       │    │                         │                          │
       │    │                         │ retry                    │
       │    │                         ▼                          │
       └────┴─────────────────────────┴──────────────────────────┘

                              timeout (2hr)
       ┌─────────────────────────────────────────────────────────┐
       │                                                         │
       │    SYNCING ──────────────────────────────────────▶ TIMEOUT
       │                                                         │
       └─────────────────────────────────────────────────────────┘
```

**State Transitions:**

| From | To | Trigger | Action |
|------|-----|---------|--------|
| NOT_SYNCED | SYNCING | `POST /_lance/sync` | Start sync job |
| NOT_SYNCED | SYNCED | Mount with existing mapping | Skip sync (lazy mode) |
| SYNCING | SYNCED | Sync completes | Enable full ES features |
| SYNCING | FAILED | S3 error, OOM, etc. | Log error, allow retry |
| SYNCING | TIMEOUT | No progress for 2hr | Cancel job, allow retry |
| SYNCED | SYNCING | External update detected | Re-sync incremental |
| FAILED | SYNCING | `POST /_lance/sync` retry | Restart from checkpoint |
| TIMEOUT | SYNCING | `POST /_lance/sync` retry | Restart from checkpoint |

#### 5.6.2 Write Blocking During Sync

```java
public class SyncAwareWriteCoordinator {
    private final SyncStateMachine syncState;

    public void indexDocument(IndexRequest request) throws SyncInProgressException {
        SyncState state = syncState.getState();

        switch (state) {
            case SYNCING:
                throw new SyncInProgressException(
                    "Index is syncing. Writes blocked. " +
                    "Progress: " + syncState.getProgress() + "%"
                );
            case NOT_SYNCED:
            case SYNCED:
                processWrite(request);
                break;
            case FAILED:
                // Allow writes, but warn
                logger.warn("Sync failed, proceeding with write");
                processWrite(request);
                break;
        }
    }
}
```

#### 5.6.3 Rate Limiting for Lance APIs

The `_lance/*` APIs are rate-limited to prevent resource exhaustion:

```java
public class LanceApiRateLimiter {
    // Cluster-wide sync concurrency limit
    private static final int MAX_CONCURRENT_SYNCS = 3;

    // Per-index rate limits
    private static final int MAX_REFRESH_PER_MINUTE = 60;
    private static final int MAX_SYNC_REQUESTS_PER_HOUR = 10;

    private final Semaphore syncSemaphore;
    private final RateLimiter refreshLimiter;
    private final Map<String, RateLimiter> indexSyncLimiters;

    public void beforeSync(String indexName) throws RateLimitedException {
        // Check cluster-wide limit
        if (!syncSemaphore.tryAcquire()) {
            throw new RateLimitedException(
                "Max concurrent syncs (" + MAX_CONCURRENT_SYNCS + ") reached. " +
                "Wait for existing syncs to complete.");
        }

        // Check per-index limit
        RateLimiter indexLimiter = indexSyncLimiters.computeIfAbsent(
            indexName, k -> RateLimiter.create(MAX_SYNC_REQUESTS_PER_HOUR / 3600.0));

        if (!indexLimiter.tryAcquire()) {
            syncSemaphore.release();
            throw new RateLimitedException(
                "Sync rate limit for index " + indexName + " exceeded. " +
                "Max " + MAX_SYNC_REQUESTS_PER_HOUR + " syncs per hour.");
        }
    }

    public void afterSync() {
        syncSemaphore.release();
    }

    public void beforeRefresh(String indexName) throws RateLimitedException {
        if (!refreshLimiter.tryAcquire()) {
            throw new RateLimitedException(
                "Refresh rate limit exceeded. Max " + MAX_REFRESH_PER_MINUTE + " per minute.");
        }
    }
}
```

**Distributed Lock for Sync:**

Only one sync per index can run cluster-wide:

```java
public class DistributedSyncLock {
    private final ClusterService clusterService;

    public boolean tryAcquire(String indexName, TimeValue timeout) {
        // Use cluster state custom metadata as distributed lock
        String lockId = "lance_sync_lock_" + indexName;

        return clusterService.submitStateUpdateTask("acquire_lance_sync_lock",
            new AcquireLockTask(lockId, nodeId(), timeout));
    }

    public void release(String indexName) {
        String lockId = "lance_sync_lock_" + indexName;
        clusterService.submitStateUpdateTask("release_lance_sync_lock",
            new ReleaseLockTask(lockId));
    }
}
```

**Rate Limit Settings:**

```yaml
# elasticsearch.yml
lance.api.sync.max_concurrent: 3              # Cluster-wide concurrent syncs
lance.api.sync.max_per_index_per_hour: 10     # Per-index sync limit
lance.api.refresh.max_per_minute: 60          # Cluster-wide refresh limit
lance.api.sync.lock_timeout: 2h               # Auto-release lock after timeout
```

**API Response on Rate Limit:**

```http
POST /my-index/_lance/sync

# 429 Too Many Requests
{
  "error": {
    "type": "rate_limit_exception",
    "reason": "Sync rate limit exceeded for index 'my-index'",
    "suggestion": "Wait 6 minutes before retrying, or contact support to increase your limit",
    "retry_after_seconds": 360,
    "docs": "https://elastic.co/docs/lance/rate-limits"
  }
}
```

See [Section 5.2.7 Error Response Format](#527-error-response-format) for complete error handling guidelines.

### 5.7 IVF-PQ Index Configuration

#### 5.7.1 Sizing Guidelines

| Dataset Size | num_partitions | num_sub_vectors | Memory/Vector |
|-------------|----------------|-----------------|---------------|
| 1M | 256 | 64 | 8 bytes |
| 100M | 1024 | 96 | 8 bytes |
| 1B | 4096 | 96 | 8 bytes |
| 10B | 16384 | 96 | 8 bytes |

#### 5.7.2 Query Parameters

```java
public class IVFPQSearchParams {
    int nprobe = 10;           // Partitions to search (higher = better recall, slower)
    int refineK = 2;           // Rerank factor (k * refineK candidates rescored)
    float oversampling = 1.5f; // Oversample factor for filters
}
```

#### 5.7.3 IVF-PQ Training

IVF-PQ indices require training on representative data:

| Training Trigger | Behavior | Use Case |
|------------------|----------|----------|
| Index creation with sample data | Train immediately | Pre-loaded data lake |
| After N vectors (default: 50K) | Auto-train on first N | Incremental indexing |
| Manual `POST /_lance/train` | Force retrain | Drift correction |
| External index mount | Use existing training | Mount mode |

**Training Settings:**
```yaml
index.lance.ivf_pq.auto_train: true
index.lance.ivf_pq.training_sample_size: 50000
index.lance.ivf_pq.training_timeout: 30m
```

**Note:** Until training completes, queries use brute-force search (slower but correct).

### 5.8 Filter Pushdown

#### 5.8.1 Supported Filters (Pushed to Lance)

| ES Filter Type | Lance SQL Equivalent | Notes |
|----------------|---------------------|-------|
| `term` | `column = 'value'` | Exact match |
| `terms` | `column IN ('a', 'b')` | Multiple values |
| `range` (numeric) | `column >= x AND column <= y` | Inclusive bounds |
| `range` (date) | `column >= timestamp` | Converted to epoch |
| `bool.must` | `AND` | All conditions |
| `bool.should` | `OR` | Any condition |
| `bool.must_not` | `NOT` | Negation |
| `exists` | `column IS NOT NULL` | Field presence |
| `prefix` (keyword) | `column LIKE 'prefix%'` | String prefix |

#### 5.8.2 Unsupported Filters (Post-filtered in ES)

| ES Filter Type | Reason | Impact |
|----------------|--------|--------|
| `geo_*` | Not in Lance columnar format | Requires ES post-filter |
| `nested` | Complex document structure | Requires ES post-filter |
| `script` | Arbitrary code execution | Cannot translate |
| `fuzzy` | Edit distance not in Lance | Requires ES post-filter |
| `regexp` | Complex patterns | Requires ES post-filter |
| `wildcard` (internal) | `*foo*` patterns | Requires ES post-filter |

#### 5.8.3 Hybrid Execution Strategy

```java
public class HybridFilterExecutor {
    public SearchResults execute(KnnQuery knn, QueryBuilder filter) {
        // 1. Analyze filter for pushdown
        FilterAnalysis analysis = analyzeFilter(filter);

        // 2. Push supported filters to Lance
        String lanceSql = analysis.getPushableAsSql();
        int oversampledK = (int)(knn.k() * analysis.getOversampleFactor());

        // 3. Execute Lance KNN with pre-filter
        LanceResults lanceResults = lanceSearch(
            knn.queryVector(),
            oversampledK,
            lanceSql
        );

        // 4. Post-filter non-pushable conditions in ES
        if (analysis.hasNonPushableFilters()) {
            return postFilter(lanceResults, analysis.getNonPushableFilter());
        }

        return lanceResults.limit(knn.k());
    }

    /**
     * Calculate oversample factor based on filter selectivity.
     * More restrictive post-filters need more candidates from Lance.
     */
    private float calculateOversampleFactor(FilterAnalysis analysis) {
        if (!analysis.hasNonPushableFilters()) {
            return 1.0f;  // No oversampling needed
        }

        // Estimate selectivity (fraction passing filter)
        float estimatedSelectivity = analysis.estimateSelectivity();

        // Oversample inversely proportional to selectivity
        // e.g., 10% selectivity → 10x oversample → ensure we get k results
        return Math.min(10.0f, 1.0f / estimatedSelectivity);
    }
}
```

#### 5.8.4 Filter Pushdown Configuration

```yaml
index.lance.filter_pushdown.enabled: true
index.lance.filter_pushdown.max_terms: 1000      # Max terms in IN clause
index.lance.filter_pushdown.oversample_max: 10   # Max oversample factor
```

#### 5.8.5 Lance Native Filter Pushdown (Implemented v1)

**Status**: ✅ Implemented (2026-02-09)

Lance SDK v1.0.0-beta.2+ supports native SQL filtering via `ScanOptions.filter()`. This enables pushing ES filters down to Lance for **prefiltering** (applied BEFORE vector search), significantly reducing search space and improving performance.

**Architecture**:

```
ES Query DSL
    ↓
LanceKnnQueryBuilder (parse filter)
    ↓
LanceKnnQuery (decide strategy)
    ↓
EsToLanceFilterConverter (new component)
    ↓  (if conversion succeeds)
SQL WHERE clause
    ↓
ScanOptions.filter(sql) ← NATIVE PREFILTER
    ↓
Lance vector search on reduced dataset
```

**Implementation Status (v1 - Minimal)**:

| Feature | Status | Notes |
|---------|--------|-------|
| Term queries | ✅ Implemented | String, boolean, numeric values |
| SQL escaping | ✅ Implemented | Single quotes doubled (`O'Reilly` → `O''Reilly'`) |
| Field mapping | ✅ Implemented | `storage.field_mapping` maps ES fields → Lance columns |
| Error handling | ✅ Hybrid | Try pushdown → fallback to ES post-filter |
| terms query | ❌ TODO | IN clause support |
| range query | ❌ TODO | Numeric/date range support |
| bool queries | ❌ TODO | AND/OR/NOT logic |

**Configuration**:

```json
{
  "mappings": {
    "properties": {
      "my_vector": {
        "type": "lance_vector",
        "storage": {
          "uri": "oss://bucket/products.lance",
          "field_mapping": "category=product_category,brand=brand_name"
        }
      }
    }
  }
}
```

**Example Query**:

```bash
# ES query with term filter
curl -X POST "localhost:9200/products/_search" -H 'Content-Type: application/json' -d'
{
  "knn": {
    "field": "my_vector",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "filter": { "term": { "category": "electronics" } }
  }
}'

# Lance receives SQL filter
ScanOptions.Builder()
    .columns(List.of("_id"))
    .nearest(vectorQuery)
    .filter("product_category = 'electronics'")  # NATIVE PREFILTER!
    .build();
```

**Fallback Behavior**:

| Scenario | Behavior |
|----------|----------|
| Field not in mapping | Warning logged → ES post-filter |
| Unsupported query type | Warning logged → ES post-filter |
| SQL conversion fails | Warning logged → ES post-filter |
| No field_mapping configured | Skip pushdown → ES post-filter (current behavior) |

**Security**:

All string values are SQL-escaped to prevent injection:
- `O'Reilly` → `O''Reilly` (single quotes doubled)
- `${jndi:ldap://evil.com}` → Quoted as literal string
- `'; DROP TABLE--` → Escaped, treated as literal text

**Performance Impact**:

Prefiltering reduces the vector search space before expensive calculations:
- **Without prefilter**: Lance searches entire dataset, ES filters results
- **With prefilter**: Lance searches only matching rows, ~10-100x faster for selective filters

**Testing**:

- Unit tests: `EsToLanceFilterConverterTests` (385 lines, comprehensive edge cases)
- Integration tests: `LanceFilterPushdownIntegrationTests` (field mapping, SQL escaping, fallback)
- Validation: `LV-50` to `LV-55` in `reg_validation_guide.md`

### 5.9 Native Memory Management

#### 5.9.1 Architecture

Lance uses JNI to call Rust code, which allocates memory outside the JVM heap:

```
┌─────────────────────────────────────────────────────────────────┐
│                           JVM Process                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────┐    ┌────────────────────────────────┐ │
│  │      JVM Heap       │    │        Native Memory           │ │
│  │  ┌───────────────┐  │    │  ┌──────────────────────────┐  │ │
│  │  │ ES Objects    │  │    │  │ Lance Rust Runtime       │  │ │
│  │  │ Java Objects  │  │    │  │ - Vector buffers         │  │ │
│  │  │ Thread stacks │  │    │  │ - IVF-PQ codebooks       │  │ │
│  │  └───────────────┘  │    │  │ - Query scratch space    │  │ │
│  │                     │    │  └──────────────────────────┘  │ │
│  │  Monitored by ES    │    │  ┌──────────────────────────┐  │ │
│  │  Circuit Breaker    │    │  │ Arrow Memory Pool        │  │ │
│  │                     │    │  │ - RecordBatch buffers    │  │ │
│  └─────────────────────┘    │  │ - IPC serialization      │  │ │
│                              │  └──────────────────────────┘  │ │
│                              │                                │ │
│                              │  NOT monitored by default      │ │
│                              └────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

#### 5.9.2 Memory Tracking Integration

```java
public class LanceMemoryTracker implements CircuitBreakerService.Listener {
    private final AtomicLong nativeMemoryUsed = new AtomicLong();
    private final CircuitBreaker parentBreaker;
    private final long maxNativeMemory;

    public LanceMemoryTracker(CircuitBreakerSettings settings) {
        this.maxNativeMemory = settings.getLanceMaxNativeMemory();
        this.parentBreaker = settings.getParentBreaker();
    }

    /**
     * Called before native allocation.
     * @throws CircuitBreakingException if limit exceeded
     */
    public void reserveMemory(long bytes, String label) {
        long current = nativeMemoryUsed.addAndGet(bytes);

        if (current > maxNativeMemory) {
            nativeMemoryUsed.addAndGet(-bytes);
            throw new CircuitBreakingException(
                "Lance native memory limit exceeded: " + current + "/" + maxNativeMemory,
                Durability.TRANSIENT
            );
        }

        // Also check parent breaker (total memory)
        parentBreaker.addEstimateBytesAndMaybeBreak(bytes, label);
    }

    public void releaseMemory(long bytes) {
        nativeMemoryUsed.addAndGet(-bytes);
        parentBreaker.addWithoutBreaking(-bytes);
    }

    /**
     * Periodic sync with actual native memory usage (drift correction).
     * Called every 30 seconds.
     */
    public void syncWithActualUsage() {
        long actualUsage = LanceNative.getMemoryUsage();
        long tracked = nativeMemoryUsed.get();

        if (Math.abs(actualUsage - tracked) > 10_000_000) { // 10MB drift threshold
            logger.warn("Lance memory tracking drift: tracked={} actual={}",
                tracked, actualUsage);
            nativeMemoryUsed.set(actualUsage);
        }
    }
}
```

#### 5.9.3 Arrow Memory Pool Configuration

```java
public class LanceArrowConfig {
    /**
     * Configure Arrow to use a bounded memory pool that integrates
     * with ES memory tracking.
     */
    public static BufferAllocator createAllocator(LanceMemoryTracker tracker) {
        return new RootAllocator(
            AllocationListener.NOOP,
            tracker.getMaxArrowMemory(),
            new LanceAllocationManager(tracker)
        );
    }

    /**
     * Custom allocation manager that reports to ES circuit breaker.
     */
    static class LanceAllocationManager implements AllocationManager {
        private final LanceMemoryTracker tracker;

        @Override
        public ArrowBuf allocate(long size) {
            tracker.reserveMemory(size, "arrow-buffer");
            return super.allocate(size);
        }

        @Override
        public void release(ArrowBuf buffer) {
            tracker.releaseMemory(buffer.capacity());
            super.release(buffer);
        }
    }
}
```

#### 5.9.4 Memory Settings

```yaml
# elasticsearch.yml
indices.breaker.lance.native.limit: 30%      # Of total memory, default 30%
indices.breaker.lance.native.overhead: 1.0   # No overhead multiplier
indices.breaker.lance.arrow.limit: 10%       # Arrow buffer pool limit

# Per-index settings
index.lance.query.max_memory: 100mb          # Per-query memory limit
index.lance.codebook.cache_size: 500mb       # IVF-PQ codebook cache
```

#### 5.9.5 Memory Pressure Handling

| Pressure Level | Action |
|----------------|--------|
| Normal (<70%) | Full functionality |
| Warning (70-85%) | Reduce batch sizes, log warning |
| High (85-95%) | Reject new queries, evict caches |
| Critical (>95%) | Force GC, restart shard if needed |

### 5.10 Shard-to-Storage Mapping

#### 5.10.1 Mapping Strategy

Each ES shard owns a separate Lance dataset in S3:

```
S3 Layout:
s3://bucket/vectors/<index-name>/
├── shard-000/
│   ├── _latest.manifest
│   ├── _versions/
│   └── data/
├── shard-001/
│   └── ...
└── shard-099/
    └── ...
```

**Mapping Rules:**

| Scenario | Mapping | Notes |
|----------|---------|-------|
| New ES index | 1 shard = 1 Lance dataset | Created on first write |
| External mount (matching) | 1 shard = 1 Lance partition | Direct mapping |
| External mount (non-matching) | Virtual partitioning | See 5.10.2 |
| Shard relocation | Cache follows shard | See 5.10.3 |

#### 5.10.1.1 Configurable Sharding Strategy

**Problem**: Lance datasets may be sharded using different algorithms than ES's default Murmur3 hash. Without knowing the sharding algorithm, ES cannot efficiently filter candidates to the current shard.

**Solution**: Configurable `ShardingStrategy` parameter in storage configuration:

**ShardingStrategy Options:**

| Strategy | Description | Use Case |
|----------|-------------|----------|
| `NONE` | No candidate filtering | Unsharded datasets, unknown sharding, or custom algorithms |
| `ES_ROUTING` | Filter using ES's Murmur3 hash | Lance datasets sharded with ES's algorithm |

**Configuration Example:**

```json
{
  "storage": {
    "type": "external",
    "uri_prefix": "s3://datalake/vectors/",
    "shard_path": "{index}/shard-{shard_id}",
    "dataset_name": "vectors.lance",
    "sharding_strategy": "ES_ROUTING"
  }
}
```

**ES Routing Algorithm:**

```java
// ES uses Murmur3 hash function for document routing
int shardId = Math.abs(Murmur3HashFunction.hash(documentId)) % numShards;

// Same algorithm used for candidate filtering
int candidateShardId = Math.abs(Murmur3HashFunction.hash(candidateId)) % numShards;

// Only include candidates belonging to current shard
if (shardId == candidateShardId) {
    // Include candidate
}
```

**Implementation:**

- `LanceStorageConfig.ShardingStrategy` enum stores the configured strategy
- `LanceKnnQuery.filterCandidatesByShard()` applies filtering based on strategy
- Defaults: `NONE` for legacy mode, `ES_ROUTING` for shard-aware mode

**Creating Lance Datasets with ES Routing:**

```python
from org.elasticsearch.cluster.routing.Murmur3HashFunction import hash

def shard_document(document_id: str, num_shards: int) -> int:
    """Shard document using ES's Murmur3 hash function."""
    return abs(hash(document_id)) % num_shards

# Create sharded dataset
for doc in documents:
    shard_id = shard_document(doc['_id'], num_shards=3)
    shard_datasets[shard_id].add(doc)
```

**Benefits:**
- 1:1 mapping between ES shards and Lance datasets when algorithms match
- Optimal performance: each shard only searches its own dataset
- Flexibility: can disable filtering for incompatible sharding schemes

#### 5.10.2 External Index with Non-Matching Partitions

When mounting an external Lance index that wasn't partitioned for ES:

```java
public class VirtualPartitionMapper {
    /**
     * Maps ES shard to a range of Lance row IDs.
     * Used when external index has different partitioning than ES shard count.
     */
    public static class ShardRange {
        final long startRowId;    // Inclusive
        final long endRowId;      // Exclusive
        final List<String> fragmentIds;  // Fragments overlapping this range
    }

    public ShardRange getShardRange(int shardId, int totalShards, long totalRows) {
        long rowsPerShard = totalRows / totalShards;
        long startRowId = shardId * rowsPerShard;
        long endRowId = (shardId == totalShards - 1) ? totalRows : startRowId + rowsPerShard;

        // Find fragments that overlap this range
        List<String> fragments = findOverlappingFragments(startRowId, endRowId);

        return new ShardRange(startRowId, endRowId, fragments);
    }
}
```

**External Mount Options:**

```json
{
  "storage": {
    "type": "external",
    "uri": "s3://datalake/vectors/",
    "partition_mode": "virtual",        // or "directory"
    "partition_column": "shard_id",     // If data has partition column
    "row_range_mode": true              // Partition by row ID ranges
  }
}
```

#### 5.10.3 Shard Relocation and Cache Warming

When a shard relocates to a new node:

```
1. Shard relocation starts (source → target)
2. Target node receives shard routing update
3. LanceShardRelocator on target:
   a. Downloads manifest from S3
   b. Optionally pre-warms cache (configurable)
   c. Shard becomes active on target
4. Source node:
   a. Evicts cache entries for relocated shard
   b. Releases S3 resources
```

```java
public class LanceShardRelocator {
    private final FragmentCache cache;
    private final S3StorageAdapter s3;

    public void onShardStarted(ShardRouting shardRouting) {
        String shardPath = getShardS3Path(shardRouting);

        // Load manifest
        LanceManifest manifest = s3.readManifest(shardPath);

        if (settings.getCacheWarmingOnRelocation()) {
            // Pre-warm frequently accessed fragments
            warmCache(manifest.getHotFragments());
        }
    }

    public void onShardStopped(ShardRouting shardRouting) {
        // Evict all cache entries for this shard
        cache.evictShard(shardRouting.shardId());
    }
}
```

**Settings:**
```yaml
index.lance.shard_relocation.cache_warming: true    # Pre-warm cache on relocation
index.lance.shard_relocation.warm_fragments: 10     # Number of hot fragments to warm
```

### 5.11 Source Field Handling

#### 5.11.1 Vector Storage in _source

By default, vectors are **not duplicated** in Lucene `_source`:

```json
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "store_in_source": false,    // Default: false (save storage)
        "dims": 768
      }
    }
  }
}
```

**Behavior by Setting:**

| `store_in_source` | _source contains vector | GET /_doc returns vector | Storage impact |
|-------------------|-------------------------|--------------------------|----------------|
| `false` (default) | No | Yes (fetched from Lance) | Optimal |
| `true` | Yes | Yes (from _source) | 2x vector storage |

#### 5.11.2 Vector Retrieval

When retrieving documents, vectors are fetched from Lance on-demand:

```java
public class LanceSourceLoader {
    public Map<String, Object> loadSource(String index, String docId, boolean includeVector) {
        // Load non-vector fields from Lucene _source
        Map<String, Object> source = luceneSourceLoader.load(docId);

        if (includeVector && !storeInSource) {
            // Fetch vector from Lance
            float[] vector = lanceReader.getVector(docId);
            source.put(vectorFieldName, vector);
        }

        return source;
    }
}
```

**API Behavior:**

```http
# Get document - includes vector (fetched from Lance)
GET /my-index/_doc/doc123
{
  "_source": {
    "product_id": "prod_123",
    "name": "Widget",
    "embedding": [0.1, 0.2, ...]   // Fetched from Lance
  }
}

# Search with _source filtering
POST /my-index/_search
{
  "knn": { ... },
  "_source": {
    "excludes": ["embedding"]    // Skip vector fetch for performance
  }
}
```

**Performance Note:** Excluding vectors from `_source` in search results avoids Lance lookups, improving response time.

---

## 6. Consistency Model

### 6.1 Overview

Lance-ES provides **eventual consistency** with configurable durability trade-offs:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Consistency Timeline                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Write ──┬── WAL sync ──┬── Buffer flush ──┬── Manifest update  │
│          │              │                  │                     │
│          │ Durable      │ S3 visible       │ Query visible       │
│          │ (local)      │ (other nodes)    │ (ES API)            │
│          │              │                  │                     │
│          │ ~1ms         │ ~100ms           │ ~1s (configurable)  │
│          │              │                  │                     │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 Read-After-Write Consistency

| Scenario | Consistency Guarantee |
|----------|----------------------|
| Same node, same shard | Immediate (buffer visible) |
| Same node, different shard | After flush (~1s) |
| Different node | After flush + manifest update (~1-2s) |
| After `?refresh=wait_for` | Guaranteed visible |
| After node restart | Visible (WAL recovery) |

### 6.3 Stale Read Scenarios

**Scenario 1: Query during flush**
```
T0: Write vector A to buffer
T1: Query arrives, searches buffer + Lance
T2: Flush starts, buffer cleared
T3: Vector A in S3 but manifest not updated
T4: New query misses vector A (stale read)
T5: Manifest updated, vector A visible
```

**Mitigation:** Queries snapshot the buffer at query start.

**Scenario 2: External Lance update**
```
T0: External process writes to Lance S3
T1: ES has cached old manifest
T2: Query returns stale results
T3: POST /_lance/refresh updates manifest
T4: Query returns fresh results
```

**Mitigation:** Configure `index.lance.refresh_interval` for automatic refresh polling.

### 6.4 Concurrent Writer Handling

Multiple ES nodes may write to the same Lance index:

```java
public class ConcurrentWriteCoordinator {
    private static final int MAX_RETRIES = 5;
    private static final long BASE_DELAY_MS = 100;
    private static final long MAX_DELAY_MS = 5000;
    private final Random jitter = new Random();

    /**
     * Lance uses optimistic concurrency control via manifest versioning.
     * Each write attempts to update manifest; conflicts retry with exponential backoff.
     */
    public void write(LanceDataset dataset, LanceFragment fragment) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                long currentVersion = dataset.getManifestVersion();
                dataset.commitFragment(fragment, expectedVersion: currentVersion);
                return;  // Success
            } catch (ManifestConflictException e) {
                if (attempt == MAX_RETRIES - 1) {
                    throw new WriteConflictException(
                        "Failed after " + MAX_RETRIES + " retries", e);
                }

                // Exponential backoff with jitter
                long delay = calculateBackoff(attempt);
                logger.debug("Manifest conflict, retrying in {}ms (attempt {})",
                    delay, attempt + 1);

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new WriteConflictException("Interrupted during retry", ie);
                }
            }
        }
    }

    /**
     * Exponential backoff: 100ms * 2^attempt + random jitter (0-100ms)
     * Capped at MAX_DELAY_MS to prevent excessive waits.
     */
    private long calculateBackoff(int attempt) {
        long exponentialDelay = BASE_DELAY_MS * (1L << attempt);  // 100, 200, 400, 800, 1600
        long jitterMs = jitter.nextInt(100);                       // 0-100ms random
        return Math.min(exponentialDelay + jitterMs, MAX_DELAY_MS);
    }
}
```

### 6.5 Consistency Settings

```yaml
# Strong consistency (highest latency)
index.lance.consistency: strong
index.lance.wal.sync_on_write: true
index.lance.flush_on_write: true           # Flush every write to S3

# Eventual consistency (default, balanced)
index.lance.consistency: eventual
index.lance.wal.sync_interval: 1s
index.lance.flush_interval: 30s

# Relaxed consistency (highest throughput)
index.lance.consistency: relaxed
index.lance.wal.enabled: false
index.lance.flush_interval: 60s
```

### 6.6 Disaster Recovery Consistency

| Failure | Data at Risk | Recovery |
|---------|--------------|----------|
| Node crash | Vectors in buffer (if WAL disabled) | WAL replay |
| S3 temporary failure | None (retry) | Automatic retry |
| S3 permanent failure | All data | Restore from backup |
| Manifest corruption | Recent writes | Roll back to previous version |

---

## 7. Code Locations

### 7.1 New Files (Plugin)

```
plugins/lance-vector/
├── build.gradle
├── src/main/java/org/elasticsearch/plugin/lance/
│   ├── LanceVectorPlugin.java              # Plugin entry point
│   │
│   ├── mapper/
│   │   ├── LanceVectorFieldMapper.java     # Field type definition
│   │   ├── LanceVectorFieldType.java       # Query integration
│   │   └── LanceVectorMappingParser.java   # Mapping parser
│   │
│   ├── codec/
│   │   ├── LanceVectorsFormat.java         # KnnVectorsFormat impl
│   │   ├── LanceVectorsWriter.java         # Write path
│   │   ├── LanceVectorsReader.java         # Read path
│   │   └── LancePerFieldKnnVectorsFormat.java
│   │
│   ├── storage/
│   │   ├── S3StorageAdapter.java           # S3 I/O operations
│   │   ├── LanceManifestManager.java       # Manifest CRUD
│   │   ├── LanceFragmentMetadata.java      # Fragment tracking
│   │   └── FragmentCache.java              # LRU cache
│   │
│   ├── query/
│   │   ├── LanceKnnVectorQuery.java        # Lucene Query impl
│   │   ├── SegmentOrdinalMapper.java       # Doc ID resolution
│   │   ├── LancePredicateConverter.java    # ES filter → Lance SQL
│   │   └── IVFPQSearchExecutor.java        # IVF-PQ search
│   │
│   ├── sync/
│   │   ├── SyncStateMachine.java           # Sync coordination
│   │   ├── LanceLuceneSyncJob.java         # Sync executor
│   │   ├── SyncCheckpointManager.java      # Checkpointing
│   │   └── CrashRecoveryHandler.java       # Recovery logic
│   │
│   ├── merge/
│   │   ├── LanceMergePolicy.java           # Compaction decisions
│   │   └── LanceMergeScheduler.java        # Background compaction
│   │
│   ├── action/
│   │   ├── SyncAction.java                 # _lance/sync API
│   │   ├── SyncStatusAction.java           # _lance/sync/status API
│   │   ├── RefreshAction.java              # _lance/refresh API
│   │   └── TransportSyncAction.java        # Transport handlers
│   │
│   └── metrics/
│       ├── LanceMetrics.java               # Metric definitions
│       └── CacheStatsCollector.java        # Cache metrics
│
├── src/test/java/org/elasticsearch/plugin/lance/
│   ├── unit/                               # Unit tests
│   ├── integration/                        # IT tests
│   └── benchmark/                          # Performance tests
│
└── src/main/resources/
    └── plugin-descriptor.properties
```

### 7.2 Modified Files (ES Core)

```
server/src/main/java/org/elasticsearch/index/codec/
└── PerFieldFormatSupplier.java
    # Add: getKnnVectorsFormatForField() check for lance_vector
    # Lines: ~159-167 (after DenseVectorFieldMapper check)

server/src/main/java/org/elasticsearch/index/engine/
└── InternalEngine.java
    # Add: Hook for Lance flush coordination
    # Lines: ~flush() method
```

### 7.3 Dependencies

```gradle
// plugins/lance-vector/build.gradle
dependencies {
    implementation 'org.lancedb:lance-core:0.25.0'
    implementation 'org.apache.arrow:arrow-vector:17.0.0'
    implementation 'org.apache.arrow:arrow-memory-netty:17.0.0'
    implementation 'software.amazon.awssdk:s3:2.28.0'
    implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'

    testImplementation project(':test:framework')
}
```

---

## 8. Observability

### 8.1 Metrics

| Metric Name | Type | Description | Labels |
|-------------|------|-------------|--------|
| `es.lance.query.latency` | Histogram | KNN query latency | index, shard |
| `es.lance.query.count` | Counter | Query count | index, status |
| `es.lance.cache.hit_rate` | Gauge | Cache hit ratio | index |
| `es.lance.cache.size_bytes` | Gauge | Cache size | index |
| `es.lance.cache.evictions` | Counter | Eviction count | cause |
| `es.lance.fragments.count` | Gauge | Fragment count | index, shard |
| `es.lance.fragments.size_bytes` | Gauge | Fragment size | index, shard |
| `es.lance.s3.requests` | Counter | S3 request count | operation |
| `es.lance.s3.latency` | Histogram | S3 operation latency | operation |
| `es.lance.s3.bytes` | Counter | S3 data transfer | direction |
| `es.lance.sync.state` | Gauge | Sync state (enum) | index |
| `es.lance.sync.progress` | Gauge | Sync progress % | index, shard |
| `es.lance.index.vectors` | Gauge | Total vectors | index |
| `es.lance.index.write_buffer` | Gauge | Buffer size | index, shard |

### 8.2 Logs

```java
// Log patterns with structured fields
public class LanceLogger {
    private static final Logger logger = LogManager.getLogger(LanceLogger.class);

    public void logQuery(String index, int shard, long latencyMs, int results) {
        logger.info("lance.query index={} shard={} latency_ms={} results={} cache_hit={}",
            index, shard, latencyMs, results, cacheHitRate);
    }

    public void logS3Operation(String op, String uri, long bytes, long latencyMs) {
        logger.debug("lance.s3 op={} uri={} bytes={} latency_ms={}",
            op, uri, bytes, latencyMs);
    }

    public void logSyncProgress(String index, int shard, long synced, long total) {
        logger.info("lance.sync index={} shard={} synced={} total={} percent={}",
            index, shard, synced, total, (synced * 100.0 / total));
    }

    public void logError(String operation, Exception e) {
        logger.error("lance.error op={} error={} trace={}",
            operation, e.getMessage(), ExceptionUtils.getStackTrace(e));
    }
}
```

**Log Levels:**
- `ERROR`: Failures affecting functionality (S3 errors, sync failures)
- `WARN`: Degraded performance (cache misses, slow queries)
- `INFO`: Significant events (sync start/complete, index creation)
- `DEBUG`: Detailed operations (S3 requests, cache operations)
- `TRACE`: Very detailed (per-fragment operations)

### 8.3 Alerts

| Alert | Condition | Severity | Action |
|-------|-----------|----------|--------|
| `LanceQueryLatencyHigh` | p99 > 500ms for 5min | Warning | Check cache, S3 latency |
| `LanceQueryLatencyCritical` | p99 > 2s for 5min | Critical | Page on-call |
| `LanceCacheHitRateLow` | hit_rate < 0.7 for 15min | Warning | Increase cache size |
| `LanceS3ErrorRate` | error_rate > 1% for 5min | Warning | Check S3 health |
| `LanceSyncStuck` | sync_state=SYNCING > 2hr | Warning | Check sync job |
| `LanceSyncFailed` | sync_state=FAILED | Critical | Investigate, retry |
| `LanceFragmentCountHigh` | fragments > 1000/shard | Warning | Trigger compaction |
| `LanceDiskUsageHigh` | cache_size > 90% limit | Warning | Eviction pressure |

### 8.4 Dashboards

**Overview Dashboard:**
- Total vectors indexed
- Query latency percentiles
- Cache hit rate
- S3 request rate and latency
- Sync status per index

**Index Detail Dashboard:**
- Per-shard fragment count and size
- Per-shard sync progress
- Write buffer sizes
- Compaction status

### 8.5 Distributed Tracing

OpenTelemetry tracing for debugging slow queries across shards and S3:

```java
public class LanceTracing {
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("es-lance");

    public SearchResults executeKnnQuery(KnnQuery query) {
        Span span = tracer.spanBuilder("lance.knn.query")
            .setAttribute("index", query.index())
            .setAttribute("k", query.k())
            .setAttribute("num_candidates", query.numCandidates())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Trace cache lookup
            Span cacheSpan = tracer.spanBuilder("lance.cache.lookup").startSpan();
            CacheResult cacheResult = fragmentCache.get(query.fragmentKey());
            cacheSpan.setAttribute("cache.hit", cacheResult.isHit());
            cacheSpan.end();

            // Trace S3 fetch if cache miss
            if (!cacheResult.isHit()) {
                Span s3Span = tracer.spanBuilder("lance.s3.download").startSpan();
                s3Span.setAttribute("s3.bucket", query.bucket());
                s3Span.setAttribute("s3.key", query.fragmentKey());
                // ... download logic
                s3Span.end();
            }

            // Trace Lance search
            Span searchSpan = tracer.spanBuilder("lance.ivfpq.search").startSpan();
            searchSpan.setAttribute("partitions.searched", nprobe);
            LanceResults results = lanceSearch(query);
            searchSpan.end();

            span.setAttribute("results.count", results.size());
            return results;
        } finally {
            span.end();
        }
    }
}
```

**Trace Propagation:**
- Traces propagate from ES REST layer through Transport to Lance operations
- S3 requests include trace context for end-to-end visibility
- Cross-shard operations linked via parent trace ID

**Configuration:**
```yaml
# elasticsearch.yml
tracing.apm.enabled: true
tracing.apm.agent.server_url: http://apm-server:8200
```

---

## 9. Debugging

### 9.1 Common Issues & Resolution

#### Issue: High Query Latency

**Symptoms:** `es.lance.query.latency` p99 > 500ms

**Diagnosis:**
```bash
# Check cache hit rate
curl -s localhost:9200/_nodes/stats/indices | jq '.nodes[].indices.lance.cache'

# Check S3 latency
curl -s localhost:9200/_nodes/stats/indices | jq '.nodes[].indices.lance.s3'

# Check IVF-PQ nprobe setting
curl -s localhost:9200/my-index/_settings | jq '.*.settings.index.lance'
```

**Resolution:**
1. If cache_hit_rate < 0.8: Increase `storage.cache_size`
2. If S3 latency high: Check network, S3 region
3. If many partitions searched: Reduce `num_candidates`

#### Issue: Sync Stuck

**Symptoms:** Sync progress not advancing

**Diagnosis:**
```bash
# Check sync status
curl -s localhost:9200/my-index/_lance/sync/status

# Check task status
curl -s localhost:9200/_tasks?detailed=true | grep lance

# Check cluster state for lock
curl -s localhost:9200/_cluster/state/metadata | jq '.metadata.lance_sync_locks'
```

**Resolution:**
1. If lock held: Wait or force-release (`POST /_lance/sync/unlock`)
2. If S3 errors: Check credentials, bucket permissions
3. If OOM: Reduce `batch_size`

#### Issue: Missing Results

**Symptoms:** KNN returns fewer results than expected

**Diagnosis:**
```bash
# Check deletion bitmap
curl -s "localhost:9200/my-index/_lance/stats" | jq '.deletions'

# Check sync status
curl -s "localhost:9200/my-index/_lance/sync/status"

# Verify Lance manifest
curl -s "localhost:9200/my-index/_lance/manifest" | jq '.fragments'
```

**Resolution:**
1. If deletions high: Trigger compaction
2. If sync incomplete: Wait for sync
3. If manifest stale: `POST /_lance/refresh`

### 9.2 Debug APIs

```http
# Get Lance index stats
GET /my-index/_lance/stats

# Get manifest details
GET /my-index/_lance/manifest

# Get cache stats
GET /my-index/_lance/cache/stats

# Force cache invalidation
POST /my-index/_lance/cache/invalidate

# Get segment-ordinal mapping
GET /my-index/_lance/mapping

# Get S3 connection test
POST /my-index/_lance/s3/test
```

### 9.3 JVM Debugging

```bash
# Enable Lance debug logging
curl -X PUT localhost:9200/_cluster/settings -H 'Content-Type: application/json' -d '{
  "transient": {
    "logger.org.elasticsearch.plugin.lance": "DEBUG",
    "logger.org.lancedb": "DEBUG"
  }
}'

# JFR profiling for native code
java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
     -XX:StartFlightRecording=duration=60s,filename=lance.jfr
```

---

## 10. Testing Strategy

### 10.1 Unit Tests

| Test Class | Coverage |
|------------|----------|
| `LanceVectorFieldMapperTests` | Mapping parsing, validation |
| `SegmentOrdinalMapperTests` | Doc ID resolution, merge handling |
| `LancePredicateConverterTests` | Filter translation |
| `FragmentCacheTests` | Cache operations, eviction |
| `SyncStateMachineTests` | State transitions |
| `ManifestManagerTests` | Manifest CRUD |

**Target:** 80%+ line coverage for plugin code

### 10.2 Integration Tests

| Test Class | Scope |
|------------|-------|
| `LanceVectorIndexIT` | End-to-end indexing and query |
| `LanceExternalMountIT` | External index mounting |
| `LanceSyncIT` | Full sync workflow |
| `LanceCacheIT` | Cache behavior with real S3 |
| `LanceMergeIT` | Compaction triggers and results |
| `LanceCrashRecoveryIT` | Recovery after simulated crash |
| `LanceFilterPushdownIT` | Filter translation correctness |

**Setup:** Use MinIO for S3 simulation, Testcontainers for isolation

### 10.3 Performance Tests

#### Latency Benchmarks

| Benchmark | Target | Method |
|-----------|--------|--------|
| Index throughput | 50K vectors/sec | JMH, 1M vectors |
| Query latency (1M) | p99 < 50ms | JMH, 1000 queries |
| Query latency (100M) | p99 < 100ms | Rally, 10K queries |
| Query latency (1B) | p99 < 200ms | Rally, 10K queries |
| Cache hit rate | > 90% | Load test, zipfian distribution |
| S3 bandwidth | > 100MB/s | Isolated network test |
| Sync throughput | 1M rows/min | Full sync benchmark |

#### Recall/Accuracy Benchmarks

**Critical:** IVF-PQ trades recall for speed. Users must understand the accuracy implications.

| Dataset | nprobe | Recall@10 Target | Recall@100 Target | Latency Target |
|---------|--------|------------------|-------------------|----------------|
| 1M vectors | 10 | > 85% | > 95% | < 20ms |
| 1M vectors | 50 | > 95% | > 99% | < 50ms |
| 100M vectors | 10 | > 80% | > 92% | < 50ms |
| 100M vectors | 50 | > 92% | > 98% | < 100ms |
| 1B vectors | 10 | > 75% | > 90% | < 100ms |
| 1B vectors | 100 | > 90% | > 97% | < 200ms |

**Recall Measurement Methodology:**

```python
def measure_recall(ground_truth_k, lance_results_k):
    """
    Recall@K = |ground_truth ∩ lance_results| / K

    Ground truth computed via brute-force exact search.
    """
    intersection = len(set(ground_truth_k) & set(lance_results_k))
    return intersection / len(ground_truth_k)
```

**Benchmark Test Class:**

```java
public class LanceRecallBenchmarkIT extends ESIntegTestCase {
    @Test
    public void testRecallAt10_1M_nprobe10() {
        // Index 1M vectors
        indexVectors(1_000_000, 768);

        // Generate 1000 random query vectors
        List<float[]> queries = generateRandomQueries(1000, 768);

        // Compute ground truth via brute-force
        List<List<String>> groundTruth = bruteForceKnn(queries, 10);

        // Run Lance KNN with nprobe=10
        List<List<String>> lanceResults = lanceKnn(queries, 10, nprobe: 10);

        // Calculate recall
        double avgRecall = calculateAverageRecall(groundTruth, lanceResults);

        assertThat("Recall@10 should be > 85%", avgRecall, greaterThan(0.85));
    }
}
```

**Recall vs. Latency Tuning Guide:**

| Use Case | Recommended nprobe | Expected Recall@10 | Notes |
|----------|-------------------|-------------------|-------|
| Real-time search | 10-20 | 80-90% | Prioritize latency |
| Product search | 30-50 | 90-95% | Balanced |
| Similarity dedup | 100+ | 95%+ | Prioritize accuracy |
| Recommendation | 20-30 | 85-92% | Good enough for recs |

**Comparison with ES Native dense_vector:**

| Metric | ES dense_vector (HNSW) | Lance IVF-PQ |
|--------|------------------------|--------------|
| Recall@10 (default) | ~95% | ~85% (nprobe=10) |
| Memory/vector | 4 bytes * dims + graph | 8-16 bytes |
| Max scale | ~100M/shard | 10B+ |
| Query latency (100M) | ~50ms | ~100ms |

**Benchmark Infrastructure:**
```yaml
# Rally track for Lance vectors
lance-vector-track:
  indices:
    - name: lance-benchmark
      body: lance-mapping.json
  corpora:
    - name: vectors-1m
      documents: 1000000
      source-file: vectors-1m.json
  operations:
    - name: knn-query
      operation-type: search
      body:
        knn:
          field: embedding
          query_vector: [...]
          k: 10
```

### 10.4 Chaos Tests

| Scenario | Expected Behavior |
|----------|-------------------|
| S3 unavailable during query | Return cached results or error |
| S3 unavailable during write | Buffer locally, retry |
| Node crash during sync | Resume from checkpoint |
| OOM during sync | Pause, reduce batch, continue |
| Concurrent sync requests | Second request rejected |

---

## 11. Security

### 11.1 S3 Authentication

**Supported Methods:**
1. **IAM Role** (Recommended for EC2/EKS)
2. **Access Keys** (stored in ES keystore)
3. **STS Assume Role** (cross-account)

```yaml
# elasticsearch.yml
lance.s3.auth.type: iam_role  # or access_key, assume_role

# For access_key (secrets in keystore)
bin/elasticsearch-keystore add lance.s3.access_key
bin/elasticsearch-keystore add lance.s3.secret_key
```

### 11.2 S3 Bucket Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ESLanceReadWrite",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789:role/es-lance-role"
      },
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::my-vectors",
        "arn:aws:s3:::my-vectors/*"
      ]
    }
  ]
}
```

### 11.3 Data Encryption

| Layer | Encryption | Configuration |
|-------|------------|---------------|
| S3 at rest | SSE-S3 / SSE-KMS | S3 bucket policy |
| S3 in transit | TLS 1.2+ | Default with AWS SDK |
| Local cache | OS-level | ESSD encryption |

### 11.4 Access Control

```http
# Index-level Lance permissions (future)
PUT /_security/role/lance_read
{
  "indices": [
    {
      "names": ["product-vectors"],
      "privileges": ["read"],
      "lance": {
        "storage_read": true,
        "sync_trigger": false
      }
    }
  ]
}
```

### 11.5 Security Considerations

| Risk | Mitigation |
|------|------------|
| Credential exposure in logs | Redact keys in log output |
| S3 bucket misconfiguration | Validate permissions on mount |
| Cache poisoning | Integrity checks on cached fragments |
| DoS via sync flood | Rate limit sync API, distributed lock |

---

## 12. Risks & Concerns

### 12.1 Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| JNI instability (native crashes) | Medium | High | Extensive testing, graceful degradation |
| S3 latency spikes | Medium | Medium | Aggressive caching, circuit breaker |
| Lance library bugs | Low | High | Pin version, monitor releases |
| Memory leaks in native code | Medium | High | Memory limits, monitoring, restarts |
| Segment-ordinal mapping corruption | Low | Critical | Checksums, rebuild capability |

### 12.2 Operational Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| S3 cost explosion | Medium | Medium | Cost monitoring, lifecycle policies |
| Sync taking too long | High | Low | Async, progress tracking, cancelable |
| Cache disk full | Medium | Medium | Eviction policies, alerts |
| Version incompatibility | Low | High | Version matrix testing |

### 12.3 Business Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Low adoption | Medium | Low | Clear documentation, migration guide |
| Support burden | Medium | Medium | Good observability, self-service debugging |
| Competitive pressure | Low | Medium | Differentiate on ES integration |

### 12.4 Open Questions

1. **Lance version stability**: Which lance-core version to pin? How to handle upgrades?
2. **Multi-tenancy**: How to isolate different users' S3 buckets?
3. **Disaster recovery**: How to recover if S3 bucket is deleted?
4. **Cost allocation**: How to attribute S3 costs per index?

---

## 13. Execution Plan

> **Key Change:** External Mount (connecting existing Lance indices from data lakes) is now **Phase 1** to deliver immediate value for users with existing vector data in S3.

### Phase 1: External Mount MVP (6 weeks)

**Rationale:** Users with existing Lance indices in S3 want to query them immediately via ES. Mount-first enables this without requiring them to re-index data.

| Week | Task | Owner | Validation |
|------|------|-------|------------|
| 1-2 | Plugin scaffold, build setup | TBD | `./gradlew :plugins:lance-vector:build` passes |
| 1-2 | LanceVectorFieldMapper (mount mode) | TBD | Mapping parses `storage.type: external` |
| 2-3 | S3StorageAdapter (read-only) | TBD | Read fragments and manifests from S3 |
| 3-4 | Schema discovery | TBD | Auto-detect Lance schema from S3 |
| 4-5 | LanceVectorsReader | TBD | KNN search on external index works |
| 5-6 | Mount API & lazy query | TBD | Mount external index in < 10s, query works |
| 6 | Mount IT | TBD | Full mount workflow tested |

**Phase 1 Exit Criteria:**
- [ ] Mount existing 100M vector Lance index from S3 in < 10s
- [ ] KNN query returns correct results (read-only mode)
- [ ] Query latency p99 < 200ms (100M vectors, with cache)
- [ ] All unit tests pass (80%+ coverage)
- [ ] Mount IT suite passes

**Phase 1 Deliverable:** Users can mount and query existing Lance indices from S3 via ES `knn` API (read-only).

### Phase 2: Cache & Performance (4 weeks)

| Week | Task | Owner | Validation |
|------|------|-------|------------|
| 7-8 | FragmentCache with backpressure | TBD | Cache hit rate > 90%, circuit breaker works |
| 8-9 | Native memory tracking | TBD | Memory limits enforced, no OOM |
| 9-10 | Filter pushdown | TBD | Supported filters pushed to Lance |
| 10 | Performance testing | TBD | Rally track passes |

**Phase 2 Exit Criteria:**
- [ ] Cache reduces S3 requests by 90%
- [ ] 100M vector query latency p99 < 100ms
- [ ] Native memory tracked and bounded
- [ ] Filter pushdown working for term/range queries
- [ ] Performance benchmark suite passes

### Phase 3: Write Path & Sync (6 weeks)

| Week | Task | Owner | Validation |
|------|------|-------|------------|
| 11-12 | LanceVectorsWriter | TBD | Buffer vectors, flush to S3 |
| 12-13 | WAL & durability | TBD | Survive crash with no data loss |
| 13-14 | LanceDocIdMapper | TBD | ES doc ID ↔ Lance row mapping |
| 14-15 | SyncStateMachine | TBD | State transitions correct |
| 15-16 | Deletion handling (bidirectional) | TBD | ES↔Lance deletes work |
| 16 | Write path IT | TBD | Index 1M vectors end-to-end |

**Phase 3 Exit Criteria:**
- [ ] Index 1M vectors to S3 via ES API
- [ ] WAL ensures durability across crashes
- [ ] Sync from external Lance updates works
- [ ] Deletions propagate ES↔Lance correctly
- [ ] All write path ITs pass

**Phase 3 Deliverable:** Full read-write support for Lance indices.

### Phase 4: Production Hardening (4 weeks)

| Week | Task | Owner | Validation |
|------|------|-------|------------|
| 17-18 | Lance compaction coordination | TBD | No corruption during compaction |
| 18-19 | Checkpoint & crash recovery | TBD | Resume sync from checkpoint |
| 19-20 | Chaos testing | TBD | All chaos scenarios pass |
| 20 | Security hardening | TBD | Credential handling, access control |

**Phase 4 Exit Criteria:**
- [ ] Lance compaction scenarios handled correctly
- [ ] Crash recovery works at any point in sync
- [ ] All chaos tests pass
- [ ] Security review passed

### Phase 5: Scale & Polish (4 weeks)

| Week | Task | Owner | Validation |
|------|------|-------|------------|
| 21-22 | 1B vector testing | TBD | Query p99 < 200ms at 1B scale |
| 22-23 | Observability | TBD | All metrics, logs, alerts, tracing working |
| 23-24 | Documentation | TBD | User guide, migration guide complete |
| 24 | Final testing | TBD | All tests pass, ready for release |

**Phase 5 Exit Criteria:**
- [ ] 1B vector benchmark passes
- [ ] OpenTelemetry tracing integrated
- [ ] All observability working
- [ ] Documentation complete
- [ ] IVF-PQ training workflow documented

### Total Timeline: ~24 weeks

### Milestone Summary

| Phase | Duration | Key Deliverable |
|-------|----------|-----------------|
| 1: External Mount | 6 weeks | Mount & query existing Lance indices (read-only) |
| 2: Cache & Performance | 4 weeks | Production-ready query performance |
| 3: Write Path & Sync | 6 weeks | Full read-write support |
| 4: Hardening | 4 weeks | Production stability |
| 5: Scale & Polish | 4 weeks | 1B scale, full observability, docs |

---

## 14. Rollout Strategy

### 14.1 Release Phases

| Phase | Scope | Duration | Criteria to Proceed |
|-------|-------|----------|---------------------|
| Alpha | Internal testing | 4 weeks | All ITs pass, no P0 bugs |
| Beta | Select customers | 4 weeks | < 5 P1 bugs, positive feedback |
| GA | General availability | Ongoing | < 2 P1 bugs, docs complete |

### 14.2 Feature Flags

```yaml
# elasticsearch.yml
xpack.lance.enabled: true          # Master switch
xpack.lance.external_mount: true   # External mount feature
xpack.lance.sync.enabled: true     # Sync feature
xpack.lance.cache.enabled: true    # Local caching
```

### 14.3 Rollback Plan

**If critical issues found:**

1. **Disable feature flag**: `xpack.lance.enabled: false`
2. **Remove plugin**: `bin/elasticsearch-plugin remove lance-vector`
3. **Restore from backup**: Standard ES restore procedure
4. **Data safety**: S3 data unaffected, only ES metadata removed

### 14.4 Monitoring During Rollout

| Metric | Alpha Target | Beta Target | GA Target |
|--------|--------------|-------------|-----------|
| Error rate | < 1% | < 0.1% | < 0.01% |
| Query p99 | < 500ms | < 200ms | < 100ms |
| Crash rate | < 1/day | < 1/week | < 1/month |

### 14.5 Communication Plan

| Milestone | Communication | Audience |
|-----------|---------------|----------|
| Alpha start | Internal announcement | Engineering team |
| Beta start | Beta program invitation | Select customers |
| GA | Blog post, docs, release notes | Public |

---

## 15. Appendix

### 15.1 Glossary

| Term | Definition |
|------|------------|
| **Lance** | Columnar data format optimized for ML workloads |
| **Fragment** | Immutable data file in Lance format (~1M rows) |
| **Manifest** | Metadata file tracking fragments and versions |
| **IVF-PQ** | Inverted File Product Quantization (vector index type) |
| **Mount** | Connecting external Lance index without copying data |
| **Sync** | Copying Lance metadata to Lucene for full ES features |

### 15.2 References

- [Lance Format Specification](https://lancedb.github.io/lance/)
- [ES KnnVectorsFormat](https://lucene.apache.org/core/9_0_0/core/org/apache/lucene/codecs/KnnVectorsFormat.html)
- [IVF-PQ Paper](https://arxiv.org/abs/1702.08734)
- [ES Plugin Development](https://www.elastic.co/guide/en/elasticsearch/plugins/current/index.html)

### 15.3 Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 0.1 | 2026-01-05 | Team | Initial draft |
| 0.2 | 2026-01-06 | Team | Added all sections per template |
| 0.3 | 2026-01-06 | Team | Design review consolidation: added consistency model, deletion flows, durability/WAL, filter pushdown, native memory management, S3 backpressure/circuit breaker, merge coordination; reorganized execution plan to prioritize external mount |
| 0.4 | 2026-01-06 | Team | Second review: added shard-to-storage mapping (5.10), _source field handling (5.11), update flow (5.4.4), rate limiting (5.6.3), schema validation, recall/accuracy benchmarks; fixed WAL durability descriptions, exponential backoff for writes; added ILM/aggregations to non-goals |
| 0.5 | 2026-01-07 | Team | Third review: renamed "takeover" to "mount" throughout for clearer user communication; added user-friendly error message guidelines |
| 0.6 | 2026-01-07 | Team | **Critical fix from dry run analysis**: Replaced segment_uuid-based doc ID resolution with LanceDocIdMapper using _es_doc_id column. Fixes: (1) write-time segment_uuid unavailability, (2) external mount support, (3) crash recovery consistency. Removed SegmentOrdinalMapper, simplified merge coordination. See docs/critical-findings.md for full analysis. |

---

**Document Status:** Ready for Implementation (Dry Run Verified)
**Next Steps:** Begin Phase 1 implementation (External Mount MVP)
