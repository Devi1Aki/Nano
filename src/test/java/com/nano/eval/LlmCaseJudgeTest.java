package com.nano.eval;

import com.nano.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCaseJudgeTest {
    @Test
    void acceptsFencedJsonAndPreservesDetails() throws Exception {
        LlmClient client = new FixedClient("""
                ```json
                {"passed":true,"details":["断言: 通过 - 找到证据"]}
                ```
                """);
        LlmCaseJudge judge = new LlmCaseJudge(client);
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "case-1", "retrieval", "react", "find code", List.of("引用文件"));

        EvalRunner.JudgeResult result = judge.judge(
                benchmarkCase, new EvalRunner.ExecutionResult("ToolRegistry.java", "trace_1"));

        assertTrue(result.passed());
        assertEquals(List.of("断言: 通过 - 找到证据"), result.details());
    }

    @Test
    void returnsFailedJudgmentFromModel() throws Exception {
        LlmCaseJudge judge = new LlmCaseJudge(new FixedClient(
                "判定如下：{\"passed\":false,\"details\":[\"缺少源码引用\"]}"));

        EvalRunner.JudgeResult result = judge.judge(
                new BenchmarkCase("case-2", "retrieval", "react", "find", List.of("引用文件")),
                new EvalRunner.ExecutionResult("没有证据", null));

        assertFalse(result.passed());
    }

    @Test
    void deterministicFailureOverridesPositiveModelJudgment() throws Exception {
        LlmCaseJudge judge = new LlmCaseJudge(new FixedClient(
                "{\"passed\":true,\"details\":[\"looks good\"]}"));
        BenchmarkCase benchmarkCase = new BenchmarkCase(
                "case-3", "editing", "react", "edit", List.of("tests pass"));
        EvalRunner.ExecutionResult execution = new EvalRunner.ExecutionResult(
                "done", "trace_3", EvalRunner.EvalMetrics.empty(),
                new EvalCheckRunner.VerificationResult(false, List.of("FAIL mvn test")));

        EvalRunner.JudgeResult result = judge.judge(benchmarkCase, execution);

        assertFalse(result.passed());
        assertTrue(result.details().contains("FAIL mvn test"));
    }

    private record FixedClient(String response) implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", response, List.of(), 1, 1);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
