package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.monitor.MonitoringSession;
import com.yiming.mc_ai_player.monitor.MonitoringState;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos.Mutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MonitorRegionExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    public MonitorRegionExecutor() {
    }

    public ActionResponse handleStart(BlockPos from, BlockPos to, int durationTicks, String dimension) {
        if (from == null || to == null) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "from and to are required");
        }
        if (durationTicks < 1) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "durationTicks must be >= 1");
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

            // Clear any existing session
            MonitoringState.clearSession();

            // Build initial snapshot (all blocks, including air)
            List<Map<String, Object>> snapshot = new ArrayList<>(volume);
            Mutable mcPos = new Mutable();
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                        mcPos.set(x, y, z);
                        BlockState state = world.getBlockState(mcPos);
                        Identifier blockId = Registries.BLOCK.getId(state.getBlock());

                        Map<String, Object> entry = new LinkedHashMap<>();
                        Map<String, Integer> pos = new LinkedHashMap<>();
                        pos.put("x", x);
                        pos.put("y", y);
                        pos.put("z", z);
                        entry.put("position", pos);
                        // Air blocks → null
                        entry.put("blockId", state.isAir() ? null : blockId.toString());
                        snapshot.add(entry);
                    }
                }
            }

            // Create session
            String sessionId = UUID.randomUUID().toString();
            MonitoringSession session = new MonitoringSession(
                sessionId, minX, minY, minZ, maxX, maxY, maxZ, dim, durationTicks
            );
            session.setInitialSnapshot(snapshot);
            MonitoringState.setActiveSession(session);

            LOGGER.info("Monitor started: session={}, region=({},{},{})->({},{},{}), ticks={}",
                sessionId, minX, minY, minZ, maxX, maxY, maxZ, durationTicks);

            // Signal the session to start monitoring (enables mixin recording)
            session.startMonitoring();

            return ActionResponse.ok(session.buildInitialResult());
        });
    }

    public ActionResponse handleGet(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "sessionId is required");
        }

        return runOnServerThread(server -> {
            MonitoringSession session = MonitoringState.getActiveSession();
            if (session == null || !session.sessionId.equals(sessionId)) {
                return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Session not found: " + sessionId);
            }

            if (!session.isCompleted()) {
                return ActionResponse.ok(session.buildRunningStatus());
            }

            // Session complete — return data and clean up
            Map<String, Object> result = session.buildCompletedResult();
            MonitoringState.clearSession();
            LOGGER.info("Monitor completed: session={}", sessionId);
            return ActionResponse.ok(result);
        });
    }
}
