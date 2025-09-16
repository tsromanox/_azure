package com.openfinance.message.eventprocessor.service;

import com.openfinance.message.eventprocessor.model.EventData;
import com.openfinance.message.eventprocessor.persistence.DeadLetterRepository;
import com.openfinance.message.eventprocessor.persistence.entity.DeadLetterEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterQueueService {

    private final DeadLetterRepository deadLetterRepository;

    @Transactional
    public void sendToDLQ(EventData eventData, Throwable error) {
        try {
            DeadLetterEntity entity = DeadLetterEntity.builder()
                    .eventData(eventData.getBody())
                    .properties(eventData.getProperties())
                    .errorMessage(error.getMessage())
                    .errorStackTrace(getStackTrace(error))
                    .partitionKey(eventData.getPartitionKey())
                    .offset(eventData.getOffset())
                    .sequenceNumber(eventData.getSequenceNumber())
                    .enqueuedTime(eventData.getEnqueuedTime())
                    .failedAt(Instant.now())
                    .retryCount(0)
                    .status("FAILED")
                    .build();

            deadLetterRepository.save(entity);
            log.info("Event sent to DLQ. Offset: {}, Error: {}",
                    eventData.getOffset(), error.getMessage());

        } catch (Exception e) {
            log.error("Failed to save event to DLQ", e);
        }
    }

    @Transactional
    public List<DeadLetterEntity> getRetriableEvents(int limit) {
        return deadLetterRepository.findRetriableEvents(limit);
    }

    @Transactional
    public void markAsRetried(String id) {
        deadLetterRepository.findById(id).ifPresent(entity -> {
            entity.setRetryCount(entity.getRetryCount() + 1);
            entity.setLastRetryAt(Instant.now());
            entity.setStatus("RETRYING");
            deadLetterRepository.save(entity);
        });
    }

    @Transactional
    public void markAsProcessed(String id) {
        deadLetterRepository.findById(id).ifPresent(entity -> {
            entity.setStatus("PROCESSED");
            entity.setProcessedAt(Instant.now());
            deadLetterRepository.save(entity);
        });
    }

    private String getStackTrace(Throwable throwable) {
        if (throwable == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append(throwable.getClass().getName()).append(": ").append(throwable.getMessage()).append("\n");

        StackTraceElement[] stackTrace = throwable.getStackTrace();
        int maxLines = Math.min(stackTrace.length, 10);
        for (int i = 0; i < maxLines; i++) {
            sb.append("\tat ").append(stackTrace[i].toString()).append("\n");
        }

        if (stackTrace.length > 10) {
            sb.append("\t... ").append(stackTrace.length - 10).append(" more\n");
        }

        return sb.toString();
    }
}