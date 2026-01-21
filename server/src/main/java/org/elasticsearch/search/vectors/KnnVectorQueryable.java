/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.FilterHeuristic;
import org.elasticsearch.search.vectors.VectorData;

/**
 * Abstraction that allows field types other than DenseVector to participate in the
 * {@link KnnVectorQueryBuilder} query pipeline. Implementations are expected to
 * produce a Lucene {@link Query} that honors the semantics of the knn request.
 *
 * Phase 1 of the Lance integration implements this interface in the lance_vector
 * field type so we can reuse the standard knn REST API while delegating execution
 * to the Lance backend.
 */
public interface KnnVectorQueryable {
    Query createKnnQuery(
        VectorData queryVector,
        int k,
        int numCands,
        Float visitPercentage,
        Float oversample,
        Query filter,
        Float similarityThreshold,
        BitSetProducer parentFilter,
        FilterHeuristic heuristic,
        boolean hnswEarlyTermination
    );

    /**
     * @return the underlying mapped field type for this vector field
     */
    MappedFieldType getMappedFieldType();
}
