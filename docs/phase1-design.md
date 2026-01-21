# Phase 1 Design: External Mount MVP (Read-Only)

**Project:** Lance Vector Integration for Elasticsearch  
**Status:** Draft (Phase 1 focused)  
**Authors:** ES Lance Integration Team  
**Created:** 2026-01-07  
**Last Updated:** 2026-01-07  
**Source Design:** `docs/design.md` (v0.6)

---

## Table of Contents

1. [Scope](#1-scope)
2. [Goals & Non-Goals](#2-goals--non-goals)
3. [User Experience](#3-user-experience)
4. [Architecture (Phase 1)](#4-architecture-phase-1)
5. [Configuration & Mapping](#5-configuration--mapping)
6. [Data Model & ID Modes](#6-data-model--id-modes)
7. [Mount Flow (Mock Dry Run)](#7-mount-flow-mock-dry-run)
8. [Query Flow (Mock Dry Run)](#8-query-flow-mock-dry-run)
9. [Filters & Fetch](#9-filters--fetch)
10. [Caching (Minimal in Phase 1)](#10-caching-minimal-in-phase-1)
11. [Failure Modes](#11-failure-modes)
12. [Observability](#12-observability)
13. [Testing Strategy (Phase 1)](#13-testing-strategy-phase-1)
14. [Rollout & Feature Flags](#14-rollout--feature-flags)
15. [Notes / Corrections to `docs/design.md`](#15-notes--corrections-to-docsdesignmd)

---

## 1. Scope

Phase 1 delivers **read-only external mount** of an existing Lance dataset stored in S3, enabling **kNN search** from Elasticsearch without copying vector data into Lucene/ES storage.

This phase is intentionally constrained to establish an end-to-end “mount → query” loop with correct request validation, shard routing, and operational visibility.

---

## 2. Goals & Non-Goals

### 2.1 Goals (Phase 1)

| ID | Goal | Acceptance |
|----|------|------------|
| P1-G1 | Mount existing Lance dataset | `PUT /{index}` with `storage.type=external` completes in < 10s (control-plane work only) |
| P1-G2 | Read-only kNN works | `POST /{index}/_search` with `knn` returns top-k hits (requires existing ES docs; see below) |
| P1-G3 | Schema validation | Dimensions/type/columns validated at mount time with actionable errors |
| P1-G4 | Shard routing correctness | Each ES shard maps deterministically to a Lance partition or virtual range |
| P1-G5 | Operational visibility | Basic metrics + logs for mount, query, and S3 I/O |

### 2.2 Non-Goals (Phase 1)

| ID | Non-Goal | Deferred To |
|----|----------|-------------|
| P1-NG1 | Write path (index/update/delete to Lance) | Phase 3 |
| P1-NG2 | Bidirectional delete sync | Phase 3 |
| P1-NG3 | Production-grade cache backpressure/circuit breaking | Phase 2 |
| P1-NG4 | Full “Lance-first” filtering/aggregations on external columns | Phase 2+ |
| P1-NG5 | EXTERNAL_MAPPED ID transforms | Phase 3 (or later) |

### 2.3 Phase 1 Precondition (Critical)

Phase 1 assumes the ES index contains **documents (metadata) in Lucene keyed by the same `_id` values** that exist in the external Lance dataset’s `lance_id_column`.

Reason: ES search responses (`hits`) are produced from Lucene documents. Phase 1 does **not** attempt to materialize 10B Lucene docs from a mounted dataset. Instead, Phase 1 **joins Lance kNN candidates to existing Lucene docs by `_id`**.

If the ES index does not contain matching `_id`s, kNN results will be empty (or partial). This is called out explicitly because it affects feasibility and user expectations.

---

## 3. User Experience

### 3.1 Primary Use Case (Phase 1)

“I already have an ES index with metadata docs (`_id`, filters, `_source`). My vectors are in S3 as a Lance dataset. I want kNN over those vectors but keep metadata in ES.”

**Workflow:**
1. User creates or already has an ES index with metadata docs (no vectors stored in Lucene).
2. User adds a `lance_vector` field mapping that points at S3 + column names.
3. User queries via the standard ES `knn` request body.

### 3.2 Example Index Mapping (External Mount)

```http
PUT /product-vectors
{
  "settings": {
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
          "uri": "s3://datalake/vectors/product/",
          "shard_path_template": "shard-{shard_id:03d}/",
          "lance_id_column": "product_id",
          "lance_vector_column": "embedding",
          "read_only": true,
          "lazy_sync": true
        }
      }
    }
  }
}
```

### 3.3 Example Query (kNN + optional filter)

```http
POST /product-vectors/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 200
  },
  "query": {
    "term": { "category": "electronics" }
  }
}
```

**Phase 1 behavior:**
- `knn` is executed in Lance (external).
- Filter is applied in Lucene post-join (Phase 1), with best-effort oversampling to try to return `k` docs.

---

## 4. Architecture (Phase 1)

### 4.1 High-Level Components (Phase 1)

| Component | Phase 1 Responsibility |
|-----------|-------------------------|
| `LanceVectorPlugin` | Registers mapper, settings, and REST actions for validate/refresh/status |
| `LanceVectorFieldMapper` + `LanceVectorFieldType` | Parses mapping, owns mount config, produces Lucene query object |
| `S3StorageAdapter` (read-only) | Reads manifests and required Lance files from S3 |
| `LanceDatasetManager` | Opens/keeps dataset handles per shard, keyed by index UUID + shard id |
| `LanceKnnExecutor` | Runs kNN in Lance for a shard and returns `(id, score)` candidates |
| `LanceJoinQuery` | Lucene query that joins candidate IDs to Lucene docs by `_id` and assigns scores |

### 4.2 Query Execution Strategy (Phase 1)

**Two-stage per-shard execution:**
1. **Candidate generation (Lance):** ask Lance for top `num_candidates` `(id, score)` for the shard’s dataset/partition.
2. **Join + post-filter (Lucene):** map candidate IDs to Lucene docs by `_id` and apply the user query/filter constraints; return top `k` with Lance scores.

This avoids storing vectors in Lucene while still returning standard ES hits from Lucene documents.

---

## 5. Configuration & Mapping

### 5.1 Mapping Fields (Phase 1)

Required:
- `dims`
- `storage.type=external`
- `storage.uri`
- `storage.lance_id_column`
- `storage.lance_vector_column`

Optional (Phase 1):
- `storage.shard_path_template` (default: `shard-{shard_id:03d}/`)
- `storage.partition_mode` (`directory` default; `virtual` allowed but may have performance caveats)
- `storage.read_only` (must be `true` in Phase 1; if `false`, reject)

### 5.2 Validation Rules (Phase 1)

Mount-time validation must check:
- S3 URI syntax and access (HEAD/list) where feasible.
- Lance dataset exists and manifest can be read.
- `lance_vector_column` exists and is vector type.
- Vector `dims` matches mapping `dims`.
- `lance_id_column` exists and is string/bytes convertible to ES `_id`.

Behavior on failure:
- Reject index creation with a clear error payload (see `docs/design.md` error format).

---

## 6. Data Model & ID Modes

Phase 1 supports **EXTERNAL_DIRECT** only.

### 6.1 Supported Mode in Phase 1: `EXTERNAL_DIRECT`

| Property | Meaning |
|----------|---------|
| Lance ID column | `storage.lance_id_column` (e.g., `product_id`) |
| ES `_id` | Must match Lance ID value exactly |
| Join method | Lucene term lookup on `_id` to find doc(s) |

### 6.2 Unsupported in Phase 1

| Mode | Why not in Phase 1 |
|------|---------------------|
| `ES_CREATED` | Requires write path to create Lance datasets and store `_es_doc_id` column |
| `EXTERNAL_MAPPED` | Requires building/maintaining an ID transform index and reverse lookup semantics |

---

## 7. Mount Flow (Mock Dry Run)

**Trigger:** `PUT /{index}` with `index.lance.mount=true` and `lance_vector` mapping.

**Per-index control-plane steps:**
1. Parse mapping; validate required fields present.
2. Validate configuration shape: `storage.type=external`, `read_only=true`.
3. Perform best-effort remote validation:
   - Read dataset manifest (or `_latest.manifest`) from S3.
   - Read schema metadata; validate `dims` and column types.
4. Persist `LanceIndexMetadata` into cluster state:
   - S3 URI + template
   - column names
   - detected schema summary
   - manifest version

**Per-shard data-plane steps (on shard start):**
1. Determine shard’s dataset location:
   - `directory` mode: `uri + shard_path_template(shardId)`
   - `virtual` mode: compute row-range mapping (Phase 1 allows but warns about performance)
2. Open a `LanceDatasetHandle` via `lance-core` configured for S3 access.
3. Record shard status as “mounted/ready” for query path.

**Success criteria:** control-plane mount completes quickly; shard start performs bounded I/O.

---

## 8. Query Flow (Mock Dry Run)

**Trigger:** `POST /{index}/_search` with `knn` on `lance_vector` field.

### 8.1 Per-Shard Execution (Phase 1)

1. **Validate request:**
   - dims match mapping
   - `k` and `num_candidates` within limits
2. **Run Lance search (candidate generation):**
   - Use dataset handle for shard
   - Execute IVF-PQ search over `lance_vector_column`
   - Return `num_candidates` tuples: `(lance_id, score)`
3. **Join to Lucene docs by `_id`:**
   - For each `lance_id`, perform a per-segment term lookup on `_id` field
   - Collect matching Lucene docIDs and attach the Lance score
4. **Apply query/filter constraints (post-join):**
   - Apply the user query (or filter bitset) to drop non-matching docs
   - If fewer than `k` remain, optionally increase oversampling up to a hard cap:
     - e.g., `num_candidates = min(max_num_candidates, num_candidates * 2)` and retry (bounded retries)
5. **Return top-k Lucene hits with Lance scores.**

### 8.2 Coordinator Merge

Standard ES scatter/gather merges shard top-k and returns the response.

### 8.3 Score Semantics

Phase 1 returns Lance similarity scores as the ES `_score`.

We must document score normalization:
- cosine / dot-product similarities in Lance must map consistently to ES expectations
- any monotonic transform must be stable across shards

---

## 9. Filters & Fetch

### 9.1 Filters (Phase 1)

Phase 1 supports **post-filtering in Lucene** using existing indexed fields.

Filter pushdown to Lance (translating ES filters into Lance SQL) is deferred to Phase 2.

Implication:
- Highly selective filters may require large `num_candidates` to return `k` results.
- Phase 1 will use bounded oversampling/retry to avoid runaway costs and will return fewer than `k` hits if necessary.

### 9.2 Fetch / `_source` (Phase 1)

Phase 1 expects `_source` to come from Lucene documents that already exist in the ES index (the “metadata index”).

Vectors are **not fetched** into `_source` in Phase 1. Returning the embedding is deferred until write-path and/or a dedicated fetch strategy is implemented.

---

## 10. Caching (Minimal in Phase 1)

Phase 1 uses a minimal, safe caching strategy:
- Rely on the Lance library’s internal caching (if available) and OS page cache.
- Optional: a small local file cache for the most frequently accessed Lance index artifacts (manifest + IVF-PQ index files), with a conservative max size and simple LRU eviction.

Production-grade backpressure, circuit breaking, async downloads, and cache warming are deferred to Phase 2.

---

## 11. Failure Modes

| Failure | Phase 1 Behavior |
|---------|-------------------|
| S3 permission denied on mount | Reject index creation with actionable error |
| S3 transient failures on query | Fail query with `s3_unavailable` (Phase 2 may enable cached-only behavior) |
| Missing Lance ID in Lucene | Candidate dropped; results may be fewer than `k` |
| Schema mismatch after mount | Require explicit `/_lance/refresh` to revalidate; fail queries if incompatible |

---

## 12. Observability

Phase 1 metrics/logs are intentionally minimal but sufficient to operate:

Metrics:
- `es.lance.query.latency` (per index/shard)
- `es.lance.s3.latency` (by operation: manifest read, file read)
- `es.lance.query.candidates` (requested vs joined vs returned)
- `es.lance.mount.time` (control-plane)

Logs (INFO):
- mount start/complete with config summary
- query execution summary (k, num_candidates, joined_count, returned_count)
- S3 errors with request IDs (redacted)

---

## 13. Testing Strategy (Phase 1)

Unit tests:
- mapping parsing + validation for external mount
- schema validation behavior (dims/type/columns)
- join correctness: candidate IDs map to Lucene docs and score assignment is stable

Integration tests:
- MinIO-backed external dataset mount
- end-to-end: index metadata docs in ES, mount Lance vectors, run kNN query and validate hits

Performance sanity:
- repeated queries should show reduced S3 I/O after warmup (even without Phase 2 cache)

---

## 14. Rollout & Feature Flags

Phase 1 is guarded by a feature flag:

```yaml
# elasticsearch.yml
xpack.lance.enabled: true
xpack.lance.external_mount: true
```

Safe rollout steps:
1. Enable only in dev clusters; validate mount + query.
2. Alpha on small datasets; verify error handling and metrics.
3. Expand dataset sizes and shard counts; validate stability.

---

## 15. Notes / Corrections to `docs/design.md`

1. **`knn` query compatibility:** ES core `KnnVectorQueryBuilder` currently hard-requires `DenseVectorFieldType`. To support `lance_vector`, Phase 1 needs an ES core change (e.g., introduce a common interface that both dense vector and lance vector field types implement, or relax the type check and delegate via polymorphism).
2. **Residual `SegmentOrdinalMapper` references:** `docs/design.md` still lists `SegmentOrdinalMapper` in code locations/tests even though v0.6 says it was removed.
3. **Phase 1 precondition should be explicit:** `docs/design.md` implies queries work immediately after mount. Phase 1 requires existing Lucene docs for `_id` join; otherwise ES cannot return standard hits. This should be called out in the main design to avoid user surprise.
4. **`_source` and vector fetch semantics:** For external mount, `docs/design.md` should explicitly state whether vectors are returned in `_source` (Phase 1: no) and what the supported fetch behavior is.

