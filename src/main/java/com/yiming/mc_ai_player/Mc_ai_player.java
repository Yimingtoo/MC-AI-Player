package com.yiming.mc_ai_player;

import com.yiming.mc_ai_player.config.ModConfigManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mc_ai_player implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");
    public static ModConfigManager CONFIG_MANAGER = new ModConfigManager();

    @Override
    public void onInitialize() {
        CONFIG_MANAGER.load();
        LOGGER.info("MC_AI_Player initialized");
    }
}
