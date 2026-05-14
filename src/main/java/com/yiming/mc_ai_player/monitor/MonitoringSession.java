package com.yiming.mc_ai_player.monitor;

import com.yiming.mc_ai_player.api.model.BlockPos;
import net.minecraft.util.math.BlockPos.Mutable;

import java.util.*;

/**
 * Holds the state of a single region monitoring session.
 * All methods must be called from the server thread.
 */
public class MonitoringSession {

    public enum Status { INITIALIZING, MONITORING, COMPLETED }

    public final String sessionId;
    public final int minX, minY, minZ;
    public final int maxX, maxY, maxZ;
    public final String dimension;
    public final int totalTicks;

    private Status status = Status.INITIALIZING;
    private int currentTick = 0;

    // Initial full snapshot (tick 0)
    private List<Map<String, Object>> initialSnapshot;

    // Positions modified in the current GT → oldBlockId at first change
    private final Map<net.minecraft.util.math.BlockPos, String> pendingChanges = new HashMap<>();

    // Completed GT change sets
    private final List<TickChangeSet> tickChanges = new ArrayList<>();

    public MonitoringSession(String sessionId, int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ,
                             String dimension, int totalTicks) {
        this.sessionId = sessionId;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.dimension = dimension;
        this.totalTicks = totalTicks;
    }

    // ---- Builder ----

    public void setInitialSnapshot(List<Map<String, Object>> snapshot) {
        this.initialSnapshot = snapshot;
    }

    public void startMonitoring() {
        this.status = Status.MONITORING;
        this.currentTick = 0;
    }

    // ---- Mixin callback (called per block state change) ----

    /**
     * Called from ServerWorldMixin when a block in the monitored region changes.
     * Records the old blockId only on the FIRST change for this position in the current GT.
     */
    public void recordChange(net.minecraft.util.math.BlockPos pos, String oldBlockId) {
        if (status != Status.MONITORING) return;
        // putIfAbsent: only records first change per GT
        pendingChanges.putIfAbsent(pos.toImmutable(), oldBlockId);
    }

    public boolean isActive() {
        return status == Status.INITIALIZING || status == Status.MONITORING;
    }

    public Set<net.minecraft.util.math.BlockPos> getPendingPositions() {
        return pendingChanges.keySet();
    }

    // ---- Called at end of each GT ----

    /**
     * Advances one GT: reads current world state for all modified positions,
     * records changes, and clears the pending set.
     */
    public void advanceTick(Map<net.minecraft.util.math.BlockPos, String> currentBlockIds) {
        if (status != Status.MONITORING) return;
        if (currentTick >= totalTicks) return;

        currentTick++;

        if (pendingChanges.isEmpty()) {
            tickChanges.add(new TickChangeSet(currentTick, Collections.emptyList()));
            pendingChanges.clear();
            checkCompleted();
            return;
        }

        List<ChangeEntry> changes = new ArrayList<>(pendingChanges.size());
        for (var entry : pendingChanges.entrySet()) {
            net.minecraft.util.math.BlockPos mcPos = entry.getKey();
            String oldBlockId = entry.getValue();
            String newBlockId = currentBlockIds.getOrDefault(mcPos, oldBlockId);

            if (!Objects.equals(oldBlockId, newBlockId)) {
                changes.add(new ChangeEntry(
                    new BlockPos(mcPos.getX(), mcPos.getY(), mcPos.getZ()),
                    oldBlockId, newBlockId
                ));
            }
        }
        tickChanges.add(new TickChangeSet(currentTick, changes));
        pendingChanges.clear();
        checkCompleted();
    }

    private void checkCompleted() {
        if (currentTick >= totalTicks) {
            status = Status.COMPLETED;
        }
    }

    // ---- Result retrieval ----

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public int getCurrentTick() {
        return currentTick;
    }

    public Map<String, Object> buildInitialResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("type", "initial");
        result.put("tick", 0);
        result.put("blocks", initialSnapshot);
        result.put("count", initialSnapshot.size());
        result.put("from", new BlockPos(minX, minY, minZ));
        result.put("to", new BlockPos(maxX, maxY, maxZ));
        result.put("dimension", dimension);
        result.put("duration_ticks", totalTicks);
        return result;
    }

    public Map<String, Object> buildRunningStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("status", "running");
        result.put("current_tick", currentTick);
        result.put("total_ticks", totalTicks);
        return result;
    }

    public Map<String, Object> buildCompletedResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("type", "changes");
        result.put("total_ticks", totalTicks);
        result.put("completed", true);

        List<Map<String, Object>> perTick = new ArrayList<>(tickChanges.size());
        for (TickChangeSet tcs : tickChanges) {
            Map<String, Object> tickEntry = new LinkedHashMap<>();
            tickEntry.put("tick", tcs.tick);
            List<Map<String, Object>> changeList = new ArrayList<>(tcs.changes.size());
            for (ChangeEntry ce : tcs.changes) {
                Map<String, Object> change = new LinkedHashMap<>();
                Map<String, Integer> pos = new LinkedHashMap<>();
                pos.put("x", ce.position.x);
                pos.put("y", ce.position.y);
                pos.put("z", ce.position.z);
                change.put("position", pos);
                change.put("oldBlockId", ce.oldBlockId);
                change.put("newBlockId", ce.newBlockId);
                changeList.add(change);
            }
            tickEntry.put("changes", changeList);
            perTick.add(tickEntry);
        }
        result.put("per_tick", perTick);
        return result;
    }

    // ---- Value types ----

    public record TickChangeSet(int tick, List<ChangeEntry> changes) {}
    public record ChangeEntry(BlockPos position, String oldBlockId, String newBlockId) {}
}
