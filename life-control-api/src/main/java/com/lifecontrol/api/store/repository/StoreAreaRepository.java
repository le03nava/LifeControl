package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.StoreArea;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreAreaRepository extends JpaRepository<StoreArea, UUID> {

    List<StoreArea> findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(UUID companyStoreId);

    List<StoreArea> findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(UUID companyStoreId);

    Optional<StoreArea> findByIdAndCompanyStoreId(UUID id, UUID companyStoreId);

    boolean existsByCompanyStoreIdAndAreaCode(UUID companyStoreId, String areaCode);

    boolean existsByCompanyStoreIdAndAreaCodeAndIdNot(UUID companyStoreId, String areaCode, UUID excludeId);
}
