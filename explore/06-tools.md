# 工具系统

## 概述

工具定义在 `McpProtocolHandler.buildToolDefinitions()` 中，通过 JSON Schema 描述参数。工具处理在 `registerToolHandlers()` 中注册，委托给对应的 `ActionExecutor` 子类。

## 工具列表

### 玩家控制（PlayerActionExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `get_player_position` | 无 | 获取位置、旋转角、血量、游戏模式 |
| `move_player` | `position`(必填), `yaw?`, `pitch?`, `relative?`, `dimension?` | 移动到目标坐标/相对移动 |
| `set_player_look` | `yaw?`, `pitch?` | 设置视角方向 |
| `send_chat_message` | `message`(必填) | 发送聊天消息（最长 256 字符，加 "[AI] " 前缀） |
| `player_jump` | 无 | 玩家跳跃（设置垂直速度 0.42） |
| `get_player_inventory` | 无 | 获取背包所有非空物品（slot, item, count） |

**实现位置**: `src/client/java/.../executor/PlayerActionExecutor.java`
**注意**: `move_player` 设置了 `relative` 标志时，坐标相对于当前位置。

### 世界查询（WorldQueryExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `get_block` | `x, y, z` | 查询单个方块（ID、状态属性、维度） |
| `get_blocks` | `positions`(数组，最多 256) | 批量查询方块 |
| `get_player_blocks` | `radius?`(默认 8) | 查询玩家周围非空气方块 |
| `get_biome` | `x, y, z` | 查询生物群系 |
| `get_game_time` | 无 | 查询游戏时间（time_of_day, day_time, 格式化时间） |
| `get_entities` | `x, y, z`, `radius?`(默认 16) | 查询附近实体 |

**实现位置**: `src/client/java/.../executor/WorldQueryExecutor.java`
**限制**: 批量查询最多 256 个位置；`get_player_blocks` 结果最多 5000 个；垂直范围受 `verticalQueryRange` 控制。

### 方块操作（BlockActionExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `set_block` | `position`(必填), `blockId`(必填), `blockState?`, `dimension?` | 放置单个方块（含状态属性） |
| `fill_region` | `from, to`(必填), `blockId`(必填), `replaceMode?`, `dimension?` | 填充区域（`replaceMode` 支持 "keep_air"） |
| `replace_blocks` | `from, to`(必填), `blockId, filterBlockId`(必填), `dimension?` | 替换区域内的指定方块 |

**实现位置**: `src/client/java/.../executor/BlockActionExecutor.java`
**限制**: 受 `maxBuildVolume`（默认 32768）限制；危险方块（基岩、屏障等）默认禁止；受 `enableBlockOperations` 开关控制。

### 命令执行（CommandActionExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `execute_command` | `command`(必填), `asPlayer?`(默认 true) | 执行游戏命令 |

**实现位置**: `src/client/java/.../executor/CommandActionExecutor.java`
**安全机制**:
- 命令黑名单：`/op, /deop, /stop, /ban, /ban-ip, /kick, /whitelist, /pardon`
- 支持命名空间绕过检测（`minecraft:op` → 照样拦截）
- 默认以玩家身份执行（`asPlayer=true`）
- 命令输出通过自定义 `CommandOutput` 捕获

### 区域扫描（ScanRegionExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `scan_region` | `from, to`(必填), `dimension?` | 扫描区域内的非空气方块 |

参见 [区域监控](07-monitoring.md)。

### 区域监控（MonitorRegionExecutor）

| 工具名 | 参数 | 功能 |
|--------|------|------|
| `monitor_region_start` | `from, to`(必填), `duration_ticks`(必填), `dimension?` | 启动区域监控 |
| `monitor_region_get` | `session_id`(必填) | 获取监控结果 |

参见 [区域监控](07-monitoring.md)。

## 工具注册流程

```
McpProtocolHandler(构造函数):
  1. buildToolDefinitions()     → 生成所有工具的 JSON Schema
  2. registerToolHandlers()     → 将工具名映射到 executor 方法

handleMessage():
  → handleToolsCall():
    → toolHandlers.get(name).apply(arguments)
    → 返回 ActionResponse
    → 封装为 MCP 响应格式 { content: [{ type: "text", text: "..." }], isError: bool }
```

## 错误码

定义在 `ErrorCode.java`，共 12 种错误：

| 错误码 | 触发场景 |
|--------|----------|
| `invalid_parameters` | 参数缺失或格式错误 |
| `not_in_world` | 玩家未加入世界 |
| `player_not_found` | 找不到玩家 |
| `out_of_range` | 超出查询范围或批量上限 |
| `block_blacklisted` | 操作被禁止的方块 |
| `command_disallowed` | 执行被禁止的命令 |
| `dimension_disallowed` | 维度不在允许列表 |
| `rate_limited` | 操作过于频繁 |
| `internal_error` | 内部错误 |
| `operation_disabled` | 功能被配置禁用 |
| `volume_exceeded` | 操作体积超出限制 |
| `block_not_found` | 方块 ID 不存在 |
