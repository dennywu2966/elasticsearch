/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Tests for nprobes parameter in LanceKnnQueryBuilder DSL.
 */
public class LanceKnnQueryBuilderNprobesTests extends ESTestCase {

    public void testFromXContentWithNprobes() throws IOException {
        String json = """
            {
              "field": "vector",
              "query_vector": [0.1, 0.2, 0.3],
              "k": 5,
              "nprobes": 50
            }
            """;
        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // Move to START_OBJECT
        LanceKnnQueryBuilder builder = LanceKnnQueryBuilder.fromXContent(parser);
        assertThat(builder.nprobes(), equalTo(50));
    }

    public void testDefaultNprobes() throws IOException {
        String json = """
            {
              "field": "vector",
              "query_vector": [0.1, 0.2, 0.3],
              "k": 5
            }
            """;
        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        parser.nextToken(); // Move to START_OBJECT
        LanceKnnQueryBuilder builder = LanceKnnQueryBuilder.fromXContent(parser);
        assertThat(builder.nprobes(), equalTo(20));
    }

    public void testConstructorWithNprobes() {
        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder(
            "vector",
            new float[] { 0.1f, 0.2f, 0.3f },
            5,
            10,
            null,
            50  // nprobes
        );
        assertThat(builder.nprobes(), equalTo(50));
    }

    public void testBackwardCompatConstructorUsesDefaultNprobes() {
        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder("vector", new float[] { 0.1f, 0.2f, 0.3f }, 5, 10);
        assertThat(builder.nprobes(), equalTo(20));
    }
}
