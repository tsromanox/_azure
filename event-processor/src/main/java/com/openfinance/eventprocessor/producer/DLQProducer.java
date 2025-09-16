package com.openfinance.eventprocessor.producer;

import com.azure.messaging.eventhubs.*;

import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.eventprocessor.model.DLQMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DLQProducer {

    private final EventHubProducerClient dlqProducer;
    private final ObjectMapper objectMapper;
    private final CustomMetrics metrics;

    /**
     * Envia mensagem para DLQ com metadados de erro
     */
    public void sendToDLQ(EventData originalEvent, Exception error,
                          String partitionId, int retryCount) {
        try {
            DLQMessage dlqMessage = buildDLQMessage(originalEvent, error,
                    partitionId, retryCount);

            String messageJson = objectMapper.writeValueAsString(dlqMessage);
            EventData dlqEvent = new EventData(messageJson.getBytes(StandardCharsets.UTF_8));

            // Adiciona propriedades para facilitar queries
            dlqEvent.getProperties().put("ErrorType", error.getClass().getSimpleName());
            dlqEvent.getProperties().put("OriginalPartition", partitionId);
            dlqEvent.getProperties().put("RetryCount", retryCount);
            dlqEvent.getProperties().put("FailedAt", Instant.now().toString());

            // Envia para DLQ
            CreateBatchOptions options = new CreateBatchOptions()
                    .setPartitionKey(partitionId); // Mantém mesma chave de partição

            EventDataBatch batch = dlqProducer.createBatch(options);
            batch.tryAdd(dlqEvent);
            dlqProducer.send(batch);

            log.warn("Evento enviado para DLQ - EventId: {}, Error: {}",
                    originalEvent.getProperties().get("EventId"),
                    error.getMessage());

            metrics.incrementDLQMessages();

        } catch (Exception e) {
            log.error("CRÍTICO: Falha ao enviar para DLQ", e);
            metrics.incrementDLQFailures();
            // Aqui você pode implementar um fallback adicional
            // como salvar em arquivo local ou banco de dados
        }
    }

    /**
     * Constrói mensagem DLQ com todas informações necessárias
     */
    private DLQMessage buildDLQMessage(EventData originalEvent,
                                       Exception error,
                                       String partitionId,
                                       int retryCount) {
        return DLQMessage.builder()
                .originalEventId(String.valueOf(originalEvent.getProperties().get("EventId")))
                .originalPartitionId(partitionId)
                .originalSequenceNumber(originalEvent.getSequenceNumber())
                .originalEventBody(new String(originalEvent.getBody(), StandardCharsets.UTF_8))
                .originalProperties(originalEvent.getProperties())
                .originalEnqueuedTime(originalEvent.getEnqueuedTime())
                .errorType(error.getClass().getName())
                .errorMessage(error.getMessage())
                .errorStackTrace(getStackTraceAsString(error))
                .retryCount(retryCount)
                .failedAt(Instant.now())
                .processingNode(getHostname())
                .metadata(Map.of(
                        "processorVersion", getClass().getPackage().getImplementationVersion(),
                        "javaVersion", System.getProperty("java.version")
                ))
                .build();
    }

    /**
     * Processa batch de mensagens falhas
     */
    public void sendBatchToDLQ(List<EventData> failedEvents,
                               Exception error,
                               String partitionId) {
        List<EventData> dlqEvents = new ArrayList<>();

        for (EventData originalEvent : failedEvents) {
            try {
                DLQMessage dlqMessage = buildDLQMessage(originalEvent, error,
                        partitionId, 0);
                String messageJson = objectMapper.writeValueAsString(dlqMessage);
                EventData dlqEvent = new EventData(messageJson.getBytes(StandardCharsets.UTF_8));
                dlqEvents.add(dlqEvent);
            } catch (Exception e) {
                log.error("Erro ao preparar mensagem DLQ", e);
            }
        }

        if (!dlqEvents.isEmpty()) {
            sendBatch(dlqEvents);
        }
    }

    private void sendBatch(List<EventData> events) {
        try {
            EventDataBatch batch = dlqProducer.createBatch();
            for (EventData event : events) {
                if (!batch.tryAdd(event)) {
                    // Batch cheio, envia e cria novo
                    dlqProducer.send(batch);
                    batch = dlqProducer.createBatch();
                    batch.tryAdd(event);
                }
            }
            // Envia batch final
            if (batch.getCount() > 0) {
                dlqProducer.send(batch);
            }
        } catch (Exception e) {
            log.error("Erro ao enviar batch para DLQ", e);
        }
    }

    private String getStackTraceAsString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }

    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
