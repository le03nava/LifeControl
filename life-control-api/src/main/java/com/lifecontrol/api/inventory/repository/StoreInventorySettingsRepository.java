package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreInventorySettingsRepository extends JpaRepository<StoreInventorySettings, UUID> {}
