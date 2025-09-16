package com.openfinance.message.eventprocessor.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);

    private final RestClient restClient;
    private final WebClient webClient;
    private final String apiBaseUrl;

    public ExternalApiClient(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${external-api.base-url}") String apiBaseUrl,
            @Value("${external-api.timeout:10s}") Duration timeout) {

        this.apiBaseUrl = apiBaseUrl;

        // Configuração do RestClient (síncrono)
        this.restClient = restClientBuilder
                .baseUrl(apiBaseUrl)
                .defaultHeader("User-Agent", "EventHub-Processor/1.0")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            throw new ApiClientException("Client error: " + response.getStatusCode());
                        })
                .defaultStatusHandler(HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new ApiServerException("Server error: " + response.getStatusCode());
                        })
                .build();

        // Configuração do WebClient (reativo)
        this.webClient = webClientBuilder
                .baseUrl(apiBaseUrl)
                .defaultHeader("User-Agent", "EventHub-Processor-Reactive/1.0")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1MB
                .build();
    }

    /**
     * Chamada síncrona usando RestClient
     * Ideal para processamento sequencial e casos onde simplicidade é prioritária
     */
    @CircuitBreaker(name = "external-api", fallbackMethod = "fallbackGetEnrichmentData")
    @Retry(name = "external-api")
    @Bulkhead(name = "external-api-pool", type = Bulkhead.Type.THREADPOOL)
    public String getEnrichmentData(String eventId) {
        log.debug("Calling external API synchronously for event: {}", eventId);

        try {
            ApiResponse response = restClient.get()
                    .uri("/enrichment/{eventId}", eventId)
                    .retrieve()
                    .body(ApiResponse.class);

            log.debug("External API response for event {}: {}", eventId, response.getStatus());
            return response.getData();

        } catch (Exception e) {
            log.error("Failed to call external API for event: {}", eventId, e);
            throw e;
        }
    }

    /**
     * Chamada assíncrona usando WebClient (Reativo)
     * Ideal para alto throughput e processamento não-bloqueante
     */
    @CircuitBreaker(name = "external-api", fallbackMethod = "fallbackGetEnrichmentDataReactive")
    @Retry(name = "external-api")
    public Mono<String> getEnrichmentDataReactive(String eventId) {
        log.debug("Calling external API reactively for event: {}", eventId);

        return webClient.get()
                .uri("/enrichment/{eventId}", eventId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                        response -> {
                            log.warn("Client error for event {}: {}", eventId, response.statusCode());
                            return Mono.error(new ApiClientException("Client error: " + response.statusCode()));
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        response -> {
                            log.warn("Server error for event {}: {}", eventId, response.statusCode());
                            return Mono.error(new ApiServerException("Server error: " + response.statusCode()));
                        })
                .bodyToMono(ApiResponse.class)
                .map(ApiResponse::getData)
                .timeout(Duration.ofSeconds(10))
                .doOnSuccess(data -> log.debug("External API reactive response for event {}: success", eventId))
                .doOnError(error -> log.error("Failed reactive call to external API for event: {}", eventId, error));
    }

    /**
     * Wrapper para converter chamada reativa em CompletableFuture
     * Útil para integração com código não-reativo
     */
    @CircuitBreaker(name = "external-api")
    @Retry(name = "external-api")
    public CompletableFuture<String> getEnrichmentDataAsync(String eventId) {
        return getEnrichmentDataReactive(eventId).toFuture();
    }

    /**
     * Chamada batch usando WebClient para múltiplos eventos
     * Otimizada para processamento em lote
     */
    public Mono<BatchApiResponse> getEnrichmentDataBatch(String[] eventIds) {
        log.debug("Calling external API batch for {} events", eventIds.length);

        BatchApiRequest request = new BatchApiRequest();
        request.setEventIds(eventIds);

        return webClient.post()
                .uri("/enrichment/batch")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(BatchApiResponse.class)
                .timeout(Duration.ofSeconds(30))
                .doOnSuccess(response ->
                        log.debug("Batch API response for {} events: {} results",
                                eventIds.length, response.getResults().size()))
                .doOnError(error ->
                        log.error("Failed batch call to external API for {} events", eventIds.length, error));
    }

    /**
     * Health check da API externa
     */
    public boolean isApiHealthy() {
        try {
            HealthCheckResponse response = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(HealthCheckResponse.class);

            return "UP".equals(response.getStatus());

        } catch (Exception e) {
            log.warn("Health check failed for external API", e);
            return false;
        }
    }

    /**
     * Health check reativo da API externa
     */
    public Mono<Boolean> isApiHealthyReactive() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(HealthCheckResponse.class)
                .map(response -> "UP".equals(response.getStatus()))
                .onErrorReturn(false)
                .timeout(Duration.ofSeconds(5));
    }

    // Fallback methods
    public String fallbackGetEnrichmentData(String eventId, Exception ex) {
        log.warn("Using fallback for synchronous API call for event {}: {}", eventId, ex.getMessage());
        return createFallbackData(eventId);
    }

    public Mono<String> fallbackGetEnrichmentDataReactive(String eventId, Exception ex) {
        log.warn("Using fallback for reactive API call for event {}: {}", eventId, ex.getMessage());
        return Mono.just(createFallbackData(eventId));
    }

    private String createFallbackData(String eventId) {
        return String.format("{\"eventId\":\"%s\",\"source\":\"fallback\",\"timestamp\":\"%s\"}",
                eventId, java.time.Instant.now().toString());
    }

    // DTOs para API
    public static class ApiResponse {
        private String status;
        private String data;
        private String timestamp;

        // getters, setters, constructors
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }

    public static class BatchApiRequest {
        private String[] eventIds;

        public String[] getEventIds() { return eventIds; }
        public void setEventIds(String[] eventIds) { this.eventIds = eventIds; }
    }

    public static class BatchApiResponse {
        private java.util.Map<String, String> results;
        private String status;

        public java.util.Map<String, String> getResults() { return results; }
        public void setResults(java.util.Map<String, String> results) { this.results = results; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class HealthCheckResponse {
        private String status;
        private String timestamp;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }

    // Exceções customizadas
    public static class ApiClientException extends RuntimeException {
        public ApiClientException(String message) { super(message); }
        public ApiClientException(String message, Throwable cause) { super(message, cause); }
    }

    public static class ApiServerException extends RuntimeException {
        public ApiServerException(String message) { super(message); }
        public ApiServerException(String message, Throwable cause) { super(message, cause); }
    }

}
