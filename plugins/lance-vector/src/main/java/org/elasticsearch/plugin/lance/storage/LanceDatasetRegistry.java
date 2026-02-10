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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Unified registry for Lance datasets with caching support.
 * <p>
 * This registry manages cached production datasets and coordinates loading.
 * <p>
 * Thread-safe: Uses Elasticsearch's Cache with automatic eviction to prevent
 * unbounded memory growth. Datasets are evicted based on LRU policy when the
 * cache reaches its maximum size.
 * <p>
 * <b>Concurrency Model:</b> Uses per-URI {@link CompletableFuture} coordination
 * to ensure only one load operation is in-flight for a given URI at any time.
 */
public class LanceDatasetRegistry {
    private static final Logger logger = LogManager.getLogger(LanceDatasetRegistry.class);

    // Maximum number of datasets to cache
    private static final int MAX_CACHED_DATASETS = 100;

    // Time after which cached datasets are eligible for eviction
    private static final TimeValue CACHE_TTL = TimeValue.timeValueHours(1);

    // Use Elasticsearch's Cache with automatic eviction
    private static volatile Cache<String, LanceDataset> CACHE;

    // Tracks in-flight per-URI load operations to prevent duplicate loads.
    private static final ConcurrentHashMap<String, CompletableFuture<LanceDataset>> LOADING_DATASETS = new ConcurrentHashMap<>();

    // Guards refresh invalidation (write) against active query/search execution (read).
    private static final ReentrantReadWriteLock QUERY_REFRESH_GUARD = new ReentrantReadWriteLock();
    private static final long DEFAULT_SEARCH_LOCK_TIMEOUT_MILLIS = TimeValue.timeValueSeconds(30).millis();
    private static final String SEARCH_LOCK_TIMEOUT_SYS_PROP = "es.lance.search_lock_timeout_millis";

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
                LOADING_DATASETS.remove(uri);
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
     * Thread-safe: in-flight loads are coordinated via {@link CompletableFuture}
     * so all concurrent callers for the same URI share a single load operation.
     *
     * @param uri    Dataset URI (used as cache key)
     * @param loader Supplier that loads the dataset if not cached
     * @return The cached or newly loaded dataset
     * @throws IOException if the loader fails
     */
    public static LanceDataset get(String uri, Supplier<LanceDataset> loader) throws IOException {
        Cache<String, LanceDataset> cache = getCache();

        LanceDataset cached = cache.get(uri);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<LanceDataset> newLoad = new CompletableFuture<>();
        CompletableFuture<LanceDataset> existingLoad = LOADING_DATASETS.putIfAbsent(uri, newLoad);

        if (existingLoad == null) {
            try {
                LanceDataset loaded = cache.get(uri);
                if (loaded != null) {
                    newLoad.complete(loaded);
                    return loaded;
                }
                logger.debug("Loading dataset into registry: {}", uri);
                LanceDataset dataset = loader.get();
                cache.put(uri, dataset);
                newLoad.complete(dataset);
                return dataset;
            } catch (Throwable t) {
                newLoad.completeExceptionally(t);
                if (t instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (t instanceof Error error) {
                    throw error;
                }
                throw new IOException("Failed to load dataset: " + uri, t);
            } finally {
                LOADING_DATASETS.remove(uri, newLoad);
            }
        }

        try {
            return existingLoad.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Failed to load dataset: " + uri, cause);
        }
    }

    /**
     * Get a dataset from the registry, loading it if necessary.
     * <p>
     * Loads real Lance datasets in production paths.
     * <ul>
     *   <li>Object storage URIs (oss://) ending with .lance or containing .lance/</li>
     *   <li>Local file paths ending with .lance or containing .lance/</li>
     *   <li>URIs starting with embedded: are test fixtures backed by FakeLanceDataset</li>
     * </ul>
     * <p>
     * Non-Lance URIs fail fast to avoid silently switching to mock/test fixtures.
     *
     * @param uri Dataset URI
     * @param dims Expected vector dimensions
     * @param config Configuration for real datasets
     * @return The dataset
     * @throws IOException if loading fails
     */
    public static LanceDataset getOrLoad(String uri, int dims, LanceDatasetConfig config) throws IOException {
        if (uri != null && uri.startsWith("embedded:")) {
            return get(uri, () -> {
                try {
                    return FakeLanceDataset.load(uri, dims);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load embedded test dataset: " + uri, e);
                }
            });
        }

        if (isLanceFormat(uri) == false) {
            if (allowTestJsonFallback(uri)) {
                logger.warn("Using test-only JSON Lance fallback for URI [{}]", uri);
                return get(uri, () -> {
                    try {
                        return FakeLanceDataset.load(uri, dims);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load test JSON dataset: " + uri, e);
                    }
                });
            }
            throw new IOException("Unsupported Lance dataset URI [" + uri + "]. Expected a .lance dataset path.");
        }

        LanceDatasetConfig effectiveConfig = config;
        if (effectiveConfig.expectedDims() == 0 && dims > 0) {
            effectiveConfig = new LanceDatasetConfig(
                effectiveConfig.idColumn(),
                effectiveConfig.vectorColumn(),
                dims,
                effectiveConfig.ossEndpoint(),
                effectiveConfig.ossAccessKeyId(),
                effectiveConfig.ossAccessKeySecret()
            );
        }

        LanceDatasetConfig finalConfig = effectiveConfig;
        return get(uri, () -> {
            try {
                return RealLanceDataset.open(uri, finalConfig);
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
     * to be considered Lance datasets.
     * <p>
     * <b>Note:</b> S3 URIs are currently treated as unsupported in this plugin.
     *
     * @param uri Dataset URI to check
     * @return true if the URI should use RealLanceDataset, false for test JSON files
     */
    public static boolean isLanceFormat(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }

        // S3 is not yet supported in this plugin.
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
     * accessing the dataset. Dataset close is handled by the cache removal listener.
     *
     * @param uri Dataset URI to invalidate
     */
    public static void invalidate(String uri) {
        withRefreshLock(() -> {
            Cache<String, LanceDataset> cache = getCache();
            logger.debug("Invalidating dataset from registry: {}", uri);
            cache.invalidate(uri);
            LOADING_DATASETS.remove(uri);
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
            LOADING_DATASETS.clear();
        });
    }

    /**
     * Execute a search-path action while blocking refresh invalidation.
     */
    public static <T> T withSearchLock(IOAction<T> action) throws IOException {
        Lock readLock = QUERY_REFRESH_GUARD.readLock();
        long timeoutMillis = searchLockTimeoutMillis();
        boolean acquired = false;
        try {
            acquired = readLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (acquired == false) {
                throw new IOException("Timed out acquiring Lance search lock after " + timeoutMillis + "ms");
            }
            return action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Lance search lock", e);
        } finally {
            if (acquired) {
                readLock.unlock();
            }
        }
    }

    private static long searchLockTimeoutMillis() {
        long configured = Long.getLong(SEARCH_LOCK_TIMEOUT_SYS_PROP, DEFAULT_SEARCH_LOCK_TIMEOUT_MILLIS);
        return configured > 0 ? configured : DEFAULT_SEARCH_LOCK_TIMEOUT_MILLIS;
    }

    private static boolean allowTestJsonFallback(String uri) {
        if (Boolean.getBoolean("es.lance.allow_file_json_fallback_for_tests") == false) {
            return false;
        }
        if (uri == null || uri.endsWith(".json") == false) {
            return false;
        }
        return uri.startsWith("file://") || uri.startsWith("/") || uri.matches("^[A-Za-z]:\\\\.*");
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
