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
        return run(cases, 1);
    }

    public EvalReport run(List<BenchmarkCase> cases, int repeat) {
        BenchmarkCorpus.validateSelection(cases);
        int safeRepeat = Math.max(1, Math.min(10, repeat));
        List<CaseResult> results = new ArrayList<>();
        long suiteStartedAt = System.nanoTime();
        for (int attempt = 1; attempt <= safeRepeat; attempt++) {
            for (BenchmarkCase benchmarkCase : cases) {
                long startedAt = System.nanoTime();
                try {
                    ExecutionResult execution = executor.execute(benchmarkCase);
                    JudgeResult judgment = judge.judge(benchmarkCase, execution);
                    EvalMetrics metrics = execution.metrics();
                    results.add(new CaseResult(
                            benchmarkCase.id(),
                            attempt,
                            judgment.passed() ? "passed" : "failed",
                            elapsedMillis(startedAt),
                            execution.traceId(),
                            preview(execution.output()),
                            judgment.details(),
                            execution.verification().details(),
                            metrics.inputTokens(), metrics.outputTokens(), metrics.cachedInputTokens(),
                            metrics.llmCalls(), metrics.toolCalls(),
                            null
                    ));
                } catch (Exception e) {
                    results.add(CaseResult.error(benchmarkCase.id(), attempt, elapsedMillis(startedAt), e));
                }
            }
        }
        long passed = results.stream().filter(result -> "passed".equals(result.status())).count();
        long failed = results.stream().filter(result -> "failed".equals(result.status())).count();
        long errors = results.stream().filter(result -> "error".equals(result.status())).count();
        return new EvalReport(Instant.now().toString(), cases.size(), safeRepeat, results.size(),
                passed, failed, errors, elapsedMillis(suiteStartedAt), List.copyOf(results));
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

    public record ExecutionResult(String output, String traceId, EvalMetrics metrics,
                                  EvalCheckRunner.VerificationResult verification) {
        public ExecutionResult {
            metrics = metrics == null ? EvalMetrics.empty() : metrics;
            verification = verification == null
                    ? new EvalCheckRunner.VerificationResult(true, List.of()) : verification;
        }

        public ExecutionResult(String output, String traceId) {
            this(output, traceId, EvalMetrics.empty(),
                    new EvalCheckRunner.VerificationResult(true, List.of()));
        }
    }

    public record EvalMetrics(long inputTokens, long outputTokens, long cachedInputTokens,
                              int llmCalls, int toolCalls) {
        public static EvalMetrics empty() {
            return new EvalMetrics(0L, 0L, 0L, 0, 0);
        }
    }

    public record JudgeResult(boolean passed, List<String> details) {
        public JudgeResult {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record CaseResult(String caseId, int attempt, String status, long durationMs, String traceId,
                             String outputPreview, List<String> details, List<String> verificationDetails,
                             long inputTokens, long outputTokens, long cachedInputTokens,
                             int llmCalls, int toolCalls, String error) {
        public CaseResult(String caseId, String status, long durationMs, String traceId,
                          String outputPreview, List<String> details, String error) {
            this(caseId, 1, status, durationMs, traceId, outputPreview, details, List.of(),
                    0L, 0L, 0L, 0, 0, error);
        }

        static CaseResult error(String caseId, int attempt, long durationMs, Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new CaseResult(caseId, attempt, "error", durationMs, null, null,
                    List.of(), List.of(), 0L, 0L, 0L, 0, 0, message);
        }
    }

    public record EvalReport(String createdAt, int uniqueCases, int repeat, int total,
                             long passed, long failed, long errors,
                             long durationMs, List<CaseResult> results) {
        public EvalReport(String createdAt, int total, long passed, long failed, long errors,
                          long durationMs, List<CaseResult> results) {
            this(createdAt, total, 1, total, passed, failed, errors, durationMs, results);
        }

        public double passRate() {
            return total == 0 ? 0D : (double) passed / total;
        }

        public long inputTokens() {
            return results == null ? 0L : results.stream().mapToLong(CaseResult::inputTokens).sum();
        }

        public long outputTokens() {
            return results == null ? 0L : results.stream().mapToLong(CaseResult::outputTokens).sum();
        }

        public long toolCalls() {
            return results == null ? 0L : results.stream().mapToLong(CaseResult::toolCalls).sum();
        }
    }
}
