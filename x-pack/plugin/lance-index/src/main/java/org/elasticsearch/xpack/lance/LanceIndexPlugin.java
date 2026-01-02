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
import org.elasticsearch.index.mapper.vectors.VectorsFormatProvider;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.LicensedFeature;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.internal.InternalVectorFormatProviderPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.lance.action.ImportLanceIndexAction;
import org.elasticsearch.xpack.lance.action.TransportImportLanceIndexAction;
import org.elasticsearch.xpack.lance.codec.LanceVectorsFormat;
import org.elasticsearch.xpack.lance.index.LanceIndexSettings;
import org.elasticsearch.xpack.lance.rest.RestImportLanceIndexAction;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Plugin for Lance vector index support in Elasticsearch.
 * <p>
 * This plugin enables using Lance format as a backend for standard dense_vector fields,
 * providing full compatibility with ES knn query syntax while offering:
 * <ul>
 *   <li>High performance: Lance's IVF-PQ and IVF-HNSW algorithms</li>
 *   <li>Cost efficiency: Lower storage costs compared to Lucene HNSW</li>
 *   <li>Import existing Lance indices without data migration</li>
 * </ul>
 * <p>
 * Usage - Enable Lance backend for an index:
 * <pre>
 * PUT /my_index
 * {
 *   "settings": {
 *     "index.lance.enabled": true,
 *     "index.lance.path": "/path/to/lance/data",  // Optional: for existing Lance data
 *     "index.lance.nprobes": 20,                   // IVF search parameter
 *     "index.lance.refine_factor": 10              // Reranking factor
 *   },
 *   "mappings": {
 *     "properties": {
 *       "my_vector": {
 *         "type": "dense_vector",
 *         "dims": 128,
 *         "index": true,
 *         "similarity": "cosine"
 *       }
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * Then use standard knn query syntax:
 * <pre>
 * GET /my_index/_search
 * {
 *   "knn": {
 *     "field": "my_vector",
 *     "query_vector": [0.1, 0.2, ...],
 *     "k": 10,
 *     "num_candidates": 100
 *   }
 * }
 * </pre>
 * <p>
 * Import existing Lance dataset:
 * <pre>
 * POST /_lance/import
 * {
 *   "index_name": "my_vectors",
 *   "lance_path": "/path/to/existing/lance/dataset",
 *   "vector_column": "embedding"
 * }
 * </pre>
 */
public class LanceIndexPlugin extends Plugin implements ActionPlugin, InternalVectorFormatProviderPlugin {

    public static final LicensedFeature.Momentary LANCE_INDEX_FEATURE = LicensedFeature.momentary(
        "vector-search",
        "lance-index",
        License.OperationMode.ENTERPRISE
    );

    private final Settings settings;

    public LanceIndexPlugin(Settings settings) {
        this.settings = settings;
    }

    /**
     * Provides Lance-based KnnVectorsFormat for indices with Lance enabled.
     * This integrates seamlessly with ES's dense_vector field type and knn queries.
     */
    @Override
    public VectorsFormatProvider getVectorsFormatProvider() {
        return (indexSettings, indexOptions, similarity, elementType) -> {
            // Check if Lance is enabled for this index
            if (LanceIndexSettings.LANCE_ENABLED.get(indexSettings.getSettings()) == false) {
                return null; // Fall back to default format
            }

            // License check
            if (LANCE_INDEX_FEATURE.check(getLicenseState()) == false) {
                throw LicenseUtils.newComplianceException(LANCE_INDEX_FEATURE.getName());
            }

            // Get Lance-specific settings
            String lancePath = LanceIndexSettings.LANCE_INDEX_PATH.get(indexSettings.getSettings());
            String vectorColumn = LanceIndexSettings.LANCE_VECTOR_COLUMN.get(indexSettings.getSettings());
            int nprobes = LanceIndexSettings.LANCE_IVF_NPROBES.get(indexSettings.getSettings());
            int refineFactor = LanceIndexSettings.LANCE_REFINE_FACTOR.get(indexSettings.getSettings());

            // Return Lance vectors format
            return new LanceVectorsFormat(
                lancePath,
                vectorColumn,
                similarity,
                elementType,
                nprobes,
                refineFactor
            );
        };
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
            LanceIndexSettings.LANCE_ENABLED,
            LanceIndexSettings.LANCE_INDEX_PATH,
            LanceIndexSettings.LANCE_CACHE_SIZE,
            LanceIndexSettings.LANCE_PRELOAD_ON_STARTUP,
            LanceIndexSettings.LANCE_SYNC_INTERVAL,
            LanceIndexSettings.LANCE_VECTOR_COLUMN,
            LanceIndexSettings.LANCE_IVF_NPROBES,
            LanceIndexSettings.LANCE_REFINE_FACTOR,
            LanceIndexSettings.LANCE_READ_ONLY,
            LanceIndexSettings.LANCE_DISTANCE_TYPE
        );
    }

    protected XPackLicenseState getLicenseState() {
        return XPackPlugin.getSharedLicenseState();
    }

    @Override
    public Collection<?> createComponents(PluginServices services) {
        return List.of();
    }
}
