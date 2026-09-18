package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.StoreLocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreLocationRepository extends JpaRepository<StoreLocation, UUID> {

    List<StoreLocation> findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(UUID storeZoneId);

    List<StoreLocation> findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(UUID storeZoneId);

    Optional<StoreLocation> findByIdAndStoreZoneId(UUID id, UUID storeZoneId);

    boolean existsByStoreZoneIdAndLocationCode(UUID storeZoneId, String locationCode);

    boolean existsByStoreZoneIdAndLocationCodeAndIdNot(UUID storeZoneId, String locationCode, UUID excludeId);

    List<StoreLocation> findByStoreZoneIdAndEnabledTrue(UUID storeZoneId);
}
