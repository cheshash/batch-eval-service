package com.batcheval.business;

import com.batcheval.config.AppConfig;
import com.batcheval.model.BatchOutputLine.RowError;
import com.batcheval.model.RowErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** Business layer — HTTP client for prompt evaluation with 429 retry. */
public class PromptClient {

    public static class PromptException extends Exception {
        private final RowError error;

        public PromptException(RowError error) {
            super(error.message());
            this.error = error;
        }

        public RowError error() {
            return error;
        }
    }

    public record PromptResult(Map<String, Object> response, long latencyMs) {}

    private final AppConfig config;
    private final HttpClient httpClient;
    private final Random random = ThreadLocalRandom.current();

    public PromptClient(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.promptTimeout())
                .build();
    }

    public PromptResult evaluate(String model, String prompt, Map<String, Object> metadata) throws PromptException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("metadata", metadata == null ? Map.of() : metadata);

        int attempts = 0;
        Integer lastStatus = null;

        while (attempts < config.maxRetryAttempts()) {
            attempts++;
            long start = System.nanoTime();
            HttpResponse<String> response;
            try {
                String body = com.batcheval.util.Json.mapper().writeValueAsString(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(config.promptEndpointUrl())
                        .timeout(config.promptTimeout())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException ex) {
                throw new PromptException(RowError.of(
                        RowErrorCode.CONNECTION_ERROR, ex.getMessage(), null, attempts));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PromptException(RowError.of(
                        RowErrorCode.INTERRUPTED, "request interrupted", null, attempts));
            }

            lastStatus = response.statusCode();
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            if (response.statusCode() == 429) {
                if (attempts >= config.maxRetryAttempts()) {
                    break;
                }
                sleep(RetryPolicy.backoffDelaySeconds(
                        attempts,
                        response,
                        config.retryBaseDelaySeconds(),
                        config.retryMaxDelaySeconds(),
                        () -> random.nextDouble()
                ));
                continue;
            }

            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new PromptException(RowError.of(
                        RowErrorCode.CLIENT_ERROR,
                        extractMessage(response),
                        response.statusCode(),
                        attempts
                ));
            }

            if (response.statusCode() >= 500) {
                throw new PromptException(RowError.of(
                        RowErrorCode.SERVER_ERROR,
                        extractMessage(response),
                        response.statusCode(),
                        attempts
                ));
            }

            return new PromptResult(parseJson(response.body()), latencyMs);
        }

        throw new PromptException(RowError.of(
                RowErrorCode.RATE_LIMIT_EXHAUSTED,
                "exceeded retry attempts for HTTP 429",
                lastStatus,
                attempts
        ));
    }

    private void sleep(double seconds) throws PromptException {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PromptException(RowError.of(
                    RowErrorCode.INTERRUPTED, "retry sleep interrupted", null, null));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String body) {
        try {
            JsonNode node = com.batcheval.util.Json.mapper().readTree(body);
            return com.batcheval.util.Json.mapper().convertValue(node, Map.class);
        } catch (Exception ex) {
            return Map.of("text", body);
        }
    }

    private String extractMessage(HttpResponse<String> response) {
        try {
            JsonNode node = com.batcheval.util.Json.mapper().readTree(response.body());
            if (node.has("message")) {
                return node.get("message").asText();
            }
            return node.toString();
        } catch (Exception ex) {
            String body = response.body();
            return body == null || body.isBlank() ? "HTTP " + response.statusCode() : body.substring(0, Math.min(body.length(), 500));
        }
    }
}
