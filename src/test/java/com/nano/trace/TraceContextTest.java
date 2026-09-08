package com.nano.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TraceContextTest {
    @TempDir
    Path tempDir;

    @Test
    void inheritsIntoTurnChildrenWithoutLeakingIntoExistingBackgroundWorkers() throws Exception {
        ExecutorService existingWorker = Executors.newSingleThreadExecutor();
        existingWorker.submit(() -> null).get(1, TimeUnit.SECONDS);
        try (TraceStore store = new TraceStore(tempDir.resolve("trace.db"))) {
            TraceContext.Session session = TraceContext.start(store, "react", "task", "fake", "fake-1");

            assertNull(existingWorker.submit(TraceContext::current).get(1, TimeUnit.SECONDS));
            ExecutorService childWorker = Executors.newSingleThreadExecutor();
            try {
                assertSame(session, childWorker.submit(TraceContext::current).get(1, TimeUnit.SECONDS));
            } finally {
                childWorker.shutdownNow();
            }

            session.finish("completed", null);
            TraceContext.clear(session);
        } finally {
            existingWorker.shutdownNow();
        }
    }
}
