package com.yiming.mcp_host.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ToolConverter {

    /**
     * 将 MCP 工具定义列表转换为 OpenAI tool 格式（Deepseek 兼容）。
     * <pre>
     * MCP:   { "name": "get_block", "description": "...", "inputSchema": {...} }
     * OpenAI: { "type": "function", "function": { "name": "...", "description": "...", "parameters": {...} } }
     * </pre>
     */
    public static JsonArray toOpenAITools(JsonArray mcpTools) {
        JsonArray openaiTools = new JsonArray();
        for (JsonElement elem : mcpTools) {
            JsonObject mcpTool = elem.getAsJsonObject();
            if (!mcpTool.has("name") || mcpTool.get("name").isJsonNull()) {
                continue;
            }
            JsonObject function = new JsonObject();
            function.addProperty("name", mcpTool.get("name").getAsString());
            if (mcpTool.has("description") && !mcpTool.get("description").isJsonNull()) {
                function.addProperty("description", mcpTool.get("description").getAsString());
            }

            // inputSchema 直接作为 parameters 使用（JSON Schema 兼容）
            if (mcpTool.has("inputSchema") && !mcpTool.get("inputSchema").isJsonNull()) {
                function.add("parameters", mcpTool.get("inputSchema"));
            } else {
                JsonObject defaultParams = new JsonObject();
                defaultParams.addProperty("type", "object");
                defaultParams.add("properties", new JsonObject());
                defaultParams.add("required", new JsonArray());
                function.add("parameters", defaultParams);
            }

            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            openaiTools.add(tool);
        }
        return openaiTools;
    }
}
