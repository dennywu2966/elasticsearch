/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.mapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

/**
 * Storage configuration for Lance vector fields.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Legacy</b>: single {@code uri} field — all shards share one dataset.</li>
 *   <li><b>Shard-aware</b>: {@code uri_prefix} + {@code shard_path} + {@code dataset_name}
 *       — each shard resolves its own dataset URI at query time.</li>
 * </ul>
 * <p>
 * The {@link ShardingStrategy} controls how Lance search candidates are filtered to match
 * the current ES shard. This is critical for performance: when ES and Lance use compatible
 * sharding, we can filter candidates aggressively using ES's routing algorithm.
 */
public class LanceStorageConfig {
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of("{index}", "{shard_id}");

    /**
     * Strategy for filtering Lance search candidates based on ES sharding.
     * <p>
     * This controls how Lance search results are mapped to ES shards. The correct strategy
     * depends on how the Lance dataset was sharded during creation.
     */
    public enum ShardingStrategy {
        /**
         * No filtering - return all candidates from Lance search.
         * Use this when:
         * <ul>
         *   <li>The Lance dataset is not sharded (single dataset for all shards)</li>
         *   <li>You're unsure about the sharding algorithm</li>
         *   <li>The dataset uses a custom sharding scheme not matching ES</li>
         * </ul>
         */
        NONE,

        /**
         * Filter candidates using ES's Murmur3 hash function.
         * This guarantees a 1:1 mapping between ES shards and Lance dataset shards
         * when the Lance dataset was created using the same algorithm.
         * <p>
         * ES uses: {@code shardId = Math.floorMod(Murmur3HashFunction.hash(document_id), numShards)}
         * <p>
         * Use this when your Lance dataset is sharded using the same Murmur3 algorithm.
         * This is the default for shard-aware storage configurations.
         */
        ES_ROUTING
    }

    private final String type;
    private final String uri;
    private final String idColumn;
    private final String vectorColumn;
    private final String ossEndpoint;
    private final String ossAccessKeyId;
    private final String ossAccessKeySecret;

    // Shard-aware fields (all nullable for backward compat)
    private final String uriPrefix;
    private final String shardPath;
    private final String datasetName;
    private final int numShards; // Number of shards in the index (for candidate filtering)
    private final ShardingStrategy shardingStrategy; // How to filter candidates based on sharding

