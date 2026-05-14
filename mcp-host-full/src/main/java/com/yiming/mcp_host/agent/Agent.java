package com.yiming.mcp_host.agent;

import com.google.gson.*;
import com.yiming.mcp_host.config.HostConfig;
import com.yiming.mcp_host.llm.LLMBridge;
import com.yiming.mcp_host.llm.TaskCancelledException;
import com.yiming.mcp_host.llm.ToolConverter;
import com.yiming.mcp_host.mcp.McpToolExecutor;
import com.yiming.mcp_host.memory.KnowledgeStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 主编排器。持有对话历史、所有子管理器（Todo/Skill/Compression/Subagent），
 * 编排压缩→LLM调用→工具执行的主循环。
 */
public class Agent {

    private static final int NAG_REMINDER_INTERVAL = 3;
    private static final Path SKILLS_DIR = Path.of("skills");

    private final LLMBridge llmBridge;
    private final McpToolExecutor mcpClient;
    private final HostConfig config;
    private final KnowledgeStore knowledgeStore;

    // 子管理器
    private final TodoManager todoManager;
    private final SubagentManager subagentManager;
    private final SkillLoader skillLoader;
    private final CompressionManager compressionManager;

    // 对话状态
    private final List<JsonObject> conversation = new ArrayList<>();
    private final JsonArray toolsDefinition;
    private final String gameVersion;

    private volatile boolean cancelled;
    private int savedConversationSize;

    public Agent(HostConfig config, LLMBridge llmBridge, McpToolExecutor mcpClient,
                 JsonArray rawMcpTools, String gameVersion) {
        this.config = config;
        this.llmBridge = llmBridge;
        this.mcpClient = mcpClient;
        this.gameVersion = gameVersion;
        this.knowledgeStore = new KnowledgeStore();

        // 转换 MCP 工具为 OpenAI 格式
        JsonArray mcpToolsOpenai = ToolConverter.toOpenAITools(rawMcpTools);
        this.toolsDefinition = mcpToolsOpenai.deepCopy();
        addInternalToolDefs();

        // 初始化子管理器（SubagentManager 只传入纯 MCP 工具，不含 TodoWrite 等内部工具）
        this.todoManager = new TodoManager();
        this.skillLoader = new SkillLoader(SKILLS_DIR);
        this.subagentManager = new SubagentManager(llmBridge, mcpClient, mcpToolsOpenai);
        this.compressionManager = new CompressionManager();

        // 构建系统消息
        conversation.add(buildSystemMessage());
    }

    // ========== 公共 API ==========

