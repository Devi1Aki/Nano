package com.nano.eval;

import java.util.List;

public record BenchmarkCase(String id, String category, String mode, String prompt,
                            List<String> assertions, List<BenchmarkCheck> checks) {
    public BenchmarkCase {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public BenchmarkCase(String id, String category, String mode, String prompt, List<String> assertions) {
        this(id, category, mode, prompt, assertions, List.of());
    }
}
