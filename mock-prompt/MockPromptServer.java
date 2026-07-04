import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Minimal mock prompt HTTP server for local/docker runs (no extra dependencies). */
public class MockPromptServer {

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(9000), 0);
        server.createContext("/v1/evaluate", MockPromptServer::handleEvaluate);
        server.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();
        System.out.println("Mock prompt server listening on :9000");
    }

    private static void handleEvaluate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"message\":\"method not allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.contains("429")) {
            write(exchange, 429, "{\"message\":\"rate limited\"}");
            return;
        }
        if (body.contains("\"400\"") || body.contains("400 error")) {
            write(exchange, 400, "{\"message\":\"bad request\"}");
            return;
        }
        if (body.contains("500")) {
            write(exchange, 500, "{\"message\":\"server error\"}");
            return;
        }
        String prompt = extractPrompt(body);
        write(exchange, 200, "{\"output\":\"Evaluated: " + escape(prompt.substring(0, Math.min(prompt.length(), 80))) + "\"}");
    }

    private static String extractPrompt(String body) {
        int idx = body.indexOf("\"prompt\"");
        if (idx < 0) {
            return body;
        }
        int start = body.indexOf('"', body.indexOf(':', idx) + 1) + 1;
        int end = body.indexOf('"', start);
        return end > start ? body.substring(start, end) : body;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
