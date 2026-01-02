/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractXContentSerializingTestCase;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.lance.mapper.LanceVectorQueryBuilder;

import java.io.IOException;
import java.util.Arrays;

import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link LanceVectorQueryBuilder}.
 */
public class LanceVectorQueryBuilderTests extends AbstractXContentSerializingTestCase<LanceVectorQueryBuilder> {

    @Override
    protected LanceVectorQueryBuilder doParseInstance(XContentParser parser) throws IOException {
        return LanceVectorQueryBuilder.fromXContent(parser);
    }

    @Override
    protected Writeable.Reader<LanceVectorQueryBuilder> instanceReader() {
        return LanceVectorQueryBuilder::new;
    }

    @Override
    protected LanceVectorQueryBuilder createTestInstance() {
        String field = randomAlphaOfLength(10);
        float[] queryVector = randomVector(randomIntBetween(1, 128));
        int k = randomIntBetween(1, 100);

        LanceVectorQueryBuilder builder = new LanceVectorQueryBuilder(field, queryVector, k);

        if (randomBoolean()) {
            builder.nprobes(randomIntBetween(1, 100));
        }

        if (randomBoolean()) {
            builder.refineFactor(randomIntBetween(1, 50));
        }

        return builder;
    }

    @Override
    protected LanceVectorQueryBuilder mutateInstance(LanceVectorQueryBuilder instance) throws IOException {
        String field = instance.getField();
        float[] queryVector = instance.getQueryVector();
        int k = instance.getK();
        Integer nprobes = instance.getNprobes();
        Integer refineFactor = instance.getRefineFactor();

        switch (randomIntBetween(0, 4)) {
            case 0 -> field = randomAlphaOfLength(10);
            case 1 -> queryVector = randomVector(queryVector.length);
            case 2 -> k = randomIntBetween(1, 100);
            case 3 -> nprobes = nprobes == null ? randomIntBetween(1, 100) : null;
            case 4 -> refineFactor = refineFactor == null ? randomIntBetween(1, 50) : null;
        }

        LanceVectorQueryBuilder mutated = new LanceVectorQueryBuilder(field, queryVector, k);
        if (nprobes != null) {
            mutated.nprobes(nprobes);
        }
        if (refineFactor != null) {
            mutated.refineFactor(refineFactor);
        }
        return mutated;
    }

    private float[] randomVector(int dims) {
        float[] vector = new float[dims];
        for (int i = 0; i < dims; i++) {
            vector[i] = randomFloat();
        }
        return vector;
    }

    public void testValidation() {
        // Test null field
        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder(null, new float[]{0.1f}, 10));

        // Test empty field
        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder("", new float[]{0.1f}, 10));

        // Test null vector
        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder("field", null, 10));

        // Test empty vector
        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder("field", new float[0], 10));

        // Test invalid k
        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder("field", new float[]{0.1f}, 0));

        expectThrows(IllegalArgumentException.class, () ->
            new LanceVectorQueryBuilder("field", new float[]{0.1f}, -1));
    }

    public void testNprobesValidation() {
        LanceVectorQueryBuilder builder = new LanceVectorQueryBuilder("field", new float[]{0.1f}, 10);

        expectThrows(IllegalArgumentException.class, () -> builder.nprobes(0));
        expectThrows(IllegalArgumentException.class, () -> builder.nprobes(-1));
    }

    public void testRefineFactorValidation() {
        LanceVectorQueryBuilder builder = new LanceVectorQueryBuilder("field", new float[]{0.1f}, 10);

        expectThrows(IllegalArgumentException.class, () -> builder.refineFactor(0));
        expectThrows(IllegalArgumentException.class, () -> builder.refineFactor(-1));
    }

    public void testEqualsAndHashCode() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        LanceVectorQueryBuilder builder1 = new LanceVectorQueryBuilder("field", vector, 10)
            .nprobes(20)
            .refineFactor(5);

        LanceVectorQueryBuilder builder2 = new LanceVectorQueryBuilder("field", vector.clone(), 10)
            .nprobes(20)
            .refineFactor(5);

        assertThat(builder1, equalTo(builder2));
        assertThat(builder1.hashCode(), equalTo(builder2.hashCode()));
    }

    public void testToString() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        LanceVectorQueryBuilder builder = new LanceVectorQueryBuilder("my_field", vector, 10);

        String str = builder.toString();
        assertTrue(str.contains("lance_vector"));
        assertTrue(str.contains("my_field"));
    }
}
