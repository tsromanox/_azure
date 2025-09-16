package com.openfinance.message.eventprocessor.repository;

import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository para Event Store
 */
@Repository
public interface EventStoreRepository extends JpaRepository<EventStoreEntry, String> {

    List<EventStoreEntry> findByAggregateIdOrderByTimestamp(String aggregateId);

    List<EventStoreEntry> findByEventTypeOrderByTimestamp(String eventType);

    List<EventStoreEntry> findByTimestampBetweenOrderByTimestamp(Instant start, Instant end);

    long countByAggregateId(String aggregateId);
}
