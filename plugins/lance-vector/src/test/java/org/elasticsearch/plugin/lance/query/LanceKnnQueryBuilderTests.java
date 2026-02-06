/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Tests for {@link LanceKnnQueryBuilder} filter parsing and serialization.
 */
public class LanceKnnQueryBuilderTests extends ESTestCase {

    private static NamedXContentRegistry xContentRegistry;
    private static NamedWriteableRegistry namedWriteableRegistry;

    @BeforeClass
    public static void initRegistries() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, emptyList());
        xContentRegistry = new NamedXContentRegistry(searchModule.getNamedXContents());
        namedWriteableRegistry = new NamedWriteableRegistry(searchModule.getNamedWriteables());
    }

    @AfterClass
    public static void cleanupRegistries() {
        xContentRegistry = null;
        namedWriteableRegistry = null;
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return xContentRegistry;
    }

    @Override
    protected NamedWriteableRegistry writableRegistry() {
        return namedWriteableRegistry;
    }

    // -- Filter parsing tests --

    public void testFromXContentWithNoFilter() throws IOException {
        String json = """
            {
              "field": "vector",
              "query_vector": [0.1, 0.2, 0.3],
              "k": 5
            }
            """;
        LanceKnnQueryBuilder builder = parseBuilder(json);
        assertNotNull(builder.filterQueries());
        assertEquals(0, builder.filterQueries().size());
    }

    public void testFromXContentWithSingleFilter() throws IOException {
        String json = """
            {
              "field": "vector",
              "query_vector": [0.1, 0.2, 0.3],
              "k": 5,
              "filter": {
                "term": { "color": "red" }
              }
            }
            """;
        LanceKnnQueryBuilder builder = parseBuilder(json);
        assertNotNull(builder.filterQueries());
        assertEquals(1, builder.filterQueries().size());
        assertThat(builder.filterQueries().get(0), org.hamcrest.Matchers.instanceOf(TermQueryBuilder.class));
    }

    public void testFromXContentWithArrayFilter() throws IOException {
        String json = """
            {
              "field": "vector",
              "query_vector": [0.1, 0.2, 0.3],
              "k": 5,
              "filter": [
                { "term": { "color": "red" } },
                { "range": { "price": { "lte": 100 } } }
              ]
            }
            """;
        LanceKnnQueryBuilder builder = parseBuilder(json);
        assertNotNull(builder.filterQueries());
        assertEquals(2, builder.filterQueries().size());
        assertThat(builder.filterQueries().get(0), org.hamcrest.Matchers.instanceOf(TermQueryBuilder.class));
        assertThat(builder.filterQueries().get(1), org.hamcrest.Matchers.instanceOf(RangeQueryBuilder.class));
    }

    // -- Constructor and accessor tests --

    public void testBackwardCompatConstructorHasEmptyFilter() {
        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder("field", new float[] { 1.0f }, 10, 100);
        assertNotNull(builder.filterQueries());
        assertEquals(0, builder.filterQueries().size());
    }

    public void testConstructorWithFilterQueries() {
        List<QueryBuilder> filters = List.of(new TermQueryBuilder("color", "red"));
        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder("field", new float[] { 1.0f }, 10, 100, filters);
        assertEquals(1, builder.filterQueries().size());
    }

    public void testFilterQueriesIsImmutable() {
        List<QueryBuilder> filters = new java.util.ArrayList<>();
        filters.add(new TermQueryBuilder("color", "red"));
        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder("field", new float[] { 1.0f }, 10, 100, filters);

        // Modifying original list should not affect builder
        filters.add(new TermQueryBuilder("size", "large"));
        assertEquals(1, builder.filterQueries().size());

        // Returned list should be immutable
        expectThrows(UnsupportedOperationException.class, () -> builder.filterQueries().add(new TermQueryBuilder("x", "y")));
    }

    // -- Serialization round-trip tests --

    public void testSerializationRoundTripNoFilter() throws IOException {
        LanceKnnQueryBuilder original = new LanceKnnQueryBuilder("vec", new float[] { 1.0f, 2.0f }, 5, 50);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), writableRegistry());
        LanceKnnQueryBuilder deserialized = new LanceKnnQueryBuilder(in);

        assertEquals(0, deserialized.filterQueries().size());
    }

    public void testSerializationRoundTripWithFilter() throws IOException {
        List<QueryBuilder> filters = List.of(new TermQueryBuilder("color", "red"), new RangeQueryBuilder("price").lte(100));
        LanceKnnQueryBuilder original = new LanceKnnQueryBuilder("vec", new float[] { 1.0f, 2.0f }, 5, 50, filters);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = new NamedWriteableAwareStreamInput(out.bytes().streamInput(), writableRegistry());
        LanceKnnQueryBuilder deserialized = new LanceKnnQueryBuilder(in);

        assertEquals(2, deserialized.filterQueries().size());
    }

    // -- Equals / HashCode tests --

    public void testEqualsWithSameFilters() {
        List<QueryBuilder> filters = List.of(new TermQueryBuilder("color", "red"));
        LanceKnnQueryBuilder q1 = new LanceKnnQueryBuilder("f", new float[] { 1.0f }, 10, 100, filters);
        LanceKnnQueryBuilder q2 = new LanceKnnQueryBuilder("f", new float[] { 1.0f }, 10, 100, filters);
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }

    public void testNotEqualsWithDifferentFilters() {
        LanceKnnQueryBuilder q1 = new LanceKnnQueryBuilder(
            "f",
            new float[] { 1.0f },
            10,
            100,
            List.of(new TermQueryBuilder("color", "red"))
        );
        LanceKnnQueryBuilder q2 = new LanceKnnQueryBuilder(
            "f",
            new float[] { 1.0f },
            10,
            100,
            List.of(new TermQueryBuilder("color", "blue"))
        );
        assertNotEquals(q1, q2);
    }

    // -- XContent round-trip --

    public void testXContentRoundTripWithFilter() throws IOException {
        List<QueryBuilder> filters = List.of(new TermQueryBuilder("color", "red"));
        LanceKnnQueryBuilder original = new LanceKnnQueryBuilder("vec", new float[] { 1.0f, 2.0f }, 5, 50, filters);

        // Serialize to XContent
        org.elasticsearch.xcontent.XContentBuilder xContentBuilder = org.elasticsearch.xcontent.XContentFactory.jsonBuilder();
        original.toXContent(xContentBuilder, org.elasticsearch.xcontent.ToXContent.EMPTY_PARAMS);
        String json = org.elasticsearch.common.Strings.toString(xContentBuilder);

        // Parse back — need to position parser inside the "lance_knn" object
        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // START_OBJECT (outer)
        parser.nextToken(); // FIELD_NAME "lance_knn"
        parser.nextToken(); // START_OBJECT (inner)
        LanceKnnQueryBuilder deserialized = LanceKnnQueryBuilder.fromXContent(parser);

        assertEquals(1, deserialized.filterQueries().size());
    }

    // -- Helper --

    private LanceKnnQueryBuilder parseBuilder(String json) throws IOException {
        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // START_OBJECT
        return LanceKnnQueryBuilder.fromXContent(parser);
    }
}
