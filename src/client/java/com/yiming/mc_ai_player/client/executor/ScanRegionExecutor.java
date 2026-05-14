package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.api.model.*;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos.Mutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ScanRegionExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    public ScanRegionExecutor() {
    }

    public ActionResponse handleScanRegion(BlockPos from, BlockPos to, String dimension) {
        if (from == null || to == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "from and to are required");
        }

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            String dim = dimension != null ? dimension : player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!getConfig().allowedDimensions.contains(dim)) {
                return ActionResponse.error(ErrorCode.DIMENSION_DISALLOWED, "Dimension not allowed: " + dim);
            }

            ServerWorld world = getWorld(server, dim);
            if (world == null) return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Invalid dimension: " + dim);

            int minX = Math.min(from.x, to.x);
            int minY = Math.min(from.y, to.y);
            int minZ = Math.min(from.z, to.z);
            int maxX = Math.max(from.x, to.x);
            int maxY = Math.max(from.y, to.y);
            int maxZ = Math.max(from.z, to.z);

            int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > getConfig().maxScanVolume) {
                return ActionResponse.error(ErrorCode.VOLUME_EXCEEDED,
                    "Volume " + volume + " exceeds max scan volume " + getConfig().maxScanVolume);
            }

            int maxResults = getConfig().maxScanResult;
            List<Map<String, Object>> blocks = new ArrayList<>();
            int skippedChunks = 0;
            boolean truncated = false;

            Mutable mcPos = new Mutable();
            outer:
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            skippedChunks++;
                            continue;
                        }

                        mcPos.set(x, y, z);
                        BlockState state = world.getBlockState(mcPos);
                        if (state.isAir()) continue;

                        Identifier blockId = Registries.BLOCK.getId(state.getBlock());

                        Map<String, Object> entry = new LinkedHashMap<>();
                        Map<String, Integer> pos = new LinkedHashMap<>();
                        pos.put("x", x);
                        pos.put("y", y);
                        pos.put("z", z);
                        entry.put("position", pos);
                        entry.put("blockId", blockId.toString());
                        blocks.add(entry);

                        if (blocks.size() >= maxResults) {
                            truncated = true;
                            break outer;
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blocks", blocks);
            data.put("count", blocks.size());
            data.put("skipped_chunks", skippedChunks);
            data.put("truncated", truncated);
            data.put("from", from);
            data.put("to", to);
            data.put("dimension", dim);
            return ActionResponse.ok(data);
        });
    }
}
