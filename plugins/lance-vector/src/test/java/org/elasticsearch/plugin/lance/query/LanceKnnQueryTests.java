/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.lucene.search.QueryVisitor;
import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link LanceKnnQuery}.
 */
public class LanceKnnQueryTests extends ESTestCase {

    private static LanceStorageConfig legacyConfig(String uri) {
        return new LanceStorageConfig("external", uri, "_id", "vector", null, null, null);
    }

    public void testEqualsIdentical() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        assertThat(q1, equalTo(q2));
        assertThat(q1.hashCode(), equalTo(q2.hashCode()));
    }

    public void testEqualsDifferentField() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field1", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field2", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentIndex() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "index-a", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "index-b", -1, vector, 10, 100, "cosine", null, 3);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentShard() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", 0, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", 1, vector, 10, 100, "cosine", null, 3);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentK() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 20, 100, "cosine", null, 3);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentNumCandidates() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 200, "cosine", null, 3);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentSimilarity() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery(
            "field",
            legacyConfig("uri://test"),
            "test-index",
            -1,
            vector,
            10,
            100,
            "dot_product",
            null,
            3
        );

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsWithNullSimilarityDefaultsToCosine() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, null, null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        assertThat(q1, equalTo(q2));
        assertThat(q1.hashCode(), equalTo(q2.hashCode()));
    }

    public void testEqualsWithNull() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        assertFalse(q1.equals(null));
    }

    public void testEqualsWithDifferentClass() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        assertFalse(q1.equals("not a query"));
    }

    public void testToStringLegacyMode() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery(
            "myField",
            legacyConfig("file:///path/to/data"),
            "test-index",
            -1,
            vector,
            10,
            100,
            "cosine",
            null,
            3
        );

        String str = query.toString("ignored");
        assertThat(str, containsString("LanceKnnQuery"));
        assertThat(str, containsString("myField"));
        assertThat(str, containsString("file:///path/to/data"));
    }

    public void testToStringShardAwareMode() {
        LanceStorageConfig shardConfig = new LanceStorageConfig(
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
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("myField", shardConfig, "my-index", 2, vector, 10, 100, "cosine", null, 3);

        String str = query.toString("ignored");
        assertThat(str, containsString("LanceKnnQuery"));
        assertThat(str, containsString("myField"));
        assertThat(str, containsString("shardAware=true"));
    }

    public void testVisitCallsVisitLeaf() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        final boolean[] visited = { false };
        QueryVisitor visitor = new QueryVisitor() {
            @Override
            public void visitLeaf(org.apache.lucene.search.Query query) {
                visited[0] = true;
            }
        };

        query.visit(visitor);
        assertTrue("visitLeaf should have been called", visited[0]);
    }

    public void testConstructorRequiresFieldName() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        expectThrows(
            NullPointerException.class,
            () -> new LanceKnnQuery(null, legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3)
        );
    }

    public void testConstructorRequiresStorageConfig() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        expectThrows(
            NullPointerException.class,
            () -> new LanceKnnQuery("field", null, "test-index", -1, vector, 10, 100, "cosine", null, 3)
        );
    }

    public void testConstructorRequiresQueryVector() {
        expectThrows(
            NullPointerException.class,
            () -> new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, null, 10, 100, "cosine", null, 3)
        );
    }

    public void testHashCodeConsistent() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector, 10, 100, "cosine", null, 3);

        int hash1 = query.hashCode();
        int hash2 = query.hashCode();
        assertThat(hash1, equalTo(hash2));
    }

    public void testDifferentVectorsSameEquality() {
        // Note: The current equals implementation doesn't compare vectors, only field/index/shard/k/numCandidates/similarity
        // This test documents that behavior
        float[] vector1 = { 1.0f, 2.0f, 3.0f };
        float[] vector2 = { 4.0f, 5.0f, 6.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector1, 10, 100, "cosine", null, 3);
        LanceKnnQuery q2 = new LanceKnnQuery("field", legacyConfig("uri://test"), "test-index", -1, vector2, 10, 100, "cosine", null, 3);

        // These are equal because equals() doesn't compare vectors
        assertThat(q1, equalTo(q2));
    }
}
