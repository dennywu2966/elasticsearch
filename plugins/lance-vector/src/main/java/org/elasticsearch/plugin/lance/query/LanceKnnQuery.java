/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.plugin.lance.profile.LanceTimer;
import org.elasticsearch.plugin.lance.profile.LanceTimingContext;
import org.elasticsearch.plugin.lance.storage.LanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDatasetConfig;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.search.profile.query.QueryProfiler;
import org.elasticsearch.search.vectors.QueryProfilerProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Lucene query that joins Lance candidates to Lucene documents by _id.
 * <p>
 * In <b>legacy mode</b> (single URI), the dataset is opened and searched once in
 * {@code createWeight()} and the candidates are shared across all leaf segments.
 * <p>
 * In <b>shard-aware mode</b> (uri_prefix + template), the dataset URI is resolved
 * per-shard and searched in {@code scorerSupplier()} so each shard reads its own
 * Lance dataset.
 * <p>
 * Implements QueryProfilerProvider to provide detailed timing information
 * when profiling is enabled via the profile=true search parameter.
 */
public class LanceKnnQuery extends Query implements QueryProfilerProvider {
    private static final Logger logger = LogManager.getLogger(LanceKnnQuery.class);

    private final String fieldName;
    private final LanceStorageConfig storageConfig;
    private final String indexName;
    private final int shardId; // -1 = unknown, resolve per-leaf
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final String similarity;
    private final Query filter;
    private final int dims;

