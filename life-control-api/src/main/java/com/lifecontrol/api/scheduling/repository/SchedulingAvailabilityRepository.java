package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the per-activity availability template.
 *
 * <p>The read finder orders by weekday and then by start time in the database, so the client gets a
 * deterministic week order without supplying a sort. The delete finder is the whole-set replacement
 * step of {@code replaceAvailability}: the template is edited as a unit, never window by window.</p>
 */
@Repository
public interface SchedulingAvailabilityRepository extends JpaRepository<SchedulingAvailability, UUID> {

    List<SchedulingAvailability> findByActivityIdOrderByDayOfWeekAscStartTimeAsc(UUID activityId);

    void deleteByActivityId(UUID activityId);
}
