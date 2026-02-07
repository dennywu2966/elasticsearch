/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

/**
 * Tests for nprobes parameter in LanceDataset search interface.
 */
public class LanceDatasetNprobesTests extends ESTestCase {

    public void testSearchWithNprobesParameter() throws Exception {
        FakeLanceDataset dataset = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        List<LanceDataset.Candidate> results = dataset.search(
            new float[] { 0.1f, 0.2f, 0.3f },
            5,
            "vector",
            50  // nprobes
        );
        assertNotNull(results);
        // FakeLanceDataset ignores nprobes, but should still return results
        assertFalse("Should return some results", results.isEmpty());
    }

    public void testSearchWithLowNprobes() throws Exception {
        FakeLanceDataset dataset = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        List<LanceDataset.Candidate> results = dataset.search(
            new float[] { 0.1f, 0.2f, 0.3f },
            5,
            "vector",
            5  // low nprobes for speed
        );
        assertNotNull(results);
    }

    public void testSearchWithHighNprobes() throws Exception {
        FakeLanceDataset dataset = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        List<LanceDataset.Candidate> results = dataset.search(
            new float[] { 0.1f, 0.2f, 0.3f },
            5,
            "vector",
            100  // high nprobes for recall
        );
        assertNotNull(results);
    }
}
