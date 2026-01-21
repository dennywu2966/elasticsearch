# Critical Findings - Design Dry Run Analysis

**Date:** 2026-01-07
**Reviewer:** Tech Lead
**Document Reviewed:** docs/design.md v0.5

---

## Executive Summary

A systematic dry run of each core flow in the Lance Vector Integration design revealed **3 critical issues** and **2 high-severity issues** that must be addressed before implementation. The primary problem is the design's reliance on Lucene's `segment_uuid` for doc ID resolution, which:

1. Is not available at write time (Lucene assigns it during flush)
2. Does not exist in externally mounted Lance indices
3. Creates inconsistent state during crash recovery

This document captures the findings and recommended fixes.

---

## Critical Issues

### CRITICAL-1: segment_uuid Not Available at Write Time

**Flows Affected:** Write, Update, Recovery

**Problem:**
The design stores `(_segment_uuid, _segment_ordinal)` in Lance to resolve doc IDs during queries. However, Lucene assigns segment UUIDs only during segment flush, not at document indexing time.

```
Design assumes:
  IndexRequest → LanceVectorsWriter.addVector(docId, vector, segmentUuid, ordinal)
                                                          ↑
                                               Not known yet!

Reality:
  IndexRequest → Lucene buffers doc → Flush creates segment → UUID assigned
```

**Impact:** Write flow is not implementable as designed.

**Evidence:** Section 5.5.1 shows `addVector(String docId, float[] vector, String segmentUuid, int ordinal)` but at this point Lucene hasn't created the segment.

---

### CRITICAL-2: External Mount Has No segment_uuid in Lance Data

**Flows Affected:** Query, Delete, Update (for mounted indices)

**Problem:**
External Lance indices from data lakes don't have `_segment_uuid` and `_segment_ordinal` columns. These are ES-specific metadata that only exist if ES wrote the data.

```
External Lance Schema:
├── id: Utf8          ✓ (user's ID)
├── vector: Float32[] ✓ (vectors)
├── category: Utf8    ✓ (metadata)
└── _segment_uuid     ✗ DOES NOT EXIST
└── _segment_ordinal  ✗ DOES NOT EXIST

Design's SegmentOrdinalMapper.resolveDocId(segmentUuid, ordinal):
  → Cannot work - no segmentUuid to look up!
```

**Impact:** The entire query flow for mounted external indices fails. This breaks the primary use case (G1: Mount existing Lance indices from S3).

**Evidence:** Section 5.1.1 defines Lance schema with `_segment_uuid` but Section 5.2.2 External Mount doesn't explain how this column gets populated for external data.

---

### CRITICAL-3: Recovery Creates Mapping Inconsistency

**Flows Affected:** Crash Recovery

**Problem:**
After crash recovery, vectors are written to a new Lance fragment, but Lucene's stored `(segment_uuid, ordinal)` mapping still points to the old (never-flushed) location.

```
Before crash:
  Lucene: doc123 → segment_A, ordinal_42 (in buffer, not flushed)
  Lance: vector in WAL (not flushed)

After recovery:
  Lance: vector flushed to NEW fragment_X
  Lucene: doc123 still thinks → segment_A, ordinal_42 (STALE!)

Query for doc123:
  1. Find doc123 in Lucene → (segment_A, ordinal_42)
  2. SegmentOrdinalMapper.resolveDocId(segment_A, 42) → NOT FOUND
  3. Query fails!
```

**Impact:** Recovered data is inaccessible after node restart.

**Evidence:** Section 5.5.2 recovery flow doesn't address updating Lucene's stored mapping after recovery.

---

## High-Severity Issues

### HIGH-1: Read-Only External Index Merge Chain Grows Forever

**Flows Affected:** Merge Coordination

**Problem:**
For read-only mounted indices, Lucene continues to merge segments, creating merge chain entries in `SegmentOrdinalMapper`. Since Lance data cannot be rewritten (read-only), these entries accumulate forever.

When `merge_chain_depth >= MAX_MERGE_CHAIN_DEPTH (5)`, the design calls `scheduleLanceResync()`, but this is impossible for read-only indices.

**Impact:** After many Lucene merges, query performance degrades or mapping becomes invalid.

---

### HIGH-2: _source Field Handling for External Mount Incomplete

**Flows Affected:** Query

