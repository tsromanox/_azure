package com.openfinance.message.eventprocessor.service;

import com.company.eventhubprocessor.client.ExternalApiClient;
import com.company.eventhubprocessor.model.EventData;
import com.company.eventhubprocessor.model.ProcessedEvent;
import com.company.eventhubprocessor.repository.EventRepository;
import com.company.eventhubprocessor.repository.OffsetRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class EventProcessorService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessorService.class);

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private OffsetRepository offsetRepository;

    @Autowired
    private ExternalApiClient externalApiClient;

    @Autowired
    private EventSourcingService eventSourcingService;

    @Value("${event-processor.processing-mode:sync}")
    private String processingMode; // "sync", "async", "reactive"

    private final Counter processedEvents;
    private final Counter failedEvents;
    private final Timer processingTimer;

    public EventProcessorService(MeterRegistry meterRegistry) {
        this.processedEvents = Counter.builder("events.processed")
                .description("Total number of events processed")
                .register(meterRegistry);

        this.failedEvents = Counter.builder("events.failed")
                .description("Total number of failed events")
                .register(meterRegistry);

        this.processingTimer = Timer.builder("events.processing.duration")
                .description("Event processing duration")
                .register(meterRegistry);
    }

    @Async
    @Observed(name = "event.processing", contextualName = "process-event-async")
    @Bulkhead(name = "event-processor", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "fallbackProcessEvent")
    public CompletableFuture<Void> processEventAsync(EventData eventData) {
        return CompletableFuture.runAsync(() -> {
            Timer.Sample sample = Timer.start();
            try {
                processEvent(eventData);
                processedEvents.increment();
                sample.stop(processingTimer);
            } catch (Exception e) {
                failedEvents.increment();
                sample.stop(processingTimer);
                throw new RuntimeException("Failed to process event", e);
            }
        });
    }

    /**
     * Processamento reativo usando WebClient
     * Ideal para alto throughput e processamento não-bloqueante
     */
    @Observed(name = "event.processing.reactive", contextualName = "process-event-reactive")
    public Mono<Void> processEventReactive(EventData eventData) {
        log.debug("Processing event reactively: {}", eventData.getId());

        return Mono.fromCallable(() -> transformEvent(eventData))
                .doOnNext(this::validateEvent)
                .flatMap(this::enrichEventWithExternalDataReactive)
                .flatMap(this::saveEventReactive)
                .flatMap(eventSourcingService::publishEventReactive)
                .doOnSuccess(result -> {
                    processedEvents.increment();
                    log.info("Event processed successfully (reactive): {}", eventData.getId());
                })
                .doOnError(error -> {
                    failedEvents.increment();
                    log.error("Error processing event (reactive): {}", eventData.getId(), error);
                })
                .onErrorMap(throwable -> new RuntimeException("Failed to process event reactively", throwable))
                .then();
    }

    @CircuitBreaker(name = "database", fallbackMethod = "fallbackProcessEvent")
    @Retry(name = "database")
    public void processEvent(EventData eventData) {
        log.debug("Processing event: {}", eventData.getId());

        try {
            // 1. Transformação de dados
            ProcessedEvent processedEvent = transformEvent(eventData);

            // 2. Validação de business rules
            validateEvent(processedEvent);

            // 3. Enriquecimento com dados externos (modo configurável)
            if ("reactive".equals(processingMode)) {
                // Usar WebClient de forma síncrona quando necessário
                String enrichmentData = externalApiClient.getEnrichmentDataReactive(eventData.getId())
                        .block(); // Blocking só quando necessário em modo híbrido
                processedEvent.setEnrichmentData(enrichmentData);
            } else {
                // Usar RestClient síncrono
                enrichEventWithExternalData(processedEvent);
            }

            // 4. Persistência
            eventRepository.save(processedEvent);

            // 5. Event Sourcing
            eventSourcingService.publishEvent(processedEvent);

            log.info("Event processed successfully: {}", eventData.getId());

        } catch (Exception e) {
            log.error("Error processing event: {}", eventData.getId(), e);
            throw e;
        }
    }

    @Transactional
    public void processBatchTransactionally(List<EventData> events, List<Long> sequenceNumbers, List<String> partitionIds) {
        try {
            for (int i = 0; i < events.size(); i++) {
                EventData event = events.get(i);
                Long sequenceNumber = sequenceNumbers.get(i);
                String partitionId = partitionIds.get(i);

                // Verificar se já foi processado (idempotência para exactly-once)
                if (offsetRepository.isSequenceNumberProcessed(partitionId, sequenceNumber)) {
                    log.debug("Event already processed, skipping: partition={}, sequence={}",
                            partitionId, sequenceNumber);
                    continue;
                }

                // Processar evento
                processEvent(event);

                // Salvar sequence number processado
                offsetRepository.saveProcessedSequenceNumber(partitionId, sequenceNumber);
            }

        } catch (Exception e) {
            log.error("Error in batch processing", e);
            throw e; // Rollback da transação
        }
    }

    /**
     * Processamento batch reativo para alta performance
     */
    public Mono<Void> processBatchReactive(List<EventData> events) {
        log.debug("Processing batch of {} events reactively", events.size());

        return Mono.fromIterable(events)
                .flatMap(this::processEventReactive, 10) // Paralelismo de 10
                .then()
                .doOnSuccess(result ->
                        log.info("Batch of {} events processed successfully (reactive)", events.size()))
                .doOnError(error ->
                        log.error("Error processing batch reactively", error));
    }

    private ProcessedEvent transformEvent(EventData eventData) {
        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setOriginalId(eventData.getId());
        processedEvent.setEventType(eventData.getEventType());
        processedEvent.setPayload(eventData.getPayload());
        processedEvent.setPartitionId(eventData.getPartitionId());
        processedEvent.setSequenceNumber(eventData.getSequenceNumber());
        processedEvent.setEnqueuedTime(eventData.getEnqueuedTime());
        processedEvent.setProcessedAt(Instant.now());
        return processedEvent;
    }

    private void validateEvent(ProcessedEvent event) {
        if (event.getPayload() == null || event.getPayload().isEmpty()) {
            throw new IllegalArgumentException("Event payload cannot be empty");
        }

        if (event.getOriginalId() == null || event.getOriginalId().isEmpty()) {
            throw new IllegalArgumentException("Event ID cannot be empty");
        }
    }

    @CircuitBreaker(name = "external-api", fallbackMethod = "fallbackEnrichEvent")
    @Retry(name = "external-api")
    private void enrichEventWithExternalData(ProcessedEvent event) {
        String enrichmentData = externalApiClient.getEnrichmentData(event.getOriginalId());
        event.setEnrichmentData(enrichmentData);
    }

    /**
     * Enriquecimento reativo usando WebClient
     */
    private Mono<ProcessedEvent> enrichEventWithExternalDataReactive(ProcessedEvent event) {
        return externalApiClient.getEnrichmentDataReactive(event.getOriginalId())
                .map(enrichmentData -> {
                    event.setEnrichmentData(enrichmentData);
                    return event;
                })
                .onErrorReturn(event); // Continuar sem enriquecimento em caso de erro
    }

    /**
     * Persistência reativa (simulação - Spring Data R2DBC seria necessário para real reactive)
     */
    private Mono<ProcessedEvent> saveEventReactive(ProcessedEvent event) {
        return Mono.fromCallable(() -> eventRepository.save(event))
                .doOnSuccess(saved -> log.debug("Event saved reactively: {}", saved.getOriginalId()));
    }

    // Fallback methods
    public CompletableFuture<Void> fallbackProcessEvent(EventData eventData, Exception ex) {
        log.warn("Using fallback for event processing: {}", ex.getMessage());
        // Enviar para DLQ ou queue de retry
        return CompletableFuture.completedFuture(null);
    }

    private void fallbackEnrichEvent(ProcessedEvent event, Exception ex) {
        log.warn("External API unavailable, processing without enrichment: {}", ex.getMessage());
        event.setEnrichmentData(createFallbackEnrichmentData(event.getOriginalId()));
    }

    private String createFallbackEnrichmentData(String eventId) {
        return String.format("{\"eventId\":\"%s\",\"source\":\"fallback\",\"timestamp\":\"%s\"}",
                eventId, Instant.now().toString());
    }

    /**
     * Método utilitário para alternar modo de processamento
     */
    public void setProcessingMode(String mode) {
        if ("sync".equals(mode) || "async".equals(mode) || "reactive".equals(mode)) {
            this.processingMode = mode;
            log.info("Processing mode changed to: {}", mode);
        } else {
            throw new IllegalArgumentException("Invalid processing mode: " + mode);
        }
    }

    public String getProcessingMode() {
        return processingMode;
    }
}
