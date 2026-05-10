package com.yiming.mc_ai_player.client;

import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.client.command.McAiPlayerCommand;
import com.yiming.mc_ai_player.client.executor.*;
import com.yiming.mc_ai_player.client.mcp.McpServer;
import com.yiming.mc_ai_player.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import java.io.PrintStream;

public class Mc_ai_playerClient implements ClientModInitializer {
    private static McpServer mcpServer;

    public static McpServer getMcpServer() {
        return mcpServer;
    }

    @Override
    public void onInitializeClient() {
        ModConfig config = Mc_ai_player.CONFIG_MANAGER.get();

        // Reserve stdout for MCP protocol messages; redirect game logs to stderr
        PrintStream originalStdout = System.out;
        System.setOut(System.err);

        // Create executors
        PlayerActionExecutor playerAction = new PlayerActionExecutor(config);
        WorldQueryExecutor worldQuery = new WorldQueryExecutor(config);
        BlockActionExecutor blockAction = new BlockActionExecutor(config);
        CommandActionExecutor commandAction = new CommandActionExecutor(config);

        // Create and start MCP server (writes to original stdout)
        mcpServer = new McpServer(originalStdout, playerAction, worldQuery, blockAction, commandAction);
        mcpServer.start();

        // Register /mcai command
        CommandRegistrationCallback.EVENT.register(McAiPlayerCommand::register);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (mcpServer != null) {
                mcpServer.stop();
            }
        });
    }
}
