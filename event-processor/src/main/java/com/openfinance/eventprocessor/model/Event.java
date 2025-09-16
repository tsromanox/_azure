package com.openfinance.eventprocessor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    @JsonProperty("id")
    private String id;

    @JsonProperty("enrichmentKey")
    private String enrichmentKey;

    @JsonProperty("partitionKey")
    private String partitionKey;

    @JsonProperty("data")
    private String data;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("type")
    private String type;

    @JsonProperty("source")
    private String source;

    @JsonProperty("version")
    private String version;

    // Metadados do Event Hub (não serializados no JSON original)
    private String partitionId;
    private Long sequenceNumber;
    private Instant enqueuedTime;
    private Long offset;

    // Properties adicionais do evento
    private Map<String, Object> properties;

    // Metadados de processamento
    private Integer retryCount;
    private String lastError;

    // Validação
    public boolean isValid() {
        return id != null && !id.trim().isEmpty() &&
                enrichmentKey != null && !enrichmentKey.trim().isEmpty();
    }

    // Helpers
    public String getEventKey() {
        return String.format("%s-%s-%d", partitionId, id, sequenceNumber);
    }

    public boolean isOld(int maxAgeSeconds) {
        if (timestamp == null) return false;
        return timestamp.isBefore(Instant.now().minusSeconds(maxAgeSeconds));
    }
}
