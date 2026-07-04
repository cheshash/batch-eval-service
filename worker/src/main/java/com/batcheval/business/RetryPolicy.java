package com.batcheval.business;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/** Business layer — retry backoff for rate-limited prompt calls. */
public final class RetryPolicy {

    private RetryPolicy() {}

    public static double backoffDelaySeconds(
            int attempt,
            HttpResponse<?> response,
            double baseDelaySeconds,
            double maxDelaySeconds,
            DoubleSupplier jitterSource
    ) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Math.min(Double.parseDouble(retryAfter.get()), maxDelaySeconds);
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        double base = Math.min(baseDelaySeconds * Math.pow(2, attempt - 1), maxDelaySeconds);
        double jitter = jitterSource.getAsDouble() * base * 0.25;
        return base + jitter;
    }
}
