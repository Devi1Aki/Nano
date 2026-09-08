package com.nano.llm;

import com.nano.trace.TraceContext;
import com.nano.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TracingLlmClientTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsTokenUsageWithoutChangingResponse() throws Exception {
        try (TraceStore store = new TraceStore(tempDir.resolve("trace.db"))) {
            TraceContext.Session session = TraceContext.start(store, "react", "hello", "fake", "fake-1");
            LlmClient client = new TracingLlmClient(new FakeClient(false));

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("hello")), List.of());
            session.finish("completed", null);
            TraceContext.clear(session);

            assertEquals("ok", response.content());
            TraceStore.TraceSummary summary = store.find(session.traceId());
            assertEquals(12, summary.inputTokens());
            assertEquals(4, summary.outputTokens());
            assertEquals(2, summary.cachedInputTokens());
            assertEquals(1, summary.llmCalls());
            assertEquals("completed", store.events(session.traceId()).get(1).status());
        }
    }

    @Test
    void recordsFailureAndRethrowsOriginalException() throws Exception {
        try (TraceStore store = new TraceStore(tempDir.resolve("failed.db"))) {
            TraceContext.Session session = TraceContext.start(store, "react", "hello", "fake", "fake-1");
            LlmClient client = new TracingLlmClient(new FakeClient(true));

            assertThrows(IOException.class, () -> client.chat(List.of(), List.of()));
            session.finish("failed", "network down");
            TraceContext.clear(session);

            assertEquals(1, store.find(session.traceId()).llmCalls());
            assertEquals("failed", store.events(session.traceId()).get(1).status());
        }
    }

    private static final class FakeClient implements LlmClient {
        private final boolean fail;

        private FakeClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (fail) {
                throw new IOException("network down");
            }
            return new ChatResponse("assistant", "ok", null, List.of(), 12, 4, 2);
        }

        @Override
        public String getModelName() {
            return "fake-1";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }
    }
}
