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
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.plugin.lance.storage.LanceDataset;
import org.elasticsearch.plugin.lance.storage.LanceDatasetConfig;
import org.elasticsearch.plugin.lance.storage.LanceDatasetRegistry;
import org.elasticsearch.plugin.lance.storage.LanceRefreshService;
import org.elasticsearch.test.ESTestCase;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public void testBuildDatasetConfigUsesConfiguredColumns() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            "embedded:test-dataset",
            "doc_id",
            "embedding",
            null,
            null,
            null,
            1,
            null
        );

        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            0,
            new float[] { 1.0f, 0.0f, 0.0f },
            1,
            10,
            "cosine",
            null,
            3,
            PreFilterHeuristic.AUTO
        );

        Method buildDatasetConfig = LanceKnnQuery.class.getDeclaredMethod("buildDatasetConfig");
        buildDatasetConfig.setAccessible(true);

        LanceDatasetConfig config = (LanceDatasetConfig) buildDatasetConfig.invoke(query);
        assertThat(config.idColumn(), equalTo("doc_id"));
        assertThat(config.vectorColumn(), equalTo("embedding"));
    }

    public void testNonDefaultNprobesPreservesConfiguredSimilarity() throws Exception {
        String uri = "embedded:similarity-nprobes";
        CountingDataset dataset = new CountingDataset(List.of(new LanceDataset.Candidate("doc1", 1.0f)));
        LanceDatasetRegistry.get(uri, () -> dataset);

        LanceStorageConfig storageConfig = new LanceStorageConfig("external", uri, "_id", "vector", null, null, null, 1, null);

        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            0,
            new float[] { 1.0f, 0.0f, 0.0f },
            1,
            10,
            "dot_product",
            null,
            3,
            42,
            PreFilterHeuristic.AUTO
        );

        try (Directory directory = new ByteBuffersDirectory(); IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            addIdDoc(writer, "doc1");
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.search(query, 5);
            }
        }

        assertThat(dataset.lastSimilarityUsed(), equalTo("dot_product"));
    }

    public void testLegacyModeUsesSqlFilterPushdownWhenFieldMappingConfigured() throws Exception {
        String uri = "embedded:legacy-pushdown";
        CountingDataset dataset = new CountingDataset(List.of(new LanceDataset.Candidate("doc1", 1.0f)));
        LanceDatasetRegistry.get(uri, () -> dataset);

        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            uri,
            "_id",
            "vector",
            null,
            null,
            null,
            1,
            Map.of("category", "product_category")
        );

        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            0,
            new float[] { 1.0f, 0.0f, 0.0f },
            1,
            10,
            "dot_product",
            new TermQuery(new Term("category", "electronics")),
            3,
            PreFilterHeuristic.AUTO
        );

        try (Directory directory = new ByteBuffersDirectory(); IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new Field(IdFieldMapper.NAME, Uid.encodeId("doc1"), ID_FIELD_TYPE));
            doc.add(new StringField("category", "electronics", Field.Store.NO));
            writer.addDocument(doc);
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.search(query, 5);
            }
        }

        assertThat(dataset.sqlSearchCalls(), equalTo(1));
        assertThat(dataset.lastSqlFilter(), equalTo("product_category = 'electronics'"));
        assertThat(dataset.lastSimilarityUsed(), equalTo("dot_product"));
    }

    public void testRefreshDoesNotCloseDatasetDuringActiveQuery() throws Exception {
        String uri = "embedded:refresh-concurrency";
        BlockingDataset dataset = new BlockingDataset(List.of(new LanceDataset.Candidate("doc1", 1.0f)));
        LanceDatasetRegistry.get(uri, () -> dataset);

        LanceStorageConfig storageConfig = new LanceStorageConfig("external", uri, "_id", "vector", null, null, null, 1, null);
        LanceKnnQuery query = new LanceKnnQuery(
            "embedding",
            storageConfig,
            "products",
            0,
            new float[] { 1.0f, 0.0f, 0.0f },
            1,
            10,
            "cosine",
            null,
            3,
            PreFilterHeuristic.AUTO
        );

        LanceRefreshService refreshService = new LanceRefreshService(Executors.newSingleThreadScheduledExecutor());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Directory directory = new ByteBuffersDirectory(); IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            addIdDoc(writer, "doc1");
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                Future<?> searchFuture = executor.submit(() -> {
                    try {
                        searcher.search(query, 5);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                assertTrue("search should reach dataset", dataset.awaitSearchStart(5, TimeUnit.SECONDS));
                Future<?> refreshFuture = executor.submit(refreshService::refreshAll);

                Thread.sleep(100);
                assertFalse("refresh should wait for active query", refreshFuture.isDone());

                dataset.allowSearchToFinish();
                searchFuture.get(5, TimeUnit.SECONDS);
                refreshFuture.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            refreshService.close();
        }
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
        private int sqlSearchCalls;
        private String lastSqlFilter;
        private String lastSimilarityUsed;

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
            lastSimilarityUsed = similarity;
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
            lastSimilarityUsed = "cosine";
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) {
            sqlSearchCalls++;
            lastSqlFilter = sqlFilter;
            lastSimilarityUsed = "cosine";
            // CountingDataset doesn't support SQL filtering, just return unfiltered results
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes, String sqlFilter, String similarity) {
            if (sqlFilter == null || sqlFilter.isEmpty()) {
                nprobesSearchCalls++;
            } else {
                sqlSearchCalls++;
                lastSqlFilter = sqlFilter;
            }
            lastSimilarityUsed = similarity;
            // CountingDataset doesn't support SQL filtering, just return unfiltered results
            return search(queryVector, k, similarity);
        }

        @Override
        public String uri() {
            return "test://counting";
        }

        int totalSearchCalls() {
            return basicSearchCalls + nprobesSearchCalls;
        }

        int sqlSearchCalls() {
            return sqlSearchCalls;
        }

        String lastSqlFilter() {
            return lastSqlFilter;
        }

        String lastSimilarityUsed() {
            return lastSimilarityUsed;
        }
    }

    private static final class BlockingDataset implements LanceDataset {
        private final List<Candidate> candidates;
        private final CountDownLatch searchStarted = new CountDownLatch(1);
        private final CountDownLatch allowSearchToFinish = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private BlockingDataset(List<Candidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public int dims() {
            return 3;
        }

        @Override
        public List<Candidate> search(float[] query, int numCandidates, String similarity) {
            searchStarted.countDown();
            try {
                if (allowSearchToFinish.await(5, TimeUnit.SECONDS) == false) {
                    throw new IllegalStateException("timed out waiting to finish search");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            if (closed.get()) {
                throw new IllegalStateException("dataset was closed during an active query");
            }
            int limit = Math.min(numCandidates, candidates.size());
            return candidates.subList(0, limit);
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) {
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) {
            return search(queryVector, k, "cosine");
        }

        @Override
        public List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) {
            return search(queryVector, k, "cosine");
        }

        @Override
        public String uri() {
            return "test://blocking";
        }

        @Override
        public void close() {
            closed.set(true);
        }

        boolean awaitSearchStart(long timeout, TimeUnit unit) throws InterruptedException {
            return searchStarted.await(timeout, unit);
        }

        void allowSearchToFinish() {
            allowSearchToFinish.countDown();
        }
    }
}
