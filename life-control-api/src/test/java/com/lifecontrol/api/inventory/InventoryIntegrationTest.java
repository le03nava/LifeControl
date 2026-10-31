package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence-level verification of the inventory core against real PostgreSQL with Flyway enabled
 * and {@code ddl-auto=validate}, so a green run proves {@code V10__inventory.sql} and the entity
 * mappings agree.
 *
 * <p>Covers the additive receipt and the {@code aggregate = SUM(locations)} invariant after a
 * receipt and after a sale, the create-then-reuse behaviour of the {@code (variant, location)}
 * balance, the database-level uniqueness and the append-only ledger. The store/location fixtures are
 * built through the real store endpoints, exactly like the other store-chain integration tests, so
 * the chain comes from the same code path the API uses.</p>
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
        locationId = ensureLocation();
    }

    @AfterEach
    void tearDown() {
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain.
     * Idempotent because the Testcontainers database is shared per JVM and the data survives
     * across methods.
     */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("INVENTORY-KEY")
                .orElseGet(() -> TenantFixtures.persist(
                        companyRepository,
                        Company.builder()
                                .companyKey("INVENTORY-KEY")
                                .companyName("Inventory Test Company")
                                .rfc("INVT010101ABC")
                                .enabled(true)
                                .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
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

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
        zoneId = zone.getId();
        storeId = store.getId();
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

    private ProductVariant createVariant(String stock) {
        var product = productRepository.save(Product.builder()
                .sku("INV-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                .name("Inventory Test Product")
                .company(companyRepository.findById(companyId).orElseThrow())
                .enabled(true)
                .build());

        var variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .companyId(companyId)
                .barCode("INV-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                .enabled(true)
                .build());

        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variant.getId())
                .companyStoreId(storeId)
                .costPrice(new BigDecimal("10.00"))
                .listPrice(new BigDecimal("20.00"))
                .stock(new BigDecimal(stock))
                .build());
        return variant;
    }

    private void applyReceipt(UUID variantId, String quantity) {
        inventoryService.applyReceipt(
                variantId, storeId, locationId, new BigDecimal(quantity), "GOODS_RECEIPT", null, "receiver");
    }

    private Optional<ProductVariantLocation> balanceOf(UUID variantId) {
        return productVariantLocationRepository.findByProductVariantIdAndStoreLocationId(variantId, locationId);
    }

    private BigDecimal aggregateStockOf(UUID variantId) {
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                .orElseThrow()
                .getStock();
    }

    @Nested
    @DisplayName("first receipt")
    class FirstReceiptTests {

        @Test
        @DisplayName("should create the balance row and write exactly one RECEIPT movement")
        void firstReceiptCreatesBalanceAndOneMovement() {
            var variant = createVariant("0.00");
            var referenceId = UUID.randomUUID();

            inventoryService.applyReceipt(
                    variant.getId(),
                    storeId,
                    locationId,
                    new BigDecimal("12.50"),
                    "GOODS_RECEIPT",
                    referenceId,
                    "receiver");

            var balance = balanceOf(variant.getId()).orElseThrow();
            assertThat(balance.getStock()).isEqualByComparingTo("12.50");
            assertThat(balance.getProductVariantId()).isEqualTo(variant.getId());
            assertThat(balance.getStoreLocationId()).isEqualTo(locationId);
            assertThat(balance.getCreatedAt()).isNotNull();
            assertThat(balance.getUpdatedAt()).isNotNull();

            assertThat(aggregateStockOf(variant.getId())).isEqualByComparingTo("12.50");

            var movements = inventoryMovementRepository.findAll();
            assertThat(movements).hasSize(1);
            var movement = movements.getFirst();
            assertThat(movement.getMovementType()).isEqualTo(MovementType.RECEIPT);
            assertThat(movement.getQuantity()).isEqualByComparingTo("12.50");
            assertThat(movement.getProductVariantId()).isEqualTo(variant.getId());
            assertThat(movement.getCompanyStoreId()).isEqualTo(storeId);
            assertThat(movement.getStoreLocationId()).isEqualTo(locationId);
            assertThat(movement.getReferenceType()).isEqualTo("GOODS_RECEIPT");
            assertThat(movement.getReferenceId()).isEqualTo(referenceId);
            assertThat(movement.getCreatedBy()).isEqualTo("receiver");
            assertThat(movement.getOccurredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("accumulation")
    class AccumulationTests {

        @Test
        @DisplayName("should accumulate both balances on a second receipt and leave the first movement untouched")
        void secondReceiptAccumulatesAndLedgerRowsAreImmutable() {
            var variant = createVariant("0.00");

            applyReceipt(variant.getId(), "6.00");
            var firstMovement = inventoryMovementRepository.findAll().getFirst();
            var firstOccurredAt = firstMovement.getOccurredAt();

            applyReceipt(variant.getId(), "2.25");

            assertThat(balanceOf(variant.getId()).orElseThrow().getStock()).isEqualByComparingTo("8.25");
            assertThat(aggregateStockOf(variant.getId())).isEqualByComparingTo("8.25");

            // The second receipt reuses the existing balance row instead of duplicating it...
            assertThat(productVariantLocationRepository.count()).isEqualTo(1);
            // ...and appends a second movement without modifying the first one (append-only ledger).
            assertThat(inventoryMovementRepository.count()).isEqualTo(2);
            var reloadedFirst =
                    inventoryMovementRepository.findById(firstMovement.getId()).orElseThrow();
            assertThat(reloadedFirst.getQuantity()).isEqualByComparingTo("6.00");
            assertThat(reloadedFirst.getMovementType()).isEqualTo(MovementType.RECEIPT);
            assertThat(reloadedFirst.getOccurredAt()).isEqualTo(firstOccurredAt);
        }
    }

    /**
     * The W3-D5 invariant, read store-wide: the sellable aggregate equals the sum of every location
     * balance the store owns. It resolves the locations through the same
     * {@code product_variant_locations -> store_locations -> ... -> company_stores} chain the
     * production allocation uses, so a stray balance row is caught instead of silently summed past.
     */
    private void assertInvariant(UUID variantId) {
        var locationSum =
                productVariantLocationRepository.findByProductVariantIdAndCompanyStoreId(variantId, storeId).stream()
                        .map(ProductVariantLocation::getStock)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aggregateStockOf(variantId))
                .as("aggregate must equal SUM(product_variant_locations.stock)")
                .isEqualByComparingTo(locationSum);
    }

    @Nested
    @DisplayName("balance invariant")
    class BalanceInvariantTests {

        @Test
        @DisplayName("should add a receipt to the aggregate instead of recomputing it from the location sum")
        void receiptIsAdditiveAndNeverRecomputesFromTheLocationSum() {
            // A divergent state seeded directly, because the application can no longer produce one:
            // sales is location-aware and V15 reset the old divergence, so every writer moves both
            // sides. It is seeded only to pin the one operation applyReceipt must never perform —
            // deriving the aggregate from SUM(product_variant_locations.stock). A recompute would
            // answer 105 here and resurrect the 60 units the sellable row no longer holds.
            var variant = createVariant("40.00");
            productVariantLocationRepository.save(ProductVariantLocation.builder()
                    .productVariantId(variant.getId())
                    .storeLocationId(locationId)
                    .stock(new BigDecimal("100.00"))
                    .build());

            applyReceipt(variant.getId(), "5.00");

            assertThat(aggregateStockOf(variant.getId()))
                    .as("aggregate must be stockBeforeReceipt + quantity, never the location sum")
                    .isEqualByComparingTo("45.00");
            assertThat(balanceOf(variant.getId()).orElseThrow().getStock())
                    .as("location must be locationBefore + quantity")
                    .isEqualByComparingTo("105.00");
        }

        @Test
        @DisplayName("should keep aggregate = SUM(locations) after a receipt")
        void invariantHoldsAfterAReceipt() {
            var variant = createVariant("0.00");

            applyReceipt(variant.getId(), "12.50");

            assertInvariant(variant.getId());
        }

        @Test
        @DisplayName("should keep aggregate = SUM(locations) after a sale and after its reversal")
        void invariantHoldsAfterASaleAndItsReversal() {
            var variant = createVariant("0.00");
            applyReceipt(variant.getId(), "10.00");
            assertInvariant(variant.getId());

            var referenceId = UUID.randomUUID();
            inventoryService.applySaleDeduction(
                    variant.getId(), storeId, new BigDecimal("4.00"), "SALES_ORDER_ITEM", referenceId, "seller");
            assertInvariant(variant.getId());
            assertThat(aggregateStockOf(variant.getId())).isEqualByComparingTo("6.00");

            inventoryService.applySaleReversal("SALES_ORDER_ITEM", referenceId, "seller");
            assertInvariant(variant.getId());
            assertThat(aggregateStockOf(variant.getId())).isEqualByComparingTo("10.00");
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
        @DisplayName("should reject a non-positive quantity and write no balance row and no movement")
        void nonPositiveQuantityWritesNothing() {
            var variant = createVariant("7.00");

            assertThatThrownBy(() -> applyReceipt(variant.getId(), "0.00"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> applyReceipt(variant.getId(), "-2.00"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(balanceOf(variant.getId())).isEmpty();
            assertThat(inventoryMovementRepository.count()).isZero();
            assertThat(aggregateStockOf(variant.getId())).isEqualByComparingTo("7.00");
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
                assertThat(aggregateStockOf(variantId))
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
                inventoryService.applySaleBatch("SALES_ORDER_ITEM", "seller", deductionsOf(variantIds));
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
            var foreignVariant = createForeignVariant("0.00");

            assertThatThrownBy(() -> inventoryService.applyReceipt(
                            foreignVariant.getId(),
                            storeId,
                            locationId,
                            new BigDecimal("5.00"),
                            "GOODS_RECEIPT",
                            UUID.randomUUID(),
                            "receiver"))
                    .isInstanceOf(ProductVariantNotFoundException.class);

            // Nothing persisted: no ledger row, no location balance and no aggregate move.
            assertThat(inventoryMovementRepository.count()).isZero();
            assertThat(productVariantLocationRepository.count()).isZero();
            assertThat(aggregateStockOf(foreignVariant.getId())).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("should refuse a sale of another company's variant and write nothing")
        void saleOfForeignVariant_IsRefusedAndWritesNothing() {
            var foreignVariant = createForeignVariant("5.00");

            assertThatThrownBy(() -> inventoryService.applySaleDeduction(
                            foreignVariant.getId(),
                            storeId,
                            new BigDecimal("1.00"),
                            "SALES_ORDER_ITEM",
                            UUID.randomUUID(),
                            "seller"))
                    .isInstanceOf(ProductVariantNotFoundException.class);

            assertThat(inventoryMovementRepository.count()).isZero();
            assertThat(productVariantLocationRepository.count()).isZero();
            assertThat(aggregateStockOf(foreignVariant.getId())).isEqualByComparingTo("5.00");
        }

        /**
         * A product and a variant of a <b>different</b> company, stocked at this test's store: the
         * pair nothing in the schema forbids, because {@code company_stores} carries no
         * {@code company_id} and the composite foreign keys only tie a variant to its own product.
         * The seeded store row is what makes the receipt and the sale reachable — without it they
         * would be refused for the wrong reason.
         */
        private ProductVariant createForeignVariant(String stock) {
            var foreignCompany = companyRepository
                    .findByCompanyKey(FOREIGN_COMPANY_KEY)
                    .orElseGet(() -> TenantFixtures.persist(
                            companyRepository,
                            Company.builder()
                                    .companyKey(FOREIGN_COMPANY_KEY)
                                    .companyName("Inventory Foreign Company")
                                    .rfc("INVF010101ABC")
                                    .enabled(true)
                                    .build()));

            var product = productRepository.save(Product.builder()
                    .sku("INV-FSKU-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("Inventory Foreign Product")
                    .company(foreignCompany)
                    .enabled(true)
                    .build());

            var variant = productVariantRepository.save(ProductVariant.builder()
                    .productId(product.getId())
                    .companyId(foreignCompany.getId())
                    .barCode("INV-FBAR-" + UUID.randomUUID().toString().substring(0, 8))
                    .variantName("Foreign-" + UUID.randomUUID().toString().substring(0, 8))
                    .enabled(true)
                    .build());

            productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                    .productVariantId(variant.getId())
                    .companyStoreId(storeId)
                    .costPrice(new BigDecimal("10.00"))
                    .listPrice(new BigDecimal("20.00"))
                    .stock(new BigDecimal(stock))
                    .build());
            return variant;
        }
    }
}
