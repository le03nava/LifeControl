package com.lifecontrol.api.store.repository;

import com.lifecontrol.api.store.model.CompanyStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyStoreRepository extends JpaRepository<CompanyStore, UUID> {

    List<CompanyStore> findByCompanyZoneIdAndEnabledTrue(UUID companyZoneId);

    List<CompanyStore> findByCompanyZoneId(UUID companyZoneId);

    Optional<CompanyStore> findByIdAndCompanyZoneId(UUID id, UUID companyZoneId);

    boolean existsByStoreNameAndCompanyZoneIdAndIdNot(String storeName, UUID companyZoneId, UUID excludeId);

    boolean existsByStoreNameAndCompanyZoneId(String storeName, UUID companyZoneId);
}
