package com.openfinance.message.eventprocessor.service;

import com.openfinance.message.eventprocessor.model.EnrichedEvent;
import com.openfinance.message.eventprocessor.model.EventPayload;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
public class EnrichmentService {

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public EnrichmentService(WebClient webClient, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;

        // Configure Circuit Breaker
        this.circuitBreaker = CircuitBreaker.of("enrichment-api", CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(50)
                .build());

        // Configure Retry
        this.retry = Retry.of("enrichment-api", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(Exception.class)
                .build());

        // Register metrics
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("Circuit breaker state transition: {}", event));
    }

    public EnrichedEvent enrichEvent(EventPayload payload) {
        Supplier<EnrichedEvent> enrichmentSupplier = () -> {
            try {
                // Call external API
                Map<String, Object> enrichedData = webClient
                        .post()
                        .uri("/enrich")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block(Duration.ofSeconds(5));

                meterRegistry.counter("enrichment.api.success").increment();

                return EnrichedEvent.builder()
                        .id(payload.getId())
                        .tenantId(payload.getTenantId())
                        .eventType(payload.getEventType())
                        .originalData(payload.getData())
                        .enrichedData(enrichedData)
                        .originalTimestamp(payload.getTimestamp())
                        .enrichmentTimestamp(Instant.now())
                        .processorId(getProcessorId())
                        .build();

            } catch (Exception e) {
                meterRegistry.counter("enrichment.api.failures").increment();
                throw new RuntimeException("Enrichment failed", e);
            }
        };

        // Apply resilience patterns
        Supplier<EnrichedEvent> decoratedSupplier = CircuitBreaker
                .decorateSupplier(circuitBreaker, enrichmentSupplier);
        decoratedSupplier = Retry.decorateSupplier(retry, decoratedSupplier);

        return decoratedSupplier.get();
    }

    private String getProcessorId() {
        return System.getenv("HOSTNAME");
    }
}
