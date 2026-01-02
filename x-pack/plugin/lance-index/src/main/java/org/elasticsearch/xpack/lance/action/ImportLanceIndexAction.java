/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.action;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

/**
 * Action for importing an existing Lance index into Elasticsearch.
 * <p>
 * This action allows users to:
 * <ul>
 *   <li>Import a Lance dataset from local storage or cloud (S3, GCS, Azure)</li>
 *   <li>Create an Elasticsearch index that references the Lance data</li>
 *   <li>Configure mapping and search parameters</li>
 * </ul>
 */
public class ImportLanceIndexAction extends ActionType<ImportLanceIndexAction.Response> {

    public static final ImportLanceIndexAction INSTANCE = new ImportLanceIndexAction();
    public static final String NAME = "cluster:admin/lance/import";

    private ImportLanceIndexAction() {
        super(NAME);
    }

    /**
     * Request to import a Lance index.
     */
    public static class Request extends ActionRequest {

        private String indexName;
        private String lancePath;
        private String vectorColumn;
        private String idColumn;
        private Map<String, Object> additionalMappings;
        private boolean createIndex;
        private int shards;
        private int replicas;

        public Request() {
            this.createIndex = true;
            this.shards = 1;
            this.replicas = 1;
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.indexName = in.readString();
            this.lancePath = in.readString();
            this.vectorColumn = in.readOptionalString();
            this.idColumn = in.readOptionalString();
            this.additionalMappings = in.readGenericMap();
            this.createIndex = in.readBoolean();
            this.shards = in.readVInt();
            this.replicas = in.readVInt();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(indexName);
            out.writeString(lancePath);
            out.writeOptionalString(vectorColumn);
            out.writeOptionalString(idColumn);
            out.writeGenericMap(additionalMappings);
            out.writeBoolean(createIndex);
            out.writeVInt(shards);
            out.writeVInt(replicas);
        }

        public String getIndexName() {
            return indexName;
        }

        public Request setIndexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        public String getLancePath() {
            return lancePath;
        }

        public Request setLancePath(String lancePath) {
            this.lancePath = lancePath;
            return this;
        }

        public String getVectorColumn() {
            return vectorColumn;
        }

        public Request setVectorColumn(String vectorColumn) {
            this.vectorColumn = vectorColumn;
            return this;
        }

        public String getIdColumn() {
            return idColumn;
        }

        public Request setIdColumn(String idColumn) {
            this.idColumn = idColumn;
            return this;
        }

        public Map<String, Object> getAdditionalMappings() {
            return additionalMappings;
        }

        public Request setAdditionalMappings(Map<String, Object> additionalMappings) {
            this.additionalMappings = additionalMappings;
            return this;
        }

        public boolean isCreateIndex() {
            return createIndex;
        }

        public Request setCreateIndex(boolean createIndex) {
            this.createIndex = createIndex;
            return this;
        }

        public int getShards() {
            return shards;
        }

        public Request setShards(int shards) {
            this.shards = shards;
            return this;
        }

        public int getReplicas() {
            return replicas;
        }

        public Request setReplicas(int replicas) {
            this.replicas = replicas;
            return this;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (Strings.isNullOrEmpty(indexName)) {
                validationException = addValidationError("index_name is required", validationException);
            }

            if (Strings.isNullOrEmpty(lancePath)) {
                validationException = addValidationError("lance_path is required", validationException);
            }

            if (shards < 1) {
                validationException = addValidationError("shards must be at least 1", validationException);
            }

            if (replicas < 0) {
                validationException = addValidationError("replicas must be non-negative", validationException);
            }

            return validationException;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return createIndex == request.createIndex
                && shards == request.shards
                && replicas == request.replicas
                && Objects.equals(indexName, request.indexName)
                && Objects.equals(lancePath, request.lancePath)
                && Objects.equals(vectorColumn, request.vectorColumn)
                && Objects.equals(idColumn, request.idColumn)
                && Objects.equals(additionalMappings, request.additionalMappings);
        }

        @Override
        public int hashCode() {
            return Objects.hash(indexName, lancePath, vectorColumn, idColumn, additionalMappings, createIndex, shards, replicas);
        }
    }

    /**
     * Response from importing a Lance index.
     */
    public static class Response extends ActionResponse implements ToXContentObject {

        private final boolean acknowledged;
        private final String indexName;
        private final String lancePath;
        private final long documentCount;
        private final int vectorDimension;
        private final String[] columns;
        private final String message;

        public Response(
            boolean acknowledged,
            String indexName,
            String lancePath,
            long documentCount,
            int vectorDimension,
            String[] columns,
            String message
        ) {
            this.acknowledged = acknowledged;
            this.indexName = indexName;
            this.lancePath = lancePath;
            this.documentCount = documentCount;
            this.vectorDimension = vectorDimension;
            this.columns = columns;
            this.message = message;
        }

        public Response(StreamInput in) throws IOException {
            super(in);
            this.acknowledged = in.readBoolean();
            this.indexName = in.readString();
            this.lancePath = in.readString();
            this.documentCount = in.readVLong();
            this.vectorDimension = in.readVInt();
            this.columns = in.readStringArray();
            this.message = in.readOptionalString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeBoolean(acknowledged);
            out.writeString(indexName);
            out.writeString(lancePath);
            out.writeVLong(documentCount);
            out.writeVInt(vectorDimension);
            out.writeStringArray(columns);
            out.writeOptionalString(message);
        }

        public boolean isAcknowledged() {
            return acknowledged;
        }

        public String getIndexName() {
            return indexName;
        }

        public String getLancePath() {
            return lancePath;
        }

        public long getDocumentCount() {
            return documentCount;
        }

        public int getVectorDimension() {
            return vectorDimension;
        }

        public String[] getColumns() {
            return columns;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("acknowledged", acknowledged);
            builder.field("index", indexName);
            builder.field("lance_path", lancePath);
            builder.field("document_count", documentCount);
            builder.field("vector_dimension", vectorDimension);
            builder.array("columns", columns);
            if (message != null) {
                builder.field("message", message);
            }
            builder.endObject();
            return builder;
        }
    }
}
