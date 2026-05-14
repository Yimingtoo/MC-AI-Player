package com.yiming.mcp_host.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiming.mcp_host.llm.LLMBridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * s06: 上下文压缩管理器。
 * 微压缩清理旧的 tool_result；自动压缩在超阈值时用 LLM 摘要替换对话。
 */
public class CompressionManager {

    private static final int TOKEN_THRESHOLD = 100000;
    private static final Path TRANSCRIPT_DIR = Path.of(".transcripts");
    private static final Gson GSON = new Gson();

    /**
     * 微压缩：当 role="tool" 的消息数超过 3 时，清除较早的消息内容。
     * Agent 的 addToolResult() 生成 {role:"tool", content:"..."} 格式。
     */
    public void microcompact(List<JsonObject> messages) {
        List<JsonObject> toolResults = new ArrayList<>();
        for (JsonObject msg : messages) {
            if ("tool".equals(getString(msg, "role"))
                    && msg.has("content") && msg.get("content").isJsonPrimitive()) {
                String content = msg.get("content").getAsString();
                if (content.length() > 100) {
                    toolResults.add(msg);
                }
            }
        }

        if (toolResults.size() <= 3) return;

        for (int i = 0; i < toolResults.size() - 3; i++) {
            toolResults.get(i).addProperty("content", "[cleared]");
        }
    }

    /**
     * 粗略估计 token 数（字符数 / 4）。
     */
    public int estimateTokens(List<JsonObject> messages) {
        return GSON.toJson(messages).length() / 4;
    }

    /**
     * 自动压缩：将完整对话归档，然后由 LLM 生成摘要替换对话内容。
     */
    public List<JsonObject> autoCompact(List<JsonObject> messages, LLMBridge llm) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            String timestamp = Instant.now().toString().replace(":", "-");
            Path transcriptPath = TRANSCRIPT_DIR.resolve("transcript_" + timestamp + ".jsonl");
            Files.writeString(transcriptPath, GSON.toJson(messages));

            String convText = GSON.toJson(messages);
            int start = Math.max(0, convText.length() - 80000);
            String tail = convText.substring(start);

            // 为摘要任务提供 system prompt 引导
            List<JsonObject> summaryMessages = new ArrayList<>();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "You are a conversation summarizer. "
                    + "Summarize the key progress, completed actions, and pending tasks "
                    + "so the assistant can continue seamlessly. Be concise.");
            summaryMessages.add(system);
            summaryMessages.add(createUserMessage("Summarize for continuity:\n" + tail));

            JsonObject summaryResponse = llm.send(summaryMessages, new JsonArray());

            String summary = "";
            if (summaryResponse.has("message")) {
                JsonObject msg = summaryResponse.getAsJsonObject("message");
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    summary = msg.get("content").getAsString();
                }
            }

            List<JsonObject> compressed = new ArrayList<>();
            compressed.add(createUserMessage(
                    "[Compressed. Transcript: " + transcriptPath.toAbsolutePath() + "]\n" + summary));

            System.out.println("[auto-compact] 压缩完成: " + transcriptPath);
            return compressed;

        } catch (Exception e) {
            System.err.println("[auto-compact] 压缩失败: " + e.getMessage());
            return messages;
        }
    }

    public int getThreshold() { return TOKEN_THRESHOLD; }

    private static JsonObject createUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", content);
        return msg;
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
}
