package com.yiming.mcp_host.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiming.mcp_host.llm.LLMBridge;
import com.yiming.mcp_host.llm.TaskCancelledException;
import com.yiming.mcp_host.mcp.McpToolExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s04: 子智能体管理器。
 * 创建独立 LLM 会话执行探索或任务，支持 Explore（只读）和 general-purpose（可写）两种类型。
 */
public class SubagentManager {

    private static final int MAX_ROUNDS = 30;
    private static final int TOOL_RESULT_LIMIT = 50000;

    private final LLMBridge llmBridge;
    private final McpToolExecutor mcpClient;
    private final JsonArray mcpTools;  // OpenAI 格式的 MCP 工具定义

    public SubagentManager(LLMBridge llmBridge, McpToolExecutor mcpClient, JsonArray mcpTools) {
        this.llmBridge = llmBridge;
        this.mcpClient = mcpClient;
        this.mcpTools = mcpTools;
    }

    /**
     * 运行子智能体。
     *
     * @param prompt    子智能体的任务描述
     * @param agentType "Explore" 只读, "general-purpose" 可写
     * @return 子智能体的文本回复
     */
    public String run(String prompt, String agentType) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(createUserMessage(prompt));

        JsonArray tools = buildTools(agentType);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonObject response;
            try {
                response = llmBridge.send(messages, tools);
            } catch (TaskCancelledException e) {
                return "(subagent cancelled)";
            } catch (Exception e) {
                return "(subagent error: " + e.getMessage() + ")";
            }

            JsonObject message = response.getAsJsonObject("message");
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";

            messages.add(message);

            JsonArray toolCalls = message.has("tool_calls") && !message.get("tool_calls").isJsonNull()
                    ? message.getAsJsonArray("tool_calls") : null;

            if (toolCalls == null || toolCalls.isEmpty()) {
                return content.isEmpty() ? "(no summary)" : content;
            }

            List<JsonObject> results = new ArrayList<>();
            for (JsonElement tcElem : toolCalls) {
                JsonObject tc = tcElem.getAsJsonObject();
                String toolCallId = tc.get("id").getAsString();
                String functionName = tc.getAsJsonObject("function").get("name").getAsString();
                String argumentsStr = tc.getAsJsonObject("function").get("arguments").getAsString();

                JsonObject arguments;
                try {
                    arguments = JsonParser.parseString(argumentsStr).getAsJsonObject();
                } catch (Exception e) {
                    results.add(createToolResult(toolCallId, "{\"error\":\"参数解析失败: " + e.getMessage() + "\"}"));
                    continue;
                }

                String output = executeTool(functionName, arguments);
                results.add(createToolResult(toolCallId, output));
            }

            // 内联构造 user 消息 content（替代重复的 toJsonArray 辅助方法）
            JsonObject resultMsg = new JsonObject();
            resultMsg.addProperty("role", "user");
            JsonArray contentArr = new JsonArray();
            for (JsonObject r : results) {
                contentArr.add(r);
            }
            resultMsg.add("content", contentArr);
            messages.add(resultMsg);
        }

        return "(subagent reached max rounds)";
    }

    private JsonArray buildTools(String agentType) {
        JsonArray tools = new JsonArray();

        tools.add(createToolDef("read_file", "读取文件内容.",
                Map.of("path", Map.of("type", "string", "description", "文件路径"))));

        if (!"Explore".equals(agentType)) {
            tools.add(createToolDef("write_file", "写入内容到文件.",
                    Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string"))));
            tools.add(createToolDef("edit_file", "替换文件中精确匹配的文本.",
                    Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"),
                            "new_text", Map.of("type", "string"))));
        }

        for (JsonElement elem : mcpTools) {
            tools.add(elem);
        }

        return tools;
    }

    private String executeTool(String name, JsonObject args) {
        return switch (name) {
            case "read_file" -> executeRead(args);
            case "write_file" -> executeWrite(args);
            case "edit_file" -> executeEdit(args);
            default -> executeMcpTool(name, args);
        };
    }

    /** 解析并校验路径（防止目录穿越） */
    private static Path resolveSafePath(String pathStr) throws IOException {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(pathStr);
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(Path.of(System.getProperty("user.dir")))) {
            throw new IOException("Path escapes workspace");
        }
        return normalized;
    }

    private String executeRead(JsonObject args) {
        try {
            String path = args.get("path").getAsString();
            Path fp = resolveSafePath(path);
            String content = Files.readString(fp);
            return limitLength(content, TOOL_RESULT_LIMIT);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String executeWrite(JsonObject args) {
        try {
            String path = args.get("path").getAsString();
            String content = args.get("content").getAsString();
            Path fp = resolveSafePath(path);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String executeEdit(JsonObject args) {
        try {
            String path = args.get("path").getAsString();
            String oldText = args.get("old_text").getAsString();
            String newText = args.get("new_text").getAsString();
            Path fp = resolveSafePath(path);
            String content = Files.readString(fp);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }
            Files.writeString(fp, content.replace(oldText, newText));
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String executeMcpTool(String name, JsonObject args) {
        try {
            JsonObject result = mcpClient.callTool(name, args);
            return limitLength(result.toString(), TOOL_RESULT_LIMIT);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return err.toString();
        }
    }

    private static JsonObject createUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", content);
        return msg;
    }

    private static JsonObject createToolResult(String toolCallId, String content) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool_result");
        result.addProperty("tool_use_id", toolCallId);
        result.addProperty("content", content);
        return result;
    }

    private static String limitLength(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n... (truncated)" : s;
    }

    private static JsonObject createToolDef(String name, String description, Map<String, Object> props) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, String> propDef = (Map<String, String>) entry.getValue();
            JsonObject p = new JsonObject();
            p.addProperty("type", propDef.get("type"));
            p.addProperty("description", propDef.getOrDefault("description", ""));
            properties.add(entry.getKey(), p);
        }
        params.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String key : props.keySet()) {
            required.add(key);
        }
        params.add("required", required);
        function.add("parameters", params);
        tool.add("function", function);
        return tool;
    }
}
