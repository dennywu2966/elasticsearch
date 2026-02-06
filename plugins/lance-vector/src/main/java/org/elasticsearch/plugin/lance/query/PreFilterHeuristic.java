/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

import org.elasticsearch.common.settings.Setting;

/**
 * Heuristic for deciding when to use pre-filtering vs post-filtering
 * in Lance kNN searches.
 * <p>
 * Pre-filtering pushes filtered document IDs to the Lance SDK via Arrow VarCharVector,
 * allowing Lance to search only within the filtered set. This is efficient when few
 * documents match the filter (M &lt; K*2).
 * <p>
 * Post-filtering runs the full Lance kNN search and intersects results with the
 * Lucene filter bitset. This is efficient when most documents match the filter.
 * <p>
 * Threshold reasoning: extracting _id from stored fields costs ~10-100x more than
 * an in-memory bitset check. Post-filter does K postings lookups to join results.
 * Break-even point is approximately M ~ K*2, where M is the number of docs matching
 * the filter and K is the requested number of nearest neighbors.
 */
public enum PreFilterHeuristic {
    /**
     * Always use pre-filtering regardless of filter selectivity.
     * Use when you know filters are always highly selective.
     */
    ALWAYS {
        @Override
        public boolean shouldPreFilter(int filteredDocCount, int k) {
            return true;
        }
    },

    /**
     * Never use pre-filtering; always use post-filtering.
     * Use when stored field _id lookups are too expensive or
     * filters are typically non-selective.
     */
    NEVER {
        @Override
        public boolean shouldPreFilter(int filteredDocCount, int k) {
            return false;
        }
    },

    /**
     * Automatically decide based on filter selectivity.
     * Pre-filters when filteredDocCount &gt; 0 AND filteredDocCount &lt; k * 2.
     */
    AUTO {
        @Override
        public boolean shouldPreFilter(int filteredDocCount, int k) {
            return filteredDocCount > 0 && filteredDocCount < k * 2;
        }
    };

    /**
     * Decide whether to use pre-filtering for the given filter result.
     *
     * @param filteredDocCount Number of documents matching the filter
     * @param k Number of nearest neighbors requested
     * @return true if pre-filtering should be used, false for post-filtering
     */
    public abstract boolean shouldPreFilter(int filteredDocCount, int k);

    /**
     * Index-level setting to control pre-filter heuristic.
     * Values: AUTO (default), ALWAYS, NEVER.
     * Scope: IndexScope, Dynamic (can be changed without reindex).
     */
    public static final Setting<PreFilterHeuristic> INDEX_SETTING = Setting.enumSetting(
        PreFilterHeuristic.class,
        "index.lance_vector.prefilter_heuristic",
        PreFilterHeuristic.AUTO,
        Setting.Property.IndexScope,
        Setting.Property.Dynamic
    );
}
