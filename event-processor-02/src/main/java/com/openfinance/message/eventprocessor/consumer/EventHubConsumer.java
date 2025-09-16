package com.openfinance.message.eventprocessor.consumer;

import com.azure.spring.cloud.service.eventhubs.consumer.EventHubsErrorHandler;
import com.azure.spring.cloud.service.eventhubs.consumer.EventHubsRecordMessageListener;
import com.azure.spring.messaging.eventhubs.implementation.core.annotation.EventHubsListener;
import com.azure.spring.messaging.checkpoint.Checkpointer;
import com.azure.messaging.eventhubs.models.EventContext;
import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.PartitionContext;
import com.azure.messaging.eventhubs.EventData;
import com.company.eventhubprocessor.service.EventProcessorService;
import com.company.eventhubprocessor.dlq.DeadLetterQueueService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.azure.spring.messaging.AzureHeaders.CHECKPOINTER;

@Component
public class EventHubConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventHubConsumer.class);

    @Autowired
    private EventProcessorService eventProcessorService;

    @Autowired
    private DeadLetterQueueService deadLetterQueueService;

    private final Counter processedEventsCounter;
    private final Counter failedEventsCounter;
    private final AtomicInteger inFlightEvents = new AtomicInteger(0);

    @Value("${event-processor.event-hub-name}")
    private String eventHubName;

    @Value("${event-processor.consumer-group:$Default}")
    private String consumerGroup;

    @Value("${event-processor.max-retries:3}")
    private int maxRetries;

    public EventHubConsumer(MeterRegistry meterRegistry) {
        this.processedEventsCounter = Counter.builder("eventhub.events.processed")
                .description("Total number of events processed successfully")
                .register(meterRegistry);

        this.failedEventsCounter = Counter.builder("eventhub.events.failed")
                .description("Total number of events that failed processing")
                .register(meterRegistry);
    }

    /**
     * Processa eventos individuais com garantia at-least-once
     * Utiliza checkpoint manual para controle de exactly-once
     */
    @EventHubsListener(
            destination = "${event-processor.event-hub-name}",
            group = "${event-processor.consumer-group:$Default}"
    )
    public void processEventAtLeastOnce(Message<String> message) {

        String messageId = message.getHeaders().get("azure_eventhubs_message_id", String.class);
        String partitionId = message.getHeaders().get("azure_eventhubs_partition_id", String.class);
        Long sequenceNumber = message.getHeaders().get("azure_eventhubs_sequence_number", Long.class);
        Checkpointer checkpointer = message.getHeaders().get(CHECKPOINTER, Checkpointer.class);

        long startTime = System.currentTimeMillis();
        inFlightEvents.incrementAndGet();

        log.info("Processing event {} from partition {}, sequence {}",
                messageId, partitionId, sequenceNumber);

        try {
            // Converter message para EventData
            com.company.eventhubprocessor.model.EventData eventData =
                    convertToEventData(message);

            // Processamento assíncrono
            CompletableFuture<Void> processingFuture =
                    eventProcessorService.processEventAsync(eventData);

            processingFuture.whenComplete((result, throwable) -> {
                try {
                    if (throwable == null) {
                        // Checkpoint manual para garantir at-least-once
                        if (checkpointer != null) {
                            checkpointer.success()
                                    .doOnSuccess(success -> {
                                        processedEventsCounter.increment();
                                        long processingTime = System.currentTimeMillis() - startTime;
                                        log.info("Event {} processed successfully in {}ms",
                                                messageId, processingTime);
                                    })
                                    .doOnError(error -> {
                                        log.error("Failed to checkpoint event {}", messageId, error);
                                    })
                                    .subscribe();
                        }
                    } else {
                        failedEventsCounter.increment();
                        log.error("Failed to process event {} from partition {}",
                                messageId, partitionId, throwable);

                        // Enviar para DLQ após esgotar tentativas
                        handleProcessingFailure(eventData, throwable, checkpointer);
                    }
                } finally {
                    inFlightEvents.decrementAndGet();
                }
            });

        } catch (Exception e) {
            inFlightEvents.decrementAndGet();
            failedEventsCounter.increment();
            log.error("Error processing event {} from partition {}",
                    messageId, partitionId, e);

            // Processar erro sem checkpoint para reprocessamento
            handleProcessingFailure(convertToEventData(message), e, checkpointer);
        }
    }

    /**
     * Processa lotes de eventos com garantia exactly-once
     * Utiliza transação para garantir processamento atômico
     */
    @EventHubsListener(
            destination = "${event-processor.event-hub-name}",
            group = "${event-processor.consumer-group-exact:event-processor-exact-group}"
    )
    public void processEventBatchExactlyOnce(List<Message<String>> messages) {

        long startTime = System.currentTimeMillis();
        log.info("Processing batch of {} events", messages.size());

        try {
            // Converter messages para EventData
            List<com.company.eventhubprocessor.model.EventData> events =
                    messages.stream()
                            .map(this::convertToEventData)
                            .toList();

            // Extrair offsets e partições para controle transacional
            List<Long> sequenceNumbers = messages.stream()
                    .map(msg -> msg.getHeaders().get("azure_eventhubs_sequence_number", Long.class))
                    .toList();

            List<String> partitionIds = messages.stream()
                    .map(msg -> msg.getHeaders().get("azure_eventhubs_partition_id", String.class))
                    .toList();

            // Processamento transacional para exactly-once
            eventProcessorService.processBatchTransactionally(events, sequenceNumbers, partitionIds);

            // Checkpoint em lote
            messages.forEach(message -> {
                Checkpointer checkpointer = message.getHeaders().get(CHECKPOINTER, Checkpointer.class);
                if (checkpointer != null) {
                    checkpointer.success().subscribe();
                }
            });

            processedEventsCounter.increment(messages.size());
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("Batch of {} events processed successfully in {}ms",
                    messages.size(), processingTime);

        } catch (Exception e) {
            failedEventsCounter.increment(messages.size());
            log.error("Failed to process batch of {} events", messages.size(), e);

            // Não fazer checkpoint para reprocessamento
            // Enviar eventos individuais para DLQ
            messages.forEach(message -> {
                try {
                    com.company.eventhubprocessor.model.EventData eventData =
                            convertToEventData(message);
                    deadLetterQueueService.sendToDeadLetterQueue(eventData,
                            "Batch processing failed", e);
                } catch (Exception dlqError) {
                    log.error("Failed to send event to DLQ", dlqError);
                }
            });
        }
    }

    private com.company.eventhubprocessor.model.EventData convertToEventData(Message<String> message) {
        com.company.eventhubprocessor.model.EventData eventData =
                new com.company.eventhubprocessor.model.EventData();

        eventData.setId(message.getHeaders().get("azure_eventhubs_message_id", String.class));
        eventData.setPayload(message.getPayload());
        eventData.setPartitionId(message.getHeaders().get("azure_eventhubs_partition_id", String.class));
        eventData.setSequenceNumber(message.getHeaders().get("azure_eventhubs_sequence_number", Long.class));
        eventData.setEnqueuedTime((Instant) message.getHeaders().get("azure_eventhubs_enqueued_time"));
        eventData.setOffset(message.getHeaders().get("azure_eventhubs_offset", String.class));

        // Extrair event type do header customizado ou do payload
        String eventType = message.getHeaders().get("event-type", String.class);
        if (eventType == null) {
            eventType = "UNKNOWN";
        }
        eventData.setEventType(eventType);

        return eventData;
    }

    private void handleProcessingFailure(
            com.company.eventhubprocessor.model.EventData eventData,
            Throwable throwable,
            Checkpointer checkpointer) {

        // Implementar retry logic com exponential backoff
        // Por enquanto, enviar diretamente para DLQ
        try {
            deadLetterQueueService.sendToDeadLetterQueue(
                    eventData,
                    "Processing failed after retries",
                    throwable instanceof Exception ? (Exception) throwable :
                            new RuntimeException(throwable)
            );

            // Fazer checkpoint apenas após enviar para DLQ
            if (checkpointer != null) {
                checkpointer.success().subscribe();
            }

        } catch (Exception dlqError) {
            log.error("Failed to send event to DLQ: {}", eventData.getId(), dlqError);
            // Não fazer checkpoint - evento será reprocessado
        }
    }

    public int getInFlightEventsCount() {
        return inFlightEvents.get();
    }
}

/**
 * Configuração para EventHub Message Listeners
 */
@Configuration
public class EventHubConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventHubConsumerConfiguration.class);

    /**
     * Bean para processar eventos individuais
     */
    @Bean
    public EventHubsRecordMessageListener eventProcessor() {
        return eventContext -> {
            EventData eventData = eventContext.getEventData();
            PartitionContext partitionContext = eventContext.getPartitionContext();

            log.info("Processing event from partition {} with sequence number {} with body: {}",
                    partitionContext.getPartitionId(),
                    eventData.getSequenceNumber(),
                    eventData.getBodyAsString());
        };
    }

    /**
     * Bean para tratar erros de processamento
     */
    @Bean
    public EventHubsErrorHandler eventErrorHandler() {
        return errorContext -> {
            PartitionContext partitionContext = errorContext.getPartitionContext();
            Throwable throwable = errorContext.getThrowable();

            log.error("Error occurred in partition processor for partition {}: {}",
                    partitionContext.getPartitionId(),
                    throwable.getMessage(),
                    throwable);
        };
    }
}
