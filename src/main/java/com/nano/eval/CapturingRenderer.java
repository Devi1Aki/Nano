package com.nano.eval;

import com.nano.hitl.ApprovalRequest;
import com.nano.hitl.ApprovalResult;
import com.nano.llm.LlmClient;
import com.nano.render.Renderer;
import com.nano.render.StatusInfo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CapturingRenderer implements Renderer {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

    @Override
    public void start() {
    }

    @Override
    public void close() {
        stream.flush();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        for (LlmClient.ToolCall toolCall : toolCalls) {
            stream.println("[tool_call] " + toolCall.id() + " " + toolCall.function().name());
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        stream.println("[diff] " + filePath + " " + length(before) + " -> " + length(after));
    }

    @Override
    public void updateStatus(StatusInfo status) {
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("Eval renderer does not approve tools directly");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }

    public String output() {
        stream.flush();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
