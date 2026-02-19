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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;

/**
 * Integration test: validates that shard-aware URI resolution produces
 * independent search results per shard.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>URI resolution with placeholders ({index}, {shard_id})</li>
 *   <li>Legacy mode backward compatibility</li>
 *   <li>Dataset loading and search results</li>
 *   <li>Registry caching behavior</li>
 *   <li>Cross-index isolation</li>
 *   <li>Edge cases and error handling</li>
 * </ul>
 */
public class ShardAwareIntegrationTests extends ESTestCase {

    @Override
    public void tearDown() throws Exception {
        LanceDatasetRegistry.clear();
        super.tearDown();
    }

    // ==================== Basic URI Resolution Tests ====================

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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
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

    // ==================== Dataset Loading Tests ====================

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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
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

    public void testShardAwareReturnsCorrectVectorsPerShard() throws Exception {
        // Verify that query results match the expected shard data
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Shard 0 should return doc1 (vector [1.0, 0.0, 0.0])
        String uri0 = config.resolveUri("test-index", 0);
        FakeLanceDataset dataset0 = FakeLanceDataset.load(uri0, 3);
        List<LanceDataset.Candidate> results0 = dataset0.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");

        assertThat(results0.get(0).id(), equalTo("doc1"));
        // Perfect match should have score 1.0
        assertThat(results0.get(0).score(), equalTo(1.0f));

        // Shard 1 should return doc2 (vector [0.0, 1.0, 0.0])
        String uri1 = config.resolveUri("test-index", 1);
        FakeLanceDataset dataset1 = FakeLanceDataset.load(uri1, 3);
        List<LanceDataset.Candidate> results1 = dataset1.search(new float[] { 0.0f, 1.0f, 0.0f }, 10, "cosine");

        assertThat(results1.get(0).id(), equalTo("doc2"));
        // Perfect match should have score 1.0 (the fixture score 0.9 is just metadata)
        assertThat(results1.get(0).score(), equalTo(1.0f));
    }

    // ==================== Registry Caching Tests ====================

    public void testDatasetRegistryCachesByUri() throws Exception {
        // Verify that loading the same URI twice returns consistent results
        // (The registry caches datasets internally)
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri0 = config.resolveUri("test-index", 0);

        // Load twice - should return consistent results
        FakeLanceDataset dataset1 = FakeLanceDataset.load(uri0, 3);
        FakeLanceDataset dataset2 = FakeLanceDataset.load(uri0, 3);

        // Both should return the same search results
        List<LanceDataset.Candidate> results1 = dataset1.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");
        List<LanceDataset.Candidate> results2 = dataset2.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");

        assertThat(results1.size(), equalTo(results2.size()));
        assertThat(results1.get(0).id(), equalTo(results2.get(0).id()));
    }

    public void testDatasetRegistrySeparatesByUri() throws Exception {
        // Verify that different URIs load different datasets
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri0 = config.resolveUri("test-index", 0);
        String uri1 = config.resolveUri("test-index", 1);

        FakeLanceDataset dataset0 = FakeLanceDataset.load(uri0, 3);
        FakeLanceDataset dataset1 = FakeLanceDataset.load(uri1, 3);

        // Should return different results (different shards have different data)
        List<LanceDataset.Candidate> results0 = dataset0.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");
        List<LanceDataset.Candidate> results1 = dataset1.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");

        // Shard-0 has doc1 with vector [1.0, 0.0, 0.0]
        assertThat(results0.get(0).id(), equalTo("doc1"));
        // Shard-1 doesn't have that vector, so results differ
        assertThat(results1.get(0).id(), not(equalTo("doc1")));
    }

    // ==================== Cross-Index Isolation Tests ====================

    public void testCrossIndexIsolation() throws Exception {
        // Verify that different indices with same shard ID get different datasets
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uriIndexA = config.resolveUri("index-a", 0);
        String uriIndexB = config.resolveUri("index-b", 0);

        // URIs should be different
        assertThat(uriIndexA, not(equalTo(uriIndexB)));
        assertTrue(uriIndexA.contains("index-a"));
        assertTrue(uriIndexB.contains("index-b"));
    }

