package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.Mc_ai_player;
import com.yiming.mc_ai_player.client.bridge.ServerAccessor;
import com.yiming.mc_ai_player.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public abstract class ActionExecutor {

    protected static ModConfig getConfig() {
        return Mc_ai_player.CONFIG_MANAGER.get();
    }

    protected static ServerWorld getWorld(MinecraftServer server, String dimensionId) {
        Identifier id = Identifier.tryParse(dimensionId);
        if (id == null) return null;
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().equals(id)) {
                return world;
            }
        }
        return null;
    }

    protected static <T> T runOnServerThread(Function<MinecraftServer, T> action) {
        MinecraftServer server = ServerAccessor.getServer();
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                T result = action.apply(server);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Operation timed out on server thread", e);
        } catch (Exception e) {
            if (e.getCause() != null) {
                throw new RuntimeException(e.getCause());
            }
            throw new RuntimeException(e);
        }
    }

    protected static ServerPlayerEntity getPlayer(MinecraftServer server) {
        var client = MinecraftClient.getInstance();
        if (client.player != null) {
            return server.getPlayerManager().getPlayer(client.player.getUuid());
        }
        var players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }
}
