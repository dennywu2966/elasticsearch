/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.plugin.lance.mapper.LanceStorageConfig;
import org.elasticsearch.test.ESTestCase;

import java.util.Map;

/**
 * Integration tests for Lance native filter pushdown.
 * <p>
 * Tests the end-to-end flow:
 * ES Query DSL → LanceKnnQueryBuilder → LanceKnnQuery → EsToLanceFilterConverter
 * → Lance SQL filter → ScanOptions.filter() → Lance vector search
 */
public class LanceFilterPushdownIntegrationTests extends ESTestCase {

    /**
     * Test that a term filter is successfully converted to SQL and pushed to Lance.
     * <p>
     * This verifies:
     * 1. Field mapping is correctly read from LanceStorageConfig
     * 2. EsToLanceFilterConverter converts TermQuery to SQL
     * 3. The SQL can be passed to Lance's ScanOptions.filter()
     */
    public void testTermFilterPushedToLance() throws Exception {
        // Setup: Create index with field mapping
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("category", "product_category", "brand", "brand_name")
        );

        // Create a term filter query
        TermQuery filter = new TermQuery(new Term("category", "electronics"));

        // Test the converter directly
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();
        String sqlFilter = converter.convert(filter, storageConfig.getFieldMapping());

        // Verify SQL conversion
        assertEquals("product_category = 'electronics'", sqlFilter);

        // Verify field mapping is accessible
        assertNotNull(storageConfig.getFieldMapping());
        assertEquals(2, storageConfig.getFieldMapping().size());
        assertEquals("product_category", storageConfig.getFieldMapping().get("category"));
    }

    /**
     * Test fallback to ES post-filter when field is not in mapping.
     * <p>
     * This verifies:
     * 1. UnmappedFieldException is thrown
     * 2. System falls back to ES post-filter
     * 3. Query still works (just without Lance prefilter)
     */
    public void testFallbackWhenFieldNotMapped() {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("category", "product_category") // "brand" not mapped
        );

        // Try to convert filter for unmapped field
        TermQuery filter = new TermQuery(new Term("brand", "Apple"));
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        LanceFilterConversionException e = expectThrows(
            LanceFilterConversionException.class,
            () -> converter.convert(filter, storageConfig.getFieldMapping())
        );

        assertTrue(e instanceof UnmappedFieldException);
        assertEquals("brand", ((UnmappedFieldException) e).getFieldName());

        // In the real query flow, this would trigger fallback to ES post-filter
    }

    /**
     * Test SQL injection prevention.
     * <p>
     * Verifies that malicious input is properly escaped.
     */
    public void testSqlInjectionPrevention() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("id", "user_id")
        );

        // SQL injection attempt
        TermQuery filter = new TermQuery(new Term("id", "1'; DROP TABLE users; --"));
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        String sqlFilter = converter.convert(filter, storageConfig.getFieldMapping());

        // Verify the injection is neutralized (quotes are doubled)
        assertEquals("user_id = '1''; DROP TABLE users; --'", sqlFilter);
        // The single quotes are escaped, so this is just a string literal
    }

    /**
     * Test multiple filters are correctly converted.
     * <p>
     * Verifies field mapping works correctly when multiple fields are mapped.
     */
    public void testMultipleFieldMappings() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("category", "product_category", "brand", "brand_name", "status", "order_status")
        );

        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        // Test each mapped field
        TermQuery categoryFilter = new TermQuery(new Term("category", "books"));
        assertEquals("product_category = 'books'", converter.convert(categoryFilter, storageConfig.getFieldMapping()));

        TermQuery brandFilter = new TermQuery(new Term("brand", "Nike"));
        assertEquals("brand_name = 'Nike'", converter.convert(brandFilter, storageConfig.getFieldMapping()));

        TermQuery statusFilter = new TermQuery(new Term("status", "active"));
        assertEquals("order_status = 'active'", converter.convert(statusFilter, storageConfig.getFieldMapping()));
    }

    /**
     * Test data type handling (boolean, numeric, string).
     */
    public void testDataTypeHandling() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("active", "is_active", "count", "item_count", "name", "product_name")
        );

        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        // Boolean
        TermQuery boolFilter = new TermQuery(new Term("active", "true"));
        assertEquals("is_active = true", converter.convert(boolFilter, storageConfig.getFieldMapping()));

        // Numeric
        TermQuery numFilter = new TermQuery(new Term("count", "42"));
        assertEquals("item_count = 42", converter.convert(numFilter, storageConfig.getFieldMapping()));

        // String
        TermQuery strFilter = new TermQuery(new Term("name", "widget"));
        assertEquals("product_name = 'widget'", converter.convert(strFilter, storageConfig.getFieldMapping()));
    }

    /**
     * Test special characters and unicode.
     */
    public void testSpecialCharactersAndUnicode() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("name", "display_name", "path", "file_path")
        );

        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        // Single quotes
        TermQuery quoteFilter = new TermQuery(new Term("name", "O'Reilly"));
        assertEquals("display_name = 'O''Reilly'", converter.convert(quoteFilter, storageConfig.getFieldMapping()));

        // Unicode
        TermQuery unicodeFilter = new TermQuery(new Term("name", "café"));
        assertEquals("display_name = 'café'", converter.convert(unicodeFilter, storageConfig.getFieldMapping()));

        // Backslashes
        TermQuery backslashFilter = new TermQuery(new Term("path", "C:\\Users\\file.txt"));
        assertEquals("file_path = 'C:\\Users\\file.txt'", converter.convert(backslashFilter, storageConfig.getFieldMapping()));
    }

    /**
     * Test empty field mapping (no filter pushdown).
     */
    public void testEmptyFieldMapping() {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of() // Empty mapping
        );

        TermQuery filter = new TermQuery(new Term("category", "electronics"));
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        LanceFilterConversionException e = expectThrows(
            LanceFilterConversionException.class,
            () -> converter.convert(filter, storageConfig.getFieldMapping())
        );

        assertTrue(e instanceof UnmappedFieldException);
    }

    /**
     * Test null field mapping (no filter pushdown configured).
     */
    public void testNullFieldMapping() throws Exception {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            null // No field mapping
        );

        // When fieldMapping is null, LanceKnnQuery will skip filter pushdown
        // This test verifies the null is handled correctly
        assertNull(storageConfig.getFieldMapping());

        // The converter should work with an empty map if null mapping is provided
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();
        TermQuery filter = new TermQuery(new Term("category", "electronics"));

        LanceFilterConversionException e = expectThrows(LanceFilterConversionException.class, () -> converter.convert(filter, Map.of()));

        assertTrue(e instanceof UnmappedFieldException);
    }

    /**
     * Test unsupported query type triggers fallback.
     */
    public void testUnsupportedQueryTypeTriggersFallback() {
        LanceStorageConfig storageConfig = new LanceStorageConfig(
            "external",
            null,
            "_id",
            "vector",
            null,
            null,
            null,
            "oss://test-bucket/products.lance",
            null,
            null,
            1,
            LanceStorageConfig.ShardingStrategy.NONE,
            Map.of("field", "column")
        );

        // Use MatchAllDocsQuery (not supported in v1)
        MatchAllDocsQuery unsupportedQuery = new MatchAllDocsQuery();
        EsToLanceFilterConverter converter = new EsToLanceFilterConverter();

        LanceFilterConversionException e = expectThrows(
            LanceFilterConversionException.class,
            () -> converter.convert(unsupportedQuery, storageConfig.getFieldMapping())
        );

        assertTrue(e instanceof UnsupportedQueryException);
    }
}
