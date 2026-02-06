# Future Development Plan
# ES Plugins: Lance Vector & Cloud IAM

**Status:** Draft v1.0
**Last Updated:** 2026-02-01
**Related:** [`highlevel-design.md`](./highlevel-design.md) - Detailed architecture

---

## Quick Reference

| Phase | Focus | Status | Priority |
|-------|-------|--------|----------|
| P0 | Production hardening | In Progress | Critical |
| P1 | Zero-copy JNI | Not Started | High |
| P1 | Pre-filtering | Not Started | High |
| P2 | NRT sync service | Not Started | Medium |
| P3 | Sharding consistency | Not Started | High |
| P4 | Generic SDK framework | Not Started | Low |

---

## Lance Vector Plugin

### Phase P0: Production Hardening (BLOCKER)

**Goal**: Ensure current implementation is production-ready before adding new features.

#### P0.1 Memory Leak Prevention
- [ ] Audit all JNI resource allocations
- [ ] Implement `Cleaner`/`PhantomReference` for native Lance datasets
- [ ] Add Valgrind/ASAN validation for JNI layer
- [ ] Add long-running soak tests (24h+)

#### P0.2 Thread Safety
- [ ] Audit shared state in `LanceVectorQuery`, `OssStorageAdapter`
- [ ] Add concurrent stress tests with ThreadSanitizer
- [ ] Document thread-safety guarantees for public APIs

#### P0.3 Observability
- [ ] Add metrics: query latency, JNI call count, memory usage
- [ ] Structured logging with request/trace IDs
- [ ] Health check endpoints for Lance connectivity
- [ ] Dashboard templates for monitoring

#### P0.4 Test Coverage
- [ ] Unit test coverage > 80%
- [ ] Integration tests with OSS backend
- [ ] Chaos tests (network partition, OSS throttling)
- [ ] Performance regression tests

**Deliverable**: Production-ready baseline validated by 7-day soak test

---

### Phase P1: Performance & Query Capability

#### P1.1 Zero-Copy JNI (High Priority)

**Problem**: Current JNI calls copy data between JVM and native heap, hurting performance.

**Approach**:
```
┌─────────────────┐     ┌──────────────────┐
│  Java Heap      │────▶│  Native Memory   │
│  Arrow vectors  │     │  (Lance owned)   │
└─────────────────┘     └──────────────────┘
       ▲                        ▲
       │   Zero-copy via       │
       └── Arrow IPC mmap ──────┘
```

**Tasks**:
1. [ ] Implement Arrow `BufferAllocator` integration with Lance SDK
2. [ ] Use `ArrowArray`/`ArrowSchema` FFI for zero-copy access
3. [ ] Benchmark before/after (target: 2-3x throughput improvement)
4. [ ] Add memory pressure tests

**Risks**: Arrow allocator integration complexity, native memory OOM

---

#### P1.2 Pre-filtering Support (High Priority)

**Problem**: kNN search returns raw Lance results; users need to filter by ES metadata.

**Approach**: Two-phase query execution
```
1. ES applies filters → candidate doc IDs
2. Lance kNN search → top-K vectors with PKs
3. PK-to-docID mapping → filter Lance results by ES candidates
4. Return filtered results
```

**Tasks**:
1. [ ] Add `_lance_pk` mapping field (primary key)
2. [ ] Implement PK-to-docID index in ES
3. [ ] Modify `LanceVectorQuery` to accept `Filter` clause
4. [ ] Add integration tests for filtered kNN

**API Example**:
```json
{
  "query": {
    "lance_vector": {
      "field": "embedding",
      "vector": [0.1, 0.2, ...],
      "k": 100,
      "pre_filter": {
        "term": { "category": "electronics" }
      }
    }
  }
}
```

---

### Phase P2: Near-Real-Time Sync

#### P2.1 Reactive Lance Update Detection

**Problem**: Lance datasets are updated directly in OSS (by external pipelines). ES needs to discover updates.

**Approach**: Poll-based manifest checking
```
ES Node → OSS Head Request → Manifest ETag changed?
                              ↓ Yes
                          Reload Lance dataset metadata
```

**Tasks**:
1. [ ] Add `refresh_interval` setting (default: 30s)
2. [ ] Background thread to poll manifest ETags
3. [ ] Graceful reload without query disruption
4. [ ] Metrics: refresh latency, reload count

**Alternative (Future)**: Event-driven via OSS Event Bridge

---

#### P2.2 Ingest Pipeline Integration

**Problem**: Non-vector fields (text, metadata) need to be indexed in ES.

**Approach**: Sidecar ingestion service
```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ Raw Data    │────▶│  Flink/NRT   │────▶│  ES (text)  │
│ (OSS/Lance) │     │  Service     │     │  Indexing   │
└─────────────┘     └──────────────┘     └─────────────┘
                           │
                           │ PK only
                           ▼
                    ┌─────────────┐
                    │ ES (Lance)  │
                    │ Vector ref  │
                    └─────────────┘
```

**Tasks**:
1. [ ] Design ingest protocol (Kafka/Pulsar/HTTP)
2. [ ] ES ingest processor to handle sidecar updates
3. [ ] Idempotency handling (PK-based upsert)
4. [ ] Backlog recovery mechanism

