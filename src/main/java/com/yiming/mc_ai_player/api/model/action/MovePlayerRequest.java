package com.yiming.mc_ai_player.api.model.action;

import com.yiming.mc_ai_player.api.model.BlockPos;

public class MovePlayerRequest {
    public BlockPos position;
    public float yaw;
    public float pitch;
    public boolean relative;
    public String dimension;
}
