# Nano

面向本地开发任务的 Agent Harness。Nano 以 Java CLI 为入口，把模型调用、工具执行、上下文管理、MCP 外部工具和人工审批统一到一套可运行的本地 Agent 流程中，用自然语言完成代码检索、文件修改、命令执行、网页读取和多步骤开发任务。

项目目标不是做一个聊天壳，而是沉淀一套接近 Claude Code 工作方式的本地开发 Agent：轻量任务走 ReAct，复杂任务走 Plan-and-Execute，多角色任务走 Multi-Agent，并通过 Memory、ToolRegistry、MCP、HITL 和审计机制保证执行链路可控、可追踪、可扩展。

## Startup

```text
   /\_/\      Nano  v16.1.0
   ( o.o )    Model glm-5.1 (glm)
    > ^ <     MCP 4/4 · 61 tools · 2/2 skills · ReAct
               ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG
               Agent Harness for local development tasks

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## Features

- Agent Harness：统一模型接入、工具调用、会话状态、上下文压缩和运行渲染，三种任务模式共享同一套执行底座。
- ReAct / Plan / Team：默认 ReAct 处理轻量任务，`/plan` 生成计划并按依赖执行，`/team` 通过 Planner / Worker / Reviewer 完成协作任务。
- Memory & Context：短期记忆管理当前对话，长期记忆跨会话持久化，conversationHistory 维护真实发送给 LLM 的消息队列，并在上下文接近窗口时触发摘要压缩。
- Tool Calling：文件读写、目录扫描、Shell 命令、项目创建、代码检索、网页搜索等能力封装为 Function Calling 工具，支持多 tool_calls 并行执行和顺序回灌。
- MCP Integration：支持 stdio 和 Streamable HTTP 两类 MCP Server，自动发现 tools/list 并注册到 ToolRegistry，外部工具命名为 `mcp__{server}__{tool}`。
- HITL & Audit：写文件、执行命令、创建项目、回滚等高风险操作支持人工确认，结合路径围栏、危险命令拦截和 JSONL 审计日志降低本地执行风险。
- RAG Code Search：基于 JavaParser 做代码分块，支持文件 / 类 / 方法粒度索引，结合向量检索和关键词检索辅助 Agent 理解本地代码库。
- Browser & Web：静态网页优先走 `web_fetch`，SPA / 登录态 / 防爬页面可通过 Chrome DevTools MCP 读取 DOM 快照和执行浏览器操作。

## Architecture

```text
User Input
   |
   v
CLI / Renderer / Command Parser
   |
   +--> ReAct Agent
   +--> Plan-and-Execute Agent
   +--> Multi-Agent Orchestrator
             |
             v
      ToolRegistry / MCP Bridge / HITL
             |
             v
  File System · Shell · RAG · Web · Browser · Runtime API

MemoryManager / ConversationHistoryCompactor / SnapshotService
贯穿三条执行路径，负责记忆注入、上下文治理和执行快照。
```

核心模块：

```text
src/main/java/com/nano/
├── agent/       ReAct、Plan-and-Execute、Multi-Agent 编排
├── cli/         命令解析、交互入口、补全、高亮、历史记录
├── tool/        内置工具注册、Function Calling 执行与结果回灌
├── mcp/         MCP 客户端、server 管理、transport、resources
├── memory/      短期记忆、长期记忆、上下文压缩
├── rag/         代码索引、分块、向量存储、代码检索
├── hitl/        人工审批策略与终端审批交互
├── policy/      路径校验、命令拦截、审计日志
├── browser/     浏览器会话、CDP 模式切换、敏感页面策略
├── render/      inline / lanterna / plain 三种渲染形态
└── runtime/     后台任务队列与本地 Runtime API
```

## Quick Start

环境要求：

- Java 17+
- Maven 3.8+
- 至少配置一个模型 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY`

启动：

```bash
cp .env.example .env

# 编辑 .env，至少填写一个 API Key
mvn clean package
java -jar target/nano-1.0-SNAPSHOT.jar
```

开发期也可以直接运行主类：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.nano.cli.Main"
```

## Configuration

Nano 会优先读取 `.env` 与环境变量。常用配置如下：

```bash
# LLM
GLM_API_KEY=your_api_key_here
DEEPSEEK_API_KEY=your_deepseek_api_key_here
STEP_API_KEY=your_step_key_here
KIMI_API_KEY=your_kimi_key_here

# Embedding / RAG
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434

# Renderer
NANO_RENDERER=inline
NANO_NO_STATUSBAR=false

# MCP startup
NANO_MCP_INITIALIZE_TIMEOUT_SECONDS=60
NANO_MCP_STARTUP_WAIT_SECONDS=8
```

更多配置见 `.env.example`。

## Usage

常用交互命令：

```text
/model                  查看或切换模型
/context                查看上下文窗口、RAG topK、token 状态
/memory                 查看记忆状态
/save <fact>            保存长期记忆
/index                  构建当前项目代码索引
/search <query>         检索代码库
/plan <task>            使用 Plan-and-Execute 执行复杂任务
/team <task>            使用 Multi-Agent 协作执行任务
/mcp                    查看 MCP server 和工具状态
/browser status         查看浏览器连接状态
/hitl on|off            开关人工审批
/audit [N]              查看最近 N 条危险操作审计
/snapshot               查看执行快照
/restore <N>            回滚到最近第 N 个 pre-turn 快照
/exit                   退出
```

自然语言示例：

```text
帮我阅读 src/main/java/com/nano/agent 下的执行流程，并总结 ReAct 的工具调用链路

