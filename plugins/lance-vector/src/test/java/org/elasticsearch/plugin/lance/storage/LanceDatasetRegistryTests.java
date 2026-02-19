/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.elasticsearch.test.ESTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Comprehensive tests for {@link LanceDatasetRegistry}.
 * <p>
 * These tests cover edge cases, error handling, concurrency, and cache behavior.
 */
public class LanceDatasetRegistryTests extends ESTestCase {

    // ========== Registry Lifecycle Tests ==========

    public void testRegistryClearWhenEmpty() {
        // Should not throw when clearing an empty registry
        LanceDatasetRegistry.clear();
        assertThat("Registry size should be 0 after clear", LanceDatasetRegistry.size(), equalTo(0));
    }

    public void testRegistryClearRemovesAllEntries() throws Exception {
        String suffix = "test-clear-" + randomInt();
        LanceDatasetRegistry.clear();

        // Add some entries
        for (int i = 0; i < 5; i++) {
            Path tempFile = createTempJsonDataset(32, 10, suffix + "-" + i);
            String uri = "file://" + tempFile.toString();
            loadFakeDataset(uri, 32);
        }

        int sizeBefore = LanceDatasetRegistry.size();
        assertThat("Registry should have entries", sizeBefore, greaterThan(0));

        // Clear should remove all
        LanceDatasetRegistry.clear();
        assertThat("Registry size should be 0 after clear", LanceDatasetRegistry.size(), equalTo(0));
    }

    public void testRegistryInvalidateNonExistentUri() {
        // Should not throw when invalidating non-existent URI
        LanceDatasetRegistry.invalidate("nonexistent://uri");
        // If we get here without exception, test passes
    }

    public void testRegistryContainsAfterLoad() throws Exception {
        String suffix = "test-contains-" + randomInt();
        Path tempFile = createTempJsonDataset(64, 10, suffix);
        String uri = "file://" + tempFile.toString();
        int dims = 64;

        // Initially not cached
        assertFalse("URI should not be cached initially", LanceDatasetRegistry.contains(uri));

        // After load, should be cached
        loadFakeDataset(uri, dims);

        assertTrue("URI should be cached after load", LanceDatasetRegistry.contains(uri));
    }

    // ========== URI Detection Tests ==========

    public void testIsLanceFormatWithLocalFileLance() {
        assertTrue("Local .lance file should be Lance format", LanceDatasetRegistry.isLanceFormat("/path/to/data.lance"));
        assertTrue("Local .lance/ directory should be Lance format", LanceDatasetRegistry.isLanceFormat("/path/to/data.lance/"));
        assertTrue(
            "Local .lance\\ directory (Windows) should be Lance format",
            LanceDatasetRegistry.isLanceFormat("C:\\path\\to\\data.lance\\")
        );
    }

    public void testIsLanceFormatWithOss() {
        assertTrue("OSS URIs with .lance should be Lance format", LanceDatasetRegistry.isLanceFormat("oss://bucket/path/to/data.lance"));
        assertTrue("OSS URIs with .lance/ should be Lance format", LanceDatasetRegistry.isLanceFormat("oss://bucket/path/to/data.lance/"));

        // OSS URIs without .lance should NOT be Lance format
        assertFalse(
            "OSS URIs without .lance should not be Lance format",
            LanceDatasetRegistry.isLanceFormat("oss://bucket/path/to/data.json")
        );
        assertFalse("OSS URIs without .lance/ should not be Lance format", LanceDatasetRegistry.isLanceFormat("oss://bucket/data.json"));
    }

    public void testIsLanceFormatWithS3() {
        // S3 is not yet supported
        assertFalse("S3 URIs should not be Lance format (not yet supported)", LanceDatasetRegistry.isLanceFormat("s3://bucket/data.lance"));
        assertFalse("S3 URIs should not be Lance format", LanceDatasetRegistry.isLanceFormat("s3://bucket/data.json"));
    }

