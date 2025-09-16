package com.openfinance.message.eventprocessor.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository para controle de offsets do Azure EventHub
 * Usado para garantir exactly-once processing
 */
@Repository
public interface OffsetRepository extends JpaRepository<ProcessedOffset, String> {

    @Query("SELECT COUNT(o) > 0 FROM ProcessedOffset o WHERE o.partitionId = :partitionId AND o.sequenceNumber = :sequenceNumber")
    boolean isSequenceNumberProcessed(@Param("partitionId") String partitionId,
                                      @Param("sequenceNumber") Long sequenceNumber);

    @Query("SELECT o FROM ProcessedOffset o WHERE o.partitionId = :partitionId ORDER BY o.sequenceNumber DESC")
    List<ProcessedOffset> findByPartitionIdOrderBySequenceNumberDesc(@Param("partitionId") String partitionId);

    @Query("SELECT o FROM ProcessedOffset o WHERE o.partitionId = :partitionId AND o.sequenceNumber = " +
            "(SELECT MAX(o2.sequenceNumber) FROM ProcessedOffset o2 WHERE o2.partitionId = :partitionId)")
    Optional<ProcessedOffset> findLatestByPartition(@Param("partitionId") String partitionId);

    @Modifying
    @Query("DELETE FROM ProcessedOffset o WHERE o.processedAt < :cutoffTime")
    int deleteOldOffsets(@Param("cutoffTime") Instant cutoffTime);

    default void saveProcessedSequenceNumber(String partitionId, Long sequenceNumber) {
        ProcessedOffset processedOffset = new ProcessedOffset();
        processedOffset.setId(partitionId + "-" + sequenceNumber);
        processedOffset.setPartitionId(partitionId);
        processedOffset.setSequenceNumber(sequenceNumber);
        processedOffset.setProcessedAt(Instant.now());
        save(processedOffset);
    }

    // Método para limpeza periódica de offsets antigos
    @Modifying
    @Query("DELETE FROM ProcessedOffset o WHERE o.processedAt < :cutoffTime AND " +
            "o.sequenceNumber < (SELECT MAX(o2.sequenceNumber) - 1000 FROM ProcessedOffset o2 WHERE o2.partitionId = o.partitionId)")
    int cleanupOldOffsets(@Param("cutoffTime") Instant cutoffTime);
}
