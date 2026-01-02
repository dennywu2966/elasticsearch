/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.mapper;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.elasticsearch.xpack.lance.index.LanceDataset;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Lucene Query implementation for Lance vector similarity search.
 * <p>
 * This query delegates to Lance's native vector search implementation,
 * which supports IVF-PQ, IVF-HNSW, and other optimized search algorithms.
 */
public class LanceVectorQuery extends Query {

    private final String field;
    private final float[] queryVector;
    private final int k;
    private final String lancePath;
    private final String vectorColumn;
    private final LanceVectorFieldMapper.LanceSimilarity similarity;
    private final LanceVectorFieldMapper.LanceIndexType indexType;
    private final int nprobes;
    private final int refineFactor;
    private final Query preFilter;

    public LanceVectorQuery(
        String field,
        float[] queryVector,
        int k,
        String lancePath,
        String vectorColumn,
        LanceVectorFieldMapper.LanceSimilarity similarity,
        LanceVectorFieldMapper.LanceIndexType indexType,
        int nprobes,
        int refineFactor,
        Query preFilter
    ) {
        this.field = Objects.requireNonNull(field, "field cannot be null");
        this.queryVector = Objects.requireNonNull(queryVector, "queryVector cannot be null");
        this.k = k;
        this.lancePath = lancePath;
        this.vectorColumn = vectorColumn;
        this.similarity = similarity;
        this.indexType = indexType;
        this.nprobes = nprobes;
        this.refineFactor = refineFactor;
        this.preFilter = preFilter;
    }

    public String getField() {
        return field;
    }

    public float[] getQueryVector() {
        return queryVector;
    }

    public int getK() {
        return k;
    }

    public String getLancePath() {
        return lancePath;
    }

    public String getVectorColumn() {
        return vectorColumn;
    }

    public LanceVectorFieldMapper.LanceSimilarity getSimilarity() {
        return similarity;
    }

    public int getNprobes() {
        return nprobes;
    }

    public int getRefineFactor() {
        return refineFactor;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return new LanceVectorWeight(this, boost);
    }

    @Override
    public void visit(QueryVisitor visitor) {
        if (visitor.acceptField(field)) {
            visitor.visitLeaf(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LanceVectorQuery that = (LanceVectorQuery) o;
        return k == that.k
            && nprobes == that.nprobes
            && refineFactor == that.refineFactor
            && Objects.equals(field, that.field)
            && Arrays.equals(queryVector, that.queryVector)
            && Objects.equals(lancePath, that.lancePath)
            && Objects.equals(vectorColumn, that.vectorColumn)
            && similarity == that.similarity
            && indexType == that.indexType
            && Objects.equals(preFilter, that.preFilter);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(field, k, lancePath, vectorColumn, similarity, indexType, nprobes, refineFactor, preFilter);
        result = 31 * result + Arrays.hashCode(queryVector);
        return result;
    }

    @Override
    public String toString(String field) {
        StringBuilder sb = new StringBuilder();
        sb.append("LanceVectorQuery(");
        sb.append("field=").append(this.field);
        sb.append(", k=").append(k);
        sb.append(", dims=").append(queryVector.length);
        sb.append(", similarity=").append(similarity);
        sb.append(", indexType=").append(indexType);
        sb.append(", nprobes=").append(nprobes);
        if (preFilter != null) {
            sb.append(", filter=").append(preFilter);
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Weight implementation for Lance vector queries.
     */
    private static class LanceVectorWeight extends Weight {

        private final LanceVectorQuery query;
        private final float boost;

        LanceVectorWeight(LanceVectorQuery query, float boost) {
            super(query);
            this.query = query;
            this.boost = boost;
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Scorer scorer = scorer(context);
            if (scorer != null) {
                DocIdSetIterator iterator = scorer.iterator();
                if (iterator.advance(doc) == doc) {
                    float score = scorer.score();
                    return Explanation.match(
                        score,
                        "Lance vector similarity search",
                        Explanation.match(score, "similarity score based on " + query.similarity)
                    );
                }
            }
            return Explanation.noMatch("Document does not match");
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            // Execute Lance search and create scorer from results
            LanceSearchResult result = executeLanceSearch(context);
            if (result == null || result.isEmpty()) {
                return null;
            }
            return new LanceVectorScorer(this, result);
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
            return false; // Lance queries are not cacheable as they depend on external data
        }

        private LanceSearchResult executeLanceSearch(LeafReaderContext context) throws IOException {
            // If no lance path is configured, return empty result
            if (query.lancePath == null || query.lancePath.isEmpty()) {
                return new LanceSearchResult(new int[0], new float[0]);
            }

            try {
                LanceDataset dataset = LanceDataset.open(query.lancePath);
                return dataset.search(
                    query.vectorColumn,
                    query.queryVector,
                    query.k,
                    query.nprobes,
                    query.refineFactor,
                    query.similarity,
                    context.reader().getLiveDocs()
                );
            } catch (Exception e) {
                throw new IOException("Failed to execute Lance search", e);
            }
        }
    }

    /**
     * Scorer for Lance vector search results.
     */
    private static class LanceVectorScorer extends Scorer {

        private final LanceSearchResult result;
        private int currentIndex = -1;

        LanceVectorScorer(Weight weight, LanceSearchResult result) {
            super(weight);
            this.result = result;
        }

        @Override
        public DocIdSetIterator iterator() {
            return new DocIdSetIterator() {
                @Override
                public int docID() {
                    if (currentIndex < 0 || currentIndex >= result.docIds.length) {
                        return NO_MORE_DOCS;
                    }
                    return result.docIds[currentIndex];
                }

                @Override
                public int nextDoc() {
                    currentIndex++;
                    return docID();
                }

                @Override
                public int advance(int target) {
                    while (nextDoc() < target) {
                        // advance
                    }
                    return docID();
                }

                @Override
                public long cost() {
                    return result.docIds.length;
                }
            };
        }

        @Override
        public float getMaxScore(int upTo) {
            float maxScore = 0f;
            for (int i = 0; i < result.scores.length && result.docIds[i] <= upTo; i++) {
                maxScore = Math.max(maxScore, result.scores[i]);
            }
            return maxScore;
        }

        @Override
        public float score() {
            if (currentIndex < 0 || currentIndex >= result.scores.length) {
                return 0f;
            }
            return result.scores[currentIndex];
        }

        @Override
        public int docID() {
            if (currentIndex < 0 || currentIndex >= result.docIds.length) {
                return DocIdSetIterator.NO_MORE_DOCS;
            }
            return result.docIds[currentIndex];
        }
    }

    /**
     * Holds results from a Lance vector search.
     */
    public static class LanceSearchResult {
        public final int[] docIds;
        public final float[] scores;

        public LanceSearchResult(int[] docIds, float[] scores) {
            this.docIds = docIds;
            this.scores = scores;
        }

        public boolean isEmpty() {
            return docIds == null || docIds.length == 0;
        }
    }
}
