package com.yiming.mcp_host;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yiming.mcp_host.cli.Repl;
import com.yiming.mcp_host.config.HostConfig;
import com.yiming.mcp_host.llm.LLMBridge;
import com.yiming.mcp_host.mcp.McpClient;
import java.nio.charset.StandardCharsets;

public class McpHostApplication {

    public static void main(String[] args) {
        // 强制 UTF-8 输出
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        // 解析 CLI 参数
        String apiKey = null;
        String baseUrl = null;
        String model = null;
        String configPath = null;
        String launchConfigPath = "run/mcp-launch.json";
        boolean noLaunch = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--api-key" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--api-key 缺少参数值");
                    apiKey = args[i];
                    System.err.println("[警告] 通过命令行传入 API Key 存在安全风险（其他进程可能通过 ps 命令看到），建议使用环境变量 DEEPSEEK_API_KEY");
                }
                case "--base-url" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--base-url 缺少参数值");
                    baseUrl = args[i];
                }
                case "--model" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--model 缺少参数值");
                    model = args[i];
                }
                case "--config" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--config 缺少参数值");
                    configPath = args[i];
                }
                case "--launch-config" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("--launch-config 缺少参数值");
                    launchConfigPath = args[i];
                }
                case "--no-launch" -> noLaunch = true;
            }
        }

        try {
            HostConfig config = (configPath != null)
                    ? HostConfig.fromFile(configPath)
                    : new HostConfig(apiKey, baseUrl, model, null, null);

            McpClient mcpClient = new McpClient(launchConfigPath, noLaunch, config.getMcpTimeoutSeconds());
            Runtime.getRuntime().addShutdownHook(new Thread(mcpClient::stop));

            // --no-launch 模式也必须 start() 来设置 running=true
            mcpClient.start();

            JsonArray tools = null;
            String gameVersion = "unknown";
            if (!noLaunch) {
                System.out.println("[MCP-Host] 已连接到 MCP Server");

                JsonObject initResult = mcpClient.initialize();
                if (initResult != null && initResult.has("serverInfo")) {
                    JsonObject si = initResult.getAsJsonObject("serverInfo");
                    gameVersion = si.has("gameVersion") ? si.get("gameVersion").getAsString() : "unknown";
                }
                System.out.println("[MCP-Host] 握手完成, Minecraft 版本: " + gameVersion);

                tools = mcpClient.listTools();
                System.out.println("[MCP-Host] 发现 " + tools.size() + " 个工具");
            } else {
                System.out.println("[MCP-Host] --no-launch 模式：跳过 MCP 握手（工具不可用）");
                tools = new JsonArray();
            }

            LLMBridge llmBridge = new LLMBridge(config, mcpClient, tools, gameVersion);
            new Repl(llmBridge, mcpClient).run();
        } catch (Exception e) {
            System.err.println("[MCP-Host] 启动失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