**Problem:**
When querying a mounted external index, ES needs to return `_source` fields. The design doesn't specify where non-vector fields come from for external indices.

Options unclear:
- Read from Lance's other columns?
- Store in Lucene during sync?
- Require sync before queries work?

**Impact:** Query results may be incomplete or require undocumented sync step.

---

## Root Cause Analysis

The fundamental issue is **coupling ES document identity to Lucene's internal segment structure**. This was likely chosen for O(1) lookup performance, but:

1. Lucene segment lifecycle doesn't align with write-time requirements
2. External data lakes have no knowledge of ES internals
3. The coupling creates fragile state that breaks under recovery/compaction

---

## Recommended Fix: Decouple from segment_uuid

### New Approach: doc_id ↔ lance_row_id Mapping

Instead of relying on Lucene segments, use a direct bidirectional mapping between ES doc IDs and Lance row IDs.

**Revised Lance Schema:**
```
For ES-created indices:
├── _lance_row_id: UInt64 (auto-incremented, immutable)
├── _es_doc_id: Utf8 (ES document ID)
├── _vector: FixedSizeList[Float32, dims]

For external mount:
├── {user_id_column}: Utf8 (specified in mapping as lance_id_column)
├── {user_vector_column}: FixedSizeList[Float32, dims]
└── ... other columns
```

**Mapping Strategy:**

| Scenario | Write Path | Query Path | Complexity |
|----------|-----------|------------|------------|
| ES-created index | Store `_es_doc_id` in Lance | Read `_es_doc_id` column | O(1) |
| External mount (ID match) | Use `lance_id_column` as `es_doc_id` | Return `lance_id` directly | O(1) |
| External mount (ID transform) | Build hash index during sync | Hash lookup | O(1) |

**Key Changes:**

1. **Remove `_segment_uuid` and `_segment_ordinal` from Lance schema**
2. **Remove `SegmentOrdinalMapper` entirely**
3. **Add `LanceDocIdMapper`** with simpler logic:
   - For writes: generate `lance_row_id`, store with `es_doc_id`
   - For queries: Lance returns `es_doc_id` directly (or lookup from mapping)
4. **For external mount:** Use `lance_id_column` mapping or build index during sync

### Complexity Comparison

| Operation | Old Design | New Design |
|-----------|-----------|------------|
| Query lookup | O(merge_depth) ≤ O(5) | **O(1)** |
| Write | Not implementable | **O(1)** |
| External mount query | Not implementable | **O(1)** |
| After Lucene merge | Mapping grows | **No change** |
| After Lance compaction | Rebuild needed | **No change** |
| Crash recovery | Inconsistent state | **Consistent** |

### Trade-offs

| Aspect | Old Design | New Design |
|--------|-----------|------------|
| Memory | Merge table in RAM | Mapping index (~16 bytes/row) |
| Disk | None | ~160GB for 10B vectors |
| Lucene coupling | Tight (fragile) | None (robust) |
| External mount | Broken | Works |

---

## Implementation Checklist

To fix the design document:

- [ ] Replace Section 5.1.1 Lance Schema - remove segment_uuid/ordinal
- [ ] Replace Section 5.1.4 SegmentOrdinalMapper with LanceDocIdMapper
- [ ] Remove Section 5.1.5 merge coordination (no longer needed for mapping)
- [ ] Update Section 5.4 Delete flow - use es_doc_id lookup
- [ ] Update Section 5.4.4 Update flow - use es_doc_id lookup
- [ ] Update Section 5.5 Write buffer - remove segment_uuid assignment
- [ ] Update Section 5.5.2 Recovery - simplified with direct mapping
- [ ] Add Section: External mount ID mapping strategy
- [ ] Update Section 6 Consistency model if affected
- [ ] Update revision history

---

## Verification Plan

After fixes, verify by dry-running:

1. **Mount flow** - Should work without segment_uuid
2. **Query flow** - Should use es_doc_id from Lance or mapping
3. **Write flow** - Should not need segment_uuid at write time
4. **Delete flow** - Should resolve lance_row_id from es_doc_id
5. **Update flow** - Should work with es_doc_id lookup
6. **Recovery flow** - Should maintain consistent mapping
7. **External mount query** - Should use lance_id_column

---

**Document Status:** Findings recorded, ready for design fixes
