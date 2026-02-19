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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for VersionedDataset atomic swap behavior.
 */
public class VersionedDatasetTests extends ESTestCase {

    public void testAtomicSwap() throws IOException {
        FakeLanceDataset v1 = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        FakeLanceDataset v2 = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);

        VersionedDataset versioned = new VersionedDataset(v1, 1L);
        assertEquals(1L, versioned.version());

        LanceDataset old = versioned.swap(v2, 2L);
        assertSame(v1, old);
        assertEquals(2L, versioned.version());
    }

    public void testDelegatesSearch() throws IOException {
        FakeLanceDataset delegate = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        VersionedDataset versioned = new VersionedDataset(delegate, 1L);

        List<LanceDataset.Candidate> results = versioned.search(new float[] { 0.1f, 0.2f, 0.3f }, 5, "vector");
        assertNotNull(results);
        assertFalse("Should return some results", results.isEmpty());
    }

    public void testConcurrentReadDuringSwap() throws IOException, InterruptedException {
        FakeLanceDataset v1 = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        VersionedDataset versioned = new VersionedDataset(v1, 1L);

        AtomicInteger errors = new AtomicInteger(0);
        int threads = 10;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        versioned.search(new float[] { 0.1f, 0.2f, 0.3f }, 5, "vector");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Swap during concurrent reads
        FakeLanceDataset v2 = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        versioned.swap(v2, 2L);

        latch.await();
        assertEquals(0, errors.get());
    }

    public void testUri() throws IOException {
        FakeLanceDataset delegate = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        VersionedDataset versioned = new VersionedDataset(delegate, 1L);
        assertEquals("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", versioned.uri());
    }

    public void testDims() throws IOException {
        FakeLanceDataset delegate = FakeLanceDataset.load("embedded:org/elasticsearch/plugin/lance/datasets/simple.json", 3);
        VersionedDataset versioned = new VersionedDataset(delegate, 1L);
        assertEquals(3, versioned.dims());
    }
}
