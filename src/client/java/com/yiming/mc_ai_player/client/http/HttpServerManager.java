package com.yiming.mc_ai_player.client.http;

import com.sun.net.httpserver.HttpServer;
import com.yiming.mc_ai_player.api.ApiConstants;
import com.yiming.mc_ai_player.api.model.ActionResponse;
import com.yiming.mc_ai_player.client.executor.*;
import com.yiming.mc_ai_player.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class HttpServerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    private HttpServer server;
    private final JsonRouter router = new JsonRouter();
    private final ModConfig config;
    private boolean running = false;

    private WorldQueryExecutor worldQuery;
    private BlockActionExecutor blockAction;
    private PlayerActionExecutor playerAction;
    private CommandActionExecutor commandAction;

    public HttpServerManager(ModConfig config) {
        this.config = config;
        initExecutors();
        registerRoutes();
    }

    private void initExecutors() {
        worldQuery = new WorldQueryExecutor(config);
        blockAction = new BlockActionExecutor(config);
        playerAction = new PlayerActionExecutor(config);
        commandAction = new CommandActionExecutor(config);
    }

    private void registerRoutes() {
        // Health & capabilities
        router.get(ApiConstants.HEALTH, (exchange, params) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("mod_version", "1.0-SNAPSHOT");
            data.put("minecraft_version", "1.21.11");
            return ActionResponse.ok(data);
        });

        router.get(ApiConstants.CAPABILITIES, (exchange, params) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("endpoints", new String[]{
                ApiConstants.HEALTH, ApiConstants.CAPABILITIES,
                ApiConstants.PLAYER_POSITION, ApiConstants.PLAYER_MOVE,
                ApiConstants.PLAYER_LOOK, ApiConstants.PLAYER_SEND_CHAT,
                ApiConstants.PLAYER_JUMP, ApiConstants.PLAYER_INVENTORY,
                ApiConstants.WORLD_BLOCK, ApiConstants.WORLD_BLOCKS,
                ApiConstants.WORLD_PLAYER_BLOCKS, ApiConstants.WORLD_BIOME,
                ApiConstants.WORLD_TIME, ApiConstants.WORLD_ENTITIES,
                ApiConstants.BLOCKS_SET, ApiConstants.BLOCKS_FILL,
                ApiConstants.BLOCKS_REPLACE, ApiConstants.COMMAND_EXECUTE
            });
            return ActionResponse.ok(data);
        });

        // Player routes
        router.get(ApiConstants.PLAYER_POSITION, playerAction::handleGetPosition);
        router.post(ApiConstants.PLAYER_MOVE, playerAction::handleMove);
        router.post(ApiConstants.PLAYER_LOOK, playerAction::handleLook);
        router.post(ApiConstants.PLAYER_SEND_CHAT, playerAction::handleSendChat);
        router.post(ApiConstants.PLAYER_JUMP, playerAction::handleJump);
        router.get(ApiConstants.PLAYER_INVENTORY, playerAction::handleGetInventory);

        // World query routes
        router.get(ApiConstants.WORLD_BLOCK, worldQuery::handleGetBlock);
        router.post(ApiConstants.WORLD_BLOCKS, worldQuery::handleGetBlocks);
        router.get(ApiConstants.WORLD_PLAYER_BLOCKS, worldQuery::handleGetPlayerBlocks);
        router.get(ApiConstants.WORLD_BIOME, worldQuery::handleGetBiome);
        router.get(ApiConstants.WORLD_TIME, worldQuery::handleGetTime);
        router.get(ApiConstants.WORLD_ENTITIES, worldQuery::handleGetEntities);

        // Block manipulation routes
        router.post(ApiConstants.BLOCKS_SET, blockAction::handleSetBlock);
        router.post(ApiConstants.BLOCKS_FILL, blockAction::handleFillRegion);
        router.post(ApiConstants.BLOCKS_REPLACE, blockAction::handleReplaceBlocks);

        // Command route
        router.post(ApiConstants.COMMAND_EXECUTE, commandAction::handleExecute);

        LOGGER.info("Registered {} API routes", 18);
    }

    public synchronized boolean start() {
        if (running) {
            LOGGER.warn("HTTP server already running");
            return false;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.httpPort), 0);
            server.createContext("/", router);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "mc-ai-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            running = true;
            LOGGER.info("HTTP server started on port {}", config.httpPort);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to start HTTP server on port {}", config.httpPort, e);
            return false;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            running = false;
            LOGGER.info("HTTP server stopped");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return config.httpPort;
    }
}