**Note**: This phase may be out-of-scope for ES plugin alone; requires external service.

---

### Phase P3: Scale & Sharding

#### P3.1 Shard Mapping Strategy

**Problem**: ES manages shards independently; Lance has its own sharding. Need consistency.

**Approach**: Coordinated sharding
```
ES Shard 0  →  Lance Partition 0
ES Shard 1  →  Lance Partition 1
...
ES Shard N  →  Lance Partition N
```

**Tasks**:
1. [ ] Define shard-to-partition mapping API
2. [ ] Validate mapping on index creation
3. [ ] Handle split/merge scenarios
4. [ ] Add validation tests

**Target**: 10B+ documents, 100+ shards

---

#### P3.2 Compute-Storage Separation

**Vision**: ES nodes are stateless compute; all data in OSS.

**Architecture**:
```
┌─────────────────┐     ┌──────────────────┐
│ ES Compute Node │────▶│  OSS (Lake)      │
│ (Stateless)     │     │  - Lance vectors │
│                 │     │  - Lucene segs   │
└─────────────────┘     │  - Metadata      │
                        └──────────────────┘
```

**Tasks**:
1. [ ] Lucene segments on OSS (P2: take-over mode)
2. [ ] Warm-up cache strategy
3. [ ] Autoscaling based on query load
4. [ ] Cost analysis vs. traditional ES

**Note**: This is a multi-quarter initiative requiring Lucene-level changes.

---

### Phase P4: Generic SDK Framework

**Goal**: Enable pluggable storage backends (Paimon, OLAP engines, etc.)

**API Design**:
```java
interface StorageBackend {
    DatasetReader openDataset(URI location);
    Scanner scan(Filter... filters);
    long count();
    // ...
}

class LanceBackend implements StorageBackend { ... }
class PaimonBackend implements StorageBackend { ... }
```

**Tasks**:
1. [ ] Define `StorageBackend` SPI
2. [ ] Plugin registration API
3. [ ] Reference implementations (Lance, Paimon)
4. [ ] Documentation for third-party integrations

---

## Cloud IAM Plugin

### Phase C0: Production Hardening

#### C0.1 Monitoring & Logging
- [ ] Structured audit logs (auth success/failure, token refresh)
- [ ] Metrics: auth latency, error rate, token cache hit rate
- [ ] Security event tracking (failed attempts, anomalies)

#### C0.2 Security Hardening
- [ ] Token encryption at rest (ES keystore integration)
- [ ] Rate limiting per user/IP
- [ ] Secure token validation (JWT signature verification)

#### C0.3 Test Coverage
- [ ] Unit tests for all auth flows
- [ ] Integration tests with mock Aliyun RAM
- [ ] Security audit review

---

### Phase C1: Token Return Mode

**Requirement**: Support returning access/refresh tokens directly (not just OAuth redirect).

**API Design**:
```json
POST /_security/authenticate
{
  "username": "...",
  "password": "..."
}

Response:
{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600
}
```

**Tasks**:
1. [ ] Add `return_tokens` setting to realm config
2. [ ] Modify `CloudIamRealm` to return token response
3. [ ] Add token refresh endpoint
4. [ ] Update Kibana integration

---

## Execution Timeline

| Quarter | Focus | Deliverables |
|---------|-------|--------------|
| Q1 2026 | P0 Hardening | Production-ready baseline, security audit |
| Q1-Q2 2026 | P1 Performance | Zero-copy JNI, pre-filtering |
| Q2 2026 | P2 NRT Sync | Manifest polling, ingest integration |
| Q3 2026 | P3 Sharding | Shard mapping, scalability tests |
| Q4 2026 | P4 Framework | Generic SDK API, Paimon PoC |
| Ongoing | C0/C1 IAM | Token return, monitoring |

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| JNI memory leaks | High | ASAN validation, soak tests |
| Lance SDK breaking changes | Medium | Version pinning, compatibility tests |
| OSS throttling | Medium | Retry with exponential backoff, circuit breaker |
| Lucene on OSS performance | High | Benchmark before committing, cache strategy |
| Aliyun IAM API changes | Low | Version negotiation, fallback logic |

---

## Open Questions

1. **NRT Sync**: Should ES implement manifest polling (simpler) or OSS event bridge (more complex)?
2. **Sharding**: How to handle dynamic shard splitting without re-writing Lance partitions?
   Answer: simply forbid it please from dennywu.
3. **Take-over mode**: Can Lucene segments on OSS achieve acceptable performance in the future.?
   Answer: use multi-layered storage as cache and we already have the technique.
4. **Token return**: Should tokens be stored in ES session or returned to client only?
   Answer: start with return to client first, but leave the possibility to
   support persisting them in es in the future.

---

## References

- **Design Doc**: [`highlevel-design.md`](./highlevel-design.md)
- **Validation**: [`VALIDATION_GUIDE.md`](./VALIDATION_GUIDE.md) (if exists)
- **Source**: `plugins/lance-vector/`, `plugins/security-realm-cloud-iam/`
