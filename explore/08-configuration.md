# 配置系统

## Mod 配置

### 配置文件

路径：`.minecraft/config/ai-player/mc_ai_player.json`

首次启动自动生成，可通过 `/mcai reload` 重新加载。

### 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `maxBuildVolume` | int | 32768 | fill_region/replace_blocks 最大体积 |
| `maxScanVolume` | int | 1000 | scan_region/monitor_region 最大体积 |
| `maxScanResult` | int | 512 | scan_region 最大返回方块数 |
| `maxQueryRange` | int | 64 | 世界查询最大半径 |
| `verticalQueryRange` | int | 5 | `get_player_blocks` 垂直范围 |
| `enableBlockOperations` | bool | true | 是否启用方块操作 |
| `enablePlayerMovement` | bool | true | 是否允许玩家移动 |
| `enableCommands` | bool | true | 是否允许执行命令 |
| `mcpPort` | int | 9123 | MCP SSE Server 端口 |
| `mcpTransport` | string | "sse" | 传输模式（"sse" 或 "stdio"） |
| `blockBlacklist` | string[] | 基岩、屏障、命令方块等 | 禁止操作的方块列表 |
| `commandBlacklistPrefixes` | string[] | /op, /deop, /stop 等 | 禁止执行的命令前缀 |
| `allowedDimensions` | string[] | 主世界、下界、末地 | 允许操作的维度 |

### 实现

- **`ModConfig.java`** — POJO 类，字段 + 默认值
- **`ModConfigManager.java`** — 加载/保存/重新加载（GSON JSON <-> POJO），使用 `FabricLoader` 定位配置目录

## Host 配置

### 配置方式

支持三种方式（优先级从高到低）：
1. CLI 参数（`--api-key`, `--base-url`, `--model`）
2. 配置文件（`--config` 参数指定路径的 JSON 文件）
3. 环境变量（默认）→ `HostConfig` 构造函数中的 `resolve*()` 方法

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPSEEK_API_KEY` | — | **必填** Deepseek API 密钥 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com/v1` | API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名称 |
| `MCP_MAX_ROUNDTRIPS` | `25` | 工具调用循环上限 |
| `MCP_TIMEOUT_SECONDS` | `30` | MCP 调用超时（秒） |

### 实现

**`HostConfig.java`** — 构造函数使用"参数 > 环境变量 > 默认值"策略。
必须提供 API Key（环境变量或参数），否则抛出 `IllegalStateException`。
验证规则：`maxToolRoundtrips ≥ 1`，`mcpTimeoutSeconds ≥ 5`。
