package com.openfinance.message.eventprocessor.persistence;

import com.openfinance.message.eventprocessor.persistence.entity.DeadLetterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntity, String> {

    @Query(value = "SELECT * FROM dead_letter_queue WHERE status = 'FAILED' " +
            "AND retry_count < 3 ORDER BY failed_at ASC LIMIT :limit", nativeQuery = true)
    List<DeadLetterEntity> findRetriableEvents(int limit);

    List<DeadLetterEntity> findByStatus(String status);

    Long countByStatus(String status);
}
