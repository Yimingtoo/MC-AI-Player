# MC-AI-Player

一个 Minecraft Fabric 模组，通过 MCP (Model Context Protocol) Server 让 AI 代理控制玩家、查询世界、操作方块和执行命令。

通信采用 **stdio 传输**、**JSON-RPC 2.0** 消息格式。

## 快速开始

### 环境要求
- Minecraft 1.21.11
- Fabric Loader 0.19.2+
- Fabric API 0.141.3+
- Java 21

### 构建

```bash
./gradlew build
```

构建产物在 `build/libs/MC_AI_Player-<version>.jar`

### 安装

1. 安装 Fabric Loader
2. 将 `fabric-api` 和 `MC_AI_Player` 的 jar 放入 `.minecraft/mods/`
3. 启动游戏

## 配置文件

首次启动自动生成于 `.minecraft/config/mc_ai_player.json`：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `maxBuildVolume` | `32768` | 方块操作最大体积 |
| `maxQueryRange` | `64` | 世界查询最大范围 |
| `enableBlockOperations` | `true` | 是否启用方块操作 |
| `enablePlayerMovement` | `true` | 是否允许玩家移动 |
| `enableCommands` | `true` | 是否允许执行命令 |
| `blockBlacklist` | 基岩、屏障、命令方块等 | 禁止操作的方块列表 |
| `commandBlacklistPrefixes` | /op, /deop, /stop 等 | 禁止执行的命令前缀 |
| `allowedDimensions` | 主世界、下界、末地 | 允许操作的维度 |

## MCP 工具列表

MCP Host 通过 stdin/stdout 与模组通信，使用 JSON-RPC 2.0。握手完成后可通过 `tools/list` 获取所有工具，通过 `tools/call` 调用工具。

### 玩家控制
| 工具名 | 说明 | 参数 |
|--------|------|------|
| `get_player_position` | 获取位置和状态 | 无 |
| `move_player` | 移动到目标坐标 | `{position, yaw, pitch, relative?, dimension?}` |
| `set_player_look` | 设置视角方向 | `{yaw?, pitch?}` |
| `send_chat_message` | 发送聊天消息 | `{message}` |
| `player_jump` | 跳跃 | 无 |
| `get_player_inventory` | 获取背包内容 | 无 |

### 世界查询
| 工具名 | 说明 | 参数 |
|--------|------|------|
| `get_block` | 查询单个方块 | `{x, y, z}` |
| `get_blocks` | 批量查询方块 | `{positions: [{x,y,z}]}` |
| `get_player_blocks` | 查询玩家周围方块 | `{radius?}` |
| `get_biome` | 查询生物群系 | `{x, y, z}` |
| `get_game_time` | 查询游戏时间 | 无 |
| `get_entities` | 查询附近实体 | `{x, y, z, radius?}` |

### 方块操作
| 工具名 | 说明 | 参数 |
|--------|------|------|
| `set_block` | 设置单个方块 | `{position, blockId, blockState?, dimension?}` |
| `fill_region` | 填充区域 | `{from, to, blockId, replaceMode?, dimension?}` |
| `replace_blocks` | 替换方块 | `{from, to, blockId, filterBlockId, dimension?}` |

### 命令
| 工具名 | 说明 | 参数 |
|--------|------|------|
| `execute_command` | 执行游戏命令 | `{command, asPlayer?}` |

## 安全

- 通信通过 stdin/stdout 仅在本地进程内进行，不对外暴露网络端口
- 敏感命令（op、stop、ban 等）默认禁止
- 危险方块（基岩、屏障等）默认禁止操作
- 可在配置文件中自定义黑名单

## 构建命令

```bash
./gradlew build          # 构建
```

## MCP Host

MCP Host 是一个独立的应用，连接 MCP Server (Minecraft 模组内的服务端) 和 Deepseek 大模型 API，实现 AI 与 Minecraft 的对话式交互。

```
用户输入 → MCP Host → Deepseek API → 模型决定调用工具 → MCP Host → Minecraft MCP Server
  → 执行结果返回模型 → 模型给出最终回答 → MCP Host 输出给用户
```

### 构建

```bash
gradle :mcp-host:build
```

编译产物在 `mcp-host/build/libs/mcp-host.jar`。

### 配置

通过环境变量配置：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `DEEPSEEK_API_KEY` | — | **必填** Deepseek API 密钥 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com/v1` | API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名称 |
| `MCP_MAX_ROUNDTRIPS` | `25` | 工具调用循环上限 |
| `MCP_TIMEOUT_SECONDS` | `30` | MCP 调用超时（秒） |

也可以通过 JSON 配置文件提供（`--config` 参数）。

### Minecraft 启动配置

MCP Host 需要 `run/mcp-launch.json` 文件来知道如何启动 Minecraft。生成方式：

```bash
# 确保先使用 Gradle 配置好 Fabric 开发环境
./gradlew configureLaunch
```

或者手动创建 `run/mcp-launch.json`：

```json
{
  "mainClass": "net.fabricmc.loader.launch.knot.KnotClient",
  "classpath": ["path/to/minecraft.jar", "path/to/fabric-loader.jar", "..."],
  "jvmArgs": [],
  "programArgs": []
}
```

### 运行

```bash
# 设置 API 密钥
export DEEPSEEK_API_KEY=sk-your-key-here

# 启动 MCP Host（自动启动 Minecraft）
gradle :mcp-host:run

# 或者直接运行已构建的 jar
java -jar mcp-host/build/libs/mcp-host.jar
```

**命令行参数：**

| 参数 | 说明 |
|------|------|
| `--api-key <key>` | API 密钥 |
| `--base-url <url>` | API 地址 |
| `--model <name>` | 模型名称 |
| `--config <file>` | 配置文件路径 |
| `--no-launch` | 不启动 Minecraft，连接已有进程 |

### 交互示例

```
mcai> 现在游戏里是什么时间？
[工具] get_game_time({}) → ...
当前游戏时间：白天，正午 12:00

mcai> 在我脚边放一块石头
[工具] get_player_position({}) → ...
[工具] set_block({"position":{...},"blockId":"stone"}) → ...
已在你脚下放置了一块石头。
```

内置命令：`.help`、`.exit`、`.tools`、`.model`、`.clear`、`.history`。
