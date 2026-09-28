package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingAvailabilityException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of an activity's availability template.
 *
 * <p>Every operation loads the activity first and authorizes the caller against the activity's own
 * store before reading or writing any window. The activity id is the only thing the request carries,
 * so the company &rarr; country &rarr; region &rarr; zone &rarr; store chain the guard needs is
 * derived from the store itself, exactly as {@link SchedulingActivityService} does. Without that
 * check any principal holding {@code lc-scheduling} could rewrite another company's template.</p>
 *
 * <p>The template is replaced as a whole: a write deletes every stored window of the activity and
 * inserts the request's set, which is the only shape the availability editor needs.</p>
 */
@Service
public class SchedulingAvailabilityService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingAvailabilityService.class);

    private final SchedulingActivityRepository schedulingActivityRepository;
    private final SchedulingAvailabilityRepository schedulingAvailabilityRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public SchedulingAvailabilityService(
            SchedulingActivityRepository schedulingActivityRepository,
            SchedulingAvailabilityRepository schedulingAvailabilityRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.schedulingActivityRepository = schedulingActivityRepository;
        this.schedulingAvailabilityRepository = schedulingAvailabilityRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Reads the activity's availability template, ordered by weekday and then by start time.
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     */
    @Transactional(readOnly = true)
    public SchedulingAvailabilityResponse getAvailability(UUID activityId) {
        var activity = findActivity(activityId);
        verifyStoreAccess(activity.getCompanyStoreId());

        var windows = schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(activityId);
        return toResponse(activityId, windows);
    }

    /**
     * Replaces the activity's availability template with the request's set, whole-set semantics: an
     * empty list clears the template.
     *
     * <p>The current set is deleted and flushed before the new rows are inserted. Without the
     * explicit {@code flush()} Hibernate is free to order the new INSERTs ahead of the DELETE inside
     * one flush, and {@code UNIQUE(activity_id, day_of_week, start_time)} would then reject a window
     * that merely replaces the one it is identical to.</p>
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     * @throws InvalidSchedulingAvailabilityException when a window is impossible or two windows of
     *     the same weekday overlap (400)
     */
    @Transactional
    public SchedulingAvailabilityResponse replaceAvailability(UUID activityId, SchedulingAvailabilityRequest request) {
        var activity = findActivity(activityId);
        verifyStoreAccess(activity.getCompanyStoreId());

        var windows = request.windows();
        validateWindows(windows);

        schedulingAvailabilityRepository.deleteByActivityId(activityId);
        schedulingAvailabilityRepository.flush();

        schedulingAvailabilityRepository.saveAll(
                windows.stream().map(window -> toEntity(activityId, window)).toList());
        logger.info("SchedulingAvailability replaced: activityId={}, windows={}", activityId, windows.size());

        // Read back through the same ordered finder the read path uses, so a PUT response and the
        // GET that follows agree for any request order. The query flushes the rows inserted above
        // inside this same transaction before it selects them.
        var stored = schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(activityId);
        return toResponse(activityId, stored);
    }

    private SchedulingActivity findActivity(UUID activityId) {
        return schedulingActivityRepository
                .findById(activityId)
                .orElseThrow(() -> new SchedulingActivityNotFoundException(activityId));
    }

    /**
     * Rejects a window set the database would accept but the product must not: an empty or inverted
     * window, a validity range that runs backwards, and two overlapping windows on the same weekday.
     *
     * <p>Two windows on different weekdays never conflict, so the overlap scan groups by weekday. The
     * day range itself is bean validation, so an out-of-range weekday is rejected at the HTTP
     * boundary before this method runs.</p>
     */
    private void validateWindows(List<SchedulingAvailabilityWindowRequest> windows) {
        for (var window : windows) {
            if (window.endTime() == null
                    || window.startTime() == null
                    || !window.endTime().isAfter(window.startTime())) {
                throw new InvalidSchedulingAvailabilityException("endTime must be after startTime");
            }
            if (window.validTo() == null
                    || window.validFrom() == null
                    || window.validTo().isBefore(window.validFrom())) {
                throw new InvalidSchedulingAvailabilityException("validTo must not be before validFrom");
            }
        }

        windows.stream()
                .collect(Collectors.groupingBy(SchedulingAvailabilityWindowRequest::dayOfWeek))
                .forEach(this::assertNoOverlap);
    }

    /**
     * A weekday's windows are sorted by start time and each must start at or after the previous one
     * ends, so touching windows (previous end == next start) are allowed and a real overlap is not.
     */
    private void assertNoOverlap(Integer dayOfWeek, List<SchedulingAvailabilityWindowRequest> dayWindows) {
        var ordered = dayWindows.stream()
                .sorted(Comparator.comparing(SchedulingAvailabilityWindowRequest::startTime))
                .toList();
        for (var i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).startTime().isBefore(ordered.get(i - 1).endTime())) {
                throw new InvalidSchedulingAvailabilityException(
                        "availability windows overlap on dayOfWeek " + dayOfWeek);
            }
        }
    }

    /**
     * Loads the store and authorizes the caller for its whole scope.
     *
     * <p>A faithful copy of {@code SchedulingActivityService.resolveStore}, deliberately not
     * extracted into a shared helper: extracting it is its own work unit, and a ~10-line read-only
     * scope check is cheaper duplicated than coupling the two scheduling services through a new
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

    private SchedulingAvailability toEntity(UUID activityId, SchedulingAvailabilityWindowRequest window) {
        return SchedulingAvailability.builder()
                .activityId(activityId)
                .dayOfWeek((short) window.dayOfWeek().intValue())
                .startTime(window.startTime())
                .endTime(window.endTime())
                .validFrom(window.validFrom())
                .validTo(window.validTo())
                .enabled(true)
                .build();
    }

    private SchedulingAvailabilityResponse toResponse(UUID activityId, List<SchedulingAvailability> windows) {
        return new SchedulingAvailabilityResponse(
                activityId, windows.stream().map(this::toWindowResponse).toList());
    }

    private SchedulingAvailabilityWindowResponse toWindowResponse(SchedulingAvailability window) {
        return new SchedulingAvailabilityWindowResponse(
                window.getId(),
                window.getDayOfWeek(),
                window.getStartTime(),
                window.getEndTime(),
                window.getValidFrom(),
                window.getValidTo());
    }
}
