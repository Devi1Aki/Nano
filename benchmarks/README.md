# Nano Benchmark Corpus

`cases.json` 是 Nano 的固定本地开发任务语料，作为后续 Trace/Eval 的稳定输入基线。当前版本只定义并校验任务，不声称尚未运行出的通过率。

每条任务包含：

- `id`：稳定且唯一的用例编号。
- `category`：检索、修改、计划、MCP 或安全治理。
- `mode`：建议使用的 Nano 执行路径。
- `prompt`：用户原始任务。
- `assertions`：评测执行器需要验证的结果。

运行结构校验：

```bash
mvn test -Dtest=BenchmarkCorpusTest -DskipTests=false
```

`BenchmarkCorpus` 已负责加载和约束校验，`EvalRunner` 支持注入用例执行器、结果判定器并输出 JSON 报告。真实 Agent 执行适配、隔离 fixture 和 LLM-as-Judge 尚未交付，因此不在 README 或简历中填写推测指标。
