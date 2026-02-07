/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
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
 * A minimal Lucene query that joins Lance candidates to Lucene documents by _id.
 * This is intentionally simple for Phase 1 and uses a fake dataset loader.
 * <p>
 * Implements QueryProfilerProvider to provide detailed timing information
 * when profiling is enabled via the profile=true search parameter.
 */
public class LanceKnnQuery extends Query implements QueryProfilerProvider {
    private static final Logger logger = LogManager.getLogger(LanceKnnQuery.class);

    /**
     * Strategy for how filters are applied during kNN search.
     */
    public enum FilterStrategy {
        /** No filter applied */
        NONE,
        /** Pre-filter: push filtered IDs to Lance SDK before search */
        PRE_FILTER,
        /** Post-filter: intersect Lance results with Lucene filter bitset */
        POST_FILTER
    }

    /**
     * Decision about which filter strategy to use, including the count of matching documents.
     *
     * @param strategy The chosen filter strategy
     * @param filteredDocCount Number of documents matching the filter (-1 if no filter)
     */
    public record FilterDecision(FilterStrategy strategy, int filteredDocCount) {}

    /**
     * Decide which filter strategy to use based on the filter selectivity and heuristic.
     *
     * @param filteredDocCount Number of docs matching the filter, or -1 if no filter
     * @param k Number of nearest neighbors requested
     * @param heuristic The pre-filter heuristic setting
     * @return FilterDecision with strategy and doc count
     */
    public static FilterDecision decideFilterStrategy(int filteredDocCount, int k, PreFilterHeuristic heuristic) {
        if (filteredDocCount < 0) {
            return new FilterDecision(FilterStrategy.NONE, filteredDocCount);
        }
        if (heuristic.shouldPreFilter(filteredDocCount, k)) {
            return new FilterDecision(FilterStrategy.PRE_FILTER, filteredDocCount);
        }
        return new FilterDecision(FilterStrategy.POST_FILTER, filteredDocCount);
    }

    /**
     * Create an Arrow VarCharVector containing document _ids for pre-filtering.
     * <p>
     * This vector is passed to the Lance SDK via JNI for zero-copy filter pushdown.
     * The caller is responsible for closing the returned vector.
     *
     * @param ids List of document _id strings
     * @param allocator Arrow buffer allocator
     * @return VarCharVector with the _ids, caller must close
     */
    public static VarCharVector createArrowIdVector(List<String> ids, BufferAllocator allocator) {
        VarCharVector vector = new VarCharVector("_id_filter", allocator);
        vector.allocateNew(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            byte[] bytes = ids.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            vector.set(i, bytes);
        }
        vector.setValueCount(ids.size());
        return vector;
    }

    /**
     * Extract _id strings from documents matching the filter bitset.
     * <p>
     * Uses Lucene stored fields to resolve docId to _id. This involves disk I/O
     * so should only be called when the filter is highly selective (M &lt; K*2).
     *
     * @param reader The LeafReader for this segment
     * @param filterBits BitSet of docs matching the filter
     * @param maxDoc Maximum document ordinal to scan
     * @return List of _id strings for matching documents
     * @throws IOException if stored fields cannot be read
     */
    static List<String> extractFilteredIds(org.apache.lucene.index.LeafReader reader, java.util.BitSet filterBits, int maxDoc)
        throws IOException {
        List<String> ids = new ArrayList<>();
        org.apache.lucene.index.StoredFields storedFields = reader.storedFields();
        for (int docId = filterBits.nextSetBit(0); docId >= 0 && docId < maxDoc; docId = filterBits.nextSetBit(docId + 1)) {
            org.apache.lucene.document.Document doc = storedFields.document(docId, java.util.Set.of(IdFieldMapper.NAME));
            org.apache.lucene.index.IndexableField idField = doc.getField(IdFieldMapper.NAME);
            if (idField != null) {
                String id = Uid.decodeId(idField.binaryValue().bytes);
                ids.add(id);
            }
        }
        return ids;
    }

    private final String fieldName;
    private final String storageUri;
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final String similarity;
    private final Query filter;
    private final int dims;
    private final String ossEndpoint;
    private final String ossAccessKeyId;
    private final String ossAccessKeySecret;

