/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.store.lance;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.store.lance.LanceDirectoryFactory;
import org.elasticsearch.index.store.lance.LanceStoreSettings;
import org.elasticsearch.plugins.IndexStorePlugin;
import org.elasticsearch.plugins.Plugin;

import java.util.List;
import java.util.Map;

public class LanceStorePlugin extends Plugin implements IndexStorePlugin {

    private final Settings settings;
    private final boolean defaultLanceEnabled;

    public LanceStorePlugin(Settings settings) {
        this.settings = settings;
        this.defaultLanceEnabled = LanceStoreSettings.CLUSTER_STORE_LANCE_ENABLED.get(settings);
    }

    @Override
    public Map<String, DirectoryFactory> getDirectoryFactories() {
        return Map.of("lance", new LanceDirectoryFactory());
    }

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(LanceStoreSettings.CLUSTER_STORE_LANCE_ENABLED, LanceStoreSettings.INDEX_STORE_LANCE_PATH);
    }

    @Override
    public Settings additionalSettings() {
        if (defaultLanceEnabled && IndexModule.INDEX_STORE_TYPE_SETTING.exists(settings) == false) {
            return Settings.builder().put(IndexModule.INDEX_STORE_TYPE_SETTING.getKey(), "lance").build();
        }
        return Settings.EMPTY;
    }
}
