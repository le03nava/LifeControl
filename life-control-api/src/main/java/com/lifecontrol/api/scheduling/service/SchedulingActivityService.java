package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityResponse;
import com.lifecontrol.api.scheduling.exception.DuplicateSchedulingActivityException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of the per-store activity catalogue.
 *
 * <p>Every operation resolves — and authorizes — the store identified by the request before touching
 * an activity. The endpoints are flat (the store travels as a query/body parameter, not in the URL),
 * so the company &rarr; country &rarr; region &rarr; zone &rarr; store chain the guard needs is
 * derived from the store itself, exactly as {@code SalesOrderService},
 * {@code StoreInventorySettingsService} and {@code GoodsReceiptService} do. Without that check any
 * principal holding {@code lc-scheduling} could read or write another company's catalogue.</p>
 *
 * <p>Deletion is a soft delete ({@code enabled = false}); {@link #enable(UUID)} reverses it, the same
 * re-enable every other soft-deleted domain in this repo exposes ({@code customer}, {@code shift},
 * {@code salesorder}).</p>
 */
@Service
public class SchedulingActivityService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulingActivityService.class);

    /**
     * Message of the 412 raised when the request's optional version precondition does not hold. It
     * names no activity or version number: the client only needs to know the row moved.
     */
    private static final String VERSION_CONFLICT_MESSAGE =
            "The scheduling activity conflicts with the current server state; reload and try again";

    private final SchedulingActivityRepository schedulingActivityRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public SchedulingActivityService(
            SchedulingActivityRepository schedulingActivityRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.schedulingActivityRepository = schedulingActivityRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Reads a page of the store's activities, ordered by name.
     *
     * @param storeId         the store whose catalogue is read
     * @param includeDisabled when {@code true} the soft-deleted activities travel too
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the store's scope
     */
    @Transactional(readOnly = true)
    public Page<SchedulingActivityResponse> getActivities(UUID storeId, boolean includeDisabled, Pageable pageable) {
        resolveStore(storeId);

        var page = includeDisabled
                ? schedulingActivityRepository.findByCompanyStoreIdOrderByActivityNameAsc(storeId, pageable)
                : schedulingActivityRepository.findByCompanyStoreIdAndEnabledTrueOrderByActivityNameAsc(
                        storeId, pageable);

        return page.map(this::toResponse);
    }

    /**
     * Reads one activity.
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     */
    @Transactional(readOnly = true)
    public SchedulingActivityResponse getActivity(UUID id) {
        var activity = findActivity(id);
        resolveStore(activity.getCompanyStoreId());
        return toResponse(activity);
    }

    /**
     * Creates an activity in the store named by the request.
     *
     * <p>{@code enabled} defaults to {@code true} when the request omits it.</p>
     *
     * @throws IllegalArgumentException when {@code companyStoreId} is null (400)
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the store's scope
     * @throws DuplicateSchedulingActivityException when the store already has an activity with that
     *     name
     */
    @Transactional
    public SchedulingActivityResponse create(SchedulingActivityRequest request) {
        // The store is required on create but the DTO is shared with update (where it is ignored),
        // so it cannot carry @NotNull. Guard it here rather than letting a null reach findById,
        // which would surface as a 500 instead of a 400.
        if (request.companyStoreId() == null) {
            throw new IllegalArgumentException("companyStoreId is required");
        }
        resolveStore(request.companyStoreId());

        if (schedulingActivityRepository.existsByCompanyStoreIdAndActivityName(
                request.companyStoreId(), request.activityName())) {
            throw new DuplicateSchedulingActivityException(
                    "Activity with name '" + request.activityName() + "' already exists in this store");
        }

        var activity = SchedulingActivity.builder()
                .companyStoreId(request.companyStoreId())
                .userId(request.userId())
                .activityName(request.activityName())
                .description(request.description())
                .durationMinutes(request.durationMinutes())
                .capacityPerSlot(request.capacityPerSlot())
                .enabled(request.enabled() == null || request.enabled())
                .build();

        // Flush so the response carries the real @Version and the non-null createdAt/updatedAt: both
        // the version increment and the Auditable callbacks run at flush time.
        var saved = schedulingActivityRepository.saveAndFlush(activity);
        logger.info(
                "SchedulingActivity created: id={}, storeId={}, name={}",
                saved.getId(),
                saved.getCompanyStoreId(),
                saved.getActivityName());
        return toResponse(saved);
    }

    /**
     * Updates an activity in place. The store is immutable: {@code request.companyStoreId()} is
     * ignored, so a client can round-trip a GET response into a PUT.
     *
     * <p>The version precondition is asserted after the activity is loaded and before any mutation,
     * so a stale request leaves the row exactly as it was. A {@code null} version means "no
     * precondition".</p>
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     * @throws VersionPreconditionException when the request asserted a version the row no longer
     *     carries (412)
     * @throws DuplicateSchedulingActivityException when another activity of the store already has
     *     that name
     */
    @Transactional
    public SchedulingActivityResponse update(UUID id, SchedulingActivityRequest request) {
        var activity = findActivity(id);
        resolveStore(activity.getCompanyStoreId());

        assertVersionPrecondition(request.version(), activity.getVersion());

        if (schedulingActivityRepository.existsByCompanyStoreIdAndActivityNameAndIdNot(
                activity.getCompanyStoreId(), request.activityName(), id)) {
            throw new DuplicateSchedulingActivityException(
                    "Activity with name '" + request.activityName() + "' already exists in this store");
        }

        activity.setActivityName(request.activityName());
        activity.setUserId(request.userId());
        activity.setDescription(request.description());
        activity.setDurationMinutes(request.durationMinutes());
        activity.setCapacityPerSlot(request.capacityPerSlot());
        if (request.enabled() != null) {
            activity.setEnabled(request.enabled());
        }

        // Flush, do not merely save: Hibernate increments the @Version at flush time, so mapping the
        // response from a non-flushed entity would answer with the pre-increment version. A client
        // that echoes that version would then be rejected with a false 412 on its next write.
        var saved = schedulingActivityRepository.saveAndFlush(activity);
        logger.info("SchedulingActivity updated: id={}, name={}", saved.getId(), saved.getActivityName());
        return toResponse(saved);
    }

    /**
     * Soft-deletes an activity ({@code enabled = false}).
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     */
    @Transactional
    public void delete(UUID id) {
        var activity = findActivity(id);
        resolveStore(activity.getCompanyStoreId());

        activity.setEnabled(false);
        schedulingActivityRepository.saveAndFlush(activity);
        logger.info("SchedulingActivity soft-deleted: id={}, name={}", id, activity.getActivityName());
    }

    /**
     * Re-enables a soft-deleted activity ({@code enabled = true}), the inverse of
     * {@link #delete(UUID)}.
     *
     * @throws SchedulingActivityNotFoundException when the activity does not exist
     * @throws CompanyStoreNotFoundException when the activity's store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller cannot
     *     access the activity's store
     */
    @Transactional
    public SchedulingActivityResponse enable(UUID id) {
        var activity = findActivity(id);
        resolveStore(activity.getCompanyStoreId());

        activity.setEnabled(true);
        // Flush so the response carries the post-increment @Version and the fresh updatedAt.
        var saved = schedulingActivityRepository.saveAndFlush(activity);
        logger.info("SchedulingActivity re-enabled: id={}, name={}", id, saved.getActivityName());
        return toResponse(saved);
    }

    private SchedulingActivity findActivity(UUID id) {
        return schedulingActivityRepository.findById(id).orElseThrow(() -> new SchedulingActivityNotFoundException(id));
    }

    /**
     * Loads the store and authorizes the caller for its whole scope.
     *
     * <p>The request carries only the store id, so the chain the guard needs is derived from the
     * store itself. The load runs first because it is what produces the chain; the access check is
     * the next statement, before any activity is read or written. A missing store is a 404 through
     * {@link CompanyStoreNotFoundException}, not a new exception.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope
     */
    private CompanyStore resolveStore(UUID storeId) {
        var store =
                companyStoreRepository.findById(storeId).orElseThrow(() -> new CompanyStoreNotFoundException(storeId));

        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();
        currentUserContext.verifyCompanyStoreAccess(
                country.getCompany().getId(), country.getId(), region.getId(), zone.getId(), store.getId());

        return store;
    }

    /**
     * Enforces the request's optional version precondition against the freshly-loaded state.
     *
     * <p>{@code null} means "no precondition" and always passes. A non-null version must equal the
     * stored one: a client that read a version and finds the world moved gets a 412 instead of
     * silently overwriting the other writer. The stored version is taken as a primitive
     * {@code long}, deliberately not its wrapper, because comparing wrappers with {@code !=} would
     * compare references instead of values.</p>
     *
     * @throws VersionPreconditionException when a non-null version does not match the current stored
     *     version
     */
    private void assertVersionPrecondition(Long assertedVersion, long storedVersion) {
        if (assertedVersion == null) {
            return;
        }
        if (assertedVersion != storedVersion) {
            logger.info("SchedulingActivity version precondition failed: assertedVersion={}", assertedVersion);
            throw new VersionPreconditionException(VERSION_CONFLICT_MESSAGE);
        }
    }

    private SchedulingActivityResponse toResponse(SchedulingActivity activity) {
        return new SchedulingActivityResponse(
                activity.getId(),
                activity.getCompanyStoreId(),
                activity.getUserId(),
                activity.getActivityName(),
                activity.getDescription(),
                activity.getDurationMinutes(),
                activity.getCapacityPerSlot(),
                activity.getEnabled(),
                activity.getVersion(),
                activity.getCreatedAt(),
                activity.getUpdatedAt());
    }
}
