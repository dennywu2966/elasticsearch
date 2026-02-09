/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.plugin.lance.mapper.LanceVectorFieldMapper;
import org.elasticsearch.plugin.lance.query.LanceKnnQueryBuilder;
import org.elasticsearch.plugin.lance.query.PreFilterHeuristic;
import org.elasticsearch.plugin.lance.rest.RestLanceRefreshAction;
import org.elasticsearch.plugin.lance.rest.RestLanceStatsAction;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.plugin.lance.storage.LanceRefreshService;
import org.elasticsearch.plugin.lance.storage.RealLanceDataset;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.xcontent.ParseField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class LanceVectorPlugin extends Plugin implements MapperPlugin, SearchPlugin, ActionPlugin {
    private static final Logger logger = LogManager.getLogger(LanceVectorPlugin.class);

    /**
     * Setting to enable Lance profiling for detailed timing information.
     * <p>
     * When enabled, Lance kNN searches will collect detailed timing information
     * for each stage of the search execution pipeline. This timing data is returned
     * in the profile section of the search response when profile=true is set.
     * <p>
     * Default is disabled to minimize overhead in production environments.
     * Profiling adds minimal overhead when disabled (&lt; 1%).
     */
    public static final Setting<Boolean> LANCE_PROFILING_ENABLED = Setting.boolSetting(
        "lance.profiling.enabled",
        false,
        Setting.Property.NodeScope
    );

    /**
     * Setting to enable automatic Lance dataset refresh for near-real-time updates.
     * <p>
     * When enabled, the plugin will periodically check for Lance dataset updates
     * and reload datasets when manifest changes are detected.
     * <p>
     * Default is true.
     */
    public static final Setting<Boolean> LANCE_REFRESH_ENABLED = Setting.boolSetting(
        "lance.refresh.enabled",
        true,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    /**
     * Interval for checking Lance dataset refresh.
     * <p>
     * Controls how often the plugin checks for dataset manifest changes.
     * Lower values detect changes faster but increase overhead.
     * <p>
     * Default is 30 seconds. Minimum is 1 second.
     */
    public static final Setting<TimeValue> LANCE_REFRESH_INTERVAL = Setting.timeSetting(
        "lance.refresh.interval",
        TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(1),
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    private final boolean profilingEnabled;
    private final boolean refreshEnabled;
    private final ScheduledExecutorService refreshExecutor;
    private final LanceRefreshService refreshService;

    public LanceVectorPlugin(Settings settings) {
        this.profilingEnabled = LANCE_PROFILING_ENABLED.get(settings);
        this.refreshEnabled = LANCE_REFRESH_ENABLED.get(settings);
        this.refreshExecutor = Executors.newSingleThreadScheduledExecutor();
        this.refreshService = new LanceRefreshService(refreshExecutor);
        this.refreshService.setRefreshInterval(LANCE_REFRESH_INTERVAL.get(settings));
    }

    @Override
    public Collection<?> createComponents(PluginServices services) {
        if (refreshEnabled) {
            refreshService.start();
        } else {
            logger.info("Lance automatic refresh is disabled via setting {}", LANCE_REFRESH_ENABLED.getKey());
        }
        return List.of();
    }

    @Override
    public List<Setting<?>> getSettings() {
        List<Setting<?>> settings = new ArrayList<>();
        settings.add(LANCE_PROFILING_ENABLED);
        settings.add(PreFilterHeuristic.INDEX_SETTING);
        settings.add(LANCE_REFRESH_ENABLED);
        settings.add(LANCE_REFRESH_INTERVAL);
        return settings;
    }

    /**
     * Check if Lance profiling is enabled.
     *
     * @return true if profiling is enabled, false otherwise
     */
    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        Map<String, Mapper.TypeParser> mappers = new HashMap<>();
        mappers.put(LanceVectorFieldMapper.CONTENT_TYPE, LanceVectorFieldMapper.PARSER);
        return mappers;
    }

    @Override
    public List<QuerySpec<?>> getQueries() {
        return List.of(
            new QuerySpec<>(
                new ParseField(LanceKnnQueryBuilder.NAME),
                LanceKnnQueryBuilder::new,  // Reader from StreamInput
                LanceKnnQueryBuilder::fromXContent  // Parser from XContentParser
            )
        );
    }

    @Override
    public Collection<RestHandler> getRestHandlers(
        Settings settings,
        NamedWriteableRegistry namedWriteableRegistry,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster,
        Predicate<NodeFeature> clusterSupportsFeature
    ) {
        return List.of(new RestLanceStatsAction(), new RestLanceRefreshAction(refreshService));
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing Lance Vector Plugin - cleaning up resources");
        try {
            refreshService.close();
            refreshExecutor.shutdown();
            if (refreshExecutor.awaitTermination(5, TimeUnit.SECONDS) == false) {
                refreshExecutor.shutdownNow();
            }

            // Close all cached datasets to release native resources
            int cacheSize = LanceDatasetRegistry.size();
            if (cacheSize > 0) {
                logger.info("Clearing Lance dataset registry with {} cached datasets", cacheSize);
                LanceDatasetRegistry.clear();
            }
            // Close the shared Arrow allocator to release all native memory
            long allocatedBefore = RealLanceDataset.getAllocatedMemory();
            if (allocatedBefore > 0) {
                logger.info("Closing Arrow allocator with {} bytes allocated", allocatedBefore);
                RealLanceDataset.closeAllocator();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while stopping Lance refresh executor", e);
            throw new IOException("Interrupted while stopping Lance refresh executor", e);
        } catch (Exception e) {
            logger.error("Error closing Lance Vector Plugin resources", e);
            throw e;
        }
    }
}
