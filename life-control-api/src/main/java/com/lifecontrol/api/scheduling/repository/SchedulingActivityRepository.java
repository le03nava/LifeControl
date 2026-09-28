package com.lifecontrol.api.scheduling.repository;

import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the per-store activity catalogue.
 *
 * <p>Every finder is scoped by {@code companyStoreId}: the store is the tenant boundary of the
 * domain, so there is no cross-store read. The list finders order by name so the catalogue has a
 * deterministic, human-meaningful page order without the caller supplying a sort.</p>
 *
 * <p>The two {@code existsBy…} finders are the duplicate check behind the
 * {@code UNIQUE(company_store_id, activity_name)} constraint. The create path uses the plain form;
 * the update path uses the {@code …AndIdNot} form so a row never collides with itself.</p>
 */
@Repository
public interface SchedulingActivityRepository extends JpaRepository<SchedulingActivity, UUID> {

    Page<SchedulingActivity> findByCompanyStoreIdOrderByActivityNameAsc(UUID companyStoreId, Pageable pageable);

    Page<SchedulingActivity> findByCompanyStoreIdAndEnabledTrueOrderByActivityNameAsc(
            UUID companyStoreId, Pageable pageable);

    boolean existsByCompanyStoreIdAndActivityName(UUID companyStoreId, String activityName);

    boolean existsByCompanyStoreIdAndActivityNameAndIdNot(UUID companyStoreId, String activityName, UUID excludeId);
}
