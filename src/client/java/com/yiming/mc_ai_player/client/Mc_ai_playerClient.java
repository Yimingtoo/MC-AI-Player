package com.yiming.mc_ai_player.client;

import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.client.command.McAiPlayerCommand;
import com.yiming.mc_ai_player.client.http.HttpServerManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class Mc_ai_playerClient implements ClientModInitializer {
    private static HttpServerManager httpServer;

    public static HttpServerManager getHttpServer() {
        return httpServer;
    }

    @Override
    public void onInitializeClient() {
        var config = Mc_ai_player.CONFIG_MANAGER.get();
        httpServer = new HttpServerManager(config);
        httpServer.start();

        // Register /mcai command from client side
        CommandRegistrationCallback.EVENT.register(McAiPlayerCommand::register);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (httpServer != null) {
                httpServer.stop();
            }
        });
    }
}
