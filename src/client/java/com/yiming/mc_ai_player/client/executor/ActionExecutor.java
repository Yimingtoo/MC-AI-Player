package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.client.bridge.ServerAccessor;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public abstract class ActionExecutor {

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
            throw new RuntimeException("Operation timed out on server thread", e);
        } catch (Exception e) {
            if (e.getCause() != null) {
                throw new RuntimeException(e.getCause());
            }
            throw new RuntimeException(e);
        }
    }
}
