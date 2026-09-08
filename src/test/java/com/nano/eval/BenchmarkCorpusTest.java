package com.nano.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCorpusTest {

    private static final Set<String> CATEGORIES = Set.of("retrieval", "editing", "planning", "mcp", "safety");
    private static final Set<String> MODES = Set.of("react", "plan", "team");

    @Test
    void corpusContainsTwentyWellFormedCases() throws Exception {
        Path corpusPath = Path.of("benchmarks", "cases.json");
        assertTrue(Files.isRegularFile(corpusPath), "benchmark corpus must exist");

        JsonNode cases = new ObjectMapper().readTree(corpusPath.toFile());
        assertTrue(cases.isArray());
        assertEquals(20, cases.size());

        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> modes = new HashSet<>();
        for (JsonNode benchmarkCase : cases) {
            String id = benchmarkCase.path("id").asText();
            String category = benchmarkCase.path("category").asText();
            String mode = benchmarkCase.path("mode").asText();
            assertTrue(!id.isBlank() && ids.add(id), "case id must be non-blank and unique: " + id);
            assertTrue(CATEGORIES.contains(category), "unknown category: " + id);
            assertTrue(MODES.contains(mode), "unknown mode: " + id);
            categories.add(category);
            modes.add(mode);
            assertTrue(!benchmarkCase.path("prompt").asText().isBlank(), "prompt must not be blank: " + id);
            assertTrue(benchmarkCase.path("assertions").isArray()
                    && !benchmarkCase.path("assertions").isEmpty(), "assertions must not be empty: " + id);
        }
        assertEquals(CATEGORIES, categories, "every benchmark category must be represented");
        assertEquals(MODES, modes, "every Agent mode must be represented");
    }
}
