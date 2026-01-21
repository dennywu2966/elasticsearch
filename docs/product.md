# Elasticsearch Vector Lake - Product Document

**Status:** Draft v1.0
**Authors:** Product Team
**Created:** 2026-01-07
**Target Audience:** Product, Engineering, Marketing, Sales

---

## Executive Summary

**One-liner:** Mount your S3 vector data lake in Elasticsearch in 10 seconds.

### The Problem

Organizations with AI/ML workloads have billions of vectors stored in S3 data lakes using Lance format. Today, to query these vectors through Elasticsearch, they must:

- **Copy** all data from S3 to ES (doubling storage costs)
- **Wait** days or weeks to reindex billions of vectors
- **Maintain** two copies that drift out of sync
- **Pay** 4x more for ES storage vs. S3

### The Solution

**Elasticsearch Vector Lake** lets users mount existing Lance vector indices from S3 directly into Elasticsearch—no data copying, no reindexing, instant queries.

### Key Benefits

| Benefit | Impact |
|---------|--------|
| **70% cost reduction** | S3 at $0.023/GB vs ES storage at $0.10/GB |
| **10-second time-to-query** | Mount existing indices instantly vs. weeks of reindexing |
| **10B+ vector scale** | IVF-PQ indexing enables massive scale on commodity hardware |
| **Zero API changes** | Use existing ES `knn` queries—no new SDKs to learn |

---

## User Personas

### Primary Persona: Data Lake Vector User

**Name:** "Lake-First Luna"

**Profile:**
- ML/AI platform engineer at mid-to-large enterprise
- Has 1B+ vectors already in S3 using Lance format
- Uses data lake for training, wants ES for serving
- Cost-conscious, needs to justify infrastructure spend

**Primary Need:** Query existing vectors via Elasticsearch without data migration

**Key Metric:** Time-to-first-query (target: < 1 minute from decision to working query)

**Quote:** *"I have 5 billion embeddings in S3. I just want to search them through our existing ES stack without copying anything."*

---

### Secondary Persona: Cost Optimizer

**Name:** "Budget-Conscious Blake"

**Profile:**
- Infrastructure lead responsible for cloud costs
- Currently paying for ES vector storage at scale
- Under pressure to reduce cloud spend 20%+
- Evaluates total cost of ownership

**Primary Need:** Reduce vector storage costs without sacrificing query capability

**Key Metric:** Cost per million vectors per month

**Quote:** *"We're spending $50K/month on vector storage. S3 would be $12K. Why can't we just use S3?"*

---

### Tertiary Persona: Scale Seeker

**Name:** "Scale-Hungry Sam"

**Profile:**
- Building next-gen recommendation/search system
- Hitting ES limits at 100M vectors per shard
- Needs to scale to billions without re-architecting
- Willing to trade some accuracy for scale

**Primary Need:** Search 10B+ vectors with acceptable latency

**Key Metric:** Query latency at 10B scale (target: p99 < 200ms)

**Quote:** *"HNSW doesn't scale past 100M per shard. We need IVF-PQ, but we don't want to leave ES."*

---

## Value Proposition

### For Data Lake Users

```
BEFORE: "I have vectors in S3. To use ES, I need to copy them, wait weeks, and pay double."

AFTER:  "I mounted my S3 vectors in ES in 10 seconds. Same data, no copy, full ES power."
```

### For Cost Optimizers

```
BEFORE: "Vector storage is 40% of our ES bill. We're stuck with it."

AFTER:  "We moved vectors to S3 and cut storage costs 70%. ES still serves queries."
```

### For Scale Seekers

```
BEFORE: "We can't get past 100M vectors without sharding nightmares."

AFTER:  "We're searching 10B vectors across 100 shards. IVF-PQ just works."
```

---

## Competitive Positioning

### Market Landscape

| Capability | ES Native | Pinecone | Weaviate | Milvus | **ES Vector Lake** |
|------------|-----------|----------|----------|--------|---------------------|
| Max vectors/index | ~100M | 1B | 1B | 10B | **10B+** |
| S3 native storage | No | No | No | Partial | **Yes** |
| Mount existing data | No | No | No | No | **Yes** |
| ES ecosystem (filters, aggs) | Full | None | None | None | **Full** |
| Zero data copy | No | No | No | No | **Yes** |
| Storage cost | High | High | Medium | Low | **Lowest (S3)** |

### Key Differentiators

1. **Only solution that mounts existing S3 data** - All others require data ingestion
2. **Full Elasticsearch integration** - Filters, scoring, aggregations on non-vector fields
3. **S3 as source of truth** - Data lake architecture, not another silo
4. **Instant time-to-value** - No reindexing, no ETL pipelines

### Competitive Responses

| Objection | Response |
|-----------|----------|
| "Pinecone is easier" | "Pinecone requires copying your data. We mount it in place." |
| "Milvus scales to 10B" | "Milvus doesn't integrate with ES. We give you both scale AND the ES ecosystem." |
| "Why not just use LanceDB?" | "LanceDB is great for Lance-native apps. We bring Lance data INTO your existing ES infrastructure." |

