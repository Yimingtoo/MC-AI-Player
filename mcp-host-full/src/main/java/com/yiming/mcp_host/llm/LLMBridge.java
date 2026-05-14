package com.yiming.mcp_host.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiming.mcp_host.config.HostConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LLM API HTTP 传输层。
 * 精简职责：仅负责与 LLM API 的 HTTP 通信，不管理对话状态或工具循环。
 */
public class LLMBridge {

    /** LLM API 请求超时（秒）。可通过环境变量 LLM_TIMEOUT_SECONDS 覆盖。 */
    private static final int LLM_TIMEOUT_SECONDS = Integer.parseInt(
            System.getenv().getOrDefault("LLM_TIMEOUT_SECONDS", "120"));

    private final HostConfig config;
    private final HttpClient httpClient;

    private volatile CompletableFuture<HttpResponse<String>> currentHttpFuture;

    public LLMBridge(HostConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 取消当前正在执行的 HTTP 请求。
     */
    public void cancel() {
        CompletableFuture<HttpResponse<String>> future = currentHttpFuture;
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 发送消息到 LLM API，返回响应中的 choices[0]。
     */
    public JsonObject send(List<JsonObject> messages, JsonArray tools) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.add("messages", toJsonArray(messages));
        body.add("tools", tools);
        body.addProperty("tool_choice", "auto");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(LLM_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> httpResponse;
        try {
            currentHttpFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            while (true) {
                try {
                    httpResponse = currentHttpFuture.get(1, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException e) {
                    if (currentHttpFuture.isCancelled()) {
                        throw new TaskCancelledException("任务已被用户中断");
                    }
                }
            }
        } catch (CancellationException e) {
            throw new TaskCancelledException("任务已被用户中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskCancelledException("任务已被用户中断");
        } finally {
            currentHttpFuture = null;
        }

        if (httpResponse.statusCode() != 200) {
            throw new RuntimeException("API 请求失败 [" + httpResponse.statusCode() + "]: " + httpResponse.body());
        }

        JsonObject jsonResponse = JsonParser.parseString(httpResponse.body()).getAsJsonObject();
        return jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
    }

    private static JsonArray toJsonArray(List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        for (JsonObject obj : list) {
            arr.add(obj);
        }
        return arr;
    }
}
