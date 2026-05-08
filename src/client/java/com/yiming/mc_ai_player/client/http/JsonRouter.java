package com.yiming.mc_ai_player.client.http;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yiming.mc_ai_player.api.model.ActionResponse;
import com.yiming.mc_ai_player.api.model.ErrorCode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class JsonRouter implements HttpHandler {
    private static final Gson GSON = new Gson();
    private final Map<String, RouteEntry> routes = new HashMap<>();

    private record RouteEntry(String method, String path, HandlerFn handler) {}

    @FunctionalInterface
    public interface HandlerFn {
        ActionResponse handle(HttpExchange exchange, Map<String, String> params) throws Exception;
    }

    public JsonRouter get(String path, HandlerFn handler) {
        routes.put("GET:" + path, new RouteEntry("GET", path, handler));
        return this;
    }

    public JsonRouter post(String path, HandlerFn handler) {
        routes.put("POST:" + path, new RouteEntry("POST", path, handler));
        return this;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = extractPath(exchange.getRequestURI());
            String key = method + ":" + path;

            RouteEntry entry = routes.get(key);
            if (entry == null) {
                sendResponse(exchange, 404, ActionResponse.error(ErrorCode.INVALID_PARAMETERS, "Not found: " + method + " " + path));
                return;
            }

            Map<String, String> params = queryToMap(exchange.getRequestURI());
            ActionResponse result = entry.handler.handle(exchange, params);
            sendResponse(exchange, result.success ? 200 : 400, result);

        } catch (Exception e) {
            sendResponse(exchange, 500, ActionResponse.error(ErrorCode.INTERNAL_ERROR, e.getMessage()));
        }
    }

    private String extractPath(URI uri) {
        String p = uri.getPath();
        return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    }

    private Map<String, String> queryToMap(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return map;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            } else if (kv.length == 1) {
                map.put(kv[0], "");
            }
        }
        return map;
    }

    public static <T> T parseBody(HttpExchange exchange, Class<T> clazz) {
        try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read request body", e);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, ActionResponse response) throws IOException {
        byte[] json = response.toJson().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }
}
