/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.LicensedFeature;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.lance.action.ImportLanceIndexAction;
import org.elasticsearch.xpack.lance.action.TransportImportLanceIndexAction;
import org.elasticsearch.xpack.lance.index.LanceIndexSettings;
import org.elasticsearch.xpack.lance.mapper.LanceVectorFieldMapper;
import org.elasticsearch.xpack.lance.mapper.LanceVectorQueryBuilder;
import org.elasticsearch.xpack.lance.rest.RestImportLanceIndexAction;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.elasticsearch.index.mapper.FieldMapper.notInMultiFields;

/**
 * Plugin for Lance vector index support in Elasticsearch.
 * <p>
 * This plugin enables:
 * <ul>
 *   <li>New field type "lance_vector" for storing vectors in Lance format</li>
 *   <li>Import and management of existing Lance indices</li>
 *   <li>Vector similarity search using Lance's optimized algorithms</li>
 * </ul>
 */
public class LanceIndexPlugin extends Plugin implements MapperPlugin, ActionPlugin, SearchPlugin {

    public static final LicensedFeature.Momentary LANCE_INDEX_FEATURE = LicensedFeature.momentary(
        null,
        "lance-index",
        License.OperationMode.ENTERPRISE
    );

    private final Settings settings;

    public LanceIndexPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        return Map.of(
            LanceVectorFieldMapper.CONTENT_TYPE,
            new FieldMapper.TypeParser((n, c) -> {
                if (LANCE_INDEX_FEATURE.check(getLicenseState()) == false) {
                    throw LicenseUtils.newComplianceException("Lance Vector Index");
                }
                return new LanceVectorFieldMapper.Builder(n, c.indexVersionCreated());
            }, notInMultiFields(LanceVectorFieldMapper.CONTENT_TYPE))
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return List.of(
            new ActionHandler<>(ImportLanceIndexAction.INSTANCE, TransportImportLanceIndexAction.class)
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
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
        return List.of(
            new RestImportLanceIndexAction()
        );
    }

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(
            LanceIndexSettings.LANCE_INDEX_PATH,
            LanceIndexSettings.LANCE_CACHE_SIZE,
            LanceIndexSettings.LANCE_PRELOAD_ON_STARTUP,
            LanceIndexSettings.LANCE_SYNC_INTERVAL,
            LanceIndexSettings.LANCE_VECTOR_COLUMN
        );
    }

    protected XPackLicenseState getLicenseState() {
        return XPackPlugin.getSharedLicenseState();
    }

    @Override
    public Collection<?> createComponents(PluginServices services) {
        return List.of();
    }

    @Override
    public List<QuerySpec<?>> getQueries() {
        return List.of(
            new QuerySpec<>(
                LanceVectorQueryBuilder.NAME,
                LanceVectorQueryBuilder::new,
                LanceVectorQueryBuilder::fromXContent
            )
        );
    }
}
