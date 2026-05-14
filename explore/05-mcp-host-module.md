# MCP Host 模块

## 概述

MCP Host 是一个独立 Java 21 应用（Gradle 子项目 `mcp-host/`），连接 Deepseek API 与 Minecraft Mod，提供 REPL 交互界面。

## 入口 McpHostApplication

`main()` 方法流程：

```
1. 初始化调试日志（清空 output/debug/output_context.md）
2. 解析 CLI 参数
3. 加载 HostConfig
4. 创建 MCP 客户端（SSE 优先，--legacy-stdio 使用 stdio）
5. 连接 MCP Server → 握手 → 获取工具列表
6. 创建 LLMBridge（传入工具定义、游戏版本）
7. 启动 REPL
```

### CLI 参数

| 参数 | 说明 |
|------|------|
| `--api-key <key>` | Deepseek API 密钥 |
| `--base-url <url>` | API 地址（默认 `https://api.deepseek.com/v1`） |
| `--model <name>` | 模型名称（默认 `deepseek-chat`） |
| `--config <file>` | 配置文件路径 |
| `--mcp-url <url>` | MCP Server URL（默认 `http://localhost:9123`） |
| `--legacy-stdio` | 使用旧版 stdio 传输 |
| `--no-launch` | （旧版）不自动启动 Minecraft |

## REPL 交互界面

**`Repl.java`** — 支持 JLine（有 Console）和 Scanner（无 Console）两种模式。

### 内置命令

| 命令 | 功能 |
|------|------|
| `.help` | 显示帮助 |
| `.cancel` | 中断当前任务 |
| `.exit` | 退出 |
| `.tools` | 列出可用工具 |
| `.model` | 显示当前模型名称 |
| `.clear` | 清屏 + 清除历史 |
| `.history` | 显示历史命令 |

### 中断机制

- Ctrl+C / `.cancel` → `LLMBridge.cancel()` 取消 HTTP 请求
- 后台工作线程在 `Repl.run()` 中管理，通过 `workerThread` 字段跟踪
- 中断后回滚本轮对话历史（`processUserInput()` 中的 `savedConversationSize`）

## LLM 桥接 LLMBridge

核心类，负责与 Deepseek API 交互和管理工具调用循环。

### 初始化

```
LLMBridge 构造函数:
  1. ToolConverter.toOpenAITools() 转换 MCP 工具为 OpenAI 格式
  2. 创建 KnowledgeStore 并加载经验
  3. 添加内部工具 _report_error 和 _save_tip
  4. 构建 system message（含游戏版本、知识库）
```

### 工具调用循环

```java
processUserInput(String userMessage):
  for (round = 0; round < maxToolRoundtrips; round++):
    1. callDeepseek() → 发送对话 + 工具定义
    2. 解析响应
       a. 无 tool_calls → 返回 accumulatedContent（结束）
       b. 有 tool_calls → 执行每个工具
    3. 对每个 tool_call:
       a. 解析参数
       b. 如果是内部工具（_report_error / _save_tip）→ 本地处理
       c. 否则 → mcpClient.callTool()
       d. 自动记录错误到 KnowledgeStore
       e. 将工具结果加入对话（role: "tool"）
    4. 回到 1（继续请求 LLM）
```

### 关键特性

- **调试日志**：每次 roundtrip 的请求/响应写入 `output/debug/output_context.md`
- **结果格式化**：`extractToolResult()` 将工具返回的 JSON 转换为结构化文本（类 YAML 格式）
- **对话上限**：`MAX_CONVERSATION_SIZE = 100`，超过时从索引 1 开始删除（保留 system message）
- **中断支持**：`cancelled` 标志 + 每秒检查，能及时响应取消请求

### 内部工具

| 工具名 | 功能 |
|--------|------|
| `_report_error` | LLM 发现工具错误时，向开发者反馈（保存 JSON 到 `run/ai-player/tool-errors/`） |
| `_save_tip` | LLM 主动记录工具使用技巧到 KnowledgeStore |

## ToolConverter

将 MCP 工具定义格式转换为 OpenAI 兼容格式：

```
MCP:   { "name": "get_block", "description": "...", "inputSchema": {...} }
OpenAI: { "type": "function", "function": { "name": "...", "description": "...", "parameters": {...} } }
```

## 调试输出

每次 LLM roundtrip 的完整请求/响应记录在 `output/debug/output_context.md`，包含：
- Roundtrip 编号和时间戳
- 完整的 JSON 请求体
- 完整的 JSON 响应体
