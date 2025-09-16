package com.openfinance.message.eventprocessor.persistence;

import com.openfinance.message.eventprocessor.persistence.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventStore extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByAggregateIdOrderByVersionAsc(String aggregateId);

    Optional<EventEntity> findByAggregateIdAndVersion(String aggregateId, Long version);

    @Query("SELECT e FROM EventEntity e WHERE e.timestamp >= :start AND e.timestamp <= :end")
    List<EventEntity> findByTimestampRange(Instant start, Instant end);

    @Query("SELECT COUNT(e) FROM EventEntity e WHERE e.partitionId = :partitionId")
    Long countByPartition(String partitionId);

    @Query("SELECT MAX(e.offset) FROM EventEntity e WHERE e.partitionId = :partitionId")
    Optional<Long> findMaxOffsetByPartition(String partitionId);
}
