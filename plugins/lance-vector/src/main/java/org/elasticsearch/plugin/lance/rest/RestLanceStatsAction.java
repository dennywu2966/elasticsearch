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
import org.elasticsearch.plugin.lance.query.LanceSearchMetrics;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.plugin.lance.storage.RealLanceDataset;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

/**
 * REST handler for Lance vector plugin statistics and health information.
 * <p>
 * Provides observability into the Lance dataset cache, memory usage, and health status.
 * <p>
 * Usage:
 * <pre>GET /_lance/stats</pre>
 * <p>
 * Response example:
 * <pre>
 * {
 *   "cache": {
 *     "size": 5,
 *     "max_size": 100,
 *     "ttl_minutes": 60
 *   },
 *   "memory": {
 *     "allocated_bytes": 52428800,
 *     "allocated_mb": 50
 *   },
 *   "health": "GREEN"
 * }
 * </pre>
 */
public class RestLanceStatsAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "lance_stats_action";
    }

    @Override
    public List<RestHandler.Route> routes() {
        return List.of(new RestHandler.Route(RestRequest.Method.GET, "/_lance/stats"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> {
            try {
                XContentBuilder builder = channel.newBuilder();
                buildStatsResponse(builder);
                channel.sendResponse(new RestResponse(RestStatus.OK, builder));
            } catch (IOException e) {
                channel.sendResponse(new RestResponse(RestStatus.INTERNAL_SERVER_ERROR, e.getMessage()));
            }
        };
    }

    private void buildStatsResponse(XContentBuilder builder) throws IOException {
        // Get cache statistics
        int cacheSize = LanceDatasetRegistry.size();
        int maxCacheSize = 100; // From LanceDatasetRegistry.MAX_DATASETS
        long ttlMinutes = 60; // From LanceDatasetRegistry.CACHE_TTL_MINUTES

        // Get memory statistics
        long allocatedBytes = RealLanceDataset.getAllocatedMemory();

        // Determine health status
        String health = calculateHealth(cacheSize, maxCacheSize, allocatedBytes);

        builder.startObject();
        {
            // Cache stats
            builder.startObject("cache");
            builder.field("size", cacheSize);
            builder.field("max_size", maxCacheSize);
            builder.field("ttl_minutes", ttlMinutes);
            builder.endObject();

            // Memory stats
            builder.startObject("memory");
            builder.field("allocated_bytes", allocatedBytes);
            builder.field("allocated_mb", allocatedBytes / (1024 * 1024));
            builder.endObject();

            // Health status
            builder.field("health", health);

            // Search metrics
            builder.startObject("search");
            builder.field("total_searches", LanceSearchMetrics.getTotalSearches());
            builder.field("total_search_time_ms", LanceSearchMetrics.getTotalSearchTimeNanos() / 1_000_000);
            builder.field("filtered_searches", LanceSearchMetrics.getFilteredSearches());
            builder.field("pre_filter_searches", LanceSearchMetrics.getPreFilterSearches());
            builder.field("post_filter_searches", LanceSearchMetrics.getPostFilterSearches());
            builder.field("search_errors", LanceSearchMetrics.getSearchErrors());
            builder.endObject();
        }
        builder.endObject();
    }

    private String calculateHealth(int cacheSize, int maxCacheSize, long allocatedBytes) {
        // Health determination logic
        if (cacheSize >= maxCacheSize) {
            return "YELLOW"; // Cache full, may evict soon
        }
        if (allocatedBytes > 500_000_000L) { // 500 MB threshold
            return "YELLOW"; // High memory usage
        }
        return "GREEN"; // Healthy
    }
}
