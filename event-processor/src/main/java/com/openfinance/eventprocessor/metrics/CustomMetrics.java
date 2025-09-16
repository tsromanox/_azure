package com.openfinance.eventprocessor.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CustomMetrics {

    private final MeterRegistry meterRegistry;

    // Contadores
    private final Counter processedEvents;
    private final Counter publishedEvents;
    private final Counter dlqMessages;
    private final Counter processingErrors;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    // Timers
    private final Timer eventProcessingTime;
    private final Timer enrichmentTime;
    private final Timer publishingTime;

    // Gauges
    private final AtomicLong currentLag = new AtomicLong(0);
    private final AtomicLong activePartitions = new AtomicLong(0);

    public CustomMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Inicializa contadores
        this.processedEvents = Counter.builder("events.processed")
                .description("Total de eventos processados")
                .tag("status", "success")
                .register(meterRegistry);

        this.publishedEvents = Counter.builder("events.published")
                .description("Total de eventos publicados")
                .register(meterRegistry);

        this.dlqMessages = Counter.builder("events.dlq")
                .description("Total de mensagens enviadas para DLQ")
                .register(meterRegistry);

        this.processingErrors = Counter.builder("events.errors")
                .description("Total de erros de processamento")
                .register(meterRegistry);

        this.cacheHits = Counter.builder("cache.hits")
                .description("Cache hits")
                .register(meterRegistry);

        this.cacheMisses = Counter.builder("cache.misses")
                .description("Cache misses")
                .register(meterRegistry);

        // Inicializa timers
        this.eventProcessingTime = Timer.builder("event.processing.time")
                .description("Tempo de processamento de eventos")
                .register(meterRegistry);

        this.enrichmentTime = Timer.builder("event.enrichment.time")
                .description("Tempo de enriquecimento")
                .register(meterRegistry);

        this.publishingTime = Timer.builder("event.publishing.time")
                .description("Tempo de publicação")
                .register(meterRegistry);

        // Inicializa gauges
        Gauge.builder("events.lag", currentLag, AtomicLong::get)
                .description("Consumer lag atual")
                .register(meterRegistry);

        Gauge.builder("partitions.active", activePartitions, AtomicLong::get)
                .description("Número de partições ativas")
                .register(meterRegistry);
    }

    // Métodos de incremento
    public void incrementProcessedEvents() {
        processedEvents.increment();
    }

    public void incrementPublishedEvents() {
        publishedEvents.increment();
    }

    public void incrementDLQMessages() {
        dlqMessages.increment();
    }

    public void incrementProcessingErrors() {
        processingErrors.increment();
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    // Métodos de timing
    public void recordEventProcessingTime(long milliseconds) {
        eventProcessingTime.record(milliseconds, TimeUnit.MILLISECONDS);
    }

    public void recordEnrichmentTime(long milliseconds) {
        enrichmentTime.record(milliseconds, TimeUnit.MILLISECONDS);
    }

    public void recordPublishingTime(long milliseconds) {
        publishingTime.record(milliseconds, TimeUnit.MILLISECONDS);
    }

    // Métodos de gauge
    public void updateConsumerLag(long lag) {
        currentLag.set(lag);
    }

    public void updateActivePartitions(long count) {
        activePartitions.set(count);
    }

    /**
     * Calcula e retorna taxa de acerto do cache
     */
    public double getCacheHitRate() {
        double hits = cacheHits.count();
        double misses = cacheMisses.count();
        double total = hits + misses;

        return total > 0 ? (hits / total) * 100 : 0;
    }
}
