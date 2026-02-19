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
 * Supports two modes of operation:
 * <ul>
 *   <li><b>Legacy mode</b> (single URI): The dataset is opened and searched once in
 *       {@code createWeight()} and the candidates are shared across all leaf segments.</li>
 *   <li><b>Shard-aware mode</b> (uri_prefix + template): The dataset URI is resolved
 *       per-shard and searched in {@code scorerSupplier()} so each shard reads its own
 *       Lance dataset.</li>
 * </ul>
 * <p>
 * Filter support includes:
 * <ul>
 *   <li><b>Pre-filter</b>: Push filtered IDs to Lance SDK before vector search (for small filters)</li>
 *   <li><b>Post-filter</b>: Intersect Lance results with Lucene filter bitset (default)</li>
 *   <li><b>Auto</b>: Automatically choose based on filter selectivity</li>
 * </ul>
 * <p>
 * Implements QueryProfilerProvider to provide detailed timing information
 * when profiling is enabled via the profile=true search parameter.
 */
public class LanceKnnQuery extends Query implements QueryProfilerProvider {
    private static final Logger logger = LogManager.getLogger(LanceKnnQuery.class);
    private static final int DEFAULT_NPROBES = 20;
    private static final boolean TRACE_DEBUG_ENABLED = Boolean.parseBoolean(System.getProperty("lance.trace.debug", "false"));

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
        boolean success = false;
        try {
            vector.allocateNew(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                byte[] bytes = ids.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                vector.set(i, bytes);
            }
            vector.setValueCount(ids.size());
            success = true;
            return vector;
        } finally {
            if (success == false) {
                vector.close();
            }
        }
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
    private final LanceStorageConfig storageConfig;
    private final String indexName;
    private final int shardId; // -1 = unknown, resolve per-leaf
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final int nprobes;
    private final String similarity;
    private final Query filter;
    private final int dims;
    private final PreFilterHeuristic prefilterHeuristic;

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
        int dims,
        PreFilterHeuristic prefilterHeuristic
    ) {
        this(
            fieldName,
            storageConfig,
            indexName,
            shardId,
            queryVector,
            k,
            numCandidates,
            similarity,
            filter,
            dims,
            DEFAULT_NPROBES,
            prefilterHeuristic
        );
    }

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
        int dims,
        int nprobes,
        PreFilterHeuristic prefilterHeuristic
    ) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.storageConfig = Objects.requireNonNull(storageConfig);
        this.indexName = Objects.requireNonNull(indexName);
        this.shardId = shardId;
        this.queryVector = Objects.requireNonNull(queryVector);
        this.k = k;
        this.numCandidates = numCandidates;
        this.nprobes = nprobes > 0 ? nprobes : DEFAULT_NPROBES;
        this.similarity = similarity == null ? "cosine" : similarity;
        this.filter = filter;
        this.dims = dims;
        this.prefilterHeuristic = prefilterHeuristic != null ? prefilterHeuristic : PreFilterHeuristic.AUTO;
    }

    /**
     * Legacy constructor for backward compatibility with single URI mode.
     */
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
        this(
            fieldName,
            new LanceStorageConfig("lance", storageUri, "_id", "vector", ossEndpoint, ossAccessKeyId, ossAccessKeySecret, 1),
            "",
            -1,
            queryVector,
            k,
            numCandidates,
            similarity,
            filter,
            dims,
            PreFilterHeuristic.AUTO
        );
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

            // Search once per Weight and share candidates across all segments.
            final List<LanceDataset.Candidate> sharedCandidates;
            final int resolvedShardId;
            if (storageConfig.isShardAware()) {
                if (shardId < 0) {
                    throw new IllegalArgumentException("shardId must be known for shard-aware Lance queries");
                }
                logger.info("Lance KNN createWeight: SHARD-AWARE mode, indexName={}, shardId={}", indexName, shardId);
                resolvedShardId = shardId;
            } else {
                logger.info("Lance KNN createWeight: LEGACY mode, indexName={}, shardId={}", indexName, shardId);
                resolvedShardId = Math.max(shardId, 0);
            }

            String resolvedUri = storageConfig.resolveUri(indexName, resolvedShardId);
            LanceDatasetConfig config = buildDatasetConfig();
            String sqlFilter = tryConvertFilterToSql(filterQuery);
            if (capturedContext != null) {
                capturedContext.putDebug("lance_trace_debug_enabled", TRACE_DEBUG_ENABLED);
                capturedContext.putDebug("lance_resolved_uri", resolvedUri);
                capturedContext.putDebug("lance_index_name", indexName);
                capturedContext.putDebug("lance_field_name", fieldName);
                capturedContext.putDebug("lance_shard_id", resolvedShardId);
                capturedContext.putDebug("lance_num_shards", storageConfig.getNumShards());
                capturedContext.putDebug("lance_k", k);
                capturedContext.putDebug("lance_num_candidates", numCandidates);
                capturedContext.putDebug("lance_nprobes", nprobes);
                capturedContext.putDebug("lance_similarity", similarity);
                capturedContext.putDebug("lance_filter_present", filterQuery != null);
                capturedContext.putDebug("lance_shard_aware", storageConfig.isShardAware());
                if (TRACE_DEBUG_ENABLED) {
                    capturedContext.putDebug("lance_sql_filter", sqlFilter != null ? sqlFilter : "");
                }
            }
            long searchStart = System.nanoTime();
            List<LanceDataset.Candidate> candidates = LanceDatasetRegistry.withSearchLock(() -> {
                LanceDataset dataset;
                try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.REGISTRY_CACHE_LOOKUP)) {
                    dataset = LanceDatasetRegistry.getOrLoad(resolvedUri, dims, config);
                }
                if (sqlFilter == null && nprobes == DEFAULT_NPROBES) {
                    return dataset.search(queryVector, numCandidates, similarity);
                }
                return dataset.search(queryVector, numCandidates, storageConfig.vectorColumn(), nprobes, sqlFilter, similarity);
            });
            if (capturedContext != null) {
                capturedContext.record(
                    LanceTimingContext.LanceTimingStage.NATIVE_SEARCH_EXECUTION,
                    (System.nanoTime() - searchStart) / 1_000_000
                );
            }

            if (storageConfig.isShardAware()) {
                candidates = filterCandidatesByShard(candidates, storageConfig.getNumShards());
            }
            if (capturedContext != null) {
                capturedContext.putDebug("lance_candidate_count", candidates.size());
            }
            sharedCandidates = candidates;

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
                    Map<Integer, Float> docScores = buildDocScores(context, sharedCandidates, filterQuery, topK, capturedContext);
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
            return new LanceDatasetConfig(storageConfig.idColumn(), storageConfig.vectorColumn(), dims, ossEp, ossKeyId, ossKeySecret);
        }
        return new LanceDatasetConfig(storageConfig.idColumn(), storageConfig.vectorColumn(), dims, null, null, null);
    }

    private static int routingHashToShardId(int hash, int numRoutingShards, int routingFactor) {
        if (numRoutingShards <= 0) {
            throw new IllegalArgumentException("numRoutingShards must be positive");
        }
        if (routingFactor <= 0) {
            throw new IllegalArgumentException("routingFactor must be positive");
        }
        return Math.floorMod(hash, numRoutingShards) / routingFactor;
    }

    /**
     * Filter candidates to only include those that belong to the current shard.
     * Uses the configured {@link org.elasticsearch.plugin.lance.mapper.LanceStorageConfig.ShardingStrategy}.
     *
     * @param candidates All candidates from Lance dataset search
     * @param numShards Total number of shards in the index
     * @return Filtered list of candidates that belong to this shard
     */
    private List<LanceDataset.Candidate> filterCandidatesByShard(List<LanceDataset.Candidate> candidates, int numShards) {
        org.elasticsearch.plugin.lance.mapper.LanceStorageConfig.ShardingStrategy strategy = storageConfig.getShardingStrategy();

        // If strategy is NONE, return all candidates without filtering
        if (strategy == org.elasticsearch.plugin.lance.mapper.LanceStorageConfig.ShardingStrategy.NONE) {
            return candidates;
        }

        // Use ES's Murmur3HashFunction to determine which shard each ID belongs to
        // This matches exactly how ES routes documents to shards
        List<LanceDataset.Candidate> filteredCandidates = new java.util.ArrayList<>(candidates.size());
        for (LanceDataset.Candidate candidate : candidates) {
            // Calculate shard ID using ES's hash function
            int candidateHash = org.elasticsearch.cluster.routing.Murmur3HashFunction.hash(candidate.id());
            int candidateShardId = routingHashToShardId(candidateHash, numShards, 1);

            // Only include candidates that belong to this shard
            // (or all candidates if we can't determine our shard ID)
            if (shardId < 0 || shardId == candidateShardId) {
                filteredCandidates.add(candidate);
            }
        }

        if (shardId >= 0 && filteredCandidates.size() != candidates.size()) {
            logger.debug(
                "Lance KNN: filtered candidates from {} to {} based on ES shard routing",
                candidates.size(),
                filteredCandidates.size()
            );
        }

        return filteredCandidates;
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
        List<LanceDataset.Candidate> sharedCandidates,
        Query filter,
        int k,
        LanceTimingContext timing
    ) throws IOException {
        long overallStart = System.nanoTime();
        var reader = context.reader();
        int maxDoc = reader.maxDoc();

        // Phase 1: Evaluate filter (if filter provided)
        java.util.BitSet filterBits = null;
        if (filter != null) {
            long filterStart = System.nanoTime();
            // Create an IndexSearcher for this leaf reader to evaluate the filter
            org.apache.lucene.search.IndexSearcher leafSearcher = new org.apache.lucene.search.IndexSearcher(reader);
            org.apache.lucene.search.Weight filterWeight = leafSearcher.createWeight(
                leafSearcher.rewrite(filter),
                org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES,
                1.0f
            );
            // Get the leaf context from the new searcher
            org.apache.lucene.index.LeafReaderContext leafContext = leafSearcher.getIndexReader().leaves().get(0);
            org.apache.lucene.search.ScorerSupplier filterSupplier = filterWeight.scorerSupplier(leafContext);
            if (filterSupplier != null) {
                org.apache.lucene.search.Scorer filterScorer = filterSupplier.get(1);
                filterBits = new java.util.BitSet(maxDoc);
                org.apache.lucene.search.DocIdSetIterator filterIter = filterScorer.iterator();
                for (int doc = filterIter.nextDoc(); doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS; doc = filterIter
                    .nextDoc()) {
                    filterBits.set(doc);
                }
            } else {
                // Filter matches nothing → return empty
                return java.util.Collections.emptyMap();
            }
            if (timing != null) {
                timing.record(LanceTimingContext.LanceTimingStage.FILTER_PROCESSING, (System.nanoTime() - filterStart) / 1_000_000);
            }
        }

        // Phase 2: Use candidates computed once in createWeight.
        List<LanceDataset.Candidate> results = sharedCandidates;

        // Phase 3: Map results to Lucene doc IDs and apply filter
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
                        // Apply filter: check if doc passes filter
                        if (filterBits != null && filterBits.get(docId) == false) {
                            continue; // Doc doesn't pass filter, skip
                        }
                        docScores.put(docId, candidate.score());
                    }
                }
            }
        }
        if (timing != null) {
            timing.record(LanceTimingContext.LanceTimingStage.SCORE_AGGREGATION, (System.nanoTime() - joinStart) / 1_000_000);
            timing.putDebug("lance_result_count", docScores.size());
            if (TRACE_DEBUG_ENABLED) {
                timing.putDebug("lance_filter_strategy", filter != null ? "post_filter" : "none");
            }
        }

        logger.debug("Lance kNN search complete: candidates={}, results={}, filter={}", results.size(), docScores.size(), filter != null);

        // Keep only top k by score
        if (docScores.size() > k) {
            Map<Integer, Float> limited = docScores.entrySet()
                .stream()
                .sorted(Map.Entry.<Integer, Float>comparingByValue().reversed())
                .limit(k)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            recordMetrics(overallStart, filter != null);
            return limited;
        }

        recordMetrics(overallStart, filter != null);

        return docScores;
    }

    private static void recordMetrics(long overallStartNanos, boolean hadFilter) {
        long duration = System.nanoTime() - overallStartNanos;
        LanceSearchMetrics.recordSearch(duration);
        if (hadFilter) {
            LanceSearchMetrics.recordFilteredSearch();
            LanceSearchMetrics.recordPostFilterSearch();
        }
    }

    /**
     * Try to convert ES filter to Lance SQL for native prefiltering.
     * Returns null if conversion fails (triggers fallback to ES post-filter).
     */
    private String tryConvertFilterToSql(Query filter) {
        if (filter == null) {
            return null;
        }

        try {
            Map<String, String> fieldMapping = storageConfig.getFieldMapping();
            if (fieldMapping == null || fieldMapping.isEmpty()) {
                logger.debug("No field_mapping configured, skipping filter pushdown");
                return null;
            }

            EsToLanceFilterConverter converter = new EsToLanceFilterConverter();
            String sqlFilter = converter.convert(filter, fieldMapping);
            logger.info("Lance filter pushdown: {}", sqlFilter);
            return sqlFilter;
        } catch (LanceFilterConversionException e) {
            // Fallback to ES post-filter (current behavior)
            logger.warn("Lance filter pushdown failed: {}, falling back to ES post-filter", e.getMessage());
            return null;
        }
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
            && nprobes == other.nprobes
            && fieldName.equals(other.fieldName)
            && storageConfigEquals(other)
            && similarity.equals(other.similarity);
    }

    private boolean storageConfigEquals(LanceKnnQuery other) {
        // For legacy mode, compare URIs; for shard-aware mode, compare indexName and shardId
        if (storageConfig.isShardAware() || other.storageConfig.isShardAware()) {
            // At least one is shard-aware - compare indexName and shardId
            return indexName.equals(other.indexName) && shardId == other.shardId;
        }
        // Both are legacy mode - compare URIs
        return storageConfig.uri().equals(other.storageConfig.uri());
    }

    @Override
    public int hashCode() {
        if (storageConfig.isShardAware()) {
            return Objects.hash(fieldName, indexName, shardId, k, numCandidates, nprobes, similarity);
        }
        return Objects.hash(fieldName, storageConfig.uri(), k, numCandidates, nprobes, similarity);
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
            logger.debug("Lance kNN timing breakdown: {}", timing);
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
            org.elasticsearch.search.profile.query.QueryProfileBreakdown breakdown = queryProfiler.getQueryBreakdown(this);
            if (breakdown != null) {
                breakdown.putAllDebugData(timing);
                logger.debug("Lance kNN profile timing: {}", timing);
            }
        }
    }
}
