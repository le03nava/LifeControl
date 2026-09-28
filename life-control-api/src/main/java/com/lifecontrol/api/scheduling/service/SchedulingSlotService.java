package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.scheduling.dto.SchedulingSlotResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingSlotRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialization of an activity's bookable slots for a requested range.
 *
 * <p>Every read loads the activity first and authorizes the caller against the activity's own store
 * before deriving any window, exactly as {@link SchedulingAvailabilityService} does: the request
 * carries only an activity id, so the company &rarr; country &rarr; region &rarr; zone &rarr; store
 * chain the guard needs is derived from the store itself. Without that check any principal holding
 * {@code lc-scheduling} could materialize another company's slots.</p>
 *
 * <p>Materialization expands the availability template into concrete instances and inserts them with
 * {@code ON CONFLICT DO NOTHING}, so requesting the same range twice is idempotent and an existing
 * slot's {@code booked} count is never disturbed. A booking therefore keeps its row across repeated
 * range reads; only an explicit template replacement reconciles the slots away, and only the unbooked
 * ones.</p>
 */
@Service
public class SchedulingSlotService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingSlotService.class);

    /**
     * Upper bound on a single materialization range. With the template's 50-window cap it is what
     * bounds one read's write volume: the range caps the dates visited and the window cap caps the
     * candidates per date, so a wider range would only enlarge the insert set of a single request.
     */
    private static final long MAX_RANGE_DAYS = 90;

    private final SchedulingActivityRepository schedulingActivityRepository;
    private final SchedulingAvailabilityRepository schedulingAvailabilityRepository;
    private final SchedulingSlotRepository schedulingSlotRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public SchedulingSlotService(
            SchedulingActivityRepository schedulingActivityRepository,
            SchedulingAvailabilityRepository schedulingAvailabilityRepository,
            SchedulingSlotRepository schedulingSlotRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.schedulingActivityRepository = schedulingActivityRepository;
        this.schedulingAvailabilityRepository = schedulingAvailabilityRepository;
        this.schedulingSlotRepository = schedulingSlotRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Materializes the activity's slots for {@code [from, to)} and reads them back ordered by start.
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     * @throws InvalidSchedulingSlotRangeException when {@code to} is not strictly after {@code from}
     *     or the span exceeds 90 days (400)
     */
    @Transactional
    public List<SchedulingSlotResponse> getSlots(UUID activityId, LocalDateTime from, LocalDateTime to) {
        var activity = findActivity(activityId);
        verifyStoreAccess(activity.getCompanyStoreId());

        validateRange(from, to);
        var created = materialize(activity, from, to);
        logger.info(
                "SchedulingSlot materialized: activityId={}, from={}, to={}, created={}",
                activityId,
                from,
                to,
                created);

        return schedulingSlotRepository
                .findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(activityId, from, to)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Rejects a range that cannot be expanded: an empty or inverted one, and one wider than the cap.
     * The cap is expressed as an inclusive end instant, so a range of exactly 90 days is accepted and
     * the first instant beyond it is not.
     */
    private void validateRange(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new InvalidSchedulingSlotRangeException("to must be after from");
        }
        if (to.isAfter(from.plusDays(MAX_RANGE_DAYS))) {
            throw new InvalidSchedulingSlotRangeException("the range must not exceed " + MAX_RANGE_DAYS + " days");
        }
    }

    /**
     * Walks every date of {@code [from, to)} against the activity's enabled windows and inserts each
     * candidate slot, returning how many rows the database actually created.
     *
     * <p>The weekday comparison is ISO-8601 on both sides: the stored {@code day_of_week} is the
     * template's convention (1 = Monday, 7 = Sunday) and {@link java.time.DayOfWeek#getValue()} is the
     * same numbering, so a window lands on the calendar date it names. The candidate instants are the
     * window start walked in steps of the activity's duration while a full step still fits inside the
     * window; instants outside {@code [from, to)} are skipped, which is the range clip.</p>
     */
    private int materialize(SchedulingActivity activity, LocalDateTime from, LocalDateTime to) {
        var windows =
                schedulingAvailabilityRepository
                        .findByActivityIdOrderByDayOfWeekAscStartTimeAsc(activity.getId())
                        .stream()
                        .filter(SchedulingAvailability::getEnabled)
                        .toList();
        var durationMinutes = activity.getDurationMinutes();
        var capacity = activity.getCapacityPerSlot();

        var created = 0;
        for (var date = from.toLocalDate(); date.atStartOfDay().isBefore(to); date = date.plusDays(1)) {
            var isoDay = (short) date.getDayOfWeek().getValue();
            for (var window : windows) {
                if (window.getDayOfWeek() != isoDay
                        || date.isBefore(window.getValidFrom())
                        || date.isAfter(window.getValidTo())) {
                    continue;
                }
                var windowEnd = LocalDateTime.of(date, window.getEndTime());
                for (var start = LocalDateTime.of(date, window.getStartTime());
                        !start.plusMinutes(durationMinutes).isAfter(windowEnd);
                        start = start.plusMinutes(durationMinutes)) {
                    if (start.isBefore(from) || !start.isBefore(to)) {
                        continue;
                    }
                    created += schedulingSlotRepository.insertIfAbsent(
                            activity.getId(), start, start.plusMinutes(durationMinutes), capacity);
                }
            }
        }
        return created;
    }

    private SchedulingSlotResponse toResponse(SchedulingSlot slot) {
        return new SchedulingSlotResponse(
                slot.getId(),
                slot.getActivityId(),
                slot.getStartAt(),
                slot.getEndAt(),
                slot.getCapacity(),
                slot.getBooked(),
                slot.getCapacity() - slot.getBooked(),
                slot.getStatus(),
                slot.getEnabled());
    }

    private SchedulingActivity findActivity(UUID activityId) {
        return schedulingActivityRepository
                .findById(activityId)
                .orElseThrow(() -> new SchedulingActivityNotFoundException(activityId));
    }

    /**
     * Loads the store and authorizes the caller for its whole scope.
     *
     * <p>A faithful copy of {@code SchedulingAvailabilityService.verifyStoreAccess}, deliberately not
     * extracted into a shared helper: extracting it is its own work unit, and a ~10-line read-only
     * scope check is cheaper duplicated than coupling the scheduling services through a new
     * abstraction.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope
     */
    private void verifyStoreAccess(UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));

        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();
        currentUserContext.verifyCompanyStoreAccess(
                country.getCompany().getId(), country.getId(), region.getId(), zone.getId(), store.getId());
    }
}
