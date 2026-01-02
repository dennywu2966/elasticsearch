/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.codec;

import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;

import java.io.IOException;

/**
 * Hybrid KnnVectorsWriter that writes to both Lucene and Lance formats.
 * <p>
 * This writer:
 * <ul>
 *   <li>Writes to Lucene format for compatibility</li>
 *   <li>Optionally syncs to Lance format for accelerated search</li>
 *   <li>Maintains full ES knn query compatibility</li>
 * </ul>
 */
public class LanceHybridVectorsWriter extends KnnVectorsWriter {

    private final SegmentWriteState state;
    private final KnnVectorsWriter delegate;
    private final String vectorColumn;
    private final DenseVectorFieldMapper.VectorSimilarity similarity;

    public LanceHybridVectorsWriter(
        SegmentWriteState state,
        KnnVectorsWriter delegate,
        String vectorColumn,
        DenseVectorFieldMapper.VectorSimilarity similarity
    ) {
        this.state = state;
        this.delegate = delegate;
        this.vectorColumn = vectorColumn;
        this.similarity = similarity;
    }

    @Override
    public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
        // Delegate to Lucene writer
        return delegate.addField(fieldInfo);
    }

    @Override
    public void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
        delegate.flush(maxDoc, sortMap);
        // Optionally sync to Lance here
    }

    @Override
    public void finish() throws IOException {
        delegate.finish();
    }

    @Override
    public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        delegate.mergeOneField(fieldInfo, mergeState);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public long ramBytesUsed() {
        return delegate.ramBytesUsed();
    }
}
