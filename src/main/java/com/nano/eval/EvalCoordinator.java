package com.nano.eval;

import com.nano.agent.Agent;
import com.nano.agent.AgentOrchestrator;
import com.nano.agent.PlanExecuteAgent;
import com.nano.hitl.HitlToolRegistry;
import com.nano.llm.LlmClient;
import com.nano.memory.LongTermMemory;
import com.nano.memory.MemoryManager;
import com.nano.mcp.McpServerManager;
import com.nano.render.Renderer;
import com.nano.skill.SkillRegistry;
import com.nano.tool.ToolRegistry;
import com.nano.trace.TraceContext;
import com.nano.trace.TraceStore;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

public final class EvalCoordinator {
    private final LlmClient llmClient;
    private final Agent reactAgent;
    private final McpServerManager mcpServerManager;
    private final SkillRegistry skillRegistry;
    private final TraceStore traceStore;
    private final Renderer mainRenderer;
    private final EvalReportStore reportStore;

    public EvalCoordinator(LlmClient llmClient, Agent reactAgent, McpServerManager mcpServerManager,
                           SkillRegistry skillRegistry, TraceStore traceStore, Renderer mainRenderer) {
        this(llmClient, reactAgent, mcpServerManager, skillRegistry, traceStore, mainRenderer,
                EvalReportStore.openDefault());
    }

    EvalCoordinator(LlmClient llmClient, Agent reactAgent, McpServerManager mcpServerManager,
                    SkillRegistry skillRegistry, TraceStore traceStore, Renderer mainRenderer,
                    EvalReportStore reportStore) {
        this.llmClient = llmClient;
        this.reactAgent = reactAgent;
        this.mcpServerManager = mcpServerManager;
        this.skillRegistry = skillRegistry;
        this.traceStore = traceStore;
        this.mainRenderer = mainRenderer;
        this.reportStore = reportStore;
    }

    public void handle(String payload, PrintStream ui) {
        EvalCommand.Request request = EvalCommand.parse(payload);
        try {
            switch (request.action()) {
                case HELP -> ui.println(EvalFormatter.help());
                case REPORTS -> ui.println(EvalFormatter.reports(reportStore.reports()));
                case COMPARE -> compare(request, ui);
                case LIST -> ui.println(EvalFormatter.cases(
                        EvalCommand.select(BenchmarkCorpus.loadDefault(), request)));
                case RUN -> runSelection(request, ui);
            }
        } catch (Exception e) {
            ui.println("❌ Eval 命令失败: " + e.getMessage());
        }
        ui.println();
    }

    private void compare(EvalCommand.Request request, PrintStream ui) throws Exception {
        String baselineName = request.baseline() == null ? "previous" : request.baseline();
        String candidateName = request.candidate() == null ? "latest" : request.candidate();
        Path baselinePath = reportStore.resolve(baselineName);
        Path candidatePath = reportStore.resolve(candidateName);
        ui.println(EvalFormatter.compare(
                reportStore.read(baselinePath.toString()),
                reportStore.read(candidatePath.toString()),
                baselinePath,
                candidatePath));
    }

    private void runSelection(EvalCommand.Request request, PrintStream ui) throws Exception {
        List<BenchmarkCase> selected = EvalCommand.select(BenchmarkCorpus.loadDefault(), request);
        if (selected.isEmpty()) {
            ui.println("❌ 没有匹配的 benchmark case，使用 /eval list 查看可用用例。");
            return;
        }
        if (request.caseId() == null && !request.allowMultiple()) {
            ui.println("❌ 批量运行需要显式添加 --all；单条用例使用 /eval run <case-id>。");
            return;
        }
        if (selected.stream().anyMatch(EvalCoordinator::mayModifyWorkspace)) {
            ui.println("🧪 editing/safety 用例将在固定临时 fixture 中运行，HITL 与策略层仍然生效。");
        }

        ToolRegistry registry = reactAgent.getToolRegistry();
        EvalRunner runner = new EvalRunner(
                benchmarkCase -> {
                    ui.println("▶ Eval " + benchmarkCase.id() + " [" + benchmarkCase.mode() + "]");
                    return executeCase(benchmarkCase, registry);
                },
                new LlmCaseJudge(llmClient));
        try {
            EvalRunner.EvalReport report = runner.run(selected, request.repeat());
            Path output = reportStore.write(report);
            ui.println(EvalFormatter.report(report, output));
            for (EvalRunner.CaseResult result : report.results()) {
                ui.println("  " + result.caseId() + "#" + result.attempt() + "  " + result.status()
                        + (result.traceId() == null ? "" : "  " + result.traceId()));
                if (result.error() != null) {
                    ui.println("    " + result.error());
                }
            }
            if (request.failUnder() >= 0D) {
                boolean passedGate = report.passRate() >= request.failUnder();
                ui.println((passedGate ? "✅" : "❌") + " Eval gate: "
                        + String.format("%.1f%%", report.passRate() * 100D) + " / required "
                        + String.format("%.1f%%", request.failUnder() * 100D));
            }
        } finally {
            registry.setContextProfile(reactAgent.getMemoryManager().getContextProfile());
            registry.setMemorySaver(reactAgent.getMemoryManager()::storeFact);
        }
    }