---

## Success Metrics

### Business Metrics (OKRs)

| Metric | Q1 Target | Q2 Target | Q4 Target |
|--------|-----------|-----------|-----------|
| Customers using Vector Lake | 5 (beta) | 20 | 100 |
| Total vectors under management | 10B | 100B | 1T |
| Customer NPS | N/A | 40+ | 50+ |
| Support tickets per customer/month | < 5 | < 3 | < 2 |

### Product Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Time-to-first-query | < 5 minutes | From mount command to successful query |
| Mount success rate | > 99% | Successful mounts / attempted mounts |
| Query error rate | < 0.1% | Failed queries / total queries |
| Cost savings realized | > 60% | Customer-reported vs. ES native |

### Technical Metrics (SLIs)

| Metric | Target |
|--------|--------|
| Query latency p99 (100M vectors) | < 100ms |
| Query latency p99 (1B vectors) | < 200ms |
| Cache hit rate | > 90% |
| Mount latency (100M vectors) | < 10 seconds |

---

## Customer Journey

### Stage 1: Discovery

**User Action:** Searches for "elasticsearch vector scale" or "S3 vector search"

**What They Find:**
- Blog post: "Query 10 Billion Vectors in S3 with Elasticsearch"
- Documentation: "Getting Started with ES Vector Lake"
- Case study: "How Company X Cut Vector Costs 70%"

**Success Criteria:** User understands the value proposition

---

### Stage 2: Evaluation

**User Action:** Tries the product with their own data

**What They Do:**
1. Install plugin (one command)
2. Mount their S3 Lance index (one API call)
3. Run a KNN query (existing ES syntax)

**What They See:**
```bash
# Install (30 seconds)
bin/elasticsearch-plugin install lance-vector

# Mount (10 seconds)
PUT /my-vectors
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "lance_vector",
        "storage": {
          "type": "s3",
          "uri": "s3://my-bucket/vectors/"
        }
      }
    }
  }
}

# Query (immediate)
POST /my-vectors/_search
{
  "knn": { "field": "embedding", "query_vector": [...], "k": 10 }
}
```

**Success Criteria:** Working query in < 5 minutes

**What Could Go Wrong:**
- S3 permissions misconfigured → Clear error message with fix instructions
- Lance schema mismatch → Validation API catches before mount
- Network latency → Documentation on region selection

---

### Stage 3: POC

**User Action:** Tests with production-like scale and queries

**Duration:** 2-4 weeks

**What They Validate:**
- [ ] Query latency meets requirements
- [ ] Cache hit rate is acceptable
- [ ] Cost model makes sense
- [ ] Integration with existing ES workflows works

**Support Touchpoints:**
- Dedicated Slack channel (beta)
- Weekly check-in call
- Access to engineering for blockers

**Success Criteria:** Sign-off from user's tech lead

---

### Stage 4: Production

**User Action:** Deploys to production workload

**What They Need:**
- Runbook for common operations
- Alerting recommendations
- Capacity planning guidance
- Support escalation path

**Success Criteria:** Stable operation for 30 days

---

### Stage 5: Scale

**User Action:** Expands usage to more indices/vectors

**What Enables This:**
- Confidence from production stability
- Clear cost savings demonstrated
- New use cases identified

**Success Criteria:** 3x growth in vectors under management

---

## Pricing & Licensing

### Licensing Model

| Tier | License | Features |
|------|---------|----------|
| **Community** | Apache 2.0 | Mount, query, basic caching |
| **Enterprise** | Elastic License | + Advanced caching, monitoring, support |

### Cost Model

Users pay for:
1. **Elasticsearch** - Existing ES licensing (no change)
2. **S3 Storage** - Standard AWS S3 rates (~$0.023/GB/month)
3. **S3 API Calls** - GET/PUT/LIST operations (~$0.0004/1000 requests)

**Cost Estimation Guide:**

| Scale | ES Native Cost/mo | Vector Lake Cost/mo | Savings |
|-------|-------------------|---------------------|---------|
| 100M vectors (300GB) | ~$300 | ~$70 | 77% |
| 1B vectors (3TB) | ~$3,000 | ~$700 | 77% |
| 10B vectors (30TB) | ~$30,000 | ~$7,000 | 77% |

*Note: Estimates based on typical ES storage costs and S3 standard pricing. Actual costs vary.*

---

## FAQ / Objection Handling

### Technical Questions

| Question | Answer |
|----------|--------|
| **Is my data safe in S3?** | S3 is the source of truth. ES only caches locally. If ES fails, your data is safe in S3. |
| **What if S3 goes down?** | Queries can serve from cache. If cache misses, queries fail gracefully with clear errors. S3 has 99.99% availability. |
| **What about query accuracy?** | IVF-PQ recall is configurable (75-99%+). Higher `nprobe` = better accuracy, slightly higher latency. We provide tuning guides. |
| **Can I still use filters?** | Yes. Term, range, and boolean filters work. They're pushed to Lance for efficiency. |

