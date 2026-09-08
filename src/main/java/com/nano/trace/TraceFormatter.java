package com.nano.trace;

import java.util.List;

public final class TraceFormatter {
    private TraceFormatter() {
    }

    public static String format(TraceStore store, String traceId) {
        if (store == null) {
            return "Trace 不可用：数据库初始化失败";
        }
        if (traceId == null || traceId.isBlank()) {
            return formatRecent(store.recent(10));
        }
        TraceStore.TraceSummary trace = store.find(traceId.trim());
        if (trace == null) {
            return "未找到 Trace: " + traceId.trim();
        }
        StringBuilder output = new StringBuilder();
        output.append("Trace ").append(trace.id()).append("\n")
                .append("  mode: ").append(trace.mode())
                .append("  status: ").append(trace.status())
                .append("  model: ").append(trace.model()).append(" (").append(trace.provider()).append(")\n")
                .append("  duration: ").append(trace.durationMs()).append("ms")
                .append("  llm: ").append(trace.llmCalls())
                .append("  tools: ").append(trace.toolCalls())
                .append("  tokens: ").append(trace.inputTokens()).append("/").append(trace.outputTokens())
                .append("  cache: ").append(trace.cachedInputTokens()).append("\n")
                .append("  prompt: ").append(oneLine(trace.promptPreview())).append("\n")
                .append("  events:\n");
        for (TraceStore.TraceEvent event : store.events(trace.id())) {
            output.append("    #").append(event.id()).append(" ")
                    .append(event.type()).append("/").append(event.status());
            if (event.name() != null && !event.name().isBlank()) {
                output.append(" ").append(event.name());
            }
            output.append(" ").append(event.durationMs()).append("ms");
            if (event.data() != null && !"{}".equals(event.data())) {
                output.append(" ").append(oneLine(event.data()));
            }
            output.append("\n");
        }
        if (trace.error() != null && !trace.error().isBlank()) {
            output.append("  error: ").append(oneLine(trace.error())).append("\n");
        }
        return output.toString().stripTrailing();
    }

    private static String formatRecent(List<TraceStore.TraceSummary> traces) {
        if (traces.isEmpty()) {
            return "暂无 Trace 记录";
        }
        StringBuilder output = new StringBuilder("Recent traces\n");
        for (TraceStore.TraceSummary trace : traces) {
            output.append("  ").append(trace.id())
                    .append("  ").append(trace.mode())
                    .append("  ").append(trace.status())
                    .append("  ").append(trace.durationMs()).append("ms")
                    .append("  llm=").append(trace.llmCalls())
                    .append(" tools=").append(trace.toolCalls())
                    .append("  ").append(oneLine(trace.promptPreview()))
                    .append("\n");
        }
        output.append("使用 /trace <trace_id> 查看事件时间线");
        return output.toString();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
