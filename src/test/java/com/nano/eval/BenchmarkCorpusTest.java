package com.nano.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCorpusTest {

    private static final Set<String> CATEGORIES = Set.of("retrieval", "editing", "planning", "mcp", "safety");
    private static final Set<String> MODES = Set.of("react", "plan", "team");

    @Test
    void corpusContainsTwentyWellFormedCases() throws Exception {
        List<BenchmarkCase> cases = BenchmarkCorpus.loadDefault();
        assertEquals(20, cases.size());

        assertEquals(CATEGORIES, cases.stream().map(BenchmarkCase::category).collect(java.util.stream.Collectors.toSet()));
        assertEquals(MODES, cases.stream().map(BenchmarkCase::mode).collect(java.util.stream.Collectors.toSet()));
        assertEquals(20, cases.stream().map(BenchmarkCase::id).distinct().count());
        assertTrue(cases.stream().allMatch(value -> !value.assertions().isEmpty()));
    }
}
