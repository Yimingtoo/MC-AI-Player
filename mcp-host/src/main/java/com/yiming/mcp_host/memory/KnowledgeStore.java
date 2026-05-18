package com.yiming.mcp_host.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨对话工具知识库。
 * 将 LLM 在工具调用中学习到的经验教训持久化到文件，
 * 并在下次启动时注入 system prompt，实现跨会话学习。
 */
public class KnowledgeStore {

    private static final Path STORE_PATH = Path.of("run", "ai-player", "knowledge.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<KnowledgeEntry> entries;
    private TfIdfIndex tfidfIndex;
    private boolean indexDirty;

    public KnowledgeStore() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
        } catch (IOException e) {
            System.err.println("[KnowledgeStore] 创建目录失败: " + e.getMessage());
        }
        this.entries = load();
        this.indexDirty = true;
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
        record(tool, extractTitle(summary), summary, argsJson, result, category);
    }

    /**
     * 记录一条工具调用经验（含指定标题）。
     */
    public synchronized void record(String tool, String title, String summary, String argsJson, String result, String category) {
        // 去重
        for (KnowledgeEntry existing : entries) {
            if (existing.matches(tool, summary)) {
                existing.count++;
                existing.lastSeen = System.currentTimeMillis();
                if (!"error".equals(category)) existing.confirmed = true;
                save();
                indexDirty = true;
                return;
            }
        }

        KnowledgeEntry entry = new KnowledgeEntry();
        entry.id = UUID.randomUUID().toString().substring(0, 8);
        entry.tool = tool;
        entry.title = title;
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
        indexDirty = true;
    }

    /**
     * 记录一条优化技巧（快捷方法）。
     */
    public synchronized void saveTip(String tool, String tip, String argsJson, String detail) {
        record(tool, tip, argsJson, detail, "tip");
    }

    /**
     * 获取知识标题索引（渐进式披露的"摘要推送"部分）。
     * 只返回每个条目的标题，按工具分组，不包含完整正文。
     */
    public synchronized String getIndex() {
        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 过往经验（跨对话记忆） ===");

        // 按工具分组输出标题
        Map<String, List<KnowledgeEntry>> byTool = entries.stream()
                .collect(Collectors.groupingBy(e -> e.tool, LinkedHashMap::new, Collectors.toList()));

        for (var group : byTool.entrySet()) {
            sb.append("\n[").append(group.getKey()).append("]");
            for (KnowledgeEntry e : group.getValue()) {
                String label = e.title.isEmpty() ? e.summary.substring(0, Math.min(40, e.summary.length())) : e.title;
                sb.append("\n- ").append(label);
            }
        }

        sb.append("\n\n你可以使用 _search_knowledge(query) 工具搜索相关知识详情。");
        return sb.toString();
    }

    /**
     * 搜索与查询文本最相关的知识条目。
     * 返回格式化文本，包含标题 + 完整正文，按 TF-IDF 相似度排序。
     */
    public synchronized String search(String query) {
        if (query == null || query.isBlank() || entries.isEmpty()) return "";
        buildIndexIfNeeded();

        List<ScoredEntry> results = tfidfIndex.search(query, entries);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 条相关知识：");
        for (int i = 0; i < results.size(); i++) {
            KnowledgeEntry e = results.get(i).entry;
            String title = e.title.isEmpty() ? e.summary.substring(0, Math.min(40, e.summary.length())) : e.title;
            sb.append("\n[").append(i + 1).append("] ").append(title);
            sb.append("\n   关联工具: ").append(e.tool);
            sb.append(" | 匹配度: ").append(String.format("%.2f", results.get(i).score));
            // summary 可能很长，只返回前 300 字，LLM 如果需要更详细可以再搜
            String detail = e.summary.length() > 300 ? e.summary.substring(0, 300) + "…" : e.summary;
            sb.append("\n   ").append(detail.replace("\n", "\n   "));
        }
        return sb.toString();
    }

    /**
     * 获取特定工具的知识摘要（保留向下兼容）。
     */
    public synchronized String getToolSummary(String tool) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            if (e.tool.equals(tool)) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(e.title.isEmpty() ? e.summary : e.title);
            }
        }
        return sb.toString();
    }

    // ---- TF-IDF 索引 ----

    private void buildIndexIfNeeded() {
        if (tfidfIndex == null || indexDirty) {
            tfidfIndex = new TfIdfIndex(entries);
            indexDirty = false;
        }
    }

    /**
     * 将混合中英文文本分词：
     * - 英文字母/数字序列 → 整个词作为 token
     * - CJK 字符序列 → 生成相邻字符 bigram
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = text.toLowerCase().strip();
        List<String> tokens = new ArrayList<>();
        StringBuilder asciiBuf = new StringBuilder();
        StringBuilder cjkBuf = new StringBuilder();

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '一' && c <= '鿿') {
                flushAscii(tokens, asciiBuf);
                cjkBuf.append(c);
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                flushCjk(tokens, cjkBuf);
                asciiBuf.append(c);
            } else {
                flushAscii(tokens, asciiBuf);
                flushCjk(tokens, cjkBuf);
            }
        }
        flushAscii(tokens, asciiBuf);
        flushCjk(tokens, cjkBuf);
        return tokens;
    }

    private static void flushAscii(List<String> tokens, StringBuilder buf) {
        if (buf.length() > 0) {
            tokens.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjk(List<String> tokens, StringBuilder buf) {
        if (buf.length() == 0) return;
        String s = buf.toString();
        if (s.length() == 1) {
            tokens.add(s);
        } else {
            for (int i = 0; i < s.length() - 1; i++) {
                tokens.add(s.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    static class TfIdfIndex {
        // docId → term → frequency in doc
        final Map<String, Map<String, Integer>> termFreqs = new HashMap<>();
        // docId → total terms
        final Map<String, Integer> docLengths = new HashMap<>();
        // term → number of docs containing it
        final Map<String, Integer> docFreqs = new HashMap<>();
        int totalDocs;

        TfIdfIndex(List<KnowledgeEntry> entries) {
            totalDocs = entries.size();
            // 收集词频
            for (KnowledgeEntry e : entries) {
                String docId = e.id;
                String text = (e.title + " " + e.summary).toLowerCase();
                List<String> terms = tokenize(text);
                Map<String, Integer> tf = new HashMap<>();
                for (String term : terms) {
                    tf.merge(term, 1, Integer::sum);
                }
                termFreqs.put(docId, tf);
                docLengths.put(docId, terms.size());
            }
            // 计算文档频率
            for (Map<String, Integer> tf : termFreqs.values()) {
                for (String term : tf.keySet()) {
                    docFreqs.merge(term, 1, Integer::sum);
                }
            }
        }

        List<ScoredEntry> search(String query, List<KnowledgeEntry> allEntries) {
            List<String> queryTerms = tokenize(query);
            if (queryTerms.isEmpty()) return List.of();

            // build id → entry map
            Map<String, KnowledgeEntry> byId = new HashMap<>();
            for (KnowledgeEntry e : allEntries) byId.put(e.id, e);

            // 打分
            List<ScoredEntry> scored = new ArrayList<>();
            for (KnowledgeEntry e : allEntries) {
                Map<String, Integer> tf = termFreqs.get(e.id);
                int docLen = docLengths.getOrDefault(e.id, 0);
                if (tf == null || docLen == 0) continue;

                double score = 0;
                for (String term : queryTerms) {
                    Integer raw = tf.get(term);
                    if (raw == null) continue;
                    double termFreq = (double) raw / docLen;
                    double invDocFreq = Math.log((double) totalDocs / (1 + docFreqs.getOrDefault(term, 0)));
                    score += termFreq * invDocFreq;
                }
                if (score > 0) {
                    scored.add(new ScoredEntry(e, score));
                }
            }

            scored.sort((a, b) -> Double.compare(b.score, a.score));

            // 动态阈值：取最高分的 40% 作为下限
            if (scored.isEmpty()) return List.of();
            double threshold = scored.get(0).score * 0.4;
            List<ScoredEntry> result = new ArrayList<>();
            for (ScoredEntry se : scored) {
                if (se.score < threshold) break;
                result.add(se);
                if (result.size() >= 5) break;
            }
            return result;
        }
    }

    static class ScoredEntry {
        final KnowledgeEntry entry;
        final double score;

        ScoredEntry(KnowledgeEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }

    // ---- Backward-compatible alias ----

    /**
     * @deprecated 改用 getIndex()。保留此方法避免外部代码直接编译失败。
     */
    @Deprecated
    public synchronized String getSummary() {
        return getIndex();
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
                    e.title = getString(obj, "title");
                    if (e.title.isEmpty()) {
                        e.title = extractTitle(getString(obj, "summary"));
                    }
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
                obj.addProperty("title", e.title);
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
        String title;
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

    /**
     * 从概要文本中自动提取标题：取首句句号/分号前的内容，不超过 50 字。
     */
    static String extractTitle(String text) {
        if (text == null || text.isBlank()) return "";
        String t = text.strip();
        for (String sep : new String[]{"。", "；", ";", "——", "\n"}) {
            int idx = t.indexOf(sep);
            if (idx > 0 && idx < 60) {
                return t.substring(0, idx).strip();
            }
        }
        return t.length() > 50 ? t.substring(0, 50).strip() + "…" : t;
    }
}
