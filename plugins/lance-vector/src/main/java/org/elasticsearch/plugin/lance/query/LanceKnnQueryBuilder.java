/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryValidationException;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.plugin.lance.mapper.LanceVectorFieldMapper.LanceVectorFieldType;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Query builder for Lance kNN search.
 * <p>
 * Supports ES 9.x filter syntax — both single object and array of filter objects:
 * <pre>
 * {
 *   "query": {
 *     "lance_knn": {
 *       "field": "vector",
 *       "query_vector": [0.1, 0.2, ...],
 *       "k": 10,
 *       "num_candidates": 100,
 *       "filter": { "term": { "color": "red" } }
 *     }
 *   }
 * }
 * </pre>
 * or:
 * <pre>
 * {
 *   "query": {
 *     "lance_knn": {
 *       "field": "vector",
 *       "query_vector": [0.1, 0.2, ...],
 *       "k": 10,
 *       "filter": [
 *         { "term": { "color": "red" } },
 *         { "range": { "price": { "lte": 100 } } }
 *       ]
 *     }
 *   }
 * }
 * </pre>
 */
public class LanceKnnQueryBuilder extends AbstractQueryBuilder<LanceKnnQueryBuilder> {
    public static final String NAME = "lance_knn";

    private static final ParseField FIELD_FIELD = new ParseField("field");
    private static final ParseField QUERY_VECTOR_FIELD = new ParseField("query_vector");
    private static final ParseField K_FIELD = new ParseField("k");
    private static final ParseField NUM_CANDIDATES_FIELD = new ParseField("num_candidates");
    private static final ParseField FILTER_FIELD = new ParseField("filter");
    private static final ParseField NPROBES_FIELD = new ParseField("nprobes");

    private static final int DEFAULT_NPROBES = 20;

    private final String fieldName;
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final List<QueryBuilder> filterQueries;
    private final int nprobes;

    /**
     * Construct a new LanceKnnQueryBuilder with filter queries.
     *
     * @param fieldName     The name of the lance_vector field
     * @param queryVector   The query vector
     * @param k             The number of nearest neighbors to return
     * @param numCandidates The number of candidates to consider
     * @param filterQueries Filter queries to apply (single or array, ES 9.x style)
     */
    public LanceKnnQueryBuilder(String fieldName, float[] queryVector, int k, int numCandidates, List<QueryBuilder> filterQueries) {
        this(fieldName, queryVector, k, numCandidates, filterQueries, DEFAULT_NPROBES);
    }

    /**
     * Construct a new LanceKnnQueryBuilder with filter queries and nprobes.
     *
     * @param fieldName     The name of the lance_vector field
     * @param queryVector   The query vector
     * @param k             The number of nearest neighbors to return
     * @param numCandidates The number of candidates to consider
     * @param filterQueries Filter queries to apply (single or array, ES 9.x style)
     * @param nprobes       Number of IVF partitions to probe (1-100)
     */
    public LanceKnnQueryBuilder(
        String fieldName,
        float[] queryVector,
        int k,
        int numCandidates,
        List<QueryBuilder> filterQueries,
        int nprobes
    ) {
        this.fieldName = fieldName;
        this.queryVector = queryVector;
        this.k = k;
        this.numCandidates = numCandidates;
        this.filterQueries = filterQueries == null ? List.of() : List.copyOf(filterQueries);
        this.nprobes = nprobes;
    }

    /**
     * Backward-compatible constructor without filter.
     */
    public LanceKnnQueryBuilder(String fieldName, float[] queryVector, int k, int numCandidates) {
        this(fieldName, queryVector, k, numCandidates, null, DEFAULT_NPROBES);
    }