    public void testSameIndexDifferentShardsAreIsolated() throws Exception {
        // Verify that same index with different shards get different datasets
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri0 = config.resolveUri("my-index", 0);
        String uri1 = config.resolveUri("my-index", 1);
        String uri2 = config.resolveUri("my-index", 2);

        // All URIs should be different
        assertThat(uri0, not(equalTo(uri1)));
        assertThat(uri0, not(equalTo(uri2)));
        assertThat(uri1, not(equalTo(uri2)));
    }

    // ==================== Edge Cases Tests ====================

    public void testLargeShardIdValues() throws Exception {
        // Test with large shard ID values (e.g., in clusters with many shards)
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri999 = config.resolveUri("test-index", 999);
        assertTrue(uri999.contains("shard-999"));

        String uri1000 = config.resolveUri("test-index", 1000);
        assertTrue(uri1000.contains("shard-1000"));
    }

    public void testSpecialCharactersInIndexName() throws Exception {
        // Test with special characters in index name
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Test with hyphen and underscore
        String uri = config.resolveUri("my-test_index", 0);
        assertTrue(uri.contains("my-test_index"));
        assertTrue(uri.contains("shard-0"));
    }

    public void testNullShardPathDefaultsToEmpty() throws Exception {
        // Test that null shard_path is handled correctly
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "embedded:shard-test",
            null,
            "custom.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("test-index", 0);
        // Should resolve to uri_prefix + "/" + dataset_name
        assertThat(uri, equalTo("embedded:shard-test/custom.lance"));
    }

    public void testNullDatasetNameDefaultsToDataLance() throws Exception {
        // Test that null dataset_name defaults to data.lance
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("test-index", 0);
        assertTrue(uri.contains("shard-0"));
        assertTrue(uri.endsWith("/data.lance"));
    }

    public void testBothPlaceholdersInSamePath() throws Exception {
        // Test using both {index} and {shard_id} in the same path
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket",
            "datasets/{index}/shard-{shard_id}",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("my-index", 5);
        assertThat(uri, equalTo("oss://bucket/datasets/my-index/shard-5/vectors.lance"));
    }

    public void testNestedShardPath() throws Exception {
        // Test with nested directory structure in shard_path
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket/prod",
            "{index}/data/shard-{shard_id}/v1",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("products", 2);
        assertThat(uri, equalTo("oss://bucket/prod/products/data/shard-2/v1/vectors.lance"));
    }

    // ==================== OSS URI Tests ====================

    public void testOssUriResolution() throws Exception {
        // Test that OSS URIs are resolved correctly
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://my-bucket/production",
            "{index}/shard-{shard_id}",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("products", 0);
        assertThat(uri, equalTo("oss://my-bucket/production/products/shard-0/vectors.lance"));

        String uri2 = config.resolveUri("products", 5);
        assertThat(uri2, equalTo("oss://my-bucket/production/products/shard-5/vectors.lance"));
    }

    public void testLegacyOssUriReturnsSameForAllShards() throws Exception {
        // Test that legacy OSS URIs work the same for all shards
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            "oss://bucket/dataset.lance",
            "_id",
            "vector",
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE
        );

