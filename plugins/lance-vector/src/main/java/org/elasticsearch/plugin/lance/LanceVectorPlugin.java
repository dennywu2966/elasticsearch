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
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.plugin.lance.mapper.LanceVectorFieldMapper;
import org.elasticsearch.plugin.lance.query.LanceKnnQueryBuilder;
import org.elasticsearch.plugin.lance.query.PreFilterHeuristic;
import org.elasticsearch.plugin.lance.rest.RestLanceStatsAction;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
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

    private final boolean profilingEnabled;

    public LanceVectorPlugin(Settings settings) {
        this.profilingEnabled = LANCE_PROFILING_ENABLED.get(settings);
    }

    @Override
    public List<Setting<?>> getSettings() {
        List<Setting<?>> settings = new ArrayList<>();
        settings.add(LANCE_PROFILING_ENABLED);
        settings.add(PreFilterHeuristic.INDEX_SETTING);
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
        return List.of(new RestLanceStatsAction());
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing Lance Vector Plugin - cleaning up resources");
        try {
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
        } catch (Exception e) {
            logger.error("Error closing Lance Vector Plugin resources", e);
            throw e;
        }
    }
}
