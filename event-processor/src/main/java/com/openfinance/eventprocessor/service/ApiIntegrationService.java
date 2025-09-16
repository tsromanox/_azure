package com.openfinance.eventprocessor.service;


import com.empresa.eventprocessor.exception.PermanentException;
import com.empresa.eventprocessor.exception.TransientException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.eventprocessor.metrics.CustomMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiIntegrationService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CustomMetrics metrics;

    // Configurações
    @Value("${external-api.base-url}")
    private String baseUrl;

    @Value("${external-api.timeout-seconds:5}")
    private int timeoutSeconds;

    @Value("${external-api.max-retries:3}")
    private int maxRetries;

    @Value("${external-api.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("${external-api.api-key:#{null}}")
    private String apiKey;

    @Value("${external-api.batch.enabled:true}")
    private boolean batchEnabled;

    @Value("${external-api.batch.size:50}")
    private int batchSize;

    @Value("${external-api.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${external-api.rate-limit.requests-per-second:100}")
    private int requestsPerSecond;

    // Executor para operações assíncronas com Virtual Threads
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Rate limiting
    private final Semaphore rateLimiter = new Semaphore(100);
    private final ScheduledExecutorService rateLimitScheduler = Executors.newScheduledThreadPool(1);

    // Estatísticas
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Map<Integer, AtomicLong> responseCodeCounts = new ConcurrentHashMap<>();

    // Cache de fallback
    private final Map<String, CachedResponse> fallbackCache = new ConcurrentHashMap<>();

    // Inicialização
    public ApiIntegrationService(RestClient restClient, ObjectMapper objectMapper, CustomMetrics metrics) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;

        // Inicia rate limiter
        if (rateLimitEnabled) {
            rateLimitScheduler.scheduleAtFixedRate(
                    this::replenishRateLimiter, 0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Busca dados de enriquecimento - método principal com todas as proteções
     */
    @CircuitBreaker(name = "enrichmentApi", fallbackMethod = "enrichmentFallback")
    @Retry(name = "enrichmentRetry")
    public String fetchEnrichmentData(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new PermanentException("Chave de enriquecimento inválida");
        }

        // Rate limiting
        if (rateLimitEnabled && !tryAcquireRateLimit()) {
            throw new TransientException("Rate limit excedido");
        }

        long startTime = System.currentTimeMillis();
        activeRequests.incrementAndGet();
        totalRequests.incrementAndGet();

        try {
            log.debug("Buscando dados de enriquecimento para key: {}", key);

            EnrichmentRequest request = new EnrichmentRequest(key, generateRequestId());

            ResponseEntity<EnrichmentResponse> response = restClient.post()
                    .uri("/enrichment")
                    .headers(headers -> setHeaders(headers))
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        recordResponseCode(res.getStatusCode().value());
                        handleClientError(res.getStatusCode(), res.getHeaders());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        recordResponseCode(res.getStatusCode().value());
                        throw new TransientException("Erro no servidor: " + res.getStatusCode());
                    })
                    .toEntity(EnrichmentResponse.class);

            // Validação da resposta
            if (response.getBody() == null || response.getBody().data == null) {
                throw new PermanentException("Resposta vazia ou inválida da API");
            }

            // Registra sucesso
            long duration = System.currentTimeMillis() - startTime;
            recordSuccess(duration);

            // Salva no cache de fallback
            updateFallbackCache(key, response.getBody().data);

            log.debug("Enriquecimento obtido com sucesso para key: {} em {}ms", key, duration);

            return response.getBody().data;

        } catch (HttpClientErrorException e) {
            handleHttpClientError(e, key);
            throw e; // Re-throw para circuit breaker

        } catch (HttpServerErrorException e) {
            handleHttpServerError(e);
            throw new TransientException("Erro no servidor: " + e.getMessage(), e);

        } catch (ResourceAccessException e) {
            handleResourceAccessError(e);
            throw new TransientException("Erro de acesso ao recurso: " + e.getMessage(), e);

        } catch (Exception e) {
            failedRequests.incrementAndGet();
            log.error("Erro inesperado ao buscar enriquecimento", e);
            throw new TransientException("Erro inesperado: " + e.getMessage(), e);

        } finally {
            activeRequests.decrementAndGet();
        }
    }

    /**
     * Busca assíncrona usando RestClient com Virtual Threads
     */
    public CompletableFuture<String> fetchEnrichmentDataAsync(String key) {
        return CompletableFuture.supplyAsync(() -> fetchEnrichmentData(key), virtualExecutor)
                .exceptionally(throwable -> {
                    log.error("Erro na busca assíncrona para key: {}", key, throwable);
                    return enrichmentFallback(key, (Exception) throwable);
                });
    }

    /**
     * Busca em batch para múltiplas chaves
     */
    public Map<String, String> fetchBatchEnrichment(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        if (!batchEnabled || keys.size() <= 1) {
            // Processa individualmente se batch desabilitado ou lista muito pequena
            return processBatchSequentially(keys);
        }

        log.info("Processando batch de {} chaves", keys.size());
        Map<String, String> results = new ConcurrentHashMap<>();

        // Divide em sub-batches do tamanho configurado
        List<List<String>> batches = partition(keys, batchSize);

        // Processa batches em paralelo usando Virtual Threads
        List<CompletableFuture<Map<String, String>>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> processSingleBatch(batch), virtualExecutor))
                .collect(Collectors.toList());

        // Aguarda todos e combina resultados
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .forEach(results::putAll))
                .join();

        log.info("Batch processado: {}/{} com sucesso", results.size(), keys.size());

        return results;
    }

    /**
     * Busca múltiplas chaves em paralelo (alternativa ao batch)
     */
    public Map<String, CompletableFuture<String>> fetchMultipleAsync(List<String> keys) {
        Map<String, CompletableFuture<String>> futures = new HashMap<>();

        for (String key : keys) {
            futures.put(key, fetchEnrichmentDataAsync(key));
        }

        return futures;
    }

    /**
     * Health check da API externa
     */
    public CompletableFuture<Boolean> checkApiHealth() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ResponseEntity<HealthResponse> response = restClient.get()
                        .uri("/health")
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            log.debug("Health check retornou erro: {}", res.getStatusCode());
                        })
                        .toEntity(HealthResponse.class);

                boolean healthy = response.getStatusCode().is2xxSuccessful();

                if (healthy && response.getBody() != null) {
                    log.debug("API health: {}", response.getBody());
                }

                return healthy;

            } catch (Exception e) {
                log.warn("API health check falhou: {}", e.getMessage());
                return false;
            }
        }, virtualExecutor);
    }

    /**
     * Busca com timeout customizado
     */
    public String fetchWithTimeout(String key, Duration timeout) {
        CompletableFuture<String> future = fetchEnrichmentDataAsync(key);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TransientException("Timeout após " + timeout.toMillis() + "ms");
        } catch (Exception e) {
            throw new TransientException("Erro ao buscar com timeout", e);
        }
    }

    /**
     * Fallback method para Circuit Breaker
     */
    public String enrichmentFallback(String key, Exception ex) {
        log.warn("Circuit breaker ativado para key: {}. Usando fallback. Erro: {}",
                key, ex.getMessage());

        metrics.incrementCircuitBreakerFallbacks();

        // Tenta cache de fallback
        CachedResponse cached = fallbackCache.get(key);
        if (cached != null && !cached.isExpired()) {
            log.debug("Retornando valor do cache de fallback para key: {}", key);
            return cached.data;
        }

        // Retorna valor padrão
        return generateDefaultEnrichment(key);
    }

    // ==================== Métodos Auxiliares ====================

    private void setHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "EventProcessor/1.0");
        headers.set("X-Request-ID", UUID.randomUUID().toString());
        headers.set("X-Timestamp", Instant.now().toString());
        headers.set("X-Client-Version", "1.0.0");

        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
    }

    private void handleClientError(HttpStatusCode status, HttpHeaders headers) {
        if (status.value() == 404) {
            throw new PermanentException("Recurso não encontrado");
        } else if (status.value() == 401 || status.value() == 403) {
            throw new PermanentException("Erro de autenticação/autorização");
        } else if (status.value() == 400) {
            throw new PermanentException("Requisição inválida");
        } else if (status.value() == 429) {
            // Rate limit da API externa
            String retryAfter = headers.getFirst("Retry-After");
            long waitTime = retryAfter != null ? Long.parseLong(retryAfter) * 1000 : 60000;
            throw new TransientException("Rate limit da API. Retry após " + waitTime + "ms");
        }
    }

    private void handleHttpClientError(HttpClientErrorException e, String key) {
        failedRequests.incrementAndGet();
        recordResponseCode(e.getStatusCode().value());

        if (e.getStatusCode().value() == 404) {
            log.warn("Recurso não encontrado para key: {}. Usando default.", key);
        } else {
            log.error("Erro cliente HTTP {}: {}", e.getStatusCode(), e.getMessage());
        }
    }

    private void handleHttpServerError(HttpServerErrorException e) {
        failedRequests.incrementAndGet();
        recordResponseCode(e.getStatusCode().value());
        log.error("Erro servidor HTTP {}: {}", e.getStatusCode(), e.getMessage());
    }

    private void handleResourceAccessError(ResourceAccessException e) {
        failedRequests.incrementAndGet();

        if (e.getCause() instanceof SocketTimeoutException) {
            log.error("Timeout ao acessar API externa");
            metrics.incrementTransientErrors();
        } else {
            log.error("Erro de rede ao acessar API: {}", e.getMessage());
        }
    }

    private Map<String, String> processBatchSequentially(List<String> keys) {
        Map<String, String> results = new HashMap<>();

        for (String key : keys) {
            try {
                results.put(key, fetchEnrichmentData(key));
            } catch (Exception e) {
                log.warn("Falha ao processar key {}: {}", key, e.getMessage());
                results.put(key, generateDefaultEnrichment(key));
            }
        }

        return results;
    }

    private Map<String, String> processSingleBatch(List<String> batch) {
        try {
            BatchEnrichmentRequest request = new BatchEnrichmentRequest(
                    batch, generateRequestId());

            ResponseEntity<BatchEnrichmentResponse> response = restClient.post()
                    .uri("/enrichment/batch")
                    .headers(headers -> setHeaders(headers))
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        log.warn("Erro no batch: {}", res.getStatusCode());
                    })
                    .toEntity(BatchEnrichmentResponse.class);

            if (response.getBody() != null && response.getBody().results != null) {
                recordSuccess(0);

                // Atualiza cache de fallback com resultados
                response.getBody().results.forEach(this::updateFallbackCache);

                return response.getBody().results;
            }

        } catch (Exception e) {
            log.error("Erro ao processar batch", e);
        }

        // Fallback: processa individualmente
        return processBatchSequentially(batch);
    }

    private boolean tryAcquireRateLimit() {
        try {
            return rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void replenishRateLimiter() {
        int permits = requestsPerSecond - rateLimiter.availablePermits();
        if (permits > 0) {
            rateLimiter.release(Math.min(permits, requestsPerSecond));
        }
    }

    private void recordSuccess(long duration) {
        successfulRequests.incrementAndGet();
        if (duration > 0) {
            metrics.recordEnrichmentTime(duration);
        }
        recordResponseCode(200);
    }

    private void recordResponseCode(int code) {
        responseCodeCounts.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
    }

    private void updateFallbackCache(String key, String data) {
        fallbackCache.put(key, new CachedResponse(data, Instant.now()));

        // Limpa cache antigo se muito grande
        if (fallbackCache.size() > 10000) {
            cleanupFallbackCache();
        }
    }

    private void cleanupFallbackCache() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 hora
        fallbackCache.entrySet().removeIf(entry ->
                entry.getValue().timestamp.isBefore(cutoff));
    }

    private String generateDefaultEnrichment(String key) {
        return String.format(
                "{\"key\":\"%s\",\"enriched\":false,\"source\":\"fallback\",\"timestamp\":\"%s\"}",
                key, Instant.now());
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Retorna estatísticas do serviço
     */
    public ApiStatistics getStatistics() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();

        return new ApiStatistics(
                total,
                successful,
                failed,
                total > 0 ? (double) successful / total * 100 : 0,
                activeRequests.get(),
                new HashMap<>(responseCodeCounts),
                fallbackCache.size()
        );
    }

    /**
     * Limpa recursos ao destruir o serviço
     */
    public void shutdown() {
        try {
            log.info("Desligando ApiIntegrationService...");
            rateLimitScheduler.shutdown();
            virtualExecutor.shutdown();

            if (!virtualExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }

            if (!rateLimitScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                rateLimitScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupção durante shutdown", e);
        }
    }

    // ==================== Classes Internas ====================

    private record EnrichmentRequest(String key, String requestId) {}

    private record EnrichmentResponse(String key, String data, Map<String, Object> metadata) {}

    private record BatchEnrichmentRequest(List<String> keys, String requestId) {}

    private record BatchEnrichmentResponse(Map<String, String> results, List<String> failures) {}

    private record HealthResponse(String status, Map<String, Object> details) {}

    private static class CachedResponse {
        final String data;
        final Instant timestamp;

        CachedResponse(String data, Instant timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return timestamp.isBefore(Instant.now().minusSeconds(3600)); // 1 hora
        }
    }

    public record ApiStatistics(
            long totalRequests,
            long successfulRequests,
            long failedRequests,
            double successRate,
            int activeRequests,
            Map<Integer, AtomicLong> responseCodeDistribution,
            int fallbackCacheSize
    ) {}
}

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiIntegrationService {

    private final RestClient restClient;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CustomMetrics metrics;

    // Configurações
    @Value("${external-api.base-url}")
    private String baseUrl;

    @Value("${external-api.timeout-seconds:5}")
    private int timeoutSeconds;

    @Value("${external-api.max-retries:3}")
    private int maxRetries;

    @Value("${external-api.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("${external-api.api-key:#{null}}")
    private String apiKey;

    @Value("${external-api.batch.enabled:true}")
    private boolean batchEnabled;

    @Value("${external-api.batch.size:50}")
    private int batchSize;

    @Value("${external-api.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${external-api.rate-limit.requests-per-second:100}")
    private int requestsPerSecond;

    // Rate limiting
    private final Semaphore rateLimiter = new Semaphore(100);
    private final ScheduledExecutorService rateLimitScheduler = Executors.newScheduledThreadPool(1);

    // Estatísticas
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Map<Integer, AtomicLong> responseCodeCounts = new ConcurrentHashMap<>();

    // Cache de fallback
    private final Map<String, CachedResponse> fallbackCache = new ConcurrentHashMap<>();

    // Inicialização
    public ApiIntegrationService(RestClient restClient, WebClient webClient,
                                 ObjectMapper objectMapper, CustomMetrics metrics) {
        this.restClient = restClient;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;

        // Inicia rate limiter
        if (rateLimitEnabled) {
            rateLimitScheduler.scheduleAtFixedRate(
                    this::replenishRateLimiter, 0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Busca dados de enriquecimento - método principal com todas as proteções
     */
    @CircuitBreaker(name = "enrichmentApi", fallbackMethod = "enrichmentFallback")
    @Retry(name = "enrichmentRetry")
    @TimeLimiter(name = "enrichmentTimeLimiter")
    public String fetchEnrichmentData(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new PermanentException("Chave de enriquecimento inválida");
        }

        // Rate limiting
        if (rateLimitEnabled && !tryAcquireRateLimit()) {
            throw new TransientException("Rate limit excedido");
        }

        long startTime = System.currentTimeMillis();
        activeRequests.incrementAndGet();
        totalRequests.incrementAndGet();

        try {
            log.debug("Buscando dados de enriquecimento para key: {}", key);

            EnrichmentRequest request = new EnrichmentRequest(key, generateRequestId());

            ResponseEntity<EnrichmentResponse> response = restClient.post()
                    .uri("/enrichment")
                    .headers(this::setHeaders)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        recordResponseCode(res.getStatusCode().value());
                        handleClientError(res.getStatusCode(), res.getHeaders());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        recordResponseCode(res.getStatusCode().value());
                        throw new TransientException("Erro no servidor: " + res.getStatusCode());
                    })
                    .toEntity(EnrichmentResponse.class);

            // Validação da resposta
            if (response.getBody() == null || response.getBody().data == null) {
                throw new PermanentException("Resposta vazia ou inválida da API");
            }

            // Registra sucesso
            long duration = System.currentTimeMillis() - startTime;
            recordSuccess(duration);

            // Salva no cache de fallback
            updateFallbackCache(key, response.getBody().data);

            log.debug("Enriquecimento obtido com sucesso para key: {} em {}ms", key, duration);

            return response.getBody().data;

        } catch (HttpClientErrorException e) {
            handleHttpClientError(e, key);
            throw e; // Re-throw para circuit breaker

        } catch (HttpServerErrorException e) {
            handleHttpServerError(e);
            throw new TransientException("Erro no servidor: " + e.getMessage(), e);

        } catch (ResourceAccessException e) {
            handleResourceAccessError(e);
            throw new TransientException("Erro de acesso ao recurso: " + e.getMessage(), e);

        } catch (Exception e) {
            failedRequests.incrementAndGet();
            log.error("Erro inesperado ao buscar enriquecimento", e);
            throw new TransientException("Erro inesperado: " + e.getMessage(), e);

        } finally {
            activeRequests.decrementAndGet();
        }
    }

    /**
     * Busca assíncrona usando WebClient
     */
    public CompletableFuture<String> fetchEnrichmentDataAsync(String key) {
        return webClient.post()
                .uri("/enrichment")
                .headers(headers -> setHeaders(headers))
                .bodyValue(new EnrichmentRequest(key, generateRequestId()))
                .retrieve()
                .bodyToMono(EnrichmentResponse.class)
                .map(response -> response.data)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(RetryBackoffSpec.backoff(maxRetries, Duration.ofMillis(retryDelayMs)))
                .doOnSuccess(data -> recordSuccess(0))
                .doOnError(error -> handleAsyncError(error))
                .toFuture();
    }

    /**
     * Busca em batch para múltiplas chaves
     */
    public Map<String, String> fetchBatchEnrichment(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        if (!batchEnabled || keys.size() <= 1) {
            // Processa individualmente se batch desabilitado ou lista muito pequena
            return processBatchSequentially(keys);
        }

        log.info("Processando batch de {} chaves", keys.size());
        Map<String, String> results = new ConcurrentHashMap<>();

        // Divide em sub-batches do tamanho configurado
        List<List<String>> batches = partition(keys, batchSize);

        // Processa batches em paralelo
        List<CompletableFuture<Map<String, String>>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> processSingleBatch(batch)))
                .collect(Collectors.toList());

        // Aguarda todos e combina resultados
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .forEach(results::putAll))
                .join();

        log.info("Batch processado: {}/{} com sucesso", results.size(), keys.size());

        return results;
    }

    /**
     * Streaming de dados usando Server-Sent Events
     */
    public Flux<String> streamEnrichmentData(String key) {
        return webClient.get()
                .uri("/enrichment/stream/{key}", key)
                .headers(this::setHeaders)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(30))
                .doOnNext(data -> log.debug("Recebido evento do stream: {}", data))
                .doOnError(error -> log.error("Erro no stream", error))
                .onErrorResume(error -> Flux.empty());
    }

    /**
     * Health check da API externa
     */
    public CompletableFuture<Boolean> checkApiHealth() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ResponseEntity<Void> response = restClient.get()
                        .uri("/health")
                        .retrieve()
                        .toBodilessEntity();

                return response.getStatusCode().is2xxSuccessful();
            } catch (Exception e) {
                log.warn("API health check falhou: {}", e.getMessage());
                return false;
            }
        });
    }

    /**
     * Fallback method para Circuit Breaker
     */
    public String enrichmentFallback(String key, Exception ex) {
        log.warn("Circuit breaker ativado para key: {}. Usando fallback. Erro: {}",
                key, ex.getMessage());

        metrics.incrementCircuitBreakerFallbacks();

        // Tenta cache de fallback
        CachedResponse cached = fallbackCache.get(key);
        if (cached != null && !cached.isExpired()) {
            log.debug("Retornando valor do cache de fallback para key: {}", key);
            return cached.data;
        }

        // Retorna valor padrão
        return generateDefaultEnrichment(key);
    }

    // ==================== Métodos Auxiliares ====================

    private void setHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "EventProcessor/1.0");
        headers.set("X-Request-ID", UUID.randomUUID().toString());
        headers.set("X-Timestamp", Instant.now().toString());

        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
    }

    private void handleClientError(HttpStatusCode status, HttpHeaders headers) {
        if (status.value() == 404) {
            throw new PermanentException("Recurso não encontrado");
        } else if (status.value() == 401 || status.value() == 403) {
            throw new PermanentException("Erro de autenticação/autorização");
        } else if (status.value() == 400) {
            throw new PermanentException("Requisição inválida");
        } else if (status.value() == 429) {
            // Rate limit da API externa
            String retryAfter = headers.getFirst("Retry-After");
            long waitTime = retryAfter != null ? Long.parseLong(retryAfter) * 1000 : 60000;
            throw new TransientException("Rate limit da API. Retry após " + waitTime + "ms");
        }
    }

    private void handleHttpClientError(HttpClientErrorException e, String key) {
        failedRequests.incrementAndGet();
        recordResponseCode(e.getStatusCode().value());

        if (e.getStatusCode().value() == 404) {
            log.warn("Recurso não encontrado para key: {}. Usando default.", key);
        } else {
            log.error("Erro cliente HTTP {}: {}", e.getStatusCode(), e.getMessage());
        }
    }

    private void handleHttpServerError(HttpServerErrorException e) {
        failedRequests.incrementAndGet();
        recordResponseCode(e.getStatusCode().value());
        log.error("Erro servidor HTTP {}: {}", e.getStatusCode(), e.getMessage());
    }

    private void handleResourceAccessError(ResourceAccessException e) {
        failedRequests.incrementAndGet();

        if (e.getCause() instanceof SocketTimeoutException) {
            log.error("Timeout ao acessar API externa");
            metrics.incrementTransientErrors();
        } else {
            log.error("Erro de rede ao acessar API: {}", e.getMessage());
        }
    }

    private void handleAsyncError(Throwable error) {
        failedRequests.incrementAndGet();

        if (error instanceof WebClientResponseException) {
            WebClientResponseException wcre = (WebClientResponseException) error;
            recordResponseCode(wcre.getStatusCode().value());
            log.error("Erro async HTTP {}: {}", wcre.getStatusCode(), wcre.getMessage());
        } else {
            log.error("Erro async: {}", error.getMessage());
        }
    }

    private Map<String, String> processBatchSequentially(List<String> keys) {
        Map<String, String> results = new HashMap<>();

        for (String key : keys) {
            try {
                results.put(key, fetchEnrichmentData(key));
            } catch (Exception e) {
                log.warn("Falha ao processar key {}: {}", key, e.getMessage());
                results.put(key, generateDefaultEnrichment(key));
            }
        }

        return results;
    }

    private Map<String, String> processSingleBatch(List<String> batch) {
        try {
            BatchEnrichmentRequest request = new BatchEnrichmentRequest(
                    batch, generateRequestId());

            ResponseEntity<BatchEnrichmentResponse> response = restClient.post()
                    .uri("/enrichment/batch")
                    .headers(this::setHeaders)
                    .body(request)
                    .retrieve()
                    .toEntity(BatchEnrichmentResponse.class);

            if (response.getBody() != null && response.getBody().results != null) {
                return response.getBody().results;
            }

        } catch (Exception e) {
            log.error("Erro ao processar batch", e);
        }

        // Fallback: processa individualmente
        return processBatchSequentially(batch);
    }

    private boolean tryAcquireRateLimit() {
        try {
            return rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void replenishRateLimiter() {
        int permits = requestsPerSecond - rateLimiter.availablePermits();
        if (permits > 0) {
            rateLimiter.release(Math.min(permits, requestsPerSecond));
        }
    }

    private void recordSuccess(long duration) {
        successfulRequests.incrementAndGet();
        metrics.recordEnrichmentTime(duration);
        recordResponseCode(200);
    }

    private void recordResponseCode(int code) {
        responseCodeCounts.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
    }

    private void updateFallbackCache(String key, String data) {
        fallbackCache.put(key, new CachedResponse(data, Instant.now()));

        // Limpa cache antigo se muito grande
        if (fallbackCache.size() > 10000) {
            cleanupFallbackCache();
        }
    }

    private void cleanupFallbackCache() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 hora
        fallbackCache.entrySet().removeIf(entry ->
                entry.getValue().timestamp.isBefore(cutoff));
    }

    private String generateDefaultEnrichment(String key) {
        return String.format(
                "{\"key\":\"%s\",\"enriched\":false,\"source\":\"fallback\",\"timestamp\":\"%s\"}",
                key, Instant.now());
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Retorna estatísticas do serviço
     */
    public ApiStatistics getStatistics() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();

        return new ApiStatistics(
                total,
                successful,
                failed,
                total > 0 ? (double) successful / total * 100 : 0,
                activeRequests.get(),
                new HashMap<>(responseCodeCounts),
                fallbackCache.size()
        );
    }

    // ==================== Classes Internas ====================

    private record EnrichmentRequest(String key, String requestId) {}

    private record EnrichmentResponse(String key, String data, Map<String, Object> metadata) {}

    private record BatchEnrichmentRequest(List<String> keys, String requestId) {}

    private record BatchEnrichmentResponse(Map<String, String> results, List<String> failures) {}

    private static class CachedResponse {
        final String data;
        final Instant timestamp;

        CachedResponse(String data, Instant timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return timestamp.isBefore(Instant.now().minusSeconds(3600)); // 1 hora
        }
    }

    public record ApiStatistics(
            long totalRequests,
            long successfulRequests,
            long failedRequests,
            double successRate,
            int activeRequests,
            Map<Integer, AtomicLong> responseCodeDistribution,
            int fallbackCacheSize
    ) {}
}