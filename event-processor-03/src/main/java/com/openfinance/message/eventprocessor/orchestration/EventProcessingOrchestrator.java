package com.openfinance.message.eventprocessor.orchestration;

import com.openfinance.message.eventprocessor.exception.EventProcessingException;
import com.openfinance.message.eventprocessor.external.ExternalApiClient;
import com.openfinance.message.eventprocessor.model.EventData;
import com.openfinance.message.eventprocessor.model.PartitionContext;
import com.openfinance.message.eventprocessor.model.ProcessingResult;
import com.openfinance.message.eventprocessor.model.TransformedEvent;
import com.openfinance.message.eventprocessor.persistence.EventStore;
import com.openfinance.message.eventprocessor.persistence.entity.EventEntity;
import com.openfinance.message.eventprocessor.processor.EventProcessor;
import com.openfinance.message.eventprocessor.service.DeadLetterQueueService;
import com.openfinance.message.eventprocessor.transformation.EventTransformer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventProcessingOrchestrator {

    private final EventStore eventStore;
    private final ExternalApiClient externalApiClient;
    private final List<EventProcessor<?>> eventProcessors;
    private final DeadLetterQueueService deadLetterQueueService;
    private final Executor eventProcessingExecutor;

    @Transactional
    public CompletableFuture<ProcessingResult> processEvent(
            Object event,
            PartitionContext partitionContext) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Transform event data
                TransformedEvent transformedEvent = transformEvent(event);

                // 2. Persist to event store (Event Sourcing pattern)
                EventEntity persistedEvent = persistEvent(transformedEvent, partitionContext);

                // 3. Process through applicable processors
                ProcessingResult result = processWithPlugins(transformedEvent);

                // 4. Call external APIs if needed
                if (requiresExternalProcessing(transformedEvent)) {
                    callExternalApis(transformedEvent);
                }

                return result;

            } catch (Exception e) {
                log.error("Failed to process event in orchestrator", e);
                throw new EventProcessingException("Processing failed", e);
            }
        }, eventProcessingExecutor);
    }

    private TransformedEvent transformEvent(Object rawEvent) {
        // Business logic transformation
        return EventTransformer.transform(rawEvent);
    }

    @Transactional
    private EventEntity persistEvent(TransformedEvent event, PartitionContext context) {
        // Event Sourcing pattern - append-only event store
        EventEntity entity = EventEntity.builder()
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType().name())
                .eventData(event.getPayload())
                .partitionId(context.getPartitionId())
                .offset(context.getOffset())
                .timestamp(Instant.now())
                .version(generateVersion())
                .build();

        return eventStore.save(entity);
    }

    private ProcessingResult processWithPlugins(TransformedEvent event) {
        List<CompletableFuture<Void>> processingFutures = eventProcessors.stream()
                .filter(processor -> processor.canProcess(event))
                .map(processor -> processor.processAsync(event))
                .toList();

        // Wait for all processors to complete
        CompletableFuture.allOf(processingFutures.toArray(new CompletableFuture[0]))
                .join();

        return ProcessingResult.success();
    }

    @CircuitBreaker(name = "external-api", fallbackMethod = "handleExternalApiFailure")
    @Retry(name = "external-api")
    private void callExternalApis(TransformedEvent event) {
        externalApiClient.processEvent(event);
    }

    private void handleExternalApiFailure(TransformedEvent event, Exception ex) {
        log.warn("External API call failed, using fallback processing", ex);
        // Fallback logic or queue for later retry
    }

    public void sendToDeadLetterQueue(EventData eventData, Throwable error) {
        deadLetterQueueService.sendToDLQ(eventData, error);
    }

    private boolean requiresExternalProcessing(TransformedEvent event) {
        return event.getEventType().requiresExternalCall();
    }

    private Long generateVersion() {
        return System.currentTimeMillis();
    }
}