    public LanceKnnQuery(
        String fieldName,
        String storageUri,
        float[] queryVector,
        int k,
        int numCandidates,
        String similarity,
        Query filter,
        int dims,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret
    ) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.storageUri = Objects.requireNonNull(storageUri);
        this.queryVector = Objects.requireNonNull(queryVector);
        this.k = k;
        this.numCandidates = numCandidates;
        this.similarity = similarity == null ? "cosine" : similarity;
        this.filter = filter;
        this.dims = dims;
        this.ossEndpoint = ossEndpoint;
        this.ossAccessKeyId = ossAccessKeyId;
        this.ossAccessKeySecret = ossAccessKeySecret;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        // Initialize timing context for this query
        LanceTimingContext context = null;
        try {
            context = LanceTimingContext.getOrCreate();
            // Activate timing collection - this enables LanceTimer to record timings
            context.activate();

            // Use unified registry that automatically selects RealLanceDataset for .lance files
            // and FakeLanceDataset for JSON test files
            // For OSS URIs, include OSS configuration
            LanceDatasetConfig config;
            if (storageUri.startsWith("oss://") && ossEndpoint != null) {
                config = new LanceDatasetConfig("_id", "vector", dims, ossEndpoint, ossAccessKeyId, ossAccessKeySecret);
            } else {
                config = new LanceDatasetConfig("_id", "vector", dims, null, null, null);
            }

            LanceDataset dataset;
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.REGISTRY_CACHE_LOOKUP)) {
                dataset = LanceDatasetRegistry.getOrLoad(storageUri, dims, config);
            }

            logger.debug("Lance KNN: dataset has {} dims, numCandidates={}, k={}", dataset.dims(), numCandidates, k);
            // Store for per-leaf execution
            final Query filterQuery = this.filter;
            final float queryBoost = boost;
            final int topK = k;
            final LanceDataset capturedDataset = dataset;
            final LanceTimingContext capturedContext = context;  // Capture for cleanup

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
                    // Hybrid buildDocScores: filter eval → strategy decision → search → join
                    Map<Integer, Float> docScores = buildDocScores(
                        context,
                        capturedDataset,
                        queryVector,
                        topK,
                        "vector",
                        filterQuery,
                        capturedContext
                    );
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
                                        return -1; // Not yet positioned
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
            // Clean up timing context to prevent ThreadLocal memory leak
            if (context != null) {
                context.deactivate();
                context.clear();
            }
        }
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

    private Map<Integer, Float> buildDocScores(
        LeafReaderContext context,
        LanceDataset dataset,
        float[] queryVector,
        int k,
        String columnName,
        Query filter,
        LanceTimingContext timing
    ) throws IOException {
        var reader = context.reader();
        int maxDoc = reader.maxDoc();

        // Phase 1: Evaluate filter
        java.util.BitSet filterBits = null;
        int filteredDocCount = -1;
        if (filter != null) {
            long filterStart = System.nanoTime();
            org.apache.lucene.search.IndexSearcher searcher = new org.apache.lucene.search.IndexSearcher(reader);
            org.apache.lucene.search.Weight filterWeight = searcher.createWeight(
                searcher.rewrite(filter),
                org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES,
                1.0f
            );
            org.apache.lucene.search.Scorer filterScorer = filterWeight.scorer(context);
            if (filterScorer != null) {
                filterBits = new java.util.BitSet(maxDoc);
                org.apache.lucene.search.DocIdSetIterator filterIter = filterScorer.iterator();
                for (int doc = filterIter.nextDoc(); doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS; doc = filterIter
                    .nextDoc()) {
                    filterBits.set(doc);
                }
                filteredDocCount = filterBits.cardinality();
            } else {
                // Filter matches nothing → return empty
                return java.util.Collections.emptyMap();
            }
            if (timing != null) {
                timing.record(LanceTimingContext.LanceTimingStage.FILTER_PROCESSING, (System.nanoTime() - filterStart) / 1_000_000);
            }
        }

        // Phase 2: Decide strategy
        PreFilterHeuristic heuristic = PreFilterHeuristic.AUTO; // TODO: read from index settings via SearchExecutionContext
        FilterDecision decision = decideFilterStrategy(filteredDocCount, k, heuristic);

        logger.debug(
            "Lance kNN filter decision: strategy={}, filteredDocs={}, k={}, heuristic={}",
            decision.strategy(),
            filteredDocCount,
            k,
            heuristic
        );

        // Phase 3: Execute search based on strategy
        List<LanceDataset.Candidate> results;
        if (decision.strategy() == FilterStrategy.PRE_FILTER) {
            // Extract _ids from matching docs, push to Lance as pre-filter
            long preFilterStart = System.nanoTime();
            List<String> filteredIds = extractFilteredIds(reader, filterBits, maxDoc);
            if (timing != null) {
                timing.record(LanceTimingContext.LanceTimingStage.ID_MATCHING, (System.nanoTime() - preFilterStart) / 1_000_000);
            }

            long searchStart = System.nanoTime();
            try (
                org.apache.arrow.memory.BufferAllocator allocator = new org.apache.arrow.memory.RootAllocator(1024 * 1024);
                var idVector = createArrowIdVector(filteredIds, allocator)
            ) {
                results = dataset.search(queryVector, k, columnName, idVector);
            }
            if (timing != null) {
                timing.record(LanceTimingContext.LanceTimingStage.NATIVE_SEARCH_EXECUTION, (System.nanoTime() - searchStart) / 1_000_000);
            }
        } else {
            // POST_FILTER or NONE - unfiltered Lance search
            long searchStart = System.nanoTime();
            results = dataset.search(queryVector, numCandidates, similarity);
            if (timing != null) {
                timing.record(LanceTimingContext.LanceTimingStage.NATIVE_SEARCH_EXECUTION, (System.nanoTime() - searchStart) / 1_000_000);
            }
        }

        // Phase 4: Map results to Lucene doc IDs
        long joinStart = System.nanoTime();
        Map<Integer, Float> docScores = new java.util.HashMap<>();
        org.apache.lucene.index.Terms terms = reader.terms(IdFieldMapper.NAME);
        if (terms != null) {
            org.apache.lucene.index.TermsEnum termsEnum = terms.iterator();
            org.apache.lucene.index.PostingsEnum postingsEnum = null;

            for (LanceDataset.Candidate candidate : results) {
                BytesRef encodedId = Uid.encodeId(candidate.id());
                if (termsEnum.seekExact(encodedId)) {
                    postingsEnum = termsEnum.postings(postingsEnum, 0);
                    int docId = postingsEnum.nextDoc();
                    if (docId != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS) {
                        // Post-filter: check if doc passes filter
                        if (decision.strategy() == FilterStrategy.POST_FILTER && filterBits != null) {
                            if (filterBits.get(docId) == false) {
                                continue; // Doc doesn't pass filter, skip
                            }
                        }
                        docScores.put(docId, candidate.score());
                    }
                }
            }
        }
        if (timing != null) {
            timing.record(LanceTimingContext.LanceTimingStage.SCORE_AGGREGATION, (System.nanoTime() - joinStart) / 1_000_000);
        }

        logger.debug(
            "Lance kNN search complete: strategy={}, candidates={}, results={}, filteredDocs={}",
            decision.strategy(),
            results.size(),
            docScores.size(),
            filteredDocCount
        );

        // Keep only top k by score
        if (docScores.size() > k) {
            return docScores.entrySet()
                .stream()
                .sorted(Map.Entry.<Integer, Float>comparingByValue().reversed())
                .limit(k)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return docScores;
    }

    @Override
    public String toString(String field) {
        return "LanceKnnQuery(" + fieldName + ", uri=" + storageUri + ")";
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
            && storageUri.equals(other.storageUri)
            && similarity.equals(other.similarity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, storageUri, k, numCandidates, similarity);
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    /**
     * Get the timing breakdown for this query.
     * This method is called to retrieve timing information.
     *
     * @return Map containing timing breakdown, or null if profiling is not active
     */
    private Map<String, Object> getTimingBreakdown() {
        LanceTimingContext context = LanceTimingContext.getOrCreate();
        if (context != null && context.isActive()) {
            Map<String, Object> timing = context.toDebugMap();
            // Log the timing breakdown for debugging
            logger.info("Lance kNN timing breakdown: {}", timing);
            return timing;
        }
        return null;
    }

    /**
     * Store the profiling information in the QueryProfiler.
     * This is called by Elasticsearch when profiling is enabled.
     * <p>
     * Note: This method logs the timing breakdown. Full Profile API integration
     * will be added in a future update to include timing in the profile response.
     *
     * @param queryProfiler the query profiler (not currently used for custom timing)
     */
    @Override
    public void profile(QueryProfiler queryProfiler) {
        Map<String, Object> timing = getTimingBreakdown();
        if (timing != null && timing.isEmpty() == false) {
            // Get the profile breakdown for this query and add Lance timing to debug info
            org.elasticsearch.search.profile.query.QueryProfileBreakdown breakdown = queryProfiler.getQueryBreakdown(this);
            if (breakdown != null) {
                // Inject Lance timing into the debug map using the new API
                breakdown.putAllDebugData(timing);
                logger.debug("Lance kNN profile timing: {}", timing);
            }
        }
    }
}
