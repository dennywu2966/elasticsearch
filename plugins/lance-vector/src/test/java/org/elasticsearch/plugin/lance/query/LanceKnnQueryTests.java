/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.arrow.vector.VarCharVector;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.plugin.lance.storage.LanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.test.ESTestCase;

import java.lang.reflect.Method;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link LanceKnnQuery}.
 */
public class LanceKnnQueryTests extends ESTestCase {
    private static final FieldType ID_FIELD_TYPE = createIdFieldType();

    @Override
    public void setUp() throws Exception {
        super.setUp();
        LanceDatasetRegistry.clear();
    }

    @Override
    public void tearDown() throws Exception {
        LanceDatasetRegistry.clear();
        super.tearDown();
    }

    public void testEqualsIdentical() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertThat(q1, equalTo(q2));
        assertThat(q1.hashCode(), equalTo(q2.hashCode()));
    }

    public void testEqualsDifferentField() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field1", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field2", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentUri() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test1", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test2", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentK() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector, 20, 100, "cosine", null, 3, null, null, null);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentNumCandidates() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector, 10, 200, "cosine", null, 3, null, null, null);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsDifferentSimilarity() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "dot_product", null, 3, null, null, null);

        assertThat(q1, not(equalTo(q2)));
    }

    public void testEqualsWithNullSimilarityDefaultsToCosine() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, null, null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertThat(q1, equalTo(q2));
        assertThat(q1.hashCode(), equalTo(q2.hashCode()));
    }

    public void testEqualsWithNull() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertFalse(q1.equals(null));
    }

    public void testEqualsWithDifferentClass() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        assertFalse(q1.equals("not a query"));
    }

    public void testToStringContainsFieldName() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("myField", "file:///path/to/data", vector, 10, 100, "cosine", null, 3, null, null, null);

        String str = query.toString("ignored");
        assertThat(str, containsString("LanceKnnQuery"));
        assertThat(str, containsString("myField"));
        assertThat(str, containsString("file:///path/to/data"));
    }

    public void testVisitCallsVisitLeaf() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

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
            () -> new LanceKnnQuery(null, "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null)
        );
    }

    public void testConstructorRequiresStorageUri() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        expectThrows(
            NullPointerException.class,
            () -> new LanceKnnQuery("field", null, vector, 10, 100, "cosine", null, 3, null, null, null)
        );
    }

    public void testConstructorRequiresQueryVector() {
        expectThrows(
            NullPointerException.class,
            () -> new LanceKnnQuery("field", "uri://test", null, 10, 100, "cosine", null, 3, null, null, null)
        );
    }

    public void testHashCodeConsistent() {
        float[] vector = { 1.0f, 2.0f, 3.0f };
        LanceKnnQuery query = new LanceKnnQuery("field", "uri://test", vector, 10, 100, "cosine", null, 3, null, null, null);

        int hash1 = query.hashCode();
        int hash2 = query.hashCode();
        assertThat(hash1, equalTo(hash2));
    }

    public void testDifferentVectorsSameEquality() {
        // Note: The current equals implementation doesn't compare vectors, only field/uri/k/numCandidates/similarity
        // This test documents that behavior
        float[] vector1 = { 1.0f, 2.0f, 3.0f };
        float[] vector2 = { 4.0f, 5.0f, 6.0f };
        LanceKnnQuery q1 = new LanceKnnQuery("field", "uri://test", vector1, 10, 100, "cosine", null, 3, null, null, null);
        LanceKnnQuery q2 = new LanceKnnQuery("field", "uri://test", vector2, 10, 100, "cosine", null, 3, null, null, null);

        // These are equal because equals() doesn't compare vectors
        assertThat(q1, equalTo(q2));
    }

    public void testShardAwareQueryWithUnknownShardIdFailsFast() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
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
            1,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String fallbackUri = storageConfig.resolveUri("products", 0);
        LanceDatasetRegistry.get(fallbackUri, () -> new CountingDataset(List.of(new LanceDataset.Candidate("doc1", 1.0f))));

        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            -1,
            new float[] { 1.0f, 0.0f, 0.0f },
            1,
            10,
            "cosine",
            null,
            3,
            PreFilterHeuristic.AUTO
        );

        try (Directory directory = new ByteBuffersDirectory(); IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            addIdDoc(writer, "doc1");
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                expectThrows(IllegalArgumentException.class, () -> searcher.search(query, 5));
            }
        }
    }

    public void testShardAwareSearchExecutesOncePerWeightAcrossSegments() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
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
            1,
            LanceStorageConfig.ShardingStrategy.ES_ROUTING
        );

        String shardUri = storageConfig.resolveUri("products", 0);
        CountingDataset dataset = new CountingDataset(
            List.of(new LanceDataset.Candidate("doc1", 1.0f), new LanceDataset.Candidate("doc2", 0.9f))
        );
        LanceDatasetRegistry.get(shardUri, () -> dataset);

        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            0,
            new float[] { 1.0f, 0.0f, 0.0f },
            2,
            10,
            "cosine",
            null,
            3,
            PreFilterHeuristic.AUTO
        );

        try (Directory directory = new ByteBuffersDirectory(); IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            addIdDoc(writer, "doc1");
            writer.commit(); // segment 1
            addIdDoc(writer, "doc2");
            writer.commit(); // segment 2

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                assertThat(reader.leaves().size(), equalTo(2));
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.search(query, 5);
            }
        }

        assertThat(dataset.totalSearchCalls(), equalTo(1));
    }

    public void testRoutingHashToShardIdUsesFloorModForMinInt() throws Exception {
        Method routingMethod = LanceKnnQuery.class.getDeclaredMethod("routingHashToShardId", int.class, int.class, int.class);
        routingMethod.setAccessible(true);

        int shard = (int) routingMethod.invoke(null, Integer.MIN_VALUE, 8, 2);
        assertThat(shard, equalTo(0));
    }

    public void testRoutingHashToShardIdAppliesRoutingFactor() throws Exception {
        Method routingMethod = LanceKnnQuery.class.getDeclaredMethod("routingHashToShardId", int.class, int.class, int.class);
        routingMethod.setAccessible(true);

        int shard = (int) routingMethod.invoke(null, 7, 8, 2);
        assertThat(shard, equalTo(3));
    }

    private static FieldType createIdFieldType() {
        FieldType type = new FieldType();
        type.setTokenized(false);
        type.setIndexOptions(IndexOptions.DOCS);
        type.freeze();
        return type;
    }

    private static void addIdDoc(IndexWriter writer, String id) throws Exception {
        Document document = new Document();
        document.add(new Field(IdFieldMapper.NAME, Uid.encodeId(id), ID_FIELD_TYPE));
        writer.addDocument(document);
    }

    private static final class CountingDataset implements LanceDataset {
        private final List<Candidate> candidates;
        private int basicSearchCalls;
        private int nprobesSearchCalls;

        private CountingDataset(List<Candidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public int dims() {
            return 3;
        }

        @Override
        public List<Candidate> search(float[] query, int numCandidates, String similarity) {
            basicSearchCalls++;
            int limit = Math.min(numCandidates, candidates.size());
            return candidates.subList(0, limit);
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) {
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) {
            nprobesSearchCalls++;
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) {
            // CountingDataset doesn't support SQL filtering, just return unfiltered results
            return search(queryVector, k, "cosine");
        }

        @Override
        public String uri() {
            return "test://counting";
        }

        int totalSearchCalls() {
            return basicSearchCalls + nprobesSearchCalls;
        }
    }
}
