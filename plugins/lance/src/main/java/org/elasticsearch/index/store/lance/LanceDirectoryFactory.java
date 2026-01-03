/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.store.lance;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.store.FsDirectoryFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LanceDirectoryFactory extends FsDirectoryFactory {

    @Override
    public Directory newDirectory(IndexSettings indexSettings, ShardPath shardPath) throws IOException {
        Path location = resolveIndexPath(indexSettings, shardPath);
        LockFactory lockFactory = indexSettings.getValue(INDEX_LOCK_FACTOR_SETTING);
        Files.createDirectories(location);
        return newFSDirectory(location, lockFactory, indexSettings);
    }

    private static Path resolveIndexPath(IndexSettings indexSettings, ShardPath shardPath) {
        String configuredPath = indexSettings.getValue(LanceStoreSettings.INDEX_STORE_LANCE_PATH);
        if (Strings.hasText(configuredPath) == false) {
            return shardPath.resolveIndex();
        }
        Path basePath = Path.of(configuredPath);
        if (basePath.isAbsolute() == false) {
            basePath = shardPath.getRootDataPath().resolve(basePath);
        }
        return basePath.resolve(shardPath.getShardId().getIndex().getUUID())
            .resolve(Integer.toString(shardPath.getShardId().id()))
            .resolve(ShardPath.INDEX_FOLDER_NAME);
    }
}
