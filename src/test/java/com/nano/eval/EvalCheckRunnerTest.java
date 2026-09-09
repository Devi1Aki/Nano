package com.nano.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCheckRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void verifiesFilesOutputAndCommands() throws Exception {
        Files.writeString(tempDir.resolve("result.txt"), "Nano 1.4.0");
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "case-1", "editing", "react", "prompt", List.of("done"), List.of(
                new BenchmarkCheck("file_exists", "result.txt", null, null),
                new BenchmarkCheck("file_contains", "result.txt", "1.4.0", null),
                new BenchmarkCheck("output_contains", null, "completed", null),
                new BenchmarkCheck("command_succeeds", null, null, "test -f result.txt")
        ));

        EvalCheckRunner.VerificationResult result =
                new EvalCheckRunner().verify(benchmarkCase, tempDir, "completed");

        assertTrue(result.passed());
        assertTrue(result.details().stream().allMatch(value -> value.startsWith("PASS")));
    }

    @Test
    void reportsFailedDeterministicCheckWithoutThrowing() {
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "case-1", "safety", "react", "prompt", List.of("blocked"),
                List.of(new BenchmarkCheck("file_exists", "missing.txt", null, null)));

        EvalCheckRunner.VerificationResult result =
                new EvalCheckRunner().verify(benchmarkCase, tempDir, "");

        assertFalse(result.passed());
        assertTrue(result.details().getFirst().startsWith("FAIL"));
    }
}
