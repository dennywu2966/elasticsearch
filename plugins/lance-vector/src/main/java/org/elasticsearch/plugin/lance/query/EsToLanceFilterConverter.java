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
import org.apache.lucene.search.TermQuery;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts Elasticsearch filter queries to Lance SQL WHERE clauses for native filter pushdown.
 * <p>
 * <b>v1 Scope:</b> Supports only term queries on keyword fields.
 * <p>
 * <b>Design:</b> Hybrid try → fallback approach.
 * <ul>
 *   <li>If conversion succeeds: Lance applies SQL filter BEFORE vector search (native prefilter)</li>
 *   <li>If conversion fails: Falls back to ES post-filter (current behavior)</li>
 * </ul>
 * <p>
 * <b>Security:</b> All string values are SQL-escaped to prevent injection.
 * Single quotes are doubled (O'Reilly → O''Reilly) per SQL standard.
 * <p>
 * <b>Field Mapping:</b> Requires index.lance.field_mapping to map ES field names to Lance columns.
 * <p>
 * Example:
 * <pre>
 * // Input
 * TermQuery query = new TermQuery(new Term("category", "electronics"));
 * Map&lt;String, String&gt; mapping = Map.of("category", "product_category");
 *
 * // Output
 * String sql = converter.convert(query, mapping);
 * // Returns: "product_category = 'electronics'"
 * </pre>
 */
public class EsToLanceFilterConverter {
    private static final Pattern SAFE_COLUMN_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Convert an Elasticsearch filter query to a Lance SQL WHERE clause.
     * <p>
     * Supported query types (v1):
     * <ul>
     *   <li>TermQuery: Converts to "column = 'value'"</li>
     * </ul>
     * <p>
     * Unsupported query types throw {@link UnsupportedQueryException},
     * triggering fallback to ES post-filter.
     *
     * @param filter      The Elasticsearch filter query to convert
     * @param fieldMapping Mapping from ES field names to Lance column names (index.lance.field_mapping)
     * @return SQL WHERE clause (without the "WHERE" keyword)
     * @throws LanceFilterConversionException if conversion fails (triggers fallback)
     */
    public String convert(Query filter, Map<String, String> fieldMapping) throws LanceFilterConversionException {
        if (filter == null) {
            throw new LanceFilterConversionException("Filter query is null");
        }

        if (filter instanceof TermQuery termQuery) {
            return convertTermQuery(termQuery, fieldMapping);
        }

        // Unsupported query type - throw to trigger fallback
        throw new UnsupportedQueryException(filter.getClass());
    }

    /**
     * Convert a TermQuery to a SQL WHERE clause.
     * <p>
     * Examples:
     * <ul>
     *   <li>category = "electronics" → "product_category = 'electronics'"</li>
     *   <li>active = true → "is_active = true"</li>
     *   <li>count = 42 → "item_count = 42"</li>
     * </ul>
     *
     * @param termQuery    The term query to convert
     * @param fieldMapping Mapping from ES field names to Lance column names
     * @return SQL WHERE clause
     * @throws LanceFilterConversionException if field not mapped or value is invalid
     */
    private String convertTermQuery(TermQuery termQuery, Map<String, String> fieldMapping) throws LanceFilterConversionException {
        String esFieldName = termQuery.getTerm().field();
        String lanceColumn = fieldMapping.get(esFieldName);

        if (lanceColumn == null) {
            throw new UnmappedFieldException(esFieldName);
        }
        validateColumnName(esFieldName, lanceColumn);

        // Get the value as a string
        String value = termQuery.getTerm().text();

        // Validate value is not null or empty
        if (value == null) {
            throw new LanceFilterConversionException("Term value for field '" + esFieldName + "' is null");
        }

        // Detect value type and format accordingly
        if (isBooleanValue(value)) {
            // Boolean: use true/false without quotes
            return lanceColumn + " = " + Boolean.parseBoolean(value);
        } else if (isNumericValue(value)) {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed) == false) {
                throw new LanceFilterConversionException("Non-finite numeric values are not supported: " + value);
            }
            // Numeric: use as-is without quotes
            return lanceColumn + " = " + value;
        } else {
            // String: escape and quote
            String escapedValue = escapeSql(value);
            return lanceColumn + " = '" + escapedValue + "'";
        }
    }

    /**
     * Escape a string value for safe SQL interpolation.
     * <p>
     * <b>Security:</b> Prevents SQL injection by doubling single quotes.
     * This follows the SQL standard for escaping string literals.
     * <p>
     * Examples:
     * <ul>
     *   <li>"hello" → "hello"</li>
     *   <li>"O'Reilly" → "O''Reilly"</li>
     *   <li>"It's Joe's" → "It''s Joe''s"</li>
     * </ul>
     *
     * @param value the string value to escape
     * @return SQL-safe string with single quotes doubled
     */
    private String escapeSql(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Double single quotes to escape them per SQL standard
        return value.replace("'", "''");
    }

    /**
     * Check if a string value represents a boolean.
     * Recognizes: "true", "false" (case-insensitive)
     */
    private boolean isBooleanValue(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value));
    }

    /**
     * Check if a string value represents a number.
     * Recognizes integers and floating-point numbers (including negative).
     */
    private boolean isNumericValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validateColumnName(String esFieldName, String columnName) throws LanceFilterConversionException {
        if (SAFE_COLUMN_NAME.matcher(columnName).matches() == false) {
            throw new LanceFilterConversionException(
                "Invalid Lance column name for field '" + esFieldName + "': " + columnName
            );
        }
    }

}