### Business Questions

| Question | Answer |
|----------|--------|
| **How is this different from LanceDB?** | LanceDB is a standalone vector DB. Vector Lake brings Lance data INTO Elasticsearch, preserving your ES investment. |
| **Will you support GCS/Azure?** | S3 first (GA). GCS and Azure Blob are on the roadmap for future releases. |
| **What about my existing HNSW indices?** | Keep using them! Vector Lake is for new large-scale use cases. HNSW is still best for < 100M vectors. |
| **Who do I call for support?** | Elastic support handles Vector Lake issues. S3 infrastructure issues go to AWS. We provide clear triage guidance. |

### Adoption Questions

| Question | Answer |
|----------|--------|
| **How long does migration take?** | There's no migration. You mount existing data in seconds. |
| **Do I need to change my queries?** | No. Standard ES `knn` queries work unchanged. |
| **What's the learning curve?** | Minimal. If you know ES, you know Vector Lake. The only new concept is the mount API. |

---

## Go-to-Market Plan

### Phase 1: Private Beta (Weeks 1-12)

**Goals:**
- Validate product-market fit with 5-10 design partners
- Gather feedback on UX, performance, gaps
- Build case studies

**Activities:**
- Hand-select beta customers (criteria below)
- Weekly feedback calls
- Rapid iteration based on feedback
- No public marketing

**Beta Customer Criteria:**
- [ ] Has 100M+ vectors in S3/Lance today
- [ ] Active Elasticsearch user
- [ ] Willing to provide feedback (calls, surveys)
- [ ] Not a direct competitor
- [ ] Technical team can self-serve with support

---

### Phase 2: Public Beta (Weeks 13-20)

**Goals:**
- Broader validation (50+ customers)
- Stress-test at scale
- Finalize documentation

**Activities:**
- Blog post announcement
- Documentation site live
- Community Slack channel
- Conference talks (if timing aligns)

---

### Phase 3: GA (Week 24+)

**Goals:**
- General availability
- Full support coverage
- Sales enablement complete

**Activities:**
- Press release
- Analyst briefings
- Customer webinar
- Sales training

---

## Documentation Strategy

### Day 1 (Alpha)

- [ ] README with installation and basic usage
- [ ] API reference (mount, query, status)
- [ ] Troubleshooting guide (common errors)

### Beta

- [ ] "Getting Started in 5 Minutes" tutorial
- [ ] Architecture overview
- [ ] Performance tuning guide
- [ ] Security configuration guide

### GA

- [ ] Complete user guide
- [ ] Video walkthrough
- [ ] Interactive demo environment
- [ ] Migration guide (from other vector DBs)
- [ ] Best practices guide

---

## Telemetry & Analytics

### Usage Metrics to Collect (with consent)

| Metric | Purpose |
|--------|---------|
| Mount operations | Feature adoption |
| Query volume | Engagement |
| Index sizes | Scale validation |
| Error types | Quality improvement |
| Cache hit rates | Performance tuning guidance |

### Privacy Considerations

- No vector data collected
- No query content collected
- Aggregated metrics only
- Opt-out available
- GDPR compliant

---

## Risk Assessment

### Product Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Low adoption | Medium | High | Strong beta program, clear value prop |
| Performance issues at scale | Medium | High | Extensive benchmarking, conservative claims |
| S3 cost surprises | Low | Medium | Cost calculator, documentation |
| Competitive response | Medium | Medium | First-mover advantage, ES integration moat |

### Technical Risks (from Engineering)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| JNI instability | Medium | High | Extensive testing, graceful degradation |
| Lance library bugs | Low | High | Pin versions, contribute upstream fixes |
| S3 latency spikes | Medium | Medium | Aggressive caching, circuit breakers |

---

## Open Questions

1. **Product naming:** Is "Elasticsearch Vector Lake" the right name? Alternatives:
   - ES LakeVector
   - Elastic Vector Connect
   - ES Vector Bridge

2. **Pricing tier split:** What features differentiate Community vs. Enterprise?

3. **Multi-cloud timeline:** When do we commit to GCS/Azure publicly?

4. **Partner strategy:** Co-marketing with LanceDB? AWS partnership?

---

## Appendix: Feature-to-Persona Mapping

| Feature | Lake-First Luna | Budget Blake | Scale-Hungry Sam |
|---------|-----------------|--------------|------------------|
| Mount existing S3 indices | **Primary** | Secondary | Secondary |
| S3 as source of truth | Primary | **Primary** | Secondary |
| 10B+ vector scale | Secondary | Secondary | **Primary** |
| IVF-PQ indexing | Secondary | Secondary | **Primary** |
| ES filter integration | Primary | Secondary | Primary |
| Local ESSD caching | Secondary | Primary | Primary |
| Cost monitoring | Secondary | **Primary** | Secondary |

---

**Document Status:** Ready for Review
**Next Steps:**
1. Executive review of value proposition
2. Legal review of licensing section
3. Marketing review of naming and positioning
