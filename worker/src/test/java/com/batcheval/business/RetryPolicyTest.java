package com.batcheval.business;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void usesRetryAfterHeaderWhenPresent() {
        HttpResponse<String> response = stubResponse(429, Map.of("Retry-After", List.of("2.5")));
        double delay = RetryPolicy.backoffDelaySeconds(1, response, 1.0, 60.0, RandomGenerator.getDefault()::nextDouble);
        assertThat(delay).isEqualTo(2.5);
    }

    @Test
    void exponentialBackoffWithJitterIsBounded() {
        HttpResponse<String> response = stubResponse(429, Map.of());
        double delay = RetryPolicy.backoffDelaySeconds(3, response, 1.0, 60.0, () -> 0.0);
        assertThat(delay).isEqualTo(4.0);
    }

    private static HttpResponse<String> stubResponse(int status, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            @Override public String body() { return ""; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.net.URI uri() { return java.net.URI.create("http://localhost"); }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
