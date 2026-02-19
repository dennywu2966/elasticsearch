/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.plugin.lance.storage.LanceRefreshService;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.mock;

/**
 * Tests for RestLanceRefreshAction manual refresh endpoint.
 */
public class RestLanceRefreshActionTests extends ESTestCase {

    private LanceRefreshService refreshService;
    private RestLanceRefreshAction action;
    private ScheduledExecutorService executorService;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executorService = Executors.newSingleThreadScheduledExecutor();
        refreshService = new LanceRefreshService(executorService);
        action = new RestLanceRefreshAction(refreshService);
    }

    public void testRoutes() {
        assertEquals(1, action.routes().size());
        assertEquals(RestRequest.Method.POST, action.routes().get(0).getMethod());
        assertEquals("/_lance/refresh", action.routes().get(0).getPath());
    }

    public void testGetName() {
        assertEquals("lance_refresh_action", action.getName());
    }

    public void testPrepareRequestDoesNotThrow() {
        NodeClient client = mock(NodeClient.class);
        RestRequest request = mock(RestRequest.class);

        // Verify prepareRequest returns a non-null consumer
        assertNotNull(action.prepareRequest(request, client));
    }

    @Override
    public void tearDown() throws Exception {
        executorService.shutdown();
        super.tearDown();
    }
}
