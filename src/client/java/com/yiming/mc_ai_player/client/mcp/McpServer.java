package com.yiming.mc_ai_player.client.mcp;

import com.google.gson.*;
import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.action.*;
import com.yiming.mc_ai_player.client.executor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class McpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");
    private static final Gson GSON = ActionResponse.GSON;
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final PrintStream mcpOut;
    private final Map<String, Function<JsonObject, ActionResponse>> toolHandlers = new LinkedHashMap<>();
    private final List<JsonObject> toolDefinitions;

    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean initialized = false;

    public McpServer(
        PrintStream mcpOut,
        PlayerActionExecutor playerAction,
        WorldQueryExecutor worldQuery,
        BlockActionExecutor blockAction,
        CommandActionExecutor commandAction
    ) {
        this.mcpOut = mcpOut;
        this.toolDefinitions = buildToolDefinitions();
        registerToolHandlers(playerAction, worldQuery, blockAction, commandAction);
    }

    // ---- Tool Definitions (JSON Schema) ----

    private List<JsonObject> buildToolDefinitions() {
        List<JsonObject> tools = new ArrayList<>();

        tools.add(buildTool("get_player_position", " Get current player position, rotation, health, and game mode"));
        tools.add(buildTool("move_player", "Move player to target coordinates",
            prop("position", obj(
                prop("x", num("X coordinate")),
                prop("y", num("Y coordinate")),
                prop("z", num("Z coordinate"))
            )),
            opt("yaw", num("Yaw rotation (degrees)"), 0),
            opt("pitch", num("Pitch rotation (degrees)"), 0),
            opt("relative", bool("Whether coordinates are relative to current position"), false),
            opt("dimension", str("Target dimension (e.g. minecraft:overworld)"))
        ));
        tools.add(buildTool("set_player_look", "Set player look direction",
            opt("yaw", num("Yaw rotation (degrees)"), 0),
            opt("pitch", num("Pitch rotation (degrees)"), 0)
        ));
        tools.add(buildTool("send_chat_message", "Send a chat message as the player",
            prop("message", str("Message to send"))));
        tools.add(buildTool("player_jump", "Make the player jump"));
        tools.add(buildTool("get_player_inventory", "Get player inventory contents"));
        tools.add(buildTool("get_block", "Get block info at coordinates",
            prop("x", num("X coordinate")),
            prop("y", num("Y coordinate")),
            prop("z", num("Z coordinate"))
        ));
        tools.add(buildTool("get_blocks", "Get block info for multiple positions (max 256)",
            prop("positions", arr(obj(
                prop("x", num("X coordinate")),
                prop("y", num("Y coordinate")),
                prop("z", num("Z coordinate"))
            )))));
        tools.add(buildTool("get_player_blocks", "Get non-air blocks around the player",
            opt("radius", num("Search radius"), 8)));
        tools.add(buildTool("get_biome", "Get biome at coordinates",
            prop("x", num("X coordinate")),
            prop("y", num("Y coordinate")),
            prop("z", num("Z coordinate"))
        ));
        tools.add(buildTool("get_game_time", "Get current game time"));
        tools.add(buildTool("get_entities", "Get entities near coordinates",
            prop("x", num("X coordinate")),
            prop("y", num("Y coordinate")),
            prop("z", num("Z coordinate")),
            opt("radius", num("Search radius"), 16)));
        tools.add(buildTool("set_block", "Set a block at a position",
            prop("position", obj(
                prop("x", num("X coordinate")),
                prop("y", num("Y coordinate")),
                prop("z", num("Z coordinate"))
            )),
            prop("blockId", str("Block ID (e.g. minecraft:stone)")),
            opt("blockState", obj("Block state properties (e.g. {facing: north})")),
            opt("dimension", str("Dimension (e.g. minecraft:overworld)"))
        ));
        tools.add(buildTool("fill_region", "Fill a region with blocks",
            prop("from", obj(
                prop("x", num("Start X")),
                prop("y", num("Start Y")),
                prop("z", num("Start Z"))
            )),
            prop("to", obj(
                prop("x", num("End X")),
                prop("y", num("End Y")),
                prop("z", num("End Z"))
            )),
            prop("blockId", str("Block ID (e.g. minecraft:stone)")),
            opt("replaceMode", str("'all' or 'keep_air'")),
            opt("dimension", str("Dimension (e.g. minecraft:overworld)"))
        ));
        tools.add(buildTool("replace_blocks", "Replace blocks of one type with another in a region",
            prop("from", obj(
                prop("x", num("Start X")),
                prop("y", num("Start Y")),
                prop("z", num("Start Z"))
            )),
            prop("to", obj(
                prop("x", num("End X")),
                prop("y", num("End Y")),
                prop("z", num("End Z"))
            )),
            prop("blockId", str("Replacement block ID")),
            prop("filterBlockId", str("Block ID to replace")),
            opt("dimension", str("Dimension (e.g. minecraft:overworld)"))
        ));
        tools.add(buildTool("execute_command", "Execute a Minecraft command",
            prop("command", str("Command to execute")),
            opt("asPlayer", bool("Execute as player (true) or console (false)"), true)
        ));

        return tools;
    }

    // ---- Tool Handler Registration ----

    private void registerToolHandlers(
        PlayerActionExecutor playerAction,
        WorldQueryExecutor worldQuery,
        BlockActionExecutor blockAction,
        CommandActionExecutor commandAction
    ) {
        toolHandlers.put("get_player_position", args -> playerAction.handleGetPosition());
        toolHandlers.put("move_player", args -> {
            MovePlayerRequest req = GSON.fromJson(args, MovePlayerRequest.class);
            return playerAction.handleMove(req);
        });
        toolHandlers.put("set_player_look", args -> {
            float yaw = getFloat(args, "yaw", 0);
            float pitch = getFloat(args, "pitch", 0);
            return playerAction.handleLook(yaw, pitch);
        });
        toolHandlers.put("send_chat_message", args -> {
            String message = getString(args, "message");
            if (message == null) return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "message is required");
            return playerAction.handleSendChat(message);
        });
        toolHandlers.put("player_jump", args -> playerAction.handleJump());
        toolHandlers.put("get_player_inventory", args -> playerAction.handleGetInventory());
        toolHandlers.put("get_block", args -> {
            int x = getInt(args, "x");
            int y = getInt(args, "y");
            int z = getInt(args, "z");
            return worldQuery.handleGetBlock(x, y, z);
        });
        toolHandlers.put("get_blocks", args -> {
            JsonArray arr = args.has("positions") ? args.getAsJsonArray("positions") : null;
            if (arr == null) return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "positions array is required");
            List<BlockPos> positions = new ArrayList<>();
            for (JsonElement el : arr) {
                positions.add(GSON.fromJson(el, BlockPos.class));
            }
            return worldQuery.handleGetBlocks(positions);
        });
        toolHandlers.put("get_player_blocks", args -> {
            int radius = getInt(args, "radius", 8);
            return worldQuery.handleGetPlayerBlocks(radius);
        });
        toolHandlers.put("get_biome", args -> {
            int x = getInt(args, "x");
            int y = getInt(args, "y");
            int z = getInt(args, "z");
            return worldQuery.handleGetBiome(x, y, z);
        });
        toolHandlers.put("get_game_time", args -> worldQuery.handleGetTime());
        toolHandlers.put("get_entities", args -> {
            double x = getDouble(args, "x");
            double y = getDouble(args, "y");
            double z = getDouble(args, "z");
            double radius = getDouble(args, "radius", 16);
            return worldQuery.handleGetEntities(x, y, z, radius);
        });
        toolHandlers.put("set_block", args -> {
            SetBlockRequest req = GSON.fromJson(args, SetBlockRequest.class);
            return blockAction.handleSetBlock(req);
        });
        toolHandlers.put("fill_region", args -> {
            FillRegionRequest req = GSON.fromJson(args, FillRegionRequest.class);
            return blockAction.handleFillRegion(req);
        });
        toolHandlers.put("replace_blocks", args -> {
            FillRegionRequest req = GSON.fromJson(args, FillRegionRequest.class);
            String filter = getString(args, "filterBlockId");
            return blockAction.handleReplaceBlocks(req, filter);
        });
        toolHandlers.put("execute_command", args -> {
            ExecuteCommandRequest req = GSON.fromJson(args, ExecuteCommandRequest.class);
            return commandAction.handleExecute(req);
        });
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
                    handleMessage(message);
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

    // ---- Message Routing ----

    private void handleMessage(JsonObject message) {
        JsonElement idEl = message.get("id");
        boolean isNotification = idEl == null || idEl.isJsonNull();
        String method = getString(message, "method");
        if (method == null) {
            if (!isNotification) sendError(idEl, -32600, "Invalid Request: missing method");
            return;
        }

        JsonObject params = message.has("params") && !message.get("params").isJsonNull()
            ? message.getAsJsonObject("params") : new JsonObject();

        try {
            switch (method) {
                case "initialize" -> handleInitialize(idEl, params);
                case "notifications/initialized" -> { initialized = true; }
                case "ping" -> sendResult(idEl, new JsonObject());
                case "tools/list" -> handleToolsList(idEl, params);
                case "tools/call" -> handleToolsCall(idEl, params);
                default -> {
                    if (!isNotification) sendError(idEl, -32601, "Method not found: " + method);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error handling method: {}", method, e);
            if (!isNotification) sendError(idEl, -32603, "Internal error: " + e.getMessage());
        }
    }

    private void handleInitialize(JsonElement id, JsonObject params) {
        String clientVersion = getString(params, "protocolVersion");
        if (clientVersion == null) {
            sendError(id, -32602, "Missing protocolVersion");
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        // Explicitly declare empty capabilities for resources and prompts
        capabilities.add("resources", new JsonObject());
        capabilities.add("prompts", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "mc-ai-player");
        serverInfo.addProperty("version", "1.0");
        result.add("serverInfo", serverInfo);

        sendResult(id, result);
    }

    private void handleToolsList(JsonElement id, JsonObject params) {
        if (!initialized) {
            sendError(id, -32000, "Server not initialized");
            return;
        }
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        for (JsonObject def : toolDefinitions) {
            tools.add(def);
        }
        result.add("tools", tools);
        sendResult(id, result);
    }

    private void handleToolsCall(JsonElement id, JsonObject params) {
        if (!initialized) {
            sendError(id, -32000, "Server not initialized");
            return;
        }

        String name = getString(params, "name");
        if (name == null) {
            sendError(id, -32602, "Missing tool name");
            return;
        }

        Function<JsonObject, ActionResponse> handler = toolHandlers.get(name);
        if (handler == null) {
            sendError(id, -32602, "Unknown tool: " + name);
            return;
        }

        JsonObject arguments = params.has("arguments") && !params.get("arguments").isJsonNull()
            ? params.getAsJsonObject("arguments") : new JsonObject();

        try {
            ActionResponse response = handler.apply(arguments);
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            if (response.success) {
                textContent.addProperty("text", response.data != null ? GSON.toJson(response.data) : "{}");
            } else {
                Map<String, String> errorMap = new LinkedHashMap<>();
                errorMap.put("error", response.error != null ? response.error.code : "unknown");
                errorMap.put("message", response.error != null ? response.error.message : "Unknown error");
                textContent.addProperty("text", GSON.toJson(errorMap));
            }
            content.add(textContent);
            result.add("content", content);
            result.addProperty("isError", !response.success);
            sendResult(id, result);
        } catch (Exception e) {
            LOGGER.error("Tool execution failed: {}", name, e);
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            Map<String, String> errorMap = new LinkedHashMap<>();
            errorMap.put("error", "internal_error");
            errorMap.put("message", e.getMessage());
            textContent.addProperty("type", "text");
            textContent.addProperty("text", GSON.toJson(errorMap));
            content.add(textContent);
            result.add("content", content);
            result.addProperty("isError", true);
            sendResult(id, result);
        }
    }

    // ---- JSON-RPC Output ----

    private void sendResult(JsonElement id, JsonObject result) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.add("id", id != null ? id : JsonNull.INSTANCE);
        message.add("result", result);
        writeMessage(message);
    }

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
        String json = GSON.toJson(message);
        mcpOut.print(json);
        mcpOut.print("\n");
        mcpOut.flush();
        if (mcpOut.checkError()) {
            LOGGER.error("MCP output stream error detected, stopping server");
            stop();
        }
    }

    // ---- JSON Helper Methods ----

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int getInt(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsInt() : 0;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static double getDouble(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsDouble() : 0;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        return obj.has(key) ? obj.get(key).getAsDouble() : def;
    }

    private static float getFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    // ---- Tool Schema Builder ----

    private static JsonObject buildTool(String name, String description) {
        return buildTool(name, description, new ToolProp[0]);
    }

    private static JsonObject buildTool(String name, String description, ToolProp... props) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        if (props != null) {
            for (ToolProp prop : props) {
                properties.add(prop.name, prop.schema);
                if (prop.required) {
                    required.add(prop.name);
                }
            }
        }

        inputSchema.add("properties", properties);
        if (required.size() > 0) {
            inputSchema.add("required", required);
        }
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private record ToolProp(String name, JsonObject schema, boolean required) {}

    private static ToolProp prop(String name, JsonObject schema) {
        return new ToolProp(name, schema, true);
    }

    private static ToolProp opt(String name, JsonObject schema) {
        return new ToolProp(name, schema, false);
    }

    private static ToolProp opt(String name, JsonObject schema, Object defaultValue) {
        JsonObject s = new JsonObject();
        s.addProperty("type", schema.get("type").getAsString());
        if (schema.has("description")) s.addProperty("description", schema.get("description").getAsString());
        if (defaultValue != null) {
            if (defaultValue instanceof Number n) s.addProperty("default", n);
            else if (defaultValue instanceof Boolean b) s.addProperty("default", b);
            else if (defaultValue instanceof String sv) s.addProperty("default", sv);
        }
        return new ToolProp(name, s, false);
    }

    private static JsonObject num(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "number");
        s.addProperty("description", description);
        return s;
    }

    private static JsonObject str(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("description", description);
        return s;
    }

    private static JsonObject bool(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "boolean");
        s.addProperty("description", description);
        return s;
    }

    private static JsonObject obj(ToolProp... props) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonArray required = new JsonArray();
        if (props != null) {
            for (ToolProp prop : props) {
                p.add(prop.name, prop.schema);
                if (prop.required) required.add(prop.name);
            }
        }
        s.add("properties", p);
        if (required.size() > 0) s.add("required", required);
        return s;
    }

    private static JsonObject obj(String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        if (description != null) s.addProperty("description", description);
        s.add("properties", new JsonObject());
        return s;
    }

    private static JsonObject arr(JsonObject items) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "array");
        s.add("items", items);
        return s;
    }
}
