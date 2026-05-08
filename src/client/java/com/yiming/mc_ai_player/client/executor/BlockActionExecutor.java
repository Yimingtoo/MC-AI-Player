package com.yiming.mc_ai_player.client.executor;

import com.sun.net.httpserver.HttpExchange;
import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.action.FillRegionRequest;
import com.yiming.mc_ai_player.api.model.action.SetBlockRequest;
import com.yiming.mc_ai_player.api.model.BlockPos;
import com.yiming.mc_ai_player.client.http.JsonRouter;
import com.yiming.mc_ai_player.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos.Mutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class BlockActionExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");
    private final ModConfig config;

    public BlockActionExecutor(ModConfig config) {
        this.config = config;
    }

    public ActionResponse handleSetBlock(HttpExchange exchange, Map<String, String> params) {
        if (!config.enableBlockOperations) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Block operations are disabled");
        }

        SetBlockRequest req = JsonRouter.parseBody(exchange, SetBlockRequest.class);
        if (req == null || req.position == null || req.blockId == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "position and blockId are required");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            String dimension = req.dimension != null ? req.dimension : player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!config.allowedDimensions.contains(dimension)) {
                return ActionResponse.error(ErrorCode.DIMENSION_DISALLOWED, "Dimension not allowed: " + dimension);
            }

            ServerWorld world = getWorld(server, dimension);
            if (world == null) return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Invalid dimension: " + dimension);

            Identifier blockId = Identifier.tryParse(req.blockId);
            if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
                return ActionResponse.error(ErrorCode.BLOCK_NOT_FOUND, "Unknown block: " + req.blockId);
            }

            if (config.blockBlacklist.contains(blockId.toString())) {
                return ActionResponse.error(ErrorCode.BLOCK_BLACKLISTED, "Block is blacklisted: " + req.blockId);
            }

            Block block = Registries.BLOCK.get(blockId);
            BlockState state = block.getDefaultState();

            // Apply block state properties if specified
            if (req.blockState != null) {
                for (var entry : req.blockState.entrySet()) {
                    state = applyProperty(state, entry.getKey(), entry.getValue());
                }
            }

            net.minecraft.util.math.BlockPos mcPos = new net.minecraft.util.math.BlockPos(
                req.position.x, req.position.y, req.position.z
            );

            boolean result = world.setBlockState(mcPos, state, net.minecraft.block.Block.NOTIFY_ALL);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result);
            data.put("position", req.position);
            data.put("blockId", req.blockId);
            return ActionResponse.ok(data);
        });
    }

    public ActionResponse handleFillRegion(HttpExchange exchange, Map<String, String> params) {
        if (!config.enableBlockOperations) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Block operations are disabled");
        }

        FillRegionRequest req = JsonRouter.parseBody(exchange, FillRegionRequest.class);
        if (req == null || req.from == null || req.to == null || req.blockId == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "from, to, and blockId are required");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            String dimension = req.dimension != null ? req.dimension : player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!config.allowedDimensions.contains(dimension)) {
                return ActionResponse.error(ErrorCode.DIMENSION_DISALLOWED, "Dimension not allowed: " + dimension);
            }

            Identifier blockId = Identifier.tryParse(req.blockId);
            if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
                return ActionResponse.error(ErrorCode.BLOCK_NOT_FOUND, "Unknown block: " + req.blockId);
            }

            if (config.blockBlacklist.contains(blockId.toString())) {
                return ActionResponse.error(ErrorCode.BLOCK_BLACKLISTED, "Block is blacklisted: " + req.blockId);
            }

            int minX = Math.min(req.from.x, req.to.x);
            int minY = Math.min(req.from.y, req.to.y);
            int minZ = Math.min(req.from.z, req.to.z);
            int maxX = Math.max(req.from.x, req.to.x);
            int maxY = Math.max(req.from.y, req.to.y);
            int maxZ = Math.max(req.from.z, req.to.z);

            int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > config.maxBuildVolume) {
                return ActionResponse.error(ErrorCode.VOLUME_EXCEEDED,
                    "Volume " + volume + " exceeds max " + config.maxBuildVolume);
            }

            ServerWorld world = getWorld(server, dimension);
            if (world == null) return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Invalid dimension: " + dimension);

            Block block = Registries.BLOCK.get(blockId);
            BlockState state = block.getDefaultState();
            boolean keepAir = "keep_air".equals(req.replaceMode);

            int placed = 0;
            Mutable pos = new Mutable();
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        pos.set(x, y, z);
                        if (keepAir && world.getBlockState(pos).isAir()) continue;
                        world.setBlockState(pos, state, Block.NOTIFY_ALL);
                        placed++;
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("placed", placed);
            data.put("volume", volume);
            data.put("blockId", req.blockId);
            data.put("from", req.from);
            data.put("to", req.to);
            return ActionResponse.ok(data);
        });
    }

    public ActionResponse handleReplaceBlocks(HttpExchange exchange, Map<String, String> params) {
        if (!config.enableBlockOperations) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Block operations are disabled");
        }

        FillRegionRequest req = JsonRouter.parseBody(exchange, FillRegionRequest.class);
        if (req == null || req.from == null || req.to == null || req.blockId == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "from, to, and blockId are required");
        }

        // The filterBlockId will be passed in blockState as a special key, or we extend FillRegionRequest
        // For simplicity, use blockId as the replacement and expect a "filter" field
        // Actually let's just reuse: the request body can have additional filter field
        // We'll parse it manually
        Map<String, Object> body;
        try {
            var gson = new com.google.gson.Gson();
            var reader = new java.io.InputStreamReader(exchange.getRequestBody(), java.nio.charset.StandardCharsets.UTF_8);
            body = gson.fromJson(reader, Map.class);
        } catch (Exception e) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Invalid request body");
        }

        String filterBlockId = body != null ? (String) body.get("filterBlockId") : null;
        if (filterBlockId == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "filterBlockId is required");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            String dimension = req.dimension != null ? req.dimension : player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!config.allowedDimensions.contains(dimension)) {
                return ActionResponse.error(ErrorCode.DIMENSION_DISALLOWED, "Dimension not allowed: " + dimension);
            }

            Identifier targetId = Identifier.tryParse(req.blockId);
            Identifier filterId = Identifier.tryParse(filterBlockId);
            if (targetId == null || filterId == null || !Registries.BLOCK.containsId(targetId) || !Registries.BLOCK.containsId(filterId)) {
                return ActionResponse.error(ErrorCode.BLOCK_NOT_FOUND, "Unknown block ID");
            }

            if (config.blockBlacklist.contains(targetId.toString())) {
                return ActionResponse.error(ErrorCode.BLOCK_BLACKLISTED, "Block is blacklisted: " + req.blockId);
            }

            int minX = Math.min(req.from.x, req.to.x);
            int minY = Math.min(req.from.y, req.to.y);
            int minZ = Math.min(req.from.z, req.to.z);
            int maxX = Math.max(req.from.x, req.to.x);
            int maxY = Math.max(req.from.y, req.to.y);
            int maxZ = Math.max(req.from.z, req.to.z);

            int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > config.maxBuildVolume) {
                return ActionResponse.error(ErrorCode.VOLUME_EXCEEDED,
                    "Volume " + volume + " exceeds max " + config.maxBuildVolume);
            }

            ServerWorld world = getWorld(server, dimension);
            Block replacement = Registries.BLOCK.get(targetId);
            BlockState replaceState = replacement.getDefaultState();
            Block filterBlock = Registries.BLOCK.get(filterId);

            int replaced = 0;
            Mutable pos = new Mutable();
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        pos.set(x, y, z);
                        if (world.getBlockState(pos).getBlock() == filterBlock) {
                            world.setBlockState(pos, replaceState, Block.NOTIFY_ALL);
                            replaced++;
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("replaced", replaced);
            data.put("filterBlockId", filterBlockId);
            data.put("replaceWithBlockId", req.blockId);
            return ActionResponse.ok(data);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, String key, String value) {
        var prop = state.getBlock().getStateManager().getProperty(key);
        if (prop != null) {
            try {
                Property<T> typedProp = (Property<T>) prop;
                return state.with(typedProp, typedProp.parse(value).orElseThrow());
            } catch (Exception e) {
                LOGGER.warn("Failed to set property {}={}", key, value);
            }
        }
        return state;
    }

    private static ServerPlayerEntity getPlayer(MinecraftServer server) {
        var client = MinecraftClient.getInstance();
        if (client.player != null) {
            return server.getPlayerManager().getPlayer(client.player.getUuid());
        }
        var players = server.getPlayerManager().getPlayerList();
        return players.isEmpty() ? null : players.get(0);
    }

    private static ServerWorld getWorld(MinecraftServer server, String dimensionId) {
        Identifier id = Identifier.tryParse(dimensionId);
        if (id == null) return null;
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().equals(id)) {
                return world;
            }
        }
        return null;
    }
}
