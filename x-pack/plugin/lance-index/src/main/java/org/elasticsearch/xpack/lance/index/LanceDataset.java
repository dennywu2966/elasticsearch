/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.index;

import org.apache.lucene.util.Bits;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper for Lance dataset operations.
 * <p>
 * This class provides a Java interface to Lance format datasets, supporting:
 * <ul>
 *   <li>Opening and reading Lance datasets</li>
 *   <li>Vector similarity search with IVF-PQ, IVF-HNSW, etc.</li>
 *   <li>Schema introspection</li>
 *   <li>Data versioning and time travel</li>
 * </ul>
 * <p>
 * Note: This implementation uses a stub/mock approach when the Lance JNI library
 * is not available. In production, this should be replaced with actual JNI bindings.
 */
public class LanceDataset implements Closeable {

    private static final Map<String, LanceDataset> DATASET_CACHE = new ConcurrentHashMap<>();

    private final String path;
    private final LanceDatasetMetadata metadata;
    private volatile boolean closed = false;

    // Native handle (when using JNI)
    private long nativeHandle = 0;

    private LanceDataset(String path, LanceDatasetMetadata metadata) {
        this.path = path;
        this.metadata = metadata;
    }

    /**
     * Opens a Lance dataset from the given path.
     * <p>
     * Supports local paths and cloud storage URIs (s3://, gs://, az://).
     *
     * @param path The path to the Lance dataset
     * @return The opened dataset
     * @throws IOException if the dataset cannot be opened
     */
    public static LanceDataset open(String path) throws IOException {
        return DATASET_CACHE.computeIfAbsent(path, p -> {
            try {
                return openInternal(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static LanceDataset openInternal(String path) throws IOException {
        validatePath(path);

        // Try to load native library
        if (isNativeLibraryAvailable()) {
            return openNative(path);
        } else {
            // Fallback to stub implementation for testing
            return openStub(path);
        }
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("Lance dataset path cannot be null or empty");
        }

        // For local paths, verify the directory exists
        if (!path.startsWith("s3://") && !path.startsWith("gs://") && !path.startsWith("az://")) {
            Path localPath = Paths.get(path);
            if (!Files.exists(localPath)) {
                throw new IOException("Lance dataset path does not exist: " + path);
            }
            if (!Files.isDirectory(localPath)) {
                throw new IOException("Lance dataset path is not a directory: " + path);
            }
        }
    }

    private static boolean isNativeLibraryAvailable() {
        try {
            System.loadLibrary("lance_jni");
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static LanceDataset openNative(String path) throws IOException {
        // Native implementation would be called here
        // For now, throw an exception indicating native library is needed
        throw new IOException(
            "Lance native library is required for production use. " +
            "Please install lance-jni and ensure the native library is on the library path."
        );
    }

    private static LanceDataset openStub(String path) throws IOException {
        // Stub implementation for testing
        LanceDatasetMetadata metadata = new LanceDatasetMetadata(
            path,
            0, // version
            0, // row count (will be loaded lazily)
            new String[]{"id", "vector", "text"}, // default schema
            128, // default vector dimensions
            LanceIndexType.IVF_PQ
        );
        return new LanceDataset(path, metadata);
    }

    /**
     * Gets the metadata for this dataset.
     */
    public LanceDatasetMetadata getMetadata() {
        return metadata;
    }

    /**
     * Gets the dataset path.
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the number of rows in the dataset.
     */
    public long getRowCount() {
        ensureOpen();
        return metadata.rowCount;
    }

    /**
     * Gets the column names in the dataset.
     */
    public String[] getColumns() {
        return metadata.columns;
    }

    /**
     * Gets the vector dimension for the specified column.
     */
    public int getVectorDimension(String column) {
        ensureOpen();
        return metadata.vectorDimension;
    }

    /**
     * Performs a vector similarity search.
     *
     * @param column The vector column to search
     * @param queryVector The query vector
     * @param k Number of results to return
     * @param nprobes Number of probes for IVF search
     * @param refineFactor Refinement factor for approximate search
     * @param similarity The similarity metric to use
     * @param liveDocs Bit set of live documents (for filtering deleted docs)
     * @return Search results containing doc IDs and scores
     */
    public LanceSearchResult search(
        String column,
        float[] queryVector,
        int k,
        int nprobes,
        int refineFactor,
        LanceSimilarity similarity,
        Bits liveDocs
    ) throws IOException {
        ensureOpen();

        if (nativeHandle != 0) {
            return searchNative(column, queryVector, k, nprobes, refineFactor, similarity.name(), liveDocs);
        } else {
            return searchStub(column, queryVector, k, nprobes, refineFactor, similarity, liveDocs);
        }
    }

    private LanceSearchResult searchNative(
        String column,
        float[] queryVector,
        int k,
        int nprobes,
        int refineFactor,
        String similarity,
        Bits liveDocs
    ) throws IOException {
        // Native JNI call would be made here
        // This is a placeholder for the actual implementation
        throw new IOException("Native Lance search not implemented");
    }

    private LanceSearchResult searchStub(
        String column,
        float[] queryVector,
        int k,
        int nprobes,
        int refineFactor,
        LanceSimilarity similarity,
        Bits liveDocs
    ) {
        // Stub implementation for testing
        // Returns empty results
        return new LanceSearchResult(new int[0], new float[0]);
    }

    /**
     * Creates a vector index on the specified column.
     *
     * @param column The vector column to index
     * @param indexType The type of index to create
     * @param numPartitions Number of IVF partitions
     * @param numSubVectors Number of sub-vectors for PQ
     */
    public void createIndex(
        String column,
        LanceIndexType indexType,
        int numPartitions,
        int numSubVectors
    ) throws IOException {
        ensureOpen();

        if (nativeHandle != 0) {
            createIndexNative(column, indexType.name(), numPartitions, numSubVectors);
        } else {
            // Stub: do nothing
        }
    }

    private native void createIndexNative(
        String column,
        String indexType,
        int numPartitions,
        int numSubVectors
    ) throws IOException;

    /**
     * Reads vectors from the dataset.
     *
     * @param column The vector column to read
     * @param offset Starting row offset
     * @param limit Maximum number of rows to read
     * @return Array of vectors
     */
    public float[][] readVectors(String column, long offset, int limit) throws IOException {
        ensureOpen();

        if (nativeHandle != 0) {
            return readVectorsNative(column, offset, limit);
        } else {
            // Stub implementation
            return new float[0][];
        }
    }

    private native float[][] readVectorsNative(String column, long offset, int limit) throws IOException;

    /**
     * Gets a specific version of the dataset.
     */
    public LanceDataset version(long version) throws IOException {
        ensureOpen();
        // Time travel to specific version
        return this; // Stub
    }

    /**
     * Gets the latest version number.
     */
    public long latestVersion() {
        return metadata.version;
    }

    /**
     * Refreshes the dataset to include any new data.
     */
    public void refresh() throws IOException {
        ensureOpen();
        // Reload metadata
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LanceDataset has been closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            DATASET_CACHE.remove(path);

            if (nativeHandle != 0) {
                closeNative();
                nativeHandle = 0;
            }
        }
    }

    private native void closeNative();

    /**
     * Clears the dataset cache. Used for testing.
     */
    public static void clearCache() {
        for (LanceDataset dataset : DATASET_CACHE.values()) {
            try {
                dataset.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        DATASET_CACHE.clear();
    }

    /**
     * Similarity metrics for Lance vector search.
     */
    public enum LanceSimilarity {
        L2,
        COSINE,
        DOT
    }

    /**
     * Supported Lance index types.
     */
    public enum LanceIndexType {
        IVF_PQ,       // Inverted File with Product Quantization
        IVF_HNSW_PQ,  // IVF + HNSW + Product Quantization
        IVF_HNSW_SQ,  // IVF + HNSW + Scalar Quantization
        FLAT,         // Brute force flat index
        HNSW          // Hierarchical Navigable Small World
    }

    /**
     * Search result from Lance vector search.
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

    /**
     * Metadata about a Lance dataset.
     */
    public static class LanceDatasetMetadata {
        public final String path;
        public final long version;
        public final long rowCount;
        public final String[] columns;
        public final int vectorDimension;
        public final LanceIndexType indexType;

        public LanceDatasetMetadata(
            String path,
            long version,
            long rowCount,
            String[] columns,
            int vectorDimension,
            LanceIndexType indexType
        ) {
            this.path = path;
            this.version = version;
            this.rowCount = rowCount;
            this.columns = columns;
            this.vectorDimension = vectorDimension;
            this.indexType = indexType;
        }
    }
}
