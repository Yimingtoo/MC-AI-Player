package com.yiming.mc_ai_player.api.model.action;

import com.yiming.mc_ai_player.api.model.BlockPos;

import java.util.Map;

public class SetBlockRequest {
    public BlockPos position;
    public String blockId;
    public Map<String, String> blockState;
    public String dimension;
}
