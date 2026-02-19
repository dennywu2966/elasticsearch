/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe search metrics for the Lance Vector plugin.
 * <p>
 * Uses LongAdder for lock-free concurrent updates in hot search paths.
 * Metrics include total searches, filtered searches, pre-filter/post-filter
 * breakdowns, timing, and error counts.
 * <p>
 * Exposed via GET /_lance/stats REST endpoint.
 */
public final class LanceSearchMetrics {

    private static final LongAdder totalSearches = new LongAdder();
    private static final LongAdder totalSearchTimeNanos = new LongAdder();
    private static final LongAdder filteredSearches = new LongAdder();
    private static final LongAdder preFilterSearches = new LongAdder();
    private static final LongAdder postFilterSearches = new LongAdder();
    private static final LongAdder searchErrors = new LongAdder();

    private LanceSearchMetrics() {}

    /**
     * Record a completed search with its duration.
     *
     * @param durationNanos Search duration in nanoseconds
     */
    public static void recordSearch(long durationNanos) {
        totalSearches.increment();
        totalSearchTimeNanos.add(durationNanos);
    }

    /** Record a search with any filter applied. */
    public static void recordFilteredSearch() {
        filteredSearches.increment();
    }

    /** Record a search using pre-filter strategy. */
    public static void recordPreFilterSearch() {
        preFilterSearches.increment();
    }

    /** Record a search using post-filter strategy. */
    public static void recordPostFilterSearch() {
        postFilterSearches.increment();
    }

    /** Record a search that failed with an error. */
    public static void recordSearchError() {
        searchErrors.increment();
    }

    /** Get total number of searches executed. */
    public static long getTotalSearches() {
        return totalSearches.sum();
    }

    /** Get total search time in nanoseconds. */
    public static long getTotalSearchTimeNanos() {
        return totalSearchTimeNanos.sum();
    }

    /** Get number of searches with filters applied. */
    public static long getFilteredSearches() {
        return filteredSearches.sum();
    }

    /** Get number of searches using pre-filter strategy. */
    public static long getPreFilterSearches() {
        return preFilterSearches.sum();
    }

    /** Get number of searches using post-filter strategy. */
    public static long getPostFilterSearches() {
        return postFilterSearches.sum();
    }

    /** Get number of searches that failed with errors. */
    public static long getSearchErrors() {
        return searchErrors.sum();
    }

    /**
     * Reset all metrics to zero.
     * <p>
     * Primarily used for testing.
     */
    public static void reset() {
        totalSearches.reset();
        totalSearchTimeNanos.reset();
        filteredSearches.reset();
        preFilterSearches.reset();
        postFilterSearches.reset();
        searchErrors.reset();
    }
}
