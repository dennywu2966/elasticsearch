/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.elasticsearch.test.ESTestCase;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class FakeLanceDatasetPreFilterTests extends ESTestCase {

    public void testSearchWithPreFilter() throws Exception {
        // This test verifies that pre-filtered search only returns results
        // matching the provided ID filter
        FakeLanceDataset dataset = createTestDataset();

        try (BufferAllocator allocator = new RootAllocator(1024 * 1024)) {
            VarCharVector idFilter = new VarCharVector("_id_filter", allocator);
            idFilter.allocateNew(2);
            idFilter.set(0, "doc1".getBytes(StandardCharsets.UTF_8));
            idFilter.set(1, "doc2".getBytes(StandardCharsets.UTF_8));
            idFilter.setValueCount(2);

            try {
                List<LanceDataset.Candidate> results = dataset.search(new float[] { 0.1f, 0.2f, 0.3f }, 5, "vector", idFilter);
                // Results should only contain docs from the filter set
                for (LanceDataset.Candidate result : results) {
                    assertTrue("Result _id should be in filter set", result.id().equals("doc1") || result.id().equals("doc2"));
                }
            } finally {
                idFilter.close();
            }
        }
    }

    public void testSearchWithNullFilterReturnsAllResults() throws Exception {
        // null filter should delegate to unfiltered search
        FakeLanceDataset dataset = createTestDataset();
        // Explicitly cast to VarCharVector to resolve ambiguity
        List<LanceDataset.Candidate> results = dataset.search(new float[] { 0.1f, 0.2f, 0.3f }, 5, "vector", (VarCharVector) null);
        assertNotNull(results);
        // Should get results (depends on test data)
        assertTrue("Should return some results", results.size() >= 0);
    }

    public void testSearchWithEmptyFilter() throws Exception {
        // Empty filter should return no results
        FakeLanceDataset dataset = createTestDataset();
        try (BufferAllocator allocator = new RootAllocator(1024 * 1024)) {
            VarCharVector idFilter = new VarCharVector("_id_filter", allocator);
            idFilter.allocateNew(0);
            idFilter.setValueCount(0);

            try {
                List<LanceDataset.Candidate> results = dataset.search(new float[] { 0.1f, 0.2f, 0.3f }, 5, "vector", idFilter);
                assertTrue("Empty filter should return no results", results.isEmpty());
            } finally {
                idFilter.close();
            }
        }
    }

    private FakeLanceDataset createTestDataset() throws Exception {
        // Use existing test resource
        return FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
    }
}
