/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance.mapper;

import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Query builder for Lance vector similarity search.
 * <p>
 * Example usage:
 * <pre>
 * {
 *   "lance_vector": {
 *     "field": "my_vector",
 *     "query_vector": [0.1, 0.2, 0.3, ...],
 *     "k": 10,
 *     "nprobes": 20,
 *     "refine_factor": 10,
 *     "filter": { ... }
 *   }
 * }
 * </pre>
 */
public class LanceVectorQueryBuilder extends AbstractQueryBuilder<LanceVectorQueryBuilder> {

    public static final String NAME = "lance_vector";
    public static final ParseField FIELD_FIELD = new ParseField("field");
    public static final ParseField QUERY_VECTOR_FIELD = new ParseField("query_vector");
    public static final ParseField K_FIELD = new ParseField("k");
    public static final ParseField NPROBES_FIELD = new ParseField("nprobes");
    public static final ParseField REFINE_FACTOR_FIELD = new ParseField("refine_factor");
    public static final ParseField FILTER_FIELD = new ParseField("filter");

    private static final ConstructingObjectParser<LanceVectorQueryBuilder, Void> PARSER = new ConstructingObjectParser<>(
        NAME,
        args -> {
            String field = (String) args[0];
            @SuppressWarnings("unchecked")
            List<Float> queryVectorList = (List<Float>) args[1];
            float[] queryVector = new float[queryVectorList.size()];
            for (int i = 0; i < queryVectorList.size(); i++) {
                queryVector[i] = queryVectorList.get(i);
            }
            int k = args[2] != null ? (Integer) args[2] : 10;
            Integer nprobes = (Integer) args[3];
            Integer refineFactor = (Integer) args[4];
            QueryBuilder filter = (QueryBuilder) args[5];

            LanceVectorQueryBuilder builder = new LanceVectorQueryBuilder(field, queryVector, k);
            if (nprobes != null) {
                builder.nprobes(nprobes);
            }
            if (refineFactor != null) {
                builder.refineFactor(refineFactor);
            }
            if (filter != null) {
                builder.filter(filter);
            }
            return builder;
        }
    );

    static {
        PARSER.declareString(constructorArg(), FIELD_FIELD);
        PARSER.declareFloatArray(constructorArg(), QUERY_VECTOR_FIELD);
        PARSER.declareInt(optionalConstructorArg(), K_FIELD);
        PARSER.declareInt(optionalConstructorArg(), NPROBES_FIELD);
        PARSER.declareInt(optionalConstructorArg(), REFINE_FACTOR_FIELD);
        PARSER.declareObject(optionalConstructorArg(), (p, c) -> parseInnerQueryBuilder(p), FILTER_FIELD);
        declareStandardFields(PARSER);
    }

    private final String field;
    private final float[] queryVector;
    private final int k;
    private Integer nprobes;
    private Integer refineFactor;
    private QueryBuilder filter;

    public LanceVectorQueryBuilder(String field, float[] queryVector, int k) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field cannot be null or empty");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("query_vector cannot be null or empty");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        this.field = field;
        this.queryVector = queryVector;
        this.k = k;
    }

    public LanceVectorQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.field = in.readString();
        this.queryVector = in.readFloatArray();
        this.k = in.readVInt();
        this.nprobes = in.readOptionalVInt();
        this.refineFactor = in.readOptionalVInt();
        this.filter = in.readOptionalNamedWriteable(QueryBuilder.class);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(field);
        out.writeFloatArray(queryVector);
        out.writeVInt(k);
        out.writeOptionalVInt(nprobes);
        out.writeOptionalVInt(refineFactor);
        out.writeOptionalNamedWriteable(filter);
    }

    public LanceVectorQueryBuilder nprobes(int nprobes) {
        if (nprobes <= 0) {
            throw new IllegalArgumentException("nprobes must be positive");
        }
        this.nprobes = nprobes;
        return this;
    }

    public LanceVectorQueryBuilder refineFactor(int refineFactor) {
        if (refineFactor <= 0) {
            throw new IllegalArgumentException("refine_factor must be positive");
        }
        this.refineFactor = refineFactor;
        return this;
    }

    public LanceVectorQueryBuilder filter(QueryBuilder filter) {
        this.filter = filter;
        return this;
    }

    public String getField() {
        return field;
    }

    public float[] getQueryVector() {
        return queryVector;
    }

    public int getK() {
        return k;
    }

    public Integer getNprobes() {
        return nprobes;
    }

    public Integer getRefineFactor() {
        return refineFactor;
    }

    public QueryBuilder getFilter() {
        return filter;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(FIELD_FIELD.getPreferredName(), field);
        builder.array(QUERY_VECTOR_FIELD.getPreferredName(), queryVector);
        builder.field(K_FIELD.getPreferredName(), k);
        if (nprobes != null) {
            builder.field(NPROBES_FIELD.getPreferredName(), nprobes);
        }
        if (refineFactor != null) {
            builder.field(REFINE_FACTOR_FIELD.getPreferredName(), refineFactor);
        }
        if (filter != null) {
            builder.field(FILTER_FIELD.getPreferredName(), filter);
        }
        boostAndQueryNameToXContent(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        MappedFieldType fieldType = context.getFieldType(field);
        if (fieldType == null) {
            throw new IllegalArgumentException("Field [" + field + "] does not exist in the mapping");
        }
        if (fieldType instanceof LanceVectorFieldMapper.LanceVectorFieldType == false) {
            throw new IllegalArgumentException(
                "Field [" + field + "] is not a lance_vector field. Expected [lance_vector] but got [" + fieldType.typeName() + "]"
            );
        }

        LanceVectorFieldMapper.LanceVectorFieldType lanceFieldType = (LanceVectorFieldMapper.LanceVectorFieldType) fieldType;

        Query preFilter = null;
        if (filter != null) {
            preFilter = filter.toQuery(context);
        }

        return lanceFieldType.createLanceQuery(queryVector, k, nprobes, refineFactor, preFilter);
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        if (filter != null) {
            QueryBuilder rewrittenFilter = filter.rewrite(queryRewriteContext);
            if (rewrittenFilter != filter) {
                return new LanceVectorQueryBuilder(field, queryVector, k)
                    .nprobes(nprobes != null ? nprobes : 20)
                    .refineFactor(refineFactor != null ? refineFactor : 10)
                    .filter(rewrittenFilter)
                    .boost(boost())
                    .queryName(queryName());
            }
        }
        return this;
    }

    @Override
    protected boolean doEquals(LanceVectorQueryBuilder other) {
        return Objects.equals(field, other.field)
            && Arrays.equals(queryVector, other.queryVector)
            && k == other.k
            && Objects.equals(nprobes, other.nprobes)
            && Objects.equals(refineFactor, other.refineFactor)
            && Objects.equals(filter, other.filter);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(field, Arrays.hashCode(queryVector), k, nprobes, refineFactor, filter);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.MINIMUM_COMPATIBLE;
    }

    public static LanceVectorQueryBuilder fromXContent(XContentParser parser) throws IOException {
        return PARSER.apply(parser, null);
    }
}
