package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.StoreZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreZoneRepository extends JpaRepository<StoreZone, UUID> {

    List<StoreZone> findByStoreAreaIdAndEnabledTrueOrderByDisplayOrderAscZoneCodeAsc(UUID storeAreaId);

    List<StoreZone> findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(UUID storeAreaId);

    Optional<StoreZone> findByIdAndStoreAreaId(UUID id, UUID storeAreaId);

    boolean existsByStoreAreaIdAndZoneCode(UUID storeAreaId, String zoneCode);

    boolean existsByStoreAreaIdAndZoneCodeAndIdNot(UUID storeAreaId, String zoneCode, UUID excludeId);

    List<StoreZone> findByStoreAreaIdAndEnabledTrue(UUID storeAreaId);
}