        assertThat(config.resolveUri("test", 0), equalTo("oss://bucket/dataset.lance"));
        assertThat(config.resolveUri("test", 1), equalTo("oss://bucket/dataset.lance"));
        assertThat(config.resolveUri("test", 99), equalTo("oss://bucket/dataset.lance"));
    }

    // ==================== Error Handling Tests ====================

    public void testInvalidPlaceholderThrowsException() throws Exception {
        // Test that unknown placeholders throw an exception
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket",
            "{index}/{unknown_placeholder}",
            "data.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> config.resolveUri("test", 0));

        assertThat(e.getMessage(), containsString("Unknown placeholder"));
    }

    public void testEmptyShardPathIsHandled() throws Exception {
        // Test that empty shard_path is handled correctly
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket",
            "",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("test", 0);
        assertThat(uri, equalTo("oss://bucket/vectors.lance"));
    }

    // ==================== Mode Detection Tests ====================

    public void testIsShardAwareDetection() throws Exception {
        // Test shard-aware mode detection
        LanceStorageConfig shardAware = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://bucket",
            "shard-{shard_id}",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        assertTrue(shardAware.isShardAware());

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
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE
        );

        assertFalse(legacy.isShardAware());
    }

    // ==================== Multiple Datasets Tests ====================

    public void testMultipleDatasetsCanBeLoadedSimultaneously() throws Exception {
        // Test that multiple datasets can be loaded and used simultaneously
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
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Load all shards
        String uri0 = config.resolveUri("test", 0);
        String uri1 = config.resolveUri("test", 1);

        FakeLanceDataset dataset0 = FakeLanceDataset.load(uri0, 3);
        FakeLanceDataset dataset1 = FakeLanceDataset.load(uri1, 3);

        // Both should be usable
        assertNotNull(dataset0);
        assertNotNull(dataset1);

        List<LanceDataset.Candidate> results0 = dataset0.search(new float[] { 1.0f, 0.0f, 0.0f }, 10, "cosine");
        List<LanceDataset.Candidate> results1 = dataset1.search(new float[] { 0.0f, 1.0f, 0.0f }, 10, "cosine");

        assertThat(results0.size(), greaterThan(0));
        assertThat(results1.size(), greaterThan(0));
    }

    // ==================== Real OSS Integration Tests ====================
    // These tests validate shard-aware URI resolution with real OSS credentials

    private static final String OSS_ACCESS_KEY_ID = System.getProperty(
        "oss.access.key.id",
        System.getenv().getOrDefault("OSS_ACCESS_KEY_ID", "")
    );
    private static final String OSS_ACCESS_KEY_SECRET = System.getProperty(
        "oss.access.key.secret",
        System.getenv().getOrDefault("OSS_ACCESS_KEY_SECRET", "")
    );
    private static final String OSS_ENDPOINT = System.getProperty(
        "oss.endpoint",
        System.getenv().getOrDefault("OSS_ENDPOINT", "oss-ap-southeast-1.aliyuncs.com")
    );
    private static final String OSS_TEST_BUCKET = System.getProperty(
        "oss.test.bucket",
        System.getenv().getOrDefault("OSS_TEST_BUCKET", "denny-test-lance")
    );

    public void testOssShardAwareUriResolution() throws Exception {
        // Test shard-aware URI resolution with OSS URIs
        // This test validates the URI template resolution without making actual OSS calls
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/production",
            "{index}/shard-{shard_id}",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Test URI resolution for different shards
        String uri0 = config.resolveUri("products", 0);
        String uri1 = config.resolveUri("products", 1);
        String uri2 = config.resolveUri("orders", 0);

        assertThat(uri0, equalTo("oss://" + OSS_TEST_BUCKET + "/production/products/shard-0/vectors.lance"));
        assertThat(uri1, equalTo("oss://" + OSS_TEST_BUCKET + "/production/products/shard-1/vectors.lance"));
        assertThat(uri2, equalTo("oss://" + OSS_TEST_BUCKET + "/production/orders/shard-0/vectors.lance"));
    }

    public void testOssConfigWithCredentials() throws Exception {
        // Test that OSS credentials are properly stored in the config
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            "test-key-id",
            "test-key-secret",
            "oss://my-bucket/path",
            "shard-{shard_id}",
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        assertThat(config.ossEndpoint(), equalTo(OSS_ENDPOINT));
        assertThat(config.ossAccessKeyId(), equalTo("test-key-id"));
        assertThat(config.ossAccessKeySecret(), equalTo("test-key-secret"));
        assertTrue(config.isShardAware());
    }

    public void testOssLegacyModeWithCredentials() throws Exception {
        // Test legacy OSS mode with credentials
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            "oss://bucket/dataset.lance",
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET
        );

        assertThat(config.ossEndpoint(), equalTo(OSS_ENDPOINT));
        assertThat(config.ossAccessKeyId(), equalTo(OSS_ACCESS_KEY_ID));
        assertThat(config.ossAccessKeySecret(), equalTo(OSS_ACCESS_KEY_SECRET));
        assertFalse(config.isShardAware());
        assertThat(config.resolveUri("test", 0), equalTo("oss://bucket/dataset.lance"));
    }

    public void testOssUriPrefixWithDifferentRegions() throws Exception {
        // Test OSS URI resolution with different regions
        String[] regions = { "oss-ap-southeast-1.aliyuncs.com", "oss-cn-hangzhou.aliyuncs.com", "oss-us-west-1.aliyuncs.com" };

        for (String region : regions) {
            LanceStorageConfig config = new LanceStorageConfig(
                "external",
                null,
                "_id",
                "vector",
                region,
                OSS_ACCESS_KEY_ID,
                OSS_ACCESS_KEY_SECRET,
                "oss://my-bucket/data",
                "{index}/shard-{shard_id}",
                null,
                3,
                LanceStorageConfig.ShardingStrategy.ES_ROUTING
            );

            String uri = config.resolveUri("test-index", 0);
            assertThat(uri, equalTo("oss://my-bucket/data/test-index/shard-0/data.lance"));
            assertThat(config.ossEndpoint(), equalTo(region));
        }
    }

    public void testOssComplexShardPath() throws Exception {
        // Test complex shard path with OSS
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/prod/v2",
            "{index}/shard-{shard_id}/partitions",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("products", 5);
        assertThat(uri, equalTo("oss://" + OSS_TEST_BUCKET + "/prod/v2/products/shard-5/partitions/vectors.lance"));
    }

    public void testOssDatasetNameCustomization() throws Exception {
        // Test custom dataset names with OSS
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/data",
            "shard-{shard_id}",
            "embeddings.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String uri = config.resolveUri("test", 0);
        assertThat(uri, equalTo("oss://" + OSS_TEST_BUCKET + "/data/shard-0/embeddings.lance"));
    }

    public void testOssUriValidation() throws Exception {
        // Test that OSS URIs are properly validated
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/path",
            "shard-{shard_id}",
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Verify the resolved URI starts with oss://
        String uri = config.resolveUri("test", 0);
        assertTrue(uri.startsWith("oss://"));
        assertTrue(uri.contains(OSS_TEST_BUCKET));
    }

    public void testOssCrossIndexIsolation() throws Exception {
        // Test that different indices with OSS get different URIs
        LanceStorageConfig config = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/production",
            "{index}/shard-{shard_id}",
            null,
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String productsShard0 = config.resolveUri("products", 0);
        String ordersShard0 = config.resolveUri("orders", 0);
        String productsShard1 = config.resolveUri("products", 1);

        // All should be different
        assertThat(productsShard0, not(equalTo(ordersShard0)));
        assertThat(productsShard0, not(equalTo(productsShard1)));
        assertThat(ordersShard0, not(equalTo(productsShard1)));

        // Verify index names are in the URIs
        assertTrue(productsShard0.contains("/products/"));
        assertTrue(ordersShard0.contains("/orders/"));
    }

    public void testOssLegacyVsShardAwareMode() throws Exception {
        // Compare legacy and shard-aware OSS URI patterns
        // Legacy mode: all shards share the same dataset
        LanceStorageConfig legacy = new LanceStorageConfig(
            "external",
            "oss://" + OSS_TEST_BUCKET + "/shared-dataset.lance",
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET
        );

        // Shard-aware mode: each shard has its own dataset
        LanceStorageConfig shardAware = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            OSS_ENDPOINT,
            OSS_ACCESS_KEY_ID,
            OSS_ACCESS_KEY_SECRET,
            "oss://" + OSS_TEST_BUCKET + "/sharded",
            "shard-{shard_id}",
            "vectors.lance",
            3,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        // Legacy mode returns same URI for all shards
        assertThat(legacy.resolveUri("test", 0), equalTo("oss://" + OSS_TEST_BUCKET + "/shared-dataset.lance"));
        assertThat(legacy.resolveUri("test", 1), equalTo("oss://" + OSS_TEST_BUCKET + "/shared-dataset.lance"));

        // Shard-aware mode returns different URIs per shard
        assertThat(shardAware.resolveUri("test", 0), equalTo("oss://" + OSS_TEST_BUCKET + "/sharded/shard-0/vectors.lance"));
        assertThat(shardAware.resolveUri("test", 1), equalTo("oss://" + OSS_TEST_BUCKET + "/sharded/shard-1/vectors.lance"));

        // Verify mode detection
        assertFalse(legacy.isShardAware());
        assertTrue(shardAware.isShardAware());
    }
}