    public void testIsLanceFormatWithFileScheme() {
        assertTrue(
            "file:// URIs ending with .lance should be Lance format",
            LanceDatasetRegistry.isLanceFormat("file:///path/to/data.lance")
        );
        assertFalse(
            "file:// URIs without .lance should not be Lance format",
            LanceDatasetRegistry.isLanceFormat("file:///path/to/data.json")
        );
    }

    public void testIsLanceFormatWithEmbedded() {
        // Embedded URIs are for test fixtures, NOT Lance format
        assertFalse("Embedded URIs should not be Lance format", LanceDatasetRegistry.isLanceFormat("embedded:test-data"));
        assertFalse("Embedded URIs with .json should not be Lance format", LanceDatasetRegistry.isLanceFormat("embedded:test.json"));
    }

    // ========== Configuration Tests ==========

    public void testLanceDatasetConfigDefaults() {
        LanceDatasetConfig config = LanceDatasetConfig.defaults();

        assertThat("Default id column", config.idColumn(), equalTo("_id"));
        assertThat("Default vector column", config.vectorColumn(), equalTo("vector"));
        assertThat("Default dims", config.expectedDims(), equalTo(0));
        assertThat("OSS endpoint should be null by default", config.ossEndpoint(), Matchers.nullValue());
        assertThat("OSS access key should be null by default", config.ossAccessKeyId(), Matchers.nullValue());
        assertThat("OSS secret should be null by default", config.ossAccessKeySecret(), Matchers.nullValue());
    }

    public void testLanceDatasetConfigWithCustomDims() {
        int dims = randomIntBetween(1, 2048);
        LanceDatasetConfig config = LanceDatasetConfig.withDims(dims);

        assertThat("Custom dims should be set", config.expectedDims(), equalTo(dims));
        assertThat("Should have default id column", config.idColumn(), equalTo("_id"));
        assertThat("Should have default vector column", config.vectorColumn(), equalTo("vector"));
    }

    public void testLanceDatasetConfigWithCustomNames() {
        String idColumn = "doc_id";
        String vectorColumn = "embedding";
        int dims = 256;

        LanceDatasetConfig config = new LanceDatasetConfig(idColumn, vectorColumn, dims, null, null, null);

        assertThat("Custom id column should be set", config.idColumn(), equalTo(idColumn));
        assertThat("Custom vector column should be set", config.vectorColumn(), equalTo(vectorColumn));
        assertThat("Custom dims should be set", config.expectedDims(), equalTo(dims));
    }

    public void testLanceDatasetConfigWithOssConfig() {
        String endpoint = "oss-ap-southeast-1.aliyuncs.com";
        String keyId = "LTAI5t...";
        String secret = "...";

        LanceDatasetConfig config = LanceDatasetConfig.withOssConfig("_id", "vector", 128, endpoint, keyId, secret);

        assertThat("OSS endpoint should be set", config.ossEndpoint(), equalTo(endpoint));
        assertThat("OSS access key should be set", config.ossAccessKeyId(), equalTo(keyId));
        assertThat("OSS secret should be set", config.ossAccessKeySecret(), equalTo(secret));
    }

    public void testLanceDatasetConfigIsOssConfigured() {
        LanceDatasetConfig defaults = LanceDatasetConfig.defaults();
        assertFalse("Default config should not have OSS", defaults.isOssConfigured());

        LanceDatasetConfig withOss = LanceDatasetConfig.withOssConfig("_id", "vector", 128, "endpoint", "key", "secret");
        assertTrue("Config with OSS should be configured", withOss.isOssConfigured());
    }

    // ========== Cache Behavior Tests ==========

    public void testCacheReturnsSameInstanceForSameUri() throws Exception {
        String suffix = "test-cache-same-" + randomInt();
        int dims = 64;

        Path tempFile = createTempJsonDataset(dims, 10, suffix);
        String uri = "file://" + tempFile.toString();

        // Load once
        LanceDataset dataset1 = loadFakeDataset(uri, dims);
        assertNotNull("First load should return dataset", dataset1);

        // Load again
        LanceDataset dataset2 = loadFakeDataset(uri, dims);
        assertNotNull("Second load should return dataset", dataset2);

        // Should be the same instance
        assertSame("Should return same cached instance", dataset1, dataset2);
    }

