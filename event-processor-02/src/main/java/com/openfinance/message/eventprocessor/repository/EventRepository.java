package com.openfinance.message.eventprocessor.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface EventRepository extends JpaRepository<ProcessedEvent, String> {

    Optional<ProcessedEvent> findByOriginalId(String originalId);

    @Query("SELECT e FROM ProcessedEvent e WHERE e.processedAt >= :since ORDER BY e.processedAt DESC")
    List<ProcessedEvent> findRecentEvents(@Param("since") Instant since);

    @Query("SELECT COUNT(e) FROM ProcessedEvent e WHERE e.processedAt >= :since")
    long countEventsSince(@Param("since") Instant since);

    boolean existsByOriginalId(String originalId);
}
