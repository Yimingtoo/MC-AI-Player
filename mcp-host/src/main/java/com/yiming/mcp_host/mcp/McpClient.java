package com.yiming.mcp_host.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class McpClient implements AutoCloseable, McpToolExecutor {

    private final String launchConfigPath;
    private final boolean noLaunch;
    private final long timeoutSeconds;

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicLong requestId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private volatile boolean running;

    public McpClient(String launchConfigPath, boolean noLaunch, long timeoutSeconds) {
        this.launchConfigPath = launchConfigPath;
        this.noLaunch = noLaunch;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void start() throws IOException {
        if (noLaunch) {
            // --no-launch 模式：不占用 stdin/stdout（REPL 直接处理用户交互）
            running = true;
            return;
        } else {
            Path configFile = Path.of(launchConfigPath);
            if (!Files.exists(configFile)) {
                throw new IOException("启动配置文件不存在: " + launchConfigPath
                        + " (绝对路径: " + configFile.toAbsolutePath().normalize()
                        + ", 工作目录: " + System.getProperty("user.dir") + ")");
            }
            String content = Files.readString(configFile);
            JsonObject config = JsonParser.parseString(content).getAsJsonObject();

            if (!config.has("mainClass") || !config.has("classpath")) {
                throw new IOException("启动配置文件缺少必要字段: mainClass、classpath");
            }
            String mainClass = config.get("mainClass").getAsString();

            // classpath 可能是字符串（冒号/分号分隔）或 JSON 数组
            String classpath;
            if (config.get("classpath").isJsonArray()) {
                JsonArray arr = config.getAsJsonArray("classpath");
                StringBuilder sb = new StringBuilder();
                for (JsonElement e : arr) {
                    if (sb.length() > 0) sb.append(File.pathSeparator);
                    sb.append(e.getAsString());
                }
                classpath = sb.toString();
            } else {
                classpath = config.get("classpath").getAsString();
            }

            JsonArray jvmArgsArr = config.getAsJsonArray("jvmArgs");
            JsonArray programArgsArr = config.getAsJsonArray("programArgs");

            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("java");
            for (JsonElement e : jvmArgsArr) cmd.add(e.getAsString());
            cmd.add("-cp");
            cmd.add(classpath.toString());
            cmd.add(mainClass);
            for (JsonElement e : programArgsArr) cmd.add(e.getAsString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        }

        running = true;
        startReaderThread();
    }

    private void startReaderThread() {
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonObject json;
                    try {
                        json = JsonParser.parseString(line).getAsJsonObject();
                    } catch (Exception e) {
                        // 非 JSON-RPC 输出（如 Minecraft 日志），忽略
                        continue;
                    }

                    // 通知（无 id 字段）
                    if (!json.has("id")) {
                        handleNotification(json);
                        continue;
                    }

                    long id = json.get("id").getAsLong();
                    CompletableFuture<JsonObject> future = pending.remove(id);
                    if (future != null) {
                        future.complete(json);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[McpClient] 读取线程异常: " + e.getMessage());
                }
            }
        }, "mcp-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleNotification(JsonObject notification) {
        String method = notification.has("method") ? notification.get("method").getAsString() : "";
        if ("notifications/initialized".equals(method)) {
            // MCP Server 初始化完成通知
        }
    }

    private JsonObject sendRequest(String method, JsonObject params) throws Exception {
        long id = requestId.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        } else {
            request.add("params", new JsonObject());
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        synchronized (this) {
            writer.write(request.toString());
            writer.newLine();
            writer.flush();
        }

        try {
            JsonObject response = future.get(timeoutSeconds, TimeUnit.SECONDS);

            if (response.has("error")) {
                JsonObject err = response.getAsJsonObject("error");
                throw new RuntimeException("MCP 错误 [" + err.get("code") + "]: " + err.get("message"));
            }

            return response.getAsJsonObject("result");
        } finally {
            pending.remove(id);
        }
    }

    public JsonObject initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        JsonObject capabilities = new JsonObject();
        params.add("capabilities", capabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "mcp-host");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);

        // 发送 initialized 通知
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/initialized");
        notification.add("params", new JsonObject());

        // 同步发送 initialized 通知
        try {
            synchronized (this) {
                writer.write(notification.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("[McpClient] 发送 initialized 通知失败: " + e.getMessage());
        }

        return result;
    }

    public JsonArray listTools() throws Exception {
        if (noLaunch) {
            return new JsonArray();
        }
        JsonObject result = sendRequest("tools/list", null);
        if (result == null || !result.has("tools") || result.get("tools").isJsonNull()) {
            return new JsonArray();
        }
        return result.getAsJsonArray("tools");
    }

    public JsonObject callTool(String name, JsonObject arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        if (arguments != null) {
            params.add("arguments", arguments);
        }
        return sendRequest("tools/call", params);
    }

    public void stop() {
        running = false;
        // 取消所有挂起的请求
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pending.entrySet()) {
            entry.getValue().cancel(true);
        }
        pending.clear();

        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }
}
