package com.nano.eval;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EvalFormatter {
    private EvalFormatter() {
    }

    public static String help() {
        return """
                Eval 命令：
                  /eval list [--category retrieval] [--mode react]
                  /eval run <case-id>
                  /eval run --category <name> --all
                  /eval reports
                  /eval compare [previous] [latest]

                run 会调用真实模型和工具；修改与安全用例仍受 HITL 和策略层约束。
                """;
    }

    public static String cases(List<BenchmarkCase> cases) {
        if (cases.isEmpty()) {
            return "没有匹配的 benchmark case。";
        }
        StringBuilder output = new StringBuilder("Benchmark cases (" + cases.size() + "):\n");
        for (BenchmarkCase item : cases) {
            output.append("  ").append(item.id()).append("  ")
                    .append(item.category()).append("/").append(item.mode()).append("  ")
                    .append(item.prompt()).append('\n');
        }
        return output.toString();
    }

    public static String report(EvalRunner.EvalReport report, Path path) {
        return "Eval 完成: " + report.passed() + "/" + report.total() + " passed, "
                + report.failed() + " failed, " + report.errors() + " errors, "
                + String.format("%.1f%%", report.passRate() * 100D) + ", " + report.durationMs() + " ms\n"
                + "报告: " + path.toAbsolutePath();
    }

    public static String reports(List<Path> reports) {
        if (reports.isEmpty()) {
            return "还没有 Eval 报告。";
        }
        StringBuilder output = new StringBuilder("最近 Eval 报告：\n");
        reports.stream().limit(10).forEach(path -> output.append("  ").append(path.toAbsolutePath()).append('\n'));
        return output.toString();
    }

    public static String compare(EvalRunner.EvalReport baseline, EvalRunner.EvalReport candidate,
                                 Path baselinePath, Path candidatePath) {
        double delta = (candidate.passRate() - baseline.passRate()) * 100D;
        long durationDelta = candidate.durationMs() - baseline.durationMs();
        Set<String> baselineCases = caseIds(baseline);
        Set<String> candidateCases = caseIds(candidate);
        String comparability = baselineCases.equals(candidateCases)
                ? ""
                : "\n  ⚠ case 集合不同，delta 仅供参考";
        return "Eval 对比：\n"
                + "  baseline  " + baseline.passed() + "/" + baseline.total() + "  "
                + String.format("%.1f%%", baseline.passRate() * 100D) + "  " + baselinePath.getFileName() + "\n"
                + "  candidate " + candidate.passed() + "/" + candidate.total() + "  "
                + String.format("%.1f%%", candidate.passRate() * 100D) + "  " + candidatePath.getFileName() + "\n"
                + "  pass-rate delta " + String.format("%+.1f pp", delta)
                + ", duration delta " + String.format("%+d ms", durationDelta)
                + comparability;
    }

    private static Set<String> caseIds(EvalRunner.EvalReport report) {
        Set<String> ids = new HashSet<>();
        if (report.results() != null) {
            report.results().forEach(result -> ids.add(result.caseId()));
        }
        return ids;
    }
}
