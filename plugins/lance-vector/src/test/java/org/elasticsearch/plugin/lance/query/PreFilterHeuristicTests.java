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

public class PreFilterHeuristicTests extends ESTestCase {

    public void testAutoShouldPreFilterWhenFilteredCountSmall() {
        // k=10, filtered=15 → 15 < 10*2=20 → pre-filter
        assertTrue(PreFilterHeuristic.AUTO.shouldPreFilter(15, 10));
    }

    public void testAutoShouldNotPreFilterWhenFilteredCountLarge() {
        // k=10, filtered=25 → 25 >= 10*2=20 → post-filter
        assertFalse(PreFilterHeuristic.AUTO.shouldPreFilter(25, 10));
    }

    public void testAlwaysShouldAlwaysPreFilter() {
        assertTrue(PreFilterHeuristic.ALWAYS.shouldPreFilter(10000, 10));
    }

    public void testNeverShouldNeverPreFilter() {
        assertFalse(PreFilterHeuristic.NEVER.shouldPreFilter(1, 10));
    }

    public void testAutoEdgeCase() {
        // k=10, filtered=20 → 20 >= 10*2=20 → post-filter (boundary)
        assertFalse(PreFilterHeuristic.AUTO.shouldPreFilter(20, 10));
    }

    public void testAutoZeroFilteredDocs() {
        // No docs match filter → post-filter (nothing to pre-filter)
        assertFalse(PreFilterHeuristic.AUTO.shouldPreFilter(0, 10));
    }
}