    private EvalRunner.ExecutionResult executeCase(BenchmarkCase benchmarkCase,
                                                    ToolRegistry registry) throws Exception {
        Path projectRoot = Path.of(registry.getProjectPath());
        try (EvalWorkspace workspace = EvalWorkspace.prepare(benchmarkCase, projectRoot)) {
            ToolRegistry caseRegistry = registryForWorkspace(registry, workspace);
            try {
                CapturingRenderer capture = new CapturingRenderer();
                caseRegistry.setWriteFileObserver((path, beforeAfter) ->
                        capture.appendDiff(path, beforeAfter[0], beforeAfter[1]));
                var profile = reactAgent.getMemoryManager().getContextProfile();
                MemoryManager evalMemory = new MemoryManager(
                        llmClient,
                        profile.shortTermMemoryBudget(),
                        profile.maxContextWindow(),
                        new LongTermMemory(workspace.statePath().toFile()));
                Agent evalAgent = new Agent(llmClient, caseRegistry, evalMemory);
                evalAgent.setRenderer(capture);
                evalAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                evalAgent.setSkillRegistry(skillRegistry);

                Callable<String> task = switch (benchmarkCase.mode()) {
                    case "plan" -> planTask(benchmarkCase, caseRegistry, evalAgent, capture);
                    case "team" -> teamTask(benchmarkCase, caseRegistry, evalAgent, capture);
                    default -> () -> evalAgent.run(benchmarkCase.prompt());
                };

                TraceContext.Session session = TraceContext.start(
                        traceStore,
                        "eval-" + benchmarkCase.mode(),
                        benchmarkCase.prompt(),
                        llmClient.getProviderName(),
                        llmClient.getModelName());
                String result = null;
                try {
                    result = workspace.isolated()
                            ? task.call()
                            : caseRegistry.getSnapshotService().runTurn(
                            "eval-" + benchmarkCase.mode(), benchmarkCase.prompt(), task::call);
                    if (session != null) {
                        session.finish(result != null && result.startsWith("❌") ? "failed" : "completed", null);
                    }
                } catch (Exception e) {
                    if (session != null) {
                        session.finish("failed", e.getMessage());
                    }
                    throw e;
                } finally {
                    TraceContext.clear(session);
                    capture.close();
                    if (!workspace.isolated()) {
                        registry.setWriteFileObserver((path, beforeAfter) ->
                                mainRenderer.appendDiff(path, beforeAfter[0], beforeAfter[1]));
                    }
                }
                String captured = appendResult(capture.output(), result);
                captured = captured + (captured.endsWith("\n") ? "" : "\n") + workspace.diffSummary();
                EvalCheckRunner.VerificationResult verification =
                        new EvalCheckRunner().verify(benchmarkCase, workspace.path(), captured);
                EvalRunner.EvalMetrics metrics = metrics(session);
                return new EvalRunner.ExecutionResult(
                        captured, session == null ? null : session.traceId(), metrics, verification);
            } finally {
                if (workspace.isolated()) {
                    caseRegistry.getSnapshotService().close();
                }
            }
        }
    }

    private ToolRegistry registryForWorkspace(ToolRegistry shared, EvalWorkspace workspace) {
        if (!workspace.isolated()) {
            return shared;
        }
        ToolRegistry isolated = shared instanceof HitlToolRegistry hitl
                ? new HitlToolRegistry(hitl.getHitlHandler())
                : new ToolRegistry();
        isolated.setProjectPath(workspace.path().toString());
        return isolated;
    }

    private EvalRunner.EvalMetrics metrics(TraceContext.Session session) {
        if (session == null || traceStore == null) {
            return EvalRunner.EvalMetrics.empty();
        }
        var summary = traceStore.find(session.traceId());
        return summary == null ? EvalRunner.EvalMetrics.empty() : new EvalRunner.EvalMetrics(
                summary.inputTokens(), summary.outputTokens(), summary.cachedInputTokens(),
                summary.llmCalls(), summary.toolCalls());
    }

    private static String appendResult(String captured, String result) {
        String output = captured == null ? "" : captured;
        if (result != null && !result.isBlank() && !output.contains(result)) {
            output = output + (output.endsWith("\n") ? "" : "\n") + result;
        }
        return output;
    }

    private Callable<String> planTask(BenchmarkCase benchmarkCase, ToolRegistry registry,
                                      Agent evalAgent, CapturingRenderer capture) {
        return () -> {
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    registry,
                    evalAgent.getMemoryManager(),
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    capture.stream());
            agent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            agent.setSkillRegistry(skillRegistry);
            return agent.run(benchmarkCase.prompt());
        };
    }

    private Callable<String> teamTask(BenchmarkCase benchmarkCase, ToolRegistry registry,
                                      Agent evalAgent, CapturingRenderer capture) {
        return () -> {
            AgentOrchestrator agent = new AgentOrchestrator(
                    llmClient, registry, evalAgent.getMemoryManager(), capture.stream());
            agent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            return agent.run(benchmarkCase.prompt());
        };
    }

    private static boolean mayModifyWorkspace(BenchmarkCase benchmarkCase) {
        return "editing".equals(benchmarkCase.category()) || "safety".equals(benchmarkCase.category());
    }
}
