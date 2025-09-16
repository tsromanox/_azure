package com.openfinance.message.eventprocessor.processor;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualThreadEventProcessor {

    private final EventHubConsumerAsyncClient consumerClient;
    private final EventHubProducerClient producerClient;
    private final EnrichmentService enrichmentService;
    private final Cache<String, EnrichedEvent> eventCache;
    private final DeadLetterQueueHandler dlqHandler;
    private final MeterRegistry meterRegistry;
    private final ExecutorService virtualThreadExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Checkpointing strategy
    private final AtomicLong processedCount = new AtomicLong(0);
    private volatile Instant lastCheckpoint = Instant.now();
    private static final long CHECKPOINT_COUNT = 1000;
    private static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(10);

    @EventListener(ApplicationReadyEvent.class)
    public void startProcessing() {
        log.info("Starting event processing with Virtual Threads");

        consumerClient.receiveEvents(new ReceiveOptions()
                        .setTrackLastEnqueuedEventProperties(true)
                        .setOwnerLevel(0L))
                .parallel(32) // Process partitions in parallel
                .runOn(Schedulers.fromExecutor(virtualThreadExecutor))
                .subscribe(
                        this::processEvent,
                        error -> {
                            log.error("Error in event processing stream", error);
                            meterRegistry.counter("processor.stream.errors").increment();
                        },
                        () -> log.info("Event processing stream completed")
                );
    }

    private void processEvent(PartitionEvent partitionEvent) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            EventData eventData = partitionEvent.getData();
            String eventId = eventData.getProperties().get("id").toString();

            log.debug("Processing event: {} from partition: {}",
                    eventId, partitionEvent.getPartitionContext().getPartitionId());

            // Try cache first
            EnrichedEvent cached = eventCache.getIfPresent(eventId);
            if (cached != null) {
                meterRegistry.counter("cache.hits").increment();
                publishEnrichedEvent(cached);
                conditionalCheckpoint(partitionEvent);
                sample.stop(meterRegistry.timer("event.processing.time", "status", "cached"));
                return;
            }

            meterRegistry.counter("cache.misses").increment();

            // Parse event
            EventPayload payload = parseEvent(eventData);

            // Enrich event (blocking I/O handled efficiently by virtual thread)
            EnrichedEvent enriched = enrichmentService.enrichEvent(payload);

            // Update cache
            eventCache.put(eventId, enriched);

            // Publish enriched event
            publishEnrichedEvent(enriched);

            // Update checkpoint conditionally
            conditionalCheckpoint(partitionEvent);

            // Record metrics
            sample.stop(meterRegistry.timer("event.processing.time", "status", "success"));
            meterRegistry.counter("events.processed",
                    "partition", partitionEvent.getPartitionContext().getPartitionId()).increment();

        } catch (Exception e) {
            sample.stop(meterRegistry.timer("event.processing.time", "status", "failure"));
            log.error("Failed to process event", e);
            handleProcessingError(partitionEvent, e);
        }
    }

    private EventPayload parseEvent(EventData eventData) throws Exception {
        return objectMapper.readValue(eventData.getBodyAsString(), EventPayload.class);
    }

    private void publishEnrichedEvent(EnrichedEvent enriched) {
        try {
            EventData outputEvent = new EventData(enriched.toJson())
                    .addProperty("processingTime", Instant.now().toString())
                    .addProperty("processorId", getProcessorId());

            SendOptions sendOptions = new SendOptions()
                    .setPartitionKey(enriched.getPartitionKey());

            producerClient.send(Collections.singletonList(outputEvent), sendOptions);

            meterRegistry.counter("events.published").increment();

        } catch (Exception e) {
            log.error("Failed to publish enriched event", e);
            meterRegistry.counter("events.publish.failures").increment();
            throw new RuntimeException("Failed to publish event", e);
        }
    }

    private void conditionalCheckpoint(PartitionEvent partitionEvent) {
        long count = processedCount.incrementAndGet();
        boolean shouldCheckpoint = (count % CHECKPOINT_COUNT == 0) ||
                Duration.between(lastCheckpoint, Instant.now()).compareTo(CHECKPOINT_INTERVAL) > 0;

        if (shouldCheckpoint) {
            partitionEvent.getPartitionContext().updateCheckpointAsync()
                    .doOnSuccess(v -> {
                        lastCheckpoint = Instant.now();
                        log.debug("Checkpoint updated for partition: {}",
                                partitionEvent.getPartitionContext().getPartitionId());
                        meterRegistry.counter("checkpoints.success").increment();
                    })
                    .doOnError(error -> {
                        log.error("Failed to update checkpoint", error);
                        meterRegistry.counter("checkpoints.failures").increment();
                    })
                    .subscribe();
        }
    }

    private void handleProcessingError(PartitionEvent partitionEvent, Exception error) {
        meterRegistry.counter("events.processing.errors",
                "error", error.getClass().getSimpleName()).increment();

        // Send to DLQ after retry attempts
        dlqHandler.handleFailedEvent(partitionEvent.getData(), error);

        // Still update checkpoint to avoid reprocessing
        partitionEvent.getPartitionContext().updateCheckpointAsync()
                .subscribe();
    }

    private String getProcessorId() {
        return System.getenv("HOSTNAME"); // Pod name in Kubernetes
    }
}
