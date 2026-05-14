package com.yiming.mc_ai_player.client.executor;

import com.yiming.mc_ai_player.api.model.*;
import com.yiming.mc_ai_player.api.model.action.ExecuteCommandRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class CommandActionExecutor extends ActionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    public CommandActionExecutor() {
    }

    public ActionResponse handleExecute(ExecuteCommandRequest req) {
        if (!getConfig().enableCommands) {
            return ActionResponse.error(ErrorCode.OPERATION_DISABLED, "Command execution is disabled");
        }

        if (req == null || req.command == null || req.command.isEmpty()) {
            return ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "command is required");
        }

        // Check command against blacklist
        String normalizedCmd = req.command.startsWith("/") ? req.command : "/" + req.command;
        String rootCommand = normalizedCmd.split(" ")[0].toLowerCase();

        // Strip namespace prefix (e.g. "minecraft:op" -> "op") to prevent bypass
        String strippedRoot = rootCommand.contains(":")
            ? rootCommand.substring(rootCommand.indexOf(":") + 1)
            : rootCommand.substring(1);

        for (String prefix : getConfig().commandBlacklistPrefixes) {
            String normalizedPrefix = prefix.startsWith("/") ? prefix : "/" + prefix;
            String strippedPrefix = normalizedPrefix.contains(":")
                ? normalizedPrefix.substring(normalizedPrefix.indexOf(":") + 1)
                : normalizedPrefix.substring(1);
            if (rootCommand.equals(normalizedPrefix.toLowerCase()) || strippedRoot.equals(strippedPrefix.toLowerCase())) {
                return ActionResponse.error(ErrorCode.COMMAND_DISALLOWED,
                    "Command is blacklisted: " + rootCommand);
            }
        }

        String finalCmd = normalizedCmd;

        return runOnServerThread(server -> {
            ServerPlayerEntity player = getPlayer(server);
            if (player == null) return ActionResponse.error(ErrorCode.PLAYER_NOT_FOUND, "No player found");

            // Collect command output
            StringBuilder outputBuilder = new StringBuilder();
            ServerCommandSource source;

            if (req.asPlayer) {
                source = player.getCommandSource();
            } else {
                source = server.getCommandSource();
            }

            // Create a custom command source that captures output
            ServerCommandSource outputSource = source.withOutput(new CommandOutput() {
                @Override
                public void sendMessage(Text message) {
                    if (outputBuilder.length() > 0) outputBuilder.append("\n");
                    outputBuilder.append(message.getString());
                    // 将指令输出显示在游戏聊天中，带 [AI] 前缀
                    player.sendMessage(Text.literal("[AI] ").append(message));
                }

                @Override
                public boolean shouldReceiveFeedback() {
                    return true;
                }

                @Override
                public boolean shouldTrackOutput() {
                    return true;
                }

                @Override
                public boolean shouldBroadcastConsoleToOps() {
                    return false;
                }
            });

            try {
                server.getCommandManager().parseAndExecute(outputSource, finalCmd.substring(1));

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("command", finalCmd);
                data.put("result", outputBuilder.toString());
                return ActionResponse.ok(data);
            } catch (Exception e) {
                LOGGER.warn("Command execution failed: {}", finalCmd, e);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("command", finalCmd);
                data.put("result", outputBuilder.toString());
                data.put("error", e.getMessage());
                return ActionResponse.ok(data);
            }
        });
    }

}
