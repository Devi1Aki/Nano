package com.nano.tool;

import com.nano.trace.TraceContext;
import com.nano.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRegistryTraceTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsParallelToolResultsInInvocationOrder() throws Exception {
        try (TraceStore store = new TraceStore(tempDir.resolve("trace.db"))) {
            TraceContext.Session session = TraceContext.start(store, "react", "list files", "fake", "fake-1");
            ToolRegistry registry = new ToolRegistry();

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("call_1", "missing_one", "{}"),
                    new ToolRegistry.ToolInvocation("call_2", "missing_two", "{}")
            ));
            session.finish("completed", null);
            TraceContext.clear(session);

            assertEquals(List.of("call_1", "call_2"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
            List<String> tracedIds = store.events(session.traceId()).stream()
                    .filter(event -> "tool".equals(event.type()))
                    .map(TraceStore.TraceEvent::data)
                    .map(data -> data.contains("call_1") ? "call_1" : "call_2")
                    .toList();
            assertEquals(2, tracedIds.size());
            assertEquals(List.of("call_1", "call_2"), tracedIds.stream().sorted().toList());
            assertEquals(2, store.find(session.traceId()).toolCalls());
        }
    }
}
