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

/**
 * Tests that verify the hybrid pre-filter/post-filter strategy selection
 * and execution in LanceKnnQuery.
 */
public class LanceKnnQueryHybridFilterTests extends ESTestCase {

    public void testBuildDocScoresWithoutFilter() {
        // Verify that no filter → NONE strategy
        var decision = LanceKnnQuery.decideFilterStrategy(-1, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.NONE, decision.strategy());
    }

    public void testSmallFilterTriggersPreFilter() {
        // 5 filtered docs, k=10 → pre-filter path
        var decision = LanceKnnQuery.decideFilterStrategy(5, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.PRE_FILTER, decision.strategy());
        assertEquals(5, decision.filteredDocCount());
    }

    public void testLargeFilterTriggersPostFilter() {
        // 1000 filtered docs, k=10 → post-filter path
        var decision = LanceKnnQuery.decideFilterStrategy(1000, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.POST_FILTER, decision.strategy());
        assertEquals(1000, decision.filteredDocCount());
    }

    public void testAlwaysHeuristicForcesPreFilter() {
        // ALWAYS heuristic should force pre-filter regardless of count
        var decision = LanceKnnQuery.decideFilterStrategy(10000, 10, PreFilterHeuristic.ALWAYS);
        assertEquals(LanceKnnQuery.FilterStrategy.PRE_FILTER, decision.strategy());
    }

    public void testNeverHeuristicForcesPostFilter() {
        // NEVER heuristic should force post-filter regardless of count
        var decision = LanceKnnQuery.decideFilterStrategy(1, 10, PreFilterHeuristic.NEVER);
        assertEquals(LanceKnnQuery.FilterStrategy.POST_FILTER, decision.strategy());
    }
}
