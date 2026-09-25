package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreInventorySettingsRepository extends JpaRepository<StoreInventorySettings, UUID> {

    /**
     * The settings row of one store. The store is the identity, so {@code company_store_id} is the
     * primary key and this is only the self-documenting form of {@link #findById}; it exists so the
     * sales deduction reads the store's priority location without a raw id lookup at the call site.
     */
    Optional<StoreInventorySettings> findByCompanyStoreId(UUID companyStoreId);
}
