package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.product.repository.VariantListingRepository;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #859, against real PostgreSQL: a store created through {@code POST …/stores} is born
 * <b>stock-ready</b>, because the create path also writes the location chain
 * {@code store_inventory_settings} requires — one area, one zone, one location, and one settings row
 * naming that location for both receiving and sales (specs S1, S2 and S4 of
 * {@code odd/tasks/store-location-chain.md}, decisions L3 and L4).
 *
 * <p>The four cases are deliberately separate, because each one would pass for a different reason:</p>
 * <ul>
 *   <li><b>S1</b> — the stock write of the per-store editor answers 200 for a store nothing was
 *       configured on. Before the change the same call answered 404
 *       {@code Store inventory settings not found for store id: …}, which is the failure this suite
 *       was written from.</li>
 *   <li><b>S2 + S4</b> — the chain is exactly one area, one zone and one location with the L3 codes
 *       and names, the store-scoped listing offers them, and the settings row names that single
 *       location twice.</li>
 *   <li><b>S6</b> — the chain is a <b>precondition</b>, not the trigger: the variant is absent from
 *       the store-scoped search until the stock write of S1 records a per-store row. Pinning only the
 *       presence after the write would let a future change make the chain itself the trigger
 *       unnoticed.</li>
 *   <li><b>S4</b> — the store-delete cascade disables the chain it now finds, so creating a chain did
 *       not create rows nothing can clean up.</li>
 * </ul>
 *
 * <p><b>Fixtures.</b> The suite seeds its own company, hierarchy, product and variant, so no other
 * class's rows can decide any answer here, and it removes everything it wrote — the store and its
 * chain included, since the store is created through the endpoint and the shared container is one per
 * JVM. The modelling of the store/variant fixtures follows
 * {@code inventory/StoreInventorySettingsIntegrationTest} and
 * {@code product/ProductVariantStoreStockWriteIntegrationTest}, including their transaction and
 * tenant discipline: armed tables are read and written inside
 * {@code TenantFixtures.inTenant}/{@code stateTenantWithinTheOpenTransaction}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Store location chain creation Integration Tests")
class StoreLocationChainCreationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String STORES_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";
    private static final String STORE_URL = STORES_URL + "/{storeId}";
    private static final String STORE_LOCATIONS_URL = STORE_URL + "/store-locations";
    private static final String INVENTORY_SETTINGS_URL = STORE_URL + "/inventory-settings";
    private static final String STORE_STOCK_URL = "/api/variants/{variantId}/stores/{storeId}";
    private static final String SEARCH_URL = "/api/product-variants/search";

    /** L3: the codes and names this unit states for the auto-created chain. */
    private static final String DEFAULT_AREA_CODE = "DEF";

    private static final String DEFAULT_AREA_NAME = "Default area";
    private static final String DEFAULT_ZONE_CODE = "DEF";
    private static final String DEFAULT_ZONE_NAME = "Default zone";
    private static final String DEFAULT_LOCATION_CODE = "DEF";
    private static final String DEFAULT_LOCATION_NAME = "Default location";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private VariantListingRepository variantListingRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID productId;
    private UUID variantId;

    /** The text every search of this suite matches, so no other class's rows can answer one. */
    private String searchToken;

    /** The stores this suite created through the endpoint, so the teardown removes their chains. */
    private final List<UUID> createdStoreIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seedCompanyHierarchy();

        searchToken = "SLCC-" + UUID.randomUUID().toString().substring(0, 8);
        var product = TenantFixtures.inTenant(
                companyId,
                () -> productRepository.save(Product.builder()
                        .sku("SLCC-" + UUID.randomUUID().toString().substring(0, 8))
                        .name(searchToken + " producto")
                        .company(companyRepository.findById(companyId).orElseThrow())
                        .enabled(true)
                        .build()));
        productId = product.getId();

        var variant = TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(productId)
                        .companyId(companyId)
                        .measureUnitId(pieceUnitId())
                        .barCode("SLCC-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName(searchToken + " variante")
                        .enabled(true)
                        .build()));
        variantId = variant.getId();
    }

    @AfterEach
    void tearDown() {
        // The stock write of S1 books the ledger, whose rows reference the variant and the location with no
        // cascade and which is append-only: they go first, through the one helper that can remove them.
        if (companyId != null) {
            deleteStockLedger(jdbcTemplate, companyId);
        }

        if (companyId != null && variantId != null) {
            for (var storeId : List.copyOf(createdStoreIds)) {
                TenantFixtures.inTenant(companyId, () -> {
                    variantListingRepository
                            .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                            .ifPresent(listing -> variantListingRepository.deleteById(listing.getId()));
                    productVariantStoreStockRepository
                            .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                            .ifPresent(row -> productVariantStoreStockRepository.deleteById(row.getId()));
                });
            }
        }

        // The chain this suite's stores now own is removed leaf-first: store_inventory_settings references
        // store_locations with no cascade, so a leftover settings row would break another suite's
        // `storeLocationRepository.deleteAll()` — the failure mode the delete order below prevents.
        for (var storeId : List.copyOf(createdStoreIds)) {
            deleteStoreAndChain(storeId);
        }
        createdStoreIds.clear();

        if (companyId != null) {
            TenantFixtures.inTenant(companyId, () -> {
                if (variantId != null) {
                    productVariantRepository.deleteById(variantId);
                }
                if (productId != null) {
                    productRepository.deleteById(productId);
                }
            });
        }
        variantId = null;
        productId = null;
    }

    // ─────────────────────────── S1: the store is stock-ready ───────────────────────────

    @Test
    @DisplayName("S1: a store created through POST accepts the stock write with no manual step")
    void storeCreatedThroughTheEndpointAcceptsStock() throws Exception {
        var storeId = createStoreThroughTheEndpoint("SLCC Stock Ready Store");

        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal("7"));

        var response = mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
                .andReturn()
                .getResponse();

        // The body is part of the failure message on purpose: the refusal this suite was written from is a 404
        // whose text names the missing row, and a bare "expected 200 but was 404" would not say which.
        assertThat(response.getStatus())
                .as("the stock write is accepted: %s", response.getContentAsString())
                .isEqualTo(200);
        var responseBody = response.getContentAsString();
        assertThat(objectMapper.readTree(responseBody).get("companyStoreId").asText())
                .isEqualTo(storeId.toString());

        assertThat(objectMapper.readTree(responseBody).get("stock").decimalValue())
                .as("the write answers with the quantity the ledger now holds")
                .isEqualByComparingTo("7");

        // The ledger booked the adjustment at the single location the chain created: the store's sales
        // location is that leaf (L4), so the movement names it and the store's position is the quantity.
        assertThat(storePositionOf(storeId, variantId)).isEqualByComparingTo("7");
        var rows = ledgerRowsOf(variantId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().storeLocationId())
                .as("the created location is the store's sales location, so it is where the stock landed")
                .isEqualTo(singleLocationOf(storeId).getId());
        assertThat(rows.getFirst().companyStoreId()).isEqualTo(storeId);
    }

    // ─────────────────────────── S2 + S4: the chain is readable and named twice ───────────────────────────

    @Test
    @DisplayName("S2/S4: the chain is one area, one zone and one location, and the settings name it twice")
    void theCreatedChainIsReadableAndTheSettingsNameTheSingleLocation() throws Exception {
        var storeId = createStoreThroughTheEndpoint("SLCC Readable Chain Store");

        // The store-scoped listing the receiving/sales picker reads: exactly the one created location,
        // carrying the L3 codes and names up the tree.
        mockMvc.perform(get(STORE_LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].locationCode").value(DEFAULT_LOCATION_CODE))
                .andExpect(jsonPath("$[0].locationName").value(DEFAULT_LOCATION_NAME))
                .andExpect(jsonPath("$[0].zoneCode").value(DEFAULT_ZONE_CODE))
                .andExpect(jsonPath("$[0].zoneName").value(DEFAULT_ZONE_NAME))
                .andExpect(jsonPath("$[0].areaCode").value(DEFAULT_AREA_CODE))
                .andExpect(jsonPath("$[0].areaName").value(DEFAULT_AREA_NAME));

        // One row per level, all enabled: the listing offers enabled leaves only, so the enabled flag is
        // read back from the rows themselves rather than inferred from the listing.
        TenantFixtures.inTenant(companyId, () -> {
            var areas = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId);
            assertThat(areas).hasSize(1);
            var area = areas.getFirst();
            assertThat(area.getAreaCode()).isEqualTo(DEFAULT_AREA_CODE);
            assertThat(area.getAreaName()).isEqualTo(DEFAULT_AREA_NAME);
            assertThat(area.getEnabled()).isTrue();

            var zones = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId());
            assertThat(zones).hasSize(1);
            var zone = zones.getFirst();
            assertThat(zone.getZoneCode()).isEqualTo(DEFAULT_ZONE_CODE);
            assertThat(zone.getZoneName()).isEqualTo(DEFAULT_ZONE_NAME);
            assertThat(zone.getEnabled()).isTrue();
            assertThat(zone.getCompanyStore().getId())
                    .as("the store of a zone is derived by the V59 trigger, never assigned by the writer")
                    .isEqualTo(storeId);

            var locations =
                    storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId());
            assertThat(locations).hasSize(1);
            var location = locations.getFirst();
            assertThat(location.getLocationCode()).isEqualTo(DEFAULT_LOCATION_CODE);
            assertThat(location.getLocationName()).isEqualTo(DEFAULT_LOCATION_NAME);
            assertThat(location.getEnabled()).isTrue();
            assertThat(location.getCompanyStore().getId()).isEqualTo(storeId);
        });

        // L4: receiving and sales share the one location, and the panel reads that pair back.
        var locationId = singleLocationOf(storeId).getId();
        var settings = settingsOf(storeId).orElseThrow();
        assertThat(settings.getReceivingLocationId()).isEqualTo(locationId);
        assertThat(settings.getSalesLocationId()).isEqualTo(locationId);

        mockMvc.perform(get(INVENTORY_SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                .andExpect(jsonPath("$.receivingLocationId").value(locationId.toString()))
                .andExpect(jsonPath("$.salesLocationId").value(locationId.toString()));
    }

    // ─────────────────────────── S6: the chain is a precondition, not the trigger ───────────────────────────

    @Test
    @DisplayName("S6: the variant appears in the store's search only after the stock write")
    void theVariantIsAbsentFromTheStoreSearchUntilTheStockWrite() throws Exception {
        var storeId = createStoreThroughTheEndpoint("SLCC Search Store");

        // The premise: the store exists with its chain and the ledger holds nothing for the variant. The chain
        // changes nothing about the search predicate, which keys on per-store rows
        // (product_variant_store_stock, variant_listing, stock_position) and names no location.
        assertThat(ledgerRowsOf(variantId)).isEmpty();

        search(storeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(singleLocationOf(storeId)).isNotNull();

        writeStock(storeId, "5");

        var body = search(storeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode stock = objectMapper.readTree(body).get("content").get(0).get("stock");
        assertThat(stock.decimalValue())
                .as("the article is visible with the quantity the write recorded")
                .isEqualByComparingTo("5");
    }

    // ─────────────────────────── S4: the delete cascade reaches the new chain ───────────────────────────

    @Test
    @DisplayName("S4: soft-deleting the store disables the area, the zone and the location it created")
    void theStoreDeleteCascadeDisablesTheCreatedChain() throws Exception {
        var storeId = createStoreThroughTheEndpoint("SLCC Cascade Store");
        var locationId = singleLocationOf(storeId).getId();

        mockMvc.perform(delete(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(editorJwt()))
                .andExpect(status().isNoContent());

        TenantFixtures.inTenant(companyId, () -> {
            assertThat(companyStoreRepository.findById(storeId).orElseThrow().getEnabled())
                    .isFalse();

            var areas = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId);
            assertThat(areas).hasSize(1);
            assertThat(areas.getFirst().getEnabled()).isFalse();

            var zones = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(
                    areas.getFirst().getId());
            assertThat(zones).hasSize(1);
            assertThat(zones.getFirst().getEnabled()).isFalse();

            var locations = storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(
                    zones.getFirst().getId());
            assertThat(locations).hasSize(1);
            assertThat(locations.getFirst().getEnabled()).isFalse();
            assertThat(locations.getFirst().getId()).isEqualTo(locationId);
        });

        // The disabled leaf is no longer offered as a receiving or sales location.
        mockMvc.perform(get(STORE_LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("S4: soft-deleting the store removes its settings, so a stock write answers 404 afterwards")
    void theStoreDeleteRemovesTheInventorySettingsSoALaterStockWriteAnswers404() throws Exception {
        var storeId = createStoreThroughTheEndpoint("SLCC Settings Removed Store");

        // The premise, asserted so this case cannot pass on a store that never had the row: the write the delete
        // must take away is accepted before the delete.
        writeStock(storeId, "3");

        mockMvc.perform(delete(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(editorJwt()))
                .andExpect(status().isNoContent());

        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal("4"));
        var response = mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus())
                .as("a deleted store answers as one that never had settings: %s", response.getContentAsString())
                .isEqualTo(404);

        assertThat(settingsOf(storeId))
                .as("the settings row is an address for new writes, not history")
                .isEmpty();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /**
     * Creates a store through the endpoint the issue is about, and remembers it so the teardown removes the
     * chain the create path now writes. The response is asserted here so a failed create cannot be mistaken
     * for a missing chain later.
     */
    private UUID createStoreThroughTheEndpoint(String storeName) throws Exception {
        var body = mockMvc.perform(post(STORES_URL, companyId, companyCountryId, regionId, zoneId)
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCompanyStoreRequest(storeName, null, null, null))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var storeId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        createdStoreIds.add(storeId);
        return storeId;
    }

    private void writeStock(UUID storeId, String stock) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal(stock));

        mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
                .andExpect(status().isOk());
    }

    private ResultActions search(UUID storeId) throws Exception {
        return mockMvc.perform(get(SEARCH_URL)
                .param("q", searchToken)
                .param("storeId", storeId.toString())
                .with(editorJwt()));
    }

    /**
     * The one enabled location the store owns, read through the store-scoped query the listing endpoint
     * uses. A store with none — which is the state before this change — fails here by name instead of on a
     * {@code NullPointerException} three assertions later.
     */
    private StoreLocation singleLocationOf(UUID storeId) {
        var locations =
                TenantFixtures.inTenant(companyId, () -> storeLocationRepository.findEnabledByCompanyStoreId(storeId));
        assertThat(locations)
                .as("the store created through the endpoint owns exactly one enabled location")
                .hasSize(1);
        return locations.getFirst();
    }

    private Optional<StoreInventorySettings> settingsOf(UUID storeId) {
        return storeInventorySettingsRepository.findById(storeId);
    }

    /** The store's sellable aggregate, from the ledger's projection, read under this suite's tenant. */
    private BigDecimal storePositionOf(UUID storeId, UUID targetVariantId) {
        var quantities = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        targetVariantId));
        assertThat(quantities).hasSize(1);
        return quantities.getFirst();
    }

    /** One row of {@code stock_movement}, as the fields the booking cases assert. */
    private record LedgerRow(String movementType, BigDecimal quantity, UUID companyStoreId, UUID storeLocationId) {}

    private List<LedgerRow> ledgerRowsOf(UUID targetVariantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT movement_type, quantity, company_store_id, store_location_id
                          FROM stock_movement
                         WHERE product_variant_id = ?
                         ORDER BY occurred_at, id
                        """,
                        (rs, row) -> new LedgerRow(
                                rs.getString("movement_type"),
                                rs.getBigDecimal("quantity"),
                                rs.getObject("company_store_id", UUID.class),
                                rs.getObject("store_location_id", UUID.class)),
                        targetVariantId));
    }

    /**
     * Removes one store and the chain under it, leaf-first: settings, locations, zones, areas, then the store
     * itself, which {@code store_areas} references with no cascade.
     */
    private void deleteStoreAndChain(UUID storeId) {
        storeInventorySettingsRepository.findById(storeId).ifPresent(storeInventorySettingsRepository::delete);
        TenantFixtures.inTenant(companyId, () -> {
            for (StoreArea area : storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId)) {
                for (StoreZone zone :
                        storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId())) {
                    storeLocationRepository.deleteAll(
                            storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(
                                    zone.getId()));
                    storeZoneRepository.deleteById(zone.getId());
                }
                storeAreaRepository.deleteById(area.getId());
            }
            companyStoreRepository.findById(storeId).ifPresent(companyStoreRepository::delete);
        });
    }

    /** A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone chain. Idempotent because the
     * Testcontainers database is shared per JVM, and one {@code inTenant} boundary states the tenant for the
     * whole tree, whose nodes are rows on (or reference) a policy-armed table.
     */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = TenantFixtures.findOrCreate(
                companyRepository, "STORE-LOCATION-CHAIN-KEY", "Store Location Chain Test Company", "SLCC010101AB");
        companyId = company.getId();

        TenantFixtures.inTenant(companyId, () -> {
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(company.getId(), country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));

            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode("SLC")
                                    .regionName("Store Location Chain Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("SLC")
                            .zoneName("Store Location Chain Zone")
                            .enabled(true)
                            .build()));

            companyCountryId = companyCountry.getId();
            regionId = region.getId();
            zoneId = zone.getId();
        });
    }

    /**
     * The principal of every request here: {@code lc-admin}, the username the ledger records as the actor,
     * and the company.
     *
     * <p>{@code /api/variants/{variantId}/stores/{storeId}} and the variant search name no company in their
     * paths, so their tenant comes from the token's claims; without the claim the store is invisible and
     * every write would answer 404 for a reason that says nothing about the chain.</p>
     */
    private RequestPostProcessor editorJwt() {
        return jwt().jwt(builder ->
                        builder.claim("preferred_username", "chain-creator").claim("company_id", companyId.toString()))
                .authorities(ROLE_LC_ADMIN);
    }
}
