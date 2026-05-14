package com.yiming.mc_ai_player.client.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yiming.mc_ai_player.client.executor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * MCP SSE 传输层服务器。
 * 使用 ServerSocket 直接管理连接，绕过 JDK HttpServer 在 Windows 上缓冲响应体的问题。
 *
 * 端点:
 *   GET /sse  — SSE 事件流
 *   POST /message?sessionId=<uuid> — 接收 JSON-RPC 请求
 */
public class McpSseServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_ai_player");

    public static final int DEFAULT_PORT = 9123;
    private static final long SSE_KEEPALIVE_MS = 30_000;

    private ServerSocket serverSocket;
    private final McpProtocolHandler handler;
    private final int port;
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final ThreadLocal<SseSession> requestSession = new ThreadLocal<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private volatile boolean running = false;
    private Thread acceptThread;

    public McpSseServer(
            int port,
            PlayerActionExecutor playerAction,
            WorldQueryExecutor worldQuery,
            BlockActionExecutor blockAction,
            CommandActionExecutor commandAction,
            ScanRegionExecutor scanRegion,
            MonitorRegionExecutor monitorRegion
    ) throws IOException {
        this.port = port;
        this.serverSocket = new ServerSocket(port);

        this.handler = new McpProtocolHandler(
                response -> {
                    SseSession session = requestSession.get();
                    if (session != null) {
                        session.outboundQueue.offer(response);
                    }
                },
                playerAction, worldQuery, blockAction, commandAction, scanRegion, monitorRegion
        );
    }

    // ---- Lifecycle ----

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mcp-sse-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOGGER.info("MCP SSE server started on port {}", port);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) {}
        for (SseSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        threadPool.shutdownNow();
        LOGGER.info("MCP SSE server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    // ---- Accept Loop ----

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    LOGGER.error("Accept failed", e);
                }
                break;
            }
        }
    }

    // ---- Connection Dispatcher ----

    private void handleConnection(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) return;

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 3) return;
            String method = parts[0];
            String uriStr = parts[1];

            // Read HTTP headers
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            URI uri = new URI(uriStr);
            String path = uri.getPath();

            if ("GET".equalsIgnoreCase(method) && "/sse".equals(path)) {
                handleSseConnection(out);
            } else if ("POST".equalsIgnoreCase(method) && "/message".equals(path)) {
                handlePostMessage(out, in, uri, contentLength);
            } else {
                byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
                sendHttpResponse(out, 404, "Not Found", "text/plain", body);
            }
        } catch (Exception e) {
            LOGGER.error("Error handling connection", e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ---- SSE Connection Handler ----

    private void handleSseConnection(OutputStream out) throws IOException {
        String sessionId = UUID.randomUUID().toString();
        SseSession session = new SseSession(sessionId, out);
        sessions.put(sessionId, session);

        // 手动发送 HTTP 响应（不使用 HttpServer，避免缓冲问题）
        String httpHeaders = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n\r\n";
        out.write(httpHeaders.getBytes(StandardCharsets.UTF_8));
        out.flush();

        writeSseEvent(out, "endpoint", "/message?sessionId=" + sessionId);
        LOGGER.info("SSE client connected: {}", sessionId);

        try {
            loopSseSession(sessionId, session, out);
        } catch (Exception e) {
            LOGGER.info("SSE client disconnected: {} - {}: {}", sessionId, e.getClass().getSimpleName(), e.getMessage());
        } finally {
            session.connected = false;
            sessions.remove(sessionId);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ---- POST Message Handler ----

    private void handlePostMessage(OutputStream out, InputStream in, URI uri, int contentLength) throws IOException {
        String sessionId = extractSessionId(uri);
        SseSession session = sessionId != null ? sessions.get(sessionId) : null;
        if (session == null) {
            byte[] err = "{\"error\":\"invalid session\"}".getBytes(StandardCharsets.UTF_8);
            sendHttpResponse(out, 400, "Bad Request", "application/json", err);
            return;
        }

        String body = readBytes(in, contentLength);
        JsonObject message = JsonParser.parseString(body).getAsJsonObject();

        // 立即返回 202 Accepted
        String respHeaders = "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n";
        out.write(respHeaders.getBytes(StandardCharsets.UTF_8));
        out.flush();

        requestSession.set(session);
        try {
            handler.handleMessage(message);
        } finally {
            requestSession.remove();
        }
    }

    // ---- Internal: SSE Session Loop ----

    private void loopSseSession(String sessionId, SseSession session, OutputStream out)
            throws IOException, InterruptedException {
        long lastKeepalive = System.currentTimeMillis();

        while (running && session.connected) {
            JsonObject response = session.outboundQueue.poll(1, TimeUnit.SECONDS);
            if (response != null) {
                writeSseEvent(out, "message", response.toString());
            }

            long now = System.currentTimeMillis();
            if (now - lastKeepalive > SSE_KEEPALIVE_MS) {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastKeepalive = now;
            }
        }
    }

    // ---- Utility Methods ----

    private static void writeSseEvent(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String extractSessionId(URI uri) {
        String query = uri.getQuery();
        if (query == null) return null;
        String prefix = "sessionId=";
        int idx = query.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = query.indexOf('&', start);
        return end < 0 ? query.substring(start) : query.substring(start, end);
    }

    private static void sendHttpResponse(OutputStream out, int statusCode, String statusText,
                                          String contentType, byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.mark(1);
                if (in.read() != '\n') {
                    in.reset();
                }
                break;
            } else if (b == '\n') {
                break;
            }
            sb.append((char) b);
        }
        return sb.length() > 0 || b != -1 ? sb.toString() : null;
    }

    private static String readBytes(InputStream in, int length) throws IOException {
        if (length <= 0) return "";
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(buf, off, length - off);
            if (read < 0) throw new IOException("Unexpected EOF reading HTTP body");
            off += read;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    // ---- Session State ----

    private static class SseSession {
        final String sessionId;
        final OutputStream outputStream;
        final BlockingQueue<JsonObject> outboundQueue = new LinkedBlockingQueue<>();
        volatile boolean connected = true;

        SseSession(String sessionId, OutputStream outputStream) {
            this.sessionId = sessionId;
            this.outputStream = outputStream;
        }

        void close() {
            connected = false;
            try { outputStream.close(); } catch (IOException ignored) {}
        }
    }
}
