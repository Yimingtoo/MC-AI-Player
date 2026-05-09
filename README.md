# MC-AI-Player

一个 Minecraft Fabric 模组，在游戏中嵌入 HTTP 服务器，通过 REST API 让 AI 代理控制玩家、查询世界、操作方块和执行命令。

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
| `httpPort` | `8123` | HTTP 服务端口 |
| `maxRequestsPerSecond` | `20` | 每秒最大请求数 |
| `maxBuildVolume` | `32768` | 方块操作最大体积 |
| `maxQueryRange` | `64` | 世界查询最大范围 |
| `enableBlockOperations` | `true` | 是否启用方块操作 |
| `enablePlayerMovement` | `true` | 是否允许玩家移动 |
| `enableCommands` | `true` | 是否允许执行命令 |
| `blockBlacklist` | 基岩、屏障、命令方块等 | 禁止操作的方块列表 |
| `commandBlacklistPrefixes` | /op, /deop, /stop 等 | 禁止执行的命令前缀 |
| `allowedDimensions` | 主世界、下界、末地 | 允许操作的维度 |

## API 接口

服务监听 `127.0.0.1:8123`，所有接口以 `/api/` 前缀。

### 系统
- `GET /api/health` — 健康检查
- `GET /api/capabilities` — 列出所有可用接口

### 玩家控制
- `GET /api/player/position` — 获取位置
- `POST /api/player/move` — 移动到目标坐标
- `POST /api/player/look` — 设置视角方向
- `POST /api/player/send-chat` — 发送聊天消息
- `POST /api/player/jump` — 跳跃
- `GET /api/player/inventory` — 获取背包内容

### 世界查询
- `GET /api/world/block` — 查询方块
- `POST /api/world/blocks` — 批量查询方块
- `GET /api/world/player-blocks` — 查询玩家周围方块
- `GET /api/world/biome` — 查询生物群系
- `GET /api/world/time` — 查询游戏时间
- `GET /api/world/entities` — 查询附近实体

### 方块操作
- `POST /api/blocks/set` — 设置单个方块
- `POST /api/blocks/fill` — 填充区域
- `POST /api/blocks/replace` — 替换方块

### 命令
- `POST /api/command/execute` — 执行游戏命令

## 安全

- HTTP 服务仅监听 `127.0.0.1`，不对外暴露
- 敏感命令（op、stop、ban 等）默认禁止
- 危险方块（基岩、屏障等）默认禁止操作
- 可在配置文件中自定义黑名单

## 构建命令

```bash
./gradlew build          # 构建
./gradlew runClient      # 运行客户端进行测试
```
