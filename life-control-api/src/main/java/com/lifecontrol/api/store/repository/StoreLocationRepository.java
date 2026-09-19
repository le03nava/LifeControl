package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.StoreLocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreLocationRepository extends JpaRepository<StoreLocation, UUID> {

    List<StoreLocation> findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(UUID storeZoneId);

    List<StoreLocation> findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(UUID storeZoneId);

    Optional<StoreLocation> findByIdAndStoreZoneId(UUID id, UUID storeZoneId);

    boolean existsByStoreZoneIdAndLocationCode(UUID storeZoneId, String locationCode);

    boolean existsByStoreZoneIdAndLocationCodeAndIdNot(UUID storeZoneId, String locationCode, UUID excludeId);

    List<StoreLocation> findByStoreZoneIdAndEnabledTrue(UUID storeZoneId);

    /**
     * Lists the enabled locations of a whole store by joining the location tree
     * ({@code store_locations -> store_zones -> store_areas -> company_stores}).
     *
     * <p>There is no store-scoped listing anywhere else: every existing finder is scoped to a store
     * zone. Ordering is deterministic over the same keys the per-zone listing uses, extended
     * upwards to the area and the zone ({@code area, zone, location} each by display order then
     * code), so the picker can present the store's locations in a stable order.</p>
     *
     * <p>Only {@code location.enabled} is filtered, mirroring {@link
     * #findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(UUID)}: a decommissioned
     * leaf is never offered as a receiving or sales location.</p>
     */
    @Query("""
            SELECT location FROM StoreLocation location
            JOIN location.storeZone zone
            JOIN zone.storeArea area
            JOIN area.companyStore store
            WHERE store.id = :companyStoreId
              AND location.enabled = true
            ORDER BY area.displayOrder ASC, area.areaCode ASC,
                     zone.displayOrder ASC, zone.zoneCode ASC,
                     location.displayOrder ASC, location.locationCode ASC
            """)
    List<StoreLocation> findEnabledByCompanyStoreId(@Param("companyStoreId") UUID companyStoreId);
}
