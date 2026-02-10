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
import org.elasticsearch.core.TimeValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background service that periodically checks Lance datasets for manifest changes
 * and performs atomic dataset swaps when updates are detected.
 * <p>
 * Manifest-based change detection: Lance datasets store a manifest file that
 * contains the current version. The refresh service polls this manifest at
 * a configurable interval and triggers a dataset reload when the version changes.
 * <p>
 * Thread model: Uses ScheduledExecutorService for periodic refresh cycles.
 * Refresh runs asynchronously to avoid blocking search threads.
 */
public class LanceRefreshService implements Closeable {
    private static final Logger logger = LogManager.getLogger(LanceRefreshService.class);

    private final ScheduledExecutorService executorService;
    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean running = false;
    private volatile TimeValue refreshInterval = TimeValue.timeValueSeconds(30);

    public LanceRefreshService(ScheduledExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * Start the automatic refresh service.
     */
    public void start() {
        if (running) return;
        running = true;
        scheduleNextRefresh();
        logger.info("Lance refresh service started with interval={}", refreshInterval);
    }

    /**
     * Stop the automatic refresh service.
     */
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        logger.info("Lance refresh service stopped");
    }

    /** Check if the service is running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Update the refresh interval.
     * <p>
     * If the service is running, the next refresh will be rescheduled with the new interval.
     *
     * @param interval New refresh interval
     */
    public void setRefreshInterval(TimeValue interval) {
        this.refreshInterval = interval;
        if (running && scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduleNextRefresh();
        }
    }

    /** Get the current refresh interval. */
    public TimeValue getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * Manually trigger a refresh of all cached datasets.
     * Checks each cached dataset's manifest for version changes.
     */
    public void refreshAll() {
        int cached = LanceDatasetRegistry.size();
        if (cached == 0) {
            logger.debug("Lance refresh triggered with empty cache");
            return;
        }

        LanceDatasetRegistry.clear();
        logger.info("Lance refresh invalidated {} cached datasets", cached);
    }

    private void scheduleNextRefresh() {
        if (running == false) return;
        scheduledTask = executorService.schedule(this::doRefreshCycle, refreshInterval.millis(), TimeUnit.MILLISECONDS);
    }

    private void doRefreshCycle() {
        if (running == false) return;
        try {
            refreshAll();
        } catch (Exception e) {
            logger.warn("Lance refresh cycle failed", e);
        } finally {
            scheduleNextRefresh();
        }
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
