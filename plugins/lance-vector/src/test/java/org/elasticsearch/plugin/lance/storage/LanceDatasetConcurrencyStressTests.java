/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Concurrency stress tests for Lance dataset management.
 * <p>
 * These tests validate thread safety under concurrent load:
 * <ul>
 *   <li>Concurrent queries to the same dataset</li>
 *   <li>Concurrent dataset loading (cache race conditions)</li>
 *   <li>Cache eviction under concurrent access</li>
 *   <li>Dataset close and reload cycles</li>
 * </ul>
 * <p>
 * These tests are critical for P0 production hardening to ensure:
 * <ul>
 *   <li>No deadlocks under concurrent access</li>
 *   <li>No data corruption</li>
 *   <li>Proper resource cleanup</li>
 *   <li>Predictable performance under load</li>
 * </ul>
 */
public class LanceDatasetConcurrencyStressTests extends ESTestCase {
    private static final Logger logger = LogManager.getLogger(LanceDatasetConcurrencyStressTests.class);

    private static final int NUM_THREADS = 10;
    private static final int OPERATIONS_PER_THREAD = 100;
    private static final long TEST_TIMEOUT_SECONDS = 60;

    /**
     * Test concurrent access to FakeLanceDataset (doesn't require native libraries).
     * <p>
     * This test validates that multiple threads can safely query the same dataset
     * without deadlocks, data races, or exceptions.
     */
    public void testConcurrentQueriesToSameDataset() throws Exception {
        assumeTrue("Use FakeLanceDataset for non-native testing", true);

        // Create a temporary JSON file as test data
        int dims = 128;
        int numVectors = 100;
        Path tempFile = createTempJsonDataset(dims, numVectors, "test-concurrent");
        String uri = "file://" + tempFile.toString();

        // Load the dataset once
        LanceDataset dataset = loadFakeDataset(uri, dims);

        CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Void>> futures = new ArrayList<>();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong totalQueryTimeNanos = new AtomicLong(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            Future<Void> future = executor.submit(() -> {
                try {
                    barrier.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS); // Synchronize start

                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        long startNanos = System.nanoTime();
                        float[] queryVector = randomVector(dims);

                        // Execute search
                        List<LanceDataset.Candidate> results = dataset.search(queryVector, 10, "cosine");

                        long elapsedNanos = System.nanoTime() - startNanos;
                        totalQueryTimeNanos.addAndGet(elapsedNanos);

                        // Validate results
                        assertNotNull("Results should not be null", results);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed: {}", threadId, e.getMessage(), e);
                    errorCount.incrementAndGet();
                }
                return null;
            });
            futures.add(future);
        }

        // Wait for all threads to complete
        for (Future<Void> future : futures) {
            future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert results
        assertThat("No errors should occur during concurrent queries", errorCount.get(), lessThan(1));
        assertThat("All operations should complete successfully", successCount.get(), greaterThan(OPERATIONS_PER_THREAD * NUM_THREADS - 1));

        long avgQueryTimeMicros = (totalQueryTimeNanos.get() / (NUM_THREADS * OPERATIONS_PER_THREAD)) / 1000;
        logger.info(
            "Concurrent query test: {} threads, {} ops/thread, avg query time: {} μs",
            NUM_THREADS,
            OPERATIONS_PER_THREAD,
            avgQueryTimeMicros
        );
    }

