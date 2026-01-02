/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractWireSerializingTestCase;
import org.elasticsearch.xpack.lance.action.ImportLanceIndexAction;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link ImportLanceIndexAction}.
 */
public class ImportLanceIndexActionTests extends AbstractWireSerializingTestCase<ImportLanceIndexAction.Request> {

    @Override
    protected Writeable.Reader<ImportLanceIndexAction.Request> instanceReader() {
        return ImportLanceIndexAction.Request::new;
    }

    @Override
    protected ImportLanceIndexAction.Request createTestInstance() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setIndexName(randomAlphaOfLength(10));
        request.setLancePath("/path/to/" + randomAlphaOfLength(8));

        if (randomBoolean()) {
            request.setVectorColumn(randomAlphaOfLength(8));
        }

        if (randomBoolean()) {
            request.setIdColumn(randomAlphaOfLength(8));
        }

        if (randomBoolean()) {
            Map<String, Object> mappings = new HashMap<>();
            mappings.put("properties", new HashMap<>());
            request.setAdditionalMappings(mappings);
        }

        request.setCreateIndex(randomBoolean());
        request.setShards(randomIntBetween(1, 10));
        request.setReplicas(randomIntBetween(0, 5));

        return request;
    }

    @Override
    protected ImportLanceIndexAction.Request mutateInstance(ImportLanceIndexAction.Request instance) throws IOException {
        ImportLanceIndexAction.Request mutated = new ImportLanceIndexAction.Request();
        mutated.setIndexName(instance.getIndexName());
        mutated.setLancePath(instance.getLancePath());
        mutated.setVectorColumn(instance.getVectorColumn());
        mutated.setIdColumn(instance.getIdColumn());
        mutated.setAdditionalMappings(instance.getAdditionalMappings());
        mutated.setCreateIndex(instance.isCreateIndex());
        mutated.setShards(instance.getShards());
        mutated.setReplicas(instance.getReplicas());

        switch (randomIntBetween(0, 7)) {
            case 0 -> mutated.setIndexName(randomAlphaOfLength(10));
            case 1 -> mutated.setLancePath("/path/to/" + randomAlphaOfLength(8));
            case 2 -> mutated.setVectorColumn(randomAlphaOfLength(8));
            case 3 -> mutated.setIdColumn(randomAlphaOfLength(8));
            case 4 -> {
                Map<String, Object> mappings = new HashMap<>();
                mappings.put("properties", new HashMap<>());
                mutated.setAdditionalMappings(mappings);
            }
            case 5 -> mutated.setCreateIndex(!instance.isCreateIndex());
            case 6 -> mutated.setShards(randomIntBetween(1, 10));
            case 7 -> mutated.setReplicas(randomIntBetween(0, 5));
        }

        return mutated;
    }

    public void testValidation_MissingIndexName() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setLancePath("/path/to/lance");

        ActionRequestValidationException e = request.validate();
        assertNotNull(e);
        assertThat(e.getMessage(), containsString("index_name is required"));
    }

    public void testValidation_MissingLancePath() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setIndexName("my_index");

        ActionRequestValidationException e = request.validate();
        assertNotNull(e);
        assertThat(e.getMessage(), containsString("lance_path is required"));
    }

    public void testValidation_InvalidShards() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setIndexName("my_index");
        request.setLancePath("/path/to/lance");
        request.setShards(0);

        ActionRequestValidationException e = request.validate();
        assertNotNull(e);
        assertThat(e.getMessage(), containsString("shards must be at least 1"));
    }

    public void testValidation_InvalidReplicas() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setIndexName("my_index");
        request.setLancePath("/path/to/lance");
        request.setReplicas(-1);

        ActionRequestValidationException e = request.validate();
        assertNotNull(e);
        assertThat(e.getMessage(), containsString("replicas must be non-negative"));
    }

    public void testValidation_Valid() {
        ImportLanceIndexAction.Request request = new ImportLanceIndexAction.Request();
        request.setIndexName("my_index");
        request.setLancePath("/path/to/lance");

        ActionRequestValidationException e = request.validate();
        assertThat(e, nullValue());
    }

    public void testResponse() throws IOException {
        ImportLanceIndexAction.Response response = new ImportLanceIndexAction.Response(
            true,
            "test_index",
            "/path/to/lance",
            1000,
            128,
            new String[]{"id", "vector", "text"},
            "Success"
        );

        assertThat(response.isAcknowledged(), equalTo(true));
        assertThat(response.getIndexName(), equalTo("test_index"));
        assertThat(response.getLancePath(), equalTo("/path/to/lance"));
        assertThat(response.getDocumentCount(), equalTo(1000L));
        assertThat(response.getVectorDimension(), equalTo(128));
        assertThat(response.getColumns().length, equalTo(3));
        assertThat(response.getMessage(), equalTo("Success"));
    }
}