    /**
     * 处理用户输入，执行工具调用循环，返回最终文本回答。
     */
    public String processUserInput(String userMessage) throws TaskCancelledException {
        cancelled = false;
        savedConversationSize = conversation.size();

        try {
            // 刷新系统消息（技能列表、知识库可能变化）
            conversation.set(0, buildSystemMessage());

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userMessage);
            conversation.add(userMsg);

            StringBuilder accumulatedContent = new StringBuilder();
            int roundsWithoutTodo = 0;

            for (int round = 0; round < config.getMaxToolRoundtrips(); round++) {
                if (cancelled) throw new TaskCancelledException("任务已被用户中断");

                // s06: 微压缩
                compressionManager.microcompact(conversation);

                // s06: 自动压缩（先快照，失败时恢复）
                if (compressionManager.estimateTokens(conversation) > compressionManager.getThreshold()) {
                    System.out.println("[auto-compact 触发]");
                    List<JsonObject> snapshot = new ArrayList<>(conversation);
                    try {
                        List<JsonObject> compressed = compressionManager.autoCompact(conversation, llmBridge);
                        conversation.clear();
                        conversation.add(buildSystemMessage());
                        conversation.addAll(compressed);
                    } catch (Exception e) {
                        System.err.println("[auto-compact] 失败，已恢复: " + e.getMessage());
                        conversation.clear();
                        conversation.addAll(snapshot);
                    }
                }

                // LLM 调用
                JsonObject response;
                try {
                    response = llmBridge.send(conversation, toolsDefinition);
                } catch (TaskCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("LLM 调用失败: " + e.getMessage());
                    break;
                }

                JsonObject message = response.getAsJsonObject("message");
                String content = message.has("content") && !message.get("content").isJsonNull()
                        ? message.get("content").getAsString() : "";

                if (!content.isEmpty()) {
                    if (accumulatedContent.length() > 0) accumulatedContent.append("\n");
                    accumulatedContent.append(content);
                }

                JsonArray toolCalls = message.has("tool_calls") && !message.get("tool_calls").isJsonNull()
                        ? message.getAsJsonArray("tool_calls") : null;

                if (toolCalls == null || toolCalls.isEmpty()) {
                    break;
                }

                // 追加助手消息（含 tool_calls）
                JsonObject assistantMsg = new JsonObject();
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.add("content", content.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(content));
                assistantMsg.add("tool_calls", toolCalls);
                conversation.add(assistantMsg);

                // 执行工具调用
                boolean usedTodo = false;

                for (JsonElement tcElem : toolCalls) {
                    if (cancelled) throw new TaskCancelledException("任务已被用户中断");

                    JsonObject tc = tcElem.getAsJsonObject();
                    String toolCallId = tc.get("id").getAsString();
                    String functionName = tc.getAsJsonObject("function").get("name").getAsString();
                    String argumentsStr = tc.getAsJsonObject("function").get("arguments").getAsString();

                    JsonObject arguments;
                    try {
                        arguments = JsonParser.parseString(argumentsStr).getAsJsonObject();
                    } catch (JsonParseException e) {
                        addToolResult(toolCallId, "{\"error\":\"工具参数解析失败: " + e.getMessage() + "\"}");
                        continue;
                    }

                    System.out.println("\n[工具] " + functionName + "(" + arguments + ") → ...");

                    String output;
                    boolean isTodoWrite = "TodoWrite".equals(functionName);

                    if (isTodoWrite) {
                        usedTodo = true;
                        output = executeTodoWrite(arguments);
                    } else if ("task".equals(functionName)) {
                        output = executeSubagent(arguments);
                    } else if ("load_skill".equals(functionName)) {
                        output = skillLoader.load(arguments.get("name").getAsString());
                    } else if (functionName.startsWith("_")) {
                        output = executeInternalTool(functionName, arguments);
                    } else {
                        output = executeMcpTool(functionName, arguments);
                    }

                    System.out.println("  -> " + (output.length() > 200 ? output.substring(0, 200) + "..." : output));
                    addToolResult(toolCallId, output);
                }

                // s03: Todo 提醒
                if (usedTodo) {
                    roundsWithoutTodo = 0;
                } else {
                    roundsWithoutTodo++;
                }

                if (todoManager.hasOpenItems() && roundsWithoutTodo >= NAG_REMINDER_INTERVAL) {
                    roundsWithoutTodo = 0;
                    JsonObject reminderMsg = new JsonObject();
                    reminderMsg.addProperty("role", "user");
                    reminderMsg.addProperty("content", "<reminder>请更新你的任务列表 (TodoWrite)。</reminder>");
                    conversation.add(reminderMsg);
                }
            }

            return accumulatedContent.toString().strip();

        } catch (TaskCancelledException e) {
            // 回滚本轮添加的对话历史
            while (conversation.size() > savedConversationSize) {
                conversation.remove(conversation.size() - 1);
            }
            throw e;
        }
    }

    /**
     * 中断当前任务。
     */
    public void cancel() {
        cancelled = true;
        llmBridge.cancel();
    }

    public boolean isRunning() {
        return mcpClient.isRunning();
    }

    public void stop() {
        mcpClient.stop();
    }

    public String getTodoStatus() {
        return todoManager.render();
    }

    public String listSkills() {
        return skillLoader.descriptions();
    }

    public void manualCompact() {
        if (conversation.size() <= 1) {
            System.out.println("没有可压缩的对话历史");
            return;
        }
        JsonObject systemMsg = conversation.get(0);
        List<JsonObject> rest = conversation.subList(1, conversation.size());
        List<JsonObject> compressed = compressionManager.autoCompact(new ArrayList<>(rest), llmBridge);
        conversation.clear();
        conversation.add(systemMsg);
        conversation.addAll(compressed);
        System.out.println("[manual compact] 完成");
    }

    public String listMcpTools() {
        try {
            JsonArray tools = mcpClient.listTools();
            StringBuilder sb = new StringBuilder("可用工具 (" + tools.size() + "):\n");
            for (JsonElement elem : tools) {
                JsonObject tool = elem.getAsJsonObject();
                sb.append("  - ").append(tool.get("name").getAsString());
                if (tool.has("description") && !tool.get("description").isJsonNull()) {
                    sb.append(": ").append(tool.get("description").getAsString());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取工具列表失败: " + e.getMessage();
        }
    }

    // ========== 工具执行 ==========

    private String executeTodoWrite(JsonObject args) {
        JsonArray items = args.getAsJsonArray("items");
        return todoManager.update(items);
    }

    private String executeSubagent(JsonObject args) {
        if (!args.has("prompt")) {
            return "{\"error\":\"task 工具缺少 prompt 参数\"}";
        }
        String prompt = args.get("prompt").getAsString();
        String agentType = args.has("agent_type") ? args.get("agent_type").getAsString() : "Explore";
        return subagentManager.run(prompt, agentType);
    }

    private String executeMcpTool(String name, JsonObject args) {
        try {
            JsonObject result = mcpClient.callTool(name, args);
            if (result.has("error") && !result.get("error").isJsonNull()) {
                String errorMsg = result.get("error").getAsString();
                knowledgeStore.record(name,
                        "调用 " + name + " 时出错: " + errorMsg,
                        args.toString(), errorMsg, "error");
            }
            return result.toString();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return err.toString();
        }
    }

    private String executeInternalTool(String name, JsonObject args) {
        return switch (name) {
            case "_report_error" -> handleReportError(args);
            case "_save_tip" -> handleSaveTip(args);
            default -> "{\"error\":\"未知的内部工具: " + name + "\"}";
        };
    }

    private String handleReportError(JsonObject args) {
        if (!args.has("tool_name") || !args.has("error_message")) {
            return "{\"error\":\"_report_error 缺少必填参数 (tool_name, error_message)\"}";
        }
        String toolName = args.get("tool_name").getAsString();
        String errorMsg = args.get("error_message").getAsString();
        String toolArgs = args.has("arguments") ? args.get("arguments").getAsString() : "";
        String analysis = args.has("analysis") ? args.get("analysis").getAsString() : "";

        JsonObject report = new JsonObject();
        report.addProperty("timestamp", System.currentTimeMillis());
        report.addProperty("tool_name", toolName);
        report.addProperty("arguments", toolArgs);
        report.addProperty("error_message", errorMsg);
        report.addProperty("analysis", analysis);

        try {
            String reportsDir = "run/ai-player/tool-errors";
            new java.io.File(reportsDir).mkdirs();
            String filename = reportsDir + "/error_" + System.currentTimeMillis() + ".json";
            Files.writeString(Path.of(filename), report.toString());
            System.err.println("\n[错误反馈] 工具错误报告已保存: " + filename);
        } catch (IOException e) {
            System.err.println("[错误反馈] 保存错误报告失败: " + e.getMessage());
        }

        return "{\"success\":true,\"message\":\"错误报告已保存\"}";
    }

    private String handleSaveTip(JsonObject args) {
        if (!args.has("tool_name") || !args.has("tip")) {
            return "{\"error\":\"_save_tip 缺少必填参数 (tool_name, tip)\"}";
        }
        String toolName = args.get("tool_name").getAsString();
        String tip = args.get("tip").getAsString();
        String context = args.has("context") ? args.get("context").getAsString() : "";
        String detail = context.isEmpty() ? tip : tip + "（适用场景: " + context + "）";
        knowledgeStore.saveTip(toolName, detail, "{}", tip);
        System.err.println("\n[知识库] 保存优化技巧: " + toolName + " → " + tip);
        return "{\"success\":true,\"message\":\"优化技巧已保存到知识库\"}";
    }

    // ========== 内部工具定义 ==========

    private void addInternalToolDefs() {
        // TodoWrite
        JsonObject todoTool = createFunctionTool("TodoWrite", "更新任务跟踪列表。使用 [>] 标记进行中的任务，[x] 标记已完成，[ ] 标记待办。必须提供 activeForm 描述当前进行中的动作。",
                buildParams(
                        buildProperty("items", "array", "任务列表，最多 20 项",
                                buildArrayItems(
                                        buildObjectProperty("content", "string", "任务描述"),
                                        buildObjectProperty("status", "string", "状态: pending/in_progress/completed"),
                                        buildObjectProperty("activeForm", "string", "进行中时显示的动态描述（如挖掘方块）")
                                ))
                ));
        toolsDefinition.add(todoTool);

        // task (subagent)
        JsonObject taskTool = createFunctionTool("task", "生成一个子智能体用于隔离的探索或任务执行。子智能体拥有独立的 LLM 会话和工具集。",
                buildParams(
                        buildProperty("prompt", "string", "子智能体的任务描述"),
                        buildProperty("agent_type", "string", "Explore=只读, general-purpose=可读写")
                ));
        toolsDefinition.add(taskTool);

        // load_skill
        JsonObject skillTool = createFunctionTool("load_skill", "按名称加载专业知识。可用技能会在系统提示中列出。",
                buildParams(
                        buildProperty("name", "string", "技能名称")
                ));
        toolsDefinition.add(skillTool);

        // _report_error
        JsonObject reportTool = createFunctionTool("_report_error", "当某个工具调用返回错误时，使用此工具向开发者反馈错误信息。",
                buildParams(
                        buildProperty("tool_name", "string", "出错的工具名称"),
                        buildProperty("arguments", "string", "调用参数 JSON"),
                        buildProperty("error_message", "string", "错误信息"),
                        buildProperty("analysis", "string", "对错误原因的分析")
                ));
        toolsDefinition.add(reportTool);

        // _save_tip
        JsonObject tipTool = createFunctionTool("_save_tip", "记录工具的高效用法、参数技巧或最佳实践。这些经验会在后续对话中自动加载。",
                buildParams(
                        buildProperty("tool_name", "string", "适用于哪个工具"),
                        buildProperty("tip", "string", "优化技巧的具体内容"),
                        buildProperty("context", "string", "何时使用此技巧")
                ));
        toolsDefinition.add(tipTool);
    }

    // ========== 系统消息 ==========

    private JsonObject buildSystemMessage() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "system");

        StringBuilder sb = new StringBuilder();
        sb.append("你是 MCP-Host AI 助手。你可以使用提供的工具来帮助用户完成任务。")
                .append("请根据用户需求合理调用工具，将工具执行结果整理后回复用户。")
                .append("\n\n当前 Minecraft 版本: ").append(gameVersion)
                .append("（使用数据组件语法，例如附魔用 [enchantments={levels:{\"minecraft:protection\":4}}]）")
                .append("\n\n如果发现某个工具调用返回了错误，并且怀疑是工具本身的问题（而非参数错误），")
                .append("请使用 _report_error 工具向开发者反馈错误信息，包括出错的工具名称、参数、错误信息和你对错误原因的分析。")
                .append("\n\n如果发现某个工具的高效用法、参数技巧或最佳实践，")
                .append("请使用 _save_tip 工具记录这些优化经验。")
                .append("\n\n对于多步骤任务，建议使用 TodoWrite 创建任务列表来跟踪进度。")
                .append("使用 task 工具可以将子任务委托给子智能体独立执行。");

        // 注入跨对话经验知识
        String knowledge = knowledgeStore.getSummary();
        if (!knowledge.isEmpty()) {
            sb.append("\n\n").append(knowledge);
        }

        // 注入可用技能
        if (!skillLoader.isEmpty()) {
            sb.append("\n\n可用技能:\n").append(skillLoader.descriptions());
        }

        msg.addProperty("content", sb.toString());
        return msg;
    }

    // ========== 工具定义辅助方法 ==========

    private static JsonObject createFunctionTool(String name, String description, JsonObject params) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", params);
        tool.add("function", function);
        return tool;
    }

    private static JsonObject buildParams(JsonObject... properties) {
        // 先收集所有 name，因为后续会从对象中移除 _name
        String[] names = new String[properties.length];
        for (int i = 0; i < properties.length; i++) {
            names[i] = properties[i].get("_name").getAsString();
        }

        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (int i = 0; i < properties.length; i++) {
            properties[i].remove("_name");
            props.add(names[i], properties[i]);
        }
        params.add("properties", props);

        JsonArray required = new JsonArray();
        for (int i = 0; i < properties.length; i++) {
            if (properties[i].has("_required") && properties[i].get("_required").getAsBoolean()) {
                required.add(names[i]);
            }
        }
        if (required.isEmpty()) {
            for (String name : names) {
                required.add(name);
            }
        }
        params.add("required", required);
        return params;
    }

    private static JsonObject buildProperty(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("_name", name);
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject buildProperty(String name, String type, String description, JsonObject items) {
        JsonObject p = buildProperty(name, type, description);
        if (items != null) {
            p.add("items", items);
        }
        return p;
    }

    private static JsonObject buildArrayItems(JsonObject... properties) {
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (JsonObject p : properties) {
            String name = p.get("_name").getAsString();
            p.remove("_name");
            props.add(name, p);
        }
        items.add("properties", props);
        return items;
    }

    private static JsonObject buildObjectProperty(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("_name", name);
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    // ========== 对话辅助 ==========

    private void addToolResult(String toolCallId, String content) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        toolMsg.addProperty("tool_call_id", toolCallId);
        toolMsg.addProperty("content", content);
        conversation.add(toolMsg);
    }
}
