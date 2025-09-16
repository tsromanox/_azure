package com.openfinance.eventprocessor.producer;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.openfinance.eventprocessor.model.EnrichedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventProducer {

    private final EventHubProducerClient destinationProducer;
    private final ObjectMapper objectMapper;

    @Qualifier("virtualThreadExecutor")
    private final AsyncTaskExecutor executor;

    private final AtomicLong sentEvents = new AtomicLong(0);

    /**
     * Envia evento único de forma assíncrona usando Virtual Threads
     */
    public CompletableFuture<Void> sendEventAsync(EnrichedEvent event) {
        return CompletableFuture.runAsync(() -> {
            sendEvent(event);
        }, executor);
    }

    /**
     * Envia evento único
     */
    public void sendEvent(EnrichedEvent event) {
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            EventData eventData = new EventData(eventJson.getBytes(StandardCharsets.UTF_8));

            // Adiciona propriedades para rastreamento
            eventData.getProperties().put("EventId", event.getOriginalEvent().getId());
            eventData.getProperties().put("EnrichedAt", event.getEnrichedAt().toString());
            eventData.getProperties().put("ProcessingNode", event.getProcessingNode());

            // Cria batch com uma única mensagem
            CreateBatchOptions options = new CreateBatchOptions()
                    .setPartitionKey(event.getOriginalEvent().getPartitionKey());

            EventDataBatch batch = destinationProducer.createBatch(options);
            if (!batch.tryAdd(eventData)) {
                throw new RuntimeException("Evento muito grande para o batch");
            }

            destinationProducer.send(batch);

            long count = sentEvents.incrementAndGet();
            if (count % 1000 == 0) {
                log.info("Total de eventos enviados: {}", count);
            }

        } catch (Exception e) {
            log.error("Erro ao enviar evento: {}", event.getOriginalEvent().getId(), e);
            throw new RuntimeException("Falha ao enviar evento", e);
        }
    }

    /**
     * Envia batch de eventos de forma otimizada
     */
    public CompletableFuture<Void> sendBatchAsync(List<EnrichedEvent> events) {
        return CompletableFuture.runAsync(() -> {
            sendBatch(events);
        }, executor);
    }

    /**
     * Envia batch de eventos
     */
    public void sendBatch(List<EnrichedEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        try {
            List<EventDataBatch> batches = new ArrayList<>();
            EventDataBatch currentBatch = destinationProducer.createBatch();

            for (EnrichedEvent event : events) {
                String eventJson = objectMapper.writeValueAsString(event);
                EventData eventData = new EventData(eventJson.getBytes(StandardCharsets.UTF_8));

                if (!currentBatch.tryAdd(eventData)) {
                    // Batch cheio, cria novo
                    batches.add(currentBatch);
                    currentBatch = destinationProducer.createBatch();

                    if (!currentBatch.tryAdd(eventData)) {
                        log.error("Evento muito grande: {}", event.getOriginalEvent().getId());
                    }
                }
            }

            // Adiciona último batch
            if (currentBatch.getCount() > 0) {
                batches.add(currentBatch);
            }

            // Envia todos os batches em paralelo usando Virtual Threads
            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(() ->
                            destinationProducer.send(batch), executor))
                    .toList();

            // Aguarda conclusão de todos
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            sentEvents.addAndGet(events.size());
            log.debug("Batch de {} eventos enviado com sucesso", events.size());

        } catch (Exception e) {
            log.error("Erro ao enviar batch de eventos", e);
            throw new RuntimeException("Falha ao enviar batch", e);
        }
    }
}