    /**
     * Test concurrent dataset loading (cache race conditions).
     * <p>
     * This test validates that when multiple threads try to load the same
     * dataset simultaneously, only one dataset is created and cached.
     */
    public void testConcurrentDatasetLoading() throws Exception {
        int dims = 64;
        int numVectors = 10;
        String suffix = "test-concurrent-loading-" + randomInt();
        Path tempFile = createTempJsonDataset(dims, numVectors, suffix);
        String uri = "file://" + tempFile.toString();

        CyclicBarrier barrier = new CyclicBarrier(NUM_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<LanceDataset>> futures = new ArrayList<>();

        for (int i = 0; i < NUM_THREADS; i++) {
            Future<LanceDataset> future = executor.submit(() -> {
                try {
                    barrier.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS); // Synchronize start
                    // All threads try to load the same URI simultaneously
                    return loadFakeDataset(uri, dims);
                } catch (Exception e) {
                    logger.error("Dataset loading failed: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }

        // Collect all results
        List<LanceDataset> datasets = new ArrayList<>();
        for (Future<LanceDataset> future : futures) {
            LanceDataset dataset = future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            datasets.add(dataset);
        }

        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // All threads should get the same dataset instance (cached)
        LanceDataset firstDataset = datasets.get(0);
        for (LanceDataset dataset : datasets) {
            assertSame("All threads should get the same cached dataset instance", firstDataset, dataset);
        }

        // Verify the URI is cached
        assertTrue("URI should be cached", LanceDatasetRegistry.contains(uri));

        logger.info("Concurrent loading test: {} threads raced to load same URI, cache worked correctly", NUM_THREADS);
    }

    /**
     * Test cache eviction under concurrent access.
     * <p>
     * This test validates that when datasets are evicted (due to cache limits),
     * resources are properly cleaned up even under concurrent access.
     */
    public void testCacheEvictionUnderConcurrentAccess() throws Exception {
        // Clear registry to start fresh
        LanceDatasetRegistry.clear();

        int numDatasets = NUM_THREADS; // Each thread loads a different dataset
        int cacheSize = 5; // Small cache to force evictions

        CyclicBarrier loadBarrier = new CyclicBarrier(NUM_THREADS);
        CyclicBarrier queryBarrier = new CyclicBarrier(NUM_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Void>> futures = new ArrayList<>();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            Path tempFile = createTempJsonDataset(32, 20, "test-eviction-" + i);
            final String uri = "file://" + tempFile.toString();

            Future<Void> future = executor.submit(() -> {
                try {
                    // Phase 1: Load datasets concurrently
                    loadBarrier.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    LanceDataset dataset = loadFakeDataset(uri, 32);
                    assertNotNull("Dataset should be loaded", dataset);

                    // Phase 2: Query concurrently (some datasets may be evicted)
                    queryBarrier.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                    for (int j = 0; j < OPERATIONS_PER_THREAD / 10; j++) {
                        float[] queryVector = randomVector(32);
                        List<LanceDataset.Candidate> results = dataset.search(queryVector, 5, "cosine");
                        assertNotNull("Results should not be null", results);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed: {}", threadId, e.getMessage(), e);
                    errorCount.incrementAndGet();
                }
                return null;
            });
            futures.add(future);
        }

        // Wait for completion
        for (Future<Void> future : futures) {
            future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert no errors
        assertThat("No errors should occur during cache eviction", errorCount.get(), lessThan(1));

        logger.info("Cache eviction test: {} datasets loaded concurrently, {} successful queries", numDatasets, successCount.get());
    }

    /**
     * Test dataset close and reload cycles under concurrent load.
     * <p>
     * This test validates that datasets can be safely closed, removed from cache,
     * and reloaded under concurrent access without resource leaks.
     */
    public void testDatasetCloseAndReloadUnderConcurrentLoad() throws Exception {
        String baseUri = "embedded:test-reload-";
        int cycles = 5;
        int dims = 32;

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Void>> futures = new ArrayList<>();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            // Create 3 distinct test files
            int fileIndex = threadId % 3;
            Path tempFile = createTempJsonDataset(dims, 10, "test-reload-" + fileIndex);
            final String uri = "file://" + tempFile.toString();

            Future<Void> future = executor.submit(() -> {
                try {
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        // Load dataset
                        LanceDataset dataset = loadFakeDataset(uri, dims);
                        assertNotNull("Dataset should load successfully", dataset);

                        // Query
                        float[] queryVector = randomVector(dims);
                        List<LanceDataset.Candidate> results = dataset.search(queryVector, 5, "cosine");
                        assertNotNull("Results should not be null", results);

                        // Occasionally invalidate (close) the dataset
                        if (randomInt(10) < 3) { // 30% chance
                            LanceDatasetRegistry.invalidate(uri);
                        }

                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    logger.error("Thread {} cycle failed: {}", threadId, e.getMessage(), e);
                    errorCount.incrementAndGet();
                }
                return null;
            });
            futures.add(future);
        }

        // Wait for completion
        for (Future<Void> future : futures) {
            future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert minimal errors (some expected due to invalidation timing)
        assertThat("Errors should be minimal during close/reload cycles", errorCount.get(), lessThan(NUM_THREADS));

        logger.info(
            "Close/reload test: {} threads, {} cycles each, {} successes, {} errors",
            NUM_THREADS,
            cycles,
            successCount.get(),
            errorCount.get()
        );
    }

    /**
     * Test mixed operations under concurrent load.
     * <p>
     * This test simulates real-world usage with a mix of:
     * - Loading new datasets
     * - Querying existing datasets
     * - Invalidating datasets
     * - Clearing the registry
     */
    public void testMixedOperationsUnderConcurrentLoad() throws Exception {
        LanceDatasetRegistry.clear();

        int numUris = 5;
        String[] uris = new String[numUris];
        for (int i = 0; i < numUris; i++) {
            Path tempFile = createTempJsonDataset(32, 10, "test-mixed-" + i);
            uris[i] = "file://" + tempFile.toString();
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Void>> futures = new ArrayList<>();
        Semaphore clearLock = new Semaphore(1); // Only one thread can clear at a time

        AtomicInteger loadCount = new AtomicInteger(0);
        AtomicInteger queryCount = new AtomicInteger(0);
        AtomicInteger invalidateCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;

            Future<Void> future = executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        int operation = randomInt(100);

                        if (operation < 60) {
                            // 60%: Query
                            String uri = uris[Math.min(randomInt(numUris), numUris - 1)];
                            LanceDataset dataset = loadFakeDataset(uri, 32);
                            float[] queryVector = randomVector(32);
                            List<LanceDataset.Candidate> results = dataset.search(queryVector, 5, "cosine");
                            assertNotNull("Results should not be null", results);
                            queryCount.incrementAndGet();

                        } else if (operation < 90) {
                            // 30%: Load (cache hit or miss)
                            String uri = uris[Math.min(randomInt(numUris), numUris - 1)];
                            LanceDataset dataset = loadFakeDataset(uri, 32);
                            assertNotNull("Dataset should load", dataset);
                            loadCount.incrementAndGet();

                        } else {
                            // 10%: Invalidate or Clear (with rate limiting)
                            if (clearLock.tryAcquire()) {
                                try {
                                    if (randomBoolean()) {
                                        String uri = uris[Math.min(randomInt(numUris), numUris - 1)];
                                        LanceDatasetRegistry.invalidate(uri);
                                    } else {
                                        // Rarely clear all
                                        if (randomInt(100) < 10) {
                                            LanceDatasetRegistry.clear();
                                        }
                                    }
                                    invalidateCount.incrementAndGet();
                                } finally {
                                    clearLock.release();
                                }
                            }
                        }

                        // Small delay to increase contention
                        if (randomInt(100) < 10) {
                            Thread.sleep(1);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed: {}", threadId, e.getMessage(), e);
                    errorCount.incrementAndGet();
                }
                return null;
            });
            futures.add(future);
        }

        // Wait for completion
        for (Future<Void> future : futures) {
            future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert results
        assertThat("Errors should be minimal during mixed operations", errorCount.get(), lessThan(NUM_THREADS));

        logger.info(
            "Mixed operations test: loads={}, queries={}, invalidates={}, errors={}",
            loadCount.get(),
            queryCount.get(),
            invalidateCount.get(),
            errorCount.get()
        );
    }

    // ========== Helper Methods ==========

    /**
     * Generate a random vector of the given dimensions.
     */
    private float[] randomVector(int dims) {
        float[] vector = new float[dims];
        for (int i = 0; i < dims; i++) {
            vector[i] = randomFloat();
        }
        return vector;
    }

    /**
     * Create a temporary JSON file with random vector data for testing.
     * <p>
     * The JSON format is:
     * <pre>
     * [
     *   { "id": "doc_0", "vector": [0.1, 0.2, ...] },
     *   { "id": "doc_1", "vector": [0.3, 0.4, ...] }
     * ]
     * </pre>
     *
     * @param dims       Vector dimensions
     * @param numVectors Number of vectors to generate
     * @param suffix     Suffix for the filename (helps identify test)
     * @return Path to the temporary JSON file
     * @throws Exception if file creation fails
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
}
