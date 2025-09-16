package com.openfinance.message.eventprocessor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class EnrichedEvent {
    private String id;
    private String tenantId;
    private String eventType;
    private Map<String, Object> originalData;
    private Map<String, Object> enrichedData;
    private Instant originalTimestamp;
    private Instant enrichmentTimestamp;
    private String processorId;

    public String getPartitionKey() {
        return String.valueOf(tenantId.hashCode() % 32);
    }

    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize enriched event", e);
        }
    }
}
