package com.openfinance.eventprocessor.service;

import com.azure.messaging.eventhubs.EventData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.eventprocessor.cache.EnrichmentCacheService;
import com.openfinance.eventprocessor.metrics.CustomMetrics;
import com.openfinance.eventprocessor.model.EnrichedEvent;
import com.openfinance.eventprocessor.model.Event;
import com.openfinance.eventprocessor.producer.DLQProducer;
import com.openfinance.eventprocessor.producer.EventProducer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EventProcessingService {

    private final EnrichmentCacheService cacheService;
    private final ApiIntegrationService apiService;
    private final EventProducer eventProducer;
    private final DLQProducer dlqProducer;
    private final ObjectMapper objectMapper;
    private final CustomMetrics metrics;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    // Configurações
    @Value("${external-api.max-retries:3}")
    private int maxRetries;

    @Value("${external-api.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("${processing.batch.size:100}")
    private int batchSize;

    @Value("${processing.parallel.enabled:true}")
    private boolean parallelProcessingEnabled;

    // Estatísticas internas
    private final Map<String, AtomicInteger> errorCountByType = new ConcurrentHashMap<>();
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private String hostname;

    @Autowired
    public EventProcessingService(
            EnrichmentCacheService cacheService,
            ApiIntegrationService apiService,
            EventProducer eventProducer,
            DLQProducer dlqProducer,
            ObjectMapper objectMapper,
            CustomMetrics metrics,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {

        this.cacheService = cacheService;
        this.apiService = apiService;
        this.eventProducer = eventProducer;
        this.dlqProducer = dlqProducer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("enrichmentApi");
        this.retry = retryRegistry.retry("enrichmentRetry");
    }

    @PostConstruct
    public void init() {
        try {
            this.hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            this.hostname = "unknown";
        }
        log.info("EventProcessingService inicializado no host: {}", hostname);
    }

    /**
     * Processa um único evento
     */
    public void processEvent(EventData eventData) {
        long startTime = System.currentTimeMillis();
        String eventId = null;
        int retryCount = 0;

        try {
            // 1. Parse do evento
            Event event = parseEvent(eventData);
            eventId = event.getId();

            log.debug("Iniciando processamento do evento: {} da partição: {}",
                    eventId, event.getPartitionId());

            // 2. Validação
            validateEvent(event);

            // 3. Enriquecimento com retry e circuit breaker
            EnrichedEvent enrichedEvent = enrichEventWithRetry(event, retryCount);

            // 4. Transformação adicional se necessário
            enrichedEvent = applyBusinessRules(enrichedEvent);

            // 5. Publicação
            publishEnrichedEvent(enrichedEvent);

            // 6. Métricas de sucesso
            long processingTime = System.currentTimeMillis() - startTime;
            enrichedEvent.setProcessingTimeMs(processingTime);

            metrics.recordEventProcessingTime(processingTime);
            metrics.incrementSuccessfulEvents();
            totalProcessed.incrementAndGet();

            if (totalProcessed.get() % 1000 == 0) {
                log.info("Marcos: {} eventos processados com sucesso", totalProcessed.get());
            }

            log.debug("Evento {} processado com sucesso em {}ms", eventId, processingTime);

        } catch (PermanentException e) {
            // Erro permanente - vai direto para DLQ
            log.error("Erro permanente no evento {}: {}", eventId, e.getMessage());
            handlePermanentError(eventData, e, eventId);

        } catch (TransientException e) {
            // Erro transitório - pode tentar novamente
            log.warn("Erro transitório no evento {}: {}", eventId, e.getMessage());
            handleTransientError(eventData, e, eventId, retryCount);

        } catch (Exception e) {
            // Erro inesperado
            log.error("Erro inesperado no evento {}: ", eventId, e);
            handleUnexpectedError(eventData, e, eventId);
        }
    }

    /**
     * Processa batch de eventos em paralelo
     */
    public CompletableFuture<BatchProcessingResult> processBatch(List<EventData> events) {
        if (!parallelProcessingEnabled) {
            return processBatchSequential(events);
        }

        long startTime = System.currentTimeMillis();
        log.info("Processando batch de {} eventos em paralelo", events.size());

        List<CompletableFuture<ProcessingResult>> futures = events.stream()
                .map(event -> CompletableFuture.supplyAsync(() -> {
                    try {
                        processEvent(event);
                        return ProcessingResult.success(extractEventId(event));
                    } catch (Exception e) {
                        return ProcessingResult.failure(extractEventId(event), e);
                    }
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<ProcessingResult> results = futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());

                    long duration = System.currentTimeMillis() - startTime;
                    return analyzeBatchResults(results, duration);
                });
    }

    /**
     * Parse do evento com tratamento de erro
     */
    private Event parseEvent(EventData eventData) throws PermanentException {
        try {
            String eventBody = new String(eventData.getBody(), StandardCharsets.UTF_8);

            if (eventBody == null || eventBody.isEmpty()) {
                throw new PermanentException("Corpo do evento vazio");
            }

            Event event = objectMapper.readValue(eventBody, Event.class);

            // Adiciona metadados do Event Hub
            event.setPartitionId(eventData.getPartitionKey());
            event.setSequenceNumber(eventData.getSequenceNumber());
            event.setEnqueuedTime(eventData.getEnqueuedTime());

            // Adiciona properties se existirem
            if (eventData.getProperties() != null) {
                event.setProperties(new HashMap<>(eventData.getProperties()));
            }

            return event;

        } catch (JsonProcessingException e) {
            throw new PermanentException("Erro ao fazer parse do JSON: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PermanentException("Erro inesperado no parse: " + e.getMessage(), e);
        }
    }

    /**
     * Validação do evento
     */
    private void validateEvent(Event event) throws PermanentException {
        List<String> errors = new ArrayList<>();

        if (event.getId() == null || event.getId().trim().isEmpty()) {
            errors.add("ID do evento ausente ou vazio");
        }

        if (event.getEnrichmentKey() == null || event.getEnrichmentKey().trim().isEmpty()) {
            errors.add("Chave de enriquecimento ausente ou vazia");
        }

        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        // Validação de timestamp muito antigo (mais de 7 dias)
        if (event.getTimestamp().isBefore(Instant.now().minusSeconds(7 * 24 * 3600))) {
            errors.add("Evento muito antigo (> 7 dias)");
        }

        // Validação de dados específicos do negócio
        if (event.getData() != null && event.getData().length() > 1_000_000) {
            errors.add("Payload do evento muito grande (> 1MB)");
        }

        if (!errors.isEmpty()) {
            throw new PermanentException("Validação falhou: " + String.join(", ", errors));
        }
    }

    /**
     * Enriquece evento com retry e circuit breaker
     */
    private EnrichedEvent enrichEventWithRetry(Event event, int retryCount) throws TransientException {
        Supplier<String> enrichmentSupplier = () -> {
            try {
                // Primeiro tenta o cache
                String cachedData = cacheService.getFromCache(event.getEnrichmentKey());
                if (cachedData != null) {
                    metrics.recordCacheHit();
                    return cachedData;
                }

                metrics.recordCacheMiss();

                // Se não está no cache, busca da API
                String enrichmentData = apiService.fetchEnrichmentData(event.getEnrichmentKey());

                // Salva no cache para próximas requisições
                cacheService.putInCache(event.getEnrichmentKey(), enrichmentData);

                return enrichmentData;

            } catch (Exception e) {
                log.error("Erro ao buscar dados de enriquecimento", e);
                throw new RuntimeException(e);
            }
        };

        // Aplica circuit breaker e retry
        Supplier<String> decoratedSupplier = CircuitBreaker
                .decorateSupplier(circuitBreaker, enrichmentSupplier);

        decoratedSupplier = Retry.decorateSupplier(retry, decoratedSupplier);

        try {
            long startTime = System.currentTimeMillis();
            String enrichmentData = decoratedSupplier.get();
            long enrichmentTime = System.currentTimeMillis() - startTime;

            metrics.recordEnrichmentTime(enrichmentTime);

            return EnrichedEvent.builder()
                    .originalEvent(event)
                    .enrichmentData(enrichmentData)
                    .enrichedAt(Instant.now())
                    .processingNode(hostname)
                    .retryCount(retryCount)
                    .build();

        } catch (Exception e) {
            throw new TransientException("Falha ao enriquecer após " + retryCount + " tentativas", e);
        }
    }

    /**
     * Aplica regras de negócio ao evento enriquecido
     */
    private EnrichedEvent applyBusinessRules(EnrichedEvent event) {
        // Exemplo de transformações de negócio

        // 1. Adiciona categoria baseada em dados
        if (event.getEnrichmentData() != null) {
            event.setCategory(determineCategory(event.getEnrichmentData()));
        }

        // 2. Calcula prioridade
        event.setPriority(calculatePriority(event));

        // 3. Adiciona tags
        event.setTags(generateTags(event));

        // 4. Aplica mascaramento de dados sensíveis se necessário
        event = maskSensitiveData(event);

        return event;
    }

    /**
     * Publica evento enriquecido
     */
    private void publishEnrichedEvent(EnrichedEvent enrichedEvent) throws TransientException {
        try {
            long startTime = System.currentTimeMillis();

            eventProducer.sendEvent(enrichedEvent);

            long publishTime = System.currentTimeMillis() - startTime;
            metrics.recordPublishingTime(publishTime);
            metrics.incrementPublishedEvents();

            log.debug("Evento {} publicado com sucesso em {}ms",
                    enrichedEvent.getOriginalEvent().getId(), publishTime);

        } catch (Exception e) {
            throw new TransientException("Falha ao publicar evento enriquecido", e);
        }
    }

    /**
     * Trata erro permanente
     */
    private void handlePermanentError(EventData eventData, PermanentException e, String eventId) {
        try {
            errorCountByType.computeIfAbsent("permanent", k -> new AtomicInteger()).incrementAndGet();
            metrics.incrementPermanentErrors();
            totalFailed.incrementAndGet();

            dlqProducer.sendToDLQ(eventData, e, eventData.getPartitionKey(), 0);

            log.warn("Evento {} enviado para DLQ devido a erro permanente: {}",
                    eventId, e.getMessage());

        } catch (Exception dlqError) {
            log.error("CRÍTICO: Falha ao enviar evento {} para DLQ", eventId, dlqError);
            // Aqui você pode implementar um fallback adicional
            writeToEmergencyFile(eventData, e);
        }
    }

    /**
     * Trata erro transitório
     */
    private void handleTransientError(EventData eventData, TransientException e,
                                      String eventId, int retryCount) {
        errorCountByType.computeIfAbsent("transient", k -> new AtomicInteger()).incrementAndGet();
        metrics.incrementTransientErrors();

        if (retryCount < maxRetries) {
            log.info("Erro transitório no evento {}. Tentativa {} de {}",
                    eventId, retryCount + 1, maxRetries);

            // Aguarda antes de reprocessar
            try {
                Thread.sleep(retryDelayMs * (retryCount + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // Re-throw para não atualizar checkpoint
            throw e;
        } else {
            log.error("Evento {} falhou após {} tentativas", eventId, maxRetries);
            handlePermanentError(eventData,
                    new PermanentException("Máximo de tentativas excedido", e), eventId);
        }
    }

    /**
     * Trata erro inesperado
     */
    private void handleUnexpectedError(EventData eventData, Exception e, String eventId) {
        errorCountByType.computeIfAbsent("unexpected", k -> new AtomicInteger()).incrementAndGet();
        metrics.incrementUnexpectedErrors();

        // Analisa se o erro pode ser transitório
        if (isRetryableError(e)) {
            handleTransientError(eventData,
                    new TransientException("Erro possivelmente transitório", e), eventId, 0);
        } else {
            handlePermanentError(eventData,
                    new PermanentException("Erro inesperado não recuperável", e), eventId);
        }
    }

    /**
     * Determina se erro é passível de retry
     */
    private boolean isRetryableError(Exception e) {
        if (e == null) return false;

        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        return e instanceof java.net.SocketTimeoutException ||
                e instanceof java.net.ConnectException ||
                e instanceof java.io.IOException ||
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("temporarily unavailable") ||
                message.contains("503") ||
                message.contains("429");
    }

    /**
     * Processa batch sequencialmente
     */
    private CompletableFuture<BatchProcessingResult> processBatchSequential(List<EventData> events) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            List<ProcessingResult> results = new ArrayList<>();

            for (EventData event : events) {
                try {
                    processEvent(event);
                    results.add(ProcessingResult.success(extractEventId(event)));
                } catch (Exception e) {
                    results.add(ProcessingResult.failure(extractEventId(event), e));
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            return analyzeBatchResults(results, duration);
        });
    }

    /**
     * Analisa resultados do batch
     */
    private BatchProcessingResult analyzeBatchResults(List<ProcessingResult> results, long duration) {
        long successful = results.stream().filter(ProcessingResult::isSuccess).count();
        long failed = results.size() - successful;

        Map<String, List<String>> failuresByType = results.stream()
                .filter(r -> !r.isSuccess())
                .collect(Collectors.groupingBy(
                        r -> r.getError().getClass().getSimpleName(),
                        Collectors.mapping(ProcessingResult::getEventId, Collectors.toList())
                ));

        double successRate = results.isEmpty() ? 0 : (double) successful / results.size() * 100;

        log.info("Batch processado: {}/{} sucesso ({:.1f}%) em {}ms",
                successful, results.size(), successRate, duration);

        if (!failuresByType.isEmpty()) {
            log.warn("Falhas por tipo: {}", failuresByType);
        }

        return new BatchProcessingResult(
                results.size(),
                successful,
                failed,
                duration,
                successRate,
                failuresByType
        );
    }

    /**
     * Extrai ID do evento
     */
    private String extractEventId(EventData eventData) {
        try {
            Map<String, Object> properties = eventData.getProperties();
            if (properties != null && properties.containsKey("EventId")) {
                return properties.get("EventId").toString();
            }
            return "unknown-" + System.currentTimeMillis();
        } catch (Exception e) {
            return "error-extracting-id";
        }
    }

    /**
     * Determina categoria baseada nos dados
     */
    private String determineCategory(String enrichmentData) {
        // Implementar lógica de categorização
        if (enrichmentData.contains("premium")) {
            return "PREMIUM";
        } else if (enrichmentData.contains("standard")) {
            return "STANDARD";
        }
        return "BASIC";
    }

    /**
     * Calcula prioridade do evento
     */
    private int calculatePriority(EnrichedEvent event) {
        // Implementar lógica de priorização
        if ("PREMIUM".equals(event.getCategory())) {
            return 1;
        } else if ("STANDARD".equals(event.getCategory())) {
            return 2;
        }
        return 3;
    }

    /**
     * Gera tags para o evento
     */
    private Set<String> generateTags(EnrichedEvent event) {
        Set<String> tags = new HashSet<>();

        // Adiciona tags baseadas em regras
        if (event.getOriginalEvent().getTimestamp().isAfter(Instant.now().minusSeconds(3600))) {
            tags.add("recent");
        }

        if (event.getCategory() != null) {
            tags.add(event.getCategory().toLowerCase());
        }

        tags.add("processed-by-" + hostname);

        return tags;
    }

    /**
     * Mascara dados sensíveis
     */
    private EnrichedEvent maskSensitiveData(EnrichedEvent event) {
        // Implementar mascaramento conforme necessário
        return event;
    }

    /**
     * Fallback de emergência - escreve em arquivo
     */
    private void writeToEmergencyFile(EventData eventData, Exception error) {
        try {
            String filename = String.format("/tmp/dlq-emergency-%d.json", System.currentTimeMillis());
            Map<String, Object> emergency = Map.of(
                    "event", new String(eventData.getBody(), StandardCharsets.UTF_8),
                    "error", error.getMessage(),
                    "timestamp", Instant.now().toString()
            );

            java.nio.file.Files.writeString(
                    java.nio.file.Paths.get(filename),
                    objectMapper.writeValueAsString(emergency)
            );

            log.error("Evento salvo em arquivo de emergência: {}", filename);
        } catch (Exception e) {
            log.error("CRÍTICO: Falha total ao salvar evento", e);
        }
    }

    /**
     * Retorna estatísticas do serviço
     */
    public ServiceStatistics getStatistics() {
        return new ServiceStatistics(
                totalProcessed.get(),
                totalFailed.get(),
                new HashMap<>(errorCountByType),
                circuitBreaker.getState().toString(),
                metrics.getCacheHitRate()
        );
    }

    // Classes internas

    private static class ProcessingResult {
        private final String eventId;
        private final boolean success;
        private final Exception error;

        private ProcessingResult(String eventId, boolean success, Exception error) {
            this.eventId = eventId;
            this.success = success;
            this.error = error;
        }

        public static ProcessingResult success(String eventId) {
            return new ProcessingResult(eventId, true, null);
        }

        public static ProcessingResult failure(String eventId, Exception error) {
            return new ProcessingResult(eventId, false, error);
        }

        public String getEventId() { return eventId; }
        public boolean isSuccess() { return success; }
        public Exception getError() { return error; }
    }

    public static class BatchProcessingResult {
        private final int total;
        private final long successful;
        private final long failed;
        private final long durationMs;
        private final double successRate;
        private final Map<String, List<String>> failuresByType;

        public BatchProcessingResult(int total, long successful, long failed,
                                     long durationMs, double successRate,
                                     Map<String, List<String>> failuresByType) {
            this.total = total;
            this.successful = successful;
            this.failed = failed;
            this.durationMs = durationMs;
            this.successRate = successRate;
            this.failuresByType = failuresByType;
        }

        // Getters
        public int getTotal() { return total; }
        public long getSuccessful() { return successful; }
        public long getFailed() { return failed; }
        public long getDurationMs() { return durationMs; }
        public double getSuccessRate() { return successRate; }
        public Map<String, List<String>> getFailuresByType() { return failuresByType; }
    }

    public static class ServiceStatistics {
        private final long totalProcessed;
        private final long totalFailed;
        private final Map<String, AtomicInteger> errorsByType;
        private final String circuitBreakerState;
        private final double cacheHitRate;

        public ServiceStatistics(long totalProcessed, long totalFailed,
                                 Map<String, AtomicInteger> errorsByType,
                                 String circuitBreakerState, double cacheHitRate) {
            this.totalProcessed = totalProcessed;
            this.totalFailed = totalFailed;
            this.errorsByType = errorsByType;
            this.circuitBreakerState = circuitBreakerState;
            this.cacheHitRate = cacheHitRate;
        }

        // Getters
        public long getTotalProcessed() { return totalProcessed; }
        public long getTotalFailed() { return totalFailed; }
        public Map<String, AtomicInteger> getErrorsByType() { return errorsByType; }
        public String getCircuitBreakerState() { return circuitBreakerState; }
        public double getCacheHitRate() { return cacheHitRate; }
    }
}
