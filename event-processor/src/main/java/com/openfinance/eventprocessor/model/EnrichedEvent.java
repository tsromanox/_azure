package com.openfinance.eventprocessor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnrichedEvent {

    // Evento original
    private Event originalEvent;

    // Dados de enriquecimento
    private String enrichmentData;
    private Map<String, Object> enrichmentMetadata;

    // Metadados de processamento
    private Instant enrichedAt;
    private String processingNode;
    private Long processingTimeMs;
    private Integer retryCount;

    // Dados derivados das regras de negócio
    private String category;
    private Integer priority;
    private Set<String> tags;

    // Informações de roteamento
    private String targetPartition;
    private String targetTopic;

    // Flags de controle
    private boolean requiresReview;
    private boolean sensitiveData;

    // Rastreamento
    private String traceId;
    private String spanId;
    private String correlationId;

    // Helpers
    public String getEventId() {
        return originalEvent != null ? originalEvent.getId() : null;
    }

    public String getEnrichmentKey() {
        return originalEvent != null ? originalEvent.getEnrichmentKey() : null;
    }

    public boolean isHighPriority() {
        return priority != null && priority <= 1;
    }

    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }

    // Método para serialização compacta
    public Map<String, Object> toCompactMap() {
        return Map.of(
                "id", getEventId(),
                "enrichmentKey", getEnrichmentKey(),
                "category", category != null ? category : "UNKNOWN",
                "priority", priority != null ? priority : 99,
                "enrichedAt", enrichedAt.toString(),
                "processingTimeMs", processingTimeMs != null ? processingTimeMs : 0
        );
    }
}
