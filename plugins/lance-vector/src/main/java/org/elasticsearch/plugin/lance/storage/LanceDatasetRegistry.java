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
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.cache.RemovalListener;
import org.elasticsearch.common.cache.RemovalNotification;
import org.elasticsearch.core.TimeValue;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Unified registry for Lance datasets with caching support.
 * <p>
 * This registry manages both {@link RealLanceDataset} (for .lance format files)
 * and {@link FakeLanceDataset} (for JSON test files), providing a single entry
 * point for dataset access with automatic caching.
 * <p>
 * Thread-safe: Uses Elasticsearch's Cache with automatic eviction to prevent
 * unbounded memory growth. Datasets are evicted based on LRU policy when the
 * cache reaches its maximum size.
 * <p>
 * <b>Concurrency Model:</b> Uses double-checked locking for thread-safe
 * dataset loading. The loading state is tracked in a separate map to prevent
 * multiple threads from loading the same dataset simultaneously.
 */
public class LanceDatasetRegistry {
    private static final Logger logger = LogManager.getLogger(LanceDatasetRegistry.class);

    // Maximum number of datasets to cache
    private static final int MAX_CACHED_DATASETS = 100;

    // Time after which cached datasets are eligible for eviction
    private static final TimeValue CACHE_TTL = TimeValue.timeValueHours(1);

    // Use Elasticsearch's Cache with automatic eviction
    private static volatile Cache<String, LanceDataset> CACHE;

    // Tracks which datasets are currently being loaded to prevent duplicate loads
    private static final ConcurrentHashMap<String, Object> LOADING_URIS = new ConcurrentHashMap<>();

    // Guards refresh invalidation (write) against active query/search execution (read).
    private static final ReentrantReadWriteLock QUERY_REFRESH_GUARD = new ReentrantReadWriteLock();

    @FunctionalInterface
    public interface IOAction<T> {
        T run() throws IOException;
    }

    /**
     * Removal listener for cache evictions.
     * <p>
     * This callback is invoked when datasets are evicted from the cache due to:
     * <ul>
     *   <li>Size limit (LRU eviction when cache is full)</li>
     *   <li>Time-based expiration (TTL expired)</li>
     *   <li>Manual invalidation</li>
     * </ul>
     * <p>
     * <b>CRITICAL:</b> This callback ensures native resources (JNI handles, Arrow memory)
     * are properly released when datasets are automatically evicted. Without this,
     * evicted datasets would leak native memory and file descriptors.
     */
    private static final RemovalListener<String, LanceDataset> DATASET_REMOVAL_LISTENER = new RemovalListener<>() {
        @Override
        public void onRemoval(RemovalNotification<String, LanceDataset> notification) {
            LanceDataset dataset = notification.getValue();
            String uri = notification.getKey();
            var reason = notification.getRemovalReason();

            try {
                if (dataset != null) {
                    logger.debug("Closing evicted dataset: uri={}, reason={}", uri, reason);
                    dataset.close();
                    logger.info("Successfully closed evicted Lance dataset: uri={}, reason={}", uri, reason);
                }
            } catch (IOException e) {
                logger.warn("Failed to close evicted dataset {}: {}", uri, e.getMessage());
            } finally {
                // Clear loading state to allow re-loading if needed
                LOADING_URIS.remove(uri);
            }
        }
    };

    private static Cache<String, LanceDataset> getCache() {
        if (CACHE == null) {
            synchronized (LanceDatasetRegistry.class) {
                if (CACHE == null) {
                    CACHE = CacheBuilder.<String, LanceDataset>builder()
                        .setMaximumWeight(MAX_CACHED_DATASETS)
                        .setExpireAfterAccess(CACHE_TTL)
                        .removalListener(DATASET_REMOVAL_LISTENER)
                        .build();
                    logger.info("Initialized Lance dataset registry with max entries={} and TTL={}", MAX_CACHED_DATASETS, CACHE_TTL);
                }
            }
        }
        return CACHE;
    }

