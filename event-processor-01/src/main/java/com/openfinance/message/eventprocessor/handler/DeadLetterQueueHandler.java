package com.openfinance.message.eventprocessor.handler;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterQueueHandler {

    private final EventHubProducerClient dlqProducer;
    private final MeterRegistry meterRegistry;

    // Retry tracking cache
    private final Cache<String, Integer> retryCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private static final int MAX_RETRIES = 3;

    public void handleFailedEvent(EventData event, Exception error) {
        String eventId = event.getProperties().get("id").toString();
        int retryCount = retryCache.get(eventId, k -> 0) + 1;

        if (retryCount <= MAX_RETRIES) {
            log.warn("Event {} failed, retry attempt {}/{}", eventId, retryCount, MAX_RETRIES);
            retryCache.put(eventId, retryCount);
            scheduleRetry(event, retryCount);
            meterRegistry.counter("dlq.retries", "attempt", String.valueOf(retryCount)).increment();
        } else {
            log.error("Event {} exceeded max retries, sending to DLQ", eventId);
            sendToDlq(event, error, retryCount);
            retryCache.invalidate(eventId);
            meterRegistry.counter("dlq.final", "reason", error.getClass().getSimpleName()).increment();
        }
    }

    private void scheduleRetry(EventData event, int retryCount) {
        // Exponential backoff
        long delayMs = (long) Math.pow(2, retryCount) * 1000;

        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    // Re-queue for processing
                    log.info("Retrying event after {} ms delay", delayMs);
                    // Implementation depends on your retry mechanism
                });
    }

    private void sendToDlq(EventData event, Exception error, int retryCount) {
        try {
            EventData dlqEvent = new EventData(event.getBody())
                    .addContext("dlq.reason", error.getMessage())
                    .addProperty("dlq.reason", error.getMessage())
                    .addProperty("dlq.errorType", error.getClass().getName())
                    .addProperty("dlq.timestamp", Instant.now().toString())
                    .addProperty("dlq.retryCount", String.valueOf(retryCount))
                    .addProperty("dlq.originalPartitionKey", event.getPartitionKey());

            // Preserve original properties
            event.getProperties().forEach(dlqEvent::addContext);

            dlqProducer.send(Collections.singletonList(dlqEvent));

            log.info("Event sent to DLQ: {}", eventId);

        } catch (Exception e) {
            log.error("Failed to send event to DLQ", e);
            meterRegistry.counter("dlq.send.failures").increment();
        }
    }
}
