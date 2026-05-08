package com.yiming.mc_ai_player.config;

import java.util.List;

public class ModConfig {
    public int httpPort = 8123;
    public int maxRequestsPerSecond = 20;
    public int maxBuildVolume = 32768;
    public int maxQueryRange = 64;
    public boolean enableBlockOperations = true;
    public boolean enablePlayerMovement = true;
    public boolean enableCommands = true;
    public List<String> blockBlacklist = List.of("minecraft:bedrock", "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block");
    public List<String> commandBlacklistPrefixes = List.of("/op", "/deop", "/stop", "/ban", "/ban-ip", "/kick", "/whitelist", "/pardon");
    public List<String> allowedDimensions = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
}
