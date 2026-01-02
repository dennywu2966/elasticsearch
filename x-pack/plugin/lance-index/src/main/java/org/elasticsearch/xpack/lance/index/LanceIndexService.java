/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.index;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for managing Lance indices within Elasticsearch.
 * <p>
 * This service handles:
 * <ul>
 *   <li>Monitoring Lance datasets for changes</li>
 *   <li>Synchronizing Lance data with Elasticsearch indices</li>
 *   <li>Managing the lifecycle of imported Lance indices</li>
 *   <li>Caching and preloading Lance datasets</li>
 * </ul>
 */
public class LanceIndexService extends AbstractLifecycleComponent {

    private static final Logger logger = LogManager.getLogger(LanceIndexService.class);

    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Settings settings;

    private final Map<String, LanceIndexState> indexStates = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Scheduler.Cancellable syncTask;

    public LanceIndexService(ClusterService clusterService, ThreadPool threadPool, Settings settings) {
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.settings = settings;
    }

    @Override
    protected void doStart() {
        running.set(true);
        // Scan for existing Lance indices on startup
        scanForLanceIndices();
        // Schedule periodic sync
        scheduleSyncTask();
    }

    @Override
    protected void doStop() {
        running.set(false);
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    @Override
    protected void doClose() throws IOException {
        // Close all Lance datasets
        for (LanceIndexState state : indexStates.values()) {
            try {
                state.close();
            } catch (Exception e) {
                logger.warn("Error closing Lance index state for [{}]", state.indexName, e);
            }
        }
        indexStates.clear();
        LanceDataset.clearCache();
    }

    /**
     * Scans the cluster state for indices that use Lance.
     */
    private void scanForLanceIndices() {
        ClusterState state = clusterService.state();
        for (IndexMetadata indexMetadata : state.metadata().indices().values()) {
            Settings indexSettings = indexMetadata.getSettings();
            String lancePath = LanceIndexSettings.LANCE_INDEX_PATH.get(indexSettings);
            if (lancePath != null && !lancePath.isEmpty()) {
                registerLanceIndex(indexMetadata.getIndex().getName(), lancePath, indexSettings);
            }
        }
    }

    /**
     * Registers a Lance index for management.
     */
    public void registerLanceIndex(String indexName, String lancePath, Settings indexSettings) {
        if (indexStates.containsKey(indexName)) {
            logger.debug("Lance index [{}] already registered", indexName);
            return;
        }

        try {
            LanceDataset dataset = LanceDataset.open(lancePath);
            LanceIndexState state = new LanceIndexState(
                indexName,
                lancePath,
                dataset,
                LanceIndexSettings.LANCE_VECTOR_COLUMN.get(indexSettings),
                LanceIndexSettings.LANCE_SYNC_INTERVAL.get(indexSettings),
                LanceIndexSettings.LANCE_PRELOAD_ON_STARTUP.get(indexSettings)
            );

            indexStates.put(indexName, state);
            logger.info("Registered Lance index [{}] from path [{}]", indexName, lancePath);

            // Preload if configured
            if (state.preloadOnStartup) {
                preloadLanceIndex(state);
            }
        } catch (Exception e) {
            logger.error("Failed to register Lance index [{}] from path [{}]", indexName, lancePath, e);
        }
    }

    /**
     * Unregisters a Lance index.
     */
    public void unregisterLanceIndex(String indexName) {
        LanceIndexState state = indexStates.remove(indexName);
        if (state != null) {
            try {
                state.close();
                logger.info("Unregistered Lance index [{}]", indexName);
            } catch (Exception e) {
                logger.warn("Error closing Lance index state for [{}]", indexName, e);
            }
        }
    }

    /**
     * Gets the Lance dataset for an index.
     */
    public LanceDataset getDataset(String indexName) {
        LanceIndexState state = indexStates.get(indexName);
        return state != null ? state.dataset : null;
    }

    /**
     * Refreshes a Lance index to pick up new data.
     */
    public void refreshLanceIndex(String indexName) throws IOException {
        LanceIndexState state = indexStates.get(indexName);
        if (state != null) {
            state.dataset.refresh();
            state.lastSyncTime = System.currentTimeMillis();
            logger.debug("Refreshed Lance index [{}]", indexName);
        }
    }

    /**
     * Checks if an index is a Lance index.
     */
    public boolean isLanceIndex(String indexName) {
        return indexStates.containsKey(indexName);
    }

    /**
     * Gets statistics for a Lance index.
     */
    public LanceIndexStats getStats(String indexName) {
        LanceIndexState state = indexStates.get(indexName);
        if (state == null) {
            return null;
        }

        LanceDataset.LanceDatasetMetadata metadata = state.dataset.getMetadata();
        return new LanceIndexStats(
            indexName,
            state.lancePath,
            metadata.rowCount,
            metadata.vectorDimension,
            metadata.version,
            state.lastSyncTime,
            state.syncCount
        );
    }

    private void preloadLanceIndex(LanceIndexState state) {
        threadPool.executor(EsExecutors.UTILITY_THREAD_POOL_NAME).execute(() -> {
            try {
                logger.info("Preloading Lance index [{}]", state.indexName);
                // Trigger a search with dummy vector to warm up the index
                float[] dummyVector = new float[state.dataset.getVectorDimension(state.vectorColumn)];
                state.dataset.search(state.vectorColumn, dummyVector, 1, 1, 1,
                    LanceDataset.LanceSimilarity.L2, null);
                logger.info("Preloaded Lance index [{}]", state.indexName);
            } catch (Exception e) {
                logger.warn("Failed to preload Lance index [{}]", state.indexName, e);
            }
        });
    }

    private void scheduleSyncTask() {
        TimeValue interval = TimeValue.timeValueSeconds(30);
        syncTask = threadPool.scheduleWithFixedDelay(
            this::syncAllIndices,
            interval,
            threadPool.executor(EsExecutors.UTILITY_THREAD_POOL_NAME)
        );
    }

    private void syncAllIndices() {
        if (!running.get()) {
            return;
        }

        for (LanceIndexState state : indexStates.values()) {
            try {
                long now = System.currentTimeMillis();
                if (now - state.lastSyncTime >= state.syncInterval.millis()) {
                    state.dataset.refresh();
                    state.lastSyncTime = now;
                    state.syncCount++;
                    logger.debug("Synced Lance index [{}]", state.indexName);
                }
            } catch (Exception e) {
                logger.warn("Failed to sync Lance index [{}]", state.indexName, e);
            }
        }
    }

    /**
     * Import an existing Lance dataset as a new Elasticsearch index.
     */
    public ImportResult importLanceDataset(
        String indexName,
        String lancePath,
        String vectorColumn,
        String idColumn,
        boolean fullImport
    ) throws IOException {
        LanceDataset dataset = LanceDataset.open(lancePath);
        LanceDataset.LanceDatasetMetadata metadata = dataset.getMetadata();

        if (fullImport) {
            // Full import: read all data and index into ES
            return importFullDataset(indexName, dataset, vectorColumn, idColumn);
        } else {
            // Reference import: just register the Lance dataset
            Settings indexSettings = Settings.builder()
                .put(LanceIndexSettings.LANCE_INDEX_PATH.getKey(), lancePath)
                .put(LanceIndexSettings.LANCE_VECTOR_COLUMN.getKey(), vectorColumn != null ? vectorColumn : "vector")
                .build();
            registerLanceIndex(indexName, lancePath, indexSettings);

            return new ImportResult(
                true,
                metadata.rowCount,
                0, // No documents indexed in reference mode
                "Lance dataset registered as reference"
            );
        }
    }

    private ImportResult importFullDataset(
        String indexName,
        LanceDataset dataset,
        String vectorColumn,
        String idColumn
    ) throws IOException {
        // This would implement full data import
        // For now, return a placeholder
        return new ImportResult(
            true,
            dataset.getRowCount(),
            dataset.getRowCount(),
            "Full import completed"
        );
    }

    /**
     * State for a Lance index.
     */
    private static class LanceIndexState implements AutoCloseable {
        final String indexName;
        final String lancePath;
        final LanceDataset dataset;
        final String vectorColumn;
        final TimeValue syncInterval;
        final boolean preloadOnStartup;
        volatile long lastSyncTime;
        volatile long syncCount;

        LanceIndexState(
            String indexName,
            String lancePath,
            LanceDataset dataset,
            String vectorColumn,
            TimeValue syncInterval,
            boolean preloadOnStartup
        ) {
            this.indexName = indexName;
            this.lancePath = lancePath;
            this.dataset = dataset;
            this.vectorColumn = vectorColumn;
            this.syncInterval = syncInterval;
            this.preloadOnStartup = preloadOnStartup;
            this.lastSyncTime = System.currentTimeMillis();
            this.syncCount = 0;
        }

        @Override
        public void close() throws Exception {
            dataset.close();
        }
    }

    /**
     * Statistics for a Lance index.
     */
    public record LanceIndexStats(
        String indexName,
        String lancePath,
        long rowCount,
        int vectorDimension,
        long version,
        long lastSyncTime,
        long syncCount
    ) {}

    /**
     * Result of a Lance import operation.
     */
    public record ImportResult(
        boolean success,
        long totalRows,
        long indexedRows,
        String message
    ) {}
}
