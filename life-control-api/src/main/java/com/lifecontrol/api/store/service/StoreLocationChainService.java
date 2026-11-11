package com.lifecontrol.api.store.service;

import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the location chain a store needs before it can hold stock: one area, one zone, one location,
 * and the {@code store_inventory_settings} row that names that location for receiving and for sales.
 *
 * <p><b>Why this exists.</b> A store created through {@code POST …/stores} used to be created without
 * any of the four rows, and {@code store_inventory_settings} is the precondition of the per-store stock
 * editor ({@code StockLedgerEngine#applyStockAdjustment} raises
 * {@code StoreInventorySettingsNotFoundException}). Setting stock by hand therefore answered 404 for a
 * store that had just been created, and the operator had to open the inventory-settings panel and pick
 * two locations before the first unit could be recorded. Since issue #859 the chain is created with the
 * store, so a store is born stock-ready.</p>
 *
 * <p><b>Why a dedicated collaborator and not {@link StoreAreaService}, {@link StoreZoneService} or
 * {@link StoreLocationService}.</b> Those services are shaped for an operator request: each resolves
 * the full company &rarr; country &rarr; region &rarr; zone &rarr; store path through
 * {@code CurrentUserContext#verifyCompanyStoreAccess}, refuses a soft-deleted ancestor and validates a
 * request body. A create path that re-entered them would re-run authorization it has already passed,
 * and would inherit guards written for a different caller. This class writes the four rows through the
 * entities' own builders and repositories and inherits none of that.</p>
 *
 * <p><b>The names are the unit's decision</b> ({@code odd/tasks/store-location-chain.md}, L3). Nothing
 * in the tree had ever created a chain row, so there was no canonical name to copy: the codes are
 * {@code DEF} and the names say <i>Default</i> on purpose, so an operator who later splits the store
 * into real areas can see at a glance which rows the server invented.</p>
 *
 * <p><b>Receiving and sales share the one location</b> (L4). The schema allows the two columns to name
 * different locations, and sharing keeps a first store's inventory in one bucket instead of inventing a
 * distinction the operator never made; splitting them later is an ordinary settings save on a screen
 * that already exists.</p>
 *
 * <p><b>Tenancy.</b> None of the four tables is armed by {@code apply_tenant_isolation}: their tenancy
 * comes from the {@code company_stores} row this chain hangs off, which the caller saved in its own
 * transaction. For the same reason the {@code company_store_id} of a zone and of a location is never
 * assigned here — {@code V59}'s triggers derive it from the stored parent and the mappings are
 * {@code insertable = false, updatable = false}.</p>
 */
@Service
public class StoreLocationChainService {

    private static final Logger logger = LoggerFactory.getLogger(StoreLocationChainService.class);

    /** L3: the code and name of the area the server creates for a store that has none. */
    private static final String DEFAULT_AREA_CODE = "DEF";

    private static final String DEFAULT_AREA_NAME = "Default area";

    /** L3: the same for the zone. */
    private static final String DEFAULT_ZONE_CODE = "DEF";

    private static final String DEFAULT_ZONE_NAME = "Default zone";

    /** L3: the same for the leaf location, which is both the receiving and the sales location (L4). */
    private static final String DEFAULT_LOCATION_CODE = "DEF";

    private static final String DEFAULT_LOCATION_NAME = "Default location";

    private final StoreAreaRepository storeAreaRepository;
    private final StoreZoneRepository storeZoneRepository;
    private final StoreLocationRepository storeLocationRepository;
    private final StoreInventorySettingsRepository storeInventorySettingsRepository;

    public StoreLocationChainService(
            StoreAreaRepository storeAreaRepository,
            StoreZoneRepository storeZoneRepository,
            StoreLocationRepository storeLocationRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository) {
        this.storeAreaRepository = storeAreaRepository;
        this.storeZoneRepository = storeZoneRepository;
        this.storeLocationRepository = storeLocationRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
    }

    /**
     * Creates the four rows of a store's default chain, in tree order: area, zone, location, settings.
     *
     * <p>Joins the caller's transaction, so the chain commits with the store it belongs to and a failure
     * rolls both back. That ordering is what keeps the {@code AFTER_COMMIT} listener of the store-created
     * event from creating a Keycloak group for a store whose chain failed to write.</p>
     *
     * <p><b>Why each row is flushed before the next is persisted.</b> Not for Hibernate's insert ordering:
     * {@code hibernate.order_inserts} is enabled ({@code application.properties}), but in hibernate-core 7.x that
     * sorts the pending inserts by their <i>foreign-key dependencies</i>, so a plain {@code save} in tree order would
     * already come out area → zone → location. The reason is the schema's own trigger: {@code V59}'s
     * {@code derive_store_zone_store_from_area} and {@code derive_store_location_store_from_zone} run
     * <b>{@code BEFORE INSERT}</b> and derive {@code company_store_id} by <b>selecting the parent row from the
     * database</b>. With the parent still pending in the persistence context, that select finds nothing, the trigger
     * derives {@code NULL} into a {@code NOT NULL} column, and the insert fails. Flushing each row executes its
     * insert before the child is built, so the trigger always has a parent to read.</p>
     *
     * <p>The settings row is flushed too, although nothing references it, so that every failure of this method
     * surfaces inside the call rather than at commit: the rollback covers it either way, but an error raised after
     * the store's response was built is the harder one to explain.</p>
     *     * @param store the store that was just saved, in the caller's transaction
     */
    @Transactional
    public void createLocationChain(CompanyStore store) {
        var area = storeAreaRepository.saveAndFlush(StoreArea.builder()
                .companyStore(store)
                .areaCode(DEFAULT_AREA_CODE)
                .areaName(DEFAULT_AREA_NAME)
                .enabled(true)
                .build());

        var zone = storeZoneRepository.saveAndFlush(StoreZone.builder()
                .storeArea(area)
                .zoneCode(DEFAULT_ZONE_CODE)
                .zoneName(DEFAULT_ZONE_NAME)
                .enabled(true)
                .build());

        var location = storeLocationRepository.saveAndFlush(StoreLocation.builder()
                .storeZone(zone)
                .locationCode(DEFAULT_LOCATION_CODE)
                .locationName(DEFAULT_LOCATION_NAME)
                .enabled(true)
                .build());

        var locationId = location.getId();
        storeInventorySettingsRepository.saveAndFlush(StoreInventorySettings.builder()
                .companyStoreId(store.getId())
                .receivingLocationId(locationId)
                .salesLocationId(locationId)
                .build());

        logger.info(
                "Store location chain created: storeId={}, areaId={}, zoneId={}, locationId={}",
                store.getId(),
                area.getId(),
                zone.getId(),
                locationId);
    }
}