    public LanceKnnQuery(
        String fieldName,
        LanceStorageConfig storageConfig,
        String indexName,
        int shardId,
        float[] queryVector,
        int k,
        int numCandidates,
        String similarity,
        Query filter,
        int dims
    ) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.storageConfig = Objects.requireNonNull(storageConfig);
        this.indexName = Objects.requireNonNull(indexName);
        this.shardId = shardId;
        this.queryVector = Objects.requireNonNull(queryVector);
        this.k = k;
        this.numCandidates = numCandidates;
        this.similarity = similarity == null ? "cosine" : similarity;
        this.filter = filter;
        this.dims = dims;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        LanceTimingContext timingContext = null;
        try {
            timingContext = LanceTimingContext.getOrCreate();
            timingContext.activate();

            final Query filterQuery = this.filter;
            final float queryBoost = boost;
            final int topK = k;
            final LanceTimingContext capturedContext = timingContext;

            // Legacy mode: search once, share candidates across all segments
            final List<LanceDataset.Candidate> sharedCandidates;
            if (storageConfig.isShardAware() == false) {
                String resolvedUri = storageConfig.resolveUri(indexName, Math.max(shardId, 0));
                LanceDatasetConfig config = buildDatasetConfig();
                LanceDataset dataset;
                try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.REGISTRY_CACHE_LOOKUP)) {
                    dataset = LanceDatasetRegistry.getOrLoad(resolvedUri, dims, config);
                    sharedCandidates = dataset.search(queryVector, numCandidates, similarity);
                }
                logger.debug(
                    "Lance KNN (legacy): dataset has {} dims, searched with numCandidates={}, got {} candidates",
                    dataset.dims(),
                    numCandidates,
                    sharedCandidates.size()
                );
            } else {
                sharedCandidates = null; // Will be resolved per-shard in scorerSupplier
            }

            return new Weight(this) {
                @Override
                public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                    ScorerSupplier supplier = scorerSupplier(context);
                    if (supplier == null) {
                        return Explanation.noMatch("no matching docs");
                    }
                    Scorer scorer = supplier.get(1);
                    int advanced = scorer.iterator().advance(doc);
                    if (advanced == doc) {
                        return Explanation.match(scorer.score(), "matched lance candidate");
                    }
                    return Explanation.noMatch("not in candidate set");
                }

                @Override
                public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
                    List<LanceDataset.Candidate> candidates;
                    if (sharedCandidates != null) {
                        // Legacy: use shared candidates
                        candidates = sharedCandidates;
                    } else {
                        // Shard-aware: resolve URI and search per-shard
                        int leafShardId = resolveShardId(context);
                        String resolvedUri = storageConfig.resolveUri(indexName, leafShardId);
                        LanceDatasetConfig config = buildDatasetConfig();
                        LanceDataset dataset;
                        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.REGISTRY_CACHE_LOOKUP)) {
                            dataset = LanceDatasetRegistry.getOrLoad(resolvedUri, dims, config);
                            candidates = dataset.search(queryVector, numCandidates, similarity);
                        }
                        logger.debug(
                            "Lance KNN (shard-aware): shard={}, uri={}, candidates={}",
                            leafShardId,
                            resolvedUri,
                            candidates.size()
                        );
                    }

                    // Create filter weight using an IndexSearcher from the context's top-level reader
                    Weight filterWeight = null;
                    if (filterQuery != null) {
                        IndexSearcher contextSearcher = new IndexSearcher(context.parent);
                        filterWeight = filterQuery.createWeight(contextSearcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
                    }
                    Map<Integer, Float> docScores = buildDocScores(context, candidates, filterWeight, topK);
                    if (docScores.isEmpty()) {
                        return null;
                    }
                    List<Integer> docIds = new ArrayList<>(docScores.keySet());
                    Collections.sort(docIds);

                    return new ScorerSupplier() {
                        @Override
                        public Scorer get(long leadCost) throws IOException {
                            DocIdSetIterator it = new DocIdSetIterator() {
                                int idx = -1;

                                @Override
                                public int docID() {
                                    if (idx < 0) {
                                        return -1;
                                    } else if (idx >= docIds.size()) {
                                        return NO_MORE_DOCS;
                                    }
                                    return docIds.get(idx);
                                }

                                @Override
                                public int nextDoc() {
                                    idx++;
                                    if (idx >= docIds.size()) {
                                        return NO_MORE_DOCS;
                                    }
                                    return docIds.get(idx);
                                }

                                @Override
                                public int advance(int target) {
                                    while (idx + 1 < docIds.size() && docIds.get(idx + 1) < target) {
                                        idx++;
                                    }
                                    if (idx + 1 < docIds.size()) {
                                        idx++;
                                        return docIds.get(idx);
                                    }
                                    return NO_MORE_DOCS;
                                }

                                @Override
                                public long cost() {
                                    return docIds.size();
                                }
                            };
                            return new LanceScorer(it, docScores, queryBoost);
                        }

                        @Override
                        public long cost() {
                            return docIds.size();
                        }
                    };
                }

                @Override
                public boolean isCacheable(LeafReaderContext ctx) {
                    return false;
                }
            };
        } finally {
            if (timingContext != null) {
                timingContext.deactivate();
                timingContext.clear();
            }
        }
    }

    private LanceDatasetConfig buildDatasetConfig() {
        String ossEp = storageConfig.ossEndpoint();
        String ossKeyId = storageConfig.ossAccessKeyId();
        String ossKeySecret = storageConfig.ossAccessKeySecret();
        if (ossEp != null) {
            return new LanceDatasetConfig("_id", "vector", dims, ossEp, ossKeyId, ossKeySecret);
        }
        return new LanceDatasetConfig("_id", "vector", dims, null, null, null);
    }

    /**
     * Resolve the shard ID from LeafReaderContext.
     * Uses the shardId passed at construction if available.
     * Otherwise defaults to 0.
     */
    private int resolveShardId(LeafReaderContext context) {
        if (shardId >= 0) {
            return shardId;
        }
        return 0;
    }

    private static class LanceScorer extends Scorer {
        private final DocIdSetIterator iterator;
        private final Map<Integer, Float> docScores;
        private final float boost;

        LanceScorer(DocIdSetIterator iterator, Map<Integer, Float> docScores, float boost) {
            this.iterator = iterator;
            this.docScores = docScores;
            this.boost = boost;
        }

        @Override
        public DocIdSetIterator iterator() {
            return iterator;
        }

        @Override
        public int docID() {
            return iterator.docID();
        }

        @Override
        public float score() {
            return docScores.getOrDefault(docID(), 0f) * boost;
        }

        @Override
        public float getMaxScore(int upTo) {
            return Float.POSITIVE_INFINITY;
        }
    }

    private static Map<Integer, Float> buildDocScores(
        LeafReaderContext context,
        List<LanceDataset.Candidate> candidates,
        Weight filterWeight,
        int k
    ) throws IOException {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        var reader = context.reader();
        var terms = reader.terms(IdFieldMapper.NAME);
        if (terms == null) {
            return Map.of();
        }
        // Build filter bitset once if filter is present
        java.util.BitSet filterBitSet = null;
        if (filterWeight != null) {
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.FILTER_PROCESSING)) {
                ScorerSupplier filterSupplier = filterWeight.scorerSupplier(context);
                if (filterSupplier != null) {
                    Scorer filterScorer = filterSupplier.get(1);
                    filterBitSet = new java.util.BitSet(reader.maxDoc());
                    DocIdSetIterator filterIter = filterScorer.iterator();
                    for (int doc = filterIter.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = filterIter.nextDoc()) {
                        filterBitSet.set(doc);
                    }
                } else {
                    return Map.of();
                }
            }
        }

        Map<Integer, Float> scores = new java.util.HashMap<>();
        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.ID_MATCHING)) {
            for (LanceDataset.Candidate c : candidates) {
                BytesRef encodedId = Uid.encodeId(c.id());
                Term term = new Term(IdFieldMapper.NAME, encodedId);
                PostingsEnum postings = reader.postings(term);
                if (postings == null) {
                    continue;
                }
                for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
                    if (filterBitSet != null && filterBitSet.get(doc) == false) {
                        continue;
                    }
                    scores.merge(doc, c.score(), Math::max);
                }
            }
        }

        // Keep only top k by score
        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.SCORE_AGGREGATION)) {
            if (scores.size() > k) {
                return scores.entrySet()
                    .stream()
                    .sorted(Map.Entry.<Integer, Float>comparingByValue().reversed())
                    .limit(k)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
        }
        return scores;
    }

    @Override
    public String toString(String field) {
        if (storageConfig.isShardAware()) {
            return "LanceKnnQuery(" + fieldName + ", index=" + indexName + ", shard=" + shardId + ", shardAware=true)";
        }
        String uri = storageConfig.uri();
        return "LanceKnnQuery(" + fieldName + ", uri=" + uri + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (sameClassAs(obj) == false) {
            return false;
        }
        LanceKnnQuery other = (LanceKnnQuery) obj;
        return k == other.k
            && numCandidates == other.numCandidates
            && fieldName.equals(other.fieldName)
            && indexName.equals(other.indexName)
            && shardId == other.shardId
            && similarity.equals(other.similarity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, indexName, shardId, k, numCandidates, similarity);
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    private Map<String, Object> getTimingBreakdown() {
        LanceTimingContext context = LanceTimingContext.getOrCreate();
        if (context != null && context.isActive()) {
            Map<String, Object> timing = context.toDebugMap();
            logger.info("Lance kNN timing breakdown: {}", timing);
            return timing;
        }
        return null;
    }

    @Override
    public void profile(QueryProfiler queryProfiler) {
        Map<String, Object> timing = getTimingBreakdown();
        if (timing != null && timing.isEmpty() == false) {
            org.elasticsearch.search.profile.query.QueryProfileBreakdown breakdown = queryProfiler.getQueryBreakdown(this);
            if (breakdown != null) {
                breakdown.putAllDebugData(timing);
                logger.debug("Lance kNN profile timing: {}", timing);
            }
        }
    }
}
