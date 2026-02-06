/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.mapper;

import java.util.Objects;
import java.util.Set;

/**
 * Storage configuration for Lance vector fields.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Legacy</b>: single {@code uri} field — all shards share one dataset.</li>
 *   <li><b>Shard-aware</b>: {@code uri_prefix} + {@code shard_path} + {@code dataset_name}
 *       — each shard resolves its own dataset URI at query time.</li>
 * </ul>
 */
public class LanceStorageConfig {
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of("{index}", "{shard_id}");

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
        String datasetName
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
        this(type, uri, idColumn, vectorColumn, ossEndpoint, ossAccessKeyId, ossAccessKeySecret, null, null, null);
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
        String name = datasetName != null ? datasetName : "data.lance";
        if (resolvedPath.isEmpty()) {
            return uriPrefix + "/" + name;
        }
        return uriPrefix + "/" + resolvedPath + "/" + name;
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
}
