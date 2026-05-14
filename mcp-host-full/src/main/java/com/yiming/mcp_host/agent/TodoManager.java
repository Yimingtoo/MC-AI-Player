package com.yiming.mcp_host.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * s03: 待办清单管理器。
 * 轻量级 checklist，LLM 通过 TodoWrite 工具更新。
 * 最多 20 项，仅允许一个 in_progress。
 */
public class TodoManager {

    static class TodoItem {
        String content;
        String status;      // "pending" | "in_progress" | "completed"
        String activeForm;
    }

    private final List<TodoItem> items = new ArrayList<>();
    private int roundsWithoutUpdate = 0;

    /**
     * 全量替换待办列表。校验后返回格式化文本。
     */
    public synchronized String update(JsonArray rawItems) {
        List<TodoItem> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < rawItems.size(); i++) {
            JsonObject obj = rawItems.get(i).getAsJsonObject();
            String content = getString(obj, "content");
            String status = getString(obj, "status", "pending").toLowerCase();
            String activeForm = getString(obj, "activeForm");

            if (content.isEmpty()) {
                return "Error: Item " + i + ": content required";
            }
            if (!"pending".equals(status) && !"in_progress".equals(status) && !"completed".equals(status)) {
                return "Error: Item " + i + ": invalid status '" + status + "'";
            }
            if (activeForm.isEmpty()) {
                return "Error: Item " + i + ": activeForm required";
            }

            if (validated.size() >= 20) {
                return "Error: Max 20 todos";
            }
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }

            TodoItem item = new TodoItem();
            item.content = content;
            item.status = status;
            item.activeForm = activeForm;
            validated.add(item);
        }

        if (inProgressCount > 1) {
            return "Error: Only one in_progress allowed";
        }

        this.items.clear();
        this.items.addAll(validated);
        this.roundsWithoutUpdate = 0;
        return render();
    }

    /**
     * 格式化当前待办列表。
     */
    public synchronized String render() {
        if (items.isEmpty()) return "No todos.";
        StringBuilder sb = new StringBuilder();
        int done = 0;
        for (TodoItem item : items) {
            String marker;
            switch (item.status) {
                case "completed" -> { marker = "[x]"; done++; }
                case "in_progress" -> marker = "[>]";
                default -> marker = "[ ]";
            }
            sb.append(marker).append(" ").append(item.content);
            if ("in_progress".equals(item.status)) {
                sb.append(" <- ").append(item.activeForm);
            }
            sb.append("\n");
        }
        sb.append("(").append(done).append("/").append(items.size()).append(" completed)");
        return sb.toString();
    }

    public synchronized boolean hasOpenItems() {
        return items.stream().anyMatch(i -> !"completed".equals(i.status));
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String getString(JsonObject obj, String key, String def) {
        String v = getString(obj, key);
        return v.isEmpty() ? def : v;
    }
}
