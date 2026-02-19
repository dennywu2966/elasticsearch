/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.apache.arrow.vector.VarCharVector;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Core interface for Lance vector datasets.
 * <p>
 * This interface abstracts the Lance storage layer to support:
 * <ul>
 *   <li>FakeLanceDataset: In-memory implementation for testing</li>
 *   <li>RealLanceDataset: Production implementation using Lance SDK (when available)</li>
 * </ul>
 * <p>
 * The interface is designed to be extensible - additional methods for sharding,
 * metrics, health checks, etc. can be added in future implementations.
 */
public interface LanceDataset extends Closeable {

    /**
     * A search result candidate with document ID and similarity score.
     */
    record Candidate(String id, float score) {}

    /**
     * Get the vector dimensionality of this dataset.
     */
    int dims();

    /**
     * Get the number of vectors in this dataset.
     * Returns -1 if size is unknown.
     */
    default long size() {
        return -1;
    }

    /**
     * Check if the dataset has an index (e.g., IVF-PQ).
     * Non-indexed datasets use brute-force search.
     */
    default boolean hasIndex() {
        return false;
    }

    /**
     * Perform approximate nearest neighbor search.
     *
     * @param query Query vector (must match dims())
     * @param numCandidates Number of candidates to return
     * @param similarity Similarity metric: "cosine", "dot_product", or "l2"
     * @return List of candidates sorted by score (highest first)
     */
    List<Candidate> search(float[] query, int numCandidates, String similarity);

    /**
     * Search with pre-filter: restrict search to documents whose _ids are in the filter vector.
     * <p>
     * The idFilter is an Arrow VarCharVector containing UTF-8 encoded _id strings.
     * Implementations should search only within documents matching these IDs.
     * The caller owns the idFilter and is responsible for closing it.
     *
     * @param queryVector The query vector
     * @param k Number of nearest neighbors
     * @param columnName The vector column name (ignored by fake implementation)
     * @param idFilter Arrow VarCharVector of _id values to restrict search to (nullable — null means no filter)
     * @return Search results restricted to filtered IDs
     * @throws IOException if search fails
     */
    List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) throws IOException;

    /**
     * Search with configurable nprobes parameter.
     * <p>
     * nprobes controls the number of partitions to search in IVF indexes.
     * Higher values improve recall at the cost of latency.
     * Typical range: 1-100, default: 20.
     *
     * @param queryVector The query vector
     * @param k Number of nearest neighbors
     * @param columnName The vector column name
     * @param nprobes Number of IVF partitions to probe
     * @return Search results
     * @throws IOException if search fails
     */
    List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) throws IOException;


    /**
     * Search with SQL filter pushdown.
     * <p>
     * This method allows Lance to apply filters BEFORE vector search using native SQL,
     * significantly reducing the search space for selective filters.
     * <p>
     * The sqlFilter parameter is a SQL WHERE clause (without the "WHERE" keyword).
     * Example: "product_category = 'electronics' AND price &lt; 100"
     *
     * @param queryVector The query vector
     * @param k Number of nearest neighbors
     * @param columnName The vector column name
     * @param sqlFilter SQL WHERE clause for pre-filtering (nullable - null means no filter)
     * @return Search results restricted to rows matching the SQL filter
     * @throws IOException if search fails
     */
    List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) throws IOException;


    /**
     * Search with both configurable nprobes and SQL filter pushdown.
     * <p>
     * Default behavior preserves backward compatibility:
     * <ul>
     *   <li>If {@code sqlFilter} is provided, delegate to {@link #search(float[], int, String, String)}</li>
     *   <li>Otherwise delegate to {@link #search(float[], int, String, int)}</li>
     * </ul>
     */
    default List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes, String sqlFilter) throws IOException {
        if (sqlFilter != null && sqlFilter.isEmpty() == false) {
            return search(queryVector, k, columnName, sqlFilter);
        }
        return search(queryVector, k, columnName, nprobes);
    }

    /**
     * Search with configurable nprobes, optional SQL filter, and explicit similarity.
     * <p>
     * Default implementation preserves backward compatibility by delegating to
     * the existing nprobes/sqlFilter search method and ignoring similarity.
     */
    default List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes, String sqlFilter, String similarity)
        throws IOException {
        return search(queryVector, k, columnName, nprobes, sqlFilter);
    }

    /**
     * Get the URI this dataset was loaded from.
     */
    String uri();

    /**
     * Close this dataset and release resources.
     */
    @Override
    default void close() throws IOException {
        // Default no-op for simple implementations
    }
}
