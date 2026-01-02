/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.codec;

import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.Bits;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.xpack.lance.index.LanceDataset;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * KnnVectorsReader implementation that reads vectors from Lance format.
 * <p>
 * This reader provides:
 * <ul>
 *   <li>Direct access to Lance dataset for vector retrieval</li>
 *   <li>Optimized IVF-PQ search with configurable nprobes</li>
 *   <li>Efficient memory usage through Lance's columnar format</li>
 * </ul>
 */
public class LanceVectorsReader extends KnnVectorsReader {

    private final SegmentReadState state;
    private final String lancePath;
    private final String vectorColumn;
    private final DenseVectorFieldMapper.VectorSimilarity similarity;
    private final int nprobes;
    private final int refineFactor;

    private LanceDataset dataset;
    private final Map<String, LanceFloatVectorValues> floatVectorValuesCache = new HashMap<>();

    public LanceVectorsReader(
        SegmentReadState state,
        String lancePath,
        String vectorColumn,
        DenseVectorFieldMapper.VectorSimilarity similarity,
        int nprobes,
        int refineFactor
    ) throws IOException {
        this.state = state;
        this.lancePath = lancePath;
        this.vectorColumn = vectorColumn;
        this.similarity = similarity;
        this.nprobes = nprobes;
        this.refineFactor = refineFactor;

        // Open Lance dataset
        if (lancePath != null && !lancePath.isEmpty()) {
            this.dataset = LanceDataset.open(lancePath);
        }
    }

    @Override
    public void checkIntegrity() throws IOException {
        // Lance handles its own integrity checks
    }

    @Override
    public FloatVectorValues getFloatVectorValues(String field) throws IOException {
        return floatVectorValuesCache.computeIfAbsent(field, f -> {
            try {
                return new LanceFloatVectorValues(dataset, vectorColumn, state.segmentInfo.maxDoc());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public ByteVectorValues getByteVectorValues(String field) throws IOException {
        // Lance primarily works with float vectors
        // Byte vectors would need conversion
        return null;
    }

    @Override
    public void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
        if (dataset == null) {
            return;
        }

        int k = knnCollector.k();

        // Execute Lance search
        LanceDataset.LanceSearchResult result = dataset.search(
            vectorColumn,
            target,
            k,
            nprobes,
            refineFactor,
            convertSimilarity(similarity),
            acceptDocs
        );

        // Collect results
        for (int i = 0; i < result.docIds.length; i++) {
            int docId = result.docIds[i];
            float score = result.scores[i];

            // Check if document is accepted
            if (acceptDocs == null || acceptDocs.get(docId)) {
                knnCollector.collect(docId, score);
            }
        }
    }

    @Override
    public void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
        // Convert byte vector to float and search
        float[] floatTarget = new float[target.length];
        for (int i = 0; i < target.length; i++) {
            floatTarget[i] = target[i];
        }
        search(field, floatTarget, knnCollector, acceptDocs);
    }

    private LanceDataset.LanceSimilarity convertSimilarity(DenseVectorFieldMapper.VectorSimilarity similarity) {
        if (similarity == null) {
            return LanceDataset.LanceSimilarity.L2;
        }
        return switch (similarity) {
            case COSINE -> LanceDataset.LanceSimilarity.COSINE;
            case DOT_PRODUCT -> LanceDataset.LanceSimilarity.DOT;
            case L2_NORM, MAX_INNER_PRODUCT -> LanceDataset.LanceSimilarity.L2;
        };
    }

    @Override
    public void close() throws IOException {
        floatVectorValuesCache.clear();
        if (dataset != null) {
            dataset.close();
            dataset = null;
        }
    }

    @Override
    public long ramBytesUsed() {
        // Estimate RAM usage - Lance uses memory-mapped files
        return 1024L * 1024L; // Placeholder
    }

    /**
     * FloatVectorValues implementation backed by Lance.
     */
    private static class LanceFloatVectorValues extends FloatVectorValues {

        private final LanceDataset dataset;
        private final String vectorColumn;
        private final int maxDoc;
        private int currentDoc = -1;
        private float[] currentVector;

        LanceFloatVectorValues(LanceDataset dataset, String vectorColumn, int maxDoc) throws IOException {
            this.dataset = dataset;
            this.vectorColumn = vectorColumn;
            this.maxDoc = maxDoc;
        }

        @Override
        public int dimension() {
            return dataset != null ? dataset.getVectorDimension(vectorColumn) : 0;
        }

        @Override
        public int size() {
            return maxDoc;
        }

        @Override
        public float[] vectorValue() throws IOException {
            if (currentVector == null && dataset != null) {
                float[][] vectors = dataset.readVectors(vectorColumn, currentDoc, 1);
                if (vectors != null && vectors.length > 0) {
                    currentVector = vectors[0];
                }
            }
            return currentVector;
        }

        @Override
        public int docID() {
            return currentDoc;
        }

        @Override
        public int nextDoc() throws IOException {
            currentDoc++;
            currentVector = null;
            return currentDoc < maxDoc ? currentDoc : NO_MORE_DOCS;
        }

        @Override
        public int advance(int target) throws IOException {
            currentDoc = target;
            currentVector = null;
            return currentDoc < maxDoc ? currentDoc : NO_MORE_DOCS;
        }

        @Override
        public FloatVectorValues copy() throws IOException {
            return new LanceFloatVectorValues(dataset, vectorColumn, maxDoc);
        }
    }
}
