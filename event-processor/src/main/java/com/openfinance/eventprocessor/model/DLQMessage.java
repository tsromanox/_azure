package com.openfinance.eventprocessor.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class DLQMessage {
    private String originalEventId;
    private String originalPartitionId;
    private Long originalSequenceNumber;
    private String originalEventBody;
    private Map<String, Object> originalProperties;
    private Instant originalEnqueuedTime;

    private String errorType;
    private String errorMessage;
    private String errorStackTrace;
    private Integer retryCount;
    private Instant failedAt;
    private String processingNode;

    // Metadados adicionais
    private Map<String, String> metadata;
}
