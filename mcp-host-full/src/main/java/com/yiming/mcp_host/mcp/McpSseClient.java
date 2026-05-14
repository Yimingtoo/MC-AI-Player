package com.yiming.mcp_host.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 传输层的 MCP 客户端。
 * 通过 HTTP SSE 连接 MCP Server，替代原先的子进程 stdio 通信。
 */
public class McpSseClient implements AutoCloseable, McpToolExecutor {

    private final String baseUrl;
    private final long timeoutSeconds;
    private final HttpClient httpClient;
    private final AtomicLong requestId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private volatile String messageEndpoint;
    private final CountDownLatch endpointLatch = new CountDownLatch(1);
    private Thread sseThread;

    public McpSseClient(String baseUrl, long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---- Lifecycle ----

    public void start() throws Exception {
        running = true;
        sseThread = new Thread(this::runSseLoop, "mcp-sse-reader");
        sseThread.setDaemon(true);
        sseThread.start();

        // 等待首次 endpoint 事件（最长 15 秒）
        if (!endpointLatch.await(15, TimeUnit.SECONDS)) {
            throw new IOException("SSE 连接超时，未收到 endpoint 事件");
        }
    }

    public void stop() {
        running = false;
        if (sseThread != null) {
            sseThread.interrupt();
            sseThread = null;
        }
        for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pending.entrySet()) {
            entry.getValue().cancel(true);
        }
        pending.clear();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    // ---- SSE 事件循环（自动重连） ----

    private void runSseLoop() {
        while (running) {
            try {
                connectAndReadSse();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) break;
                System.err.println("[McpSseClient] SSE 连接断开，2 秒后重连... (" + e.getMessage() + ")");
                // 连接断开 → 取消所有挂起请求（它们不会再有响应）
                messageEndpoint = null;
                for (Map.Entry<Long, CompletableFuture<JsonObject>> entry : pending.entrySet()) {
                    entry.getValue().cancel(true);
                }
                pending.clear();
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void connectAndReadSse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();

        String line;
        while (running && (line = reader.readLine()) != null) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (currentData.length() > 0) currentData.append("\n");
                currentData.append(line.substring(5).trim());
            } else if (line.isEmpty() && currentEvent != null) {
                // 一个 SSE 事件结束
                String data = currentData.toString();
                switch (currentEvent) {
                    case "endpoint" -> {
                        messageEndpoint = data;
                        endpointLatch.countDown();
                    }
                    case "message" -> handleSseMessage(data);
                }
                currentEvent = null;
                currentData = new StringBuilder();
            }
            // ":" 开头的行是注释（keepalive），忽略
        }
    }

    private void handleSseMessage(String data) {
        try {
            JsonObject json = JsonParser.parseString(data).getAsJsonObject();
            if (json.has("id") && !json.get("id").isJsonNull()) {
                long id = json.get("id").getAsLong();
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) {
                    future.complete(json);
                }
            }
        } catch (Exception e) {
            System.err.println("[McpSseClient] SSE 消息解析失败: " + e.getMessage());
        }
    }

    // ---- MCP 协议方法 ----

    private JsonObject sendRequest(String method, JsonObject params) throws Exception {
        String endpoint = resolveEndpoint();

        long id = requestId.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params != null ? params : new JsonObject());

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            httpClient.send(httpReq, HttpResponse.BodyHandlers.discarding());

            // 等待 SSE 推送的响应
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

    private String resolveEndpoint() throws Exception {
        String ep = messageEndpoint;
        if (ep == null) {
            // 等待 endpoint（最多 timeoutSeconds）
            if (!endpointLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("SSE endpoint 未就绪");
            }
            ep = messageEndpoint;
        }
        if (ep.startsWith("http://") || ep.startsWith("https://")) return ep;
        return baseUrl + (ep.startsWith("/") ? ep : "/" + ep);
    }

    public JsonObject initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "mcp-host");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);

        // 发送 initialized 通知（无响应）
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/initialized");
        notification.add("params", new JsonObject());

        try {
            String endpoint = resolveEndpoint();
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(notification.toString()))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            httpClient.send(httpReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[McpSseClient] 发送 initialized 通知失败: " + e.getMessage());
        }

        return result;
    }

    public JsonArray listTools() throws Exception {
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
}
