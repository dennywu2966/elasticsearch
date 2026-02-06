# P0 Task Plan: Lance Plugin Production Hardening (Soak + Resource Leak Baseline)

**Based on**: `future_plan_refined_zh.md` P0 requirements
**Status**: `in_progress`
**Created**: 2026-02-06
**Target**: 7-day soak testing + zero resource leak baseline

---

## Goal

Achieve production-ready state for Lance Vector plugin with:
1. **7-day soak test** passing without degradation
2. **Zero resource leaks** (native memory, file descriptors, threads)
3. **Explainable resource behavior** (metrics, documentation)

---

## Current Baseline Assessment

### Already Completed (from validation reports)
- ✅ Memory leak fixes (ThreadLocal, cache eviction, plugin lifecycle)
- ✅ Basic functionality validated (8-phase validation complete)
- ✅ OSS integration working
- ✅ Performance baseline established (33ms avg query time)

### Outstanding P0 Requirements (from future_plan_refined_zh.md)

#### P0.1 Resource & Memory Governance
- [ ] **Cache eviction must close resources**: Verify `LanceDatasetRegistry` eviction callback properly calls `LanceDataset.close()`
- [ ] **Arrow allocator strategy固化**: Document `RootAllocator` limits, child allocator boundaries, failure modes
- [ ] **Avoid unpredictable environment variable reflection**: `RealLanceDataset.setEnvIfChanged()` uses reflection to modify `System.getenv()` - high risk
- [ ] **Sensitive information protection**: Verify no plaintext credentials in logs/exceptions

#### P0.2 Concurrency & Thread Safety
- [ ] Document `com.lancedb.lance.Dataset` / `LanceScanner` concurrent semantics
- [ ] Add concurrency stress tests: concurrent queries + cache eviction + dataset reload

#### P0.3 Correctness (Cross-shard)
- [ ] **Clarify data model**: One index → one Lance dataset, or one shard → one dataset partition?
- [ ] Document `_id` join complexity and limitations

#### P0.4 Observability & Logging
- [ ] Standardize log levels (no per-candidate INFO logs)
- [ ] Implement metrics: query count, p50/p95/p99 latency, dataset open/scan time, cache hit rate, native memory
- [ ] Health check mechanism

#### P0.5 Testing & Release Criteria
- [ ] **7-day soak test**: native memory, FDs, CPU, GC, latency drift
- [ ] Lance version compatibility: `_distance` vs `distance` field detection

---

## Phases

| Phase | Status | Description | DoD |
|-------|--------|-------------|-----|
| P0.0 | `pending` | Environment setup & baseline measurement | ES built, baseline metrics captured |
| P0.1 | `complete` | Resource leak audit & fixes | Cache eviction + env var documentation complete |
| P0.2 | `pending` | Concurrency stress testing | Thread safety validated |
| P0.3 | `pending` | Observability implementation | Metrics & logs production-ready |
| P0.4 | `pending` | 7-day soak test execution | Soak test passed |
| P0.5 | `pending` | Production baseline report | Document + rollback procedures |

---

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| (None yet) | - | - |

---

## Files Created/Modified

### Planning Files
- `task_plan.md` (this file)
- `findings.md` (research findings)
- `progress.md` (session log)

### Code Files (anticipated)
- TBD

---

## Next Steps

1. **P0.0**: Build ES distribution and establish baseline metrics
2. **P0.1**: Audit cache eviction, Arrow allocator, environment variable reflection
3. **P0.2**: Implement concurrency stress tests
4. **P0.3**: Add comprehensive metrics and health checks
5. **P0.4**: Run 7-day soak test with monitoring
6. **P0.5**: Generate production baseline report with rollback procedures
