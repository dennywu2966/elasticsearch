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
import org.elasticsearch.plugin.lance.storage.LanceRefreshService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.test.ESTestCase;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.mockito.Mockito.mock;

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

    public void testCreateComponentsStartsRefreshWhenEnabled() throws Exception {
        LanceVectorPlugin plugin = new LanceVectorPlugin(Settings.EMPTY);
        Plugin.PluginServices services = mock(Plugin.PluginServices.class);

        try {
            plugin.createComponents(services);
            LanceRefreshService refreshService = extractRefreshService(plugin);
            assertTrue(refreshService.isRunning());
        } finally {
            plugin.close();
        }
    }

    public void testCreateComponentsDoesNotStartRefreshWhenDisabled() throws Exception {
        Settings settings = Settings.builder().put("lance.refresh.enabled", false).build();
        LanceVectorPlugin plugin = new LanceVectorPlugin(settings);

        Plugin.PluginServices services = mock(Plugin.PluginServices.class);

        try {
            plugin.createComponents(services);
            LanceRefreshService refreshService = extractRefreshService(plugin);
            assertFalse(refreshService.isRunning());
        } finally {
            plugin.close();
        }
    }

    private static LanceRefreshService extractRefreshService(LanceVectorPlugin plugin) throws Exception {
        Field field = LanceVectorPlugin.class.getDeclaredField("refreshService");
        field.setAccessible(true);
        return (LanceRefreshService) field.get(plugin);
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
