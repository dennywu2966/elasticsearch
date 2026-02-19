/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class LanceKnnQueryIdExtractionTests extends ESTestCase {

    public void testCreateArrowIdVectorFromStringIds() throws Exception {
        List<String> ids = List.of("doc1", "doc2", "doc3");
        try (BufferAllocator allocator = new RootAllocator(1024 * 1024)) {
            try (VarCharVector vector = LanceKnnQuery.createArrowIdVector(ids, allocator)) {
                assertNotNull(vector);
                assertEquals(3, vector.getValueCount());
                assertEquals("doc1", new String(vector.get(0)));
                assertEquals("doc2", new String(vector.get(1)));
                assertEquals("doc3", new String(vector.get(2)));
            }
        }
    }

    public void testCreateArrowIdVectorEmpty() throws Exception {
        List<String> ids = List.of();
        try (BufferAllocator allocator = new RootAllocator(1024 * 1024)) {
            try (VarCharVector vector = LanceKnnQuery.createArrowIdVector(ids, allocator)) {
                assertNotNull(vector);
                assertEquals(0, vector.getValueCount());
            }
        }
    }

    public void testCreateArrowIdVectorWithUnicode() throws Exception {
        List<String> ids = List.of("doc-\u00e9\u00e0\u00fc", "id-\u4e2d\u6587");
        try (BufferAllocator allocator = new RootAllocator(1024 * 1024)) {
            try (VarCharVector vector = LanceKnnQuery.createArrowIdVector(ids, allocator)) {
                assertEquals(2, vector.getValueCount());
                assertEquals("doc-\u00e9\u00e0\u00fc", new String(vector.get(0)));
                assertEquals("id-\u4e2d\u6587", new String(vector.get(1)));
            }
        }
    }
}
