# 知识库系统

## 概述

KnowledgeStore 实现跨对话的工具使用经验持久化。LLM 在工具调用中学到的经验教训会被保存到文件，下次启动时自动注入 system prompt。

## 存储

- 文件：`run/ai-player/knowledge.json`
- 格式：JSON 数组
- 管理：自动加载、自动保存、自动去重

## 数据模型

```json
{
  "id": "a1b2c3d4",
  "tool": "execute_command",
  "summary": "give 命令需要使用 components 语法设置附魔",
  "argsPattern": "[command, asPlayer]",
  "result": "结果或错误信息",
  "category": "tip",  // "error" | "tip" | "optimization"
  "confirmed": true,
  "count": 3,
  "firstSeen": 1715000000000,
  "lastSeen": 1715000123000
}
```

## 分类

| 类别 | 说明 | 注入位置 |
|------|------|----------|
| `tip` / `optimization` | 优化技巧 | 显示在 `[优化技巧]` 部分 |
| `error` | 曾遇到的错误 | 显示在 `[曾遇到的错误]` 部分 |
| 其他（已验证） | 确认有效的经验 | 显示在 `[已验证的经验]` 部分 |

## 知识注入流程

```
1. LLMBridge 创建 → new KnowledgeStore() → 加载 knowledge.json
2. knowledgeStore.getSummary() → 格式化为文本
3. 注入 system message → "=== 过往经验（跨对话记忆）==="
4. LLM 在对话中参考这些经验
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
| `_save_tip` | LLM 发现最佳实践 | 保存优化技巧到知识库 |
| `_report_error` | 工具返回错误 | 保存错误报告到 `run/ai-player/tool-errors/` |

## 实现文件

| 文件 | 职责 |
|------|------|
| `KnowledgeStore.java` | 存储管理（load/save/summary）、去重、分类格式化 |
| 自动记录逻辑 | `LLMBridge.java` 的工具循环中的错误检测 |
| 内部工具处理 | `LLMBridge.handleLocalToolCall()` 中的 `_save_tip` 和 `_report_error` |