/plan 给这个项目增加一个新的 CLI 命令 /doctor，用来检查 API Key、MCP 和 Java 版本

/team 帮我分析 memory 模块的上下文压缩逻辑，找出可能破坏 tool_call/tool_result 配对的边界

阅读 @src/main/java/com/nano/mcp/McpClient.java，解释 initialize 到 tools/call 的完整流程
```

## Agent Modes

Nano 当前有三条主执行路径：

| 模式             | 入口     | 适合场景                               |
| ---------------- | -------- | -------------------------------------- |
| ReAct            | 默认输入 | 单轮问答、简单修改、边执行边观察       |
| Plan-and-Execute | `/plan`  | 多步骤、有依赖、需要先审阅计划的任务   |
| Multi-Agent      | `/team`  | 需要任务拆解、执行和结果审核的复杂任务 |

三条路径复用 `ToolRegistry`、`MemoryManager`、`ConversationHistoryCompactor` 和 `SnapshotService`，避免每种模式各写一套工具调用和上下文管理逻辑。

## Tools

内置工具包括：

```text
read_file
write_file
list_dir
execute_command
create_project
search_code
web_search
web_fetch
revert_turn
```

工具调用流程：

1. LLM 通过 Function Calling 选择工具并生成参数。
2. ToolRegistry 校验工具名、参数和执行策略。
3. 高风险工具进入 HITL 审批链路。
4. 工具执行结果按 `tool_call_id` 回灌到 conversationHistory。
5. Agent 基于工具结果继续推理，直到生成最终回答。

同一轮返回多个 `tool_calls` 时，Nano 会并行执行无依赖工具，并按原始顺序写回结果，保持 OpenAI-compatible tool call 协议稳定。

## MCP

Nano 支持把外部 MCP Server 暴露的工具动态接入 Agent：

- stdio：通过子进程标准输入输出通信，适合本地工具 server。
- Streamable HTTP：通过 OkHttp + SSE 连接远程 MCP server。
- tools/list：启动后发现外部工具并转换为 Nano 工具描述。
- tools/call：模型选择 MCP 工具后，由 McpToolBridge 转发调用。
- resources：支持列举和读取 MCP resource，并可用 `@server:uri` 在输入中引用。

工具命名规则：

```text
mcp__chrome-devtools__take_snapshot
mcp__filesystem__read_file
mcp__server__tool
```

默认情况下，MCP 工具也会纳入 HITL 和 AuditLog，避免第三方工具绕过本地安全策略。

## Memory & Context

Nano 将记忆和上下文分成三层：

- 短期记忆：当前会话里的用户消息、模型回复、工具调用和工具结果。
- 长期记忆：通过 `/save` 或明确保存动作写入，跨会话复用。
- conversationHistory：真实发送给 LLM 的消息队列，严格维护 `assistant.tool_calls -> tool` 的配对关系。

上下文接近模型窗口阈值时，Compactor 会对旧消息做摘要压缩，保留最近多轮完整交互，并按 user message 边界重建历史，避免切断 tool call 协议。

## Safety

Nano 不是容器沙箱，也不承诺隔离任意恶意代码。它的本地安全模型由几层机制组成：

- HITL：高风险工具调用前要求用户确认。
- PathGuard：文件类操作默认限制在项目根目录内。
- CommandGuard：提前拒绝明显危险命令，如全盘删除、系统关机、设备写入等。
- AuditLog：危险工具调用按天写入 JSONL，记录参数摘要、审批结果和执行结果。
- SnapshotService：每轮任务前后创建 side-git 快照，可通过 `/restore` 回滚。

## Runtime API

Nano 可以作为本地 Runtime API 运行，便于接入 IDE 插件、自动化脚本或 Web 面板：

```bash
NANO_RUNTIME_API_KEY=your_local_api_key \
java -jar target/nano-1.0-SNAPSHOT.jar serve --http --port 8080
```

主要端点：

```text
POST /v1/threads
POST /v1/threads/{id}/turns
GET  /v1/threads/{id}/events
```

Runtime API 只监听本地，并要求 `Authorization: Bearer <key>` 或 `X-Nano-API-Key`。

## Tests

日常打包默认跳过测试，优先产出可手工验收的 jar：

```bash
mvn clean package
```

按范围回归：

```bash
mvn test -Pquick
mvn test -Pphase16-smoke
mvn test -Dtest=ExecutionPlanTest -DskipTests=false
mvn test -DskipTests=false
```

## Project Status

已完成的核心能力：

- ReAct Agent 循环
- Plan-and-Execute + DAG 任务依赖
- Multi-Agent Planner / Worker / Reviewer
- Memory 与长上下文压缩
- ToolRegistry 与并行 tool calls
- MCP stdio / Streamable HTTP 接入
- Chrome DevTools MCP 浏览器能力
- RAG 代码检索
- HITL 审批、安全策略和审计日志
- Side-Git 快照与回滚
- Runtime API 与后台任务队列
- 图片输入与多模态消息格式

当前边界：

- 不提供容器 / VM 级沙箱。
- MCP OAuth、sampling 和 server 自动恢复仍属于后续增强。
- 图片输入依赖具体模型是否支持多模态。
- 代码检索效果依赖 embedding provider 和本地索引质量。

## License

本项目当前未声明开源许可证。公开发布前建议补充 `LICENSE`，否则默认保留全部权利。
