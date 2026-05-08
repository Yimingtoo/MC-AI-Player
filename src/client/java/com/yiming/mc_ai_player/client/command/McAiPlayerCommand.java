package com.yiming.mc_ai_player.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.client.Mc_ai_playerClient;
import com.yiming.mc_ai_player.client.http.HttpServerManager;
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
                    HttpServerManager http = Mc_ai_playerClient.getHttpServer();
                    if (http != null && http.isRunning()) {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§a[MC_AI] HTTP server is running on port " + http.getPort()), false);
                    } else {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§c[MC_AI] HTTP server is not running"), false);
                    }
                    return 1;
                })
            )
            .then(literal("start")
                .executes(ctx -> {
                    HttpServerManager http = Mc_ai_playerClient.getHttpServer();
                    if (http == null) {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§c[MC_AI] HTTP server manager not available"), false);
                        return 0;
                    }
                    if (http.isRunning()) {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§e[MC_AI] HTTP server is already running"), false);
                        return 1;
                    }
                    http.start();
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§a[MC_AI] HTTP server started"), false);
                    return 1;
                })
            )
            .then(literal("stop")
                .executes(ctx -> {
                    HttpServerManager http = Mc_ai_playerClient.getHttpServer();
                    if (http != null && http.isRunning()) {
                        http.stop();
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§c[MC_AI] HTTP server stopped"), false);
                    } else {
                        ctx.getSource().sendFeedback(() ->
                            Text.literal("§e[MC_AI] HTTP server is not running"), false);
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
                        Text.literal("§e/mcai status §7- Show HTTP server status"), false);
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§e/mcai start §7- Start HTTP server"), false);
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§e/mcai stop §7- Stop HTTP server"), false);
                    ctx.getSource().sendFeedback(() ->
                        Text.literal("§e/mcai reload §7- Reload config"), false);
                    return 1;
                })
            )
        );
    }
}
