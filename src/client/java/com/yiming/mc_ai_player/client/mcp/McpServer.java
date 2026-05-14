package com.yiming.mc_ai_player.client.mcp;

import com.google.gson.*;
import com.yiming.mc_ai_player.client.executor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class McpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    private final McpProtocolHandler handler;
    private final PrintStream mcpOut;

    private Thread thread;
    private volatile boolean running = false;

    public McpServer(
        PrintStream mcpOut,
        PlayerActionExecutor playerAction,
        WorldQueryExecutor worldQuery,
        BlockActionExecutor blockAction,
        CommandActionExecutor commandAction,
        ScanRegionExecutor scanRegion,
        MonitorRegionExecutor monitorRegion
    ) {
        this.mcpOut = mcpOut;
        this.handler = new McpProtocolHandler(
            this::writeMessage,
            playerAction, worldQuery, blockAction, commandAction, scanRegion, monitorRegion
        );
    }

    // ---- Lifecycle ----

    public synchronized void start() {
        if (running) {
            LOGGER.warn("MCP server already running");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "mc-ai-mcp");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("MCP server started (stdio)");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        LOGGER.info("MCP server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    // ---- Main Loop ----

    private void loop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    handler.handleMessage(message);
                } catch (JsonSyntaxException e) {
                    sendError(null, -32700, "Parse error: " + e.getMessage());
                } catch (Exception e) {
                    LOGGER.error("Error handling MCP message", e);
                    sendError(null, -32603, "Internal error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.error("MCP read loop error", e);
            }
        } finally {
            running = false;
        }
    }

    // ---- JSON-RPC Output ----

    private void sendError(JsonElement id, int code, String msg) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.add("id", id != null ? id : JsonNull.INSTANCE);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", msg);
        message.add("error", error);
        writeMessage(message);
    }

    private void writeMessage(JsonObject message) {
        String json = new Gson().toJson(message);
        mcpOut.print(json);
        mcpOut.print("\n");
        mcpOut.flush();
        if (mcpOut.checkError()) {
            LOGGER.error("MCP output stream error detected, stopping server");
            stop();
        }
    }
}
