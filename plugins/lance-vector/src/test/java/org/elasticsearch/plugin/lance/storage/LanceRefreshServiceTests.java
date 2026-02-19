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
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests for LanceRefreshService background dataset refresh.
 */
public class LanceRefreshServiceTests extends ESTestCase {

    private ScheduledExecutorService executorService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        LanceDatasetRegistry.clear();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void tearDown() throws Exception {
        LanceDatasetRegistry.clear();
        executorService.shutdown();
        super.tearDown();
    }

    public void testServiceStartsAndStops() {
        LanceRefreshService service = new LanceRefreshService(executorService);
        service.start();
        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    public void testManualRefreshTrigger() {
        LanceRefreshService service = new LanceRefreshService(executorService);
        service.start();
        service.refreshAll();
        service.stop();
    }

    public void testRefreshAllClearsCachedDatasets() throws IOException {
        LanceRefreshService service = new LanceRefreshService(executorService);
        LanceDataset dataset = new LanceDataset() {
            @Override
            public int dims() {
                return 3;
            }

            @Override
            public List<Candidate> search(float[] query, int numCandidates, String similarity) {
                return List.of();
            }

            @Override
            public List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) {
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
                return "test://refresh";
            }
        };

        LanceDatasetRegistry.get("test://refresh", () -> dataset);
        assertTrue(LanceDatasetRegistry.contains("test://refresh"));

        service.refreshAll();

        assertFalse(LanceDatasetRegistry.contains("test://refresh"));
    }

    public void testSetRefreshInterval() {
        LanceRefreshService service = new LanceRefreshService(executorService);
        service.start();
        service.setRefreshInterval(TimeValue.timeValueSeconds(10));
        assertEquals(TimeValue.timeValueSeconds(10), service.getRefreshInterval());
        service.stop();
    }

    public void testRefreshCycleContinuesAfterRefreshException() throws Exception {
        CountingExecutorService countingExecutor = new CountingExecutorService();
        LanceRefreshService service = new LanceRefreshService(countingExecutor) {
            @Override
            public void refreshAll() {
                throw new RuntimeException("boom");
            }
        };

        service.start();
        assertTrue("First cycle should be scheduled", countingExecutor.runScheduledTask());
        assertTrue("Next cycle should still be scheduled after exception", countingExecutor.runScheduledTask());
        assertEquals("each cycle should execute refresh", 2, countingExecutor.executedTasks());
        service.stop();
    }

    private static class CountingExecutorService extends java.util.concurrent.AbstractExecutorService implements ScheduledExecutorService {
        private final java.util.concurrent.atomic.AtomicBoolean shutdown = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicInteger executedTasks = new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.LinkedBlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown.set(true);
            java.util.ArrayList<Runnable> drained = new java.util.ArrayList<>();
            tasks.drainTo(drained);
            return drained;
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown.get();
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.offer(command);
            return new CompletedScheduledFuture();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Not needed in test");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("Not needed in test");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Not needed in test");
        }

        boolean runScheduledTask() {
            Runnable task = tasks.poll();
            if (task == null) {
                return false;
            }
            executedTasks.incrementAndGet();
            task.run();
            return true;
        }

        int executedTasks() {
            return executedTasks.get();
        }
    }

    private static class CompletedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
