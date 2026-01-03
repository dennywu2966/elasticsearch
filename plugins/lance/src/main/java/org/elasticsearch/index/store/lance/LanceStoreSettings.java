/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.store.lance;

import org.elasticsearch.common.settings.Setting;

public final class LanceStoreSettings {

    public static final Setting<Boolean> CLUSTER_STORE_LANCE_ENABLED = Setting.boolSetting(
        "cluster.store.lance.enabled",
        false,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    public static final Setting<String> INDEX_STORE_LANCE_PATH = Setting.simpleString(
        "index.store.lance.path",
        Setting.Property.IndexScope
    );

    private LanceStoreSettings() {}
}
