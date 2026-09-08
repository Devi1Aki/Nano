package com.nano.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsTraceSummaryAndOrderedEvents() throws Exception {
        try (TraceStore store = new TraceStore(tempDir.resolve("traces.db"))) {
            String id = store.startTrace("react", "修复 bug", "glm", "glm-5.1");
            store.appendEvent(id, "turn", "react", "started", 0, "{}");
            store.appendEvent(id, "tool", "read_file", "completed", 12,
                    "{\"toolCallId\":\"call_1\"}");
            store.finishTrace(id, "completed", 25, 120, 30, 10, 1, 1, null);

            TraceStore.TraceSummary summary = store.find(id);
            assertNotNull(summary);
            assertEquals("completed", summary.status());
            assertEquals(120, summary.inputTokens());
            assertEquals(1, summary.toolCalls());
            assertNull(summary.error());

            List<TraceStore.TraceEvent> events = store.events(id);
            assertEquals(2, events.size());
            assertTrue(events.get(0).id() < events.get(1).id());
            assertEquals("call_1", new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(events.get(1).data()).path("toolCallId").asText());
        }
    }

    @Test
    void sanitizesPromptAndEventDataBeforePersistence() throws Exception {
        try (TraceStore store = new TraceStore(tempDir.resolve("safe.db"))) {
            String id = store.startTrace("react", "api_key=secret-value", "glm", "glm-5.1");
            store.appendEvent(id, "llm", "glm-5.1", "failed", 1,
                    "Bearer abcdefghijklmnopqrstuvwxyz");

            assertFalse(store.find(id).promptPreview().contains("secret-value"));
            assertFalse(store.events(id).get(0).data().contains("abcdefghijklmnopqrstuvwxyz"));
        }
    }
}
