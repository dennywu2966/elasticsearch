/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.elasticsearch.test.ESTestCase;

import java.util.concurrent.CountDownLatch;

/**
 * Tests for LanceSearchMetrics thread-safe search statistics.
 */
public class LanceSearchMetricsTests extends ESTestCase {

    public void testRecordSearch() {
        LanceSearchMetrics.reset();
        LanceSearchMetrics.recordSearch(50_000_000L); // 50ms
        assertEquals(1, LanceSearchMetrics.getTotalSearches());
        assertEquals(50_000_000L, LanceSearchMetrics.getTotalSearchTimeNanos());
    }

    public void testRecordPreFilterSearch() {
        LanceSearchMetrics.reset();
        LanceSearchMetrics.recordPreFilterSearch();
        assertEquals(1, LanceSearchMetrics.getPreFilterSearches());
    }

    public void testRecordPostFilterSearch() {
        LanceSearchMetrics.reset();
        LanceSearchMetrics.recordPostFilterSearch();
        assertEquals(1, LanceSearchMetrics.getPostFilterSearches());
    }

    public void testRecordFilteredSearch() {
        LanceSearchMetrics.reset();
        LanceSearchMetrics.recordFilteredSearch();
        assertEquals(1, LanceSearchMetrics.getFilteredSearches());
    }

    public void testRecordSearchError() {
        LanceSearchMetrics.reset();
        LanceSearchMetrics.recordSearchError();
        assertEquals(1, LanceSearchMetrics.getSearchErrors());
    }

    public void testConcurrentUpdates() throws Exception {
        LanceSearchMetrics.reset();
        int threads = 10;
        int perThread = 1000;
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    LanceSearchMetrics.recordSearch(1_000_000L);
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        assertEquals(threads * perThread, LanceSearchMetrics.getTotalSearches());
    }
}
