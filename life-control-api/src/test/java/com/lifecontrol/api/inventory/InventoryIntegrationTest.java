package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence-level verification of the inventory core against real PostgreSQL with Flyway enabled
 * and {@code ddl-auto=validate}, so a green run proves {@code V10__inventory.sql} and the entity
 * mappings agree.
 *
 * <p>Covers the receipt as one {@code stock_movement} row and the {@code stock_position =
 * SUM(stock_balance)} invariant after a receipt and after a sale, the create-then-reuse behaviour of the
 * {@code (variant, location)} row, the database-level uniqueness and the append-only ledger. Every balance
 * here is read from the ledger's projections, because since T3c of {@code odd/tasks/ledger-mirror-retirement.md}
 * nothing maintains the two legacy {@code stock} columns. The store/location fixtures are built through the
 * real store endpoints, exactly like the other store-chain integration tests, so the chain comes from the
 * same code path the API uses.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Inventory Integration Tests")
class InventoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String STORE_BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}";
    private static final String AREA_BASE_URL = STORE_BASE_URL + "/areas";
    private static final String ZONE_BASE_URL = AREA_BASE_URL + "/{areaId}/store-zones";
    private static final String LOCATION_BASE_URL = ZONE_BASE_URL + "/{storeZoneId}/store-locations";

    private static final String AREA_CODE = "IA01";
    private static final String ZONE_CODE = "IZ01";
    private static final String LOCATION_CODE = "IL01";
    /** A second tenant, only ever used to own a variant another company must not resolve. */
    private static final String FOREIGN_COMPANY_KEY = "INVENTORY-FOREIGN-KEY";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private ProductVariantLocationRepository productVariantLocationRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

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

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID locationId;

    @BeforeEach
    void setUp() throws Exception {
        // inventory_movements and product_variant_locations reference product_variants and
        // store_locations with no cascade: delete the inventory rows first so any integration class
        // that runs later in this shared JVM can delete products and locations wholesale.
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();

        seedCompanyHierarchy();
        deleteTheStockLedger();
        locationId = ensureLocation();
    }

    @AfterEach
    void tearDown() {
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
        deleteTheStockLedger();
    }

    /**
     * Deletes the stock ledger rows this suite's writers booked, through the shared helper.
     *
     * <p>Since T5a of {@code odd/tasks/inventory-ledger.md} every receipt, sale, reversal and manual edit also
     * writes {@code stock_movement}, which references {@code product_variants} and {@code store_locations}
     * with no cascade and is append-only, so the inherited {@code inventoryMovementRepository.deleteAll()}
     * cannot touch it. A row left behind would block any later suite in this shared JVM from deleting the
     * variant or the location it names.</p>
     */
    private void deleteTheStockLedger() {
        if (companyId == null) {
            return;
        }
        deleteStockLedger(jdbcTemplate, companyId);
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain.
     * Idempotent because the Testcontainers database is shared per JVM and the data survives
     * across methods.
     *
     * <p>One {@link TenantFixtures#inTenant} around the children, not one per statement: every child of
     * the chain is a row on a policy-armed table and references the detached {@code Company} above, so
     * both its {@code INSERT} and the flush's unsaved-value probe need the parent's tenant installed.</p>
     */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = TenantFixtures.findOrCreate(
                companyRepository, "INVENTORY-KEY", "Inventory Test Company", "INVT010101ABC");

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
                                    .regionCode("INV")
                                    .regionName("Inventory Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("INV")
                            .zoneName("Inventory Zone")
                            .enabled(true)
                            .build()));

            var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> "Inventory Test Store".equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Inventory Test Store")
                            .enabled(true)
                            .build()));

            companyCountryId = companyCountry.getId();
            regionId = region.getId();
            zoneId = zone.getId();
            storeId = store.getId();
        });
    }

    /** Creates the area, zone and location through the store endpoints and returns the location id. */
    private UUID ensureLocation() throws Exception {
        var areaId = ensureArea();
        var storeZoneId = ensureStoreZone(areaId);

        var existing =
                storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId).stream()
                        .filter(location -> LOCATION_CODE.equals(location.getLocationCode()))
                        .findFirst();
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        mockMvc.perform(post(
                                LOCATION_BASE_URL,
                                companyId,
                                companyCountryId,
                                regionId,
                                zoneId,
                                storeId,
                                areaId,
                                storeZoneId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreLocationRequest(LOCATION_CODE, "Receiving shelf", null, 1))))
                .andExpect(status().isCreated());

        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId).stream()
                .filter(location -> LOCATION_CODE.equals(location.getLocationCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private UUID ensureArea() throws Exception {
        var existing = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                .filter(area -> AREA_CODE.equals(area.getAreaCode()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        mockMvc.perform(post(AREA_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreAreaRequest(AREA_CODE, "Warehouse", null, 1))))
                .andExpect(status().isCreated());

        return storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                .filter(area -> AREA_CODE.equals(area.getAreaCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private UUID ensureStoreZone(UUID areaId) throws Exception {
        var existing = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId).stream()
                .filter(zone -> ZONE_CODE.equals(zone.getZoneCode()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreZoneRequest(ZONE_CODE, "Aisle", null, 1))))
                .andExpect(status().isCreated());

        return storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId).stream()
                .filter(zone -> ZONE_CODE.equals(zone.getZoneCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    /**
     * A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. The unit is required
     * since ADR-0006 decision 3 ({@code V55}), and a random uuid would be refused by the foreign key —
     * which would make the fixture fail for a reason that says nothing about the rule under test.
     */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    /**
     * A variant registered in this suite's store with {@code stock} units on hand.
     *
     * <p><b>The stock is booked through the ledger, and that is the point since T3c.</b> This fixture used to
     * write {@code product_variant_store_stock.stock} and nothing else, which was a second truth beside the
     * ledger; the write path stops maintaining that column, so a fixture that still seeded it would describe a
     * world the code no longer lives in. A receipt is the only way to seed stock now.</p>
     */
    private ProductVariant createVariant(String stock) {
        var created = TenantFixtures.inTenant(companyId, () -> {
            var product = productRepository.save(Product.builder()
                    .sku("INV-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("Inventory Test Product")
                    .company(companyRepository.findById(companyId).orElseThrow())
                    .enabled(true)
                    .build());

            var variant = productVariantRepository.save(ProductVariant.builder()
                    .productId(product.getId())
                    .companyId(companyId)
                    .measureUnitId(pieceUnitId())
                    .barCode("INV-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                    .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                    .enabled(true)
                    .build());

            productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                    .productVariantId(variant.getId())
                    .companyStoreId(storeId)
                    .costPrice(new BigDecimal("10.00"))
                    .listPrice(new BigDecimal("20.00"))
                    .build());
            return variant;
        });

        var seeded = new BigDecimal(stock);
        if (seeded.signum() > 0) {
            applyReceipt(created.getId(), stock);
        }
        return created;
    }

    /**
     * Applies a receipt under this class's company and answers the line identity it booked.
     *
     * <p>{@link InventoryService} derives its tenant from the request principal at the transaction
     * boundary; a test that calls it without an HTTP request has no principal, so the fixture states the
     * company the store belongs to. Without it the service's first read of {@code company_stores} answers
     * not-found instead of reaching the rule under test.</p>
     *
     * @return the {@code goods_receipt_items} id the ledger row names as its source, so a case can point at the
     *     row it booked instead of counting rows and trusting their order
     */
    private UUID applyReceipt(UUID variantId, String quantity) {
        var referenceId = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applyReceipt(
                        variantId,
                        storeId,
                        locationId,
                        new BigDecimal(quantity),
                        StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                        referenceId,
                        "receiver"));
        return referenceId;
    }

    private Optional<ProductVariantLocation> balanceOf(UUID variantId) {
        return productVariantLocationRepository.findByProductVariantIdAndStoreLocationId(variantId, locationId);
    }

    /**
     * The sellable aggregate of this store, read from the ledger's projection.
     *
     * <p>It used to read {@code product_variant_store_stock.stock}. Since T3c nothing maintains that column, so
     * reading it would make every assertion about a receipt or a sale agree with a frozen number - the silent
     * agreement this slice exists to remove. {@code stock_position} is derived from the movements by trigger,
     * which is what makes it the answer.</p>
     */
    private BigDecimal storePositionOf(UUID variantId) {
        return storePositionOptionally(variantId)
                .orElseThrow(() ->
                        new AssertionError("no stock_position row for variant " + variantId + " in store " + storeId));
    }

    /**
     * The same projection, or empty when the trigger never booked one.
     *
     * <p>A refusal case asserts emptiness instead of a number: nothing was written, so there is no position to
     * read at all, and asking for one row would report "no row" - the right answer stated in a way that could
     * not also catch a row that landed on the wrong quantity.</p>
     */
    private Optional<BigDecimal> storePositionOptionally(UUID variantId) {
        var quantities = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variantId));
        return quantities.stream().findFirst();
    }

    /** One location's balance, from the projection the trigger maintains. */
    private BigDecimal locationStockOf(UUID variantId, UUID storeLocationId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeLocationId,
                        variantId));
    }

    /**
     * One row of {@code stock_movement}, as the fields a booking case asserts.
     *
     * <p>Since T3c the ledger is the only record of a movement: {@code inventory_movements} is no longer
     * written, so a case that wants to say what was booked reads here. The projections above are the only
     * balances.</p>
     */
    private record LedgerRow(
            String movementType,
            String sourceType,
            UUID sourceId,
            BigDecimal quantity,
            UUID companyStoreId,
            UUID storeLocationId,
            String actor,
            OffsetDateTime occurredAt) {}

    private List<LedgerRow> ledgerRowsOf(UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT movement_type, source_type, source_id, quantity, company_store_id,
                               store_location_id, actor, occurred_at
                          FROM stock_movement
                         WHERE product_variant_id = ?
                         ORDER BY occurred_at, id
                        """,
                        (rs, row) -> new LedgerRow(
                                rs.getString("movement_type"),
                                rs.getString("source_type"),
                                rs.getObject("source_id", UUID.class),
                                rs.getBigDecimal("quantity"),
                                rs.getObject("company_store_id", UUID.class),
                                rs.getObject("store_location_id", UUID.class),
                                rs.getString("actor"),
                                rs.getObject("occurred_at", OffsetDateTime.class)),
                        variantId));
    }

    @Nested
    @DisplayName("first receipt")
    class FirstReceiptTests {

        @Test
        @DisplayName("should create the balance row and write exactly one RECEIPT movement")
        void firstReceiptCreatesBalanceAndOneMovement() {
            var variant = createVariant("0.00");
            var referenceId = UUID.randomUUID();

            TenantFixtures.inTenant(
                    companyId,
                    () -> inventoryService.applyReceipt(
                            variant.getId(),
                            storeId,
                            locationId,
                            new BigDecimal("12.50"),
                            StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                            referenceId,
                            "receiver"));

            // The (variant, location) row still exists and still names the pair the lock order is keyed by;
            // its stock column has been frozen since T3c, so the number on hand is read from the projections.
            var balance = balanceOf(variant.getId()).orElseThrow();
            assertThat(balance.getProductVariantId()).isEqualTo(variant.getId());
            assertThat(balance.getStoreLocationId()).isEqualTo(locationId);
            assertThat(balance.getCreatedAt()).isNotNull();
            assertThat(balance.getUpdatedAt()).isNotNull();

            assertThat(locationStockOf(variant.getId(), locationId)).isEqualByComparingTo("12.50");
            assertThat(storePositionOf(variant.getId())).isEqualByComparingTo("12.50");

            var movements = ledgerRowsOf(variant.getId());
            assertThat(movements).hasSize(1);
            var movement = movements.getFirst();
            assertThat(movement.movementType()).isEqualTo(StockLedgerWriter.RECEIPT);
            assertThat(movement.quantity()).isEqualByComparingTo("12.50");
            assertThat(movement.companyStoreId()).isEqualTo(storeId);
            assertThat(movement.storeLocationId()).isEqualTo(locationId);
            assertThat(movement.sourceType())
                    .as("the row names the source the caller stated: the receipt line")
                    .isEqualTo(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
            assertThat(movement.sourceId()).isEqualTo(referenceId);
            assertThat(movement.actor()).isEqualTo("receiver");
            assertThat(movement.occurredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("accumulation")
    class AccumulationTests {

        @Test
        @DisplayName("should accumulate both balances on a second receipt and leave the first movement untouched")
        void secondReceiptAccumulatesAndLedgerRowsAreImmutable() {
            var variant = createVariant("0.00");

            var firstReference = applyReceipt(variant.getId(), "6.00");
            var bookedFirst = ledgerRowsOf(variant.getId());
            assertThat(bookedFirst).hasSize(1);
            var firstMovement = bookedFirst.getFirst();

            var secondReference = applyReceipt(variant.getId(), "2.25");

            assertThat(locationStockOf(variant.getId(), locationId)).isEqualByComparingTo("8.25");
            assertThat(storePositionOf(variant.getId())).isEqualByComparingTo("8.25");

            // The second receipt reuses the existing (variant, location) row instead of duplicating it...
            assertThat(productVariantLocationRepository.count()).isEqualTo(1);
            // ...and appends a second movement without modifying the first one (append-only ledger).
            var bookedAfter = ledgerRowsOf(variant.getId());
            assertThat(bookedAfter).hasSize(2);
            assertThat(bookedAfter)
                    .filteredOn(row -> row.sourceId().equals(firstReference))
                    .as("the first movement is still there, with the quantity and timestamp it was booked with")
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.movementType()).isEqualTo(StockLedgerWriter.RECEIPT);
                        assertThat(row.quantity()).isEqualByComparingTo("6.00");
                        assertThat(row.occurredAt()).isEqualTo(firstMovement.occurredAt());
                    });
            assertThat(bookedAfter)
                    .filteredOn(row -> row.sourceId().equals(secondReference))
                    .as("the second receipt appended a row of its own")
                    .singleElement()
                    .satisfies(row -> assertThat(row.quantity()).isEqualByComparingTo("2.25"));
        }
    }

    /**
     * The W3-D5 invariant, as the ledger states it since T3c: the store's projection equals the sum of the
     * location balances the store owns.
     *
     * <p>Both sides are the ledger's now. The left side used to be {@code product_variant_store_stock.stock} and
     * the right side {@code SUM(product_variant_locations.stock)} - two columns nothing maintains any more, so
     * reading them would compare two frozen numbers and call it agreement. {@code stock_position} and
     * {@code stock_balance} are derived from {@code stock_movement} by the V61 trigger, so the sum is taken
     * over the projection and the locations are resolved through {@code store_locations.company_store_id} - the
     * same ownership the store projection is keyed by, which keeps a balance row of another store from being
     * summed in silently.</p>
     */
    private void assertInvariant(UUID variantId) {
        var locationSum = TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(b.quantity), 0)
                          FROM stock_balance b
                          JOIN store_locations l ON l.id = b.store_location_id
                         WHERE b.product_variant_id = ?
                           AND l.company_store_id = ?
                        """, BigDecimal.class, variantId, storeId));
        assertThat(storePositionOf(variantId))
                .as("stock_position must equal SUM(stock_balance) over the locations the store owns")
                .isEqualByComparingTo(locationSum);
    }

    @Nested
    @DisplayName("balance invariant")
    class BalanceInvariantTests {

        /*
         * Deleted with T3c of odd/tasks/ledger-mirror-retirement.md: "should add a receipt to the aggregate
         * instead of recomputing it from the location sum" seeded product_variant_store_stock.stock = 40
         * against product_variant_locations.stock = 100 by hand - a divergence no writer could produce, built
         * only to pin that a receipt adds to the aggregate instead of re-deriving it from the location sum.
         * Both columns are frozen now and V61's trigger derives both projections from the one movement, so
         * there is no second copy that could disagree and no recompute left to forbid: rewritten against the
         * projections it would assert 45 == 45, which is a weaker case rather than the same one.
         */

        @Test
        @DisplayName("should keep stock_position = SUM(stock_balance) after a receipt")
        void invariantHoldsAfterAReceipt() {
            var variant = createVariant("0.00");

            applyReceipt(variant.getId(), "12.50");

            assertInvariant(variant.getId());
        }

        @Test
        @DisplayName("should keep stock_position = SUM(stock_balance) after a sale and after its reversal")
        void invariantHoldsAfterASaleAndItsReversal() {
            var variant = createVariant("0.00");
            applyReceipt(variant.getId(), "10.00");
            assertInvariant(variant.getId());

            var referenceId = UUID.randomUUID();
            TenantFixtures.inTenant(
                    companyId,
                    () -> inventoryService.applySaleDeduction(
                            variant.getId(),
                            storeId,
                            new BigDecimal("4.00"),
                            "SALES_ORDER_ITEM",
                            referenceId,
                            "seller"));
            assertInvariant(variant.getId());
            assertThat(storePositionOf(variant.getId())).isEqualByComparingTo("6.00");

            TenantFixtures.inTenant(
                    companyId, () -> inventoryService.applySaleReversal("SALES_ORDER_ITEM", referenceId, "seller"));
            assertInvariant(variant.getId());
            assertThat(storePositionOf(variant.getId())).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("database constraints and guards")
    class ConstraintAndGuardTests {

        @Test
        @DisplayName("should reject a duplicate (variant, location) balance row at the database level")
        void duplicateBalancePairIsRejectedByTheUniqueConstraint() {
            var variant = createVariant("0.00");
            var pair = ProductVariantLocation.builder()
                    .productVariantId(variant.getId())
                    .storeLocationId(locationId)
                    .stock(BigDecimal.ZERO)
                    .build();
            productVariantLocationRepository.save(pair);

            var duplicate = ProductVariantLocation.builder()
                    .productVariantId(variant.getId())
                    .storeLocationId(locationId)
                    .stock(new BigDecimal("99.00"))
                    .build();

            assertThatThrownBy(() -> productVariantLocationRepository.saveAndFlush(duplicate))
                    .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                            .as("database constraint violation for the duplicate pair")
                            .isTrue());

            assertThat(productVariantLocationRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should reject a non-positive quantity and leave the ledger and both projections unchanged")
        void nonPositiveQuantityWritesNothing() {
            // The seed is a booked receipt now, so "writes nothing" is stated as the ledger still holding
            // exactly that one row: a refused receipt appends nothing to it.
            var variant = createVariant("7.00");

            assertThatThrownBy(() -> applyReceipt(variant.getId(), "0.00"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> applyReceipt(variant.getId(), "-2.00"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(ledgerRowsOf(variant.getId())).hasSize(1);
            assertThat(locationStockOf(variant.getId(), locationId)).isEqualByComparingTo("7.00");
            assertThat(storePositionOf(variant.getId())).isEqualByComparingTo("7.00");
        }

        private boolean hasConstraintViolationCause(Throwable thrown) {
            for (var cause = thrown; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * F-4 of {@code odd/tasks/sales-location-aware-stock.md}: the engine's multi-key lock ordering
     * is argued but not reproduced. {@code SalesOrderIntegrationTest} 5.9 races a single
     * {@code (store, variant)} key, so it cannot tell a sorted acquisition order from an unsorted
     * one — with one key there is no cycle to form. This case races two transactions whose batches
     * name the same set of keys in opposite input orders: {@code applySaleBatch} pre-locks every
     * distinct row in ascending key order, so both transactions claim the rows in the same order and
     * serialize; losing that sort lets the two acquisition orders cross and deadlock instead.
     */
    @Nested
    @DisplayName("concurrent multi-key sale batch")
    class ConcurrentMultiKeySaleBatchTests {

        private static final int DISTINCT_KEYS = 6;
        private static final int ROUNDS = 12;
        private static final BigDecimal SEEDED_STOCK = new BigDecimal("1000.00");
        private static final BigDecimal DEDUCTION_PER_THREAD = new BigDecimal("1.00");

        @Test
        @DisplayName("should commit two opposite-order multi-key batches with deterministic stock and no deadlock")
        void concurrentOppositeOrderBatchesCommitDeterministically() throws Exception {
            // Seed each (store, variant) key so the pessimistic store-stock lock and the location
            // balance row both exist, and give every key enough stock that an insufficient-stock 409
            // can never be mistaken for the deadlock this case is here to catch.
            var ascendingVariants = new ArrayList<UUID>();
            for (var i = 0; i < DISTINCT_KEYS; i++) {
                var variant = createVariant("0.00");
                applyReceipt(variant.getId(), SEEDED_STOCK.toPlainString());
                ascendingVariants.add(variant.getId());
            }
            var descendingVariants = new ArrayList<>(ascendingVariants);
            Collections.reverse(descendingVariants);

            // A dedicated pair of threads, not the shared common pool, so the race actually starts
            // two transactions at once even on a single-core machine.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (var round = 0; round < ROUNDS; round++) {
                    var ready = new CountDownLatch(2);
                    var start = new CountDownLatch(1);

                    var ascending = CompletableFuture.runAsync(() -> raceBatch(ready, start, ascendingVariants), pool);
                    var descending =
                            CompletableFuture.runAsync(() -> raceBatch(ready, start, descendingVariants), pool);

                    assertThat(ready.await(10, TimeUnit.SECONDS))
                            .as("both racing threads reached the barrier (round %d)", round)
                            .isTrue();
                    start.countDown();
                    // A deadlock aborts one of the two futures here with SQLState 40P01; a commit
                    // loss or a rolled-back deduction fails the aggregate assertions below.
                    CompletableFuture.allOf(ascending, descending).get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            var expectedFinal = SEEDED_STOCK.subtract(DEDUCTION_PER_THREAD.multiply(BigDecimal.valueOf(2L * ROUNDS)));
            for (var variantId : ascendingVariants) {
                assertThat(storePositionOf(variantId))
                        .as("aggregate must be initial minus what both threads deducted over %d rounds", ROUNDS)
                        .isEqualByComparingTo(expectedFinal);
                assertInvariant(variantId);
            }
        }

        private void raceBatch(CountDownLatch ready, CountDownLatch start, List<UUID> variantIds) {
            try {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("race start signal was never released");
                }
                TenantFixtures.inTenant(
                        companyId,
                        () -> inventoryService.applySaleBatch("SALES_ORDER_ITEM", "seller", deductionsOf(variantIds)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("raced sale-batch thread was interrupted", interrupted);
            }
        }

        private List<InventoryService.SaleChange> deductionsOf(List<UUID> variantIds) {
            var changes = new ArrayList<InventoryService.SaleChange>();
            for (var variantId : variantIds) {
                changes.add(InventoryService.SaleChange.deduction(
                        storeId, variantId, DEDUCTION_PER_THREAD, UUID.randomUUID()));
            }
            return changes;
        }
    }

    @Nested
    @DisplayName("another company's variant")
    class ForeignCompanyVariantTests {

        @Test
        @DisplayName("should refuse a receipt of another company's variant and write nothing")
        void receiptOfForeignVariant_IsRefusedAndWritesNothing() {
            var foreignVariant = createForeignVariant();

            assertThatThrownBy(() -> TenantFixtures.inTenant(
                            companyId,
                            () -> inventoryService.applyReceipt(
                                    foreignVariant.getId(),
                                    storeId,
                                    locationId,
                                    new BigDecimal("5.00"),
                                    StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                                    UUID.randomUUID(),
                                    "receiver")))
                    .isInstanceOf(ProductVariantNotFoundException.class);

            // Nothing persisted: no ledger row, no location balance and no aggregate move. The ledger read runs
            // under this company's scope, which is the tenant the refusal had to leave untouched.
            assertThat(ledgerRowsOf(foreignVariant.getId())).isEmpty();
            assertThat(productVariantLocationRepository.count()).isZero();
            assertThat(storePositionOptionally(foreignVariant.getId())).isEmpty();
        }

        @Test
        @DisplayName("should refuse a sale of another company's variant and write nothing")
        void saleOfForeignVariant_IsRefusedAndWritesNothing() {
            var foreignVariant = createForeignVariant();

            assertThatThrownBy(() -> TenantFixtures.inTenant(
                            companyId,
                            () -> inventoryService.applySaleDeduction(
                                    foreignVariant.getId(),
                                    storeId,
                                    new BigDecimal("1.00"),
                                    "SALES_ORDER_ITEM",
                                    UUID.randomUUID(),
                                    "seller")))
                    .isInstanceOf(ProductVariantNotFoundException.class);

            assertThat(ledgerRowsOf(foreignVariant.getId())).isEmpty();
            assertThat(productVariantLocationRepository.count()).isZero();
            assertThat(storePositionOptionally(foreignVariant.getId())).isEmpty();
        }

        /**
         * A product and a variant of a <b>different</b> company, stocked at this test's store: the
         * pair nothing in the schema forbids, because {@code company_stores} carries no
         * {@code company_id} and the composite foreign keys only tie a variant to its own product.
         * The seeded store row is what makes the receipt and the sale reachable — without it they
         * would be refused for the wrong reason, a variant the store never registered. It states no
         * stock: since T3c nothing here holds a balance, and seeding the frozen column would make the
         * store row a second truth beside the one the refusal is actually about.
         */
        private ProductVariant createForeignVariant() {
            var foreignCompany = TenantFixtures.findOrCreate(
                    companyRepository, FOREIGN_COMPANY_KEY, "Inventory Foreign Company", "INVF010101ABC");

            // Everything the receipt and the sale touch below belongs to the foreign company, so the
            // children are seeded under ITS tenant, not this class's.
            return TenantFixtures.inTenant(foreignCompany.getId(), () -> {
                var product = productRepository.save(Product.builder()
                        .sku("INV-FSKU-" + UUID.randomUUID().toString().substring(0, 8))
                        .name("Inventory Foreign Product")
                        .company(foreignCompany)
                        .enabled(true)
                        .build());

                var variant = productVariantRepository.save(ProductVariant.builder()
                        .productId(product.getId())
                        .companyId(foreignCompany.getId())
                        .measureUnitId(pieceUnitId())
                        .barCode("INV-FBAR-" + UUID.randomUUID().toString().substring(0, 8))
                        .variantName("Foreign-" + UUID.randomUUID().toString().substring(0, 8))
                        .enabled(true)
                        .build());

                productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                        .productVariantId(variant.getId())
                        .companyStoreId(storeId)
                        .costPrice(new BigDecimal("10.00"))
                        .listPrice(new BigDecimal("20.00"))
                        .build());
                return variant;
            });
        }
    }
}
