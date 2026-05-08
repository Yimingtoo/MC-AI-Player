package com.yiming.mc_ai_player.api.model.action;

import com.yiming.mc_ai_player.api.model.BlockPos;

public class FillRegionRequest {
    public BlockPos from;
    public BlockPos to;
    public String blockId;
    public String replaceMode; // "all" or "keep_air"
    public String dimension;
}
