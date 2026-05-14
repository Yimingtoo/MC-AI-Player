# Minecraft Fabric Mod 模块

## 概述

Minecraft Fabric Mod，使用 Fabric Loom 构建，目标 Minecraft 1.21.11，Java 21。

## 生命周期

```
游戏启动
  │
  ├─→ Mc_ai_player.onInitialize()
  │    └─ CONFIG_MANAGER.load()   ← 加载 Mod 配置
  │
  └─→ Mc_ai_playerClient.onInitializeClient()
       ├─ 创建 6 个 Executor 实例
       ├─ 根据 config.mcpTransport 选择传输模式
       │   ├─ "sse" → 启动 McpSseServer（端口 9123）
       │   └─ 其他  → 启动 McpServer（stdio，重定向 stdout）
       ├─ 注册 END_SERVER_TICK 处理器（监控用）
       ├─ 注册 /mcai 命令
       └─ 注册 CLIENT_STOPPING 回调（关闭 MCP Server）
```

## 关键文件

| 文件 | 路径 | 职责 |
|------|------|------|
| Mod 主类 | `src/main/java/.../Mc_ai_player.java` | `ModInitializer`，加载配置 |
| 客户端主类 | `src/client/java/.../Mc_ai_playerClient.java` | `ClientModInitializer`，初始化所有运行时组件 |
| 服务端访问 | `src/client/java/.../bridge/ServerAccessor.java` | 通过 `MinecraftClient.getInstance().getServer()` 获取 `MinecraftServer` 实例 |
| Fabric mod 元数据 | `src/main/resources/fabric.mod.json` | 模组 ID、入口点、依赖 |
| Mixin 配置 | `src/main/resources/mc_ai_player.mixins.json` | 注册 ServerWorldMixin |

## API 数据模型

包 `src/main/java/.../api/model/`

| 类 | 用途 |
|------|------|
| `ActionResponse` | 统一响应格式（`success` + `data`/`error`），含 GSON 序列化 |
| `BlockPos` | 方块坐标（`x, y, z`） |
| `BlockInfo` | 方块信息（位置、ID、属性、维度） |
| `PlayerInfo` | 玩家信息（位置、视角、血量、游戏模式） |
| `Position` | 位置坐标（浮点） |
| `ErrorCode` | 枚举，所有可能的错误码（12 种） |

包 `src/main/java/.../api/model/action/` — 工具调用请求模型：

| 类 | 对应工具 |
|------|---------|
| `MovePlayerRequest` | `move_player` |
| `SetBlockRequest` | `set_block` |
| `FillRegionRequest` | `fill_region`, `replace_blocks` |
| `ExecuteCommandRequest` | `execute_command` |

## 执行器框架

基类 `ActionExecutor` 提供通用的服务端线程执行工具方法：
- `runOnServerThread()` — 在 Minecraft Server 线程同步执行操作（CompletableFuture + 10 秒超时）
- `getPlayer()` — 获取当前玩家
- `getWorld()` — 根据维度 ID 获取世界
- `getConfig()` — 获取 Mod 配置

参见 [工具系统](06-tools.md)。

## Mixin

**`ServerWorldMixin.java`** — 注入 `World.setBlockState()` 方法的 `@HEAD`，用于在区域监控激活时捕获方块变化。详见 [区域监控](07-monitoring.md)。

## /mcai 命令

游戏内命令 (`McAiPlayerCommand.java`)：

| 子命令 | 功能 |
|--------|------|
| `/mcai status` | 显示 MCP Server 运行状态和传输模式 |
| `/mcai reload` | 重新加载配置文件 |
| `/mcai help` | 显示帮助信息 |
