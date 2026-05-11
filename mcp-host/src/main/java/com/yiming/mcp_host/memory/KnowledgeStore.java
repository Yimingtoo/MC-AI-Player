package com.yiming.mcp_host.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 跨对话工具知识库。
 * 将 LLM 在工具调用中学习到的经验教训持久化到文件，
 * 并在下次启动时注入 system prompt，实现跨会话学习。
 */
public class KnowledgeStore {

    private static final Path STORE_PATH = Path.of("run", "ai-player", "knowledge.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<KnowledgeEntry> entries;

    public KnowledgeStore() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
        } catch (IOException e) {
            System.err.println("[KnowledgeStore] 创建目录失败: " + e.getMessage());
        }
        this.entries = load();
    }

    // ---- Public API ----

    /**
     * 记录一条工具调用经验。
     *
     * @param tool      工具名称
     * @param summary   经验总结
     * @param argsJson  本次调用的参数（JSON 字符串）
     * @param result    调用结果
     * @param category  类别: "error" 错误, "tip" 技巧, "optimization" 优化
     */
    public synchronized void record(String tool, String summary, String argsJson, String result, String category) {
        // 去重
        for (KnowledgeEntry existing : entries) {
            if (existing.matches(tool, summary)) {
                existing.count++;
                existing.lastSeen = System.currentTimeMillis();
                if (!"error".equals(category)) existing.confirmed = true;
                save();
                return;
            }
        }

        KnowledgeEntry entry = new KnowledgeEntry();
        entry.id = UUID.randomUUID().toString().substring(0, 8);
        entry.tool = tool;
        entry.summary = summary;
        entry.argsPattern = summarizeArgs(argsJson);
        entry.result = result;
        entry.category = category;
        entry.count = 1;
        entry.confirmed = !"error".equals(category);
        entry.firstSeen = System.currentTimeMillis();
        entry.lastSeen = entry.firstSeen;
        entries.add(entry);
        save();
    }

    /**
     * 记录一条优化技巧（快捷方法）。
     */
    public synchronized void saveTip(String tool, String tip, String argsJson, String detail) {
        record(tool, tip, argsJson, detail, "tip");
    }

    /**
     * 获取所有知识条目，格式化为 LLM 易读的纯文本。
     */
    public synchronized String getSummary() {
        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 过往经验（跨对话记忆） ===");

        boolean hasTips = false;
        boolean hasConfirmed = false;
        boolean hasErrors = false;

        for (KnowledgeEntry e : entries) {
            if ("tip".equals(e.category) || "optimization".equals(e.category)) {
                if (!hasTips) { sb.append("\n[优化技巧]"); hasTips = true; }
                sb.append("\n- 工具 ").append(e.tool).append(": ").append(e.summary);
            }
        }

        for (KnowledgeEntry e : entries) {
            if (!"error".equals(e.category) && !"tip".equals(e.category) && !"optimization".equals(e.category)) {
                if (e.confirmed) {
                    if (!hasConfirmed) { sb.append("\n[已验证的经验]"); hasConfirmed = true; }
                    sb.append("\n- 工具 ").append(e.tool).append(": ").append(e.summary);
                }
            }
        }

        for (KnowledgeEntry e : entries) {
            if ("error".equals(e.category) || (!e.confirmed && !"tip".equals(e.category) && !"optimization".equals(e.category))) {
                if (!hasErrors) { sb.append("\n[曾遇到的错误]"); hasErrors = true; }
                sb.append("\n- 工具 ").append(e.tool).append(": ").append(e.summary);
            }
        }

        sb.append("\n参考以上经验，避免重复错误并应用已知优化技巧。");
        return sb.toString();
    }

    /**
     * 获取特定工具的知识摘要。
     */
    public synchronized String getToolSummary(String tool) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            if (e.tool.equals(tool)) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(e.summary);
            }
        }
        return sb.toString();
    }

    // ---- Persistence ----

    @SuppressWarnings("unchecked")
    private List<KnowledgeEntry> load() {
        try {
            if (Files.exists(STORE_PATH)) {
                String content = Files.readString(STORE_PATH);
                JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
                List<KnowledgeEntry> list = new ArrayList<>();
                for (var el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    KnowledgeEntry e = new KnowledgeEntry();
                    e.id = getString(obj, "id");
                    e.tool = getString(obj, "tool");
                    e.summary = getString(obj, "summary");
                    e.argsPattern = getString(obj, "argsPattern");
                    e.result = getString(obj, "result");
                    e.category = getString(obj, "category");
                    if (e.category.isEmpty()) e.category = "error"; // 兼容旧数据
                    e.confirmed = getBool(obj, "confirmed");
                    e.count = getInt(obj, "count");
                    e.firstSeen = getLong(obj, "firstSeen");
                    e.lastSeen = getLong(obj, "lastSeen");
                    list.add(e);
                }
                return list;
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeStore] 加载知识库失败: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (KnowledgeEntry e : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", e.id);
                obj.addProperty("tool", e.tool);
                obj.addProperty("summary", e.summary);
                obj.addProperty("argsPattern", e.argsPattern);
                obj.addProperty("result", e.result);
                obj.addProperty("category", e.category);
                obj.addProperty("confirmed", e.confirmed);
                obj.addProperty("count", e.count);
                obj.addProperty("firstSeen", e.firstSeen);
                obj.addProperty("lastSeen", e.lastSeen);
                arr.add(obj);
            }
            Files.writeString(STORE_PATH, GSON.toJson(arr));
        } catch (IOException e) {
            System.err.println("[KnowledgeStore] 保存知识库失败: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    private static String summarizeArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank() || argsJson.equals("{}")) return "";
        try {
            JsonObject obj = JsonParser.parseString(argsJson).getAsJsonObject();
            return obj.keySet().toString();
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 80) + "..." : argsJson;
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static boolean getBool(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).getAsBoolean();
    }

    private static int getInt(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsInt() : 0;
    }

    private static long getLong(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsLong() : 0;
    }

    // ---- Data Model ----

    static class KnowledgeEntry {
        String id;
        String tool;
        String summary;
        String argsPattern;
        String result;
        String category = "error"; // "error", "tip", "optimization"
        boolean confirmed;
        int count;
        long firstSeen;
        long lastSeen;

        boolean matches(String tool, String summary) {
            return this.tool.equals(tool) && this.summary.equals(summary);
        }
    }
}
