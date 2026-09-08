package com.nano.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nano.llm.LlmClient;
import com.nano.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TraceContext {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final InheritableThreadLocal<Session> LOCAL = new InheritableThreadLocal<>();

    private TraceContext() {
    }

    public static Session start(TraceStore store, String mode, String prompt, String provider, String model) {
        if (store == null) {
            return null;
        }
        Session session;
        try {
            session = new Session(store, store.startTrace(mode, prompt, provider, model));
        } catch (RuntimeException e) {
            return null;
        }
        LOCAL.set(session);
        session.event("turn", mode, "started", 0L, Map.of());
        return session;
    }

    public static Session current() {
        return LOCAL.get();
    }

    public static void clear(Session session) {
        if (session == null) {
            return;
        }
        if (LOCAL.get() == session) {
            LOCAL.remove();
        }
    }

    public static void llmCompleted(LlmClient.ChatResponse response, long durationMs, int messageCount,
                                    int toolDefinitionCount, String provider, String model) {
        Session session = current();
        if (session == null || response == null) {
            return;
        }
        session.inputTokens.addAndGet(Math.max(0, response.inputTokens()));
        session.outputTokens.addAndGet(Math.max(0, response.outputTokens()));
        session.cachedInputTokens.addAndGet(Math.max(0, response.cachedInputTokens()));
        session.llmCalls.incrementAndGet();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider);
        data.put("model", model);
        data.put("messages", Math.max(0, messageCount));
        data.put("toolDefinitions", Math.max(0, toolDefinitionCount));
        data.put("toolCalls", response.toolCalls() == null ? 0 : response.toolCalls().size());
        data.put("inputTokens", Math.max(0, response.inputTokens()));
        data.put("outputTokens", Math.max(0, response.outputTokens()));
        data.put("cachedInputTokens", Math.max(0, response.cachedInputTokens()));
        session.event("llm", model, "completed", durationMs, data);
    }

    public static void llmFailed(long durationMs, int messageCount, int toolDefinitionCount,
                                 String provider, String model, Throwable error) {
        Session session = current();
        if (session == null) {
            return;
        }
        session.llmCalls.incrementAndGet();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider);
        data.put("model", model);
        data.put("messages", Math.max(0, messageCount));
        data.put("toolDefinitions", Math.max(0, toolDefinitionCount));
        data.put("error", error == null ? "unknown" : error.getMessage());
        session.event("llm", model, "failed", durationMs, data);
    }

    public static void toolCompleted(ToolRegistry.ToolExecutionResult result) {
        Session session = current();
        if (session == null || result == null) {
            return;
        }
        session.toolCalls.incrementAndGet();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", result.id());
        data.put("argumentChars", result.argumentsJson() == null ? 0 : result.argumentsJson().length());
        data.put("resultChars", result.result() == null ? 0 : result.result().length());
        data.put("imageParts", result.imageParts() == null ? 0 : result.imageParts().size());
        data.put("timedOut", result.timedOut());
        String status = result.timedOut() ? "timeout"
                : result.result() != null && result.result().startsWith("工具执行失败") ? "failed" : "completed";
        session.event("tool", result.name(), status, result.elapsedMillis(), data);
    }

    public static final class Session {
        private final TraceStore store;
        private final String traceId;
        private final long startedAtNanos = System.nanoTime();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cachedInputTokens = new AtomicLong();
        private final AtomicInteger llmCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();

        private Session(TraceStore store, String traceId) {
            this.store = store;
            this.traceId = traceId;
        }

        public String traceId() {
            return traceId;
        }

        public void event(String type, String name, String status, long durationMs, Map<String, ?> data) {
            try {
                store.appendEvent(traceId, type, name, status, durationMs,
                        data == null || data.isEmpty() ? "{}" : MAPPER.writeValueAsString(data));
            } catch (JsonProcessingException | RuntimeException ignored) {
                // Trace 必须 fail-open，不能影响 Agent 主流程。
            }
        }

        public void finish(String status, String error) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            event("turn", null, status, durationMs,
                    error == null ? Map.of() : Map.of("error", error));
            try {
                store.finishTrace(traceId, status, durationMs,
                        inputTokens.get(), outputTokens.get(), cachedInputTokens.get(),
                        llmCalls.get(), toolCalls.get(), error);
            } catch (RuntimeException ignored) {
                // Trace 必须 fail-open，不能覆盖任务真实结果。
            }
        }
    }
}
