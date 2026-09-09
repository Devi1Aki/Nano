package com.nano.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsPassFailureAndExecutionErrorWithoutStoppingSuite() throws Exception {
        List<BenchmarkCase> cases = BenchmarkCorpus.loadDefault().subList(0, 3);
        EvalRunner runner = new EvalRunner(
                benchmarkCase -> {
                    if ("search-003".equals(benchmarkCase.id())) {
                        throw new IllegalStateException("executor failed");
                    }
                    return new EvalRunner.ExecutionResult("output for " + benchmarkCase.id(), "trace_" + benchmarkCase.id());
                },
                (benchmarkCase, execution) -> new EvalRunner.JudgeResult(
                        "search-001".equals(benchmarkCase.id()), List.of("deterministic test judge"))
        );

        EvalRunner.EvalReport report = runner.run(cases);
        assertEquals(3, report.total());
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
        assertEquals(1, report.errors());

        Path output = tempDir.resolve("report.json");
        EvalRunner.writeReport(report, output);
        assertTrue(Files.readString(output).contains("trace_search-001"));
    }

    @Test
    void repeatsCasesAndAggregatesTraceMetrics() {
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "case-1", "retrieval", "react", "prompt", List.of("answer"));
        EvalRunner runner = new EvalRunner(
                ignored -> new EvalRunner.ExecutionResult(
                        "answer", "trace_1", new EvalRunner.EvalMetrics(10, 4, 2, 1, 3),
                        new EvalCheckRunner.VerificationResult(true, List.of("PASS fixture"))),
                (ignored, execution) -> new EvalRunner.JudgeResult(true, List.of("passed")));

        EvalRunner.EvalReport report = runner.run(List.of(benchmarkCase), 3);

        assertEquals(1, report.uniqueCases());
        assertEquals(3, report.repeat());
        assertEquals(3, report.total());
        assertEquals(List.of(1, 2, 3), report.results().stream().map(EvalRunner.CaseResult::attempt).toList());
        assertEquals(30, report.inputTokens());
        assertEquals(12, report.outputTokens());
        assertEquals(9, report.toolCalls());
    }

}
