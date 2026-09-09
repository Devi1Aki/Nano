package com.nano.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalWorkspaceTest {
    @TempDir
    Path tempDir;

    @Test
    void usesFixedFixtureAndCleansEntireContainmentDirectory() throws Exception {
        createFixture(tempDir);
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "safety-001", "safety", "react", "prompt", List.of("blocked"));
        Path container;

        try (EvalWorkspace workspace = EvalWorkspace.prepare(benchmarkCase, tempDir)) {
            container = workspace.path().getParent();
            assertTrue(Files.exists(workspace.path().resolve("protected.txt")));
            Files.writeString(workspace.path().resolve("../nano-eval-outside.txt").normalize(), "test");
            assertTrue(Files.exists(container.resolve("nano-eval-outside.txt")));
        }

        assertFalse(Files.exists(container));
    }

    @Test
    void reportsContentChangesAndIgnoresGeneratedTargetFiles() throws Exception {
        createFixture(tempDir);
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "edit-001", "editing", "react", "prompt", List.of("changed"));

        try (EvalWorkspace workspace = EvalWorkspace.prepare(benchmarkCase, tempDir)) {
            Files.writeString(workspace.path().resolve("protected.txt"), "changed");
            Files.createDirectories(workspace.path().resolve("target/classes"));
            Files.writeString(workspace.path().resolve("target/classes/generated.class"), "generated");

            String summary = workspace.diffSummary();
            assertTrue(summary.contains("modified protected.txt"));
            assertFalse(summary.contains("target"));
        }
    }

    @Test
    void sharedMcpWorkspaceStillUsesTemporaryStateDirectory() throws Exception {
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "mcp-001", "mcp", "react", "prompt", List.of("tools"));
        Path state;

        try (EvalWorkspace workspace = EvalWorkspace.prepare(benchmarkCase, tempDir)) {
            assertFalse(workspace.isolated());
            assertTrue(workspace.path().equals(tempDir.toAbsolutePath().normalize()));
            state = workspace.statePath();
            Files.writeString(state.resolve("memory.db"), "temporary");
        }

        assertFalse(Files.exists(state));
    }

    private static void createFixture(Path projectRoot) throws Exception {
        Path fixture = Files.createDirectories(projectRoot.resolve("benchmarks/fixtures/java-project"));
        Files.writeString(fixture.resolve("protected.txt"), "protected");
    }
}
