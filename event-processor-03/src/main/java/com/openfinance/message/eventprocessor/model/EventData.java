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
public class EventData {

    private byte[] body;
    private Map<String, Object> properties;
    private Map<String, Object> systemProperties;
    private Long offset;
    private Long sequenceNumber;
    private Instant enqueuedTime;
    private String partitionKey;

    public String getContentType() {
        return properties != null ?
                (String) properties.getOrDefault("content-type", "application/json") :
                "application/json";
    }
}
