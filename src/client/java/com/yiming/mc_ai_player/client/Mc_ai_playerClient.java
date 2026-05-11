package com.yiming.mc_ai_player.client;

import com.yiming.mc_ai_player.client.command.McAiPlayerCommand;
import com.yiming.mc_ai_player.client.executor.*;
import com.yiming.mc_ai_player.client.mcp.McpServer;
import com.yiming.mc_ai_player.client.mcp.McpSseServer;
import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import java.io.PrintStream;

public class Mc_ai_playerClient implements ClientModInitializer {
    private static McpServer mcpServer;
    private static McpSseServer mcpSseServer;

    public static McpServer getMcpServer() {
        return mcpServer;
    }

    public static McpSseServer getMcpSseServer() {
        return mcpSseServer;
    }

    @Override
    public void onInitializeClient() {
        // Create executors
        PlayerActionExecutor playerAction = new PlayerActionExecutor();
        WorldQueryExecutor worldQuery = new WorldQueryExecutor();
        BlockActionExecutor blockAction = new BlockActionExecutor();
        CommandActionExecutor commandAction = new CommandActionExecutor();

        // Determine transport mode
        ModConfig config = Mc_ai_player.CONFIG_MANAGER.get();
        if ("sse".equals(config.mcpTransport)) {
            startSseTransport(config, playerAction, worldQuery, blockAction, commandAction);
        } else {
            startStdioTransport(playerAction, worldQuery, blockAction, commandAction);
        }

        // Register /mcai command
        CommandRegistrationCallback.EVENT.register(McAiPlayerCommand::register);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (mcpServer != null) mcpServer.stop();
            if (mcpSseServer != null) mcpSseServer.stop();
        });
    }

    private void startSseTransport(ModConfig config,
                                    PlayerActionExecutor playerAction,
                                    WorldQueryExecutor worldQuery,
                                    BlockActionExecutor blockAction,
                                    CommandActionExecutor commandAction) {
        try {
            mcpSseServer = new McpSseServer(
                    config.mcpPort,
                    playerAction, worldQuery, blockAction, commandAction
            );
            mcpSseServer.start();
        } catch (Exception e) {
            System.err.println("[Mc_ai_playerClient] 启动 MCP SSE Server 失败: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private void startStdioTransport(PlayerActionExecutor playerAction,
                                     WorldQueryExecutor worldQuery,
                                     BlockActionExecutor blockAction,
                                     CommandActionExecutor commandAction) {
        PrintStream originalStdout = System.out;
        System.setOut(System.err);

        mcpServer = new McpServer(originalStdout, playerAction, worldQuery, blockAction, commandAction);
        mcpServer.start();
    }
}
