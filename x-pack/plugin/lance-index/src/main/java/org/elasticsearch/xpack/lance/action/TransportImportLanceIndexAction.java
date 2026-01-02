/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.lance.index.LanceDataset;
import org.elasticsearch.xpack.lance.index.LanceIndexSettings;
import org.elasticsearch.xpack.lance.mapper.LanceVectorFieldMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Transport action for importing Lance indices into Elasticsearch.
 * <p>
 * This action:
 * <ol>
 *   <li>Opens the Lance dataset and reads its schema</li>
 *   <li>Creates an Elasticsearch index with appropriate mappings</li>
 *   <li>Configures the lance_vector field to point to the Lance data</li>
 * </ol>
 */
public class TransportImportLanceIndexAction extends TransportMasterNodeAction<
    ImportLanceIndexAction.Request,
    ImportLanceIndexAction.Response> {

    private final Client client;

    @Inject
    public TransportImportLanceIndexAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Client client
    ) {
        super(
            ImportLanceIndexAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            ImportLanceIndexAction.Request::new,
            indexNameExpressionResolver,
            ImportLanceIndexAction.Response::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.client = client;
    }

    @Override
    protected void masterOperation(
        Task task,
        ImportLanceIndexAction.Request request,
        ClusterState state,
        ActionListener<ImportLanceIndexAction.Response> listener
    ) throws Exception {
        // Open the Lance dataset to read metadata
        LanceDataset dataset;
        try {
            dataset = LanceDataset.open(request.getLancePath());
        } catch (IOException e) {
            listener.onFailure(new IllegalArgumentException(
                "Failed to open Lance dataset at path [" + request.getLancePath() + "]: " + e.getMessage(), e
            ));
            return;
        }

        LanceDataset.LanceDatasetMetadata metadata = dataset.getMetadata();
        String vectorColumn = request.getVectorColumn() != null ? request.getVectorColumn() : "vector";
        int vectorDims = dataset.getVectorDimension(vectorColumn);

        if (request.isCreateIndex()) {
            // Build index settings
            Settings.Builder settingsBuilder = Settings.builder()
                .put("index.number_of_shards", request.getShards())
                .put("index.number_of_replicas", request.getReplicas())
                .put(LanceIndexSettings.LANCE_INDEX_PATH.getKey(), request.getLancePath())
                .put(LanceIndexSettings.LANCE_VECTOR_COLUMN.getKey(), vectorColumn)
                .put(LanceIndexSettings.LANCE_READ_ONLY.getKey(), true);

            // Build mappings
            Map<String, Object> mappings = buildMappings(
                vectorColumn,
                vectorDims,
                request.getIdColumn(),
                metadata.columns,
                request.getAdditionalMappings()
            );

            CreateIndexRequest createIndexRequest = new CreateIndexRequest(request.getIndexName())
                .settings(settingsBuilder)
                .mapping(mappings);

            client.admin().indices().create(createIndexRequest, new ActionListener<CreateIndexResponse>() {
                @Override
                public void onResponse(CreateIndexResponse createIndexResponse) {
                    listener.onResponse(new ImportLanceIndexAction.Response(
                        createIndexResponse.isAcknowledged(),
                        request.getIndexName(),
                        request.getLancePath(),
                        metadata.rowCount,
                        vectorDims,
                        metadata.columns,
                        "Successfully imported Lance index"
                    ));
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        } else {
            // Index already exists, just return the metadata
            listener.onResponse(new ImportLanceIndexAction.Response(
                true,
                request.getIndexName(),
                request.getLancePath(),
                metadata.rowCount,
                vectorDims,
                metadata.columns,
                "Lance index validated successfully"
            ));
        }
    }

    private Map<String, Object> buildMappings(
        String vectorColumn,
        int vectorDims,
        String idColumn,
        String[] columns,
        Map<String, Object> additionalMappings
    ) {
        Map<String, Object> properties = new HashMap<>();

        // Add lance_vector field for the vector column
        Map<String, Object> vectorField = new HashMap<>();
        vectorField.put("type", LanceVectorFieldMapper.CONTENT_TYPE);
        vectorField.put("dims", vectorDims);
        vectorField.put("similarity", "l2");
        vectorField.put("vector_column", vectorColumn);
        properties.put(vectorColumn, vectorField);

        // Add ID field if specified
        if (idColumn != null) {
            Map<String, Object> idField = new HashMap<>();
            idField.put("type", "keyword");
            properties.put(idColumn, idField);
        }

        // Add other columns as appropriate field types
        for (String column : columns) {
            if (column.equals(vectorColumn) || column.equals(idColumn)) {
                continue;
            }
            if (!properties.containsKey(column)) {
                // Default to keyword for unknown columns
                Map<String, Object> field = new HashMap<>();
                field.put("type", "keyword");
                properties.put(column, field);
            }
        }

        // Merge additional mappings
        if (additionalMappings != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> additionalProperties = (Map<String, Object>) additionalMappings.get("properties");
            if (additionalProperties != null) {
                properties.putAll(additionalProperties);
            }
        }

        Map<String, Object> mappings = new HashMap<>();
        mappings.put("properties", properties);

        // Add _source configuration
        Map<String, Object> source = new HashMap<>();
        source.put("enabled", true);
        mappings.put("_source", source);

        return mappings;
    }

    @Override
    protected ClusterBlockException checkBlock(ImportLanceIndexAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
