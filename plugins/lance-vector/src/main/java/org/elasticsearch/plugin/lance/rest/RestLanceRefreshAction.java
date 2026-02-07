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
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

/**
 * REST handler for manual Lance dataset refresh trigger.
 * <p>
 * Allows manual triggering of the Lance dataset refresh cycle without waiting
 * for the scheduled automatic refresh. Useful for forcing immediate updates
 * after bulk document operations.
 * <p>
 * Usage:
 * <pre>POST /_lance/refresh</pre>
 * <p>
 * Response example:
 * <pre>
 * {
 *   "acknowledged": true,
 *   "message": "Lance dataset refresh triggered"
 * }
 * </pre>
 */
public class RestLanceRefreshAction extends BaseRestHandler {

    private final LanceRefreshService refreshService;

    public RestLanceRefreshAction(LanceRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Override
    public String getName() {
        return "lance_refresh_action";
    }

    @Override
    public List<RestHandler.Route> routes() {
        return List.of(new RestHandler.Route(RestRequest.Method.POST, "/_lance/refresh"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> {
            try {
                refreshService.refreshAll();

                XContentBuilder builder = channel.newBuilder();
                builder.startObject();
                builder.field("acknowledged", true);
                builder.field("message", "Lance dataset refresh triggered");
                builder.endObject();

                channel.sendResponse(new RestResponse(RestStatus.OK, builder));
            } catch (Exception e) {
                channel.sendResponse(new RestResponse(RestStatus.INTERNAL_SERVER_ERROR, e.getMessage()));
            }
        };
    }
}
