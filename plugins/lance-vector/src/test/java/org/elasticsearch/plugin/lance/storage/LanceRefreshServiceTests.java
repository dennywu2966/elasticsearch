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
}
