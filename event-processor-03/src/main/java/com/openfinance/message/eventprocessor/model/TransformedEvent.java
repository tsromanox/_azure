package com.openfinance.message.eventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformedEvent {

    private String eventId;
    private EventType eventType;
    private String aggregateId;
    private Long version;
    private Instant timestamp;
    private Map<String, Object> payload;
    private Map<String, Object> metadata;
    private String correlationId;
    private String causationId;
    private ProcessingPriority priority;

    public boolean requiresExternalCall() {
        return eventType != null && eventType.requiresExternalCall();
    }
}
