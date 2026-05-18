# 知识库系统

## 概述

KnowledgeStore 实现跨对话的工具使用经验持久化。LLM 在工具调用中学到的经验教训会被保存到文件，下次启动时自动加载。

采用 **渐进式披露** 模式：启动时只注入标题索引，完整内容在 LLM 需要时按需提供。

## 存储

- 文件：`run/ai-player/knowledge.json`
- 格式：JSON 数组
- 管理：自动加载、自动保存、自动去重

## 数据模型

```json
{
  "id": "a1b2c3d4",
  "tool": "execute_command",
  "title": "gamerule命令格式",
  "summary": "Minecraft 1.21.11 gamerule命令格式——查询: `gamerule <rule>`...",
  "argsPattern": "[command, asPlayer]",
  "result": "结果或错误信息",
  "category": "tip",
  "confirmed": true,
  "count": 3,
  "firstSeen": 1715000000000,
  "lastSeen": 1715000123000
}
```

| 字段 | 说明 | 用途 |
|------|------|------|
| `title` | 短标题 | 在 system prompt 索引中展示 |
| `summary` | 完整正文 | `_search_knowledge` 返回、自动注入时展示 |

## 渐进式披露

### 1. 摘要推送（Index injection）

LLMBridge 启动时调用 `getIndex()`，在 system message 末尾注入仅包含标题的索引：

```
=== 过往经验（跨对话记忆） ===
[execute_command]
- gamerule命令格式
- 全部47个布尔gamerule
- 管理命令(OP 3-4级)
...

你可以使用 _search_knowledge(query) 工具搜索相关知识详情。
```

### 2. 自动注入（Auto injection）

**首轮 pre-injection**：收到用户消息后、第一次 LLM 调用前，用用户输入作为 query 搜索 top 相关知识，注入为 `role=system` 消息。

**工具后 post-injection**：每轮 roundtrip 的工具执行完成后，用工具名 + 参数值作为 query 搜索知识，将结果（标题 + 前 300 字截断）注入为 `role=system` 消息，供下一轮 LLM 参考。

### 3. LLM 按需搜索（Search injection）

LLM 可通过内部工具 `_search_knowledge(query)` 随时搜索知识详情。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 自然语言查询（如 "gamerule命令格式"） |

返回：最多 5 条匹配结果，含标题、关联工具名、匹配度和完整正文（前 300 字）。

## TF-IDF 搜索

- **分词策略**：英文字母/数字序列作为完整 token，CJK 字符序列生成相邻字符 bigram
- **索引构建**：懒加载，首次 `search()` 调用时构建，entry 变更时增量重建
- **排序**：TF-IDF 余弦相似度，动态阈值（最高分的 40%，最多 5 条）
- **依赖**：零外部依赖，纯 Java 实现

## 知识注入流程

```
1. LLMBridge 创建 → new KnowledgeStore() → 加载 knowledge.json
2. knowledgeStore.getIndex() → 格式化为标题索引（仅标题，无正文）
3. 注入 system message → "=== 过往经验（跨对话记忆）==="
4. 收到用户消息 → knowledgeStore.search(userMessage) → 首轮预注入
5. LLM 调用工具 → 工具执行完成 → knowledgeStore.search(toolContext) → 自动注入
   或 LLM 主动调用 _search_knowledge(query) → 按需搜索
```

## 自动记录机制

### 工具调用错误自动记录

在 `LLMBridge.processUserInput()` 中，每次工具调用后检查结果：

```java
if (toolResult.has("error")) {
    knowledgeStore.record(functionName, summary, argumentsStr, errorMessage, "error");
}
```

### LLM 主动记录

LLM 可通过两个内部工具主动记录知识：

| 内部工具 | 触发条件 | 作用 |
|----------|----------|------|
| `_save_tip` | LLM 发现最佳实践 | 保存优化技巧到知识库，title 自动从正文提取 |
| `_report_error` | 工具返回错误 | 保存错误报告到 `run/ai-player/tool-errors/` |

## 数据迁移

旧格式的 knowledge.json（无 `title` 字段）在首次加载时自动迁移：
- `load()` 检测到 `title` 为空时，调用 `extractTitle(summary)` 自动提取
- 提取规则：取首句句号/分号前的内容，不超过 50 字
- 首次 `save()`（发生在下一次 `record()` 调用）将 title 持久化到文件

## System prompt 指令

system message 中包含以下指导，帮助 LLM 理解渐进式披露机制：

> 在第一次调用任何工具前，建议先使用 _search_knowledge 工具搜索当前任务相关的历史经验，避免重复踩坑。

## 实现文件

| 文件 | 职责 |
|------|------|
| `KnowledgeStore.java` | 存储管理、TF-IDF 搜索、标题索引、去重 |
| `LLMBridge.java` | 自动注入逻辑、`_search_knowledge` 工具处理 |
| `explore/09-knowledge-system.md` | 设计文档（本文） |
