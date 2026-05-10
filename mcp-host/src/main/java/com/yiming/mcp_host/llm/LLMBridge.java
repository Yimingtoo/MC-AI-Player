package com.yiming.mcp_host.llm;

import com.google.gson.*;
import com.yiming.mcp_host.config.HostConfig;
import com.yiming.mcp_host.mcp.McpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LLMBridge {

    private final HostConfig config;
    private final McpClient mcpClient;
    private final JsonArray toolsDefinition;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private final List<JsonObject> conversation = new ArrayList<>();
    private static final int MAX_CONVERSATION_SIZE = 100;

    public LLMBridge(HostConfig config, McpClient mcpClient, JsonArray toolsDefinition, String gameVersion) {
        this.config = config;
        this.mcpClient = mcpClient;
        this.toolsDefinition = ToolConverter.toOpenAITools(toolsDefinition);
        addInternalTools();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 初始化系统消息，设定 AI 助手的角色和行为规范
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content",
                "你是 MCP-Host AI 助手。你可以使用提供的工具来帮助用户完成任务。"
                + "请根据用户需求合理调用工具，将工具执行结果整理后回复用户。"
                + "\n\n当前 Minecraft 版本: " + gameVersion
                + "（使用数据组件语法，例如附魔用 [enchantments={levels:{\"minecraft:protection\":4}}]）"
                + "\n\n如果你发现某个工具调用返回了错误，并且怀疑是工具本身的问题（而非参数错误），请使用 _report_error 工具向开发者反馈错误信息，包括出错的工具名称、参数、错误信息和你对错误原因的分析。");
        conversation.add(systemMsg);
    }

    /**
     * 处理用户输入，执行工具调用循环，返回最终文本回答。
     */
    public String processUserInput(String userMessage) throws Exception {
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        conversation.add(userMsg);
        trimConversation();

        StringBuilder accumulatedContent = new StringBuilder();

        for (int round = 0; round < config.getMaxToolRoundtrips(); round++) {
            JsonObject response = callDeepseek();

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

            // 逐个执行工具调用
            for (JsonElement tcElem : toolCalls) {
                JsonObject tc = tcElem.getAsJsonObject();
                String toolCallId = tc.get("id").getAsString();
                String functionName = tc.getAsJsonObject("function").get("name").getAsString();
                String argumentsStr = tc.getAsJsonObject("function").get("arguments").getAsString();

                JsonObject arguments;
                try {
                    arguments = JsonParser.parseString(argumentsStr).getAsJsonObject();
                } catch (JsonParseException e) {
                    JsonObject toolResult = new JsonObject();
                    toolResult.addProperty("error", "工具参数解析失败: " + e.getMessage());
                    String resultText = toolResult.toString();
                    JsonObject toolMsg = new JsonObject();
                    toolMsg.addProperty("role", "tool");
                    toolMsg.addProperty("tool_call_id", toolCallId);
                    toolMsg.addProperty("content", resultText);
                    conversation.add(toolMsg);
                    continue;
                }
                showToolCall(functionName, arguments);

                JsonObject toolResult;
                if (functionName.startsWith("_")) {
                    toolResult = handleLocalToolCall(functionName, arguments);
                } else {
                    try {
                        toolResult = mcpClient.callTool(functionName, arguments);
                    } catch (Exception e) {
                        toolResult = new JsonObject();
                        toolResult.addProperty("error", e.getMessage());
                    }
                }

                String resultText = toolResult.toString();

                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", toolCallId);
                toolMsg.addProperty("content", resultText);
                conversation.add(toolMsg);
            }
        }

        return accumulatedContent.toString().strip();
    }

    private JsonObject callDeepseek() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.add("messages", buildMessagesJson());
        body.add("tools", toolsDefinition);
        body.addProperty("tool_choice", "auto");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 200) {
            throw new RuntimeException("API 请求失败 [" + httpResponse.statusCode() + "]: " + httpResponse.body());
        }

        JsonObject jsonResponse = JsonParser.parseString(httpResponse.body()).getAsJsonObject();
        return jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
    }

    private JsonArray buildMessagesJson() {
        JsonArray arr = new JsonArray();
        for (JsonObject msg : conversation) {
            arr.add(msg);
        }
        return arr;
    }

    private void trimConversation() {
        while (conversation.size() > MAX_CONVERSATION_SIZE) {
            conversation.remove(1); // 保留 system 消息（索引 0）
        }
    }

    private void addInternalTools() {
        // 添加 _report_error 工具定义，供 LLM 反馈工具错误
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", "_report_error");
        function.addProperty("description", "当某个工具调用返回错误时，使用此工具向开发者反馈错误信息，帮助修复问题");
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject toolNameProp = new JsonObject();
        toolNameProp.addProperty("type", "string");
        toolNameProp.addProperty("description", "出错的工具名称");
        properties.add("tool_name", toolNameProp);
        JsonObject argsProp = new JsonObject();
        argsProp.addProperty("type", "string");
        argsProp.addProperty("description", "调用工具时传入的参数（JSON 格式）");
        properties.add("arguments", argsProp);
        JsonObject errorMsgProp = new JsonObject();
        errorMsgProp.addProperty("type", "string");
        errorMsgProp.addProperty("description", "工具返回的错误信息");
        properties.add("error_message", errorMsgProp);
        JsonObject analysisProp = new JsonObject();
        analysisProp.addProperty("type", "string");
        analysisProp.addProperty("description", "你对错误原因的分析和推测");
        properties.add("analysis", analysisProp);
        params.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("tool_name");
        required.add("error_message");
        params.add("required", required);
        function.add("parameters", params);
        tool.add("function", function);
        this.toolsDefinition.add(tool);
    }

    private JsonObject handleLocalToolCall(String name, JsonObject arguments) {
        switch (name) {
            case "_report_error" -> {
                if (!arguments.has("tool_name") || !arguments.has("error_message")) {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", "_report_error 缺少必填参数 (tool_name, error_message)");
                    return result;
                }
                String toolName = arguments.get("tool_name").getAsString();
                String errorMsg = arguments.get("error_message").getAsString();
                String toolArgs = arguments.has("arguments") ? arguments.get("arguments").getAsString() : "";
                String analysis = arguments.has("analysis") ? arguments.get("analysis").getAsString() : "";

                JsonObject report = new JsonObject();
                report.addProperty("timestamp", System.currentTimeMillis());
                report.addProperty("tool_name", toolName);
                report.addProperty("arguments", toolArgs);
                report.addProperty("error_message", errorMsg);
                report.addProperty("analysis", analysis);

                try {
                    String reportsDir = "run/tool-errors";
                    new java.io.File(reportsDir).mkdirs();
                    String filename = reportsDir + "/error_" + System.currentTimeMillis() + ".json";
                    Files.writeString(Path.of(filename), report.toString());
                    System.err.println("\n[错误反馈] 工具错误报告已保存: " + filename);
                } catch (IOException e) {
                    System.err.println("[错误反馈] 保存错误报告失败: " + e.getMessage());
                }

                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("message", "错误报告已保存到本地");
                return result;
            }
            default -> {
                JsonObject result = new JsonObject();
                result.addProperty("error", "未知的本地工具: " + name);
                return result;
            }
        }
    }

    private void showToolCall(String name, JsonObject arguments) {
        System.out.println("\n[工具] " + name + "(" + arguments + ") → ...");
    }
}
