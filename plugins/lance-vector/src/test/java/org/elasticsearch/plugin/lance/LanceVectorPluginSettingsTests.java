/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.test.ESTestCase;

import java.util.Collection;

/**
 * Tests for LanceVectorPlugin cluster-level settings.
 */
public class LanceVectorPluginSettingsTests extends ESTestCase {

    public void testDefaultRefreshInterval() {
        Settings settings = Settings.EMPTY;
        TimeValue interval = LanceVectorPlugin.LANCE_REFRESH_INTERVAL.get(settings);
        assertEquals(TimeValue.timeValueSeconds(30), interval);
    }

    public void testCustomRefreshInterval() {
        Settings settings = Settings.builder().put("lance.refresh.interval", "10s").build();
        TimeValue interval = LanceVectorPlugin.LANCE_REFRESH_INTERVAL.get(settings);
        assertEquals(TimeValue.timeValueSeconds(10), interval);
    }

    public void testRefreshEnabled() {
        Settings settings = Settings.EMPTY;
        assertTrue(LanceVectorPlugin.LANCE_REFRESH_ENABLED.get(settings));
    }

    public void testRefreshDisabled() {
        Settings settings = Settings.builder().put("lance.refresh.enabled", false).build();
        assertFalse(LanceVectorPlugin.LANCE_REFRESH_ENABLED.get(settings));
    }

    public void testRefreshRestHandlerIsRegistered() {
        LanceVectorPlugin plugin = new LanceVectorPlugin(Settings.EMPTY);
        try {
            Collection<RestHandler> handlers = plugin.getRestHandlers(null, null, null, null, null, null, null, null, null);
            assertEquals(2, handlers.size());
            assertTrue(containsPath(handlers, "/_lance/stats"));
            assertTrue(containsPath(handlers, "/_lance/refresh"));
        } finally {
            try {
                plugin.close();
            } catch (Exception e) {
                fail("plugin close failed: " + e.getMessage());
            }
        }
    }

    private static boolean containsPath(Collection<RestHandler> handlers, String path) {
        for (RestHandler handler : handlers) {
            if (handler instanceof BaseRestHandler base) {
                for (RestHandler.Route route : base.routes()) {
                    if (path.equals(route.getPath())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
