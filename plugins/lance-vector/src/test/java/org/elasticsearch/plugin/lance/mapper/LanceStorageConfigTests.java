/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.mapper;

import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for {@link LanceStorageConfig}.
 */
public class LanceStorageConfigTests extends ESTestCase {

    // --- Legacy mode tests (backward compatibility) ---

    public void testConstructorAndAccessors() {
        LanceStorageConfig config = new LanceStorageConfig("external", "file:///path/to/dataset", "_id", "vector", null, null, null);

        assertThat(config.type(), equalTo("external"));
        assertThat(config.uri(), equalTo("file:///path/to/dataset"));
        assertThat(config.idColumn(), equalTo("_id"));
        assertThat(config.vectorColumn(), equalTo("vector"));
        assertFalse(config.isShardAware());
    }

    public void testConstructorWithOssUri() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            "oss://my-bucket/vectors/dataset.lance",
            "doc_id",
            "embedding",
            null,
            null,
            null
        );

        assertThat(config.type(), equalTo("external"));
        assertThat(config.uri(), equalTo("oss://my-bucket/vectors/dataset.lance"));
        assertThat(config.idColumn(), equalTo("doc_id"));
        assertThat(config.vectorColumn(), equalTo("embedding"));
    }

    public void testConstructorWithEmbeddedUri() {
        LanceStorageConfig config = new LanceStorageConfig("external", "embedded:test-vectors.json", "_id", "vector", null, null, null);

        assertThat(config.uri(), equalTo("embedded:test-vectors.json"));
    }

    public void testConstructorRequiresType() {
        expectThrows(NullPointerException.class, () -> new LanceStorageConfig(null, "file:///path", "_id", "vector", null, null, null));
    }

    public void testConstructorRequiresUri() {
        expectThrows(NullPointerException.class, () -> new LanceStorageConfig("external", null, "_id", "vector", null, null, null));
    }

    public void testConstructorRequiresIdColumn() {
        expectThrows(
            NullPointerException.class,
            () -> new LanceStorageConfig("external", "file:///path", null, "vector", null, null, null)
        );
    }

    public void testConstructorRequiresVectorColumn() {
        expectThrows(NullPointerException.class, () -> new LanceStorageConfig("external", "file:///path", "_id", null, null, null, null));
    }

    public void testDifferentConfigurations() {
        LanceStorageConfig config1 = new LanceStorageConfig("external", "file:///path1", "_id", "vector", null, null, null);

        LanceStorageConfig config2 = new LanceStorageConfig("local", "file:///path2", "id", "embeddings", null, null, null);

        assertThat(config1.type(), equalTo("external"));
        assertThat(config2.type(), equalTo("local"));
        assertThat(config1.uri(), equalTo("file:///path1"));
        assertThat(config2.uri(), equalTo("file:///path2"));
    }

    // --- Shard-aware mode tests ---

    public void testResolveUriWithShardTemplate() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            "{index}/shard-{shard_id}",
            "vectors.lance"
        );
        assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/prod/my-index/shard-0/vectors.lance"));
        assertThat(config.resolveUri("my-index", 2), equalTo("oss://bucket/prod/my-index/shard-2/vectors.lance"));
    }

    public void testResolveUriFallsBackToLegacyUri() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            "oss://bucket/data.lance",
            "_id",
            "vector",
            null,
            null,
            null,
            null,
            null,
            null
        );
        assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/data.lance"));
        assertThat(config.resolveUri("my-index", 5), equalTo("oss://bucket/data.lance"));
    }

    public void testIsShardAware() {
        LanceStorageConfig sharded = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            "{index}/shard-{shard_id}",
            "vectors.lance"
        );
        assertTrue(sharded.isShardAware());

        LanceStorageConfig legacy = new LanceStorageConfig(
            "external",
            "oss://bucket/data.lance",
            "_id",
            "vector",
            null,
            null,
            null,
            null,
            null,
            null
        );
        assertFalse(legacy.isShardAware());
    }

    public void testResolveUriRejectsUnknownPlaceholder() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket",
            "{index}/{unknown}",
            "data.lance"
        );
        expectThrows(IllegalArgumentException.class, () -> config.resolveUri("idx", 0));
    }

    public void testResolveUriWithNullShardPathDefaultsToEmpty() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            null,
            "vectors.lance"
        );
        assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/prod/vectors.lance"));
    }

    public void testResolveUriWithNullDatasetNameDefaultsToDataLance() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            "{index}/shard-{shard_id}",
            null
        );
        assertThat(config.resolveUri("my-index", 0), equalTo("oss://bucket/prod/my-index/shard-0/data.lance"));
    }

    public void testShardAwareAccessors() {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            "{index}/shard-{shard_id}",
            "vectors.lance"
        );
        assertThat(config.uriPrefix(), equalTo("oss://bucket/prod"));
        assertThat(config.shardPath(), equalTo("{index}/shard-{shard_id}"));
        assertThat(config.datasetName(), equalTo("vectors.lance"));
        assertNull(config.uri());
    }

    public void testConstructorRejectsNullUriWhenNotShardAware() {
        expectThrows(
            NullPointerException.class,
            () -> new LanceStorageConfig("external", null, "_id", "vector", null, null, null, null, null, null)
        );
    }
}
