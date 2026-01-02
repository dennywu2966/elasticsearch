/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.lance.action.ImportLanceIndexAction;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestRequest.Method.PUT;

/**
 * REST handler for importing Lance indices.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>POST /_lance/import - Import a Lance index into Elasticsearch</li>
 *   <li>PUT /_lance/{index}/import - Import a Lance index with a specific name</li>
 * </ul>
 * <p>
 * Example request:
 * <pre>
 * POST /_lance/import
 * {
 *   "index_name": "my_vectors",
 *   "lance_path": "/path/to/lance/dataset",
 *   "vector_column": "embedding",
 *   "id_column": "doc_id",
 *   "shards": 1,
 *   "replicas": 1
 * }
 * </pre>
 */
@ServerlessScope(Scope.INTERNAL)
public class RestImportLanceIndexAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "import_lance_index_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(
            new Route(POST, "/_lance/import"),
            new Route(PUT, "/_lance/{index}/import")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();

        // Get index name from path or body
        String indexFromPath = restRequest.param("index");
        if (indexFromPath != null) {
            request.setIndexName(indexFromPath);
        }

        // Parse request body
        if (restRequest.hasContent()) {
            Map<String, Object> source = XContentHelper.convertToMap(
                restRequest.content(),
                false,
                XContentType.JSON
            ).v2();

            if (source.containsKey("index_name") && indexFromPath == null) {
                request.setIndexName((String) source.get("index_name"));
            }

            if (source.containsKey("lance_path")) {
                request.setLancePath((String) source.get("lance_path"));
            }

            if (source.containsKey("vector_column")) {
                request.setVectorColumn((String) source.get("vector_column"));
            }

            if (source.containsKey("id_column")) {
                request.setIdColumn((String) source.get("id_column"));
            }

            if (source.containsKey("create_index")) {
                request.setCreateIndex((Boolean) source.get("create_index"));
            }

            if (source.containsKey("shards")) {
                request.setShards(((Number) source.get("shards")).intValue());
            }

            if (source.containsKey("replicas")) {
                request.setReplicas(((Number) source.get("replicas")).intValue());
            }

            if (source.containsKey("mappings")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mappings = (Map<String, Object>) source.get("mappings");
                request.setAdditionalMappings(mappings);
            }
        }

        return channel -> client.execute(
            ImportLanceIndexAction.INSTANCE,
            request,
            new RestToXContentListener<>(channel)
        );
    }
}
