package com.nano.eval;

import java.util.List;

public record BenchmarkCase(String id, String category, String mode, String prompt, List<String> assertions) {
    public BenchmarkCase {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }
}
