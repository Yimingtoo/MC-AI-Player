package com.yiming.mcp_host.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class HostConfig {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxToolRoundtrips;
    private final int mcpTimeoutSeconds;

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int DEFAULT_MAX_TOOL_ROUNDTRIPS = 25;
    private static final int DEFAULT_MCP_TIMEOUT_SECONDS = 30;

    public HostConfig() {
        this(null, null, null, null, null);
    }

    public HostConfig(String apiKey, String baseUrl, String model,
                      Integer maxToolRoundtrips, Integer mcpTimeoutSeconds) {
        this.apiKey = apiKey != null ? apiKey : resolveApiKey();
        this.baseUrl = baseUrl != null ? baseUrl : resolveBaseUrl();
        this.model = model != null ? model : resolveModel();
        this.maxToolRoundtrips = maxToolRoundtrips != null ? maxToolRoundtrips : resolveMaxToolRoundtrips();
        this.mcpTimeoutSeconds = mcpTimeoutSeconds != null ? mcpTimeoutSeconds : resolveMcpTimeoutSeconds();

        if (this.maxToolRoundtrips < 1) {
            throw new IllegalArgumentException("maxToolRoundtrips 必须 >= 1，当前值: " + this.maxToolRoundtrips);
        }
        if (this.mcpTimeoutSeconds < 5) {
            throw new IllegalArgumentException("mcpTimeoutSeconds 必须 >= 5，当前值: " + this.mcpTimeoutSeconds);
        }
    }

    public static HostConfig fromFile(String configPath) throws IOException {
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            System.err.println("[HostConfig] 配置文件不存在: " + path.toAbsolutePath().normalize() + "，将使用默认配置");
            return new HostConfig();
        }
        String content = Files.readString(path);
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        String apiKey = getString(json, "apiKey");
        String baseUrl = getString(json, "baseUrl");
        String model = getString(json, "model");
        Integer maxToolRoundtrips = getInt(json, "maxToolRoundtrips");
        Integer mcpTimeoutSeconds = getInt(json, "mcpTimeoutSeconds");

        return new HostConfig(apiKey, baseUrl, model, maxToolRoundtrips, mcpTimeoutSeconds);
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public int getMaxToolRoundtrips() { return maxToolRoundtrips; }
    public int getMcpTimeoutSeconds() { return mcpTimeoutSeconds; }

    private static String resolveApiKey() {
        String env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) return env;
        throw new IllegalStateException("DEEPSEEK_API_KEY 未设置，请通过环境变量或配置文件提供");
    }

    private static String resolveBaseUrl() {
        String env = System.getenv("DEEPSEEK_BASE_URL");
        return (env != null && !env.isBlank()) ? env : DEFAULT_BASE_URL;
    }

    private static String resolveModel() {
        String env = System.getenv("DEEPSEEK_MODEL");
        return (env != null && !env.isBlank()) ? env : DEFAULT_MODEL;
    }

    private static int resolveMaxToolRoundtrips() {
        String env = System.getenv("MCP_MAX_ROUNDTRIPS");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_MAX_TOOL_ROUNDTRIPS;
    }

    private static int resolveMcpTimeoutSeconds() {
        String env = System.getenv("MCP_TIMEOUT_SECONDS");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_MCP_TIMEOUT_SECONDS;
    }

    private static String getString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static Integer getInt(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : null;
    }
}
