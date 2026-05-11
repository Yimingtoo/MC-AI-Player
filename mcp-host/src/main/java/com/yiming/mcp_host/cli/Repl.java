package com.yiming.mcp_host.cli;

import com.yiming.mcp_host.llm.LLMBridge;
import com.yiming.mcp_host.mcp.McpToolExecutor;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.util.Scanner;

public class Repl {

    private static final String PROMPT = "mcai> ";

    private final LLMBridge llmBridge;
    private final McpToolExecutor mcpClient;
    private final boolean useScanner;
    private final LineReader reader;
    private final Scanner scanner;

    public Repl(LLMBridge llmBridge, McpToolExecutor mcpClient) {
        this.llmBridge = llmBridge;
        this.mcpClient = mcpClient;

        boolean useJLine = System.console() != null;
        LineReader r = null;
        if (useJLine) {
            try {
                r = LineReaderBuilder.builder()
                        .appName("MCP-Host")
                        .build();
            } catch (Exception e) {
                useJLine = false;
            }
        }
        this.reader = r;
        this.scanner = useJLine ? null : new Scanner(System.in);
        this.useScanner = !useJLine;
    }

    public void run() {
        System.out.println("MCP-Host REPL — 输入 .help 查看命令, .exit 退出");

        while (mcpClient.isRunning()) {
            String line;
            try {
                if (useScanner) {
                    System.out.print(PROMPT);
                    System.out.flush();
                    line = scanner.hasNextLine() ? scanner.nextLine() : null;
                    if (line == null) break;
                } else {
                    line = reader.readLine(PROMPT);
                }
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null || line.isBlank()) continue;

            if (line.startsWith(".")) {
                handleBuiltin(line);
            } else {
                handleUserInput(line);
            }
        }
    }

    private void handleBuiltin(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0]) {
            case ".help" -> printHelp();
            case ".exit" -> {
                System.out.println("再见！");
                mcpClient.stop();
            }
            case ".tools" -> listTools();
            case ".model" -> System.out.println("当前模型: " + System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
            case ".clear" -> {
                if (reader != null) {
                    try { reader.getHistory().purge(); } catch (IOException ignored) {}
                }
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
            case ".history" -> {
                if (reader != null) {
                    reader.getHistory().forEach(e -> System.out.println(e.line()));
                } else {
                    System.out.println("历史记录不可用（当前使用 Scanner 模式）");
                }
            }
            default -> System.out.println("未知命令: " + cmd + " — 输入 .help 查看可用命令");
        }
    }

    private void handleUserInput(String input) {
        try {
            String response = llmBridge.processUserInput(input);
            System.out.println("\n" + response);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
        }
    }

    private void printHelp() {
        System.out.println("""
                .help             显示此帮助
                .exit             退出程序
                .tools            列出可用工具
                .model            显示当前模型
                .clear            清屏并清除历史
                .history          显示历史命令""");
    }

    private void listTools() {
        try {
            var tools = mcpClient.listTools();
            System.out.println("可用工具 (" + tools.size() + "):");
            for (var elem : tools) {
                var tool = elem.getAsJsonObject();
                System.out.println("  - " + tool.get("name").getAsString()
                        + (tool.has("description") ? ": " + tool.get("description").getAsString() : ""));
            }
        } catch (Exception e) {
            System.err.println("获取工具列表失败: " + e.getMessage());
        }
    }
}
