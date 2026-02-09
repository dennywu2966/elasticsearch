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

/**
 * Exception thrown when a filter query type is not supported for Lance pushdown.
 * <p>
 * Currently, only term queries are supported in v1. Other query types (range, wildcard,
 * prefix, etc.) trigger fallback to ES post-filtering.
 */
public class UnsupportedQueryException extends LanceFilterConversionException {

    private final Class<? extends Query> queryType;

    /**
     * Constructs a new unsupported query exception.
     *
     * @param queryType the type of query that is not supported
     */
    public UnsupportedQueryException(Class<? extends Query> queryType) {
        super(
            "Query type "
                + queryType.getSimpleName()
                + " not supported for Lance filter pushdown. "
                + "Only term queries are supported in v1."
        );
        this.queryType = queryType;
    }

    /**
     * Constructs a new unsupported query exception with a custom message.
     *
     * @param queryType the type of query that is not supported
     * @param message   a custom message
     */
    public UnsupportedQueryException(Class<? extends Query> queryType, String message) {
        super(message);
        this.queryType = queryType;
    }

    /**
     * Gets the type of query that is not supported.
     *
     * @return the query type
     */
    public Class<? extends Query> getQueryType() {
        return queryType;
    }
}
