/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.mapper;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.SimpleMappedFieldType;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Field mapper for Lance vector fields.
 * <p>
 * This mapper supports storing vectors in Lance format and enables vector similarity search
 * using Lance's optimized algorithms (IVF-PQ, IVF-HNSW, etc.).
 * <p>
 * Example mapping:
 * <pre>
 * "my_vector": {
 *   "type": "lance_vector",
 *   "dims": 128,
 *   "similarity": "cosine",
 *   "lance_index_type": "IVF_PQ",
 *   "lance_path": "/path/to/lance/index"
 * }
 * </pre>
 */
public class LanceVectorFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "lance_vector";
    public static final short MAX_DIMS_COUNT = 4096;

    /**
     * Supported similarity metrics for Lance vectors.
     */
    public enum LanceSimilarity {
        L2,
        COSINE,
        DOT;

        public static LanceSimilarity fromString(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Supported Lance index types.
     */
    public enum LanceIndexType {
        IVF_PQ,       // Inverted File with Product Quantization
        IVF_HNSW_PQ,  // IVF + HNSW + Product Quantization
        IVF_HNSW_SQ,  // IVF + HNSW + Scalar Quantization
        FLAT,         // Brute force flat index
        HNSW;         // Hierarchical Navigable Small World

        public static LanceIndexType fromString(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT).replace("-", "_"));
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Builder for LanceVectorFieldMapper.
     */
    public static class Builder extends FieldMapper.Builder {

        private final Parameter<Integer> dims = new Parameter<>(
            "dims",
            false,
            () -> null,
            (n, c, o) -> {
                if (o instanceof Integer == false) {
                    throw new MapperParsingException(
                        "Property [dims] on field [" + n + "] must be an integer but got [" + o + "]"
                    );
                }
                return XContentMapValues.nodeIntegerValue(o);
            },
            m -> ((LanceVectorFieldMapper) m).dims,
            XContentBuilder::field,
            Objects::toString
        ).setSerializerCheck((id, ic, v) -> v != null);

        private final Parameter<LanceSimilarity> similarity = new Parameter<>(
            "similarity",
            false,
            () -> LanceSimilarity.L2,
            (n, c, o) -> LanceSimilarity.fromString((String) o),
            m -> ((LanceVectorFieldMapper) m).similarity,
            (b, n, v) -> b.field(n, v.toString()),
            Objects::toString
        );

        private final Parameter<LanceIndexType> indexType = new Parameter<>(
            "lance_index_type",
            true,
            () -> LanceIndexType.IVF_PQ,
            (n, c, o) -> LanceIndexType.fromString((String) o),
            m -> ((LanceVectorFieldMapper) m).indexType,
            (b, n, v) -> b.field(n, v.toString()),
            Objects::toString
        );

        private final Parameter<String> lancePath = new Parameter<>(
            "lance_path",
            true,
            () -> "",
            (n, c, o) -> (String) o,
            m -> ((LanceVectorFieldMapper) m).lancePath,
            XContentBuilder::field,
            Objects::toString
        );

        private final Parameter<String> vectorColumn = new Parameter<>(
            "vector_column",
            true,
            () -> "vector",
            (n, c, o) -> (String) o,
            m -> ((LanceVectorFieldMapper) m).vectorColumn,
            XContentBuilder::field,
            Objects::toString
        );

        private final Parameter<Integer> nprobes = new Parameter<>(
            "nprobes",
            true,
            () -> 20,
            (n, c, o) -> XContentMapValues.nodeIntegerValue(o),
            m -> ((LanceVectorFieldMapper) m).nprobes,
            XContentBuilder::field,
            Objects::toString
        );

        private final Parameter<Integer> refineFactor = new Parameter<>(
            "refine_factor",
            true,
            () -> 10,
            (n, c, o) -> XContentMapValues.nodeIntegerValue(o),
            m -> ((LanceVectorFieldMapper) m).refineFactor,
            XContentBuilder::field,
            Objects::toString
        );

        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private final IndexVersion indexVersionCreated;

        public Builder(String name, IndexVersion indexVersionCreated) {
            super(name);
            this.indexVersionCreated = indexVersionCreated;

            this.dims.addValidator(d -> {
                if (d != null && (d < 1 || d > MAX_DIMS_COUNT)) {
                    throw new MapperParsingException(
                        "The number of dimensions should be in the range [1, " + MAX_DIMS_COUNT + "] but was [" + d + "]"
                    );
                }
            });
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] { dims, similarity, indexType, lancePath, vectorColumn, nprobes, refineFactor, meta };
        }

        @Override
        public LanceVectorFieldMapper build(MapperBuilderContext context) {
            return new LanceVectorFieldMapper(
                leafName(),
                new LanceVectorFieldType(
                    context.buildFullName(leafName()),
                    dims.getValue(),
                    similarity.getValue(),
                    indexType.getValue(),
                    lancePath.getValue(),
                    vectorColumn.getValue(),
                    meta.getValue()
                ),
                builderParams(this, context),
                dims.getValue(),
                similarity.getValue(),
                indexType.getValue(),
                lancePath.getValue(),
                vectorColumn.getValue(),
                nprobes.getValue(),
                refineFactor.getValue()
            );
        }
    }

    /**
     * Field type for Lance vectors.
     */
    public static class LanceVectorFieldType extends SimpleMappedFieldType {

        private final Integer dims;
        private final LanceSimilarity similarity;
        private final LanceIndexType indexType;
        private final String lancePath;
        private final String vectorColumn;

        public LanceVectorFieldType(
            String name,
            Integer dims,
            LanceSimilarity similarity,
            LanceIndexType indexType,
            String lancePath,
            String vectorColumn,
            Map<String, String> meta
        ) {
            super(name, false, false, true, TextSearchInfo.NONE, meta);
            this.dims = dims;
            this.similarity = similarity;
            this.indexType = indexType;
            this.lancePath = lancePath;
            this.vectorColumn = vectorColumn;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        public Integer getDims() {
            return dims;
        }

        public LanceSimilarity getSimilarity() {
            return similarity;
        }

        public LanceIndexType getIndexType() {
            return indexType;
        }

        public String getLancePath() {
            return lancePath;
        }

        public String getVectorColumn() {
            return vectorColumn;
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return SourceValueFetcher.identity(name(), context, format);
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            throw new IllegalArgumentException(
                "[lance_vector] fields do not support sorting, aggregations, or scripting"
            );
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException(
                "Field [" + name() + "] of type [lance_vector] does not support term queries. "
                    + "Use knn queries for vector similarity search."
            );
        }

        @Override
        public DocValueFormat docValueFormat(String format, ZoneId timeZone) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [lance_vector] doesn't support formats.");
            }
            return DocValueFormat.RAW;
        }

        /**
         * Create a Lance vector query for similarity search.
         */
        public Query createLanceQuery(
            float[] queryVector,
            int k,
            Integer nprobes,
            Integer refineFactor,
            Query preFilter
        ) {
            if (dims != null && queryVector.length != dims) {
                throw new IllegalArgumentException(
                    "Query vector dimension [" + queryVector.length + "] does not match field dimension [" + dims + "]"
                );
            }
            return new LanceVectorQuery(
                name(),
                queryVector,
                k,
                lancePath,
                vectorColumn,
                similarity,
                indexType,
                nprobes != null ? nprobes : 20,
                refineFactor != null ? refineFactor : 10,
                preFilter
            );
        }
    }

    private final Integer dims;
    private final LanceSimilarity similarity;
    private final LanceIndexType indexType;
    private final String lancePath;
    private final String vectorColumn;
    private final int nprobes;
    private final int refineFactor;

    private LanceVectorFieldMapper(
        String simpleName,
        LanceVectorFieldType fieldType,
        BuilderParams builderParams,
        Integer dims,
        LanceSimilarity similarity,
        LanceIndexType indexType,
        String lancePath,
        String vectorColumn,
        int nprobes,
        int refineFactor
    ) {
        super(simpleName, fieldType, builderParams);
        this.dims = dims;
        this.similarity = similarity;
        this.indexType = indexType;
        this.lancePath = lancePath;
        this.vectorColumn = vectorColumn;
        this.nprobes = nprobes;
        this.refineFactor = refineFactor;
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
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        XContentParser parser = context.parser();
        XContentParser.Token token = parser.currentToken();

        if (token == XContentParser.Token.VALUE_NULL) {
            return;
        }

        float[] vector;
        if (token == XContentParser.Token.START_ARRAY) {
            vector = parseVectorArray(parser);
        } else if (token == XContentParser.Token.VALUE_STRING) {
            // Support base64 encoded vectors
            vector = parseBase64Vector(parser.text());
        } else {
            throw new MapperParsingException(
                "Expected array or base64 string for [" + fullPath() + "] but got [" + token + "]"
            );
        }

        if (dims != null && vector.length != dims) {
            throw new MapperParsingException(
                "Vector dimension [" + vector.length + "] does not match configured dimension [" + dims + "]"
            );
        }

        // Store vector as binary doc values
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        context.doc().add(new BinaryDocValuesField(fullPath(), new BytesRef(buffer.array())));

        // Also store the vector for _source reconstruction if needed
        if (hasDocValues() == false) {
            context.doc().add(new StoredField(fullPath(), buffer.array()));
        }
    }

    private float[] parseVectorArray(XContentParser parser) throws IOException {
        float[] buffer = new float[1024];
        int index = 0;

        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            if (index >= buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            if (token == XContentParser.Token.VALUE_NUMBER) {
                buffer[index++] = parser.floatValue();
            } else {
                throw new MapperParsingException(
                    "Expected number in vector array but got [" + token + "]"
                );
            }
        }

        return Arrays.copyOf(buffer, index);
    }

    private float[] parseBase64Vector(String base64) {
        byte[] bytes = java.util.Base64.getDecoder().decode(base64);
        if (bytes.length % Float.BYTES != 0) {
            throw new MapperParsingException(
                "Base64 vector length [" + bytes.length + "] is not a multiple of float size"
            );
        }

        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }

        return vector;
    }

    @Override
    public Builder getMergeBuilder() {
        return new Builder(leafName(), IndexVersion.current()).init(this);
    }
}
