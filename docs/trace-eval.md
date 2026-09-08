# Trace 与 Eval

Nano v1.2.0 将可观测性放在 Agent 主链路，而不是依赖零散控制台日志。

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

## Eval 边界

`benchmarks/cases.json` 提供 20 条固定任务语料，覆盖检索、修改、计划、MCP、安全治理和三种 Agent 模式。`BenchmarkCorpus` 校验语料结构，`EvalRunner` 负责顺序执行用例、隔离单例异常、调用可插拔判定器并生成 JSON 报告。

当前尚未提供真实 Agent 执行适配器、临时 fixture 复制和 LLM-as-Judge，因此仓库不声明 pass rate。后续接入时应从 Trace 读取 token、工具步数和耗时，避免评测结果与实际执行链路割裂。
