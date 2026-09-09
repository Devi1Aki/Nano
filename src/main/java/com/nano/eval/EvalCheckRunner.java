package com.nano.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class EvalCheckRunner {
    private static final long COMMAND_TIMEOUT_SECONDS = 60L;

    public VerificationResult verify(BenchmarkCase benchmarkCase, Path workspace, String output) {
        if (benchmarkCase.checks().isEmpty()) {
            return new VerificationResult(true, List.of("no deterministic checks"));
        }
        List<String> details = new ArrayList<>();
        boolean passed = true;
        for (BenchmarkCheck check : benchmarkCase.checks()) {
            CheckResult result = run(check, workspace, output == null ? "" : output);
            passed &= result.passed();
            details.add((result.passed() ? "PASS " : "FAIL ") + result.details());
        }
        return new VerificationResult(passed, details);
    }

    private CheckResult run(BenchmarkCheck check, Path workspace, String output) {
        String type = check.type().toLowerCase(Locale.ROOT);
        try {
            return switch (type) {
                case "file_exists" -> fileExists(workspace, check.path(), true);
                case "file_not_exists" -> fileExists(workspace, check.path(), false);
                case "file_contains" -> fileContains(workspace, check.path(), check.value());
                case "output_contains" -> contains("output", output, check.value());
                case "command_succeeds" -> commandSucceeds(workspace, check.command());
                default -> new CheckResult(false, "unknown check type: " + check.type());
            };
        } catch (Exception e) {
            return new CheckResult(false, type + " error: " + safeMessage(e));
        }
    }

    private CheckResult fileExists(Path workspace, String path, boolean expected) {
        Path target = resolveCheckPath(workspace, path);
        boolean actual = Files.exists(target);
        return new CheckResult(actual == expected,
                (expected ? "file_exists " : "file_not_exists ") + path + " actual=" + actual);
    }

    private CheckResult fileContains(Path workspace, String path, String value) throws IOException {
        Path target = resolveCheckPath(workspace, path);
        if (!Files.isRegularFile(target)) {
            return new CheckResult(false, "file_contains " + path + " missing");
        }
        return contains("file_contains " + path, Files.readString(target), value);
    }

    private CheckResult contains(String label, String text, String value) {
        boolean passed = value != null && !value.isBlank() && text.contains(value);
        return new CheckResult(passed, label + " value=" + value);
    }

    private CheckResult commandSucceeds(Path workspace, String command) throws Exception {
        if (command == null || command.isBlank()) {
            return new CheckResult(false, "command_succeeds command is blank");
        }
        Process process = new ProcessBuilder("/bin/sh", "-lc", command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CheckResult(false, "command_succeeds timeout: " + command);
        }
        String commandOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CheckResult(process.exitValue() == 0,
                "command_succeeds exit=" + process.exitValue() + " command=" + command
                        + preview(commandOutput));
    }

    private static Path resolveCheckPath(Path workspace, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("check path is blank");
        }
        return workspace.resolve(value).normalize();
    }

    private static String preview(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.isEmpty() ? "" : " output=" + compact.substring(0, Math.min(200, compact.length()));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record CheckResult(boolean passed, String details) {
    }

    public record VerificationResult(boolean passed, List<String> details) {
        public VerificationResult {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }
}
