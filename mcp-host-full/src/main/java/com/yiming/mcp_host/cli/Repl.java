package com.yiming.mcp_host.cli;

import com.yiming.mcp_host.agent.Agent;
import com.yiming.mcp_host.llm.TaskCancelledException;
import com.yiming.mcp_host.mcp.McpToolExecutor;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;

import java.util.Scanner;

public class Repl {

    private static final String PROMPT = "mcai> ";

    private final Agent agent;
    private final McpToolExecutor mcpClient;
    private final boolean useScanner;
    private final LineReader reader;
    private final Scanner scanner;

    private volatile Thread workerThread;
    private volatile boolean taskRunning;

    public Repl(Agent agent, McpToolExecutor mcpClient) {
        this.agent = agent;
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
        System.out.println("MCP-Host Full REPL — 输入 .help 查看命令, .exit 退出");
        System.out.println("提示: .cancel 可中断正在执行的任务");
        showPrompt();

        while (mcpClient.isRunning()) {
            String line;
            try {
                if (useScanner) {
                    line = scanner.hasNextLine() ? scanner.nextLine() : null;
                    if (line == null) break;
                } else {
                    line = reader.readLine();
                }
            } catch (UserInterruptException e) {
                Thread w = workerThread;
                if (w != null && w.isAlive()) {
                    agent.cancel();
                    w.interrupt();
                    System.out.println("\n任务已中断");
                    showPrompt();
                }
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null || line.isBlank()) {
                if (!taskRunning) showPrompt();
                continue;
            }

            if (line.startsWith(".")) {
                handleBuiltin(line);
                if (!taskRunning) showPrompt();
            } else {
                handleUserInput(line);
                // 由 worker 线程在完成后打印提示符，此处跳过
            }
        }
    }

    private void handleBuiltin(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0]) {
            case ".help" -> printHelp();
            case ".cancel" -> {
                Thread w = workerThread;
                if (w != null && w.isAlive()) {
                    agent.cancel();
                    w.interrupt();
                    System.out.println("任务已中断");
                } else {
                    System.out.println("当前没有正在执行的任务");
                }
            }
            case ".exit" -> {
                System.out.println("再见！");
                agent.stop();
                Thread w = workerThread;
                if (w != null && w.isAlive()) {
                    try { w.join(3000); } catch (InterruptedException ignored) {}
                }
            }
            case ".tools" -> System.out.println(agent.listMcpTools());
            case ".model" -> System.out.println("当前模型: " + System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
            case ".todo" -> System.out.println(agent.getTodoStatus());
            case ".skills" -> System.out.println(agent.listSkills());
            case ".compact" -> agent.manualCompact();
            case ".clear" -> System.out.print("\033[H\033[2J");
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
        taskRunning = true;
        workerThread = new Thread(() -> {
            try {
                String response = agent.processUserInput(input);
                System.out.println("\n" + response);
            } catch (TaskCancelledException e) {
                System.out.println("\n任务已中断");
            } catch (Exception e) {
                System.err.println("\n错误: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                taskRunning = false;
                showPrompt();
            }
        }, "llm-worker");
        workerThread.start();
    }

    private void showPrompt() {
        System.out.print(PROMPT);
        System.out.flush();
    }

    private void printHelp() {
        System.out.println("""
                .help             显示此帮助
                .cancel           中断当前正在执行的任务
                .exit             退出程序
                .tools            列出可用工具
                .model            显示当前模型
                .todo             显示当前待办列表
                .skills           列出可用技能
                .compact           手动压缩对话历史
                .clear            清屏
                .history          显示历史命令""");
    }
}
