package com.yiming.mc_ai_player.api.model;

import java.util.Map;

public class BlockInfo {
    public BlockPos position;
    public String blockId;
    public Map<String, String> blockState;
    public String dimension;

    public BlockInfo() {}

    public BlockInfo(BlockPos position, String blockId, Map<String, String> blockState, String dimension) {
        this.position = position;
        this.blockId = blockId;
        this.blockState = blockState;
        this.dimension = dimension;
    }
}
