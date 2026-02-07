/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tests for LanceRefreshService background dataset refresh.
 */
public class LanceRefreshServiceTests extends ESTestCase {

    private ScheduledExecutorService executorService;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void tearDown() throws Exception {
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

    public void testSetRefreshInterval() {
        LanceRefreshService service = new LanceRefreshService(executorService);
        service.start();
        service.setRefreshInterval(TimeValue.timeValueSeconds(10));
        assertEquals(TimeValue.timeValueSeconds(10), service.getRefreshInterval());
        service.stop();
    }
}
