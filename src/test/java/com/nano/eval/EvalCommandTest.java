package com.nano.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCommandTest {
    @Test
    void parsesSingleCaseRun() {
        EvalCommand.Request request = EvalCommand.parse("run search-001");

        assertEquals(EvalCommand.Action.RUN, request.action());
        assertEquals("search-001", request.caseId());
        assertFalse(request.allowMultiple());
    }

    @Test
    void parsesFilteredBatchRun() {
        EvalCommand.Request request = EvalCommand.parse("run --category retrieval --mode react --all");

        assertEquals("retrieval", request.category());
        assertEquals("react", request.mode());
        assertNull(request.caseId());
        assertTrue(request.allowMultiple());
    }

    @Test
    void filtersCorpusWithoutWeakeningCorpusValidation() throws Exception {
        EvalCommand.Request request = EvalCommand.parse("list --category=mcp --mode=react");
        List<BenchmarkCase> selected = EvalCommand.select(BenchmarkCorpus.loadDefault(), request);

        assertEquals(3, selected.size());
        assertTrue(selected.stream().allMatch(item -> "mcp".equals(item.category())));
        assertTrue(selected.stream().allMatch(item -> "react".equals(item.mode())));
    }
}