    // Filter pushdown fields
    private final Map<String, String> fieldMapping; // ES field name -> Lance column name mapping

    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret,
        String uriPrefix,
        String shardPath,
        String datasetName,
        int numShards,
        ShardingStrategy shardingStrategy,
        Map<String, String> fieldMapping
    ) {
        this.type = Objects.requireNonNull(type);
        this.idColumn = Objects.requireNonNull(idColumn);
        this.vectorColumn = Objects.requireNonNull(vectorColumn);
        this.ossEndpoint = ossEndpoint;
        this.ossAccessKeyId = ossAccessKeyId;
        this.ossAccessKeySecret = ossAccessKeySecret;
        this.uriPrefix = uriPrefix;
        this.shardPath = shardPath;
        this.datasetName = datasetName;
        this.numShards = numShards > 0 ? numShards : 1; // Default to 1 if not set
        // Default to ES_ROUTING for shard-aware configs, NONE for legacy
        this.shardingStrategy = shardingStrategy != null
            ? shardingStrategy
            : (uriPrefix != null ? ShardingStrategy.ES_ROUTING : ShardingStrategy.NONE);
        this.fieldMapping = fieldMapping == null ? null : Collections.unmodifiableMap(new java.util.HashMap<>(fieldMapping));

        if (uriPrefix != null) {
            this.uri = null; // Shard-aware mode; uri resolved at query time
        } else {
            this.uri = Objects.requireNonNull(uri, "Either [storage.uri] or [storage.uri_prefix] is required");
        }
    }

    /** Backward-compatible constructor for legacy single-URI mode. */
    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret
    ) {
        this(
            type,
            uri,
            idColumn,
            vectorColumn,
            ossEndpoint,
            ossAccessKeyId,
            ossAccessKeySecret,
            null,
            null,
            null,
            1,
            ShardingStrategy.NONE,
            null
        );
    }

    /** Backward-compatible constructor for legacy single-URI mode with numShards. */
    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret,
        int numShards
    ) {
        this(
            type,
            uri,
            idColumn,
            vectorColumn,
            ossEndpoint,
            ossAccessKeyId,
            ossAccessKeySecret,
            null,
            null,
            null,
            numShards,
            ShardingStrategy.NONE,
            null
        );
    }

    /** Backward-compatible constructor for shard-aware mode (12 parameters, before fieldMapping was added). */
    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret,
        String uriPrefix,
        String shardPath,
        String datasetName,
        int numShards,
        ShardingStrategy shardingStrategy
    ) {
        this(
            type,
            uri,
            idColumn,
            vectorColumn,
            ossEndpoint,
            ossAccessKeyId,
            ossAccessKeySecret,
            uriPrefix,
            shardPath,
            datasetName,
            numShards,
            shardingStrategy,
            null
        );
    }

    /** Backward-compatible constructor for legacy single-URI mode with numShards and fieldMapping. */
    public LanceStorageConfig(
        String type,
        String uri,
        String idColumn,
        String vectorColumn,
        String ossEndpoint,
        String ossAccessKeyId,
        String ossAccessKeySecret,
        int numShards,
        Map<String, String> fieldMapping
    ) {
        this(
            type,
            uri,
            idColumn,
            vectorColumn,
            ossEndpoint,
            ossAccessKeyId,
            ossAccessKeySecret,
            null,
            null,
            null,
            numShards,
            ShardingStrategy.NONE,
            fieldMapping
        );
    }

    /** Returns true if this config uses shard-aware URI templates. */
    public boolean isShardAware() {
        return uriPrefix != null;
    }

    /**
     * Resolve the concrete dataset URI for a given index and shard.
     * In legacy mode, returns the static URI regardless of shard.
     * In shard-aware mode, builds: {uriPrefix}/{shardPath with placeholders resolved}/{datasetName}
     */
    public String resolveUri(String indexName, int shardId) {
        if (isShardAware() == false) {
            return uri;
        }
        String resolvedPath = shardPath;
        if (resolvedPath != null) {
            // Validate no unknown placeholders
            String remaining = resolvedPath.replaceAll("\\{index}", "").replaceAll("\\{shard_id}", "");
            if (remaining.contains("{")) {
                throw new IllegalArgumentException(
                    "Unknown placeholder in shard_path [" + shardPath + "]. Allowed: " + ALLOWED_PLACEHOLDERS
                );
            }
            resolvedPath = resolvedPath.replace("{index}", indexName).replace("{shard_id}", String.valueOf(shardId));
        } else {
            resolvedPath = "";
        }
        String name = datasetName;

        // Build URI, avoiding double slashes
        StringBuilder result = new StringBuilder(uriPrefix);
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '/') {
            result.append('/');
        }
        if (!resolvedPath.isEmpty()) {
            result.append(resolvedPath);
            // If resolvedPath ends with .lance, it IS the dataset root, don't add datasetName
            // If resolvedPath doesn't end with .lance, we need to append datasetName
            if (!resolvedPath.endsWith(".lance")) {
                if (!resolvedPath.endsWith("/")) {
                    result.append('/');
                }
                // Default to "data.lance" if datasetName is null
                String datasetToAppend = (name != null && !name.isEmpty()) ? name : "data.lance";
                result.append(datasetToAppend);
            }
            // If name is explicitly set but resolvedPath ends with .lance, this is a configuration error
            else if (name != null && !name.isEmpty()) {
                throw new IllegalArgumentException(
                    "Cannot specify dataset_name when shard_path already ends with .lance. "
                        + "Either remove dataset_name or remove .lance suffix from shard_path."
                );
            }
        } else if (name != null && !name.isEmpty()) {
            if (!result.isEmpty() && result.charAt(result.length() - 1) == '/') {
                result.append(name);
            } else {
                result.append('/').append(name);
            }
        }

        return result.toString();
    }

    public String type() {
        return type;
    }

    public String uri() {
        return uri;
    }

    public String idColumn() {
        return idColumn;
    }

    public String vectorColumn() {
        return vectorColumn;
    }

    public String ossEndpoint() {
        return ossEndpoint;
    }

    public String ossAccessKeyId() {
        return ossAccessKeyId;
    }

    public String ossAccessKeySecret() {
        return ossAccessKeySecret;
    }

    public String uriPrefix() {
        return uriPrefix;
    }

    public String shardPath() {
        return shardPath;
    }

    public String datasetName() {
        return datasetName;
    }

    /**
     * Returns the number of shards in the index.
     * This is used for filtering Lance search candidates to match ES's document routing.
     */
    public int getNumShards() {
        return numShards;
    }

    /**
     * Returns the sharding strategy for filtering Lance search candidates.
     */
    public ShardingStrategy getShardingStrategy() {
        return shardingStrategy;
    }

    /**
     * Returns the field mapping for ES field names to Lance column names.
     * <p>
     * This mapping is used for native filter pushdown: ES term queries are converted
     * to Lance SQL WHERE clauses using this mapping.
     * <p>
     * Example: {"category": "product_category", "brand": "brand_name"}
     * <p>
     * Returns null if no mapping is configured (filter pushdown disabled).
     *
     * @return Map of ES field names to Lance column names, or null
     */
    public Map<String, String> getFieldMapping() {
        return fieldMapping;
    }
}
