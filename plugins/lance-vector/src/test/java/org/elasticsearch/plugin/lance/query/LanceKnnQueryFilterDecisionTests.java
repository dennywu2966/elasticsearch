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

public class LanceKnnQueryFilterDecisionTests extends ESTestCase {

    public void testPreFilterDecisionWhenSmallFilterCount() {
        // 5 filtered docs, k=10, AUTO heuristic -> pre-filter (5 < 10*2=20)
        var decision = LanceKnnQuery.decideFilterStrategy(5, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.PRE_FILTER, decision.strategy());
        assertEquals(5, decision.filteredDocCount());
    }

    public void testPostFilterDecisionWhenLargeFilterCount() {
        // 500 filtered docs, k=10, AUTO heuristic -> post-filter (500 >= 20)
        var decision = LanceKnnQuery.decideFilterStrategy(500, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.POST_FILTER, decision.strategy());
        assertEquals(500, decision.filteredDocCount());
    }

    public void testAlwaysPreFilterOverridesCount() {
        var decision = LanceKnnQuery.decideFilterStrategy(10000, 10, PreFilterHeuristic.ALWAYS);
        assertEquals(LanceKnnQuery.FilterStrategy.PRE_FILTER, decision.strategy());
    }

    public void testNeverPreFilterOverridesCount() {
        var decision = LanceKnnQuery.decideFilterStrategy(1, 10, PreFilterHeuristic.NEVER);
        assertEquals(LanceKnnQuery.FilterStrategy.POST_FILTER, decision.strategy());
    }

    public void testNoFilterReturnsNone() {
        var decision = LanceKnnQuery.decideFilterStrategy(-1, 10, PreFilterHeuristic.AUTO);
        assertEquals(LanceKnnQuery.FilterStrategy.NONE, decision.strategy());
        assertEquals(-1, decision.filteredDocCount());
    }
}
