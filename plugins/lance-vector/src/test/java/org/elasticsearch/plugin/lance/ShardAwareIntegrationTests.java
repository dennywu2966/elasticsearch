/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance;

import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.plugin.lance.storage.FakeLanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

/**
 * Integration test: validates that shard-aware URI resolution produces
 * independent search results per shard.
 */
public class ShardAwareIntegrationTests extends ESTestCase {

    @Override
    public void tearDown() throws Exception {
        LanceDatasetRegistry.clear();
        super.tearDown();
    }

    public void testShardAwareResolvesDistinctDatasets() throws Exception {
        // Each shard resolves to a different URI
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "embedded:shard-test",
            "shard-{shard_id}",
            null
        );

        String uri0 = config.resolveUri("test-index", 0);
        String uri1 = config.resolveUri("test-index", 1);

        assertThat(uri0, not(equalTo(uri1)));
        assertTrue(uri0.contains("shard-0"));
        assertTrue(uri1.contains("shard-1"));
    }

    public void testLegacyModeAllShardsShareSameUri() throws Exception {
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            "embedded:org/elasticsearch/plugin/lance/shared-data.json",
            "_id",
            "vector",
            null,
            null,
            null
        );

        assertThat(config.resolveUri("test-index", 0), equalTo("embedded:org/elasticsearch/plugin/lance/shared-data.json"));
        assertThat(config.resolveUri("test-index", 1), equalTo("embedded:org/elasticsearch/plugin/lance/shared-data.json"));
        assertThat(config.resolveUri("test-index", 99), equalTo("embedded:org/elasticsearch/plugin/lance/shared-data.json"));
    }

    public void testShardAwareLoadsDistinctDatasets() throws Exception {
        // Load each shard's dataset and verify they have different data
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "embedded:shard-test",
            "shard-{shard_id}",
            null
        );

        String uri0 = config.resolveUri("test-index", 0);
        String uri1 = config.resolveUri("test-index", 1);

        FakeLanceDataset dataset0 = FakeLanceDataset.load(uri0, 3);
        FakeLanceDataset dataset1 = FakeLanceDataset.load(uri1, 3);

        // Each dataset should have 2 entries
        List<LanceDataset.Candidate> results0 = dataset0.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");
        List<LanceDataset.Candidate> results1 = dataset1.search(new float[] { 0.0f, 1.0f, 0.0f }, 10, "cosine");

        assertThat(results0.size(), greaterThan(0));
        assertThat(results1.size(), greaterThan(0));

        // Verify results are different (doc1/doc3 in shard-0, doc2/doc4 in shard-1)
        assertThat(results0.get(0).id(), equalTo("doc1"));
        assertThat(results1.get(0).id(), equalTo("doc2"));
    }

    public void testShardAwareWithIndexPlaceholder() throws Exception {
        // Test that {index} placeholder is correctly resolved
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "embedded:shard-test",
            "{index}/shard-{shard_id}",
            null
        );

        String uri0 = config.resolveUri("my-index", 0);
        String uri1 = config.resolveUri("my-index", 1);
        String uriOther = config.resolveUri("other-index", 0);

        assertThat(uri0, not(equalTo(uri1)));
        assertTrue(uri0.contains("my-index"));
        assertTrue(uri0.contains("shard-0"));
        assertTrue(uri1.contains("my-index"));
        assertTrue(uri1.contains("shard-1"));
        assertTrue(uriOther.contains("other-index"));
        assertTrue(uriOther.contains("shard-0"));
    }
}
