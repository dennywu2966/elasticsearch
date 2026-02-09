/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.mapper;

import org.apache.lucene.search.Query;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MappingParserContext;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.plugin.lance.query.LanceKnnQuery;
import org.elasticsearch.plugin.lance.query.PreFilterHeuristic;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LanceVectorFieldMapper extends FieldMapper {
    public static final String CONTENT_TYPE = "lance_vector";
    private static final String STORAGE_FIELD = "storage";
    private static final String DIMS_FIELD = "dims";
    private static final String SIMILARITY_FIELD = "similarity";

    public static class Builder extends FieldMapper.Builder {
        private final int dims;
        private final LanceStorageConfig storage;
        private final String similarity;
        private final IndexVersion indexVersionCreated;

        public Builder(String name, int dims, String similarity, LanceStorageConfig storage, IndexVersion indexVersionCreated) {
            super(name);
            this.dims = dims;
            this.similarity = similarity == null ? "cosine" : similarity;
            this.storage = storage;
            this.indexVersionCreated = indexVersionCreated;
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[0];
        }

        @Override
        public LanceVectorFieldMapper build(MapperBuilderContext context) {
            LanceVectorFieldType fieldType = new LanceVectorFieldType(context.buildFullName(leafName()), dims, similarity, storage);
            return new LanceVectorFieldMapper(leafName(), fieldType, builderParams(this, context));
        }
    }

    public static final Mapper.TypeParser PARSER = new Mapper.TypeParser() {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, MappingParserContext parserContext)
            throws MapperParsingException {
            Object dimsObj = node.remove(DIMS_FIELD);
            if (dimsObj == null) {
                throw new MapperParsingException("[dims] is required for lance_vector");
            }
            int dims;
            if (dimsObj instanceof Number) {
                dims = ((Number) dimsObj).intValue();
            } else {
                throw new MapperParsingException("[dims] must be an integer for lance_vector");
            }

            String similarity = "cosine";
            Object simObj = node.remove(SIMILARITY_FIELD);
            if (simObj != null) {
                similarity = simObj.toString();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> storage = (Map<String, Object>) node.remove(STORAGE_FIELD);
            if (storage == null) {
                storage = Map.of();
            }

            String type = "external";
            Object typeObj = storage.get("type");
            if (typeObj != null) {
                type = typeObj.toString();
            }

            // Shard-aware fields (optional, takes precedence over uri)
            String uriPrefix = getStringOrNull(storage, "uri_prefix");
            String shardPath = getStringOrNull(storage, "shard_path");
            String datasetName = getStringOrNull(storage, "dataset_name");

            // Legacy single URI (required unless uri_prefix is present)
            String uri = getStringOrNull(storage, "uri");

            if (uriPrefix == null && uri == null) {
                throw new MapperParsingException("Either [storage.uri] or [storage.uri_prefix] is required for lance_vector");
            }

            String idColumn = getStringOrDefault(storage, "lance_id_column", "_id");
            String vectorColumn = getStringOrDefault(storage, "lance_vector_column", "vector");

            // OSS configuration (optional)
            String ossEndpoint = getStringOrNull(storage, "oss_endpoint");
            String ossAccessKeyId = getStringOrNull(storage, "oss_access_key_id");
            String ossAccessKeySecret = getStringOrNull(storage, "oss_access_key_secret");

            boolean readOnly = true;
            Object readOnlyObj = storage.get("read_only");
            if (readOnlyObj != null) {
                readOnly = Booleans.parseBoolean(readOnlyObj.toString());
            }

            if (readOnly == false) {
                throw new MapperParsingException("Phase 1 lance_vector supports only read_only external datasets");
            }

            // Get number of shards from index settings for candidate filtering
            int numShards = parserContext.getIndexSettings().getNumberOfShards();

            // Parse sharding strategy (optional, defaults to ES_ROUTING for shard-aware configs)
            LanceStorageConfig.ShardingStrategy shardingStrategy = LanceStorageConfig.ShardingStrategy.ES_ROUTING;
            Object shardingStrategyObj = storage.get("sharding_strategy");
            if (shardingStrategyObj != null) {
                String strategyStr = shardingStrategyObj.toString().toUpperCase();
                try {
                    shardingStrategy = LanceStorageConfig.ShardingStrategy.valueOf(strategyStr);
                } catch (IllegalArgumentException e) {
                    throw new MapperParsingException(
                        "Invalid sharding_strategy [" + strategyStr + "]. " + "Valid values: NONE, ES_ROUTING"
                    );
                }
            }

            // Parse field_mapping for native filter pushdown (optional)
            // Format: "es_field1=lance_column1,es_field2=lance_column2"
            Map<String, String> fieldMapping = null;
            Object fieldMappingObj = storage.get("field_mapping");
            if (fieldMappingObj != null) {
                String fieldMappingStr = fieldMappingObj.toString();
                fieldMapping = parseFieldMapping(fieldMappingStr);
            }

            LanceStorageConfig storageConfig = new LanceStorageConfig(
                type,
                uri,
                idColumn,
                vectorColumn,
                ossEndpoint,
                ossAccessKeyId,
                ossAccessKeySecret,
                uriPrefix,
                shardPath,
                datasetName,
                numShards,
                shardingStrategy,
                fieldMapping
            );
            return new Builder(name, dims, similarity, storageConfig, parserContext.getIndexSettings().getIndexVersionCreated());
        }

        /**
         * Parse field_mapping string into a Map.
         * <p>
         * Format: "es_field1=lance_column1,es_field2=lance_column2"
         * <p>
         * Example: "category=product_category,brand=brand_name"
         *
         * @param fieldMappingStr The field mapping string
         * @return Map of ES field names to Lance column names
         * @throws MapperParsingException if the format is invalid
         */
        private Map<String, String> parseFieldMapping(String fieldMappingStr) throws MapperParsingException {
            Map<String, String> mapping = new HashMap<>();
            if (fieldMappingStr == null || fieldMappingStr.trim().isEmpty()) {
                return mapping;
            }

            String[] pairs = fieldMappingStr.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length != 2) {
                    throw new MapperParsingException(
                        "Invalid field_mapping format: [" + pair + "]. " + "Expected format: es_field=lance_column (comma-separated pairs)"
                    );
                }
                String esField = keyValue[0].trim();
                String lanceColumn = keyValue[1].trim();
                if (esField.isEmpty() || lanceColumn.isEmpty()) {
                    throw new MapperParsingException("Invalid field_mapping: empty field or column name in [" + pair + "]");
                }
                mapping.put(esField, lanceColumn);
            }
            return mapping;
        }

        /**
         * Get string value from map or null if not present.
         */
        private String getStringOrNull(Map<String, Object> map, String key) {
            Object value = map.get(key);
            return value != null ? value.toString() : null;
        }

        /**
         * Get string value from map or default if not present.
         */
        private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
            Object value = map.get(key);
            return value != null ? value.toString() : defaultValue;
        }
    };

    public static class LanceVectorFieldType extends MappedFieldType {
        private final int dims;
        private final String similarity;
        private final LanceStorageConfig storage;

        public LanceVectorFieldType(String name, int dims, String similarity, LanceStorageConfig storage) {
            super(name, false, false, false, Collections.emptyMap());
            this.dims = dims;
            this.similarity = similarity == null ? "cosine" : similarity;
            this.storage = storage;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException("term queries not supported for lance_vector");
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return SourceValueFetcher.identity(name(), context, format);
        }

        /**
         * Create a kNN query for Lance vector search (10-parameter version for ES framework compatibility).
         * <p>
         * This method is called by Elasticsearch core via reflection.
         * Uses default values for indexName and shardId.
         *
         * @deprecated Use {@link #createKnnQuery(VectorData, int, int, Float, Float, Query, Float, BitSetProducer, FilterHeuristic, boolean, String, int)} instead.
         */
        @Deprecated
        public Query createKnnQuery(
            VectorData queryVector,
            int k,
            int numCands,
            Float visitPercentage,  // Ignored - Lance doesn't use this
            Float oversample,  // Ignored - Lance doesn't use this
            Query filter,
            Float vectorSimilarity,  // Ignored - Lance uses its own similarity
            org.apache.lucene.search.join.BitSetProducer parentFilter,  // Ignored - Lance doesn't support nested
            DenseVectorFieldMapper.FilterHeuristic heuristic,  // Ignored - Lance uses its own search strategy
            boolean hnswEarlyTermination  // Ignored - Lance doesn't use HNSW
        ) {
            return createKnnQuery(
                queryVector,
                k,
                numCands,
                visitPercentage,
                oversample,
                filter,
                vectorSimilarity,
                parentFilter,
                heuristic,
                hnswEarlyTermination,
                "",
                -1
            );
        }

        /**
         * Create a kNN query for Lance vector search.
         * <p>
         * Supports both legacy single-URI mode and shard-aware mode with uri_prefix.
         * The first 10 parameters mirror DenseVectorFieldType.createKnnQuery (most are ignored).
         * The last two ({@code indexName}, {@code shardId}) enable shard-aware URI resolution.
         *
         * @param queryVector The query vector
         * @param k Number of nearest neighbors to return
         * @param numCands Number of candidates to fetch from Lance
         * @param visitPercentage Ignored - Lance doesn't use this
         * @param oversample Ignored - Lance doesn't use this
         * @param filter Optional Lucene filter to apply
         * @param vectorSimilarity Ignored - Lance uses its own similarity
         * @param parentFilter Ignored - Lance doesn't support nested
         * @param heuristic Ignored - Lance uses its own PreFilterHeuristic
         * @param hnswEarlyTermination Ignored - Lance doesn't use HNSW
         * @param indexName Index name for shard-aware URI resolution
         * @param shardId Shard ID for shard-aware URI resolution (-1 if unknown)
         */
        public Query createKnnQuery(
            VectorData queryVector,
            int k,
            int numCands,
            Float visitPercentage,  // Ignored - Lance doesn't use this
            Float oversample,  // Ignored - Lance doesn't use this
            Query filter,
            Float vectorSimilarity,  // Ignored - Lance uses its own similarity
            org.apache.lucene.search.join.BitSetProducer parentFilter,  // Ignored - Lance doesn't support nested
            DenseVectorFieldMapper.FilterHeuristic heuristic,  // Ignored - Lance uses its own search strategy
            boolean hnswEarlyTermination,  // Ignored - Lance doesn't use HNSW
            String indexName,
            int shardId
        ) {
            float[] vector = queryVector.isFloat() ? queryVector.asFloatVector() : toFloat(queryVector.asByteVector());
            if (vector.length != dims) {
                throw new IllegalArgumentException("query vector dims mismatch expected=" + dims + " got=" + vector.length);
            }
            // Use PreFilterHeuristic.AUTO as default; can be made configurable via index settings in the future
            PreFilterHeuristic prefilterHeuristic = PreFilterHeuristic.AUTO;
            return new LanceKnnQuery(
                name(),
                storage,
                indexName,
                shardId,
                vector,
                k,
                numCands,
                similarity,
                filter,
                dims,
                prefilterHeuristic
            );
        }

        private static float[] toFloat(byte[] bytes) {
            float[] result = new float[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                result[i] = bytes[i];
            }
            return result;
        }

        public int getDims() {
            return dims;
        }

        public String getSimilarity() {
            return similarity;
        }

        public LanceStorageConfig getStorage() {
            return storage;
        }
    }

    private LanceVectorFieldMapper(String simpleName, MappedFieldType mappedFieldType, BuilderParams params) {
        super(simpleName, mappedFieldType, params);
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        // Phase 1 is read-only; we ignore any provided value. Field exists only in mapping.
        if (context.parser().currentToken() != null) {
            context.parser().skipChildren();
        }
    }

    @Override
    public LanceVectorFieldType fieldType() {
        return (LanceVectorFieldType) super.fieldType();
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        LanceVectorFieldType ft = fieldType();
        return new Builder(leafName(), ft.dims, ft.similarity, ft.storage, IndexVersion.current());
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, Params params) throws IOException {
        super.doXContentBody(builder, params);
        LanceVectorFieldType ft = fieldType();
        builder.field(DIMS_FIELD, ft.dims);
        builder.field(SIMILARITY_FIELD, ft.similarity);
        builder.startObject(STORAGE_FIELD);
        builder.field("type", ft.storage.type());
        if (ft.storage.isShardAware()) {
            builder.field("uri_prefix", ft.storage.uriPrefix());
            if (ft.storage.shardPath() != null) {
                builder.field("shard_path", ft.storage.shardPath());
            }
            if (ft.storage.datasetName() != null) {
                builder.field("dataset_name", ft.storage.datasetName());
            }
        } else {
            builder.field("uri", ft.storage.uri());
        }
        builder.field("lance_id_column", ft.storage.idColumn());
        builder.field("lance_vector_column", ft.storage.vectorColumn());
        builder.field("read_only", true);
        builder.endObject();
    }
}