    /**
     * Get or load a dataset from the registry.
     * <p>
     * If the dataset is already cached, returns the cached instance.
     * Otherwise, calls the loader to create the dataset and caches it.
     * <p>
     * Thread-safe: Uses double-checked locking with a loading marker to prevent
     * multiple threads from loading the same dataset simultaneously.
     *
     * @param uri    Dataset URI (used as cache key)
     * @param loader Supplier that loads the dataset if not cached
     * @return The cached or newly loaded dataset
     * @throws IOException if the loader fails
     */
    public static LanceDataset get(String uri, Supplier<LanceDataset> loader) throws IOException {
        Cache<String, LanceDataset> cache = getCache();

        // Fast path: check cache without synchronization
        LanceDataset cached = cache.get(uri);
        if (cached != null) {
            return cached;
        }

        // Slow path: synchronize to prevent duplicate loads
        synchronized (uri.intern()) {  // Intern URI for per-URI locking
            // Double-check: another thread may have loaded while we waited
            cached = cache.get(uri);
            if (cached != null) {
                return cached;
            }

            // Check if already loading (rare race condition)
            if (LOADING_URIS.putIfAbsent(uri, uri) != null) {
                // Another thread is loading this dataset, wait and retry
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for dataset load: " + uri, e);
                }
                // Retry after short wait
                cached = cache.get(uri);
                if (cached != null) {
                    return cached;
                }
                // If still not loaded, continue with loading
            }

            try {
                logger.debug("Loading dataset into registry: {}", uri);
                LanceDataset dataset = loader.get();
                cache.put(uri, dataset);
                return dataset;
            } finally {
                // Clear loading state
                LOADING_URIS.remove(uri);
            }
        }
    }

    /**
     * Get a dataset from the registry, loading it if necessary.
     * <p>
     * Automatically selects the appropriate dataset type based on URI:
     * <ul>
     *   <li>Object storage URIs (oss://, s3://) → RealLanceDataset</li>
     *   <li>Local file:// URIs ending with .lance or containing .lance/ → RealLanceDataset</li>
     *   <li>URIs starting with embedded: → FakeLanceDataset (embedded JSON for testing)</li>
     *   <li>Other URIs → FakeLanceDataset (external JSON file for testing)</li>
     * </ul>
     * <p>
     * <b>IMPORTANT:</b> Object storage URIs must always use RealLanceDataset, never FakeLanceDataset.
     * FakeLanceDataset is only for test fixtures and should not be used in production code paths.
     *
     * @param uri Dataset URI
     * @param dims Expected vector dimensions
     * @param config Configuration for real datasets
     * @return The dataset
     * @throws IOException if loading fails
     */
    public static LanceDataset getOrLoad(String uri, int dims, LanceDatasetConfig config) throws IOException {
        return get(uri, () -> {
            try {
                if (isLanceFormat(uri)) {
                    return RealLanceDataset.open(uri, config);
                } else {
                    return FakeLanceDataset.load(uri, dims);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load dataset: " + uri, e);
            }
        });
    }

    /**
     * Check if the URI points to a real Lance format dataset.
     * <p>
     * Real Lance datasets include:
     * <ul>
     *   <li>Local file:// URIs ending with .lance</li>
     *   <li>Object storage URIs (oss://) ending with .lance or containing .lance/</li>
     *   <li>Paths containing .lance/ directory marker</li>
     * </ul>
     * <p>
     * <b>IMPORTANT:</b> Object storage URIs must end with .lance or contain .lance/
     * to be considered Lance datasets. Files with other extensions (like .json)
     * are assumed to be test fixtures.
     * <p>
     * <b>Note:</b> S3 URIs are not yet supported and will fall back to FakeLanceDataset
     * for testing purposes.
     *
     * @param uri Dataset URI to check
     * @return true if the URI should use RealLanceDataset, false for test JSON files
     */
    public static boolean isLanceFormat(String uri) {
        // S3 is not yet supported - return false to use FakeLanceDataset for testing
        if (uri.startsWith("s3://")) {
            return false;
        }

        // OSS URIs - only if they have .lance extension or path
        if (uri.startsWith("oss://")) {
            // Extract the path part after the bucket
            int firstSlash = uri.indexOf('/', uri.indexOf(':') + 2);
            if (firstSlash >= 0 && firstSlash < uri.length() - 1) {
                String path = uri.substring(firstSlash + 1);
                return path.endsWith(".lance") || path.contains(".lance/");
            }
            return false;
        }

        // Local .lance files or directories
        return uri.endsWith(".lance") || uri.contains(".lance/") || uri.contains(".lance\\");
    }

    /**
     * Invalidate and close a specific dataset from the cache.
     * <p>
     * This method is thread-safe and can be called while other threads are
     * accessing the dataset. The dataset will be closed before being removed.
     *
     * @param uri Dataset URI to invalidate
     */
    public static void invalidate(String uri) {
        withRefreshLock(() -> {
            Cache<String, LanceDataset> cache = getCache();
            LanceDataset removed = cache.get(uri);
            if (removed != null) {
                try {
                    logger.debug("Invalidating dataset from registry: {}", uri);
                    removed.close();
                } catch (IOException e) {
                    logger.warn("Error closing invalidated dataset {}: {}", uri, e.getMessage());
                }
            }
            cache.invalidate(uri);
            LOADING_URIS.remove(uri);
        });
    }

    /**
     * Clear all datasets from the cache.
     * <p>
     * This closes all cached datasets and removes them from the registry.
     * Useful for testing or shutdown.
     * <p>
     * <b>WARNING:</b> This method is not atomic with respect to concurrent
     * getOrLoad() calls. New datasets may be loaded while clear() is in progress.
     * For production use, prefer invalidate() for specific URIs.
     */
    public static void clear() {
        withRefreshLock(() -> {
            logger.debug("Clearing all datasets from registry");
            Cache<String, LanceDataset> cache = getCache();

            // The removal listener will handle closing all datasets
            cache.invalidateAll();
            LOADING_URIS.clear();
        });
    }

    /**
     * Execute a search-path action while blocking refresh invalidation.
     */
    public static <T> T withSearchLock(IOAction<T> action) throws IOException {
        Lock readLock = QUERY_REFRESH_GUARD.readLock();
        readLock.lock();
        try {
            return action.run();
        } finally {
            readLock.unlock();
        }
    }

    private static void withRefreshLock(Runnable action) {
        Lock writeLock = QUERY_REFRESH_GUARD.writeLock();
        writeLock.lock();
        try {
            action.run();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Get the number of cached datasets.
     */
    public static int size() {
        Cache<String, LanceDataset> cache = getCache();
        return cache.count();
    }

    /**
     * Check if a dataset is cached.
     */
    public static boolean contains(String uri) {
        Cache<String, LanceDataset> cache = getCache();
        return cache.get(uri) != null;
    }
}
