package com.yiming.mc_ai_player.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.client.Mc_ai_playerClient;
import com.yiming.mc_ai_player.client.mcp.McpServer;
import com.yiming.mc_ai_player.client.mcp.McpSseServer;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

public class McAiPlayerCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("mcai")
            .then(literal("status")
                .executes(ctx -> {
                    McpServer mcp = Mc_ai_playerClient.getMcpServer();
                    McpSseServer sse = Mc_ai_playerClient.getMcpSseServer();
                    if (sse != null && sse.isRunning()) {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§a[MC_AI] MCP server is running (SSE, port " + sse.getPort() + ")"), false);
                    } else if (mcp != null && mcp.isRunning()) {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§a[MC_AI] MCP server is running (stdio)"), false);
                    } else {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§c[MC_AI] MCP server is not running"), false);
                    }
                    return 1;
                })
            )
            .then(literal("reload")
                .executes(ctx -> {
                    Mc_ai_player.CONFIG_MANAGER.reload();
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§a[MC_AI] Config reloaded"), false);
                    return 1;
                })
            )
            .then(literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§6=== MC_AI Player Commands ==="), false);
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§e/mcai status §7- Show MCP server status"), false);
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§e/mcai reload §7- Reload config"), false);
                    return 1;
                })
            )
        );
    }
}
