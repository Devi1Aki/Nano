package com.nano.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BenchmarkCorpus {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("retrieval", "editing", "planning", "mcp", "safety");
    private static final Set<String> MODES = Set.of("react", "plan", "team");

    private BenchmarkCorpus() {
    }

    public static List<BenchmarkCase> load(Path path) throws IOException {
        List<BenchmarkCase> cases = MAPPER.readValue(path.toFile(), new TypeReference<>() {
        });
        validate(cases);
        return List.copyOf(cases);
    }

    public static List<BenchmarkCase> loadDefault() throws IOException {
        return load(Path.of("benchmarks", "cases.json"));
    }

    public static void validate(List<BenchmarkCase> cases) {
        validateCases(cases, true);
    }

    public static void validateSelection(List<BenchmarkCase> cases) {
        validateCases(cases, false);
    }

    private static void validateCases(List<BenchmarkCase> cases, boolean requireFullCoverage) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("benchmark corpus cannot be empty");
        }
        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> modes = new HashSet<>();
        for (BenchmarkCase benchmarkCase : cases) {
            if (benchmarkCase == null || benchmarkCase.id() == null || benchmarkCase.id().isBlank()
                    || !ids.add(benchmarkCase.id())) {
                throw new IllegalArgumentException("benchmark case id must be non-blank and unique");
            }
            if (!CATEGORIES.contains(benchmarkCase.category())) {
                throw new IllegalArgumentException("unknown benchmark category: " + benchmarkCase.id());
            }
            if (!MODES.contains(benchmarkCase.mode())) {
                throw new IllegalArgumentException("unknown Agent mode: " + benchmarkCase.id());
            }
            if (benchmarkCase.prompt() == null || benchmarkCase.prompt().isBlank()) {
                throw new IllegalArgumentException("benchmark prompt cannot be blank: " + benchmarkCase.id());
            }
            if (benchmarkCase.assertions().isEmpty()
                    || benchmarkCase.assertions().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("benchmark assertions cannot be empty: " + benchmarkCase.id());
            }
            categories.add(benchmarkCase.category());
            modes.add(benchmarkCase.mode());
        }
        if (requireFullCoverage && !categories.equals(CATEGORIES)) {
            throw new IllegalArgumentException("benchmark corpus must cover every category");
        }
        if (requireFullCoverage && !modes.equals(MODES)) {
            throw new IllegalArgumentException("benchmark corpus must cover every Agent mode");
        }
    }
}
