package com.yiming.mcp_host.llm;

import com.google.gson.*;
import com.yiming.mcp_host.config.HostConfig;
import com.yiming.mcp_host.mcp.McpToolExecutor;
import com.yiming.mcp_host.memory.KnowledgeStore;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LLMBridge {

    private final HostConfig config;
    private final McpToolExecutor mcpClient;
    private final JsonArray toolsDefinition;
    private final HttpClient httpClient;
    private final KnowledgeStore knowledgeStore;
    private final Gson gson = new Gson();

    private final List<JsonObject> conversation = new ArrayList<>();
    private static final int MAX_CONVERSATION_SIZE = 100;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private volatile boolean cancelled;
    private volatile CompletableFuture<HttpResponse<String>> currentHttpFuture;

    public LLMBridge(HostConfig config, McpToolExecutor mcpClient, JsonArray toolsDefinition, String gameVersion) {
        this.config = config;
        this.mcpClient = mcpClient;
        this.toolsDefinition = ToolConverter.toOpenAITools(toolsDefinition);
        this.knowledgeStore = new KnowledgeStore();
        addInternalTools();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 初始化系统消息，设定 AI 助手的角色和行为规范
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        String systemContent = "你是 MCP-Host AI 助手。你可以使用提供的工具来帮助用户完成任务。"
                + "请根据用户需求合理调用工具，将工具执行结果整理后回复用户。"
                + "\n\n当前 Minecraft 版本: " + gameVersion
                + "（使用数据组件语法，例如附魔用 [enchantments={levels:{\"minecraft:protection\":4}}]）"
                + "\n\n如果你发现某个工具调用返回了错误，并且怀疑是工具本身的问题（而非参数错误），请使用 _report_error 工具向开发者反馈错误信息，包括出错的工具名称、参数、错误信息和你对错误原因的分析。"
                + "\n\n如果你发现某个工具的高效用法、参数技巧或最佳实践（例如：某个参数值效果更好、调用顺序更优化），请使用 _save_tip 工具记录这些优化经验。这些经验会在后续对话中自动加载，帮助你自己和其他人更高效地使用工具。";

        // 注入跨对话经验知识
        String knowledge = knowledgeStore.getSummary();
        if (!knowledge.isEmpty()) {
            systemContent += "\n\n" + knowledge;
        }

        systemMsg.addProperty("content", systemContent);
        conversation.add(systemMsg);
    }

    /**
     * 中断当前正在执行的任务。
     */
    public void cancel() {
        cancelled = true;
        CompletableFuture<HttpResponse<String>> future = currentHttpFuture;
        if (future != null) {
            future.cancel(true);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 处理用户输入，执行工具调用循环，返回最终文本回答。
     */
    public String processUserInput(String userMessage) throws Exception {
        cancelled = false;
        int savedConversationSize = conversation.size();
        try {
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        conversation.add(userMsg);
        trimConversation();

        StringBuilder accumulatedContent = new StringBuilder();

        for (int round = 0; round < config.getMaxToolRoundtrips(); round++) {
            if (cancelled) {
                throw new TaskCancelledException("任务已被用户中断");
            }
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
                if (cancelled) {
                    throw new TaskCancelledException("任务已被用户中断");
                }
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
                boolean hadError = false;
                String errorMessage = null;
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

                // 自动记录工具调用错误
                if (toolResult.has("error") && !toolResult.get("error").isJsonNull()) {
                    hadError = true;
                    errorMessage = toolResult.get("error").getAsString();
                    String summary = "调用 " + functionName + " 时出错: " + errorMessage;
                    summary += "。参数: " + arguments.keySet();
                    knowledgeStore.record(functionName, summary, argumentsStr, errorMessage, "error");
                }

                String resultText = extractToolResult(toolResult);

                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", toolCallId);
                toolMsg.addProperty("content", resultText);
                conversation.add(toolMsg);
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

        HttpResponse<String> httpResponse;
        try {
            currentHttpFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            // 轮询等待 HTTP 响应，每秒检查 cancelled 标志
            while (true) {
                try {
                    httpResponse = currentHttpFuture.get(1, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException e) {
                    if (cancelled) {
                        currentHttpFuture.cancel(true);
                        throw new TaskCancelledException("任务已被用户中断");
                    }
                }
            }
        } catch (CancellationException e) {
            throw new TaskCancelledException("任务已被用户中断");
        } catch (InterruptedException e) {
            if (cancelled) {
                throw new TaskCancelledException("任务已被用户中断");
            }
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            currentHttpFuture = null;
        }

        String responseBody = httpResponse.body();

        // 记录请求和响应到调试日志
        writeDebugLog(body.toString(), responseBody);

        if (httpResponse.statusCode() != 200) {
            throw new RuntimeException("API 请求失败 [" + httpResponse.statusCode() + "]: " + responseBody);
        }

        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
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

        // 添加 _save_tip 工具定义，供 LLM 主动记录优化技巧
        JsonObject tipTool = new JsonObject();
        tipTool.addProperty("type", "function");
        JsonObject tipFunc = new JsonObject();
        tipFunc.addProperty("name", "_save_tip");
        tipFunc.addProperty("description", "当你发现某个工具的高效用法、参数技巧或最佳实践时，使用此工具保存优化经验，方便未来参考。例如：发现某个参数组合效果更好、调用顺序更优、或者某个值的特殊用法。");
        JsonObject tipParams = new JsonObject();
        tipParams.addProperty("type", "object");
        JsonObject tipProps = new JsonObject();
        JsonObject tipToolName = new JsonObject();
        tipToolName.addProperty("type", "string");
        tipToolName.addProperty("description", "适用于哪个工具");
        tipProps.add("tool_name", tipToolName);
        JsonObject tipContent = new JsonObject();
        tipContent.addProperty("type", "string");
        tipContent.addProperty("description", "优化技巧的具体内容，包括参数值、调用方式、使用场景等");
        tipProps.add("tip", tipContent);
        JsonObject tipContext = new JsonObject();
        tipContext.addProperty("type", "string");
        tipContext.addProperty("description", "何时使用此技巧（条件/场景描述）");
        tipProps.add("context", tipContext);
        tipParams.add("properties", tipProps);
        JsonArray tipRequired = new JsonArray();
        tipRequired.add("tool_name");
        tipRequired.add("tip");
        tipParams.add("required", tipRequired);
        tipFunc.add("parameters", tipParams);
        tipTool.add("function", tipFunc);
        this.toolsDefinition.add(tipTool);
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
                    String reportsDir = "run/ai-player/tool-errors";
                    new java.io.File(reportsDir).mkdirs();
                    String filename = reportsDir + "/error_" + System.currentTimeMillis() + ".json";
                    Files.writeString(Path.of(filename), report.toString());
                    System.err.println("\n[错误反馈] 工具错误报告已保存: " + filename);
                } catch (IOException e) {
                    System.err.println("[错误反馈] 保存错误报告失败: " + e.getMessage());
                }

                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("message", "错误报告已保存到本地（工具错误已由系统自动记录到知识库）");
                return result;
            }
            case "_save_tip" -> {
                if (!arguments.has("tool_name") || !arguments.has("tip")) {
                    JsonObject r = new JsonObject();
                    r.addProperty("error", "_save_tip 缺少必填参数 (tool_name, tip)");
                    return r;
                }
                String toolName = arguments.get("tool_name").getAsString();
                String tip = arguments.get("tip").getAsString();
                String context = arguments.has("context") ? arguments.get("context").getAsString() : "";
                String detail = context.isEmpty() ? tip : tip + "（适用场景: " + context + "）";
                knowledgeStore.saveTip(toolName, detail, "{}", tip);
                System.err.println("\n[知识库] 保存优化技巧: " + toolName + " → " + tip);
                JsonObject r = new JsonObject();
                r.addProperty("success", true);
                r.addProperty("message", "优化技巧已保存到知识库，下次启动时自动加载");
                return r;
            }
            default -> {
                JsonObject result = new JsonObject();
                result.addProperty("error", "未知的本地工具: " + name);
                return result;
            }
        }
    }

    private static int roundtripCounter = 0;

    /**
     * 将 LLM 请求和响应写入调试日志文件 output/debug/output_context.md
     */
    private void writeDebugLog(String requestBody, String responseBody) {
        roundtripCounter++;
        try {
            Path logPath = Path.of("output", "debug", "output_context.md");
            FileWriter fw = new FileWriter(logPath.toFile(), StandardCharsets.UTF_8, true);
            PrintWriter writer = new PrintWriter(fw);
            writer.println("## Roundtrip " + roundtripCounter + " — " + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println();
            writer.println("### 请求");
            writer.println("```json");
            writer.println(requestBody);
            writer.println("```");
            writer.println();
            writer.println("### 响应");
            writer.println("```json");
            writer.println(responseBody);
            writer.println("```");
            writer.println();
            writer.println("---");
            writer.println();
            writer.close();
        } catch (IOException e) {
            // 静默失败，不影响正常流程
        }
    }

    /**
     * 从 MCP 工具结果中提取内容并以结构化文本（类 YAML）格式返回，
     * 避免嵌套 JSON 的双重转义，同时更利于大模型阅读。
     * MCP 结果格式: { content: [{ type: "text", text: "..." }], isError: bool }
     */
    private static String extractToolResult(JsonObject toolResult) {
        if (toolResult == null) return "(空)";

        boolean isError = toolResult.has("isError") && toolResult.get("isError").getAsBoolean();

        if (toolResult.has("content") && toolResult.get("content").isJsonArray()) {
            JsonArray content = toolResult.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : content) {
                JsonObject item = el.getAsJsonObject();
                String type = item.has("type") ? item.get("type").getAsString() : "";
                if ("text".equals(type) && item.has("text")) {
                    String text = item.get("text").getAsString();
                    // 尝试解析为 JSON 并格式化
                    try {
                        JsonElement parsed = JsonParser.parseString(text);
                        if (parsed.isJsonArray() || parsed.isJsonObject()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(jsonToText(parsed, 0));
                        } else {
                            // 已是纯文本（如指令输出），直接使用
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(text);
                        }
                    } catch (JsonParseException e) {
                        // 非 JSON 纯文本，直接使用
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            if (sb.length() > 0) {
                if (isError) return "错误:\n" + sb;
                return sb.toString();
            }
        }

        return toolResult.toString();
    }

    /** 将 JSON 元素递归转换为结构化文本 */
    private static String jsonToText(JsonElement element, int indent) {
        if (element == null || element.isJsonNull()) return "~";

        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) return p.getAsNumber().toString();
            if (p.isBoolean()) return String.valueOf(p.getAsBoolean());
            return p.toString();
        }

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.size() == 0) return "(空)";
            String pad = "  ".repeat(indent);
            StringBuilder sb = new StringBuilder();
            for (var entry : obj.entrySet()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(pad).append(entry.getKey()).append(": ");
                JsonElement val = entry.getValue();
                if (val.isJsonObject() || val.isJsonArray()) {
                    sb.append("\n").append(jsonToText(val, indent + 1));
                } else {
                    sb.append(jsonToText(val, 0));
                }
            }
            return sb.toString();
        }

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.size() == 0) return "(空)";
            String pad = indent > 0 ? "  ".repeat(indent) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (sb.length() > 0) sb.append("\n");
                JsonElement item = arr.get(i);
                if (item.isJsonObject() || item.isJsonArray()) {
                    sb.append(pad).append("- ");
                    String inner = jsonToText(item, indent + 1);
                    // 将第一行与 "- " 放在同一行，其余行缩进对齐
                    String[] lines = inner.split("\n", 2);
                    sb.append(lines[0]);
                    if (lines.length > 1) {
                        sb.append("\n").append(lines[1]);
                    }
                } else {
                    sb.append(pad).append("- ").append(jsonToText(item, 0));
                }
            }
            return sb.toString();
        }

        return element.toString();
    }

    private void showToolCall(String name, JsonObject arguments) {
        System.out.println("\n[工具] " + name + "(" + arguments + ") → ...");
    }
}
