# MCP 协议与传输层

## 协议概述

MC-AI-Player 使用 **MCP (Model Context Protocol)** 作为 Mod 与 Host 的通信协议，基于 **JSON-RPC 2.0** 消息格式。

## 传输层

### SSE 模式（默认）

Mod 启动时在端口 9123 开启 HTTP ServerSocket，提供两个端点：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/sse` | GET | SSE 事件流（服务器→客户端推送） |
| `/message?sessionId=<uuid>` | POST | 接收客户端请求（客户端→服务器） |

**SSE 事件类型：**
- `endpoint` — 连接时发送，告知客户端 `/message` 地址（含 sessionId）
- `message` — 工具调用响应的推送
- `: keepalive` — 每 30 秒的心跳（SSE 注释行）

**实现位置**：
- Mod 端：`McpSseServer.java` — 直接使用 ServerSocket，绕过 JDK HttpServer 的缓冲问题
- Host 端：`McpSseClient.java` — 读取 SSE 流，将响应匹配到挂起的请求

### stdio 模式（旧版/兼容）

Mod 作为子进程启动，通过 stdin/stdout 进行 JSON-RPC 通信。Mod 启动时重定向 stdout。

**实现位置**：
- Mod 端：`McpServer.java` — 从 stdin 读取 JSON 行，写入 stdout
- Host 端：`McpClient.java` — 启动 Java 子进程，管理管道

## JSON-RPC 消息

### 请求格式
```json
{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "get_block", "arguments": {"x": 0, "y": 64, "z": 0}}}
```

### 响应格式
```json
{"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "..."}], "isError": false}}
```

### 错误格式
```json
{"jsonrpc": "2.0", "id": 1, "error": {"code": -32602, "message": "Missing tool name"}}
```

## 协议方法

| 方法 | 方向 | 说明 |
|------|------|------|
| `initialize` | Host→Mod | 握手，交换协议版本和能力 |
| `notifications/initialized` | Host→Mod | 初始化完成通知（无响应） |
| `ping` | Host→Mod | 心跳 |
| `tools/list` | Host→Mod | 获取工具列表 |
| `tools/call` | Host→Mod | 调用工具 |

## 初始化流程

```
Host                          Mod
  │                            │
  │── initialize ────────────→ │  (协议版本、客户端信息)
  │←── result (serverInfo) ───│  (协议版本、能力、游戏版本)
  │── notifications/initialized─→  (通知完成)
  │                            │
  │── tools/list ────────────→ │
  │←── tools[] ───────────────│
  │                            │
  │── tools/call ────────────→ │
  │←── result ────────────────│
```

## 代码位置

| 组件 | 文件 | 关键类/方法 |
|------|------|------------|
| Mod SSE Server | `McpSseServer.java` | `handleSseConnection()`, `handlePostMessage()` |
| Mod stdio Server | `McpServer.java` | `loop()` - 逐行读取 stdin |
| Mod Protocol Handler | `McpProtocolHandler.java` | `handleMessage()` - 路由分发 |
| Host SSE Client | `McpSseClient.java` | `runSseLoop()`, `sendRequest()` |
| Host stdio Client | `McpClient.java` | `sendRequest()`, `startReaderThread()` |
| 客户端接口 | `McpToolExecutor.java` | 统一 `callTool()` / `listTools()` 接口 |

## 传输层对比

| 特性 | SSE | stdio |
|------|-----|-------|
| 网络 | HTTP :9123 | 本机管道 |
| 自动重连 | ✅ 支持（2 秒延迟） | ❌ 进程退出则断连 |
| 并发请求 | ✅ 通过 requestId 匹配 | ✅ 通过 requestId 匹配 |
| 实现复杂度 | 较高（手动 HTTP 解析） | 简单（stdin/stdout） |
| 调试 | 可通过 HTTP 抓包 | 较难 |
