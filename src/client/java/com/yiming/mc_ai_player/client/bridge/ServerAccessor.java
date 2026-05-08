package com.yiming.mc_ai_player.client.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

public class ServerAccessor {
    public static MinecraftServer getServer() {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) {
            throw new IllegalStateException("Not connected to a server/world");
        }
        return server;
    }
}
