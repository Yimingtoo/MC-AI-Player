package com.yiming.mcp_host.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * MCP 客户端接口，兼容 stdio (McpClient) 和 SSE (McpSseClient) 传输层。
 */
public interface McpToolExecutor {
    JsonObject callTool(String name, JsonObject arguments) throws Exception;
    JsonArray listTools() throws Exception;
    boolean isRunning();
    void stop();
}