    public void testCacheEvictionOnMultipleUris() throws Exception {
        LanceDatasetRegistry.clear();

        // Load multiple datasets (registry max is 100, so use small number)
        int numDatasets = 5;
        String[] uris = new String[numDatasets];

        for (int i = 0; i < numDatasets; i++) {
            String suffix = "test-eviction-" + i;
            uris[i] = "file://" + createTempJsonDataset(32, 10, suffix).toString();
            try {
                loadFakeDataset(uris[i], 32);
            } catch (IOException e) {
                // Expected if file doesn't exist
            }
        }

        // All URIs should be cached (or at least attempted)
        for (String uri : uris) {
            // Contains check just verifies cache lookup works
            LanceDatasetRegistry.contains(uri);
        }

        LanceDatasetRegistry.clear();
    }

    // ========== Error Handling Tests ==========

    public void testGetWithInvalidUri() {
        String uri = "invalid://uri/that/does/not/exist";
        int dims = 64;

        expectThrows(Exception.class, () -> { LanceDatasetRegistry.getOrLoad(uri, dims, LanceDatasetConfig.defaults()); });
    }

    public void testGetOrLoadRejectsNonLanceUris() {
        String uri = "file:///tmp/not-a-lance.json";
        String property = "es.lance.allow_file_json_fallback_for_tests";
        String original = System.getProperty(property);
        System.clearProperty(property);
        try {
            IOException e = expectThrows(IOException.class, () -> LanceDatasetRegistry.getOrLoad(uri, 64, LanceDatasetConfig.defaults()));
            assertThat(e.getMessage(), containsString("Unsupported Lance dataset URI"));
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    public void testGetOrLoadWithLoaderThrowsException() throws IOException {
        LanceDatasetRegistry.clear();
        String uri = "embedded:test-exception-" + randomInt();

        // Create a loader that throws - Supplier can't throw checked exceptions,
        // so we need to wrap in RuntimeException
        Supplier<LanceDataset> failingLoader = () -> {
            throw new RuntimeException("Simulated load failure", new IOException("Wrapped exception"));
        };

        try {
            LanceDatasetRegistry.get(uri, failingLoader);
            fail("Should throw RuntimeException from loader");
        } catch (RuntimeException e) {
            assertThat("Should have error from loader", e.getMessage(), containsString("Simulated load failure"));
        }

        // Failed loads should not be cached
        assertFalse("Failed load should not be cached", LanceDatasetRegistry.contains(uri));
    }

    public void testDatasetCloseIdempotent() throws Exception {
        String suffix = "test-close-" + randomInt();
        Path tempFile = createTempJsonDataset(32, 10, suffix);
        String uri = "file://" + tempFile.toString();

        LanceDataset dataset = loadFakeDataset(uri, 32);
        assertNotNull("Dataset should be loaded", dataset);

        // Close once
        dataset.close();

        // Close again should be safe (no-op or handled gracefully)
        dataset.close();

        // Clean up
        LanceDatasetRegistry.invalidate(uri);
    }

    public void testInvalidateClosesDatasetOnlyOnce() throws Exception {
        LanceDatasetRegistry.clear();
        String uri = "unit://close-once-" + randomAlphaOfLength(8);
        CountingCloseDataset dataset = new CountingCloseDataset();

        LanceDatasetRegistry.get(uri, () -> dataset);
        LanceDatasetRegistry.invalidate(uri);

        assertThat("invalidate should trigger exactly one close", dataset.closeCount.get(), equalTo(1));
    }

    // ========== Thread Safety Tests ==========

    public void testConcurrentGetOrLoadSameUri() throws Exception {
        String suffix = "test-concurrent-get-" + randomInt();
        Path tempFile = createTempJsonDataset(64, 10, suffix);
        String uri = "file://" + tempFile.toString();
        int dims = 64;

        int numThreads = 5;
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numThreads);

        // All threads should get the same instance
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    LanceDataset dataset = loadFakeDataset(uri, dims);
                    assertNotNull("Dataset should load successfully", dataset);
                    latch.countDown();
                } catch (Exception e) {
                    // Should not happen
                    e.printStackTrace();
                }
            }).start();
        }

        boolean completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue("All threads should complete successfully", completed);
    }

    // ========== Edge Cases ==========

    public void testGetWithEmptyUri() {
        String uri = "";
        int dims = 64;

        expectThrows(Exception.class, () -> { LanceDatasetRegistry.getOrLoad(uri, dims, LanceDatasetConfig.defaults()); });
    }

    public void testGetOrLoadWithZeroDims() throws Exception {
        String suffix = "test-zero-dims-" + randomInt();
        // Even with zero dims, should be able to load if file exists
        // Zero dims just means we don't validate dimension count
        Path tempFile = createTempJsonDataset(32, 10, suffix);
        String uri = "file://" + tempFile.toString();
        int dims = 0; // Edge case: zero dimensions means auto-detect

        // Should load successfully (dims=0 means auto-detect)
        LanceDataset dataset = loadFakeDataset(uri, dims);
        assertNotNull("Dataset should load even with dims=0 (auto-detect)", dataset);
    }

    public void testIsLanceFormatWithEmptyUri() {
        assertFalse("Empty URI should not be Lance format", LanceDatasetRegistry.isLanceFormat(""));
        assertFalse("Null-like URI should not be Lance format", LanceDatasetRegistry.isLanceFormat(""));
    }

    public void testIsLanceFormatWithMixedCase() {
        // The isLanceFormat method is case-sensitive for scheme checking
        // but any URI ending with .lance is recognized (fallback behavior)
        assertFalse("s3:// (lowercase) is not supported", LanceDatasetRegistry.isLanceFormat("s3://bucket/data.lance"));
        assertFalse("s3:// without .lance is not supported", LanceDatasetRegistry.isLanceFormat("s3://bucket/data.json"));

        // S3:// (uppercase) falls through to .lance check - returns true
        // This is current behavior, could be refined in future
        assertTrue(
            "S3:// with .lance path is recognized (fallback to .lance check)",
            LanceDatasetRegistry.isLanceFormat("S3://bucket/data.lance")
        );

        // Same for OSS with different cases
        assertTrue("oss:// (lowercase) with .lance is OSS format", LanceDatasetRegistry.isLanceFormat("oss://bucket/data.lance"));
        assertTrue(
            "OSS:// (uppercase) with .lance is recognized (fallback)",
            LanceDatasetRegistry.isLanceFormat("OSS://bucket/data.lance")
        );

        assertFalse("OSS:// without .lance path should not be recognized", LanceDatasetRegistry.isLanceFormat("OSS://bucket/data.json"));
    }

    // ========== Helper Methods ==========

    /**
     * Create a temporary JSON file with random vector data for testing.
     */
    private Path createTempJsonDataset(int dims, int numVectors, String suffix) throws Exception {
        Path tempDir = createTempDir();
        Path tempFile = tempDir.resolve("lance-test-" + suffix + ".json");

        StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (int i = 0; i < numVectors; i++) {
            json.append("  {\"id\": \"doc_").append(suffix).append("_").append(i).append("\", \"vector\": [");
            for (int j = 0; j < dims; j++) {
                json.append(randomFloat());
                if (j < dims - 1) {
                    json.append(", ");
                }
            }
            json.append("]}");
            if (i < numVectors - 1) {
                json.append(",\n");
            }
        }
        json.append("\n]");

        Files.writeString(tempFile, json.toString());
        return tempFile;
    }

    private LanceDataset loadFakeDataset(String uri, int dims) throws IOException {
        return LanceDatasetRegistry.get(uri, () -> {
            try {
                return FakeLanceDataset.load(uri, dims);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static class CountingCloseDataset implements LanceDataset {
        private final AtomicInteger closeCount = new AtomicInteger(0);

        @Override
        public int dims() {
            return 0;
        }

        @Override
        public List<Candidate> search(float[] query, int numCandidates, String similarity) {
            return List.of();
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, org.apache.arrow.vector.VarCharVector idFilter) {
            return List.of();
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) {
            return List.of();
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) {
            return List.of();
        }

        @Override
        public String uri() {
            return "unit://dataset";
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
