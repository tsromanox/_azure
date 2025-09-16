package com.openfinance.message.eventprocessor.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class EventPayload {
    private String id;
    private String tenantId;
    private String eventType;
    private Map<String, Object> data;
    private Instant timestamp;
}
