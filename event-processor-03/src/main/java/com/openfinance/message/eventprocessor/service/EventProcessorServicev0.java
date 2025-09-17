package com.openfinance.message.eventprocessor.service;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.models.*;
import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.PartitionContext;
import com.azure.spring.messaging.eventhubs.core.EventHubsProcessorFactory;
import com.azure.spring.messaging.eventhubs.core.checkpoint.CheckpointConfig;
import com.azure.spring.messaging.eventhubs.core.properties.EventHubsContainerProperties;
import com.openfinance.message.eventprocessor.config.EventProcessingProperties;
import com.openfinance.message.eventprocessor.model.*;
import com.openfinance.message.eventprocessor.orchestration.EventProcessingOrchestrator;
import com.openfinance.message.eventprocessor.serialization.EventDeserializer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
@Slf4j
public class EventProcessorServicev0 {

    private final EventProcessingProperties properties;
    private final EventDeserializer eventDeserializer;
    private final EventProcessingOrchestrator orchestrator;
    private final EventHubsProcessorFactory processorFactory;
    private final MeterRegistry meterRegistry;
    private final Executor eventProcessingExecutor;

    // Event processor client
    private EventProcessorClient eventProcessorClient;

    // Tracking metrics
    private final AtomicLong processedEvents = new AtomicLong(0);
    private final AtomicLong failedEvents = new AtomicLong(0);
    private final AtomicInteger activeProcessors = new AtomicInteger(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Map<String, PartitionContext> partitionContexts = new ConcurrentHashMap<>();

    // Metrics
    private Counter eventsProcessedCounter;
    private Counter eventsFailedCounter;
    private Timer eventProcessingTimer;

    // Checkpointing
    private final AtomicLong lastCheckpointTime = new AtomicLong(System.currentTimeMillis());
    private static final long CHECKPOINT_INTERVAL_MS = 30000; // 30 seconds

    @Autowired
    public EventProcessorServicev0(
            EventProcessingProperties properties,
            EventDeserializer eventDeserializer,
            EventProcessingOrchestrator orchestrator,
            EventHubsProcessorFactory processorFactory,
            MeterRegistry meterRegistry,
            Executor eventProcessingExecutor) {
        this.properties = properties;
        this.eventDeserializer = eventDeserializer;
        this.orchestrator = orchestrator;
        this.processorFactory = processorFactory;
        this.meterRegistry = meterRegistry;
        this.eventProcessingExecutor = eventProcessingExecutor;
    }

    @PostConstruct
    public void initialize() {
        try {
            initializeMetrics();
            initializeEventProcessor();
            startProcessor();
        } catch (Exception e) {
            log.error("Failed to initialize event processor", e);
            throw new RuntimeException("Failed to initialize event processor", e);
        }
    }

    private void initializeMetrics() {
        this.eventsProcessedCounter = Counter.builder("events.processed.total")
                .description("Total number of events processed")
                .tag("service", "event-processor")
                .tag("format", properties.getDataFormat().toString())
                .tag("mode", properties.getMode().toString())
                .register(meterRegistry);

        this.eventsFailedCounter = Counter.builder("events.failed.total")
                .description("Total number of failed events")
                .tag("service", "event-processor")
                .register(meterRegistry);

        this.eventProcessingTimer = Timer.builder("event.processing.duration")
                .description("Event processing duration")
                .tag("service", "event-processor")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    private void initializeEventProcessor() {
        // Create processor using Spring Cloud Azure EventHubsProcessorFactory
        EventHubsContainerProperties containerProperties = new EventHubsContainerProperties();

        // Configure checkpoint
        CheckpointConfig checkpointConfig = new CheckpointConfig();
        if (properties.getDeliveryGuarantee() == DeliveryGuarantee.EXACTLY_ONCE) {
            checkpointConfig.setMode(CheckpointConfig.CheckpointMode.RECORD);
        } else {
            checkpointConfig.setMode(CheckpointConfig.CheckpointMode.BATCH);
            checkpointConfig.setInterval(Duration.ofSeconds(30));
            checkpointConfig.setCount(properties.getCheckpointBatchSize());
        }
        containerProperties.setCheckpointConfig(checkpointConfig);

        // Create custom event processor
        this.eventProcessorClient = createEventProcessor();

        log.info("Event processor client initialized for EventHub: {}, ConsumerGroup: {}, Mode: {}",
                properties.getEventHubName(),
                properties.getConsumerGroup(),
                properties.getMode());
    }

    private EventProcessorClient createEventProcessor() {
        // Create the event processor with proper configuration
        return processorFactory.createEventProcessorClient(
                properties.getEventHubName(),
                properties.getConsumerGroup(),
                createEventConsumer(),
                createErrorConsumer()
        );
    }

    private Consumer<EventContext> createEventConsumer() {
        return eventContext -> {
            activeProcessors.incrementAndGet();
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                processEventInternal(eventContext);
            } catch (Exception e) {
                log.error("Failed to process event from partition: {}",
                        eventContext.getPartitionContext().getPartitionId(), e);
                handleProcessingFailure(eventContext, e);
            } finally {
                activeProcessors.decrementAndGet();
                sample.stop(eventProcessingTimer);
            }
        };
    }

    private Consumer<ErrorContext> createErrorConsumer() {
        return errorContext -> {
            log.error("Error in partition processor for partition: {}",
                    errorContext.getPartitionContext().getPartitionId(),
                    errorContext.getThrowable());

            // Handle different types of errors
            Throwable throwable = errorContext.getThrowable();
            if (throwable instanceof EventHubException) {
                EventHubException ehException = (EventHubException) throwable;
                if (ehException.isTransient()) {
                    log.info("Transient error occurred, will retry: {}", ehException.getMessage());
                } else {
                    log.error("Non-transient EventHub error occurred: {}", ehException.getMessage());
                }
            }
        };
    }

    @Timed(value = "event.processing.duration", description = "Time taken to process an event")
    private void processEventInternal(EventContext eventContext) {
        try {
            EventData eventData = eventContext.getEventData();
            PartitionContext partitionContext = eventContext.getPartitionContext();

            log.debug("Processing event from partition: {}, offset: {}, sequence: {}",
                    partitionContext.getPartitionId(),
                    eventData.getOffset(),
                    eventData.getSequenceNumber());

            // Store partition context for monitoring
            updatePartitionContext(partitionContext, eventData);

            // Deserialize based on configured format
            Object deserializedEvent = eventDeserializer.deserialize(
                    eventData.getBodyAsBinaryData().toBytes(),
                    properties.getDataFormat()
            );

            // Create context for processing
            ProcessEventArgs processEventArgs = ProcessEventArgs.builder()
                    .data(convertToEventData(eventData))
                    .partitionContext(convertToPartitionContext(partitionContext))
                    .build();

            // Process event through orchestrator
            CompletableFuture<ProcessingResult> processingFuture =
                    orchestrator.processEvent(deserializedEvent, processEventArgs.getPartitionContext());

            // Handle result
            processingFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    handleProcessingFailure(eventContext, throwable);
                } else {
                    handleProcessingSuccess(eventContext, result);
                }
            });

        } catch (Exception e) {
            log.error("Failed to process event", e);
            handleProcessingFailure(eventContext, e);
            throw new RuntimeException("Event processing failed", e);
        }
    }

    @CircuitBreaker(name = "database", fallbackMethod = "handleDatabaseFailure")
    @Retry(name = "external-api")
    private void handleProcessingSuccess(EventContext eventContext, ProcessingResult result) {
        eventsProcessedCounter.increment();
        processedEvents.incrementAndGet();

        // Checkpoint based on delivery guarantee and batch size
        if (shouldCheckpoint()) {
            performCheckpoint(eventContext);
        }

        log.debug("Successfully processed event. Total processed: {}, Result: {}",
                processedEvents.get(), result.getStatus());
    }

    private void handleProcessingFailure(EventContext eventContext, Throwable throwable) {
        eventsFailedCounter.increment();
        failedEvents.incrementAndGet();

        log.error("Failed to process event from partition: {}, offset: {}",
                eventContext.getPartitionContext().getPartitionId(),
                eventContext.getEventData().getOffset(),
                throwable);

        if (properties.isDeadLetterEnabled()) {
            sendToDeadLetterQueue(eventContext, throwable);
        }
    }

    // Fallback method for Circuit Breaker
    private void handleDatabaseFailure(EventContext eventContext, ProcessingResult result, Exception ex) {
        log.warn("Database circuit breaker opened, using fallback processing", ex);
        // Store event for later retry or alternative processing
        sendToDeadLetterQueue(eventContext, ex);
    }

    private void sendToDeadLetterQueue(EventContext eventContext, Throwable error) {
        try {
            EventData eventData = eventContext.getEventData();
            com.openfinance.message.eventprocessor.model.EventData modelEventData = convertToEventData(eventData);
            orchestrator.sendToDeadLetterQueue(modelEventData, error);
        } catch (Exception dlqError) {
            log.error("Failed to send message to dead letter queue", dlqError);
        }
    }

    private boolean shouldCheckpoint() {
        // Check based on delivery guarantee
        if (properties.getDeliveryGuarantee() == DeliveryGuarantee.EXACTLY_ONCE) {
            return true; // Always checkpoint for exactly-once
        }

        // For at-least-once, checkpoint periodically
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCheckpoint = currentTime - lastCheckpointTime.get();

        // Checkpoint based on count or time
        return processedEvents.get() % properties.getCheckpointBatchSize() == 0 ||
                timeSinceLastCheckpoint >= CHECKPOINT_INTERVAL_MS;
    }

    private void performCheckpoint(EventContext eventContext) {
        try {
            eventContext.updateCheckpointAsync()
                    .whenComplete((v, ex) -> {
                        if (ex != null) {
                            log.error("Failed to update checkpoint", ex);
                        } else {
                            lastCheckpointTime.set(System.currentTimeMillis());
                            log.debug("Checkpoint updated for partition: {}, offset: {}",
                                    eventContext.getPartitionContext().getPartitionId(),
                                    eventContext.getEventData().getOffset());
                        }
                    });
        } catch (Exception e) {
            log.error("Error performing checkpoint", e);
        }
    }

    private void updatePartitionContext(PartitionContext azureContext, EventData eventData) {
        com.openfinance.message.eventprocessor.model.PartitionContext context =
                convertToPartitionContext(azureContext);
        context.setOffset(eventData.getOffset());
        context.setSequenceNumber(eventData.getSequenceNumber());
        partitionContexts.put(azureContext.getPartitionId(), context);
    }

    private com.openfinance.message.eventprocessor.model.EventData convertToEventData(EventData azureEventData) {
        Map<String, Object> properties = new HashMap<>();
        azureEventData.getProperties().forEach((k, v) -> properties.put(k, v));

        Map<String, Object> systemProperties = new HashMap<>();
        systemProperties.put("enqueuedTime", azureEventData.getEnqueuedTime());
        systemProperties.put("offset", azureEventData.getOffset());
        systemProperties.put("sequenceNumber", azureEventData.getSequenceNumber());
        systemProperties.put("partitionKey", azureEventData.getPartitionKey());

        return com.openfinance.message.eventprocessor.model.EventData.builder()
                .body(azureEventData.getBodyAsBytes())
                .properties(properties)
                .systemProperties(systemProperties)
                .offset(azureEventData.getOffset())
                .sequenceNumber(azureEventData.getSequenceNumber())
                .enqueuedTime(azureEventData.getEnqueuedTime())
                .partitionKey(azureEventData.getPartitionKey())
                .build();
    }

    private com.openfinance.message.eventprocessor.model.PartitionContext convertToPartitionContext(
            PartitionContext azurePartitionContext) {
        return com.openfinance.message.eventprocessor.model.PartitionContext.builder()
                .partitionId(azurePartitionContext.getPartitionId())
                .eventHubName(azurePartitionContext.getEventHubName())
                .consumerGroup(azurePartitionContext.getConsumerGroup())
                .build();
    }

    private void startProcessor() {
        try {
            eventProcessorClient.start();
            isRunning.set(true);
            log.info("Event processor started successfully for EventHub: {}, ConsumerGroup: {}",
                    properties.getEventHubName(), properties.getConsumerGroup());
        } catch (Exception e) {
            log.error("Failed to start event processor", e);
            isRunning.set(false);
            throw new RuntimeException("Failed to start event processor", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down event processor...");
        isRunning.set(false);

        if (eventProcessorClient != null) {
            try {
                eventProcessorClient.stop();
                log.info("Event processor stopped successfully");
            } catch (Exception e) {
                log.error("Error during processor shutdown", e);
            }
        }

        // Log final metrics
        log.info("Final metrics - Processed: {}, Failed: {}",
                processedEvents.get(), failedEvents.get());
    }

    // Public methods for health checks and monitoring

    public boolean isHealthy() {
        return isRunning.get() && eventProcessorClient != null;
    }

    public long getProcessedEventsCount() {
        return processedEvents.get();
    }

    public long getFailedEventsCount() {
        return failedEvents.get();
    }

    public int getActiveProcessorsCount() {
        return activeProcessors.get();
    }

    public Map<String, PartitionMetrics> getPartitionMetrics() {
        Map<String, PartitionMetrics> metrics = new HashMap<>();
        partitionContexts.forEach((partitionId, context) -> {
            metrics.put(partitionId, new PartitionMetrics(
                    context.getPartitionId(),
                    context.getOffset(),
                    context.getSequenceNumber()
            ));
        });
        return metrics;
    }

    public double getSuccessRate() {
        long total = processedEvents.get() + failedEvents.get();
        if (total == 0) return 100.0;
        return (double) processedEvents.get() / total * 100;
    }

    public double getErrorRate() {
        long total = processedEvents.get() + failedEvents.get();
        if (total == 0) return 0.0;
        return (double) failedEvents.get() / total * 100;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PartitionMetrics {
        private String partitionId;
        private Long lastOffset;
        private Long lastSequenceNumber;
    }
}