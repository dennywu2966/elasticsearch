/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.lance;

import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperTestCase;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.lance.mapper.LanceVectorFieldMapper;
import org.junit.Before;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

/**
 * Tests for {@link LanceVectorFieldMapper}.
 */
public class LanceVectorFieldMapperTests extends MapperTestCase {

    @Override
    protected Collection<? extends Plugin> getPlugins() {
        return List.of(new TestLanceIndexPlugin());
    }

    @Override
    protected void minimalMapping(XContentBuilder b) throws IOException {
        b.field("type", "lance_vector").field("dims", 3);
    }

    @Override
    protected Object getSampleValueForDocument() {
        return List.of(0.1f, 0.2f, 0.3f);
    }

    @Override
    protected void registerParameters(ParameterChecker checker) throws IOException {
        checker.registerConflictCheck("dims", b -> b.field("dims", 4));
        checker.registerConflictCheck("similarity", b -> b.field("similarity", "cosine"));
    }

    public void testDefaults() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        MappedFieldType fieldType = mapper.mappers().getFieldType("field");
        assertThat(fieldType, instanceOf(LanceVectorFieldMapper.LanceVectorFieldType.class));

        LanceVectorFieldMapper.LanceVectorFieldType lanceFieldType =
            (LanceVectorFieldMapper.LanceVectorFieldType) fieldType;
        assertThat(lanceFieldType.getDims(), equalTo(3));
        assertThat(lanceFieldType.getSimilarity(), equalTo(LanceVectorFieldMapper.LanceSimilarity.L2));
        assertThat(lanceFieldType.getIndexType(), equalTo(LanceVectorFieldMapper.LanceIndexType.IVF_PQ));
    }

    public void testDimensionValidation() {
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(fieldMapping(b -> b.field("type", "lance_vector").field("dims", 0)))
        );
        assertThat(e.getMessage(), containsString("The number of dimensions should be in the range"));
    }

    public void testMaxDimensions() {
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(fieldMapping(b -> b.field("type", "lance_vector").field("dims", 5000)))
        );
        assertThat(e.getMessage(), containsString("The number of dimensions should be in the range"));
    }

    public void testSimilarityOptions() throws IOException {
        for (LanceVectorFieldMapper.LanceSimilarity similarity : LanceVectorFieldMapper.LanceSimilarity.values()) {
            DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
                b.field("type", "lance_vector")
                    .field("dims", 128)
                    .field("similarity", similarity.toString())
            ));

            LanceVectorFieldMapper.LanceVectorFieldType fieldType =
                (LanceVectorFieldMapper.LanceVectorFieldType) mapper.mappers().getFieldType("field");
            assertThat(fieldType.getSimilarity(), equalTo(similarity));
        }
    }

    public void testIndexTypeOptions() throws IOException {
        for (LanceVectorFieldMapper.LanceIndexType indexType : LanceVectorFieldMapper.LanceIndexType.values()) {
            DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
                b.field("type", "lance_vector")
                    .field("dims", 128)
                    .field("lance_index_type", indexType.toString())
            ));

            LanceVectorFieldMapper.LanceVectorFieldType fieldType =
                (LanceVectorFieldMapper.LanceVectorFieldType) mapper.mappers().getFieldType("field");
            assertThat(fieldType.getIndexType(), equalTo(indexType));
        }
    }

    public void testParseVectorArray() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector").field("dims", 3)
        ));

        ParsedDocument doc = mapper.parse(source(b ->
            b.array("field", 0.1, 0.2, 0.3)
        ));

        assertNotNull(doc.rootDoc().getBinaryValue("field"));
    }

    public void testParseBase64Vector() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector").field("dims", 3)
        ));

        // Create base64 encoded vector [0.1, 0.2, 0.3]
        String base64Vector = java.util.Base64.getEncoder().encodeToString(
            new byte[] {
                (byte) 0xCD, (byte) 0xCC, (byte) 0xCC, (byte) 0x3D,  // 0.1 in little endian
                (byte) 0xCD, (byte) 0xCC, (byte) 0x4C, (byte) 0x3E,  // 0.2 in little endian
                (byte) 0x9A, (byte) 0x99, (byte) 0x99, (byte) 0x3E   // 0.3 in little endian
            }
        );

        ParsedDocument doc = mapper.parse(source(b ->
            b.field("field", base64Vector)
        ));

        assertNotNull(doc.rootDoc().getBinaryValue("field"));
    }

    public void testDimensionMismatch() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector").field("dims", 3)
        ));

        Exception e = expectThrows(
            MapperParsingException.class,
            () -> mapper.parse(source(b -> b.array("field", 0.1, 0.2, 0.3, 0.4)))
        );
        assertThat(e.getMessage(), containsString("does not match configured dimension"));
    }

    public void testLancePath() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector")
                .field("dims", 128)
                .field("lance_path", "/path/to/lance/data")
        ));

        LanceVectorFieldMapper.LanceVectorFieldType fieldType =
            (LanceVectorFieldMapper.LanceVectorFieldType) mapper.mappers().getFieldType("field");
        assertThat(fieldType.getLancePath(), equalTo("/path/to/lance/data"));
    }

    public void testVectorColumn() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector")
                .field("dims", 128)
                .field("vector_column", "embedding")
        ));

        LanceVectorFieldMapper.LanceVectorFieldType fieldType =
            (LanceVectorFieldMapper.LanceVectorFieldType) mapper.mappers().getFieldType("field");
        assertThat(fieldType.getVectorColumn(), equalTo("embedding"));
    }

    public void testNullValue() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b ->
            b.field("type", "lance_vector").field("dims", 3)
        ));

        ParsedDocument doc = mapper.parse(source(b ->
            b.nullField("field")
        ));

        assertNull(doc.rootDoc().getBinaryValue("field"));
    }

    /**
     * Test plugin without license check for unit tests.
     */
    public static class TestLanceIndexPlugin extends LanceIndexPlugin {
        public TestLanceIndexPlugin() {
            super(org.elasticsearch.common.settings.Settings.EMPTY);
        }

        @Override
        protected org.elasticsearch.license.XPackLicenseState getLicenseState() {
            // Return a mock license state that always allows the feature
            return null;
        }

        @Override
        public java.util.Map<String, org.elasticsearch.index.mapper.Mapper.TypeParser> getMappers() {
            return java.util.Map.of(
                LanceVectorFieldMapper.CONTENT_TYPE,
                new org.elasticsearch.index.mapper.FieldMapper.TypeParser(
                    (n, c) -> new LanceVectorFieldMapper.Builder(n, c.indexVersionCreated()),
                    org.elasticsearch.index.mapper.FieldMapper.notInMultiFields(LanceVectorFieldMapper.CONTENT_TYPE)
                )
            );
        }
    }
}
