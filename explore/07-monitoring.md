# 区域监控系统

## 概述

MC-AI-Player 提供两种区域相关操作：**scan**（单次扫描）和 **monitor**（持续跟踪变化）。

## scan_region（单次扫描）

**`ScanRegionExecutor.handleScanRegion()`**

扫描一个立方体区域，返回所有非空气方块的位置和 ID。

特性：
- 按 Y→X→Z 三层循环遍历
- 跳过未加载的区块（`skipped_chunks` 计数）
- 超过 `maxScanResult`（默认 512 个方块）时截断（`truncated` 标志）
- 受 `maxScanVolume`（默认 1000）限制

## monitor_region（持续监控）

一个两阶段的过程，通过 Mixin 实时捕获方块变化。

### 阶段 1: `monitor_region_start`

```
1. 参数验证（volume ≤ maxScanVolume, duration_ticks ≥ 1）
2. 清除现有 session（单例模式）
3. 构建初始快照（GT=0，所有方块包括空气）
4. 创建 MonitoringSession
5. session.startMonitoring() → 状态变为 MONITORING
6. 返回 session_id + 初始快照
```

### 阶段 2: `monitor_region_get`

```
1. 查找 session（单例，匹配 session_id）
2. 未完成 → 返回运行状态（current_tick / total_ticks）
3. 已完成 → 返回完整变更日志 + 清理 session
```

### 每 GT 的监控流程

```
END_SERVER_TICK 事件:
  1. 获取 activeSession
  2. 收集每个 pending position 的当前方块状态
  3. session.advanceTick(currentStates):
     a. currentTick++
     b. 比较 oldBlockId vs newBlockId → 生成 ChangeEntry 列表
     c. 清空 pendingChanges
     d. 若 currentTick ≥ totalTicks → 状态 COMPLETED
```

### Mixin 捕获

**`ServerWorldMixin`** — 注入 `World.setBlockState()` 的 `@HEAD`：

```
每次方块变化时:
  1. 检查是否有 activeSession
  2. 检查是否在监控的维度 + 区域内
  3. 记录变化前的老方块 ID 到 session.pendingChanges
  (putIfAbsent: 同一 GT 内同一个位置只记首次变化)
```

## 数据模型

### MonitoringSession 状态

| 状态 | 说明 |
|------|------|
| `INITIALIZING` | 刚创建，尚未开始监控 |
| `MONITORING` | 正在监控中，记录变化 |
| `COMPLETED` | 达到 duration_ticks，可获取结果 |

### 返回数据结构

**启动响应** (`buildInitialResult`):
```
session_id, type="initial", tick=0
blocks: [{position:{x,y,z}, blockId: "minecraft:stone"|null}, ...]
from, to, dimension, duration_ticks
```

**运行中状态** (`buildRunningStatus`):
```
session_id, status="running", current_tick, total_ticks
```

**完成结果** (`buildCompletedResult`):
```
session_id, type="changes", total_ticks, completed=true
per_tick: [{tick: 1, changes: [{position:{x,y,z}, oldBlockId, newBlockId}]}]
```

## 关键设计

| 特性 | 说明 |
|------|------|
| 单例模式 | 同一时间只能有一个 active session，新 session 隐式清除旧 session |
| 初始快照包含空气 | 空气块用 `blockId: null` 表示 |
| 每 GT 只记第一次变化 | 同一 GT 内同一位置的后续变化忽略（`putIfAbsent`） |
| 自动清理 | 结果读取后自动清理 session |
| Mixin 范围 | 仅监控维度 + 区域内变化，不影响性能 |

## 实现文件

| 文件 | 职责 |
|------|------|
| `ScanRegionExecutor.java` | scan_region 工具实现 |
| `MonitorRegionExecutor.java` | monitor_region 工具实现 |
| `MonitoringSession.java` | 会话状态、GT 变化记录、结果构建 |
| `MonitoringState.java` | 线程安全的单例 session 持有者（`volatile`） |
| `ServerWorldMixin.java` | Mixin 注入，捕获方块变化 |
