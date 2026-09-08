package com.nano.llm;

import com.nano.trace.TraceContext;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class TracingLlmClient implements LlmClient {
    private final LlmClient delegate;

    public TracingLlmClient(LlmClient delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate LlmClient cannot be null");
        }
        this.delegate = delegate;
    }

    public static LlmClient wrap(LlmClient client) {
        return client == null || client instanceof TracingLlmClient ? client : new TracingLlmClient(client);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = delegate.chat(messages, tools, listener);
            TraceContext.llmCompleted(response, elapsedMillis(startedAt), size(messages), size(tools),
                    getProviderName(), getModelName());
            return response;
        } catch (IOException | RuntimeException e) {
            TraceContext.llmFailed(elapsedMillis(startedAt), size(messages), size(tools),
                    getProviderName(), getModelName(), e);
            throw e;
        }
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
