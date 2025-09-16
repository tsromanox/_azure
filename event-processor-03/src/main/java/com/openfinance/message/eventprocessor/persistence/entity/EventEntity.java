package com.openfinance.message.eventprocessor.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event_store", indexes = {
        @Index(name = "idx_aggregate_id", columnList = "aggregate_id"),
        @Index(name = "idx_event_type", columnList = "event_type"),
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_partition_offset", columnList = "partition_id, offset_value")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", columnDefinition = "jsonb")
    private Map<String, Object> eventData;

    @Column(name = "partition_id")
    private String partitionId;

    @Column(name = "offset_value")
    private Long offset;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "version")
    private Long version;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
