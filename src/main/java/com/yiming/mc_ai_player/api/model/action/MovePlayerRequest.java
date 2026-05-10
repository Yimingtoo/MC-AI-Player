package com.yiming.mc_ai_player.api.model.action;

import com.yiming.mc_ai_player.api.model.Position;

public class MovePlayerRequest {
    public Position position;
    public float yaw;
    public float pitch;
    public boolean relative;
    public String dimension;
}
