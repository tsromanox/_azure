package com.openfinance.message.eventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingResult {

    private ProcessingStatus status;
    private String eventId;
    private Instant processedAt;
    private Duration processingTime;
    private String errorMessage;
    private Map<String, Object> resultData;
    private int retryCount;

    public static ProcessingResult success() {
        return ProcessingResult.builder()
                .status(ProcessingStatus.SUCCESS)
                .processedAt(Instant.now())
                .build();
    }

    public static ProcessingResult failure(String errorMessage) {
        return ProcessingResult.builder()
                .status(ProcessingStatus.FAILED)
                .errorMessage(errorMessage)
                .processedAt(Instant.now())
                .build();
    }

    public static ProcessingResult retry(String reason, int retryCount) {
        return ProcessingResult.builder()
                .status(ProcessingStatus.RETRY)
                .errorMessage(reason)
                .retryCount(retryCount)
                .processedAt(Instant.now())
                .build();
    }
}
