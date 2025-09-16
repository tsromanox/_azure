package com.openfinance.message.eventprocessor.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "dead_letter_queue", indexes = {
        @Index(name = "idx_dlq_status", columnList = "status"),
        @Index(name = "idx_dlq_retry_count", columnList = "retry_count"),
        @Index(name = "idx_dlq_failed_at", columnList = "failed_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Lob
    @Column(name = "event_data", columnDefinition = "BLOB")
    private byte[] eventData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", columnDefinition = "jsonb")
    private Map<String, Object> properties;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    private String errorStackTrace;

    @Column(name = "partition_key")
    private String partitionKey;

    @Column(name = "offset_value")
    private Long offset;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "enqueued_time")
    private Instant enqueuedTime;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "status", nullable = false)
    private String status; // FAILED, RETRYING, PROCESSED, ABANDONED

    @Column(name = "processed_at")
    private Instant processedAt;
}
