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
import org.apache.lucene.util.SuppressForbidden;
import org.elasticsearch.plugin.lance.profile.LanceTimer;
import org.elasticsearch.plugin.lance.profile.LanceTimingContext;

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
    // Memory limit set to prevent OOM - 256MB should be enough for search operations
    private static final long ALLOCATOR_LIMIT = 256 * 1024 * 1024; // 256MB
    private static volatile BufferAllocator allocator;

    private static BufferAllocator getAllocator() {
        if (allocator == null) {
            synchronized (RealLanceDataset.class) {
                if (allocator == null) {
                    allocator = new RootAllocator(ALLOCATOR_LIMIT);
                }
            }
        }
        return allocator;
    }

    /**
     * Get a child allocator for a specific operation.
     * This helps isolate memory usage and enables proper cleanup.
     */
    private static BufferAllocator getChildAllocator(String name) {
        return getAllocator().newChildAllocator(name, 0, ALLOCATOR_LIMIT / 4);
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
            String datasetPath;
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.URI_SETUP)) {
                datasetPath = normalizeUri(uri);

                // For OSS URIs, set environment variables for lance-java SDK
                if (uri.startsWith("oss://") && config.isOssConfigured()) {
                    logger.info("Configuring OSS environment variables: endpoint={}", config.ossEndpoint());

                    // Check if environment variables are already set (proper way)
                    boolean envAlreadySet = System.getenv("OSS_ENDPOINT") != null
                        && System.getenv("OSS_ACCESS_KEY_ID") != null
                        && System.getenv("OSS_ACCESS_KEY_SECRET") != null;

                    if (envAlreadySet) {
                        logger.info(
                            "OSS environment variables already set (recommended approach). "
                                + " Lance will use pre-configured credentials from process environment."
                        );
                    } else {
                        logger.warn(
                            "OSS environment variables not set in process environment. "
                                + "Attempting fallback via reflection (may not work reliably). "
                                + "Recommended: Set OSS_ENDPOINT, OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET "
                                + "in the parent shell before starting ES. "
                                + "See plugins/lance-vector/ENVIRONMENT_VARIABLE_SETUP.md for details."
                        );
                        // lance-java SDK reads OSS configuration from environment variables
                        // These are consumed by the underlying lance-rust object store implementation
                        setEnvIfChanged("OSS_ENDPOINT", config.ossEndpoint());
                        setEnvIfChanged("OSS_ACCESS_KEY_ID", config.ossAccessKeyId());
                        setEnvIfChanged("OSS_ACCESS_KEY_SECRET", config.ossAccessKeySecret());
                    }
                }
            }

            Dataset dataset;
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.NATIVE_DATASET_OPEN)) {
                ReadOptions readOptions = new ReadOptions.Builder().build();
                dataset = Dataset.open(getAllocator(), datasetPath, readOptions);
            }

            // Extract schema and find vector column dimensions
            Schema schema;
            int dims;
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.SCHEMA_PARSING)) {
                schema = dataset.getSchema();
                dims = getVectorDimensions(schema, config.vectorColumn());
            }

            // Get row count
            long vectorCount = dataset.countRows();

            // Check if dataset has vector index on the specified column
            boolean hasIndex;
            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.INDEX_DETECTION)) {
                hasIndex = checkHasIndex(dataset, config.vectorColumn());
            }

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
     * For file:// URIs, ensure we return an absolute path.
     */
    private static String normalizeUri(String uri) {
        if (uri.startsWith("file://")) {
            String path = uri.substring(7);
            // Ensure path starts with / for absolute paths
            if (path.startsWith("/") == false) {
                path = "/" + path;
            }
            return path;
        }
        // For oss:// and s3://, lance-java should handle them directly
        // but we may need to configure storage options
        return uri;
    }

    /**
     * Set environment variable if the value has changed.
     * <p>
     * <b>WARNING:</b> This method uses reflection to modify the Java environment map.
     * This is a <b>high-risk operation</b> with the following limitations:
     * <ul>
     *   <li>Not thread-safe: concurrent modifications may cause race conditions</li>
     *   <li>JVM-version dependent: relies on internal implementation details</li>
     *   <li>May not work with SecurityManager enabled</li>
     *   <li>Changes may not be visible to native Lance code</li>
     * </ul>
     * <p>
     * <b>RECOMMENDED APPROACH:</b> Set environment variables in the parent shell
     * before starting Elasticsearch. See {@code ENVIRONMENT_VARIABLE_SETUP.md}
     * for detailed instructions.
     * <p>
     * This method is kept as a fallback for development/testing only.
     */
    @SuppressForbidden(
        reason = "Need to set OSS environment variables for Lance native library. "
            + "Lance Rust SDK reads these from process environment (getenv()), not Java System.getenv(). "
            + "This is a fallback mechanism; production deployments should set environment variables "
            + "in the parent shell before starting ES. See ENVIRONMENT_VARIABLE_SETUP.md for details."
    )
    private static void setEnvIfChanged(String name, String value) {
        try {
            String current = System.getenv(name);
            if (current == null || current.equals(value) == false) {
                // Use reflection to modify environment variables since System.getenv() is immutable
                // NOTE: This modifies the Java-side environment map only; native getenv() may not see these changes
                var env = System.getenv();
                var field = env.getClass().getDeclaredField("m");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var writableEnv = (java.util.Map<String, String>) field.get(env);
                writableEnv.put(name, value);
                logger.warn(
                    "Environment variable {} was set via reflection (fallback mechanism). "
                        + "This may not work reliably. Recommended: Set {} in the parent shell before starting ES. "
                        + "See plugins/lance-vector/ENVIRONMENT_VARIABLE_SETUP.md for details.",
                    name,
                    name
                );
                logger.debug("Environment variable {} set via reflection to: {}", name, value != null ? "***" : null);
            }
        } catch (Exception e) {
            logger.warn(
                "Failed to set environment variable {} via reflection: {}. "
                    + "This may cause OSS authentication failures. "
                    + "Set environment variables in the parent shell before starting ES. "
                    + "See plugins/lance-vector/ENVIRONMENT_VARIABLE_SETUP.md for details.",
                name,
                e.getMessage()
            );
        }
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

        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.VECTOR_SEARCH_SETUP)) {
            // Use native vector search via the Query API
            return vectorSearch(query, numCandidates, similarity);
        } catch (Exception e) {
            logger.error("Lance search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Lance search failed", e);
        }
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) throws IOException {
        if (idFilter == null) {
            return search(queryVector, k, "cosine");
        }

        // TODO: Implement Lance SDK pre-filter using native filter pushdown
        // Lance SDK 1.0.0-beta.2 may not support ID-based pre-filtering yet
        // For now, fall back to unfiltered search
        logger.warn(
            "Pre-filter search requested with {} IDs, but Lance SDK filter pushdown not yet implemented. "
                + "Falling back to unfiltered search. Post-filtering will be applied in LanceKnnQuery.",
            idFilter.getValueCount()
        );
        return search(queryVector, k, "cosine");
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) throws IOException {
        logger.debug("Lance search with nprobes={}, k={}", nprobes, k);
        if (queryVector.length != dims) {
            throw new IllegalArgumentException("Query vector dims mismatch: expected " + dims + ", got " + queryVector.length);
        }

        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.VECTOR_SEARCH_SETUP)) {
            return vectorSearch(queryVector, k, "cosine", nprobes);
        } catch (Exception e) {
            logger.error("Lance search with nprobes failed: {}", e.getMessage(), e);
            throw new RuntimeException("Lance search with nprobes failed", e);
        }
    }

    /**
     * Perform vector search using lance-java's native vector search API.
     * This leverages the native Rust implementation for fast approximate nearest neighbor search.
     * <p>
     * Memory management: Arrow resources are properly closed via try-with-resources,
     * and we explicitly clear batch data after extraction to release memory faster.
     * <p>
     * Performance tuning via environment variables:
     * <ul>
     *   <li>LANCE_IO_THREADS: Max concurrent I/O operations (default: 64 for cloud)</li>
     *   <li>LANCE_MAX_IOP_SIZE: Max size per I/O operation (default: 16MB)</li>
     *   <li>LANCE_NPROBES: Override nprobes for IVF search (default: 20)</li>
     * </ul>
     */
    private List<Candidate> vectorSearch(float[] queryVector, int numCandidates, String similarity) throws Exception {
        // Log Lance configuration for debugging
        String lanceIoThreads = System.getenv("LANCE_IO_THREADS");
        String lanceMaxIopSize = System.getenv("LANCE_MAX_IOP_SIZE");
        String lanceNprobesOverride = System.getenv("LANCE_NPROBES");

        int effectiveNprobes = nprobes;
        if (lanceNprobesOverride != null) {
            try {
                effectiveNprobes = Integer.parseInt(lanceNprobesOverride);
                logger.debug("Using LANCE_NPROBES override: {}", effectiveNprobes);
            } catch (NumberFormatException e) {
                logger.warn("Invalid LANCE_NPROBES value: {}, using default: {}", lanceNprobesOverride, nprobes);
            }
        }

        logger.info(
            "Lance search config: nprobes={}, indexed={}, LANCE_IO_THREADS={}, LANCE_MAX_IOP_SIZE={}, vectors={}, dims={}",
            effectiveNprobes,
            indexed,
            lanceIoThreads != null ? lanceIoThreads : "64 (default)",
            lanceMaxIopSize != null ? lanceMaxIopSize : "16MB (default)",
            vectorCount,
            dims
        );

        // Measure JNI call overhead separately (should be <1ms)
        long jniStartNanos = System.nanoTime();

        // Build the vector search query
        Query.Builder queryBuilder = new Query.Builder().setColumn(vectorColumn)
            .setKey(queryVector)
            .setK(numCandidates)
            .setDistanceType(toDistanceType(similarity))
            .setUseIndex(indexed);

        if (indexed) {
            // Set IVF search parameters for indexed search
            queryBuilder.setNprobes(effectiveNprobes);
        }

        Query query = queryBuilder.build();

        long queryBuildNanos = System.nanoTime() - jniStartNanos;
        logger.info("JNI Query.build() took {} us (should be <100us)", queryBuildNanos / 1000);

        // Build scan options with the vector search query
        long scanBuildStartNanos = System.nanoTime();
        ScanOptions scanOptions = new ScanOptions.Builder().columns(List.of(idColumn)).nearest(query).limit(numCandidates).build();
        long scanBuildNanos = System.nanoTime() - scanBuildStartNanos;
        logger.info("JNI ScanOptions.build() took {} us (should be <100us)", scanBuildNanos / 1000);

        // Execute the search - preallocate list to avoid resizing
        List<Candidate> candidates = new ArrayList<>(numCandidates);

        // Measure scanner creation (this is where OSS connection happens)
        long scannerStartNanos = System.nanoTime();
        try (LanceScanner scanner = dataset.newScan(scanOptions); ArrowReader reader = scanner.scanBatches()) {
            long scannerCreateNanos = System.nanoTime() - scannerStartNanos;
            logger.info("JNI dataset.newScan() + scanBatches() took {} ms (OSS connection setup)", scannerCreateNanos / 1_000_000);

            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.NATIVE_SCAN_SETUP)) {
                // Scanner setup complete
            }

            // Measure each batch load separately to identify OSS fetch latency
            int batchCount = 0;
            long totalBatchLoadNanos = 0;
            long totalBatchProcessNanos = 0;

            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.NATIVE_SEARCH_EXECUTION)) {
                while (true) {
                    long batchLoadStartNanos = System.nanoTime();
                    boolean hasNext = reader.loadNextBatch();
                    long batchLoadNanos = System.nanoTime() - batchLoadStartNanos;
                    totalBatchLoadNanos += batchLoadNanos;

                    if (!hasNext) break;

                    batchCount++;
                    logger.info(
                        "Batch {} loadNextBatch() took {} ms (includes OSS partition fetch)",
                        batchCount,
                        batchLoadNanos / 1_000_000
                    );

                    VectorSchemaRoot batch = reader.getVectorSchemaRoot();
                    long processStartNanos = System.nanoTime();
                    try (var batchTimer = new LanceTimer(LanceTimingContext.LanceTimingStage.BATCH_PROCESSING)) {
                        extractCandidates(batch, similarity, candidates);
                        // Clear the batch to release Arrow buffers early
                        batch.clear();
                    }
                    totalBatchProcessNanos += System.nanoTime() - processStartNanos;
                }
            }

            logger.info(
                "Lance search summary: batches={}, totalBatchLoad={}ms, totalBatchProcess={}ms, candidates={}",
                batchCount,
                totalBatchLoadNanos / 1_000_000,
                totalBatchProcessNanos / 1_000_000,
                candidates.size()
            );
        }
        // ArrowReader and LanceScanner are auto-closed here, releasing all Arrow memory

        // Sort by score descending (higher is better)
        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.SCORE_CONVERSION)) {
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());

            // Return only the requested number of candidates
            if (candidates.size() > numCandidates) {
                return new ArrayList<>(candidates.subList(0, numCandidates));
            }
        }
        return candidates;
    }

    /**
     * Vector search with explicit nprobes parameter.
     * <p>
     * This overload allows callers to control the nprobes value directly
     * instead of relying on the instance field or environment variable.
     *
     * @param queryVector The query vector
     * @param numCandidates Number of candidates to return
     * @param similarity Similarity metric
     * @param nprobes Number of IVF partitions to probe
     * @return Search results
     * @throws Exception if search fails
     */
    private List<Candidate> vectorSearch(float[] queryVector, int numCandidates, String similarity, int nprobes) throws Exception {
        // Log Lance configuration for debugging
        String lanceIoThreads = System.getenv("LANCE_IO_THREADS");
        String lanceMaxIopSize = System.getenv("LANCE_MAX_IOP_SIZE");

        logger.info(
            "Lance search with explicit nprobes: nprobes={}, indexed={}, LANCE_IO_THREADS={}, LANCE_MAX_IOP_SIZE={}, vectors={}, dims={}",
            nprobes,
            indexed,
            lanceIoThreads != null ? lanceIoThreads : "64 (default)",
            lanceMaxIopSize != null ? lanceMaxIopSize : "16MB (default)",
            vectorCount,
            dims
        );

        // Measure JNI call overhead separately (should be <1ms)
        long jniStartNanos = System.nanoTime();

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

        long queryBuildNanos = System.nanoTime() - jniStartNanos;
        logger.info("JNI Query.build() took {} us (should be <100us)", queryBuildNanos / 1000);

        // Build scan options with the vector search query
        long scanBuildStartNanos = System.nanoTime();
        ScanOptions scanOptions = new ScanOptions.Builder().columns(List.of(idColumn)).nearest(query).limit(numCandidates).build();
        long scanBuildNanos = System.nanoTime() - scanBuildStartNanos;
        logger.info("JNI ScanOptions.build() took {} us (should be <100us)", scanBuildNanos / 1000);

        // Execute the search - preallocate list to avoid resizing
        List<Candidate> candidates = new ArrayList<>(numCandidates);

        // Measure scanner creation (this is where OSS connection happens)
        long scannerStartNanos = System.nanoTime();
        try (LanceScanner scanner = dataset.newScan(scanOptions); ArrowReader reader = scanner.scanBatches()) {
            long scannerCreateNanos = System.nanoTime() - scannerStartNanos;
            logger.info("JNI dataset.newScan() + scanBatches() took {} ms (OSS connection setup)", scannerCreateNanos / 1_000_000);

            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.NATIVE_SCAN_SETUP)) {
                // Scanner setup complete
            }

            // Measure each batch load separately to identify OSS fetch latency
            int batchCount = 0;
            long totalBatchLoadNanos = 0;
            long totalBatchProcessNanos = 0;

            try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.NATIVE_SEARCH_EXECUTION)) {
                while (true) {
                    long batchLoadStartNanos = System.nanoTime();
                    boolean hasNext = reader.loadNextBatch();
                    long batchLoadNanos = System.nanoTime() - batchLoadStartNanos;
                    totalBatchLoadNanos += batchLoadNanos;

                    if (!hasNext) break;

                    batchCount++;
                    logger.info(
                        "Batch {} loadNextBatch() took {} ms (includes OSS partition fetch)",
                        batchCount,
                        batchLoadNanos / 1_000_000
                    );

                    VectorSchemaRoot batch = reader.getVectorSchemaRoot();
                    long processStartNanos = System.nanoTime();
                    try (var batchTimer = new LanceTimer(LanceTimingContext.LanceTimingStage.BATCH_PROCESSING)) {
                        extractCandidates(batch, similarity, candidates);
                        // Clear the batch to release Arrow buffers early
                        batch.clear();
                    }
                    totalBatchProcessNanos += System.nanoTime() - processStartNanos;
                }
            }

            logger.info(
                "Lance search summary: batches={}, totalBatchLoad={}ms, totalBatchProcess={}ms, candidates={}",
                batchCount,
                totalBatchLoadNanos / 1_000_000,
                totalBatchProcessNanos / 1_000_000,
                candidates.size()
            );
        }
        // ArrowReader and LanceScanner are auto-closed here, releasing all Arrow memory

        // Sort by score descending (higher is better)
        try (var timer = new LanceTimer(LanceTimingContext.LanceTimingStage.SCORE_CONVERSION)) {
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());

            // Return only the requested number of candidates
            if (candidates.size() > numCandidates) {
                return new ArrayList<>(candidates.subList(0, numCandidates));
            }
        }
        return candidates;
    }

    /**
     * Extract candidates from an Arrow batch result.
     * <p>
     * Lance returns distance metrics when using nearest search.
     * The column name varies by Lance version - try both "distance" and "_distance".
     */
    private void extractCandidates(VectorSchemaRoot batch, String similarity, List<Candidate> candidates) {
        // The ID column
        VarCharVector idVector = (VarCharVector) batch.getVector(idColumn);
        if (idVector == null) {
            logger.warn("ID column '{}' not found in batch", idColumn);
            return;
        }

        // Try different distance column names based on Lance version
        Float4Vector distVector = (Float4Vector) batch.getVector("_distance");
        if (distVector == null) {
            distVector = (Float4Vector) batch.getVector("distance");
        }

        logger.info(
            "extractCandidates: batch.getRowCount()={}, distVector={}, schema={}",
            batch.getRowCount(),
            distVector != null ? "present" : "NULL",
            batch.getSchema().toJson()
        );

        if (distVector == null) {
            logger.error(
                "CRITICAL: Distance column not found in Lance scan results! "
                    + "Tried '_distance' and 'distance'. Available columns: {}. "
                    + "This will result in 0 scores for all results.",
                batch.getSchema().getFields().stream().map(f -> f.getName()).toList()
            );
            // Continue with null distVector - will default to distance=0
        }

        for (int i = 0; i < batch.getRowCount(); i++) {
            if (idVector.isNull(i)) continue;

            String id = new String(idVector.get(i), StandardCharsets.UTF_8);
            float distance = distVector != null && distVector.isNull(i) == false ? distVector.get(i) : 0f;
            float score = distanceToScore(distance, similarity);
            logger.info("Candidate: id={}, distance={}, score={}, similarity={}", id, distance, score, similarity);
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
     * <p>
     * IMPORTANT: Lance's distance values may not match the theoretical ranges due to:
     * - IVF-PQ approximation (distances are approximate)
     * - Different distance implementations (e.g., Lance's cosine might be computed differently)
     *
     * For cosine distance: Lance returns 1 - similarity, but we need to handle values > 1.0
     * The correct conversion for Lance's cosine distance to similarity is:
     * similarity = max(0, 1 - distance) for normalized vectors
     * However, with IVF-PQ, distances can be > 1.0 even for normalized vectors
     */
    private float distanceToScore(float distance, String similarity) {
        float score = switch (similarity.toLowerCase(Locale.ROOT)) {
            // For Lance's cosine distance, use a sigmoid-like conversion
            // that handles values > 1.0 gracefully
            case "cosine" -> {
                // Convert cosine distance to similarity score
                // Since distance can be > 1.0 (due to IVF-PQ approximation), use a soft conversion
                // score = 1.0 / (1.0 + distance) works well for typical distance ranges
                yield 1.0f / (1.0f + distance);
            }
            case "dot_product", "dot" -> -distance;  // dot product distance (negated)
            case "l2", "euclidean" -> 1.0f / (1.0f + distance);  // inverse of L2
            default -> 1.0f / (1.0f + distance);
        };
        return score;
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

    /**
     * Close the shared Arrow allocator.
     * Should be called during plugin shutdown to release all Arrow memory.
     */
    public static void closeAllocator() {
        synchronized (RealLanceDataset.class) {
            if (allocator != null) {
                try {
                    logger.info("Closing Arrow allocator, allocated bytes: {}", allocator.getAllocatedMemory());
                    allocator.close();
                } catch (Exception e) {
                    logger.warn("Error closing Arrow allocator: {}", e.getMessage());
                } finally {
                    allocator = null;
                }
            }
        }
    }

    /**
     * Get the current allocated memory in bytes.
     * Useful for monitoring and debugging memory issues.
     */
    public static long getAllocatedMemory() {
        BufferAllocator alloc = allocator;
        return alloc != null ? alloc.getAllocatedMemory() : 0;
    }
}
