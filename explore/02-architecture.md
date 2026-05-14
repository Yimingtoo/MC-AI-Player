# 系统架构

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│                   用户 (User)                        │
│              (通过 REPL 交互)                        │
└──────────────────────┬──────────────────────────────┘
                       │ 文本输入/输出
                       ▼
┌─────────────────────────────────────────────────────┐
│                  MCP Host (Java 21)                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │   REPL   │──│LLMBridge │──│ MCP Client        │   │
│  │ (JLine)  │  │(Deepseek)│  │ (SSE/stdio)       │   │
│  └──────────┘  └──────────┘  └────────┬─────────┘   │
│                                        │             │
│  ┌──────────────────┐                  │             │
│  │  KnowledgeStore   │  (跨会话记忆)    │             │
│  └──────────────────┘                  │             │
└────────────────────────────────────────┼─────────────┘
                                         │ MCP 协议 (JSON-RPC 2.0)
                                         │ (SSE: HTTP :9123 / stdio: 管道)
                                         ▼
┌─────────────────────────────────────────────────────┐
│           Minecraft Mod (Fabric 1.21.11)              │
│                                                       │
│  ┌──────────────┐   ┌──────────────────────────────┐ │
│  │ MCP Server    │───│   McpProtocolHandler         │ │
│  │ (SSE / stdio) │   │   (消息路由 + 工具定义)       │ │
│  └──────────────┘   └──────────┬───────────────────┘ │
│                                │                     │
│  ┌─────────────────────────────┴──────────┐          │
│  │          ActionExecutor 体系            │          │
│  │  ┌──────────┐ ┌───────────┐ ┌────────┐ │          │
│  │  │ Player   │ │ WorldQuery│ │ Block  │ │          │
│  │  │ Action   │ │ Executor  │ │ Action │ │          │
│  │  ├──────────┤ ├───────────┤ ├────────┤ │          │
│  │  │ Command  │ │ ScanRegion│ │Monitor │ │          │
│  │  │ Action   │ │ Executor  │ │ Region │ │          │
│  │  └──────────┘ └───────────┘ └────────┘ │          │
│  └─────────────────────────────────────────┘          │
│                                                       │
│  ┌────────────────┐   ┌──────────────────────┐        │
│  │   ModConfig    │   │  MonitoringSession    │        │
│  │   Manager      │   │  + Mixin (World)      │        │
│  └────────────────┘   └──────────────────────┘        │
└─────────────────────────────────────────────────────┘
```

## 数据流

用户输入一次 **Task** 的完整流程：

```
1. 用户在 REPL 输入文本
2. REPL → LLMBridge.processUserInput()
3. LLMBridge 向 Deepseek API 发送请求（含 system prompt + 对话历史 + 工具定义）
4. Deepseek 返回响应：
   a. 纯文本回答 → 直接显示给用户，流程结束
   b. 工具调用请求 → 进入工具循环
5. LLMBridge 解析工具调用，通过 MCP Client 发送 JSON-RPC 请求
6. MCP Client → HTTP(S) POST / SSE → MCP Server
7. MCP Server → McpProtocolHandler → 对应 Executor
8. Executor 在 Minecraft Server 线程执行操作
9. 结果逐级返回至 LLMBridge
10. LLMBridge 将工具结果加入对话，再次请求 Deepseek
11. 重复步骤 4-10 直到 LLM 返回纯文本回答或达到 roundtrip 上限
12. 最终回答显示给用户
```

## 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 传输协议 | **SSE 为主**（端口 9123），stdio 为兼容选项 | SSE 支持自动重连、更稳定；stdio 用于旧版兼容 |
| 通信格式 | JSON-RPC 2.0 | MCP 协议标准 |
| LLM 模型 | Deepseek（可配置） | 通过 OpenAI 兼容 API 调用 |
| 工具格式 | MCP `tools/list` → 内部转换为 OpenAI tool 格式 | Deepseek 兼容 OpenAI 的 tool calling |
| 跨会话记忆 | KnowledgeStore（JSON 文件） | LLM 在工具调用中学到的经验持久化，下次启动自动注入 |
| Mod 端执行 | 在 Minecraft Server 线程同步执行 | 所有世界操作必须在服务端线程进行 |
| 中断处理 | Ctrl+C → 取消 HTTP 请求 + 回滚对话 | 保证 REPL 响应性，不残留无效对话状态 |

## 模块文件树

```
MC-AI-Player/
├── src/                          # Minecraft Fabric Mod
│   ├── main/java/.../
│   │   ├── Mc_ai_player.java     # Mod 初始化
│   │   ├── api/model/            # API 数据模型
│   │   ├── config/               # 配置系统
│   │   ├── mixin/                # Minecraft Mixin
│   │   └── monitor/              # 区域监控
│   └── client/java/.../
│       ├── client/
│       │   ├── Mc_ai_playerClient.java  # 客户端初始化
│       │   ├── bridge/           # 服务端访问桥
│       │   ├── command/          # /mcai 命令
│       │   ├── executor/         # 工具执行器
│       │   └── mcp/              # MCP 服务器 + 协议处理器
├── mcp-host/                     # MCP Host 应用
│   └── src/main/java/.../
│       ├── McpHostApplication.java  # 入口
│       ├── cli/Repl.java         # 交互界面
│       ├── config/HostConfig.java   # Host 配置
│       ├── llm/                  # LLM 桥接 (LLMBridge, ToolConverter)
│       ├── mcp/                  # MCP 客户端 (SSE + stdio)
│       └── memory/               # 知识库 (KnowledgeStore)
└── docs/                         # 文档
    └── agents/                   # Agent 技能文档
```
