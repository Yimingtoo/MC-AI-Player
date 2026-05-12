package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class WorldQueryExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    public WorldQueryExecutor() {
    }

    public ActionResponse handleGetBlock(int x, int y, int z) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");
            ServerWorld world = player.getEntityWorld();
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);

            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "Chunk not loaded at " + x + ", " + y + ", " + z);
            }

            BlockState state = world.getBlockState(pos);
            Identifier blockId = Registries.BLOCK.getId(state.getBlock());
            Map<String, String> properties = new HashMap<>();
            state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

            BlockInfo info = new BlockInfo(
                new BlockPos(x, y, z),
                blockId.toString(),
                properties,
                world.getRegistryKey().getValue().toString()
            );
            return ActionResponse.ok(info);
        });
    }

    public ActionResponse handleGetBlocks(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "positions array is required");
        }

        if (positions.size() > 256) {
            return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "Max 256 positions per request");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");
            ServerWorld world = player.getEntityWorld();
            List<BlockInfo> results = new ArrayList<>();
            int skipped = 0;
            for (BlockPos bp : positions) {
                net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(bp.x, bp.y, bp.z);
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                    skipped++;
                    continue;
                }

                BlockState state = world.getBlockState(pos);
                Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                Map<String, String> properties = new HashMap<>();
                state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

                results.add(new BlockInfo(
                    bp, blockId.toString(), properties, world.getRegistryKey().getValue().toString()
                ));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blocks", results);
            data.put("count", results.size());
            data.put("skipped", skipped);
            return ActionResponse.ok(data);
        });
    }

    public ActionResponse handleGetPlayerBlocks(int radius) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            if (radius < 1 || radius > getConfig().maxQueryRange) {
                return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "radius must be 1-" + getConfig().maxQueryRange);
            }

            ServerWorld world = player.getEntityWorld();
            net.minecraft.util.math.BlockPos center = player.getBlockPos();
            List<BlockInfo> results = new ArrayList<>();
            int vr = Math.min(getConfig().verticalQueryRange, getConfig().maxQueryRange);

            for (int dx = -radius; dx <= radius && results.size() < 5000; dx++) {
                for (int dz = -radius; dz <= radius && results.size() < 5000; dz++) {
                    net.minecraft.util.math.BlockPos pos = center.add(dx, 0, dz);
                    if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

                    for (int dy = -vr; dy <= vr && results.size() < 5000; dy++) {
                        net.minecraft.util.math.BlockPos checkPos = pos.add(0, dy, 0);
                        BlockState state = world.getBlockState(checkPos);
                        if (state.isAir()) continue;

                        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                        Map<String, String> properties = new HashMap<>();
                        state.getEntries().forEach((prop, value) -> properties.put(prop.getName(), value.toString()));

                        results.add(new BlockInfo(
                            new BlockPos(checkPos.getX(), checkPos.getY(), checkPos.getZ()),
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

    public ActionResponse handleGetBiome(int x, int y, int z) {
        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");
            ServerWorld world = player.getEntityWorld();
            net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
            Identifier biomeId = world.getBiome(pos).getKey()
                .map(key -> key.getValue())
                .orElse(Identifier.of("unknown"));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("biome", biomeId.toString());
            data.put("position", new BlockPos(x, y, z));
            data.put("dimension", world.getRegistryKey().getValue().toString());
            return ActionResponse.ok(data);
        });
    }

    public ActionResponse handleGetTime() {
        return runOnServerThread(server -> {
            ServerWorld world = server.getWorlds().iterator().next();
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

    public ActionResponse handleGetEntities(double x, double y, double z, double radius) {
        return runOnServerThread(server -> {
            if (radius < 1 || radius > getConfig().maxQueryRange) {
                return ActionResponse.error(ErrorCode.OUT_OF_RANGE, "radius must be 1-" + getConfig().maxQueryRange);
            }

            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            ServerWorld world = player.getEntityWorld();
            Box box = new Box(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

            List<Map<String, Object>> entityList = new ArrayList<>();
            for (Entity entity : world.getEntitiesByClass(Entity.class, box, entity -> true)) {
                if (entity == player) continue;
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
        });
    }

    private static String formatTickTime(long ticks) {
        long hours = (ticks / 1000 + 6) % 24;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format("%02d:%02d", hours, minutes);
    }
}
