package com.yiming.mc_ai_player.api;

public class ApiConstants {
    public static final String API_PREFIX = "/api";

    // Health & capabilities
    public static final String HEALTH = "/api/health";
    public static final String CAPABILITIES = "/api/capabilities";

    // Player
    public static final String PLAYER_POSITION = "/api/player/position";
    public static final String PLAYER_MOVE = "/api/player/move";
    public static final String PLAYER_LOOK = "/api/player/look";
    public static final String PLAYER_SEND_CHAT = "/api/player/send-chat";
    public static final String PLAYER_JUMP = "/api/player/jump";
    public static final String PLAYER_INVENTORY = "/api/player/inventory";

    // World query
    public static final String WORLD_BLOCK = "/api/world/block";
    public static final String WORLD_BLOCKS = "/api/world/blocks";
    public static final String WORLD_PLAYER_BLOCKS = "/api/world/player-blocks";
    public static final String WORLD_BIOME = "/api/world/biome";
    public static final String WORLD_TIME = "/api/world/time";
    public static final String WORLD_ENTITIES = "/api/world/entities";

    // Block manipulation
    public static final String BLOCKS_SET = "/api/blocks/set";
    public static final String BLOCKS_FILL = "/api/blocks/fill";
    public static final String BLOCKS_REPLACE = "/api/blocks/replace";

    // Command
    public static final String COMMAND_EXECUTE = "/api/command/execute";
}