    /**
     * Read from stream.
     */
    public LanceKnnQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.fieldName = in.readString();
        this.queryVector = in.readFloatArray();
        this.k = in.readVInt();
        this.numCandidates = in.readVInt();
        this.filterQueries = readQueries(in);
        this.nprobes = in.readVInt();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeFloatArray(queryVector);
        out.writeVInt(k);
        out.writeVInt(numCandidates);
        writeQueries(out, filterQueries);
        out.writeVInt(nprobes);
    }

    public List<QueryBuilder> filterQueries() {
        return filterQueries;
    }

    public int nprobes() {
        return nprobes;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(FIELD_FIELD.getPreferredName(), fieldName);
        builder.field(QUERY_VECTOR_FIELD.getPreferredName(), queryVector);
        builder.field(K_FIELD.getPreferredName(), k);
        builder.field(NUM_CANDIDATES_FIELD.getPreferredName(), numCandidates);
        if (nprobes != DEFAULT_NPROBES) {
            builder.field(NPROBES_FIELD.getPreferredName(), nprobes);
        }
        if (filterQueries.isEmpty() == false) {
            builder.startArray(FILTER_FIELD.getPreferredName());
            for (QueryBuilder filterQuery : filterQueries) {
                filterQuery.toXContent(builder, params);
            }
            builder.endArray();
        }
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        MappedFieldType fieldType = context.getFieldType(fieldName);
        if (fieldType instanceof LanceVectorFieldType == false) {
            throw new IllegalArgumentException("field [" + fieldName + "] is not a lance_vector field");
        }

        LanceVectorFieldType lanceFieldType = (LanceVectorFieldType) fieldType;
        VectorData vectorData = VectorData.fromFloats(queryVector);

        // Build combined filter from filterQueries
        Query filter = buildFilterQuery(context);

        int shardId = context.getShardId();
        String indexName = context.index().getName();

        return lanceFieldType.createKnnQuery(
            vectorData,
            k,
            numCandidates,
            null,  // visitPercentage - ignored by Lance
            null,  // oversample - ignored by Lance
            filter,
            null,  // vectorSimilarity - ignored by Lance
            null,  // parentFilter - ignored by Lance (no nested support)
            null,  // heuristic - ignored by Lance
            false,  // hnswEarlyTermination - ignored by Lance
            indexName,  // indexName for shard-aware URI resolution
            shardId,  // shardId for shard-aware URI resolution
            nprobes  // nprobes for IVF partition probing
        );
    }

    private Query buildFilterQuery(SearchExecutionContext context) throws IOException {
        if (filterQueries.isEmpty()) {
            return null;
        }
        if (filterQueries.size() == 1) {
            return filterQueries.get(0).toQuery(context);
        }
        // Multiple filters: combine with BoolQuery must clauses
        BoolQueryBuilder boolQuery = new BoolQueryBuilder();
        for (QueryBuilder fq : filterQueries) {
            boolQuery.filter(fq);
        }
        return boolQuery.toQuery(context);
    }

    protected QueryValidationException validate(SearchExecutionContext context) {
        QueryValidationException validationException = null;
        if (fieldName == null || fieldName.isEmpty()) {
            validationException = QueryValidationException.addValidationError(
                "lance_knn",
                "field name is null or empty",
                validationException
            );
        }
        if (queryVector == null || queryVector.length == 0) {
            validationException = QueryValidationException.addValidationError(
                "lance_knn",
                "query_vector is null or empty",
                validationException
            );
        }
        if (k <= 0) {
            validationException = QueryValidationException.addValidationError(
                "lance_knn",
                "k must be positive, got [" + k + "]",
                validationException
            );
        }
        if (numCandidates <= 0) {
            validationException = QueryValidationException.addValidationError(
                "lance_knn",
                "num_candidates must be positive, got [" + numCandidates + "]",
                validationException
            );
        }
        return validationException;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(fieldName, Arrays.hashCode(queryVector), k, numCandidates, filterQueries, nprobes);
    }

    @Override
    protected boolean doEquals(LanceKnnQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName)
            && Arrays.equals(queryVector, other.queryVector)
            && k == other.k
            && numCandidates == other.numCandidates
            && Objects.equals(filterQueries, other.filterQueries)
            && nprobes == other.nprobes;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersion.zero();
    }

    /**
     * Parse a LanceKnnQueryBuilder from XContent.
     * Supports ES 9.x filter syntax: single object or array of filter objects.
     */
    public static LanceKnnQueryBuilder fromXContent(XContentParser parser) throws IOException {
        String fieldName = null;
        float[] queryVector = null;
        int k = 10;
        int numCandidates = 100;
        int nprobes = DEFAULT_NPROBES;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;
        List<QueryBuilder> filterQueries = new ArrayList<>();

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                if (QUERY_VECTOR_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryVector = parseQueryVector(parser);
                } else if (FILTER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    // Array of filter objects — ES 9.x style
                    while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                        filterQueries.add(AbstractQueryBuilder.parseTopLevelQuery(parser));
                    }
                } else {
                    throw new IllegalArgumentException("unknown array field [" + currentFieldName + "]");
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (FILTER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    // Single filter object
                    filterQueries.add(AbstractQueryBuilder.parseTopLevelQuery(parser));
                } else {
                    throw new IllegalArgumentException("unknown object field [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if (FIELD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fieldName = parser.text();
                } else if (K_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    k = parser.intValue(true);
                } else if (NUM_CANDIDATES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    numCandidates = parser.intValue(true);
                } else if (NPROBES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    nprobes = parser.intValue(true);
                } else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else {
                    throw new IllegalArgumentException("unknown field [" + currentFieldName + "]");
                }
            }
        }

        if (fieldName == null) {
            throw new IllegalArgumentException("field [" + FIELD_FIELD.getPreferredName() + "] is required");
        }
        if (queryVector == null) {
            throw new IllegalArgumentException("query_vector is required");
        }

        LanceKnnQueryBuilder builder = new LanceKnnQueryBuilder(fieldName, queryVector, k, numCandidates, filterQueries, nprobes);
        builder.boost(boost);
        if (queryName != null) {
            builder.queryName(queryName);
        }
        return builder;
    }

    private static float[] parseQueryVector(XContentParser parser) throws IOException {
        ArrayList<Float> vector = new ArrayList<>();
        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
            vector.add((float) parser.doubleValue());
        }
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = vector.get(i);
        }
        return result;
    }
}
