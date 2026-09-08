package com.nano.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class EvalRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final CaseExecutor executor;
    private final CaseJudge judge;

    public EvalRunner(CaseExecutor executor, CaseJudge judge) {
        this.executor = executor;
        this.judge = judge;
    }

    public EvalReport run(List<BenchmarkCase> cases) {
        BenchmarkCorpus.validateSelection(cases);
        List<CaseResult> results = new ArrayList<>();
        long suiteStartedAt = System.nanoTime();
        for (BenchmarkCase benchmarkCase : cases) {
            long startedAt = System.nanoTime();
            try {
                ExecutionResult execution = executor.execute(benchmarkCase);
                JudgeResult judgment = judge.judge(benchmarkCase, execution);
                results.add(new CaseResult(
                        benchmarkCase.id(),
                        judgment.passed() ? "passed" : "failed",
                        elapsedMillis(startedAt),
                        execution.traceId(),
                        preview(execution.output()),
                        judgment.details(),
                        null
                ));
            } catch (Exception e) {
                results.add(new CaseResult(
                        benchmarkCase.id(), "error", elapsedMillis(startedAt), null, null,
                        List.of(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                ));
            }
        }
        long passed = results.stream().filter(result -> "passed".equals(result.status())).count();
        long failed = results.stream().filter(result -> "failed".equals(result.status())).count();
        long errors = results.stream().filter(result -> "error".equals(result.status())).count();
        return new EvalReport(Instant.now().toString(), results.size(), passed, failed, errors,
                elapsedMillis(suiteStartedAt), List.copyOf(results));
    }

    public static void writeReport(EvalReport report, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(output.toFile(), report);
    }

    private static String preview(String output) {
        if (output == null) {
            return null;
        }
        String compact = output.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...(truncated)";
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    @FunctionalInterface
    public interface CaseExecutor {
        ExecutionResult execute(BenchmarkCase benchmarkCase) throws Exception;
    }

    @FunctionalInterface
    public interface CaseJudge {
        JudgeResult judge(BenchmarkCase benchmarkCase, ExecutionResult execution) throws Exception;
    }

    public record ExecutionResult(String output, String traceId) {
    }

    public record JudgeResult(boolean passed, List<String> details) {
        public JudgeResult {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record CaseResult(String caseId, String status, long durationMs, String traceId,
                             String outputPreview, List<String> details, String error) {
    }

    public record EvalReport(String createdAt, int total, long passed, long failed, long errors,
                             long durationMs, List<CaseResult> results) {
        public double passRate() {
            return total == 0 ? 0D : (double) passed / total;
        }
    }
}
