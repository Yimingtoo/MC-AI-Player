package com.yiming.mc_ai_player.api.model;

public class PlayerInfo {
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public String dimension;
    public float health;
    public String gameMode;

    public PlayerInfo() {}

    public PlayerInfo(double x, double y, double z, float yaw, float pitch, String dimension, float health, String gameMode) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
        this.health = health;
        this.gameMode = gameMode;
    }
}
