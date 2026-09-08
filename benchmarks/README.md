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

后续 Eval Runner 会复制 fixture 到临时工作区执行任务，并记录成功率、工具步数、耗时、token 与失败类别；在执行器落地前，不在 README 或简历中填写推测指标。
