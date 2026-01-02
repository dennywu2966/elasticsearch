/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.index;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.TimeValue;

/**
 * Settings for Lance index integration.
 */
public final class LanceIndexSettings {

    private LanceIndexSettings() {}

    /**
     * Enable Lance backend for dense_vector fields in this index.
     * When enabled, vector data will be stored and searched using Lance format
     * instead of Lucene's native HNSW.
     */
    public static final Setting<Boolean> LANCE_ENABLED = Setting.boolSetting(
        "index.lance.enabled",
        false,
        Setting.Property.IndexScope,
        Setting.Property.Final
    );

    /**
     * The path to the Lance index directory on disk.
     * This can be a local path or a URI (s3://, gs://, az://).
     */
    public static final Setting<String> LANCE_INDEX_PATH = Setting.simpleString(
        "index.lance.path",
        "",
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * The name of the vector column in the Lance dataset.
     * Defaults to "vector".
     */
    public static final Setting<String> LANCE_VECTOR_COLUMN = Setting.simpleString(
        "index.lance.vector_column",
        "vector",
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * Cache size for Lance index data in memory.
     */
    public static final Setting<ByteSizeValue> LANCE_CACHE_SIZE = Setting.byteSizeSetting(
        "index.lance.cache_size",
        ByteSizeValue.ofMb(256),
        ByteSizeValue.ZERO,
        ByteSizeValue.ofGb(64),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * Whether to preload Lance index data on startup.
     */
    public static final Setting<Boolean> LANCE_PRELOAD_ON_STARTUP = Setting.boolSetting(
        "index.lance.preload_on_startup",
        false,
        Setting.Property.IndexScope
    );

    /**
     * Sync interval for Lance index updates.
     */
    public static final Setting<TimeValue> LANCE_SYNC_INTERVAL = Setting.timeSetting(
        "index.lance.sync_interval",
        TimeValue.timeValueSeconds(30),
        TimeValue.timeValueSeconds(1),
        TimeValue.timeValueHours(24),
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * Default number of probes for IVF search.
     */
    public static final Setting<Integer> LANCE_IVF_NPROBES = Setting.intSetting(
        "index.lance.ivf_nprobes",
        20,
        1,
        1000,
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * Refinement factor for approximate search.
     */
    public static final Setting<Integer> LANCE_REFINE_FACTOR = Setting.intSetting(
        "index.lance.refine_factor",
        10,
        1,
        100,
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * Whether the Lance index is read-only (for imported indices).
     */
    public static final Setting<Boolean> LANCE_READ_ONLY = Setting.boolSetting(
        "index.lance.read_only",
        false,
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );

    /**
     * The distance metric to use for vector similarity.
     * Supported values: l2, cosine, dot
     */
    public static final Setting<String> LANCE_DISTANCE_TYPE = Setting.simpleString(
        "index.lance.distance_type",
        "l2",
        value -> {
            if (value != null && !value.isEmpty()) {
                if (!value.equals("l2") && !value.equals("cosine") && !value.equals("dot")) {
                    throw new IllegalArgumentException(
                        "Invalid distance_type [" + value + "]. Supported values: l2, cosine, dot"
                    );
                }
            }
        },
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );
}
