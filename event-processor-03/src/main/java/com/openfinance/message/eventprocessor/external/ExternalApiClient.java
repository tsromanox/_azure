package com.openfinance.message.eventprocessor.external;

import com.openfinance.message.eventprocessor.exception.ExternalApiException;
import com.openfinance.message.eventprocessor.model.TransformedEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalApiClient {

    private final WebClient.Builder webClientBuilder;
    private final RestTemplate restTemplate;

    @Value("${external.api.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${external.api.timeout:5000}")
    private int timeoutMillis;

    @CircuitBreaker(name = "external-api")
    @Retry(name = "external-api")
    public void processEvent(TransformedEvent event) {
        try {
            WebClient webClient = webClientBuilder
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            ApiRequest request = buildApiRequest(event);

            ApiResponse response = webClient
                    .post()
                    .uri("/events/process")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            Mono.error(new IllegalArgumentException("Client error: " + clientResponse.statusCode())))
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            Mono.error(new RuntimeException("Server error: " + clientResponse.statusCode())))
                    .bodyToMono(ApiResponse.class)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .block();

            log.info("External API processed event successfully. Event ID: {}, Response: {}",
                    event.getEventId(), response);

        } catch (Exception e) {
            log.error("Failed to call external API for event: {}", event.getEventId(), e);
            throw new ExternalApiException("External API call failed", e);
        }
    }

    public boolean isHealthy() {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/health",
                    HttpMethod.GET,
                    null,
                    String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("External API health check failed", e);
            return false;
        }
    }

    private ApiRequest buildApiRequest(TransformedEvent event) {
        return ApiRequest.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType().name())
                .aggregateId(event.getAggregateId())
                .payload(event.getPayload())
                .metadata(event.getMetadata())
                .correlationId(event.getCorrelationId())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiRequest {
        private String eventId;
        private String eventType;
        private String aggregateId;
        private Map<String, Object> payload;
        private Map<String, Object> metadata;
        private String correlationId;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiResponse {
        private String status;
        private String message;
        private Map<String, Object> data;
    }
}
