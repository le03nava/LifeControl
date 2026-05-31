package com.lifecontrol.api.activity.repository;

import com.lifecontrol.api.activity.model.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    /**
     * Paginated query with optional filters for date range, process name,
     * event name, user ID, and HTTP method.
     *
     * @param from       optional start of date range (inclusive)
     * @param to         optional end of date range (inclusive)
     * @param process    optional process name (exact match)
     * @param event      optional event name (exact match)
     * @param userId     optional user ID (exact match)
     * @param httpMethod optional HTTP method (exact match)
     * @param pageable   pagination parameters
     * @return a page of matching activity logs
     */
    @Query("""
            SELECT l FROM ActivityLog l
            JOIN l.activityProcess p
            JOIN l.activityEvent e
            WHERE (:from IS NULL OR l.createdAt >= :from)
              AND (:to IS NULL OR l.createdAt <= :to)
              AND (:process IS NULL OR p.name = :process)
              AND (:event IS NULL OR e.name = :event)
              AND (:userId IS NULL OR l.userId = :userId)
              AND (:httpMethod IS NULL OR l.httpMethod = :httpMethod)
            """)
    Page<ActivityLog> findByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("process") String process,
            @Param("event") String event,
            @Param("userId") String userId,
            @Param("httpMethod") String httpMethod,
            Pageable pageable
    );
}
