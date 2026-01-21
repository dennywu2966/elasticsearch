/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import com.lancedb.lance.Dataset;
import com.lancedb.lance.ReadOptions;
import com.lancedb.lance.index.DistanceType;
import com.lancedb.lance.ipc.LanceScanner;
import com.lancedb.lance.ipc.Query;
import com.lancedb.lance.ipc.ScanOptions;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Production Lance dataset implementation using native JNI bindings.
 * <p>
 * This class integrates with lance-java (LanceDB's Java SDK) which uses JNI
 * to call the native Rust core for maximum performance.
 * <p>
 * <b>Performance characteristics:</b>
 * <ul>
 *   <li>IVF-PQ indexing: O(nprobes * centroids) instead of O(n) brute-force</li>
 *   <li>Memory-mapped access: Zero-copy reads from disk</li>
 *   <li>Native SIMD: Rust core uses vectorized distance calculations</li>
 *   <li>Batch support: Process multiple queries efficiently</li>
 * </ul>
 *
 * @see <a href="https://github.com/lancedb/lance">LanceDB on GitHub</a>
 */
public class RealLanceDataset implements LanceDataset {
    private static final Logger logger = LogManager.getLogger(RealLanceDataset.class);

    // Shared allocator for Arrow memory - lance-java requires this
    // Lazy initialization to avoid class loading errors when native libs aren't available
    private static volatile BufferAllocator allocator;

    private static BufferAllocator getAllocator() {
        if (allocator == null) {
            synchronized (RealLanceDataset.class) {
                if (allocator == null) {
                    allocator = new RootAllocator(Long.MAX_VALUE);
                }
            }
        }
        return allocator;
    }

    private final Dataset dataset;
    private final String uri;
    private final int dims;
    private final long vectorCount;
    private final boolean indexed;
    private final String idColumn;
    private final String vectorColumn;
    private final int nprobes;

    private RealLanceDataset(
        Dataset dataset,
        String uri,
        int dims,
        long vectorCount,
        boolean indexed,
        String idColumn,
        String vectorColumn,
        int nprobes
    ) {
        this.dataset = dataset;
        this.uri = uri;
        this.dims = dims;
        this.vectorCount = vectorCount;
        this.indexed = indexed;
        this.idColumn = idColumn;
        this.vectorColumn = vectorColumn;
        this.nprobes = nprobes;
    }

    /**
     * Open a Lance dataset from URI.
     * <p>
     * Supported URI schemes:
     * <ul>
     *   <li>file:// - Local filesystem</li>
     *   <li>oss:// - Alibaba Cloud OSS (requires credentials)</li>
     *   <li>s3:// - Amazon S3 (requires credentials)</li>
     * </ul>
     *
     * @param uri Dataset URI (e.g., "file:///path/to/data.lance" or "/path/to/data.lance")
     * @param config Configuration
     * @return Opened dataset
     * @throws IOException if dataset cannot be opened
     */
    public static RealLanceDataset open(String uri, LanceDatasetConfig config) throws IOException {
        logger.info("Opening Lance dataset: uri={}, expectedDims={}", uri, config.expectedDims());

        try {
            // Normalize URI - lance-java expects filesystem paths without file:// prefix
            String datasetPath = normalizeUri(uri);

            // Open dataset with lance-java SDK
            ReadOptions readOptions = new ReadOptions.Builder().build();
            Dataset dataset = Dataset.open(getAllocator(), datasetPath, readOptions);

            // Extract schema and find vector column dimensions
            Schema schema = dataset.getSchema();
            int dims = getVectorDimensions(schema, config.vectorColumn());

            // Get row count
            long vectorCount = dataset.countRows();

            // Check if dataset has vector index on the specified column
            boolean hasIndex = checkHasIndex(dataset, config.vectorColumn());

            // Validate dimensions if expected
            if (config.expectedDims() > 0 && dims != config.expectedDims()) {
                dataset.close();
                throw new IllegalArgumentException("Dimension mismatch: expected " + config.expectedDims() + ", got " + dims);
            }

            logger.info("Opened Lance dataset: uri={}, vectors={}, dims={}, indexed={}", uri, vectorCount, dims, hasIndex);

            // Default nprobes for IVF search (higher = more accurate but slower)
            int nprobes = 20;

            return new RealLanceDataset(dataset, uri, dims, vectorCount, hasIndex, config.idColumn(), config.vectorColumn(), nprobes);
        } catch (Exception e) {
            throw new IOException("Failed to open Lance dataset: " + uri, e);
        }
    }

    /**
     * Normalize URI to filesystem path that lance-java expects.
     */
    private static String normalizeUri(String uri) {
        if (uri.startsWith("file://")) {
            return uri.substring(7);
        }
        // For oss:// and s3://, lance-java should handle them directly
        // but we may need to configure storage options
        return uri;
    }

    /**
     * Extract vector dimensions from Arrow schema.
     */
    private static int getVectorDimensions(Schema schema, String vectorColumn) {
        for (Field field : schema.getFields()) {
            if (field.getName().equals(vectorColumn)) {
                // For fixed-size list types, get the list size
                if (field.getType().getTypeID() == ArrowType.ArrowTypeID.FixedSizeList) {
                    ArrowType.FixedSizeList listType = (ArrowType.FixedSizeList) field.getType();
                    return listType.getListSize();
                }
                // For list types, we need to infer from data
                return -1;
            }
        }
        throw new IllegalArgumentException("Vector column not found: " + vectorColumn);
    }

    /**
     * Check if dataset has a vector index on the specified column.
     */
    private static boolean checkHasIndex(Dataset dataset, String vectorColumn) {
        try {
            // listIndexes() returns List<String> containing index names
            List<String> indexNames = dataset.listIndexes();
            // Check if any index name contains the vector column name
            // (index names typically include the column name)
            for (String indexName : indexNames) {
                if (indexName.contains(vectorColumn) || indexName.contains("vector")) {
                    return true;
                }
            }
            // If there are any indexes at all, assume the dataset is indexed
            return indexNames.isEmpty() == false;
        } catch (Exception e) {
            logger.debug("Could not check index status: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public long size() {
        return vectorCount;
    }

    @Override
    public boolean hasIndex() {
        return indexed;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public List<Candidate> search(float[] query, int numCandidates, String similarity) {
        if (query.length != dims) {
            throw new IllegalArgumentException("Query vector dims mismatch: expected " + dims + ", got " + query.length);
        }

        try {
            // Use native vector search via the Query API
            return vectorSearch(query, numCandidates, similarity);
        } catch (Exception e) {
            logger.error("Lance search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Lance search failed", e);
        }
    }

    /**
     * Perform vector search using lance-java's native vector search API.
     * This leverages the native Rust implementation for fast approximate nearest neighbor search.
     */
    private List<Candidate> vectorSearch(float[] queryVector, int numCandidates, String similarity) throws Exception {
        // Build the vector search query
        Query.Builder queryBuilder = new Query.Builder().setColumn(vectorColumn)
            .setKey(queryVector)
            .setK(numCandidates)
            .setDistanceType(toDistanceType(similarity))
            .setUseIndex(indexed);

        if (indexed) {
            // Set IVF search parameters for indexed search
            queryBuilder.setNprobes(nprobes);
        }

        Query query = queryBuilder.build();

        // Build scan options with the vector search query
        ScanOptions scanOptions = new ScanOptions.Builder().columns(List.of(idColumn)).nearest(query).limit(numCandidates).build();

        // Execute the search
        List<Candidate> candidates = new ArrayList<>();

        try (LanceScanner scanner = dataset.newScan(scanOptions); ArrowReader reader = scanner.scanBatches()) {

            while (reader.loadNextBatch()) {
                VectorSchemaRoot batch = reader.getVectorSchemaRoot();
                extractCandidates(batch, similarity, candidates);
            }
        }

        // Sort by score descending (higher is better)
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return candidates.subList(0, Math.min(numCandidates, candidates.size()));
    }

    /**
     * Extract candidates from an Arrow batch result.
     */
    private void extractCandidates(VectorSchemaRoot batch, String similarity, List<Candidate> candidates) {
        // The ID column
        VarCharVector idVector = (VarCharVector) batch.getVector(idColumn);

        // Lance returns distance in a "_distance" column when using nearest search
        Float4Vector distVector = (Float4Vector) batch.getVector("_distance");

        for (int i = 0; i < batch.getRowCount(); i++) {
            if (idVector.isNull(i)) continue;

            String id = new String(idVector.get(i), StandardCharsets.UTF_8);
            float distance = distVector != null && distVector.isNull(i) == false ? distVector.get(i) : 0f;
            float score = distanceToScore(distance, similarity);
            candidates.add(new Candidate(id, score));
        }
    }

    /**
     * Convert similarity string to Lance DistanceType.
     */
    private DistanceType toDistanceType(String similarity) {
        return switch (similarity.toLowerCase(Locale.ROOT)) {
            case "cosine" -> DistanceType.Cosine;
            case "dot_product", "dot" -> DistanceType.Dot;
            case "l2", "euclidean" -> DistanceType.L2;
            default -> DistanceType.Cosine;
        };
    }

    /**
     * Convert distance to similarity score.
     * Lance returns distance metrics, we need similarity scores (higher = better).
     */
    private float distanceToScore(float distance, String similarity) {
        return switch (similarity.toLowerCase(Locale.ROOT)) {
            case "cosine" -> 1.0f - distance;  // cosine distance to similarity
            case "dot_product", "dot" -> -distance;  // dot product distance (negated)
            case "l2", "euclidean" -> 1.0f / (1.0f + distance);  // inverse of L2
            default -> 1.0f - distance;
        };
    }

    @Override
    public void close() throws IOException {
        logger.debug("Closing Lance dataset: {}", uri);
        try {
            if (dataset != null) {
                dataset.close();
            }
        } catch (Exception e) {
            throw new IOException("Failed to close Lance dataset", e);
        }
    }

    /**
     * Get the configured nprobes value for IVF search.
     * Higher values = more accurate but slower.
     */
    public int getNprobes() {
        return nprobes;
    }

    /**
     * Get the ID column name.
     */
    public String getIdColumn() {
        return idColumn;
    }

    /**
     * Get the vector column name.
     */
    public String getVectorColumn() {
        return vectorColumn;
    }

    /**
     * Get the underlying Lance dataset for advanced operations.
     * Use with caution - this exposes internal state.
     */
    Dataset getNativeDataset() {
        return dataset;
    }
}
