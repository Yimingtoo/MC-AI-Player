package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.action.MovePlayerRequest;
import com.yiming.mc_ai_player.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class PlayerActionExecutor extends ActionExecutor {
    private final ModConfig config;

    public PlayerActionExecutor(ModConfig config) {
        this.config = config;
    }

    public ActionResponse handleGetPosition() {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            PlayerInfo info = new PlayerInfo(
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                player.getHealth(),
                player.interactionManager.getGameMode().asString()
            );
            return ActionResponse.ok(info);
        });
    }

    public ActionResponse handleMove(MovePlayerRequest req) {
        if (!config.enablePlayerMovement) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Player movement is disabled");
        }

        if (req == null || req.position == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "position is required");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            double x = req.position.x;
            double y = req.position.y;
            double z = req.position.z;

            if (req.relative) {
                x += player.getX();
                y += player.getY();
                z += player.getZ();
            }

            String dimension = req.dimension != null ? req.dimension : player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!config.allowedDimensions.contains(dimension)) {
                return ActionResponse.error(ErrorCode.DIMENSION_DISALLOWED, "Dimension not allowed: " + dimension);
            }

            ServerWorld targetWorld = null;
            Identifier dimId = Identifier.tryParse(dimension);
            if (dimId != null) {
                for (ServerWorld w : server.getWorlds()) {
                    if (w.getRegistryKey().getValue().equals(dimId)) {
                        targetWorld = w;
                        break;
                    }
                }
            }

            if (targetWorld == null) {
                targetWorld = player.getEntityWorld();
            }

            player.teleport(targetWorld, x, y, z, java.util.EnumSet.noneOf(PositionFlag.class), req.yaw, req.pitch, false);

            PlayerInfo info = new PlayerInfo(
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                targetWorld.getRegistryKey().getValue().toString(),
                player.getHealth(),
                player.interactionManager.getGameMode().asString()
            );
            return ActionResponse.ok(info);
        });
    }

    public ActionResponse handleLook(float yaw, float pitch) {
        if (!config.enablePlayerMovement) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Player movement is disabled");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            player.setYaw(yaw);
            player.setPitch(pitch);

            PlayerInfo info = new PlayerInfo(
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                player.getHealth(),
                player.interactionManager.getGameMode().asString()
            );
            return ActionResponse.ok(info);
        });
    }

    public ActionResponse handleSendChat(String message) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            server.getPlayerManager().broadcast(Text.literal("[AI] " + message), false);
            return ActionResponse.ok(Map.of("sent", message));
        });
    }

    public ActionResponse handleJump() {
        if (!config.enablePlayerMovement) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Player movement is disabled");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            Vec3d velocity = player.getVelocity();
            player.setVelocity(velocity.x, 0.42, velocity.z);
            player.velocityDirty = true;
            return ActionResponse.ok(Map.of("jumped", true));
        });
    }

    public ActionResponse handleGetInventory() {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            PlayerInventory inv = player.getInventory();
            List<Map<String, Object>> slots = new ArrayList<>();

            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("slot", i);
                    slot.put("item", Registries.ITEM.getId(stack.getItem()).toString());
                    slot.put("count", stack.getCount());
                    slots.add(slot);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("slots", slots);
            data.put("mainHand", Registries.ITEM.getId(player.getMainHandStack().getItem()).toString());
            return ActionResponse.ok(data);
        });
    }

    private static ServerPlayerEntity getPlayer(MinecraftServer server) {
        var client = MinecraftClient.getInstance();
        if (client.player != null) {
            return server.getPlayerManager().getPlayer(client.player.getUuid());
        }
        var players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }
}
