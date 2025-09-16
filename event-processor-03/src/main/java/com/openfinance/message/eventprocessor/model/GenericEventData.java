package com.openfinance.message.eventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenericEventData {

    private String eventId;
    private String eventType;
    private String aggregateId;
    private Long version;
    private Instant timestamp;
    private String source;
    private Map<String, Object> metadata;
    private JsonNode payload;

    // For correlation tracking
    private String correlationId;
    private String causationId;

    // For partitioning
    private String partitionKey;
}
