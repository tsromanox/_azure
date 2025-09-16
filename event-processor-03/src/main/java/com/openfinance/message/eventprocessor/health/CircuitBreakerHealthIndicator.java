package com.openfinance.message.eventprocessor.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component("circuitBreakers")
@RequiredArgsConstructor
@Slf4j
public class CircuitBreakerHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean anyOpen = false;
        boolean anyHalfOpen = false;

        for (CircuitBreaker circuitBreaker : circuitBreakerRegistry.getAllCircuitBreakers()) {
            Map<String, Object> cbDetails = new HashMap<>();
            CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
            CircuitBreaker.State state = circuitBreaker.getState();

            cbDetails.put("state", state.name());
            cbDetails.put("failureRate", String.format("%.2f%%", metrics.getFailureRate()));
            cbDetails.put("slowCallRate", String.format("%.2f%%", metrics.getSlowCallRate()));
            cbDetails.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
            cbDetails.put("failedCalls", metrics.getNumberOfFailedCalls());
            cbDetails.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
            cbDetails.put("slowCalls", metrics.getNumberOfSlowCalls());
            cbDetails.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());

            details.put(circuitBreaker.getName(), cbDetails);

            if (state == CircuitBreaker.State.OPEN) {
                anyOpen = true;
            } else if (state == CircuitBreaker.State.HALF_OPEN) {
                anyHalfOpen = true;
            }
        }

        Health.Builder builder = new Health.Builder();

        if (anyOpen) {
            builder.status("CIRCUIT_OPEN")
                    .withDetail("message", "One or more circuit breakers are OPEN");
        } else if (anyHalfOpen) {
            builder.status("DEGRADED")
                    .withDetail("message", "One or more circuit breakers are HALF_OPEN");
        } else {
            builder.up();
        }

        return builder.withDetails(details).build();
    }
}
