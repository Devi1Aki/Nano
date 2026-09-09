package com.nano.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsListsAndReadsReports() throws Exception {
        EvalReportStore store = new EvalReportStore(tempDir);
        EvalRunner.EvalReport report = report("search-001", 1);

        Path output = store.write(report);

        assertTrue(output.getFileName().toString().startsWith("eval-"));
        assertEquals(output, store.resolve("latest"));
        assertEquals(1, store.read("latest").passed());
    }

    @Test
    void warnsWhenComparingDifferentCaseSets() {
        String formatted = EvalFormatter.compare(
                report("search-001", 1), report("mcp-001", 0),
                Path.of("before.json"), Path.of("after.json"));

        assertTrue(formatted.contains("case 集合不同"));
    }

    private static EvalRunner.EvalReport report(String caseId, long passed) {
        String status = passed == 1 ? "passed" : "failed";
        return new EvalRunner.EvalReport("2026-01-01T00:00:00Z", 1, passed, 1 - passed, 0, 10,
                List.of(new EvalRunner.CaseResult(caseId, status, 10, "trace_1", "out", List.of(), null)));
    }
}
