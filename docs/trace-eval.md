# Trace 与 Eval

Nano v1.3.0 将可观测性和可执行评测放在同一条 Agent 主链路，而不是依赖零散控制台日志或手工演示。

## Trace 链路

CLI 每次提交任务时创建一个 trace，并记录执行模式、模型、状态、耗时和脱敏后的 prompt 摘要。`TracingLlmClient` 统一记录模型调用次数、耗时和输入/输出/cache token；`ToolRegistry.executeTools()` 统一记录内置工具与 MCP 工具的名称、`tool_call_id`、耗时、超时状态以及参数/结果长度。

Trace 不保存完整模型消息、工具参数和工具结果，避免源码、命令输出或密钥进入观测数据库。写入失败采用 fail-open，任务仍按原结果继续。

默认数据库：

```text
~/.nano/traces/traces.db
```

可通过 `NANO_TRACE_DIR` 或 `-Dnano.trace.dir` 修改目录。

CLI 查询：

```text
/trace
/trace trace_ab12cd34ef56
```

## Eval 执行

`benchmarks/cases.json` 提供 20 条固定任务语料，覆盖检索、修改、计划、MCP、安全治理和三种 Agent 模式。`BenchmarkCorpus` 校验语料结构，`EvalRunner` 顺序执行用例并隔离单例异常。执行器复用真实 Agent、ToolRegistry、MCP、HITL、策略与 Side-Git 快照链路，每条用例生成独立 trace；`LlmCaseJudge` 根据执行输出逐条核验断言。

```text
/eval list
/eval list --category mcp --mode react
/eval run search-001
/eval run --category retrieval --all
/eval reports
/eval compare previous latest
```

报告默认写入 `~/.nano/eval/`，也可通过 `NANO_EVAL_DIR` 或 `-Dnano.eval.dir` 改目录。比较不同 case 集合时会显示警告，避免把语料变化误判为模型或 Agent 退化。

Eval 调用真实模型并可能触发真实工具。批量执行必须显式添加 `--all`；editing/safety 用例仍经过 HITL、PathGuard 和 CommandGuard。当前未实现临时 fixture 复制与多次采样，因此仓库不预置通过率，发布数据前应固定模型、配置、代码版本与 case 集合。
