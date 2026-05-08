package com.yiming.mc_ai_player.client.executor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.action.SetBlockRequest;
import com.yiming.mc_ai_player.config.ModConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class WorldQueryExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");
    private static final Gson GSON = new Gson();
    private final ModConfig config;

    public WorldQueryExecutor(ModConfig config) {
        this.config = config;
    }

    public ActionResponse handleGetBlock(HttpExchange exchange, Map<String, String> params) {
        int x, y, z;
        try {
            x = Integer.parseInt(params.get("x"));
            y = Integer.parseInt(params.get("y"));
            z = Integer.parseInt(params.get("z"));
        } catch (NumberFormatException | NullPointerException e) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "x, y, z are required integers");
        }
        final int fx = x, fy = y, fz = z;
        return runOnServerThread(server -> {

            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            ServerWorld world = player.getEntityWorld();
            BlockPos pos = new BlockPos(fx, fy, fz);

            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "Chunk not loaded at " + fx + ", " + fy + ", " + fz);
            }

            BlockState state = world.getBlockState(pos);
            Identifier blockId = Registries.BLOCK.getId(state.getBlock());
            Map<String, String> properties = new HashMap<>();
            state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

            BlockInfo info = new BlockInfo(
                new com.yiming.mc_ai_player.api.model.BlockPos(fx, fy, fz),
                blockId.toString(),
                properties,
                world.getRegistryKey().getValue().toString()
            );
            return ActionResponse.ok(info);
        });
    }

    public ActionResponse handleGetBlocks(HttpExchange exchange, Map<String, String> params) {
        List<com.yiming.mc_ai_player.api.model.BlockPos> positions;
        try {
            Type listType = new TypeToken<List<com.yiming.mc_ai_player.api.model.BlockPos>>() {}.getType();
            String body = new String(exchange.getRequestBody().readAllBytes());
            positions = GSON.fromJson(body, listType);
        } catch (Exception e) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Invalid positions array: " + e.getMessage());
        }

        if (positions == null || positions.isEmpty()) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "positions array is required");
        }

        if (positions.size() > 256) {
            return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "Max 256 positions per request");
        }

        List<BlockInfo> results = new ArrayList<>();
        for (com.yiming.mc_ai_player.api.model.BlockPos bp : positions) {
            BlockInfo info = queryBlock(bp.x, bp.y, bp.z);
            if (info != null) results.add(info);
        }
        return ActionResponse.ok(results);
    }

    public ActionResponse handleGetPlayerBlocks(HttpExchange exchange, Map<String, String> params) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            int radius;
            try {
                radius = Integer.parseInt(params.get("radius"));
                if (radius < 1 || radius > config.maxQueryRange) {
                    return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "radius must be 1-" + config.maxQueryRange);
                }
            } catch (NumberFormatException | NullPointerException e) {
                radius = 8;
            }

            ServerWorld world = player.getEntityWorld();
            BlockPos center = player.getBlockPos();
            int y = center.getY();
            List<BlockInfo> results = new ArrayList<>();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, 0, dz);
                    if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

                    // Get blocks from y-5 to y+5 vertically
                    for (int dy = -5; dy <= 5; dy++) {
                        BlockPos checkPos = pos.add(0, dy, 0);
                        BlockState state = world.getBlockState(checkPos);
                        if (state.isAir()) continue;

                        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                        Map<String, String> properties = new HashMap<>();
                        state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

                        results.add(new BlockInfo(
                            new com.yiming.mc_ai_player.api.model.BlockPos(checkPos.getX(), checkPos.getY(), checkPos.getZ()),
                            blockId.toString(),
                            properties,
                            world.getRegistryKey().getValue().toString()
                        ));
                    }
                }
            }
            return ActionResponse.ok(results);
        });
    }

    public ActionResponse handleGetBiome(HttpExchange exchange, Map<String, String> params) {
        return runOnServerThread(server -> {
            try {
                int x = Integer.parseInt(params.get("x"));
                int y = Integer.parseInt(params.get("y"));
                int z = Integer.parseInt(params.get("z"));

                ServerPlayerEntity player = getPlayer(server);
                if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

                ServerWorld world = player.getEntityWorld();
                BlockPos pos = new BlockPos(x, y, z);
                Identifier biomeId = world.getBiome(pos).getKey()
                    .map(key -> key.getValue())
                    .orElse(Identifier.of("unknown"));

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("biome", biomeId.toString());
                data.put("position", new com.yiming.mc_ai_player.api.model.BlockPos(x, y, z));
                data.put("dimension", world.getRegistryKey().getValue().toString());
                return ActionResponse.ok(data);
            } catch (NumberFormatException | NullPointerException e) {
                return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "x, y, z are required integers");
            }
        });
    }

    public ActionResponse handleGetTime(HttpExchange exchange, Map<String, String> params) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            ServerWorld world = player.getEntityWorld();
            long timeOfDay = world.getTimeOfDay();
            long dayTime = world.getTime();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("time_of_day", timeOfDay);
            data.put("day_time", dayTime);
            data.put("formatted_time", formatTickTime(timeOfDay));
            data.put("dimension", world.getRegistryKey().getValue().toString());
            return ActionResponse.ok(data);
        });
    }

    public ActionResponse handleGetEntities(HttpExchange exchange, Map<String, String> params) {
        return runOnServerThread(server -> {
            try {
                double x = Double.parseDouble(params.get("x"));
                double y = Double.parseDouble(params.get("y"));
                double z = Double.parseDouble(params.get("z"));
                double radius = params.containsKey("radius") ? Double.parseDouble(params.get("radius")) : 16;

                if (radius < 1 || radius > config.maxQueryRange) {
                    return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "radius must be 1-" + config.maxQueryRange);
                }

                ServerPlayerEntity player = getPlayer(server);
                if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

                ServerWorld world = player.getEntityWorld();
                Box box = new Box(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

                List<Map<String, Object>> entityList = new ArrayList<>();
                for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> true)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("uuid", entity.getUuid().toString());
                    entry.put("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                    entry.put("name", entity.getName().getString());
                    entry.put("x", entity.getX());
                    entry.put("y", entity.getY());
                    entry.put("z", entity.getZ());
                    entityList.add(entry);
                }

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("count", entityList.size());
                data.put("entities", entityList);
                return ActionResponse.ok(data);
            } catch (NumberFormatException | NullPointerException e) {
                return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "x, y, z are required numbers");
            }
        });
    }

    private BlockInfo queryBlock(int x, int y, int z) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return null;

            ServerWorld world = player.getEntityWorld();
            BlockPos pos = new BlockPos(x, y, z);
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;

            BlockState state = world.getBlockState(pos);
            Identifier blockId = Registries.BLOCK.getId(state.getBlock());
            Map<String, String> properties = new HashMap<>();
            state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

            return new BlockInfo(
                new com.yiming.mc_ai_player.api.model.BlockPos(x, y, z),
                blockId.toString(),
                properties,
                world.getRegistryKey().getValue().toString()
            );
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

    private static String formatTickTime(long ticks) {
        long hours = (ticks / 1000 + 6) % 24;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format("%02d:%02d", hours, minutes);
    }
}
