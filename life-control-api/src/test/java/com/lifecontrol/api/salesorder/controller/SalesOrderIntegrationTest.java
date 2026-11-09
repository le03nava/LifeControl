package com.lifecontrol.api.salesorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalePaymentRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.salesorder.repository.SalePaymentRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import com.lifecontrol.api.store.model.CompanyStore;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sales Order Integration Tests")
class SalesOrderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** The handle the shared ledger cleanup deletes the projections through (see the base class). */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The production writer the split relocation goes through, so the fixture books what production books. */
    @Autowired
    private StockLedgerWriter stockLedgerWriter;

    /** The production receipt entry point the fixture's opening goes through: stock is booked, never seeded. */
    @Autowired
    private StockLedgerEngine stockLedgerEngine;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository itemRepository;

    /**
     * The production reader of what a document has paid, which is ADR-0002 M3 and M5 stated once (T10).
     *
     * <p><b>Why the suite calls it directly.</b> {@code netPaid}'s {@code NOT EXISTS (… sale_payment_void …)} has no
     * other reader in the tree: it runs inside a charge, and a charge is refused by the state guard before it asks a
     * document that is already {@code COMPLETED}. The exclusion of a voided row is therefore only observable through
     * the statement itself, on a database, with a void row that a case appended — which is what
     * {@code MoneyInvariantTests} does, instead of leaving the claim in the unit test's javadoc unproved.</p>
     */
    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private StatusTypeRepository statusTypeRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private ShiftRepository shiftRepository;

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
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    private UUID customerId;
    private UUID companyStoreId;
    private UUID companyId;
    private UUID shiftId;
    private UUID salesLocationId;
    private UUID secondaryLocationId;
    private UUID draftStatusId;
    private UUID activeStatusId;
    private UUID pendingStatusId;
    private UUID completedStatusId;
    private UUID cancelledStatusId;
    private UUID pendingItemStatusId;
    private UUID addedItemStatusId;
    private UUID cancelledItemStatusId;

    private static final SimpleGrantedAuthority ROLE_LC_SALES = new SimpleGrantedAuthority("ROLE_lc-sales");

    /**
     * The ledger source of the stock this fixture seeds.
     *
     * <p>A discriminator that can never be mistaken for a real one: a sale states its line and a receipt its
     * {@code goods_receipt_items} row, while this stock belongs to no document at all — it is the state a store
     * carries before its first one, which {@code V62}'s Path A replay books for the whole tree under
     * {@code source_type = 'MIGRATION'}. The opening is booked as a real {@code RECEIPT} through
     * {@link StockLedgerEngine#applyReceipt}, the production path, so the fixture states stock exactly the way
     * production does; the discriminator is what lets every assertion answer "what did the document under test
     * book?" instead of counting the fixture's own receipt.</p>
     */
    private static final String FIXTURE_OPENING = "FIXTURE_OPENING";

    @BeforeEach
    void setUp() {
        // ADR-0003 made `sales_orders` a tenant table, so the cleanup below needs the scope the
        // application's filter installs per request. Without it every deleteAll() deletes NOTHING — RLS
        // answers zero rows and reports success — so the orders this suite places survive into the next
        // class and pin the shifts it deletes (measured as `fk` failures on `shifts` in a full run, none in
        // an isolated one). The company is the deterministic one this suite already finds-or-creates.
        companyId = TenantFixtures.findOrCreate(companyRepository, "TEST-KEY", "Test Company", "TEST123456ABC")
                .getId();
        TenantScope.set(companyId, null);

        // Clean up mutable data in reverse FK dependency order. The ledger's projections are removed by
        // `deleteStockLedger` in the teardown, and the per-location balance rows the sale path used to lock are
        // gone with V74, so the store row below is the only inventory row a fixture still writes.
        itemRepository.deleteAll();
        salesOrderRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        shiftRepository.deleteAll();

        // Seed reference data (idempotent after first call — data persists across test methods)
        seedReferenceData();
    }

    @AfterEach
    void tearDown() {
        // A charged order's payment rows go first: they are append-only and they reference the order, so
        // leaving them behind fails the next case's cleanup on fk_payment_order_same_company. The helper is
        // the base class's, because this suite is no longer the only one whose fixture charges a document.
        if (companyId != null) {
            deleteSalePayments(companyId);
        }

        // The orders this suite places also book the stock ledger (T5a of odd/tasks/inventory-ledger.md),
        // and those rows reference product_variants and store_locations with no cascade while the ledger is
        // append-only: the deletes below would fail on fk_movement_variant, and the rows would keep blocking
        // every other class in this shared JVM. They are removed first, through the shared helper.
        if (companyId != null) {
            deleteStockLedger(jdbcTemplate, companyId);
        }

        // store_inventory_settings references store_locations, so the store-tree integration
        // classes cannot delete locations wholesale while this row exists. It is recreated by
        // seedReferenceData on the next test.
        if (companyStoreId != null) {
            storeInventorySettingsRepository
                    .findById(companyStoreId)
                    .ifPresent(storeInventorySettingsRepository::delete);
        }

        // The scope is cleared last: it is installed for the whole test so the deletes above match rows
        // instead of silently matching none, and leaving it on the thread would leak into whatever suite
        // runs next on this one.
        TenantScope.clear();
    }

    /**
     * Ensures all reference data needed by sales order operations exists in the DB.
     * Uses find-or-create pattern: loads existing entities first, creates if missing.
     * Flyway V3 already seeds the sales-order status types/statuses on PostgreSQL;
     * the find-or-create calls below are idempotent, and the company chain, customer,
     * and shifts are created manually.
     */
    private void seedReferenceData() {
        // Find-or-create sales-order status types and statuses (already seeded by Flyway V3)
        var salesOrderType = statusTypeRepository
                .findByStatusTypeNameIgnoreCase("SALES_ORDER")
                .orElseGet(() -> statusTypeRepository.save(StatusType.builder()
                        .statusTypeName("SALES_ORDER")
                        .enabled(true)
                        .build()));

        var salesOrderItemType = statusTypeRepository
                .findByStatusTypeNameIgnoreCase("SALES_ORDER_ITEM")
                .orElseGet(() -> statusTypeRepository.save(StatusType.builder()
                        .statusTypeName("SALES_ORDER_ITEM")
                        .enabled(true)
                        .build()));

        for (var name : List.of("Draft", "Active", "Pending", "Completed", "Cancelled")) {
            if (!statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(name, salesOrderType.getId())) {
                statusRepository.save(Status.builder()
                        .statusName(name)
                        .statusType(salesOrderType)
                        .enabled(true)
                        .build());
            }
        }

        for (var name : List.of("Pending", "Added", "Cancelled")) {
            if (!statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(name, salesOrderItemType.getId())) {
                statusRepository.save(Status.builder()
                        .statusName(name)
                        .statusType(salesOrderItemType)
                        .enabled(true)
                        .build());
            }
        }

        // Load status IDs (self-seeded above)
        draftStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Draft")
                .orElseThrow()
                .getId();
        activeStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Active")
                .orElseThrow()
                .getId();
        pendingStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Pending")
                .orElseThrow()
                .getId();
        completedStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Completed")
                .orElseThrow()
                .getId();
        cancelledStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Cancelled")
                .orElseThrow()
                .getId();
        pendingItemStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                .orElseThrow()
                .getId();
        addedItemStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added")
                .orElseThrow()
                .getId();
        cancelledItemStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Cancelled")
                .orElseThrow()
                .getId();

        // Company chain: find or create (Country → Company → CompanyCountry → CompanyRegion → CompanyZone →
        // CompanyStore)
        var country = countryRepository
                .findByCountryCode("MX")
                .orElseGet(() -> countryRepository.save(Country.builder()
                        .countryCode("MX")
                        .countryName("Mexico")
                        .enabled(true)
                        .build()));

        var company = TenantFixtures.findOrCreate(companyRepository, "TEST-KEY", "Test Company", "TEST123456ABC");

        // One boundary states the company for the whole chain, its store tree and the settings row that
        // names the sales location: every one of those rows is on a policy-armed table or references one,
        // so an unscoped read and the flush's unsaved-value probe would both answer "no rows".
        TenantFixtures.inTenant(company.getId(), () -> {
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
                                    .regionCode("01")
                                    .regionName("Test Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("01")
                            .zoneName("Test Zone")
                            .enabled(true)
                            .build()));

            var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Test Store")
                            .enabled(true)
                            .build()));

            // The sales path now deducts through StockLedgerEngine, which needs a per-location balance
            // and the store's sales location to allocate against. Seed them once per store.
            ensureInventoryLocations(store);

            companyStoreId = store.getId();
            companyId = store.getCompanyZone()
                    .getCompanyRegion()
                    .getCompanyCountry()
                    .getCompany()
                    .getId();
        });

        // Customer: find or create
        var customer = customerRepository.findBySalesChannel("TEST").stream()
                .findFirst()
                .orElseGet(() -> customerRepository.save(Customer.builder()
                        .name("Test Customer")
                        .salesChannel("TEST")
                        .enabled(true)
                        .build()));

        customerId = customer.getId();

        // Shift: find or create
        var shifts = shiftRepository.findByCompanyStoreId(companyStoreId);
        if (shifts.isEmpty()) {
            var shift = Shift.builder()
                    .companyStoreId(companyStoreId)
                    .userId("user123")
                    .openedAt(LocalDateTime.now().minusHours(1))
                    .status("ABIERTO")
                    .enabled(true)
                    .build();
            shift = shiftRepository.save(shift);
            shiftId = shift.getId();
        } else {
            shiftId = shifts.get(0).getId();
        }
    }

    /**
     * Creates (find-or-create) the store tree, the two locations the sales tests allocate across
     * and the {@code store_inventory_settings} row that names the priority sales location. The
     * location creates the FK chain {@code store_locations -> store_zones -> store_areas ->
     * company_stores} the allocation query walks to prove the location belongs to the store.
     */
    private void ensureInventoryLocations(CompanyStore store) {
        var area = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId()).stream()
                .filter(candidate -> "SALES-AREA".equals(candidate.getAreaCode()))
                .findFirst()
                .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                        .companyStore(store)
                        .areaCode("SALES-AREA")
                        .areaName("Sales Area")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));

        var zone = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                .filter(candidate -> "SALES-ZONE".equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                        .storeArea(area)
                        .zoneCode("SALES-ZONE")
                        .zoneName("Sales Zone")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));

        salesLocationId = ensureLocation(zone, "SALES-01", "Sales shelf");
        secondaryLocationId = ensureLocation(zone, "SALES-02", "Back shelf");

        storeInventorySettingsRepository
                .findByCompanyStoreId(store.getId())
                .orElseGet(() -> storeInventorySettingsRepository.save(StoreInventorySettings.builder()
                        .companyStoreId(store.getId())
                        .receivingLocationId(salesLocationId)
                        .salesLocationId(salesLocationId)
                        .build()));
    }

    private UUID ensureLocation(StoreZone zone, String code, String name) {
        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId()).stream()
                .filter(location -> code.equals(location.getLocationCode()))
                .findFirst()
                .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                        .storeZone(zone)
                        .locationCode(code)
                        .locationName(name)
                        .displayOrder(1)
                        .enabled(true)
                        .build()))
                .getId();
    }

    /**
     * Creates a real product backing a test variant. The PostgreSQL schema enforces
     * {@code product_variants.product_id -> products.id}, so variants cannot be
     * persisted against a fabricated product id.
     */
    /**
     * A product of this class's company.
     *
     * <p>The company read and the armed {@code INSERT} share one stated tenant: without it the read answers
     * empty and the product's parent looks unsaved to the flush.</p>
     */
    private Product createTestProduct() {
        return TenantFixtures.inTenant(
                companyId,
                () -> productRepository.save(Product.builder()
                        .sku("SKU-" + UUID.randomUUID().toString().substring(0, 12))
                        .name("Test Product")
                        .company(companyRepository.findById(companyId).orElseThrow())
                        .enabled(true)
                        .build()));
    }

    /**
     * Creates a product variant with the given stock quantity for testing.
     */
    private ProductVariant createTestVariant(BigDecimal stock) {
        return TenantFixtures.inTenant(companyId, () -> {
            var variant = new ProductVariant();
            variant.setProductId(createTestProduct().getId());
            variant.setCompanyId(companyId);
            variant.setMeasureUnitId(pieceUnitId());
            variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
            variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setEnabled(true);
            var saved = productVariantRepository.save(variant);
            saveStoreStock(saved.getId(), stock, new BigDecimal("60.00"));
            return saved;
        });
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
     * Creates a second variant with given stock (different productId) for multi-variant tests.
     */
    private ProductVariant createTestVariantB(BigDecimal stock) {
        return TenantFixtures.inTenant(companyId, () -> {
            var variant = new ProductVariant();
            variant.setProductId(createTestProduct().getId());
            variant.setCompanyId(companyId);
            variant.setMeasureUnitId(pieceUnitId());
            variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
            variant.setVariantName("VariantB-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setEnabled(true);
            var saved = productVariantRepository.save(variant);
            saveStoreStock(saved.getId(), stock, new BigDecimal("30.00"));
            return saved;
        });
    }

    /**
     * Creates an enabled variant deliberately not stocked in the store: no aggregate row and no
     * location balance. A sale of it is a stock insufficiency, not a malformed request.
     */
    private ProductVariant createVariantWithoutStoreStock() {
        return TenantFixtures.inTenant(companyId, () -> {
            var variant = new ProductVariant();
            variant.setProductId(createTestProduct().getId());
            variant.setCompanyId(companyId);
            variant.setMeasureUnitId(pieceUnitId());
            variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
            variant.setVariantName(
                    "VariantNoStore-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setEnabled(true);
            return productVariantRepository.save(variant);
        });
    }

    /**
     * Registers the variant in this store with {@code stock} units on hand.
     *
     * <p><b>Two writes, and the units are one of them — a movement, never a column.</b> The
     * {@code product_variant_store_stock} row is what this store records about the variant (its cost, which every
     * inbound ledger row has to state) and it is created with the column's own default; the units are one
     * {@code RECEIPT} booked through {@link StockLedgerEngine#applyReceipt}, the production path, so the
     * projection trigger is what fills {@code stock_balance} and {@code stock_position}. Seeding the legacy columns
     * instead would restore the second truth T3c removed: nothing maintains them any more, so an assertion that
     * read one would agree for ever with a number the application never reads.</p>
     *
     * <p>Runs inside the caller's tenant transaction, like every other writer call in this suite: the receipt reads
     * the variant and the store row it locks, so opening a transaction of its own here would read neither.</p>
     */
    private void saveStoreStock(UUID variantId, BigDecimal stock, BigDecimal costPrice) {
        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(companyStoreId)
                .costPrice(costPrice)
                .build());
        seedOpeningReceipt(variantId, salesLocationId, stock);
    }

    /**
     * Books the fixture's opening into one location as a receipt: the production writer, in the production shape.
     *
     * <p>Sourced {@link #FIXTURE_OPENING} so no assertion below can confuse it with the document it is about, and
     * so the two rows a split books — the opening at the priority location and the stock placed on a second shelf
     * — are one pair of facts under one discriminator.</p>
     */
    private void seedOpeningReceipt(UUID variantId, UUID storeLocationId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() == 0) {
            return;
        }
        stockLedgerEngine.applyReceipt(
                variantId, companyStoreId, storeLocationId, quantity, FIXTURE_OPENING, UUID.randomUUID(), "fixture");
    }

    /**
     * A quantity at the scale the columns carry: four decimals.
     *
     * <p>{@code sales_order_items.quantity}, {@code stock_movement.quantity} and the projections derived from them
     * are all {@code NUMERIC(19,4)} (ADR-0002 decision 1, S2), so a line's {@code 0.3333} arrives as
     * {@code 0.3333} and a whole {@code 2} arrives as {@code 2.0000} — and {@code BigDecimal.equals}, which compares
     * the scale as well as the value, would call those two different numbers. Every quantity these readers answer
     * goes through here, so an assertion compares the number a document states instead of the width of the column
     * that stores it.
     *
     * <p><b>Why four and no longer two.</b> This used to state {@code setScale(2, UNNECESSARY)}, which is the grain
     * the columns had before the money slice and which <b>fails</b> on a legitimate four-decimal quantity — a per-gram
     * price is exactly the case decision 1 widened the column for. The rounding mode stays {@code UNNECESSARY}: a
     * value the column cannot hold is a fixture stating something the schema would have refused, and it should fail
     * loudly instead of being rounded into agreement.
     */
    private static BigDecimal documentQuantity(BigDecimal stored) {
        return stored == null ? null : stored.setScale(4, RoundingMode.UNNECESSARY);
    }

    /**
     * The store's position — the number a sale is refused by — read from the ledger's projection.
     *
     * <p>It used to read {@code product_variant_store_stock.stock}. Since T3c nothing maintains that column, so
     * reading it would agree with a frozen number for ever and every assertion in this suite would pass over a
     * balance that stopped moving. {@code stock_position} is derived from {@code stock_movement} by the
     * {@code V61} trigger, and that is what makes it the answer.</p>
     */
    private BigDecimal stockPositionOf(UUID variantId) {
        return documentQuantity(TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("""
                        SELECT COALESCE(
                                   (SELECT quantity
                                      FROM stock_position
                                     WHERE company_store_id = ? AND product_variant_id = ?),
                                   0)
                        """, BigDecimal.class, companyStoreId, variantId)));
    }

    /**
     * One location's balance of a variant, from the same projection ({@code stock_balance}).
     *
     * <p>An absent row is zero, not an error: the projection only holds keys the ledger moved, so "no movement"
     * and "nothing on the shelf" are the same answer.</p>
     */
    private BigDecimal locationStockOf(UUID variantId, UUID locationId) {
        return documentQuantity(TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("""
                        SELECT COALESCE(
                                   (SELECT quantity
                                      FROM stock_balance
                                     WHERE store_location_id = ? AND product_variant_id = ?),
                                   0)
                        """, BigDecimal.class, locationId, variantId)));
    }

    /**
     * Every {@code stock_balance} row this store holds for the variant, read under the tenant that owns it.
     *
     * <p>A list instead of a number, because a refusal case asks whether <b>any</b> balance row exists: a query
     * for one row would report "no row", which is the right answer stated in a way that could not also catch a
     * row that landed at the wrong location.</p>
     */
    private List<BigDecimal> locationBalancesOf(UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT quantity FROM stock_balance WHERE product_variant_id = ?",
                        BigDecimal.class,
                        variantId));
    }

    /**
     * One {@code stock_movement} row: the only record of a booked fact since the legacy table stopped being
     * written. The columns are the ledger's own — {@code sourceId} is the sale line for a sale, and whatever
     * discriminator the writer stated for a receipt.
     */
    private record LedgerMovement(
            String movementType, BigDecimal quantity, UUID storeLocationId, String sourceType, UUID sourceId) {}

    /**
     * The ledger rows one variant carries, oldest first, excluding the fixture's own opening.
     *
     * <p><b>Why the opening is excluded.</b> Every case below asks what the document under test booked; the stock
     * it was given to work with is the fixture's, booked under {@link #FIXTURE_OPENING}. Counted together, a case
     * that expects one row after a sale would see two, and the extra one would say nothing about the sale.</p>
     *
     * <p>Read under the tenant: the ledger's tables are armed on {@code tenant_table} ({@code V60}) and
     * {@code FORCE}d, so an unscoped read answers zero rows instead of failing.</p>
     */
    private List<LedgerMovement> movementsOf(UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT movement_type, quantity, store_location_id, source_type, source_id
                          FROM stock_movement
                         WHERE product_variant_id = ? AND source_type <> ?
                         ORDER BY occurred_at, id
                        """,
                        (rs, rowNum) -> new LedgerMovement(
                                rs.getString("movement_type"),
                                documentQuantity(rs.getBigDecimal("quantity")),
                                rs.getObject("store_location_id", UUID.class),
                                rs.getString("source_type"),
                                rs.getObject("source_id", UUID.class)),
                        variantId,
                        FIXTURE_OPENING));
    }

    /**
     * Splits a variant's balance between the priority sales location and a second location, so a
     * deduction larger than the priority balance has to spill over. The aggregate keeps the sum.
     *
     * <p><b>The split is a relocation, and it is booked as one.</b> The units leave the priority location through a
     * real {@code ADJUSTMENT_OUT} and arrive on the second shelf through a fixture receipt, so the ledger's
     * per-location balances <em>are</em> the split and no column is written to say so. Both rows are sourced
     * {@link #FIXTURE_OPENING}, which is what keeps them out of {@link #movementsOf}.</p>
     */
    private void seedSplitBalances(UUID variantId, BigDecimal salesLocationStock, BigDecimal secondaryStock) {
        TenantFixtures.inTenant(companyId, () -> {
            var aggregate = stockPositionOf(variantId);
            if (salesLocationStock.add(secondaryStock).compareTo(aggregate) != 0) {
                throw new IllegalStateException("The fixture seeds " + aggregate + " for variant " + variantId
                        + " and this split describes " + salesLocationStock.add(secondaryStock)
                        + ", so the ledger's position and this split could not agree");
            }

            // The ledger's per-location balances ARE the split, and nothing else has to be created for it: the
            // row the sale path used to lock at the second location went with V74, and a movement names the
            // location it moves through the booking below.
            var relocated = aggregate.subtract(salesLocationStock);
            if (relocated.signum() > 0) {
                stockLedgerWriter.bookAdjustmentOut(
                        new StockLedgerWriter.Booking(
                                companyId,
                                companyStoreId,
                                salesLocationId,
                                variantId,
                                "fixture",
                                FIXTURE_OPENING,
                                UUID.randomUUID(),
                                StockLedgerWriter.SINGLE_WRITE),
                        relocated);
            }
            seedOpeningReceipt(variantId, secondaryLocationId, secondaryStock);
        });
    }

    /**
     * Creates an order with the given lines through the API. Returns the order id and each
     * generated line id keyed by variant, so a test can assert the ledger reference of a line
     * without depending on the response's item order.
     */
    private PlacedOrder createOrder(SalesOrderItemRequest... items) throws Exception {
        var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(items));
        var result = mockMvc.perform(post("/api/sales-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(scopedSalesJwt()))
                .andExpect(status().isCreated())
                .andReturn();
        return placedOrder(result.getResponse().getContentAsString());
    }

    /** Replaces the order's line set through PUT and returns the resulting line ids. */
    private PlacedOrder updateOrderItems(UUID orderId, SalesOrderItemRequest... items) throws Exception {
        var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(items));
        var result = mockMvc.perform(put("/api/sales-orders/{id}", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andReturn();
        return placedOrder(result.getResponse().getContentAsString());
    }

    private PlacedOrder placedOrder(String body) throws Exception {
        var root = objectMapper.readTree(body);
        var itemIdsByVariant = new HashMap<UUID, UUID>();
        for (var node : root.get("items")) {
            itemIdsByVariant.put(
                    UUID.fromString(node.get("productVariantId").asText()),
                    UUID.fromString(node.get("id").asText()));
        }
        return new PlacedOrder(UUID.fromString(root.get("id").asText()), itemIdsByVariant);
    }

    /** One line of a create/update request at the price the other fixtures use. */
    private SalesOrderItemRequest line(UUID itemId, UUID variantId, String quantity) {
        return new SalesOrderItemRequest(
                itemId, variantId, new BigDecimal(quantity), new BigDecimal("100.00"), BigDecimal.ZERO, null);
    }

    private List<LedgerMovement> movementsOfType(UUID variantId, String movementType) {
        return movementsOf(variantId).stream()
                .filter(movement -> movement.movementType().equals(movementType))
                .toList();
    }

    private boolean itemEnabled(UUID itemId) {
        return itemRepository.findById(itemId).orElseThrow().getEnabled();
    }

    private void cancelOrder(UUID orderId) throws Exception {
        var request = new UpdateSalesOrderStatusRequest(cancelledStatusId);
        mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("Cancelled"));
    }

    /** Draft → Active → Pending → Completed, the only transition chain into a terminal success. */
    private void completeOrder(UUID orderId) throws Exception {
        transitionOrder(orderId, activeStatusId);
        transitionOrder(orderId, pendingStatusId);
        transitionOrder(orderId, completedStatusId);
    }

    private void transitionOrder(UUID orderId, UUID statusId) throws Exception {
        var request = new UpdateSalesOrderStatusRequest(statusId);
        mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(scopedSalesJwt()))
                .andExpect(status().isOk());
    }

    private void cancelItem(UUID orderId, UUID itemId) throws Exception {
        var request = new UpdateSalesOrderStatusRequest(cancelledItemStatusId);
        mockMvc.perform(patch("/api/sales-orders/{id}/items/{itemId}/status", orderId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("Cancelled"));
    }

    /** An order's id plus its line ids, keyed by the variant each line carries. */
    private record PlacedOrder(UUID orderId, Map<UUID, UUID> itemIdsByVariant) {
        UUID itemId(UUID variantId) {
            return itemIdsByVariant.get(variantId);
        }
    }

    // ─── 5.1 Create order with items → the charge books the stock ─

    @Nested
    @DisplayName("5.1 Create order with items — the charge books the stock")
    class CreateOrderStockDeductionTests {

        @Test
        @DisplayName("the created line reports the line total the schema derived, not the unit price it was built from")
        void createOrder_LineReportsTheSchemaLineTotal() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // 2 x 100 - 10 + 0. The request states a price and a discount; `finalPrice` is not a field it can
            // state, because V85 made the column read-only. The response used to report the unit price, 90.
            var itemRequest = new SalesOrderItemRequest(
                    null,
                    variant.getId(),
                    new BigDecimal("2.00"),
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            var result = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(root.get("totalAmount").decimalValue())
                    .as("the document's total is Σ of the line's parts (M1, M2), at the grain the column carries")
                    .isEqualByComparingTo(new BigDecimal("190.0000"));
            assertThat(root.get("items").get(0).get("finalPrice").decimalValue())
                    .as("V85 (D-h): the response reports the row the trigger wrote — read back, not the number "
                            + "the service built — which is the same fresh-context trap trg_sales_order_items_"
                            + "subtotal already carried")
                    .isEqualByComparingTo(new BigDecimal("190.0000"));
        }

        @Test
        @DisplayName("should create the order without booking stock and deduct it per variant when it is charged")
        void createOrderWithItems_StockDeducted() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            var result = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.statusName").value("Draft"))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn();

            var root = objectMapper.readTree(result.getResponse().getContentAsString());
            var orderId = UUID.fromString(root.get("id").asText());
            var itemId = UUID.fromString(root.get("items").get(0).get("id").asText());

            // ADR-0002 decision 7: creating the document moves nothing. The position the fixture seeded is the
            // position the charge will find, and the ledger holds no row of this document's yet.
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(orderId);

            // Verify the deduction reached both sides: aggregate and location 100 - 5 = 95
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // The ledger it never read before now explains the move: one SALE row per location
            // consumed, positive quantity, referencing the line that caused it (W3-D6).
            var movements = movementsOf(variant.getId());
            assertThat(movements).hasSize(1);
            assertThat(movements.get(0).movementType()).isEqualTo(StockLedgerWriter.SALE);
            assertThat(movements.get(0).quantity()).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(movements.get(0).storeLocationId()).isEqualTo(salesLocationId);
            assertThat(movements.get(0).sourceType()).isEqualTo("SALES_ORDER_ITEM");
            assertThat(movements.get(0).sourceId()).isEqualTo(itemId);
        }
    }

    // ─── 5.2 The charge of a document the store cannot cover → 409 ─

    @Nested
    @DisplayName("5.2 Charge with insufficient stock — 409 and nothing written")
    class CreateOrderInsufficientStockTests {

        /**
         * The refusal moved from the create to the charge, and it is stronger for it: the create succeeds, and
         * the charge that cannot cover its stock rolls back the payment, the transition, the line states and the
         * movement of the variant that could have covered it.
         *
         * <p><b>Why the create half is an assertion and not scaffolding.</b> A document may state more than the
         * store holds — that is a document, and its money is the customer's to agree — so the 201 here is the
         * other side of decision 7 and not an incidental step. The engine is the refusal, and it runs once, under
         * the order's lock.</p>
         */
        @Test
        @DisplayName("should create the document holding more than the store has, and refuse its charge with 409")
        void createOrder_InsufficientStock_Charge_Returns409() throws Exception {
            var variant = createTestVariant(new BigDecimal("5.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();
            var orderId = UUID.fromString(objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("id")
                    .asText());
            assertNothingBooked(variant.getId(), "5.00");

            transitionOrder(orderId, activeStatusId);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    objectMapper.writeValueAsString(chargeForItsTotal(orderId, savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    // The documented sale contract, with its own numbers: the position is the authority and its
                    // refusal is the documented 409, not the projection trigger's database error.
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString(
                                    "Insufficient stock for variant " + variant.getId())))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("requested 10")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("available 5")));

            // The refusal is the whole transaction's: no payment row (the table is append-only, so a row that
            // landed could not be taken back), no transition out of Active, the line still waiting, and the
            // position and the ledger exactly as the charge found them.
            assertThat(paymentsOf(orderId)).isEmpty();
            assertThat(inTransaction(() ->
                            salesOrderRepository.findById(orderId).orElseThrow().getStatus()))
                    .isEqualTo(SalesOrderState.ACTIVE);
            assertThat(inTransaction(() -> itemRepository.findBySalesOrderId(orderId)))
                    .singleElement()
                    .satisfies(item -> assertThat(item.getStatus()).isEqualTo(SalesOrderItemState.PENDING));
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        // One case lived here and no longer can: "409, not 500, when the ledger's position cannot cover the line".
        // It built its state by raising the two legacy stock columns by hand and booking nothing, so the legacy
        // gates passed and only the position could refuse. T3c stopped maintaining those columns, so that state
        // cannot be constructed at all: the position is the only balance there is, and the case above pins the
        // same refusal with the numbers the position states.

        @Test
        @DisplayName("should answer the engine's refusal, not a 400, when the variant is not stocked in the store")
        void createOrder_VariantNotStockedInStore_Charge() throws Exception {
            var variant = createVariantWithoutStoreStock();

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("1.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SalesOrderRequest(
                                    customerId, companyStoreId, shiftId, "user123", List.of(itemRequest))))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();
            var orderId = UUID.fromString(objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("id")
                    .asText());
            transitionOrder(orderId, activeStatusId);

            // The variant exists and is enabled but has no position in this store: that is zero sellable stock,
            // and the engine answers it as the stock refusal (409) and not as a malformed request (400) — the
            // position gate runs before any store row is read. Stated here because this is the boundary where
            // it could quietly become the 400 `IllegalArgumentException` the engine also carries.
            mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    objectMapper.writeValueAsString(chargeForItsTotal(orderId, savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));

            // Nothing was written for the unstocked variant: no store row, no balance row and no movement — and
            // no payment, which is the half the charge adds to this case.
            assertThat(paymentsOf(orderId)).isEmpty();
            assertThat(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(
                            variant.getId(), companyStoreId))
                    .isEmpty();
            assertThat(locationBalancesOf(variant.getId()))
                    .as("no balance at either location: the per-location row is the ledger's projection now "
                            + "(V74), and `stock_balance` holds only keys a movement named")
                    .isEmpty();
            assertThat(movementsOf(variant.getId())).isEmpty();
        }
    }

    // ─── 5.3 Add item to order → the charge books it ──────────────

    @Nested
    @DisplayName("5.3 Add item to order — the charge books the stock")
    class AddItemStockDeductionTests {

        @Test
        @DisplayName("should add the line without booking stock, and deduct it when the order is charged")
        void addItem_StockDeducted() throws Exception {
            // Create an order without items
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", null);

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Create variant with stock 50
            var variant = createTestVariant(new BigDecimal("50.00"));

            // Add item with quantity 3
            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("3.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var addResult = mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.quantity").value(3.00))
                    .andReturn();

            var itemId = UUID.fromString(objectMapper
                    .readTree(addResult.getResponse().getContentAsString())
                    .get("id")
                    .asText());

            // The add is a line write: the store still has all 50, and the document holds none of them.
            assertNothingBooked(variant.getId(), "50.00");

            chargeFromItsCurrentState(UUID.fromString(orderId));

            // Verify aggregate and location stock: 50 - 3 = 47
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("47.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("47.00"));

            var movements = movementsOf(variant.getId());
            assertThat(movements).hasSize(1);
            assertThat(movements.get(0).movementType()).isEqualTo(StockLedgerWriter.SALE);
            assertThat(movements.get(0).sourceId()).isEqualTo(itemId);
        }
    }

    // ─── 5.4 Update item quantity → the charge books the stated quantity ─

    @Nested
    @DisplayName("5.4 Update item quantity — the document edits nothing, the charge books what it states")
    class UpdateItemQuantityDeltaTests {

        @Test
        @DisplayName("should book the quantity the line states at the charge, whatever the edits before it")
        void updateItem_QuantityChange_BooksTheStatedQuantity() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var placed = createOrder(line(null, variant.getId(), "5.00"));
            var itemId = placed.itemId(variant.getId());

            // The two edits of the same line, in the order a cashier would make them: 5 → 8 → 2.
            for (var quantity : List.of("8.00", "2.00")) {
                mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(line(itemId, variant.getId(), quantity)))
                                .with(scopedSalesJwt()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.quantity").value(Double.parseDouble(quantity)));
                assertNothingBooked(variant.getId(), "100.00");
            }

            chargeFromItsCurrentState(placed.orderId());

            // The charge books what the line says at that moment: one SALE of 2.00 — not the 5 it was born with,
            // not the 3 the growth was worth, and no compensating pair. The delta rule and the SALE_REVERSAL that
            // implemented it left with the per-line path (ADR-0002 decision 7): there is one booking to make and
            // one moment to make it.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(movementsOf(variant.getId()))
                    .extracting(LedgerMovement::movementType, LedgerMovement::sourceId, LedgerMovement::quantity)
                    .containsExactly(tuple(StockLedgerWriter.SALE, itemId, new BigDecimal("2.0000")));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .as("nothing was given back, because nothing had been taken")
                    .isEmpty();
        }

        /**
         * A repeated edit books nothing, and since ADR-0002 decision 7 it books nothing for a stronger reason than
         * the delta rule: the path has no booking to repeat.
         *
         * <p><b>What the retry half still proves.</b> Invariant I5 of
         * {@code odd/tasks/ledger-per-write-identity.md} is that a repeated request must not add a movement, and
         * the answer must not be the movement's source key — a key refuses a duplicate by failing, and a retry is
         * legal. The per-line path answered it with document state (a zero delta registers nothing). The charge
         * answers it structurally: a Completed document is refused before any payment or movement exists, and the
         * edits this case repeats never book in the first place.</p>
         *
         * <p><b>Three sends, not two.</b> A retry has no bound, and a single no-op is indistinguishable from a
         * write that collided with {@code uq_movement_source} and was swallowed — so the row count is what is
         * asserted, not the aggregate alone. The charge at the end is what shows the counter is still the
         * authority: it is the line's first write, so the movement is booked under 1.</p>
         */
        @Test
        @DisplayName("a retried quantity update books nothing, and the charge books the line once")
        void aRetriedQuantityUpdateBooksNoMovement() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "5.00")));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = UUID.fromString(root.get("id").asText());
            var itemId = UUID.fromString(root.get("items").get(0).get("id").asText());

            assertThat(ledgerSaleRowsOf(variant.getId()))
                    .as("the creation books nothing: a document holds no stock until its charge")
                    .isZero();
            assertThat(writeSeqOf(itemId))
                    .as("and it numbers nothing, because it books nothing")
                    .isZero();

            var retry = line(itemId, variant.getId(), "5.00");
            for (int attempt = 0; attempt < 3; attempt++) {
                mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(retry))
                                .with(scopedSalesJwt()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.quantity").value(5.00));
            }

            assertThat(ledgerSaleRowsOf(variant.getId()))
                    .as("SalesOrderService.updateSalesOrderItem books nothing on any delta, so a repeated request "
                            + "reads the document and stops: I5's retry half is carried by the path having no "
                            + "booking, not by the source key")
                    .isZero();
            assertThat(writeSeqOf(itemId))
                    .as("the retry books nothing, so it bumps nothing: a bump with no movement under it would pass "
                            + "the row count above while quietly disagreeing with the ledger")
                    .isZero();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));

            chargeFromItsCurrentState(orderId);

            assertThat(ledgerSaleRowsOf(variant.getId()))
                    .as("the charge is the document's one booking, and it repeats nothing either")
                    .isEqualTo(1);
            assertThat(writeSeqOf(itemId))
                    .as("the charge takes the line's first write sequence, which is the number the movement copies")
                    .isEqualTo(1L);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));
        }

        /** The ledger's {@code SALE} rows of one variant, counted inside the tenant ADR-0005's policy demands. */
        private int ledgerSaleRowsOf(UUID variantId) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM stock_movement WHERE product_variant_id = ? AND movement_type = 'SALE'",
                            Integer.class,
                            variantId));
        }

        /**
         * The identity each {@code SALE} row of one variant was booked under, oldest first.
         *
         * <p>The three columns {@code V73} keys a movement on are read together because they answer one question:
         * which line, and which of its writes, produced this row. Reading the source alone cannot tell two writes
         * of one line apart, which is the whole point of the fifth column.</p>
         */
        private List<LedgerSaleIdentity> ledgerSaleIdentitiesOf(UUID variantId) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.query(
                            """
                            SELECT source_type, source_id, write_seq
                              FROM stock_movement
                             WHERE product_variant_id = ? AND movement_type = 'SALE'
                             ORDER BY occurred_at, id
                            """,
                            (rs, rowNum) -> new LedgerSaleIdentity(
                                    rs.getString("source_type"),
                                    rs.getObject("source_id", UUID.class),
                                    rs.getLong("write_seq")),
                            variantId));
        }

        /** The line's own counter, which is the authority the movement's copy of it comes from. */
        private long writeSeqOf(UUID itemId) {
            return jdbcTemplate.queryForObject(
                    "SELECT write_seq FROM sales_order_items WHERE id = ?", Long.class, itemId);
        }

        /** One ledger {@code SALE} row's identity: the source it names and the write it belongs to. */
        private record LedgerSaleIdentity(String sourceType, UUID sourceId, long writeSeq) {}

        /**
         * Two edits of one line book nothing, and the charge that follows is that line's one write.
         *
         * <p><b>What this case was, and why it is retargeted rather than deleted.</b> It pinned the ledger's
         * per-write identity from the edit path: two edits of one line booked two {@code SALE} rows, and nothing
         * but {@code sales_order_items.write_seq} ({@code V73}) could tell them apart. Under ADR-0002 decision 7 an
         * edit books nothing, so the second {@code SALE} row the fifth column existed for no longer comes from
         * here — the subject belongs to the charge, which books one movement per line and numbers it from the same
         * counter. The identity the ledger states is still the triple; what changed is how many writes one line
         * has.
         *
         * <p><b>Why it goes through the controller and not through a fixture with hand-written numbers.</b> The
         * numbers are the service's to produce: a case that states them itself would prove the key is wide enough
         * and nothing about the bump, and the bump is the half that can silently repeat a value.</p>
         */
        @Test
        @DisplayName("two edits of one line book nothing, and the charge books it once under write_seq 1")
        void twoEditsOfOneLineBookNothingAndTheChargeBooksItOnce() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "5.00"));
            var orderId = placed.orderId();
            var itemId = placed.itemId(variant.getId());

            for (var quantity : List.of("8.00", "10.00")) {
                mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(line(itemId, variant.getId(), quantity)))
                                .with(scopedSalesJwt()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.quantity").value(Double.parseDouble(quantity)));
            }

            assertThat(ledgerSaleIdentitiesOf(variant.getId()))
                    .as("neither edit booked anything, so the ledger holds no row of this line at all")
                    .isEmpty();
            assertThat(writeSeqOf(itemId)).as("and neither edit numbered one").isZero();

            chargeFromItsCurrentState(orderId);

            var identities = ledgerSaleIdentitiesOf(variant.getId());
            assertThat(identities)
                    .as("the charge books the line once: there is one sale and it is of the quantity the line states")
                    .hasSize(1);
            assertThat(identities)
                    .extracting(LedgerSaleIdentity::sourceType)
                    .as("the row names the document line, not the legacy row it used to mirror")
                    .containsOnly("SALES_ORDER_ITEM");
            assertThat(identities).extracting(LedgerSaleIdentity::sourceId).containsOnly(itemId);
            assertThat(identities)
                    .extracting(LedgerSaleIdentity::writeSeq)
                    .as("the charge takes the line's first sequence, and the movement copies the number it was "
                            + "booked under")
                    .containsExactly(1L);
            assertThat(writeSeqOf(itemId))
                    .as("the line's own counter is the authority the copy came from")
                    .isEqualTo(1L);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("90.00"));
        }

        /**
         * The locked bump, measured <b>inside</b> the bump: the first transaction bumps and holds the line's row
         * while the second one bumps, so the second must observe the value the first produced.
         *
         * <p><b>Why the charge is the caller this measures.</b> Since ADR-0002 decision 7 the charge is the one
         * writer of a line's counter, and the scenario the lock exists for is the one this case builds: two
         * transactions bumping the same line at the same time. The counter starts at 0 because creating the
         * document books nothing, so the answers are 1 and 2 rather than 2 and 3 — the invariant is the same and
         * the starting point is the one the schema now states.</p>
         *
         * <p><b>Why two threads released together are not enough, stated because the first version of this case did
         * exactly that.</b> Two threads that start at the same instant prove nothing about the statement: a
         * read-modify-write reads, writes, and — when the two windows happen not to overlap — answers 1 and 2 as
         * happily as {@code UPDATE … RETURNING} does. This case therefore holds the first bump's transaction open
         * until the second bump is <b>observed waiting on the row</b>, which is a fact the database reports rather
         * than a sleep that hopes for it:</p>
         * <ul>
         *   <li>{@code UPDATE … write_seq = write_seq + 1 … RETURNING} takes the row lock with the statement, so the
         *       second bump blocks on it, is seen waiting, and re-reads the committed 1 under {@code READ COMMITTED}
         *       after the first commits: the answers are 1 and 2.</li>
         *   <li>A {@code SELECT} followed by an {@code UPDATE} takes no lock on the read, so the second bump reads
         *       the stale 0 and both write 1 back: the answers are 1 and 1 — the duplicate the fifth key column would
         *       then have to refuse, i.e. a legal charge turned into a 500.</li>
         *   <li>An implementation that takes no lock at all never makes the second bump wait, and the barrier times
         *       out naming the other sessions instead of passing on a lucky interleaving.</li>
         * </ul>
         * <p>No sleep and no barrier can make a read-modify-write pass this: the second bump is demonstrably inside
         * its own bump — the database says it is waiting on the row — before the first one commits.</p>
         */
        @Test
        @DisplayName("the locked bump hands two concurrent writers distinct sequences")
        void theLockedBumpHandsTwoConcurrentWritersDistinctSequences() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "5.00")));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var itemId = UUID.fromString(objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("items")
                    .get(0)
                    .get("id")
                    .asText());
            assertThat(writeSeqOf(itemId))
                    .as("the creation books nothing, so the counter still stands at 0: the charge is the writer "
                            + "this case measures")
                    .isZero();

            var theFirstBumpIsDone = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var first = CompletableFuture.supplyAsync(
                        () -> TenantFixtures.inTenant(companyId, () -> {
                            var bumped = itemRepository.bumpWriteSeq(itemId);
                            theFirstBumpIsDone.countDown();
                            awaitTheOtherTransactionWaitingOnTheLineRow();
                            return bumped;
                        }),
                        pool);

                assertThat(theFirstBumpIsDone.await(20, TimeUnit.SECONDS))
                        .as("the first bump answered and its transaction still holds the line's row, which is the "
                                + "window the second bump has to start inside")
                        .isTrue();

                var second = CompletableFuture.supplyAsync(
                        () -> TenantFixtures.inTenant(companyId, () -> itemRepository.bumpWriteSeq(itemId)), pool);

                assertThat(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)))
                        .as("the second bump was waiting on the row while the first held it, so it cannot have read "
                                + "the value the first read: the answers are 1 and 2, and a read-modify-write answers "
                                + "1 twice")
                        .containsExactlyInAnyOrder(1L, 2L);
            } finally {
                pool.shutdownNow();
            }

            assertThat(writeSeqOf(itemId)).as("and both bumps are on the line").isEqualTo(2L);
        }

        /**
         * The wait the second bump must be in: the transaction-id lock a concurrent {@code UPDATE} blocks on.
         *
         * <p><b>Why the transaction id and not a tuple.</b> A second {@code UPDATE} of a row another transaction is
         * already updating waits on that transaction's id — PostgreSQL's {@code XactLockTableWait} — which is what
         * {@code pg_locks.locktype = 'transactionid'} and {@code wait_event = 'transactionid'} report. A statement
         * that only <em>read</em> the row waits on nothing, which is exactly the difference this case measures.</p>
         */
        private static final String WAITING_ON_THE_LINE_ROW = """
                SELECT count(*) FROM pg_stat_activity
                 WHERE pid <> pg_backend_pid()
                   AND datname = current_database()
                   AND usename = current_user
                   AND wait_event_type = 'Lock'
                   AND wait_event = 'transactionid'
                """;

        /** What the other sessions of this case looked like, so a timeout says why instead of just failing. */
        private static final String OTHER_SESSIONS = """
                SELECT format('%s state=%s waiting=%s/%s query=%s', pid, state, wait_event_type, wait_event,
                              left(coalesce(query, '<hidden>'), 80))
                  FROM pg_stat_activity
                 WHERE pid <> pg_backend_pid()
                   AND datname = current_database()
                """;

        /**
         * Blocks until another session is waiting on a transaction id, which is what a second bump does.
         *
         * <p>This is the barrier the case needs, and the reason it is not a sleep: the first transaction must not
         * commit until the second one is inside its bump, or a read-modify-write could be observed in a lucky order
         * and pass. {@code pg_stat_activity} is the database's own report of the wait, and both sessions belong to
         * the pool's role, which is what makes another session's row readable.</p>
         */
        private void awaitTheOtherTransactionWaitingOnTheLineRow() {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                var waiting = jdbcTemplate.queryForObject(WAITING_ON_THE_LINE_ROW, Integer.class);
                if (waiting != null && waiting > 0) {
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the first bump was interrupted while waiting", interrupted);
                }
            }
            throw new IllegalStateException("no other transaction was waiting on the sales_order_items row within 20 "
                    + "seconds, so the second bump never reached the row's lock — the bump is not taking it. Other "
                    + "sessions: " + jdbcTemplate.queryForList(OTHER_SESSIONS, String.class));
        }
    }

    // ─── 5.5 Delete item before the charge → nothing is booked ───

    @Nested
    @DisplayName("5.5 Deleting a line before the charge — nothing is booked")
    class DeleteItemStockRestoredTests {

        /**
         * A line deleted from an uncharged document gives nothing back, because nothing was taken.
         *
         * <p><b>What moved.</b> This case used to assert the item delete's reconciliation: 7 units sold on create,
         * given back on delete. Under ADR-0002 decision 7 nothing is sold on create, so the delete has nothing to
         * give back — and the document that loses its only line has nothing left to charge. The reversal assertion
         * is not dropped: it moves to {@link DeleteOrderStockRestoreTests}, the one path where a reversal exists.
         * </p>
         */
        @Test
        @DisplayName("should book nothing when the line is soft-deleted, and leave the document nothing to charge")
        void deleteItemBeforeTheCharge_BooksNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "7.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // The store never lost a unit, the line is gone, and the reversal the delete used to write does not
            // exist because no deduction did.
            assertThat(itemEnabled(itemId)).isFalse();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();

            // The other half of the same fact: the document left with no line has nothing to charge, so the line
            // contributes neither stock nor money.
            transitionOrder(placed.orderId(), activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            // The body is what a till sends for the line it believes is still there, and the
                            // refusal below is the document's — nothing to pay — and not the amount's.
                            .content(objectMapper.writeValueAsString(
                                    onePayment(savedPaymentMethodId(), new BigDecimal("700.0000"))))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString("there is nothing to pay")));
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        /**
         * Deleting a line and re-enabling it books nothing either way, and the charge that follows books it
         * <b>once</b>.
         *
         * <p><b>What the case was.</b> W3-D11: a soft-deleted line was given back, so re-enabling it had to be a
         * fresh hold — and this pinned the re-deduction (SALE, SALE_REVERSAL, SALE again). Nothing is booked before
         * the charge now, so the interesting statement is the one that replaced it: the document ends up with one
         * movement for that line and never two, whatever it went through.</p>
         */
        @Test
        @DisplayName("should book nothing when a deleted line is re-enabled, and book it once at the charge")
        void deleteItemThenReEnable_BooksOnceAtTheCharge() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();

            // The PUT carries the deleted line's id back: the line is re-enabled and still holds nothing, because
            // it never held anything.
            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "4.00"));

            assertThat(itemEnabled(itemId)).isTrue();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            // One sale of four, and no compensating row: the line holds what the charge booked and nothing else.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
        }
    }

    // ─── 5.6 Cancel order → nothing to restore ───────────────────

    @Nested
    @DisplayName("5.6 Cancelling an uncharged order — no reversal for any line")
    class CancelOrderStockRestoreTests {

        /**
         * A cancel restores nothing, because the document it cancels booked nothing.
         *
         * <p><b>What the case was, and the scenario it keeps.</b> It pinned the order-cancel reversal: the enabled
         * lines given back, the soft-deleted one excluded, no line credited twice. Under ADR-0002 decision 7 a
         * cancellation can only leave Draft, Active or Pending — none of which holds stock — so the scenario is
         * kept with its two lines (one deleted first, one live) and the invariant it now states is that neither
         * produces a reversal. {@code COMPLETED} is terminal, so there is no "cancel a charged order" case to move
         * it to; the reversal a charged document has is its deletion, in
         * {@link DeleteOrderStockRestoreTests}.</p>
         */
        @Test
        @DisplayName("should restore nothing on cancel, whatever state its lines are in")
        void cancelOrder_BooksNoReversalForEitherLine() throws Exception {
            // Two variants, because the case has to name each line: `PlacedOrder` keys the ids by variant, and two
            // lines of one variant would leave only one of them addressable.
            var variant = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "3.00"), line(null, variantB.getId(), "5.00"));
            var deletedItemId = placed.itemId(variant.getId());
            var liveItemId = placed.itemId(variantB.getId());
            assertNothingBooked(variant.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "100.00");

            // One line is soft-deleted, which used to be the case the cancel had to exclude from its reversal.
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), deletedItemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertNothingBooked(variant.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "100.00");

            cancelOrder(placed.orderId());

            // Neither line is credited: the deleted one has nothing to give back and the live one was never
            // booked. The ledger holds no row of either, which is what "no double restore" became.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(stockPositionOf(variantB.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variantB.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(movementsOf(variantB.getId())).isEmpty();
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getStatus()))
                    .isEqualTo(SalesOrderState.CANCELLED);
            assertThat(itemEnabled(liveItemId)).isTrue();
        }
    }

    // ─── 5.7 Delete a charged order → its charge is reversed ─────

    @Nested
    @DisplayName("5.7 Delete a charged order (soft) — the charge's booking is reversed")
    class DeleteOrderStockRestoreTests {

        /**
         * The one path a reversal exists on: a document whose charge booked its stock, deleted afterwards.
         *
         * <p><b>Why the create no longer moves the stock and the delete still does.</b> ADR-0002 decision 7 makes the
         * charge the booking, and a reversal is meaningful only against a booking. So the case charges first —
         * which is what books the four units — and then deletes, which credits them back exactly once.
         * {@code COMPLETED} is terminal for transitions but not for deletion, which is why this is the shape.</p>
         */
        @Test
        @DisplayName("should restore the stock its charge booked when the order is soft-deleted, and only once")
        void deleteOrder_StockRestored() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));

            // Soft-delete the entire order
            mockMvc.perform(delete("/api/sales-orders/{id}", placed.orderId()).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // Verify both sides restored to 100, and the reversal is on the ledger for this line.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            var reversals = movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL);
            assertThat(reversals).hasSize(1);
            assertThat(reversals.get(0).sourceId()).isEqualTo(itemId);

            // Deleting it again credits nothing: the engine restores a line's uncovered remainder, and after the
            // first reversal there is none. This is where the "reversed twice" guard is now observable, because a
            // second reversal is the only way to credit a charged document twice.
            mockMvc.perform(delete("/api/sales-orders/{id}", placed.orderId()).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .as("a second delete of the same document is a second reversal of the same reference, and the "
                            + "engine credits only what the line still holds")
                    .hasSize(1);
        }
    }

    // ─── 5.8 The charge books the stock, once ─────────────────────

    @Nested
    @DisplayName("5.8 Charging a document books its stock, once")
    class ChargeBooksTheStockTests {

        /**
         * The inversion of {@code completeOrder_StockUnchanged}, which T6b contradicts on purpose.
         *
         * <p><b>What the old case asserted and why it is now the opposite.</b> It created a document that deducted
         * its six units, walked it Draft → Active → Pending → Completed and asserted the stock did not move — true,
         * because the movement had already happened at create. Under ADR-0002 decision 7 creating books nothing and
         * the charge books everything, so the same walk with a charge at the end is what has to move the stock; and
         * the second charge is the other half, because a document that could be charged twice would book its stock
         * twice and pay its total twice.</p>
         */
        @Test
        @DisplayName("should move the stock on the charge, and a second charge neither moves it nor pays it again")
        void charge_MovesStockAndASecondChargeRepeatsNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "6.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            // The charge is the moment the stock moves: 100 - 6 = 94, on both sides.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
            assertThat(paymentsOf(placed.orderId())).hasSize(1);

            // The same request a second time, which is what a retried charge looks like.
            var second = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A Completed document is not a chargable one: the refusal is the state guard's 400, taken under the
            // lock that makes the retry see the first charge's commit (ADR-0002 M10).
            assertThat(second.getResponse().getStatus()).isEqualTo(400);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(movementsOf(variant.getId()))
                    .as("one movement of this line, not two: the retry never reached the engine")
                    .hasSize(1);
            assertThat(paymentsOf(placed.orderId()))
                    .as("and one payment: `sale_payment` is append-only, so a second row could not be taken back")
                    .hasSize(1);
            assertThat(itemRepository.findById(itemId).orElseThrow().getStatus())
                    .isEqualTo(SalesOrderItemState.ADDED);
        }
    }

    // ─── 5.9 Concurrent charges same variant → serialized ────────

    @Nested
    @DisplayName("5.9 Concurrent charges of one variant — serialized, no lost updates")
    class ConcurrentOrdersSerializedTests {

        /**
         * Two charges of one variant, released together: one books, the other is refused, and the position is the
         * winner's.
         *
         * <p><b>Why the concurrency moved from the create to the charge.</b> This case used to run two concurrent
         * creates and assert that the engine serialized them — one 201, one 409. Creates book nothing now, so both
         * would answer 201 and the case would pass while measuring nothing. The lock it is about is taken by the
         * charge ({@code stock_position} in {@code applySaleDeduction}, under the order's own row lock), so that is
         * where the two threads go: two documents, 8 and 5 units against a position of 10.</p>
         *
         * <p><b>What a lost update would look like.</b> Both charges reading 10 and both believing they fit would
         * leave the position at 2 while two payments claim 13 units were sold, or at -3 if the projection allowed
         * it. The assertions are therefore the pair: exactly one charge succeeds, and the movement the ledger holds
         * states the quantity the position lost.</p>
         */
        @Test
        @DisplayName("should serialize concurrent charges of one variant with no lost updates")
        void concurrentCharges_Serialized_NoLostUpdates() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var eight = createOrder(line(null, variant.getId(), "8.00"));
            var five = createOrder(line(null, variant.getId(), "5.00"));
            // Both documents are Active — the state a charge is taken from — so the race is the stock, not the
            // document's own transition.
            transitionOrder(eight.orderId(), activeStatusId);
            transitionOrder(five.orderId(), activeStatusId);
            assertNothingBooked(variant.getId(), "10.00");

            var paymentMethodId = savedPaymentMethodId();
            var eightAmount = orderMoneyOf(eight.orderId()).totalAmount();
            var fiveAmount = orderMoneyOf(five.orderId()).totalAmount();
            var latch = new CountDownLatch(2);
            var startSignal = new CountDownLatch(1);
            var results = new int[2];

            var chargeOne = CompletableFuture.runAsync(() -> {
                results[0] = chargeStatus(latch, startSignal, eight.orderId(), paymentMethodId, eightAmount);
            });
            var chargeTwo = CompletableFuture.runAsync(() -> {
                results[1] = chargeStatus(latch, startSignal, five.orderId(), paymentMethodId, fiveAmount);
            });

            latch.await(5, TimeUnit.SECONDS);
            startSignal.countDown();
            CompletableFuture.allOf(chargeOne, chargeTwo).get(30, TimeUnit.SECONDS);

            // Exactly one charge took the stock. A second success would mean the position was read twice, and a
            // pair of refusals would mean the lock refused more than the stock did.
            assertThat(results)
                    .as("one charge wins the position and the other is refused it")
                    .contains(200, 409);

            var stock = stockPositionOf(variant.getId());
            assertThat(stock)
                    .as("the winner's deduction, and only that one")
                    .isIn(new BigDecimal("2.0000"), new BigDecimal("5.0000"));
            assertThat(stock.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(stock);

            var sales = movementsOfType(variant.getId(), StockLedgerWriter.SALE);
            assertThat(sales).hasSize(1);
            assertThat(sales.get(0).quantity())
                    .as("the movement states the quantity the position lost")
                    .isEqualByComparingTo(new BigDecimal("10.00").subtract(stock));
            // And one payment between the two documents: the refused charge wrote none.
            assertThat(paymentsOf(eight.orderId()).size()
                            + paymentsOf(five.orderId()).size())
                    .isEqualTo(1);
        }

        /**
         * Two retries of <b>one</b> charge, released together: one document, one payment, one movement (M10).
         *
         * <p><b>Why the sibling case above does not state this.</b> It charges two different documents, so the
         * serialization it observes is the stock position's. M10 is about the document's own row: «one charge per
         * order, even under concurrent retries» — and a retry is the same request twice, from the same client, with
         * the same order id. Two threads, one {@code PATCH /charge}, is what a double-tapped button looks like.</p>
         *
         * <p><b>What the lock buys, and what its absence looks like.</b> Both threads read the row before either
         * commits; without {@code SELECT … FOR UPDATE} both see {@code PENDING}, both pass the guard, both append a
         * payment and both book the stock. {@code sale_payment} is append-only and the ledger is too, so neither
         * extra row could be taken back by anything this suite could do: the document would state a sale the
         * customer paid for twice. With the lock the loser waits, re-reads {@code COMPLETED} and is refused by the
         * state guard — which is why the refusal's <b>message</b> is asserted and not only its status: a 400 that
         * came from anywhere else would not be the serialization the invariant claims.</p>
         */
        @Test
        @DisplayName("should append exactly one payment when two retries of one charge race")
        void concurrentRetriesOfOneCharge_appendExactlyOnePayment() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();
            var amount = orderMoneyOf(placed.orderId()).totalAmount();
            var latch = new CountDownLatch(2);
            var startSignal = new CountDownLatch(1);
            var answers = new ChargeAnswer[2];

            var first = CompletableFuture.runAsync(
                    () -> answers[0] = chargeAnswer(latch, startSignal, placed.orderId(), paymentMethodId, amount));
            var second = CompletableFuture.runAsync(
                    () -> answers[1] = chargeAnswer(latch, startSignal, placed.orderId(), paymentMethodId, amount));

            latch.await(5, TimeUnit.SECONDS);
            startSignal.countDown();
            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

            assertThat(List.of(answers[0].status(), answers[1].status()))
                    .as("the lock makes the retry a no-op: one charge wins the row and the other is refused")
                    .containsExactlyInAnyOrder(200, 400);

            var refused = answers[0].status() == 200 ? answers[1] : answers[0];
            assertThat(refused.body())
                    .as("the loser is refused by the state guard, by name, and not by the database")
                    .contains("current status is COMPLETED");

            assertThat(paymentsOf(placed.orderId()))
                    .as("one payment: `sale_payment` is append-only, so a second row could not be taken back")
                    .hasSize(1);
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE))
                    .as("and one movement: the loser never reached the engine")
                    .hasSize(1);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(orderStateOf(placed.orderId())).isEqualTo(SalesOrderState.COMPLETED);
        }

        /**
         * Two line writes on <b>one</b> document, released together: the parts the document states are the sum of
         * the lines that survived (M2).
         *
         * <p><b>Why the sequential case could not state it.</b> {@code updateOrderItems_recomputesTheBreakdown…}
         * writes one line and reads the parts afterwards, so it proves the recomputation happens and nothing about
         * the moment it reads. M2 is a statement about a <b>concurrent</b> writer: the parts are recomputed in the
         * same transaction as the line write <b>under the order's row lock</b>, because a cross-row sum is not
         * expressible as a {@code CHECK} — nothing but the lock stops two writers from each summing a snapshot in
         * which the other's line is not there yet, and each committing its own version of the document.</p>
         *
         * <p><b>What the loser's state looks like without the lock.</b> Both threads write their own line (no
         * contention there — two rows), then both sum the lines and both write the five money columns of the same
         * order row. The last writer wins the parts, and if it summed before the other committed, the parts state a
         * document that never existed: subtotal 700 against lines that add up to 900, with the two lines intact. No
         * constraint refuses it — {@code ck_order_total} only holds the parts against each other, which is exactly
         * why M2 is a service invariant with a named lock.</p>
         *
         * <p><b>Why rounds.</b> The window is between the sum and the commit, so an unlocked run can also get the
         * interleaving right by luck; a probe that runs it once passes about half the time, and a regression case
         * that passes by luck is not one. Each round owns its document and its two variants, so a round cannot
         * inherit the previous one's parts.</p>
         */
        @Test
        @DisplayName("should keep the document's parts equal to its lines when two line writes race")
        void concurrentLineWrites_recomputeThePartsUnderTheOrderLock() throws Exception {
            for (var round = 1; round <= 5; round++) {
                var variant = createTestVariant(new BigDecimal("100.00"));
                var variantB = createTestVariantB(new BigDecimal("100.00"));
                var placed = createOrder(line(null, variant.getId(), "2.00"), line(null, variantB.getId(), "2.00"));
                var firstLine = placed.itemId(variant.getId());
                var secondLine = placed.itemId(variantB.getId());

                var latch = new CountDownLatch(2);
                var startSignal = new CountDownLatch(1);
                var results = new int[2];

                var first = CompletableFuture.runAsync(() -> results[0] =
                        writeLineStatus(latch, startSignal, placed.orderId(), firstLine, variant.getId(), "4.00"));
                var second = CompletableFuture.runAsync(() -> results[1] =
                        writeLineStatus(latch, startSignal, placed.orderId(), secondLine, variantB.getId(), "5.00"));

                latch.await(5, TimeUnit.SECONDS);
                startSignal.countDown();
                CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

                assertThat(results)
                        .as(
                                "round %d: two lines of one document are two writes, and neither is the other's conflict",
                                round)
                        .containsOnly(200);

                var money = orderMoneyOf(placed.orderId());
                var lines = linePartsOf(placed.orderId());
                assertThat(money.subtotal())
                        .as(
                                "round %d: M2 — the document's subtotal is Σ of its lines', with no lost update and no "
                                        + "line counted twice",
                                round)
                        .isEqualByComparingTo(lines.subtotal());
                assertThat(money.discountTotal())
                        .as("round %d: and its discount total is Σ of the lines'", round)
                        .isEqualByComparingTo(lines.discountTotal());
                assertThat(money.taxTotal())
                        .as("round %d: and its tax total is Σ of the lines'", round)
                        .isEqualByComparingTo(lines.taxTotal());
                // And the numbers themselves: 4 x 100 and 5 x 100, the two writes that survived.
                assertThat(money.subtotal())
                        .as("round %d: the two lines as both writers left them", round)
                        .isEqualByComparingTo(new BigDecimal("900.0000"));
                assertThat(money.totalAmount())
                        .as("round %d: M1 — the total the parts add up to", round)
                        .isEqualByComparingTo(new BigDecimal("900.0000"));
            }
        }

        /**
         * Writes one line of one document from one of a case's threads and answers the status the API stated.
         *
         * <p>The item-level {@code PUT} and not the order-level one: the order-level request replaces the whole line
         * set, so two of them would overwrite each other's line as well and the race would be about last-write-wins
         * instead of about the two sums. One line each is the shape that leaves both writes standing and only the
         * document's money to disagree.</p>
         */
        private int writeLineStatus(
                CountDownLatch ready,
                CountDownLatch start,
                UUID orderId,
                UUID itemId,
                UUID variantId,
                String quantity) {
            try {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(line(itemId, variantId, quantity)))
                                .with(scopedSalesJwt()))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception failed) {
                return 500;
            }
        }

        /**
         * A cancellation and a charge of one document, released together: exactly one of them happens, and the row
         * agrees with the answer.
         *
         * <p><b>The window, and why no other case would have caught it.</b> Unlocked, the cancellation read
         * {@code PENDING}, passed the ADR-0004 guard on that read, and then wrote at flush — and its
         * {@code UPDATE} re-read the row under {@code READ COMMITTED}, found the {@code COMPLETED} the charge had
         * just committed, and overwrote it. Its own transition row states {@code PENDING -> CANCELLED}, because that
         * is the move the guard authorised, so the document moved {@code COMPLETED -> CANCELLED} with no row stating
         * that move: measured on the pre-fix source, {@code V68}'s deferred constraint trigger refuses the commit
         * ({@code PSQLException: SALES_ORDER … moved COMPLETED -> CANCELLED without its status_transition row}), the
         * cancellation's transaction rolls back and the request answers <b>500</b>. Without the trigger the same
         * write commits, and because {@code deleteSalesOrder} reverses only a {@code COMPLETED} document, the
         * deduction the charge booked is stranded while {@code sale_payment} — append-only — keeps the row that
         * paid for it.</p>
         *
         * <p><b>So the case fails twice over without the lock, and the second half is the point.</b> The loser is a
         * {@code 500} instead of a refusal that names the reason, which is the outcome a client can actually act on;
         * and the biconditional below is intact only because the schema caught what the service did not. A
         * correctness argument that rests on a deferred trigger is not one this document gets to make, so both arms
         * are asserted: the refusal is 400 or 409, and the partner tables agree with the state. With the lock the
         * cancellation serializes behind the charge, re-reads {@code COMPLETED} and the guard refuses it by name.</p>
         *
         * <p><b>What the case reads.</b> Not the status history, which records nothing wrong from its own point of
         * view, and not only the responses: the partner tables. The invariant is the biconditional {@code COMPLETED}
         * ⟺ one payment row and one {@code SALE} movement, and {@code CANCELLED} ⇒ neither. The state that satisfies
         * no arm of it — a cancellation with both rows under it — is the defect's signature, and a run that ends that
         * way fails here instead of passing on a plausible-looking sequence of transitions.</p>
         *
         * <p><b>The harness is the sibling case's, on one document, and repeated.</b> Two threads, one latch to
         * release them together and one to wait for both, with the two requests differing only in the verb — which
         * is the shape that leaves the interleaving to the database rather than to the test. Three rounds, because
         * the unlocked version won or lost the race by which thread reached the database first: a probe that runs it
         * once passes by luck about half the time, and a regression case that passes by luck is not one. Each round
         * owns its document and its variant, so a round cannot inherit the previous one's position or payment.</p>
         */
        @Test
        @DisplayName("should never strand a booked deduction when a cancellation races the charge")
        void cancelRacingCharge_neverStrandsABookedDeduction() throws Exception {
            for (var round = 1; round <= 3; round++) {
                var variant = createTestVariant(new BigDecimal("100.00"));
                var placed = createOrder(line(null, variant.getId(), "4.00"));
                var orderId = placed.orderId();
                // Active is the one state both requests are legal from: the charge auto-promotes it to Pending on
                // its way to Completed, and the cancellation leaves it directly. A Draft would have no charge edge
                // at all and the race would be about the guard and not about the lock.
                transitionOrder(orderId, activeStatusId);
                assertNothingBooked(variant.getId(), "100.00");

                var paymentMethodId = savedPaymentMethodId();
                var amount = orderMoneyOf(orderId).totalAmount();
                var latch = new CountDownLatch(2);
                var startSignal = new CountDownLatch(1);
                var results = new int[2];

                var charge = CompletableFuture.runAsync(
                        () -> results[0] = chargeStatus(latch, startSignal, orderId, paymentMethodId, amount));
                var cancel = CompletableFuture.runAsync(() -> results[1] = cancelStatus(latch, startSignal, orderId));

                latch.await(5, TimeUnit.SECONDS);
                startSignal.countDown();
                CompletableFuture.allOf(charge, cancel).get(30, TimeUnit.SECONDS);

                var answered = List.of(results[0], results[1]);
                // One document, one terminal state: both requests answering 200 is the defect, whether or not the
                // partner tables below happened to agree — a second 200 means one of the two overwrote the other.
                assertThat(answered)
                        .as("round %d: exactly one of the cancellation and the charge writes the document", round)
                        .filteredOn(status -> status == 200)
                        .hasSize(1);
                // The loser is refused by the guard it raced into, and never by the database: 409 is the charge's
                // transition having already happened (Completed is terminal), 400 is the charge finding a document
                // the cancellation closed. A 5xx here would be a lock or a constraint, which is not this subject.
                var loser = answered.stream()
                        .filter(status -> status != 200)
                        .findFirst()
                        .orElseThrow();
                assertThat(loser)
                        .as("round %d: the loser is a refusal that names the reason, not a 500", round)
                        .isIn(400, 409);

                // The half that the responses cannot state, read from the tables the charge writes: a document is
                // money that moved exactly when it is Completed, and a payment or a movement without that state is
                // a sale nobody can reverse.
                var state = orderStateOf(orderId);
                var payments = paymentsOf(orderId);
                var sales = movementsOfType(variant.getId(), StockLedgerWriter.SALE);
                assertThat(state)
                        .as("round %d: the document ends in one of the two states the race can produce", round)
                        .isIn(SalesOrderState.COMPLETED, SalesOrderState.CANCELLED);

                if (state == SalesOrderState.COMPLETED) {
                    assertThat(payments)
                            .as(
                                    "round %d: a completed document holds exactly one payment, the one its charge "
                                            + "inserted",
                                    round)
                            .hasSize(1);
                    assertThat(sales)
                            .as("round %d: and exactly one SALE movement, the one its charge booked", round)
                            .hasSize(1);
                    assertThat(stockPositionOf(variant.getId()))
                            .as("round %d: which is the deduction the position lost", round)
                            .isEqualByComparingTo(new BigDecimal("96.00"));
                } else {
                    // The defect's signature: a CANCELLED document carrying the charge's payment and its movement.
                    // `sale_payment` is append-only, so such a row cannot be taken back, and the reversal path
                    // only runs for a COMPLETED document, so nothing gives the units back either.
                    assertThat(payments)
                            .as(
                                    "round %d: a cancelled document holds no payment — the charge that would write "
                                            + "one is the write the guard refused",
                                    round)
                            .isEmpty();
                    assertThat(sales)
                            .as(
                                    "round %d: and no SALE movement — stock booked under a cancelled document is "
                                            + "stranded, because only a delete of a COMPLETED document reverses",
                                    round)
                            .isEmpty();
                    assertThat(stockPositionOf(variant.getId()))
                            .as("round %d: the position still holds every unit it started with", round)
                            .isEqualByComparingTo(new BigDecimal("100.00"));
                }
            }
        }

        /** The document's persisted state, read from the row both of the case's threads wrote. */
        private SalesOrderState orderStateOf(UUID orderId) {
            return inTransaction(
                    () -> salesOrderRepository.findById(orderId).orElseThrow().getStatus());
        }

        /** Cancels one order from one of the case's threads and answers the status the API stated. */
        private int cancelStatus(CountDownLatch ready, CountDownLatch start, UUID orderId) {
            try {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new UpdateSalesOrderStatusRequest(cancelledStatusId)))
                                .with(scopedSalesJwt()))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception failed) {
                return 500;
            }
        }
    }

    // ─── 5.10 A charge that cannot cover its stock → full rollback

    @Nested
    @DisplayName("5.10 A charge whose stock cannot be covered — full rollback")
    class MultiItemCreateRollbackTests {

        /**
         * The strongest rollback this document can state: the payment, the transition, the line states and the
         * movement of the line that <b>could</b> have been covered all go back together.
         *
         * <p><b>Why it is stronger than the create-side case it replaces.</b> That case only had the inserted lines
         * to roll back. A charge writes four things in one transaction — a {@code sale_payment} row that is
         * append-only, the breakdown, the order's state and every line's state — and the stock batch itself runs
         * two deductions, the first of which succeeds. Each of those is asserted here, because each of them is a
         * write that would survive on its own if the transaction boundary were in the wrong place.</p>
         */
        @Test
        @DisplayName("should roll back the payment, the transitions, the lines and the covered line's deduction")
        void charge_OneLineInsufficient_RollsBackEverything() throws Exception {
            var variantA = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("2.00"));

            // Order: A qty 5 (sufficient), B qty 10 (insufficient — only 2 available)
            var placed = createOrder(line(null, variantA.getId(), "5.00"), line(null, variantB.getId(), "10.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            assertNothingBooked(variantA.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "2.00");

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // No payment row: the table is append-only, so a row that landed could not be undone.
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            // No transition: the document is where the case left it, and neither line moved to Added.
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getStatus()))
                    .isEqualTo(SalesOrderState.ACTIVE);
            assertThat(inTransaction(() -> itemRepository.findBySalesOrderId(placed.orderId())))
                    .hasSize(2)
                    .allSatisfy(item -> assertThat(item.getStatus()).isEqualTo(SalesOrderItemState.PENDING));
            // A's deduction was in the same batch and rolled back with it: both positions are the fixture's and
            // neither variant carries a movement.
            assertThat(stockPositionOf(variantA.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variantA.getId())).isEmpty();
            assertThat(stockPositionOf(variantB.getId())).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(locationStockOf(variantB.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(movementsOf(variantB.getId())).isEmpty();
        }
    }

    // ─── Full flow: Draft → add item (→ Active) → add another item → charge (→ Pending → Completed)

    @Nested
    @DisplayName("Full flow: Draft → Active → Pending → Completed via charge")
    class FullFlowChargeIntegrationTests {

        @Test
        @DisplayName("should create Draft, add items, and charge end-to-end")
        void fullFlow_DraftToActiveToCharge() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Step 1: Create empty Draft order
            var createRequest = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", null);

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.statusName").value("Draft"))
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Step 2: Add first item → auto-transitions Draft → Active
            var firstItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("3.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstItem))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.quantity").value(3.00));

            // Verify order is now Active (status updated in DB). The read states this class's company
            // because `TenantScopeFilter` clears the thread's scope in its `finally`: the scope
            // `@BeforeEach` set is gone after the first `mockMvc.perform`, and `sales_orders` has been armed
            // since `V65`, so a bare `findById` answers no row for an order the request just committed.
            var orderAfterFirstItem = inTransaction(() ->
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow());
            // The state is the row's, and the label is projected from its code: `V71` dropped the pointer this
            // read used to follow, so the pair is asked of the catalog by (family, code) — the same projection
            // every response makes.
            assertThat(orderAfterFirstItem.getStatus()).isEqualTo(SalesOrderState.ACTIVE);
            var statusAfterFirstItem = inTransaction(() -> statusRepository
                    .findByTypeNameAndCode(
                            "SALES_ORDER", orderAfterFirstItem.getStatus().name())
                    .orElseThrow());
            assertThat(statusAfterFirstItem.getStatusName()).isEqualTo("Active");

            // The add is a line and nothing else: the store still holds its 100 (ADR-0002 decision 7).
            assertNothingBooked(variant.getId(), "100.00");

            // Step 3: Add second item → stays Active
            var secondItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(secondItem))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.quantity").value(2.00));

            // Verify order is still Active (not re-transitioned)
            var orderAfterSecondItem = inTransaction(() ->
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow());
            assertThat(orderAfterSecondItem.getStatus()).isEqualTo(SalesOrderState.ACTIVE);
            var statusAfterSecondItem = inTransaction(() -> statusRepository
                    .findByTypeNameAndCode(
                            "SALES_ORDER", orderAfterSecondItem.getStatus().name())
                    .orElseThrow());
            assertThat(statusAfterSecondItem.getStatusName()).isEqualTo("Active");

            // Still nothing booked: the second add is a second line, not a second deduction.
            assertNothingBooked(variant.getId(), "100.00");

            // Step 4: Charge → auto-promotes Active → Pending → Completed
            var paymentMethod = PaymentMethod.builder()
                    .paymentMethodName("Efectivo-" + UUID.randomUUID())
                    .paymentMethodShortName("EFECTIVO")
                    .enabled(true)
                    .build();
            paymentMethod = paymentMethodRepository.save(paymentMethod);
            var paymentMethodId = paymentMethod.getId();
            var chargeRequest = chargeForItsTotal(UUID.fromString(orderId), paymentMethodId);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Completed"))
                    .andExpect(jsonPath("$.paymentMethodId").doesNotExist());

            // Verify final order status is Completed
            var finalOrder = inTransaction(() ->
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow());
            assertThat(finalOrder.getStatus()).isEqualTo(SalesOrderState.COMPLETED);
            var finalStatus = inTransaction(() -> statusRepository
                    .findByTypeNameAndCode("SALES_ORDER", finalOrder.getStatus().name())
                    .orElseThrow());
            assertThat(finalStatus.getStatusName()).isEqualTo("Completed");
            assertThat(paymentsOf(UUID.fromString(orderId)))
                    .as("ADR-0002 step 6 has run: sales_orders.payment_method_id is dropped by V87, and how the "
                            + "document was paid is a row — the payment the charge appended, naming the method the "
                            + "request took the money through")
                    .singleElement()
                    .satisfies(payment -> assertThat(payment.methodId()).isEqualTo(paymentMethodId));

            // The charge is what books the document: 100 - 3 - 2 = 95, one SALE row per line, each naming its
            // own line. Before T6b this assertion said "stock unchanged after charging" — the movement had already
            // happened at create.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));
            var bookedLines = movementsOfType(variant.getId(), StockLedgerWriter.SALE);
            assertThat(bookedLines).hasSize(2);
            assertThat(bookedLines).extracting(LedgerMovement::sourceType).containsOnly("SALES_ORDER_ITEM");
            assertThat(bookedLines.stream()
                            .map(LedgerMovement::sourceId)
                            .distinct()
                            .count())
                    .as("one movement per line, each referencing its own line and not the other's")
                    .isEqualTo(2L);
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();

            // Verify items transitioned to Added
            var items = itemRepository.findBySalesOrderId(UUID.fromString(orderId));
            for (var item : items) {
                if (item.getEnabled()) {
                    assertThat(item.getStatus()).isEqualTo(SalesOrderItemState.ADDED);
                    var itemStatus = inTransaction(() -> statusRepository
                            .findByTypeNameAndCode(
                                    "SALES_ORDER_ITEM", item.getStatus().name())
                            .orElseThrow());
                    assertThat(itemStatus.getStatusName()).isEqualTo("Added");
                }
            }
        }
    }

    // ─── T6a — the document's money is its parts ─────────────────

    /**
     * The five money columns of one document, as the row states them: what a till report prints, and the
     * four parts {@code ck_order_total} makes add up to the total exactly (ADR-0002 M1).
     */
    private record OrderMoney(
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal roundingAdjustment,
            BigDecimal totalAmount) {}

    /**
     * One {@code sale_payment} row. The table is append-only, so every field here is what the charge stated
     * once and nothing in the tree edits afterwards (ADR-0002 decision 5).
     */
    private record PaymentRow(
            UUID methodId,
            String direction,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount,
            String reference,
            String actor,
            OffsetDateTime paidAt) {}

    /**
     * The document's money, read from the row inside this class's tenant.
     *
     * <p>{@code sales_orders} has been armed <b>and forced</b> since {@code V65}, so an unscoped read answers
     * no row instead of the row that is there — which is why this goes through {@link #inTransaction}.</p>
     */
    private OrderMoney orderMoneyOf(UUID orderId) {
        return inTransaction(() -> jdbcTemplate.queryForObject(
                """
                SELECT subtotal, discount_total, tax_total, rounding_adjustment, total_amount
                  FROM sales_orders
                 WHERE id = ?
                """,
                (rs, rowNum) -> new OrderMoney(
                        rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("tax_total"),
                        rs.getBigDecimal("rounding_adjustment"),
                        rs.getBigDecimal("total_amount")),
                orderId));
    }

    /**
     * Every payment one document carries, oldest first.
     *
     * <p>{@code V83} armed {@code sale_payment} on {@code company_id} and forced the policy, so this reads
     * under the tenant like every other armed table in this suite.</p>
     */
    private List<PaymentRow> paymentsOf(UUID orderId) {
        return inTransaction(() -> jdbcTemplate.query(
                """
                SELECT method_id, direction, amount, tendered_amount, change_amount, reference, actor, paid_at
                  FROM sale_payment
                 WHERE company_id = ? AND sales_order_id = ?
                 ORDER BY paid_at, id
                """,
                (rs, rowNum) -> new PaymentRow(
                        rs.getObject("method_id", UUID.class),
                        rs.getString("direction"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("tendered_amount"),
                        rs.getBigDecimal("change_amount"),
                        rs.getString("reference"),
                        rs.getString("actor"),
                        rs.getObject("paid_at", OffsetDateTime.class)),
                companyId,
                orderId));
    }

    /**
     * One decimal field of a response, refused by name when the response omits it.
     *
     * <p><b>Why not {@code node.get(field).decimalValue()}.</b> A field the record does not carry is a JSON node
     * that is not there, and dereferencing it answers a {@code NullPointerException} that names no field: the very
     * defect the sheet cases exist for is an absent part, so the absence has to be the assertion. The
     * comparison then reads the number the response stated.</p>
     */
    private BigDecimal decimalOf(JsonNode node, String field) {
        assertThat(node.get(field)).as("the response states %s", field).isNotNull();
        return node.get(field).decimalValue();
    }

    /** The document as the read endpoint answers it — the other side of the write paths this suite usually reads. */
    private JsonNode orderResponseOf(UUID orderId) throws Exception {
        var body = mockMvc.perform(get("/api/sales-orders/{id}", orderId).with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    /** The one line the read endpoint answers for a document, by id. */
    private JsonNode itemFromReadEndpoint(UUID orderId, UUID itemId) throws Exception {
        var body = mockMvc.perform(get("/api/sales-orders/{id}/items", orderId).with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (var item : objectMapper.readTree(body)) {
            if (itemId.toString().equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("the read endpoint states no line " + itemId + " of order " + orderId);
    }

    /**
     * The two name snapshots of a line, written where {@code V81} writes them.
     *
     * <p><b>Why the fixture is the writer.</b> The migration is the only writer those columns have ever had: it
     * backfilled every existing line from the variant's product and its own name, and no path in the application
     * states one yet (ADR-0002 step 5's writer is a later unit). A case that asserts the response reports them
     * therefore has to state the row the way the migration left it, and the fact that this helper is the only way
     * to do it is exactly what the column's javadoc records.</p>
     */
    private void writeSnapshotsAsV81Did(UUID itemId, String productName, String variantName) {
        inTransaction(() -> jdbcTemplate.update(
                "UPDATE sales_order_items SET product_name_snapshot = ?, variant_name_snapshot = ? WHERE id = ?",
                productName,
                variantName,
                itemId));
    }

    /**
     * An enabled payment method with the cash flag the case states: a fresh row per case, because the catalog is
     * global and a case must not depend on what another one wrote.
     *
     * <p>{@code is_cash} is the column ADR-0002 M4 reads, and it is stated here rather than inherited from the
     * seeded {@code Efectivo} row: {@code V83} backfills that row by its exact name, so a fixture that reused the
     * name would be relying on a migration's data instead of on the flag the request is judged against.</p>
     */
    private PaymentMethod savedPaymentMethod(String name, boolean cash) {
        return paymentMethodRepository.save(PaymentMethod.builder()
                .paymentMethodName(name + "-" + UUID.randomUUID())
                .paymentMethodShortName(cash ? "EFECTIVO" : "TARJETA")
                .enabled(true)
                .isCash(cash)
                .build());
    }

    /**
     * The method every charge case that states no tender charges through, and a card on purpose: a cash method
     * named by a case that never counts cash would be a fixture the M4 guard is supposed to look past.
     */
    private UUID savedPaymentMethodId() {
        return savedPaymentMethod("Tarjeta", false).getId();
    }

    /** A cash method: the one a payment that states a tender is allowed to name (ADR-0002 M4). */
    private PaymentMethod savedCashPaymentMethod() {
        return savedPaymentMethod("Efectivo", true);
    }

    /** A method that is not cash, carrying the name the M4 refusal has to cite. */
    private PaymentMethod savedCardPaymentMethod() {
        return savedPaymentMethod("Tarjeta Crédito", false);
    }

    /** Charges the order through the API and states the one outcome every charge case shares. */
    private void charge(UUID orderId, UUID paymentMethodId) throws Exception {
        mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chargeForItsTotal(orderId, paymentMethodId)))
                        .with(scopedSalesJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("Completed"));
    }

    /**
     * The charge body a client sends: one payment, for the document's own total.
     *
     * <p><b>Why the total is read and not a constant.</b> Since T7b a charge's payments add up to the document's
     * total <b>exactly</b> (ADR-0002 M3, and D-g's exact reading of it), so a fixture that stated an amount of its
     * own would be sending a different request from the one a till sends — and the total is a row, not a number:
     * every create, edit, add and delete recomputes it from the lines.</p>
     */
    private ChargeSalesOrderRequest chargeForItsTotal(UUID orderId, UUID paymentMethodId) throws Exception {
        return onePayment(paymentMethodId, orderMoneyOf(orderId).totalAmount());
    }

    /**
     * The charge body with the amount the case itself states — for a case whose point is that the request and the
     * document disagree, or whose document has a total no payment can state.
     */
    private ChargeSalesOrderRequest onePayment(UUID paymentMethodId, BigDecimal amount) {
        return new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(paymentMethodId, amount, null, null)));
    }

    /** What one concurrent charge answered: the status, and the body that names which guard refused it. */
    private record ChargeAnswer(int status, String body) {}

    /**
     * Charges one order from one of a case's threads and answers what the API stated, body included.
     *
     * <p>The amount is the case's, read from the document's row <b>before</b> the two threads are released:
     * the charge's payments add up to the total exactly (T7b), so a body read inside the race would add a
     * statement to the window the case exists to measure.</p>
     *
     * <p><b>Why the body and not only the status.</b> Two refusals can answer {@code 400} on this endpoint — the
     * state guard and the tender arithmetic — and only one of them is the loser of a race on the document's state.
     * A case that asserted the number alone would call a request refused for an unrelated reason a serialization.</p>
     */
    private ChargeAnswer chargeAnswer(
            CountDownLatch ready, CountDownLatch start, UUID orderId, UUID paymentMethodId, BigDecimal amount) {
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            var response = mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(onePayment(paymentMethodId, amount)))
                            .with(scopedSalesJwt()))
                    .andReturn()
                    .getResponse();
            return new ChargeAnswer(response.getStatus(), response.getContentAsString());
        } catch (Exception failed) {
            return new ChargeAnswer(500, String.valueOf(failed));
        }
    }

    /** The status alone, for a case whose assertion is the pair of answers and not the reason of one. */
    private int chargeStatus(
            CountDownLatch ready, CountDownLatch start, UUID orderId, UUID paymentMethodId, BigDecimal amount) {
        return chargeAnswer(ready, start, orderId, paymentMethodId, amount).status();
    }

    /**
     * Charges the document from whatever state the case left it in, which is the whole of what a case needs to
     * say about it.
     *
     * <p><b>Why it reads the state instead of always transitioning.</b> A Draft has no charge transition
     * (ADR-0004 decision 3), so a document still in Draft has to be moved to Active first — while the item
     * endpoint auto-activates, and a case that transitions the order itself leaves it Active or Pending. A
     * helper that always transitioned would invent a second move on those, and one that never did would fail on
     * every document created with its lines in the same request.</p>
     */
    private void chargeFromItsCurrentState(UUID orderId) throws Exception {
        var state = inTransaction(
                () -> salesOrderRepository.findById(orderId).orElseThrow().getStatus());
        if (state == SalesOrderState.DRAFT) {
            transitionOrder(orderId, activeStatusId);
        }
        charge(orderId, savedPaymentMethodId());
    }

    /**
     * Asserts that a document has booked nothing: the position the fixture seeded is exactly as it was, and the
     * ledger holds no movement of this fixture's.
     *
     * <p>Since ADR-0002 decision 7 that is the state of a document before its charge, whatever create, add,
     * edit, delete, cancel or re-enable it went through. Stated once here so a case says which moment it is
     * looking at instead of repeating two assertions that must agree.</p>
     */
    private void assertNothingBooked(UUID variantId, String seededPosition) {
        assertThat(stockPositionOf(variantId))
                .as("a document holds no stock until its charge books it (ADR-0002 decision 7)")
                .isEqualByComparingTo(new BigDecimal(seededPosition));
        assertThat(movementsOf(variantId))
                .as("and the ledger holds no movement of this document's")
                .isEmpty();
    }

    /**
     * The lines' own money, summed from the table the document's parts are derived from: Σ {@code subtotal},
     * Σ {@code discount_applied} and Σ {@code tax_amount} over the enabled lines (ADR-0002 M2).
     *
     * <p><b>Why the columns and not the entities.</b> {@code subtotal} is derived by {@code V81}'s trigger and
     * {@code final_price} by {@code V85}'s, so the rows are the only place a line's money exists: an entity a write
     * left behind carries the number the insert sent. Reading the two sides from the schema is also what makes the
     * comparison the invariant — the document's parts against its lines — instead of two readers of one list.</p>
     */
    private record LineParts(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal) {}

    private LineParts linePartsOf(UUID orderId) {
        return inTransaction(() -> jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(subtotal), 0), COALESCE(SUM(discount_applied), 0), COALESCE(SUM(tax_amount), 0)
                  FROM sales_order_items
                 WHERE sales_order_id = ? AND enabled
                """,
                (rs, rowNum) -> new LineParts(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                orderId));
    }

    /**
     * The ids of a document's payments, oldest first: what a void row has to name.
     *
     * <p>Read under the tenant like {@link #paymentsOf}: {@code V83} armed {@code sale_payment} and forced the
     * policy, so an unscoped read answers no row instead of the row that is there.</p>
     */
    private List<UUID> paymentIdsOf(UUID orderId) {
        return inTransaction(() -> jdbcTemplate.queryForList(
                "SELECT id FROM sale_payment WHERE company_id = ? AND sales_order_id = ? ORDER BY paid_at, id",
                UUID.class,
                companyId,
                orderId));
    }

    /**
     * The void rows a document's payments carry, read from the table that makes a payment void (M5).
     *
     * <p>Its own reader and not a flag on {@link #paymentsOf}: the void is a row in another table, and a payment is
     * void <b>if and only if</b> a row here names it — so the assertion has to be about these rows and not about a
     * column somebody could have set.</p>
     */
    private List<UUID> voidedPaymentIdsOf(UUID orderId) {
        return inTransaction(() -> jdbcTemplate.queryForList("""
                SELECT v.payment_id
                  FROM sale_payment_void v
                  JOIN sale_payment p ON p.id = v.payment_id AND p.company_id = v.company_id
                 WHERE v.company_id = ? AND p.sales_order_id = ?
                 ORDER BY v.occurred_at, v.payment_id
                """, UUID.class, companyId, orderId));
    }

    /**
     * Appends the void row that makes a payment void (ADR-0002 decision 5, M5).
     *
     * <p><b>Why the fixture is the writer.</b> The void is the payment's transition, and no unit of this tree
     * authorizes one yet: {@code chargeSalesOrder} takes money in and never gives it back (D-g), so the endpoint that
     * voids a payment is a later unit's. A case that asserts the net excludes a void therefore has to state the row
     * the way that unit will, through the one privilege the application role holds on this table — {@code INSERT},
     * which is the half of the append-only rule that must stay open.</p>
     */
    private void voidPayment(UUID paymentId, String reason) {
        inTransaction(() -> jdbcTemplate.update(
                "INSERT INTO sale_payment_void (payment_id, company_id, actor, reason) VALUES (?, ?, ?, ?)",
                paymentId,
                companyId,
                "supervisor",
                reason));
    }

    /**
     * Appends a {@code REFUND} row the charge never writes: the one direction the request cannot state (D-g).
     *
     * <p>It exists here because M3's row-reading half — {@code Σ CHARGE − Σ REFUND} over the non-voided rows — has no
     * other way to see a refund: the endpoint only charges, so a refund is a row only a fixture can state.</p>
     */
    private UUID recordRefund(UUID orderId, UUID paymentMethodId, BigDecimal amount) {
        return inTransaction(() -> {
            var paymentId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO sale_payment (id, company_id, sales_order_id, method_id, direction, amount, paid_at, actor)
                    VALUES (?, ?, ?, ?, 'REFUND', ?, now(), ?)
                    """, paymentId, companyId, orderId, paymentMethodId, amount, "seller");
            return paymentId;
        });
    }

    @Nested
    @DisplayName("T6a The document's money is its parts — ADR-0002 M1, M2 and M10")
    class OrderMoneyBreakdownTests {

        @Test
        @DisplayName("should state the breakdown of its lines when the order is created")
        void createOrder_statesTheBreakdownOfItsLines() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));

            // The two lines of the case: 2 × 100.00 with a 10.00 discount and 3 × 50.00 with none. The
            // discount is what makes the document's three parts three different numbers instead of two.
            var discounted = new SalesOrderItemRequest(
                    null,
                    variant.getId(),
                    new BigDecimal("2.00"),
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    null);
            var plain = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("3.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);
            var request =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(discounted, plain));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = UUID.fromString(objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("id")
                    .asText());

            // The parts are the lines': subtotal is Σ round(list_price × quantity, 4) = 200 + 150, the
            // discount is Σ discount_applied = 10, and neither product declares a taxed code (the column's
            // `NA` default is zero by definition), so there is no tax to add.
            var money = orderMoneyOf(orderId);
            assertThat(money.subtotal()).isEqualByComparingTo(new BigDecimal("350.0000"));
            assertThat(money.discountTotal()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(money.taxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(money.roundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(money.totalAmount()).isEqualByComparingTo(new BigDecimal("340.0000"));

            // And the response states the same number the row does: 340.00 is exactly representable in MXN,
            // so the single rounding at the document boundary moves nothing.
            assertThat(new BigDecimal(objectMapper
                            .readTree(created.getResponse().getContentAsString())
                            .get("totalAmount")
                            .asText()))
                    .isEqualByComparingTo(new BigDecimal("340.0000"));
        }

        @Test
        @DisplayName("should recompute the document's parts when a line's quantity changes")
        void updateOrderItems_recomputesTheBreakdownUnderTheOrderLock() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var variantB = createTestVariantB(new BigDecimal("10.00"));

            // Two variants, because the case needs each line's id: `PlacedOrder` keys the ids by variant, and
            // two lines of one variant would leave only one of them addressable.
            var placed = createOrder(
                    new SalesOrderItemRequest(
                            null,
                            variant.getId(),
                            new BigDecimal("2.00"),
                            new BigDecimal("100.00"),
                            new BigDecimal("10.00"),
                            null),
                    new SalesOrderItemRequest(
                            null,
                            variantB.getId(),
                            new BigDecimal("3.00"),
                            new BigDecimal("50.00"),
                            BigDecimal.ZERO,
                            null));
            assertThat(orderMoneyOf(placed.orderId()).totalAmount()).isEqualByComparingTo(new BigDecimal("340.0000"));

            // Only the first line's quantity moves: 2 → 4, so the document's subtotal grows by 200 and its
            // total has to grow with it. A document that kept 340.0000 here would be a stale total, which is
            // the state ADR-0002 M2 (recompute in the same transaction as the line write, under the order's
            // row lock) exists to make impossible.
            updateOrderItems(
                    placed.orderId(),
                    new SalesOrderItemRequest(
                            placed.itemId(variant.getId()),
                            variant.getId(),
                            new BigDecimal("4.00"),
                            new BigDecimal("100.00"),
                            new BigDecimal("10.00"),
                            null),
                    new SalesOrderItemRequest(
                            placed.itemId(variantB.getId()),
                            variantB.getId(),
                            new BigDecimal("3.00"),
                            new BigDecimal("50.00"),
                            BigDecimal.ZERO,
                            null));

            var money = orderMoneyOf(placed.orderId());
            assertThat(money.subtotal()).isEqualByComparingTo(new BigDecimal("550.0000"));
            assertThat(money.discountTotal()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(money.totalAmount()).isEqualByComparingTo(new BigDecimal("540.0000"));
        }

        @Test
        @DisplayName("should append exactly one CHARGE payment for the document's total")
        void chargeAppendsOnePaymentForTheTotal() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            // A document born with its lines in the same request stays Draft (only the item endpoint
            // auto-activates), and a Draft has no transition into a charge: the order is moved to Active
            // first, which is the state the charge then auto-promotes to Pending.
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();
            // Nothing has moved yet: the document holds its two units and the store all ten.
            assertNothingBooked(variant.getId(), "10.00");

            charge(placed.orderId(), paymentMethodId);

            var money = orderMoneyOf(placed.orderId());
            var payments = paymentsOf(placed.orderId());
            assertThat(payments).hasSize(1);

            var payment = payments.get(0);
            assertThat(payment.direction()).isEqualTo("CHARGE");
            assertThat(payment.amount())
                    .as("the payment states the document's total, under the lock that produced it")
                    .isEqualByComparingTo(money.totalAmount());
            assertThat(payment.amount()).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(payment.methodId()).isEqualTo(paymentMethodId);
            assertThat(payment.actor())
                    .as("the actor is the token's preferred_username, not the order's user_id")
                    .isEqualTo("seller");
            assertThat(payment.paidAt()).isNotNull();
            assertThat(payment.tenderedAmount())
                    .as("a tender nobody counted is not invented by the server: this body stated none")
                    .isNull();
            assertThat(payment.changeAmount()).isNull();

            // The same transaction is the one that books the stock (ADR-0002 decision 7): the two units leave the
            // position, and the row names the line the document holds.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("8.00"));
            var sales = movementsOfType(variant.getId(), StockLedgerWriter.SALE);
            assertThat(sales).hasSize(1);
            assertThat(sales.get(0).sourceId()).isEqualTo(placed.itemId(variant.getId()));
            assertThat(sales.get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.00"));
        }

        @Test
        @DisplayName("should refuse a second charge and append no second payment")
        void chargeTwice_isRefusedAndAppendsNoSecondPayment() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            // Draft is not chargable, so the first charge starts from Active — the state the retry then finds
            // behind itself as Completed.
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();
            charge(placed.orderId(), paymentMethodId);

            // The same request a second time, which is what a retried charge looks like.
            var second = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), paymentMethodId)))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A Completed order is not a chargable one: the refusal is the state guard's 400, taken under the
            // lock that makes the retry see the first charge's commit (ADR-0002 M10).
            assertThat(second.getResponse().getStatus()).isEqualTo(400);

            // The row count is the observable that matters, and the refusal alone is not enough: a charge that
            // appended its payment and only then met the guard would answer 400 as well, and `sale_payment` is
            // append-only — a row that landed could not be taken back by anything this suite could do.
            assertThat(paymentsOf(placed.orderId())).hasSize(1);
            assertThat(orderMoneyOf(placed.orderId()).totalAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));
            // And the stock moved once, not twice: the refusal happened before the second batch could book
            // anything, which is what the row count states and the aggregate alone cannot.
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("8.00"));
        }

        @Test
        @DisplayName("should refuse to charge a document whose enabled lines add up to zero, and append no payment")
        void chargeWithNothingToPay_isRefusedAndAppendsNoPayment() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();

            // The document's only line is removed, so what the charge is about to see holds nothing left to pay
            // for and the breakdown states a total of 0.0000. It is removed rather than cancelled because a
            // cancelled line keeps `enabled = true` and `sumMoneyParts` still sums it — a separate accounting
            // question this case does not cover.
            mockMvc.perform(delete(
                                    "/api/sales-orders/{id}/items/{itemId}",
                                    placed.orderId(),
                                    placed.itemId(variant.getId()))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(orderMoneyOf(placed.orderId()).totalAmount()).isEqualByComparingTo(new BigDecimal("0.0000"));

            var refused = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            // The body is what a till sends for the line it believes is still there; the refusal
                            // below is the document's — nothing to pay — and not the amount's.
                            .content(objectMapper.writeValueAsString(
                                    onePayment(paymentMethodId, new BigDecimal("200.0000"))))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A document with nothing to pay is a business refusal (409) that explains itself, never the
            // schema's refusal read back: `V83` states `CHECK (amount > 0)`, so a zero payment is caught at flush
            // as a `DataIntegrityViolationException`, which the handler answers with a generic 409 that names
            // neither the order nor the reason. The status alone therefore proves nothing — the message does.
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("Cannot charge sales order " + placed.orderId())
                    .contains("there is nothing to pay");

            // The row count is the observable that matters, and the refusal alone is not enough: a charge that
            // appended its payment and only then met the guard would answer the same status, and `sale_payment`
            // is append-only — a row that landed could not be taken back by anything this suite could do.
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            // The deleted line carries no stock either: the document's only line is gone, so the batch the charge
            // would send is empty and nothing reached the engine at all.
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
        }
    }

    // ─── T10 — M5's void and M8's rounding boundary ──────────────

    /**
     * The two invariants of ADR-0002 that no single row can state, and that this suite had never observed.
     *
     * <p><b>M5's second half: a void row excludes its payment from the net.</b> The first half — a void is never an
     * {@code UPDATE} and never a {@code DELETE} — is the trigger and the two {@code REVOKE}s of {@code V83}, and
     * {@code SalePaymentRepository} is the only reader of the other half: {@code netPaid}'s
     * {@code NOT EXISTS (SELECT 1 FROM sale_payment_void …)}. No case in the tree inserted a
     * {@code sale_payment_void} row, so that statement had never run against PostgreSQL, while the unit test's
     * javadoc already claimed this suite proved it.</p>
     *
     * <p><b>M8 and decision 1: the rounding happens once, at the document boundary.</b> Every money assertion in the
     * service suite states {@code roundingAdjustment = 0.0000}, because every fixture's lines are exactly
     * representable in MXN — so the one place {@code SalesOrderService.recomputeBreakdown} rounds to the document's
     * {@code currency.minor_units} was unobserved. The case below builds a line whose money is a ten-thousandth of a
     * peso, which is the only way to make the adjustment non-zero.</p>
     */
    @Nested
    @DisplayName("T10 A void excludes its payment (M5) and the total is rounded once (M8)")
    class MoneyInvariantTests {

        /**
         * M5, read through the production reader, on a database: the net is the charge's payment until a void row
         * names it, and the payment itself survives.
         *
         * <p><b>Why the positive control is half the case.</b> A {@code netPaid} that answered zero for everything
         * would satisfy the assertion after the void just as well, so the same statement is read before it: the
         * number has to be the total first and zero second, and the only thing that changed between the two reads is
         * the void row. That is the biconditional M5 states — a payment is void <b>if and only if</b> a void row names
         * it — observed from the side that decides what the customer has paid.</p>
         *
         * <p><b>Why the row count is the other half.</b> A void that deleted the payment would also make the net
         * zero, and it would destroy the drawer's history: {@code sale_payment_void} is an appended row precisely so
         * the voided payment is still readable beside the row that voided it.</p>
         */
        @Test
        @DisplayName("a void row excludes its payment from the net, and the payment row survives it")
        void voidRow_excludesItsPaymentFromTheNet() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            charge(placed.orderId(), savedPaymentMethodId());

            var total = orderMoneyOf(placed.orderId()).totalAmount();
            assertThat(inTransaction(() -> salePaymentRepository.netPaid(companyId, placed.orderId())))
                    .as("what the document has paid is the charge's one payment")
                    .isEqualByComparingTo(total);

            var paymentId = paymentIdsOf(placed.orderId()).get(0);
            voidPayment(paymentId, "wrong tender: the customer paid with a card");

            assertThat(inTransaction(() -> salePaymentRepository.netPaid(companyId, placed.orderId())))
                    .as("M5: a payment is void if and only if a void row names it, so the net no longer counts it")
                    .isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(paymentsOf(placed.orderId()))
                    .as("and the payment is still there — a void is an appended row, never a delete")
                    .hasSize(1);
            assertThat(voidedPaymentIdsOf(placed.orderId()))
                    .as("the void row names exactly the payment it voids")
                    .containsExactly(paymentId);
        }

        /**
         * M5 through the only HTTP path that reads the net: a voided refund stops reducing what the document has
         * paid, so the same request that was refused is accepted.
         *
         * <p><b>Why a refund and not a charge.</b> The request the charge appends is always for the document's total,
         * so a voided {@code CHARGE} row only ever adds to the sum and no answer would change. A {@code REFUND}
         * subtracts — M3 states {@code Σ CHARGE − Σ REFUND} — which makes it the one row whose exclusion is
         * observable through the guard: counted, the net is 150 against a total of 200 and the charge is refused;
         * voided, the net is 200 and the same charge completes.</p>
         *
         * <p><b>What this pins besides the exclusion.</b> That the guard reads rows and not the request: the two
         * attempts send the identical body, and only the void row between them changed the answer. And that a
         * refused charge appends nothing — the 200 row it inserted before the guard is rolled back with the
         * transaction, which is the half {@code sale_payment}'s append-only rule makes unrecoverable if it were not.</p>
         */
        @Test
        @DisplayName(
                "the guard reads the net of the non-voided rows: a live refund reduces it and a voided one does not")
        void chargeGuard_readsTheNetOfTheNonVoidedRows() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();
            var total = orderMoneyOf(placed.orderId()).totalAmount();

            var refundId = recordRefund(placed.orderId(), paymentMethodId, new BigDecimal("50.0000"));

            var refused = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(onePayment(paymentMethodId, total)))
                            .with(scopedSalesJwt()))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString())
                    .as("the refusal states the net it read: the charge's 200 minus the live refund's 50")
                    .contains("its non-voided payments add up to 150.0000")
                    // The total is the one the breakdown just wrote, so it is stated at the currency's own
                    // scale: MXN rounds to two decimals (decision 1), and 200.0000 is the column's grain.
                    .contains("does not cover the order's total of 200.00");
            assertThat(paymentsOf(placed.orderId()))
                    .as("the refused charge appended nothing: the table holds the refund and only it")
                    .hasSize(1);

            voidPayment(refundId, "the refund was recorded against the wrong document");

            charge(placed.orderId(), paymentMethodId);

            assertThat(paymentsOf(placed.orderId()))
                    .as("the voided refund and the charge: two rows, neither of them edited")
                    .hasSize(2);
            assertThat(voidedPaymentIdsOf(placed.orderId())).containsExactly(refundId);
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getStatus()))
                    .as("and the document completed, on the net the void left")
                    .isEqualTo(SalesOrderState.COMPLETED);
        }

        /**
         * M8 and decision 1, at the boundary they are about: the line keeps a ten-thousandth, the document rounds
         * once, and the difference is stated as the adjustment instead of being absorbed.
         *
         * <p><b>Why {@code 0.3333 × 1.0000}.</b> MXN's minor unit is 0.01 ({@code currency.minor_units = 2}), so the
         * line's 0.3333 is not representable in it. Rounding the <b>line</b> to 0.33 would make the document's
         * subtotal disagree with its own lines by a ten-thousandth per line — which is what «never per line, which
         * is how a total stops matching the paper» (S2) forbids. Rounding only the total is what leaves an
         * adjustment of {@code 0.3300 − 0.3333 = −0.0033}.</p>
         *
         * <p><b>What each assertion rules out.</b> The subtotal at four decimals rules out a per-line rounding; the
         * total at two rules out no rounding at all; the adjustment being exactly the difference rules out a total
         * that was rounded and a document that did not say so; M1's exact sum is the {@code CHECK} read back; and the
         * bound is M8's, one minor unit of the document's own currency — a {@code CHECK} cannot read the catalog, so
         * this is the only place the bound can be stated against the currency instead of against the literal 1.</p>
         */
        @Test
        @DisplayName("rounds the total once at the document boundary and states the difference as the adjustment")
        void documentTotal_roundsOnceAtTheDocumentBoundary() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var placed = createOrder(new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("0.3333"), new BigDecimal("1.0000"), BigDecimal.ZERO, null));

            var money = orderMoneyOf(placed.orderId());
            assertThat(money.subtotal())
                    .as("the line's money is not rounded: four decimals is the grain the column carries")
                    .isEqualByComparingTo(new BigDecimal("0.3333"));
            assertThat(money.roundingAdjustment())
                    .as("the difference is stated as the adjustment instead of being absorbed silently")
                    .isEqualByComparingTo(new BigDecimal("-0.0033"));
            assertThat(money.totalAmount())
                    .as("decision 1: rounded once, at the document boundary, to the currency's minor units")
                    .isEqualByComparingTo(new BigDecimal("0.3300"));
            assertThat(money.subtotal()
                            .subtract(money.discountTotal())
                            .add(money.taxTotal())
                            .add(money.roundingAdjustment()))
                    .as("M1, exact equality: the four parts add up to the total")
                    .isEqualByComparingTo(money.totalAmount());
            assertThat(money.roundingAdjustment().abs())
                    .as("M8: the adjustment stays within one minor unit of the document's currency (0.01)")
                    .isLessThan(new BigDecimal("0.01"));

            var root = orderResponseOf(placed.orderId());
            assertThat(decimalOf(root, "totalAmount")).isEqualByComparingTo(new BigDecimal("0.3300"));
            assertThat(decimalOf(root, "roundingAdjustment")).isEqualByComparingTo(new BigDecimal("-0.0033"));
            assertThat(decimalOf(root.get("items").get(0), "subtotal"))
                    .as("the line's own money is stated at four decimals, unrounded")
                    .isEqualByComparingTo(new BigDecimal("0.3333"));
        }
    }

    // ─── T7b — the charge with many payments ────────────────────

    /**
     * The write side of ADR-0002 decision 5 (S4), and the three obligations no column can state (S6).
     *
     * <p><b>Why these are integration cases and not unit ones.</b> Every one of them asserts a row count and the
     * columns of the rows that landed — {@code sale_payment} is append-only, so a row that arrived could not be
     * taken back by anything this suite could do — and the arithmetic the request is judged by is the same
     * {@code ck_payment_tender} that {@code V83} puts behind it. A mock has neither rows nor constraints, so it
     * would let the class pass while proving only that the service threw something.</p>
     *
     * <p><b>What D-g fixes here.</b> The charge takes money in and never gives it back: the request carries no
     * direction, the server writes {@code CHARGE}, and the {@code REFUND} the schema admits waits for the unit
     * that authorizes one. That is why no case in this class names a direction.</p>
     */
    @Nested
    @DisplayName("T7b The charge with many payments — M3 exact, M4 cash-only and the tender")
    class ChargeWithManyPaymentsTests {

        /**
         * Cash and card in one sale are two rows, and the document keeps reporting a method.
         *
         * <p><b>What M3 means as exact equality, and why the tender is not the amount.</b> Each row's
         * {@code amount} is what that payment applies to the sale, so the rows add up to the total — 50 of cash and
         * 150 of card for a sale of 200 — while {@code tendered_amount} is what the customer handed over for that
         * half (a 100 note) and {@code change_amount} is what went back (50). A sum above the total would be a row
         * stating money the document does not cost.</p>
         *
         * <p><b>Why the document carries no method of its own.</b> {@code sales_orders.payment_method_id} used to be
         * written from the first {@code CHARGE} row and reported beside the payments. ADR-0002 step 6 dropped it
         * ({@code V87}) once {@code V86} populated the rows: a document with mixed tender has no "the" method, and
         * the two rows below are the only statement of how this sale was paid. The assertion is that the response
         * states no such field and both rows agree with the request.</p>
         */
        @Test
        @DisplayName("appends one CHARGE row per payment when cash and card cover the total between them")
        void charge_TwoTenders_AppendsOneRowPerPayment() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var cash = savedCashPaymentMethod();
            var card = savedCardPaymentMethod();
            assertThat(orderMoneyOf(placed.orderId()).totalAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));

            var charge = new ChargeSalesOrderRequest(List.of(
                    new SalePaymentRequest(
                            cash.getId(),
                            new BigDecimal("50.0000"),
                            new BigDecimal("100.0000"),
                            new BigDecimal("50.0000")),
                    new SalePaymentRequest(card.getId(), new BigDecimal("150.0000"), null, null)));

            var charged = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(charge))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andReturn();

            var payments = paymentsOf(placed.orderId());
            assertThat(payments).hasSize(2);
            assertThat(payments)
                    .as("one row per payment, and every one of them money that came in (D-g: no direction is asked "
                            + "for and none is refused by this endpoint either)")
                    .extracting(PaymentRow::direction)
                    .containsOnly("CHARGE");
            assertThat(payments)
                    .extracting(PaymentRow::methodId, PaymentRow::amount)
                    .containsExactlyInAnyOrder(
                            tuple(cash.getId(), new BigDecimal("50.0000")),
                            tuple(card.getId(), new BigDecimal("150.0000")));
            assertThat(payments.stream().map(PaymentRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("ADR-0002 M3: the payments apply to the sale, so they add up to its total exactly")
                    .isEqualByComparingTo(orderMoneyOf(placed.orderId()).totalAmount());

            var cashRow = payments.stream()
                    .filter(row -> row.methodId().equals(cash.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(cashRow.tenderedAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(cashRow.changeAmount()).isEqualByComparingTo(new BigDecimal("50.0000"));
            var cardRow = payments.stream()
                    .filter(row -> row.methodId().equals(card.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(cardRow.tenderedAmount())
                    .as("a card counts no cash, so its tender pair is absent rather than zero")
                    .isNull();
            assertThat(cardRow.changeAmount()).isNull();

            // The document, as the charge's own response states it: Completed, no method of its own, and both
            // payments on the sheet.
            var root = objectMapper.readTree(charged.getResponse().getContentAsString());
            assertThat(root.get("statusName").asText()).isEqualTo("Completed");
            assertThat(root.has("paymentMethodId"))
                    .as("ADR-0002 step 6 has run: V87 dropped the column, so the response states no single method "
                            + "and the two rows above are the only statement of how this sale was paid")
                    .isFalse();
            assertThat(root.get("payments").size()).isEqualTo(2);

            // And the charge is still the moment the stock moves, once, whatever the tender (decision 7).
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("8.00"));
        }

        /**
         * M3 as the brief reads it: covered <b>exactly</b>, and not "at least".
         *
         * <p><b>Why the excess is asserted beside the shortfall.</b> "At least the total" would pass both of the
         * refusals this case wants, but it would also accept 250 of payments for a sale of 200 — a row stating money
         * the document does not cost, which is the row M3 exists to make unwritable. The two directions are one
         * assertion block because one rule refuses both.</p>
         */
        @Test
        @DisplayName("refuses payments that do not add up to the total, and appends no row")
        void charge_PaymentsNotEqualToTheTotal_isRefusedAndAppendsNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var card = savedCardPaymentMethod();

            var shortfall = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    onePayment(card.getId(), new BigDecimal("150.0000"))))
                            .with(scopedSalesJwt()))
                    .andReturn();
            assertThat(shortfall.getResponse().getStatus()).isEqualTo(409);
            assertThat(shortfall.getResponse().getContentAsString())
                    .as("the refusal states both numbers: the payments' sum and the document's total")
                    .contains("its payments add up to 150.0000")
                    .contains("the document's total is 200.00")
                    .contains("add up to the document's total exactly");

            var excess = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    onePayment(card.getId(), new BigDecimal("250.0000"))))
                            .with(scopedSalesJwt()))
                    .andReturn();
            assertThat(excess.getResponse().getStatus()).isEqualTo(409);
            assertThat(excess.getResponse().getContentAsString()).contains("its payments add up to 250.0000");

            // Nothing landed, and nothing that would have to be taken back: no payment (the table is append-only),
            // the document where the case left it, and no unit booked.
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getStatus()))
                    .isEqualTo(SalesOrderState.ACTIVE);
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        /**
         * M4: change is returned only for a cash method, and the refusal names the method.
         *
         * <p><b>Why the request below is otherwise valid.</b> The tender arithmetic holds — 250 handed over, 200
         * applied, 50 back — so the only rule that can refuse it is the method's {@code is_cash}, which is the flag
         * this slice is the first reader of in {@code salesorder}. A refusal that named the amount instead of the
         * method would send an operator to recount a drawer that is already correct.</p>
         */
        @Test
        @DisplayName("refuses a tender on a method that is not cash, naming the method (M4)")
        void charge_CardPaymentStatingATender_isRefusedByName() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var card = savedCardPaymentMethod();

            var charge = new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(
                    card.getId(), new BigDecimal("200.0000"), new BigDecimal("250.0000"), new BigDecimal("50.0000"))));

            var refused = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(charge))
                            .with(scopedSalesJwt()))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(refused.getResponse().getContentAsString())
                    .as("the refusal names the method it is about, by name and by id")
                    .contains("is not a cash method")
                    .contains(card.getPaymentMethodName())
                    .contains(card.getId().toString());

            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getStatus()))
                    .isEqualTo(SalesOrderState.ACTIVE);
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        /**
         * The tender arithmetic, refused as a 400 and never as the constraint's 409.
         *
         * <p><b>Why the status is part of the assertion.</b> {@code ck_payment_tender} holds exactly this arithmetic,
         * so a service that did not judge it would still refuse the request — with the generic data-constraint 409,
         * at flush, naming neither the payment nor the number. That answer is indistinguishable from a stock
         * conflict on this endpoint, and it is the answer D-g's slice exists to put a legible refusal in front of.
         * The constraint keeps its job as the last line of defence; it is simply not what a client meets.</p>
         */
        @Test
        @DisplayName("refuses an inconsistent tender as a 400, never as the constraint's 409")
        void charge_InconsistentTender_IsRefusedAsABadRequest() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            transitionOrder(placed.orderId(), activeStatusId);
            var cash = savedCashPaymentMethod();

            // 250 handed over for a 200 payment leaves 50, and the request says 40.
            assertTenderRefusedAsBadRequest(
                    placed.orderId(),
                    new SalePaymentRequest(
                            cash.getId(),
                            new BigDecimal("200.0000"),
                            new BigDecimal("250.0000"),
                            new BigDecimal("40.0000")),
                    "the change of a cash payment is tendered - amount");

            // The pair is stated together or not at all: half of it says neither how much was counted nor how
            // much went back, and ck_payment_tender reads both columns for that reason.
            assertTenderRefusedAsBadRequest(
                    placed.orderId(),
                    new SalePaymentRequest(cash.getId(), new BigDecimal("200.0000"), new BigDecimal("250.0000"), null),
                    "together or not at all");
            assertTenderRefusedAsBadRequest(
                    placed.orderId(),
                    new SalePaymentRequest(cash.getId(), new BigDecimal("200.0000"), null, new BigDecimal("50.0000")),
                    "together or not at all");

            // Less handed over than applied to the sale is not a tender: it is a payment that has not been made.
            assertTenderRefusedAsBadRequest(
                    placed.orderId(),
                    new SalePaymentRequest(
                            cash.getId(), new BigDecimal("200.0000"), new BigDecimal("150.0000"), BigDecimal.ZERO),
                    "cannot be less than what the payment applies");

            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        /**
         * One refusal of the tender arithmetic: a 400 that names the payment, and not the constraint's envelope.
         *
         * <p>{@code violates a data constraint} is the sentence {@code GlobalExceptionHandler} answers a
         * {@code DataIntegrityViolationException} with, so its absence here is what separates "the service refused
         * this" from "the database refused it at flush" — a 400 either way would prove neither.</p>
         */
        private void assertTenderRefusedAsBadRequest(UUID orderId, SalePaymentRequest payment, String expectedFragment)
                throws Exception {
            var refused = mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ChargeSalesOrderRequest(List.of(payment))))
                            .with(scopedSalesJwt()))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains(expectedFragment)
                    .doesNotContain("violates a data constraint");
        }
    }

    // ─── T7a — the read sheet ────────────────────────────────────

    @Nested
    @DisplayName("T7a The read sheet — the breakdown is visible, and a response omits no part")
    class SalesOrderReadSurfaceTests {

        /**
         * The document's money as the API answers it, on the write path and on the read path.
         *
         * <p><b>Why the write path is the harder half.</b> The breakdown is written by the service under the
         * order's lock, so the instance a create leaves behind is not always the row — and a client that reads a
         * {@code null} part cannot tell it apart from a document with no money. The same sheet is then read back
         * from the GET, which is a row nobody has just written. Both are the same assertion block because the four
         * parts only mean something together with the total they make: M1 is exact equality, so a client adds
         * them up.</p>
         */
        @Test
        @DisplayName("the created order's response states its currency and the four parts that add up to its total")
        void createdOrder_StatesCurrencyAndTheFourParts() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));

            // The two lines T6a's breakdown case uses, because they are what makes the parts four different
            // numbers instead of one: 2 × 100.00 less a 10.00 discount, and 3 × 50.00 with none.
            var discounted = new SalesOrderItemRequest(
                    null,
                    variant.getId(),
                    new BigDecimal("2.00"),
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    null);
            var plain = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("3.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);
            var request =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(discounted, plain));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var written = objectMapper.readTree(created.getResponse().getContentAsString());
            assertSheet(written);

            assertSheet(orderResponseOf(UUID.fromString(written.get("id").asText())));
        }

        /**
         * The line's half of the sheet: its parts and the two names a reprint reads.
         *
         * <p><b>The parts come from the write path.</b> {@code subtotal} is derived by {@code V81} on the way in
         * and mapped read-only, so the instance a create leaves behind carries nothing and a mapper that read it
         * would answer {@code null} here and nowhere else — which is why the assertion is on the create's own
         * response and not only on a later GET.</p>
         *
         * <p><b>The names come from the row, and the fixture states them.</b> {@code V81} is the only writer of
         * the two snapshot columns today, so the case writes what the migration wrote for an existing line — and
         * then writes names the catalog never had, because a snapshot that tracked the catalog would not be a
         * snapshot at all (ADR-0002 decision 4).</p>
         */
        @Test
        @DisplayName("the line's response states its parts and the snapshot names, as the catalog gave them")
        void line_StatesItsPartsAndTheSnapshotNames() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var written = objectMapper.readTree(created.getResponse().getContentAsString());
            var orderId = UUID.fromString(written.get("id").asText());
            var line = written.get("items").get(0);
            var itemId = UUID.fromString(line.get("id").asText());

            assertThat(decimalOf(line, "subtotal"))
                    .as("V81 derives the line's money on the way in; the response reads the row it produced")
                    .isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(decimalOf(line, "taxRate"))
                    .as("the fixture's product declares no code, so the line keeps the schema's zero")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decimalOf(line, "taxAmount")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decimalOf(line, "finalPrice")).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(line.get("taxCode").asText()).isEqualTo("NA");

            writeSnapshotsAsV81Did(itemId, "Test Product", variant.getVariantName());
            var named = itemFromReadEndpoint(orderId, itemId);
            assertThat(named.get("productNameSnapshot").asText())
                    .as("the name the catalog gave this line's product")
                    .isEqualTo("Test Product");
            assertThat(named.get("variantNameSnapshot").asText()).isEqualTo(variant.getVariantName());

            writeSnapshotsAsV81Did(itemId, "Renamed Product", "Renamed Variant");
            var reprinted = itemFromReadEndpoint(orderId, itemId);
            assertThat(reprinted.get("productNameSnapshot").asText())
                    .as("a snapshot is the name at sale time: the reader reads the column, never the live catalog")
                    .isEqualTo("Renamed Product");
            assertThat(reprinted.get("variantNameSnapshot").asText()).isEqualTo("Renamed Variant");
        }

        /**
         * The money that came in, as a row the charge's own response states.
         *
         * <p>The charged document is the only one that has a payment, and the response that reports it is the one
         * the charge builds — not a later GET, which would let a mapper that read an empty persistence context
         * pass a case a client would fail. One {@code CHARGE} for the whole total, and an absent tender: the body
         * this case sends states a card payment and no cash in hand, which T7b reads as a tender nobody counted.</p>
         */
        @Test
        @DisplayName("the charged order's response states exactly one CHARGE payment for its total, with no tender")
        void chargedOrder_StatesTheOneChargeAsAPaymentRow() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            // A document born with its lines in the same request stays Draft, and Draft has no charge edge.
            transitionOrder(placed.orderId(), activeStatusId);
            var paymentMethodId = savedPaymentMethodId();

            var charged = mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), paymentMethodId)))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andReturn();

            var root = objectMapper.readTree(charged.getResponse().getContentAsString());
            assertThat(root.get("payments"))
                    .as("the charge's own response states the row it appended, not only the one a later GET sees")
                    .isNotNull();
            assertThat(root.get("payments").size()).isEqualTo(1);

            var payment = root.get("payments").get(0);
            assertThat(payment.get("direction").asText()).isEqualTo("CHARGE");
            assertThat(payment.get("methodId").asText()).isEqualTo(paymentMethodId.toString());
            assertThat(decimalOf(payment, "amount"))
                    .as("the payment states the document's total, under the lock that produced it")
                    .isEqualByComparingTo(decimalOf(root, "totalAmount"));
            assertThat(decimalOf(payment, "amount")).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(payment.get("tenderedAmount").isNull())
                    .as("a tender nobody counted is not invented by the server")
                    .isTrue();
            assertThat(payment.get("changeAmount").isNull()).isTrue();
            assertThat(payment.get("reference").isNull()).isTrue();
            assertThat(payment.get("actor").asText()).isEqualTo("seller");
            assertThat(payment.get("paidAt").isNull())
                    .as("the payment states when the money moved")
                    .isFalse();
            assertThat(payment.get("id").isNull())
                    .as("a void cites this identity")
                    .isFalse();
        }

        /** The five money fields plus the currency and the payment list, as one sheet. */
        private void assertSheet(JsonNode root) {
            assertThat(root.get("currency"))
                    .as("the response states the document's own currency, which V80 derives from the company")
                    .isNotNull();
            assertThat(root.get("currency").asText()).isEqualTo(TenantFixtures.SEED_CURRENCY);

            assertThat(decimalOf(root, "subtotal")).isEqualByComparingTo(new BigDecimal("350.0000"));
            assertThat(decimalOf(root, "discountTotal")).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(decimalOf(root, "taxTotal")).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(decimalOf(root, "roundingAdjustment")).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(decimalOf(root, "totalAmount")).isEqualByComparingTo(new BigDecimal("340.0000"));

            assertThat(decimalOf(root, "subtotal")
                            .subtract(decimalOf(root, "discountTotal"))
                            .add(decimalOf(root, "taxTotal"))
                            .add(decimalOf(root, "roundingAdjustment")))
                    .as("ADR-0002 M1 read back from the response: the four parts add up to the total exactly")
                    .isEqualByComparingTo(decimalOf(root, "totalAmount"));

            assertThat(root.get("payments"))
                    .as("a document nobody has charged states an empty list, never a missing one")
                    .isNotNull();
            assertThat(root.get("payments").size()).isZero();
        }
    }

    // ─── T4 — the line copies the product's code and the resolved rate ───

    /**
     * The copy ADR-0002 decision 6 states, driven through the API a sale really goes through (S1, S3 and S6 of
     * {@code odd/tasks/product-tax-code.md}).
     *
     * <p><b>What is being proved, and why the response is not enough.</b> Decision 6 says the rate is resolved
     * server-side from the {@code tax_rate} row that applies to the <i>document's</i> date and is <b>copied</b>
     * onto the line at write time, so a later rate change never restates a past sale. The line's
     * {@code tax_amount} is then derived by {@code V90}'s trigger from that stored rate, and the document's
     * {@code tax_total} from the lines. A mapper that invented a number and a writer that stored one are
     * indistinguishable on a response alone, so each case reads the row back too.</p>
     *
     * <p><b>Before this slice the write was the silent zero, not a refusal.</b> {@code tax_code} and
     * {@code tax_rate} were mapped {@code insertable = false, updatable = false}, so nothing in Java ever stated
     * them: every line kept the column defaults ({@code NA}, {@code 0}) and {@code tax_amount} stayed zero for a
     * product that declares {@code IVA}. That is the failure D-a was decided about, and it is what makes the
     * first case below fail rather than refuse.</p>
     *
     * <p><b>Why every case clears the rates first.</b> {@code tax_rate} is a policy-armed tenant table and the
     * container is shared by the whole JVM, so a rate another case configured would decide this one's answer. A
     * case that configures nothing is therefore a case the company states no rate for, which is the state every
     * environment is in today.</p>
     */
    @Nested
    @DisplayName("T4 The line copies the product's tax code and the rate the document's date resolves")
    class LineTaxCopyTests {

        @BeforeEach
        void noRateIsConfigured() {
            clearTaxRates();
        }

        @AfterEach
        void leaveNoRateBehind() {
            clearTaxRates();
        }

        @Test
        @DisplayName("an IVA product's line carries its code, the 16% rate and the tax reaches the total")
        void ivaLine_copiesTheCodeAndTheRateAndTheTaxReachesTheTotal() throws Exception {
            // Effective a year before the document's date, which is today: the rate applies, and it is the only
            // row for the code, so nothing here depends on which of two rows is "the latest".
            configureTaxRate("IVA", "0.16", LocalDate.now().minusYears(1));
            var variant = createVariantWithTaxCode("IVA", new BigDecimal("10.00"));

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "2.00")));
            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(created.getResponse().getContentAsString());
            var orderId = UUID.fromString(root.get("id").asText());
            var item = root.get("items").get(0);

            assertThat(item.get("taxCode").asText())
                    .as("the code the sold variant's product declares, copied verbatim onto the line")
                    .isEqualTo("IVA");
            assertThat(decimalOf(item, "taxRate"))
                    .as("the rate the document's date resolves, stored on the line as the snapshot decision 6 "
                            + "describes")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            assertThat(decimalOf(item, "taxAmount"))
                    .as("V90 derives round((subtotal - discount) * stored rate, 4) = round(200 * 0.16, 4)")
                    .isEqualByComparingTo(new BigDecimal("32.0000"));
            assertThat(decimalOf(item, "finalPrice"))
                    .as("the line's total is its money plus the tax the row states")
                    .isEqualByComparingTo(new BigDecimal("232.0000"));

            // And the document's own parts include it: 200 of goods, 32 of tax, 232 to pay. Reading the
            // response alone would leave open whether the row says the same thing.
            assertThat(decimalOf(root, "subtotal")).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(decimalOf(root, "taxTotal")).isEqualByComparingTo(new BigDecimal("32.0000"));
            assertThat(decimalOf(root, "totalAmount")).isEqualByComparingTo(new BigDecimal("232.0000"));

            var money = orderMoneyOf(orderId);
            assertThat(money.taxTotal())
                    .as("tax_total is the sum of the lines' tax_amount, which is why the line had to carry a "
                            + "rate at all")
                    .isEqualByComparingTo(new BigDecimal("32.0000"));
            assertThat(money.totalAmount()).isEqualByComparingTo(new BigDecimal("232.0000"));
        }

        @Test
        @DisplayName("a product that declares NA keeps the zeros the column has always carried")
        void naProduct_keepsTheZeros() throws Exception {
            // The regression half of the copy: `NA` is zero by definition (D-a), so it resolves to 0 with no
            // rate row — the state every line in the tree is in before this slice.
            var variant = createTestVariant(new BigDecimal("10.00"));

            var placed = createOrder(line(null, variant.getId(), "2.00"));
            var row = itemFromReadEndpoint(placed.orderId(), placed.itemId(variant.getId()));

            assertThat(row.get("taxCode").asText()).isEqualTo("NA");
            assertThat(decimalOf(row, "taxRate")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decimalOf(row, "taxAmount")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decimalOf(row, "finalPrice"))
                    .as("nothing to add: the line's total is its own money")
                    .isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(orderMoneyOf(placed.orderId()).taxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("TASA_0 and EXENTO copy their own code with a zero rate and need no rate row (D-a)")
        void zeroByDefinitionCodes_copyTheCodeWithNoRateRow() throws Exception {
            for (var code : List.of("TASA_0", "EXENTO")) {
                var variant = createVariantWithTaxCode(code, new BigDecimal("10.00"));

                var placed = createOrder(line(null, variant.getId(), "2.00"));
                var row = itemFromReadEndpoint(placed.orderId(), placed.itemId(variant.getId()));

                assertThat(row.get("taxCode").asText())
                        .as("the code travels even when the rate is zero: Tasa 0% and Exento are the same zero "
                                + "with two meanings, and only the code tells them apart")
                        .isEqualTo(code);
                assertThat(decimalOf(row, "taxRate"))
                        .as("the code " + code + " is zero by definition, so no tax_rate row is needed and none "
                                + "was configured")
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(decimalOf(row, "taxAmount")).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(decimalOf(row, "finalPrice")).isEqualByComparingTo(new BigDecimal("200.0000"));
            }
        }

        @Test
        @DisplayName("an IVA product with no rate on the document's date is refused, naming it, and stores nothing")
        void ivaWithNoRate_isRefusedAndStoresNothing() throws Exception {
            var variant = createVariantWithTaxCode("IVA", new BigDecimal("10.00"));

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "2.00")));
            var refused = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andReturn();

            assertThat(refused.getResponse().getStatus())
                    .as("a code the company states no rate for is the client's to fix, and the ADR already says "
                            + "so: a missing rate is never a silent zero (decision 6, D-a)")
                    .isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .as("the refusal names the code and the document's date, the two facts whoever configures "
                            + "the rate needs")
                    .contains("IVA")
                    .contains(LocalDate.now().toString())
                    .doesNotContain("violates a data constraint");

            assertThat(inTransaction(() -> salesOrderRepository.count()))
                    .as("a refused line refuses the document: no order row survives the rollback")
                    .isZero();
            assertThat(inTransaction(() -> itemRepository.count()))
                    .as("and no line row either — the copy runs before the line is written, not after")
                    .isZero();
        }

        @Test
        @DisplayName("an edit restates the tax from the stored rate, never from a rate resolved again")
        void anEditRestatesTheTaxFromTheStoredRate() throws Exception {
            configureTaxRate("IVA", "0.16", LocalDate.now().minusYears(1));
            var variant = createVariantWithTaxCode("IVA", new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));

            // A second, later rate for the same code, effective before the document's date — so a resolution
            // run *now* would answer 8%. A write path that resolved again on an edit would charge 8% of 300
            // and quietly rewrite a sale the customer already saw at 16%.
            configureTaxRate("IVA", "0.08", LocalDate.now().minusDays(1));

            var itemId = placed.itemId(variant.getId());
            updateOrderItems(
                    placed.orderId(),
                    new SalesOrderItemRequest(
                            itemId,
                            variant.getId(),
                            new BigDecimal("3.00"),
                            new BigDecimal("100.00"),
                            BigDecimal.ZERO,
                            null));

            var row = itemFromReadEndpoint(placed.orderId(), itemId);

            assertThat(decimalOf(row, "taxRate"))
                    .as("the rate is the one the line was sold at: an edit of the quantity does not move the "
                            + "document's date and must not pick up a rate stated afterwards")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            assertThat(decimalOf(row, "taxAmount"))
                    .as("the derivation restates the amount from the stored rate: 16% of 300, not 8%")
                    .isEqualByComparingTo(new BigDecimal("48.0000"));
            assertThat(decimalOf(row, "finalPrice")).isEqualByComparingTo(new BigDecimal("348.0000"));
        }

        /**
         * A rate row for this class's company, stated the way a reviewed migration states one: {@code tax_rate}
         * has no writer in the application, by decision (ADR-0002 decision 6 and {@code TaxRate}'s javadoc), so
         * the fixture is the writer here exactly as it is for a line's name snapshots.
         */
        private void configureTaxRate(String taxCode, String rate, LocalDate effectiveFrom) {
            inTransaction(() -> jdbcTemplate.update(
                    "INSERT INTO tax_rate (id, company_id, tax_code, rate, effective_from, enabled) "
                            + "VALUES (?, ?, ?, ?, ?, true)",
                    UUID.randomUUID(),
                    companyId,
                    taxCode,
                    new BigDecimal(rate),
                    java.sql.Date.valueOf(effectiveFrom)));
        }

        /** No rate at all for this class's company: the state every environment is in today. */
        private void clearTaxRates() {
            inTransaction(() -> jdbcTemplate.update("DELETE FROM tax_rate WHERE company_id = ?", companyId));
        }

        /**
         * A product that declares {@code taxCode}, and one stocked variant of it.
         *
         * <p>The code is written through {@code Product.Builder} — the column {@code V89} added — so the fixture
         * states the article the sale will read instead of patching a line: the whole point of S2 is that the
         * code belongs to the product, and a line has no way to state one.</p>
         */
        private ProductVariant createVariantWithTaxCode(String taxCode, BigDecimal stock) {
            return TenantFixtures.inTenant(companyId, () -> {
                var product = productRepository.save(Product.builder()
                        .sku("SKU-TAX-" + UUID.randomUUID().toString().substring(0, 8))
                        .name("Producto " + taxCode)
                        .taxCode(taxCode)
                        .company(companyRepository.findById(companyId).orElseThrow())
                        .enabled(true)
                        .build());
                var variant = new ProductVariant();
                variant.setProductId(product.getId());
                variant.setCompanyId(companyId);
                variant.setMeasureUnitId(pieceUnitId());
                variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
                variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
                variant.setEnabled(true);
                var saved = productVariantRepository.save(variant);
                saveStoreStock(saved.getId(), stock, new BigDecimal("60.00"));
                return saved;
            });
        }
    }

    // ─── 5.11 Store reassignment refused while items hold stock ──

    @Nested
    @DisplayName("5.11 Store reassignment — refused while the order has items")
    class StoreReassignmentTests {

        @Test
        @DisplayName("should refuse moving an order to another store and leave the original store stock untouched")
        void updateOrderStore_WithActiveItems_RefusedAndOriginalStockUnchanged() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            var created = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = UUID.fromString(objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("id")
                    .asText());

            // The order was never charged, so no store holds anything for it: the fixture's 10 is intact and the
            // document's two units are still on the shelf.
            assertNothingBooked(variant.getId(), "10.00");

            var zone = TenantFixtures.inTenant(
                            companyId,
                            () -> companyStoreRepository
                                    .findById(companyStoreId)
                                    .orElseThrow())
                    .getCompanyZone();
            var otherStore = TenantFixtures.inTenant(
                    companyId,
                    () -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Other Store "
                                    + UUID.randomUUID().toString().substring(0, 8))
                            .enabled(true)
                            .build()));

            var moveRequest = new SalesOrderRequest(customerId, otherStore.getId(), shiftId, "user123", null);

            // The caller is authorized for BOTH stores (a multi-store manager), so the store guard
            // passes and the request reaches the reassignment rule this test is about: the 409.
            var refused = mockMvc.perform(put("/api/sales-orders/{id}", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveRequest))
                            .with(scopedSalesJwt(otherStore.getId())))
                    .andExpect(status().isConflict())
                    .andReturn();

            assertThat(refused.getResponse().getContentAsString()).contains("cannot be reassigned from company store");

            // Nothing moved in either store: the document holds no stock yet, so what this case pins is the
            // document's rule — a reassignment with active lines stays refused — and not the store's. Narrowing it
            // is a decision of its own (recorded in odd/tasks/money-first-class.md, T6b log), because the charge
            // would now simply book at whichever store the document names.
            assertNothingBooked(variant.getId(), "10.00");
            assertThat(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(
                            variant.getId(), otherStore.getId()))
                    .isEmpty();
        }
    }

    // ─── 5.12 Create order without a store claim → 403 ───────────

    @Nested
    @DisplayName("5.12 Create order without a store claim — 403")
    class StoreClaimDenialTests {

        @Test
        @DisplayName("should deny an lc-sales principal with no store claim and persist nothing")
        void createOrderWithoutStoreClaim_Returns403() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            // The role passes @PreAuthorize and ScopeLevel.STORE grants it a store scope, but the
            // token carries no store claim, so the company -> country -> region -> zone -> store
            // check in CurrentUserContext.verifyCompanyStoreAccess throws, GlobalExceptionHandler
            // maps it to 403 and the write phase never starts.
            //
            // The company claim stays: /api/sales-orders names no company in its path, so that claim is
            // the tenant the policy reads, and without it the order's store is hidden and the write is
            // refused 404 before the store check this case is about ever runs.
            var noStoreClaim = jwt().authorities(ROLE_LC_SALES)
                    .jwt(builder ->
                            builder.claim("preferred_username", "seller").claim("company_id", companyId.toString()));
            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(noStoreClaim))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // @BeforeEach deletes all orders, so an empty table proves nothing was persisted.
            assertThat(salesOrderRepository.findAll()).isEmpty();
            // The denied request must not have booked stock either, on either side.
            assertNothingBooked(variant.getId(), "100.00");
        }
    }

    // ─── 5.13 Deduction across two locations — exact reversal ───

    @Nested
    @DisplayName("5.13 The charge spills across locations — the reversal restores each exactly")
    class SpilloverReversalTests {

        /**
         * The spill happens at the charge, and the reversal happens when the charged document is deleted.
         *
         * <p><b>Why the delete is the order's and not the line's.</b> The scenario the case is about — a sale that
         * drains the priority shelf and spills onto the second one, then gives each shelf back exactly what it took
         * — is unchanged. What changed is which call reverses: the line is {@code ADDED} after the charge and the
         * item endpoint refuses a line of a charged document, while the order delete is the path that reverses a
         * document whose charge booked something ({@code SalesOrderService#deleteSalesOrder}). Using it keeps the
         * whole scenario and tests the reversal where a reversal exists.</p>
         */
        @Test
        @DisplayName(
                "should spill past the priority location at the charge and reverse each location exactly on delete")
        void deductSpillsAndDeleteRestoresExactLocations() throws Exception {
            var variant = createTestVariant(new BigDecimal("13.00"));
            // Priority sales location holds 3, the second location holds 10, the aggregate holds 13.
            seedSplitBalances(variant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var placed = createOrder(line(null, variant.getId(), "5.00"));
            var itemId = placed.itemId(variant.getId());

            // The split is exactly as the fixture left it: the create books nothing.
            assertNothingBooked(variant.getId(), "13.00");
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));

            chargeFromItsCurrentState(placed.orderId());

            // The priority location drains to zero and the remainder comes from the second one.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("8.00"));

            var sales = movementsOfType(variant.getId(), StockLedgerWriter.SALE);
            assertThat(sales).hasSize(2);
            assertThat(sales)
                    .extracting(LedgerMovement::storeLocationId, LedgerMovement::quantity)
                    .containsExactlyInAnyOrder(
                            tuple(salesLocationId, new BigDecimal("3.0000")),
                            tuple(secondaryLocationId, new BigDecimal("2.0000")));
            assertThat(sales).allSatisfy(m -> assertThat(m.sourceId()).isEqualTo(itemId));

            // Deleting the charged document reverses the ledger, so each location gets back exactly what it gave
            // — never a re-run of the priority allocation, which would credit all 5 to the first one.
            mockMvc.perform(delete("/api/sales-orders/{id}", placed.orderId()).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));

            var reversals = movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL);
            assertThat(reversals).hasSize(2);
            assertThat(reversals)
                    .extracting(LedgerMovement::storeLocationId, LedgerMovement::quantity)
                    .containsExactlyInAnyOrder(
                            tuple(salesLocationId, new BigDecimal("3.0000")),
                            tuple(secondaryLocationId, new BigDecimal("2.0000")));
        }
    }

    // ─── 5.14 Cancelling a line books nothing, and the charge skips it ───

    @Nested
    @DisplayName("5.14 Cancelling a line — nothing is restored, and the charge skips it")
    class CancelledItemRestoreTests {

        /**
         * A cancelled line gives nothing back, and the document it belongs to books nothing for it.
         *
         * <p><b>What the case was.</b> W3-D4: cancelling a line restored it exactly as deleting it did, so the case
         * asserted the reversal and the balances coming back to 100. Under ADR-0002 decision 7 nothing is deducted
         * before the charge, so the cancellation has nothing to restore — and the charge excludes a cancelled line
         * from its batch. The scenario is kept with its one line, and the assertion moves to the moment the stock
         * would have moved: the charge books no unit at all.</p>
         */
        @Test
        @DisplayName("should book nothing when the line is cancelled, and book nothing for it at the charge")
        void cancelItem_BooksNothingAndTheChargeSkipsIt() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            cancelItem(placed.orderId(), itemId);

            // No reversal, because there was no movement to reverse.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();

            // The document is still chargeable — a cancelled line keeps its money, which is the accounting
            // question T4 owes and not this unit's — and the charge books nothing for it: the line is not live, so
            // the batch it builds is empty and the engine is never asked.
            chargeFromItsCurrentState(placed.orderId());
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(paymentsOf(placed.orderId())).hasSize(1);
        }

        /**
         * A cancelled line later removed through the order PUT is not booked twice — it is not booked at all, and
         * only the retained line is.
         *
         * <p><b>What the case was.</b> It guarded the double reversal: the cancel reversed the line, and the PUT that
         * soft-deleted it afterwards had to not reverse it a second time. There is no first reversal now, so the
         * guard's subject becomes the charge: a line cancelled and then soft-deleted holds nothing, so the document
         * books only the line that survived both edits.</p>
         */
        @Test
        @DisplayName("should book nothing for a cancelled line later soft-deleted, and charge only the retained line")
        void cancelItemThenDelete_BooksNothingForTheCancelledLine() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("50.00"));
            // Two lines: the cancelled one is the subject, the retained one keeps the order-level
            // PUT's items non-empty so its diff actually runs.
            var placed = createOrder(line(null, variant.getId(), "4.00"), line(null, variantB.getId(), "3.00"));
            var itemId = placed.itemId(variant.getId());
            var retainedItemId = placed.itemId(variantB.getId());

            cancelItem(placed.orderId(), itemId);
            assertNothingBooked(variant.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "50.00");

            // The item-level DELETE is refused (F-7): the line rule does not depend on stock, and a cancelled line
            // has no edge back into the document's live set.
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict());
            assertThat(itemEnabled(itemId)).isTrue();
            assertNothingBooked(variant.getId(), "100.00");

            // The supported route is the order-level PUT (W3-D13): it soft-deletes the cancelled line, and books
            // nothing, because a cancelled line contributes no stock work.
            updateOrderItems(placed.orderId(), line(retainedItemId, variantB.getId(), "3.00"));

            assertThat(itemEnabled(itemId)).isFalse();
            assertNothingBooked(variant.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "50.00");

            chargeFromItsCurrentState(placed.orderId());

            // The cancelled and deleted line is not booked at all, and the retained one is booked once.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(stockPositionOf(variantB.getId())).isEqualByComparingTo(new BigDecimal("47.00"));
            assertThat(movementsOfType(variantB.getId(), StockLedgerWriter.SALE))
                    .hasSize(1);
        }

        /**
         * A second delete of a soft-deleted line is refused, and neither delete books anything.
         *
         * <p><b>What the case was.</b> "Second delete refused and no second reversal": the first delete reversed the
         * line and the second had to be refused rather than reversing it again. The refusal is unchanged and is
         * still the line rule's; what moved is the reversal count, which is now zero because there is no booking to
         * reverse. The "exactly once" half lives in {@link DeleteOrderStockRestoreTests}, where the one reversal
         * there is can actually be observed.</p>
         */
        @Test
        @DisplayName("should refuse a second delete of a soft-deleted line, and book nothing for either")
        void deleteItemTwice_SecondIsRefusedAndNothingIsBooked() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            assertThat(itemEnabled(itemId)).isFalse();
            assertNothingBooked(variant.getId(), "100.00");

            // The line is already soft-deleted, so the second delete is refused instead of answering
            // 204 for a write the line rule refuses everywhere else (F-7).
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict());

            assertThat(itemEnabled(itemId)).isFalse();
            assertNothingBooked(variant.getId(), "100.00");

            // And the document left with no line has nothing to charge, so the deleted lines keep contributing
            // neither stock nor money.
            transitionOrder(placed.orderId(), activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            // The body states an amount for the lines the document no longer has: the refusal is
                            // the document's — nothing to pay — and not the amount's.
                            .content(objectMapper.writeValueAsString(
                                    onePayment(savedPaymentMethodId(), new BigDecimal("400.0000"))))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString("there is nothing to pay")));
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        /**
         * Re-enabling a cancelled line books nothing, and the charge skips it whatever quantity it names.
         *
         * <p><b>What the case was.</b> W3-D11: re-enabling a cancelled line with a changed quantity must not
         * re-deduct it, and the case was built so that a wrongly-treated cancelled line would be caught by the
         * delta. Under ADR-0002 decision 7 the sequencing is different and the claim is easier to state and harder
         * to fake: the charge skips a line in {@code CANCELLED}, so the quantity it names never reaches the engine.
         * </p>
         */
        @Test
        @DisplayName("should not book a cancelled line re-enabled with a changed quantity, at the charge either")
        void cancelItemThenReEnable_DoesNotHoldStock() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelItem(placed.orderId(), itemId);
            assertNothingBooked(variant.getId(), "100.00");

            // Cancelled is terminal in the item status machine, so re-enabling the flag revives no hold. The
            // quantity is deliberately different from the original 4.00: the claim is that a cancelled line is
            // skipped by the charge, and a line that never reaches the engine can neither be deducted nor
            // restored.
            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "5.00"));

            // The claim was "the row's status_id is Cancelled"; the column is gone, so it is asked twice: the
            // row's state is the constant, and the pair a screen reads is the catalog row of that code.
            assertThat(itemRepository.findById(itemId).orElseThrow().getStatus())
                    .isEqualTo(SalesOrderItemState.CANCELLED);
            assertThat(inTransaction(() -> statusRepository
                            .findByTypeNameAndCode("SALES_ORDER_ITEM", SalesOrderItemState.CANCELLED.name())
                            .orElseThrow()
                            .getId()))
                    .isEqualTo(cancelledItemStatusId);
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            // The charge is where the cancelled line would finally be sold, and it is skipped: no movement, and the
            // shelf never moved.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }
    }

    // ─── 5.15 PUT /{id} item diff — the diff is a document edit ──

    /**
     * The order-level line diff, asked about the stock at the moment the stock moves.
     *
     * <p><b>What the six cases were.</b> They were the ledger's proof of the delta arithmetic: a line added sold in
     * full, a grown line sold by its difference, a shrunk one reversed and re-sold, a dropped one reversed, a
     * variant change reversed-then-sold, and several changes in one batch. Under ADR-0002 decision 7 the diff books
     * nothing and the charge books the document's final state, so each case keeps its own diff — the edits are the
     * scenario and the assertions on the resulting line set are unchanged — and moves its ledger expectations to
     * the charge. The reversal half of the shrunk and dropped cases is now observable only on a deleted charged
     * document ({@code DeleteOrderStockRestoreTests}), which is stated where it applies.</p>
     */
    @Nested
    @DisplayName("5.15 Updating an order's line set — the diff edits, the charge books")
    class UpdateOrderItemDiffTests {

        @Test
        @DisplayName("should sell a newly added line, referencing its generated id, when the document is charged")
        void updateOrder_LineAdded_SellsTheNewLine() throws Exception {
            var variantA = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("50.00"));

            var placed = createOrder(line(null, variantA.getId(), "2.00"));
            var retained = line(placed.itemId(variantA.getId()), variantA.getId(), "2.00");

            var after = updateOrderItems(placed.orderId(), retained, line(null, variantB.getId(), "3.00"));

            // The diff added a line and moved nothing: both shelves still hold their full stock.
            assertNothingBooked(variantA.getId(), "100.00");
            assertNothingBooked(variantB.getId(), "50.00");

            chargeFromItsCurrentState(placed.orderId());

            // Both lines are sold once each: the kept one owes nothing extra and the added one is sold in full
            // from its priority location — in the one batch the charge sends.
            assertThat(stockPositionOf(variantA.getId())).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(stockPositionOf(variantB.getId())).isEqualByComparingTo(new BigDecimal("47.00"));
            assertThat(locationStockOf(variantB.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("47.00"));

            var sales = movementsOfType(variantB.getId(), StockLedgerWriter.SALE);
            assertThat(sales).hasSize(1);
            assertThat(sales.get(0).quantity()).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(sales.get(0).sourceType()).isEqualTo("SALES_ORDER_ITEM");
            assertThat(sales.get(0).sourceId()).isEqualTo(after.itemId(variantB.getId()));
            assertThat(movementsOfType(variantA.getId(), StockLedgerWriter.SALE))
                    .hasSize(1);
        }

        @Test
        @DisplayName("should book the grown line's stated quantity, not its difference")
        void updateOrder_QuantityGrown_BooksTheStatedQuantity() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "100.00");

            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "5.00"));
            assertNothingBooked(variant.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            // One sale of five: there is no earlier booking for a difference to be taken from, and no reversal
            // to write for a line that never held anything.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(movementsOf(variant.getId()))
                    .extracting(LedgerMovement::movementType, LedgerMovement::sourceId, LedgerMovement::quantity)
                    .containsExactly(tuple(StockLedgerWriter.SALE, itemId, new BigDecimal("5.0000")));
            assertThat(movementsOfType(variant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
        }

        @Test
        @DisplayName("should book the shrunk line's smaller quantity, and give nothing back")
        void updateOrder_QuantityShrunk_BooksTheSmallerQuantity() throws Exception {
            var variant = createTestVariant(new BigDecimal("13.00"));
            seedSplitBalances(variant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var placed = createOrder(line(null, variant.getId(), "5.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "13.00");

            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "2.00"));
            assertNothingBooked(variant.getId(), "13.00");

            chargeFromItsCurrentState(placed.orderId());

            // The charge sells the two units the line states, from the priority shelf: 3 - 2 and 10 untouched. The
            // reversal that the old delta pair (reverse 5, re-sell 2) used to write does not exist, because the
            // five were never taken.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("11.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(movementsOf(variant.getId()))
                    .extracting(LedgerMovement::movementType, LedgerMovement::storeLocationId, LedgerMovement::quantity)
                    .containsExactly(tuple(StockLedgerWriter.SALE, salesLocationId, new BigDecimal("2.0000")));
        }

        @Test
        @DisplayName("should book nothing for a deleted line and sell only the retained one")
        void updateOrder_LineDeleted_BooksNothingForTheDeletedLine() throws Exception {
            var variantA = createTestVariant(new BigDecimal("13.00"));
            seedSplitBalances(variantA.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));
            var variantB = createTestVariantB(new BigDecimal("10.00"));

            var placed = createOrder(line(null, variantA.getId(), "5.00"), line(null, variantB.getId(), "1.00"));
            var itemAId = placed.itemId(variantA.getId());
            var itemBId = placed.itemId(variantB.getId());
            assertNothingBooked(variantA.getId(), "13.00");
            assertNothingBooked(variantB.getId(), "10.00");

            // Omitting A from the request deletes it; B keeps its quantity.
            updateOrderItems(placed.orderId(), line(itemBId, variantB.getId(), "1.00"));

            assertThat(itemEnabled(itemAId)).isFalse();
            assertNothingBooked(variantA.getId(), "13.00");
            assertNothingBooked(variantB.getId(), "10.00");

            chargeFromItsCurrentState(placed.orderId());

            // The document's surviving line is the only thing sold: the deleted line's variant has no movement at
            // all — not a SALE and not a reversal of one — and its two shelves are exactly as they were.
            assertThat(stockPositionOf(variantA.getId())).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(variantA.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(movementsOf(variantA.getId())).isEmpty();
            assertThat(stockPositionOf(variantB.getId())).isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(movementsOfType(variantB.getId(), StockLedgerWriter.SALE))
                    .hasSize(1);
        }

        @Test
        @DisplayName("should sell only the variant the line states, and take nothing from the old one")
        void updateOrder_VariantChanged_SellsOnlyTheVariantTheLineStates() throws Exception {
            var first = createTestVariant(new BigDecimal("13.00"));
            var second = createTestVariant(new BigDecimal("13.00"));
            // Keep the sold variant first in the engine's (store, variant) ordering so the composite
            // is characterized without depending on the generated ids.
            var oldVariant = first.getId().compareTo(second.getId()) < 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "5.00"));
            var itemId = placed.itemId(oldVariant.getId());
            assertNothingBooked(oldVariant.getId(), "13.00");

            updateOrderItems(placed.orderId(), line(itemId, newVariant.getId(), "2.00"));

            assertNothingBooked(oldVariant.getId(), "13.00");
            assertNothingBooked(newVariant.getId(), "13.00");

            chargeFromItsCurrentState(placed.orderId());

            // The old variant keeps its split, because nothing was ever taken from it: the reversal the old
            // arrangement wrote first is gone, and the charge sells the variant the line names.
            assertThat(stockPositionOf(oldVariant.getId())).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(movementsOf(oldVariant.getId())).isEmpty();
            assertThat(stockPositionOf(newVariant.getId())).isEqualByComparingTo(new BigDecimal("11.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("11.00"));
            assertThat(movementsOfType(newVariant.getId(), StockLedgerWriter.SALE))
                    .extracting(LedgerMovement::sourceId, LedgerMovement::quantity)
                    .containsExactly(tuple(itemId, new BigDecimal("2.0000")));
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(newVariant.getId());
        }

        @Test
        @DisplayName("should sell every new variant in one batch, whatever order the lines name them in")
        void updateOrder_MultipleVariantChanges_AllSellTheNewVariant() throws Exception {
            var variants = List.of(
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")));
            var byId = variants.stream()
                    .sorted(Comparator.comparing(ProductVariant::getId))
                    .toList();
            // Two new variants that sort before both old ones. The engine sorts a batch by (store, variant), which
            // used to matter because a reversal and a deduction of the same line shared one reference; the charge
            // sends only deductions, so what the case keeps is the outcome: four positions, two of them sold.
            var newOne = byId.get(0);
            var newTwo = byId.get(1);
            var oldOne = byId.get(2);
            var oldTwo = byId.get(3);

            var placed = createOrder(line(null, oldOne.getId(), "2.00"), line(null, oldTwo.getId(), "2.00"));
            var firstItem = placed.itemId(oldOne.getId());
            var secondItem = placed.itemId(oldTwo.getId());

            updateOrderItems(
                    placed.orderId(),
                    line(firstItem, newOne.getId(), "2.00"),
                    line(secondItem, newTwo.getId(), "2.00"));

            assertNothingBooked(oldOne.getId(), "100.00");
            assertNothingBooked(oldTwo.getId(), "100.00");
            assertNothingBooked(newOne.getId(), "100.00");
            assertNothingBooked(newTwo.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            assertThat(stockPositionOf(oldOne.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(stockPositionOf(oldTwo.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(oldOne.getId())).isEmpty();
            assertThat(movementsOf(oldTwo.getId())).isEmpty();
            assertThat(stockPositionOf(newOne.getId())).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(stockPositionOf(newTwo.getId())).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(movementsOfType(newOne.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(movementsOfType(newOne.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
            assertThat(movementsOfType(newTwo.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(movementsOfType(newTwo.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
        }
    }

    // ─── 5.16 /{id}/items/{itemId} variant change — the charge sells the current variant

    /**
     * Changing one line's variant, asked about the stock.
     *
     * <p><b>What the three cases were.</b> They proved that a variant change reversed the old variant exactly and
     * sold the new one, including when the new variant's id sorted first (so a wrongly ordered batch would run the
     * deduction before the reversal) and across successive changes. Under ADR-0002 decision 7 nothing is reversed,
     * because nothing was taken: the charge sells the variant the line names when it runs, which is one deduction in
     * a batch that carries no reversal at all. The scenarios are kept — the adversarial ordering included — and
     * each states the same outcome on the charge.</p>
     */
    @Nested
    @DisplayName("5.16 Changing one line's variant — the charge sells the one it names")
    class UpdateItemVariantChangeTests {

        @Test
        @DisplayName("should book nothing when the line's variant changes, and sell the new one at the charge")
        void updateItem_VariantChange_BooksNothingAndTheChargeSellsTheNewVariant() throws Exception {
            var first = createTestVariant(new BigDecimal("10.00"));
            var second = createTestVariant(new BigDecimal("10.00"));
            var oldVariant = first.getId().compareTo(second.getId()) < 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("1.00"), new BigDecimal("9.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "4.00"));
            var itemId = placed.itemId(oldVariant.getId());
            // The split is intact: no sale spilled from it, because none happened.
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("9.00"));
            assertNothingBooked(oldVariant.getId(), "10.00");

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(line(itemId, newVariant.getId(), "3.00")))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(3.00));

            assertNothingBooked(oldVariant.getId(), "10.00");
            assertNothingBooked(newVariant.getId(), "10.00");

            chargeFromItsCurrentState(placed.orderId());

            // The old variant is untouched — there was no hold to give back and no reversal to write — and the
            // charge sells the variant the line names.
            assertThat(stockPositionOf(oldVariant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(movementsOf(oldVariant.getId())).isEmpty();
            assertThat(stockPositionOf(newVariant.getId())).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(movementsOfType(newVariant.getId(), StockLedgerWriter.SALE))
                    .extracting(LedgerMovement::sourceId, LedgerMovement::quantity)
                    .containsExactly(tuple(itemId, new BigDecimal("3.0000")));
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(newVariant.getId());
        }

        @Test
        @DisplayName("should still sell the new variant when its id sorts before the old one")
        void updateItem_VariantChange_NewVariantSortsFirst_StillSellsNew() throws Exception {
            var first = createTestVariant(new BigDecimal("10.00"));
            var second = createTestVariant(new BigDecimal("10.00"));
            // The adversarial but reachable ordering the case was built on: the new variant sorts before the old
            // one, so a batch that paired a reversal with a deduction of one line would run them in an order that
            // decides the outcome. The charge sends one deduction per line and no reversal, so the ordering cannot
            // decide anything — and the case still pins the outcome instead of assuming it.
            var oldVariant = first.getId().compareTo(second.getId()) > 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("1.00"), new BigDecimal("9.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "4.00"));
            var itemId = placed.itemId(oldVariant.getId());
            assertNothingBooked(oldVariant.getId(), "10.00");

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(line(itemId, newVariant.getId(), "3.00")))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk());

            chargeFromItsCurrentState(placed.orderId());

            assertThat(stockPositionOf(oldVariant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(stockPositionOf(newVariant.getId())).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(movementsOfType(newVariant.getId(), StockLedgerWriter.SALE))
                    .hasSize(1);
            assertThat(movementsOfType(newVariant.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
        }

        @Test
        @DisplayName("should keep selling the current variant across successive changes A→B→C→A")
        void updateItem_SuccessiveVariantChanges_AlwaysSellsCurrent() throws Exception {
            var variants = List.of(
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")));
            var byId = variants.stream()
                    .sorted(Comparator.comparing(ProductVariant::getId))
                    .toList();
            // Start on the latest-sorting variant so each of the first two changes targets one that
            // sorts earlier — the ordering that used to reverse a fresh sale instead of the old one.
            var low = byId.get(0);
            var mid = byId.get(1);
            var high = byId.get(2);

            var placed = createOrder(line(null, high.getId(), "4.00"));
            var itemId = placed.itemId(high.getId());

            updateOrderItems(placed.orderId(), line(itemId, mid.getId(), "4.00"));
            updateOrderItems(placed.orderId(), line(itemId, low.getId(), "4.00"));
            updateOrderItems(placed.orderId(), line(itemId, high.getId(), "4.00"));

            // Three changes, three line writes, no movement: every variant still holds its hundred.
            assertNothingBooked(high.getId(), "100.00");
            assertNothingBooked(mid.getId(), "100.00");
            assertNothingBooked(low.getId(), "100.00");

            chargeFromItsCurrentState(placed.orderId());

            // The document ends on the variant it started on, and that is the one and only one sold.
            assertThat(stockPositionOf(high.getId())).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(stockPositionOf(mid.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(stockPositionOf(low.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(mid.getId())).isEmpty();
            assertThat(movementsOf(low.getId())).isEmpty();
            assertThat(movementsOfType(high.getId(), StockLedgerWriter.SALE)).hasSize(1);
            assertThat(movementsOfType(high.getId(), StockLedgerWriter.SALE_REVERSAL))
                    .isEmpty();
        }
    }

    // ─── 5.17 Update beyond stock — accepted, refused at the charge ─

    /**
     * A quantity the store cannot cover, asked of the two moments it can be asked at.
     *
     * <p><b>What the two cases were.</b> They asserted the 409 <b>and</b> that the failed deduction rolled the
     * update back — the line kept its old quantity, the old variant's reversal rolled back with the new variant's
     * refusal. Under ADR-0002 decision 7 an edit checks no stock, so the update succeeds and persists what the
     * request states; the refusal is the charge's, and it rolls back the charge instead of the edit. Both halves are
     * asserted, in the order they now happen.</p>
     */
    @Nested
    @DisplayName("5.17 Updating beyond stock — the edit is accepted, the charge refuses it")
    class UpdateInsufficientStockTests {

        @Test
        @DisplayName("should accept a grown line beyond the position and refuse its charge with 409")
        void updateOrder_QuantityGrow_BeyondStock_IsAcceptedAndTheChargeRefuses() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            var itemId = placed.itemId(variant.getId());
            assertNothingBooked(variant.getId(), "10.00");

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(itemId, variant.getId(), "50.00")));
            mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk());

            // The edit is a document state, and it really is stored: fifty units are what the document now says.
            assertThat(itemRepository.findById(itemId).orElseThrow().getQuantity())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
            assertNothingBooked(variant.getId(), "10.00");

            transitionOrder(placed.orderId(), activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("requested 50")))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("available 10")));

            // The refused charge wrote nothing: no payment, the position is the fixture's, the ledger is empty and
            // the line still states the quantity the edit accepted.
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(itemRepository.findById(itemId).orElseThrow().getQuantity())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("should accept a change to a scarce variant and refuse its charge with 409")
        void updateOrder_VariantChange_BeyondStockOfTheNewVariant_IsAcceptedAndTheChargeRefuses() throws Exception {
            var oldVariant = createTestVariant(new BigDecimal("100.00"));
            var scarceVariant = createTestVariantB(new BigDecimal("1.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "5.00"));
            var itemId = placed.itemId(oldVariant.getId());
            assertNothingBooked(oldVariant.getId(), "100.00");
            assertNothingBooked(scarceVariant.getId(), "1.00");

            var request = new SalesOrderRequest(
                    customerId,
                    companyStoreId,
                    shiftId,
                    "user123",
                    List.of(line(itemId, scarceVariant.getId(), "3.00")));
            mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk());

            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(scarceVariant.getId());
            assertNothingBooked(oldVariant.getId(), "100.00");
            assertNothingBooked(scarceVariant.getId(), "1.00");

            transitionOrder(placed.orderId(), activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict());

            // Neither variant moved: the refusal rolled the whole batch back, which is what the old case asserted
            // for the reversal this one no longer has to roll back.
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertThat(stockPositionOf(oldVariant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(oldVariant.getId())).isEmpty();
            assertThat(stockPositionOf(scarceVariant.getId())).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(scarceVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(movementsOf(scarceVariant.getId())).isEmpty();
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(scarceVariant.getId());
        }
    }

    // ─── 5.18 An uncharged document — nothing is booked, in any order ───

    /**
     * The four orderings of "a line or an order is cancelled, then something else happens", asked what they book.
     *
     * <p><b>What the class was.</b> It guarded the second reversal: whichever order the two cancellations and the
     * delete happened in, the engine's uncovered-remainder arithmetic made the second reversal a no-op, so no line
     * was ever credited twice. Under ADR-0002 decision 7 an uncharged document holds nothing, so every one of these
     * orderings books nothing at all — and the guard's subject, a reversal that must not run twice, lives where a
     * reversal exists: on a deleted charged document, where
     * {@code DeleteOrderStockRestoreTests.deleteOrder_StockRestored} deletes it twice and credits once. Each
     * scenario is kept, with the invariant it now states.</p>
     */
    @Nested
    @DisplayName("5.18 An uncharged document — nothing is booked, in any order of cancels and deletes")
    class SecondReversalGuardTests {

        @Test
        @DisplayName("should book nothing when a deleted line's order is later cancelled")
        void deleteItemThenCancelOrder_BooksNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertNothingBooked(variant.getId(), "100.00");

            cancelOrder(placed.orderId());

            // Neither step books a movement, and there is no reversal to be a second one of.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        @Test
        @DisplayName("should book nothing when an order is cancelled after one of its lines was")
        void cancelLineThenCancelOrder_BooksNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelItem(placed.orderId(), itemId);
            assertNothingBooked(variant.getId(), "100.00");

            // The cancelled line is still enabled, so it is part of what the cancel sees — and neither the line's
            // cancel nor the order's books anything.
            cancelOrder(placed.orderId());

            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        @Test
        @DisplayName("should book nothing when a line of a cancelled order is then cancelled")
        void cancelOrderThenCancelLine_BooksNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelOrder(placed.orderId());
            assertNothingBooked(variant.getId(), "100.00");

            cancelItem(placed.orderId(), itemId);

            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        @Test
        @DisplayName("should not move stock when a cancelled and deleted order is re-enabled")
        void cancelOrderThenDeleteThenReEnable_NoStockMovement() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));

            cancelOrder(placed.orderId());
            assertNothingBooked(variant.getId(), "100.00");

            mockMvc.perform(delete("/api/sales-orders/{id}", placed.orderId()).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(inTransaction(() -> salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getEnabled()))
                    .isFalse();
            // The delete reverses only a document whose charge booked something; a cancelled one never charged.
            assertNothingBooked(variant.getId(), "100.00");

            mockMvc.perform(patch("/api/sales-orders/{id}/enable", placed.orderId())
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            // Re-enabling restores the flag, never the stock: the ledger stays empty and no balance moves.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }
    }

    // ─── 5.19 Terminal order and terminal line — refuse the modification ───

    @Nested
    @DisplayName("5.19 Modifying a terminal order or line — 409, no stock move")
    class TerminalModificationGuardTests {

        @Test
        @DisplayName("should refuse a new line on a Cancelled order instead of stranding its deduction")
        void updateOrder_OnCancelledOrder_NewLineRejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            cancelOrder(placed.orderId());
            assertNothingBooked(variant.getId(), "100.00");

            var body = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "2.00")));
            var response = mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // Cancelled is terminal and a cancelled document can never be charged, so a line added to it would be
            // a line no charge ever books — which is the invariant the refusal protects, and the balance must not
            // move on either side.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("should refuse a changed line set on a Completed order")
        void updateOrder_OnCompletedOrder_Rejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            // The status chain walks the document to Completed without a charge, which books nothing: the document
            // holds no stock to be edited around, and the refusal is about the state, not the stock.
            completeOrder(placed.orderId());
            assertNothingBooked(variant.getId(), "100.00");

            var body = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(itemId, variant.getId(), "6.00")));
            var response = mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("should refuse changing a Cancelled line through the item endpoint")
        void updateItem_OnCancelledLine_Rejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            cancelItem(placed.orderId(), itemId);
            assertNothingBooked(variant.getId(), "100.00");

            var body = line(itemId, variant.getId(), "5.00");
            var response = mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A cancelled line is outside the live set the charge reads: editing it cannot make it sellable, so the
            // refusal keeps the document and the store exactly where they were.
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("should refuse changing a soft-deleted line through the item endpoint")
        void updateItem_OnSoftDeletedLine_Rejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertNothingBooked(variant.getId(), "100.00");

            var body = line(itemId, variant.getId(), "5.00");
            var response = mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A soft-deleted line is outside the live set too, and the item endpoint never re-enables it: the
            // supported route is the order-level PUT, which re-enables it and lets the charge book it (W3-D11).
            assertThat(stockPositionOf(variant.getId())).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();
            assertThat(movementsOf(variant.getId())).isEmpty();
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }
    }

    // ─── 5.16 Item-level writes never cross the order boundary ───

    @Nested
    @DisplayName("5.16 A line of another order is not reachable through the order in the path")
    class ForeignLineOwnershipTests {

        @Test
        @DisplayName("should answer 404 and move no stock for a foreign line, on update and on delete")
        void foreignLine_IsNotFoundOnBothItemWrites() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var owner = createOrder(line(null, variant.getId(), "4.00"));
            var holder = createOrder(line(null, variant.getId(), "1.00"));
            var foreignItemId = owner.itemId(variant.getId());
            // Two documents, and neither of them has booked a unit: both are still documents.
            assertNothingBooked(variant.getId(), "100.00");

            // The targeted line is live — enabled and Pending — so the ownership check is the only
            // one that can refuse these two writes; the same request against its own order succeeds.
            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", holder.orderId(), foreignItemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(line(foreignItemId, variant.getId(), "9.00")))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", holder.orderId(), foreignItemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNotFound());

            assertThat(itemEnabled(foreignItemId)).isTrue();
            // The two refusals wrote nothing: the position is the fixture's and the ledger holds no row.
            assertNothingBooked(variant.getId(), "100.00");
        }
    }

    // ─── 5.21 One line's sequence, and only a booked write takes one ───

    /**
     * {@code sales_order_items.write_seq} ({@code V73}) is what makes a movement attributable to <b>one</b> write of
     * <b>one</b> line, and the two cases here are the two halves of that claim: a booked write is numbered and the
     * movement copies the number, and a write that books nothing neither takes a number nor leaves one behind.
     *
     * <p><b>Why the column is the identity and not a decoration.</b> A {@code stock_movement} row names its line, and
     * that alone cannot tell two writes of that line apart: after {@code T6b} a line has exactly one booked write
     * (the charge), which makes the source key sufficient <em>today</em> and says nothing about the rule. The counter
     * is the general statement — the pair (line, its n-th write) — and the ledger's unique key on the source states
     * it, so a second movement under the same number is refused by the schema instead of being averaged into a
     * balance nobody can explain.</p>
     *
     * <p><b>Why they go through the controller and not through a fixture with hand-written numbers.</b> The numbers
     * are the service's to produce: a case that writes them itself proves the key is wide enough and nothing about
     * the bump, and the bump is the half that can silently repeat a value, skip one, or spend a number on a write
     * the database then refused. Both read the columns back, which is the only evidence that exists.</p>
     */
    @Nested
    @DisplayName("5.21 write_seq — one line, one booked write, one number")
    class WriteSequenceIdentityTests {

        /**
         * A document with two live lines and one cancelled line: each live line is numbered {@code 1} and its one
         * {@code SALE} row carries that number, and the cancelled line is numbered nothing and carries nothing.
         *
         * <p><b>The asymmetry is the assertion.</b> The charge builds its batch from the enabled, non-cancelled
         * lines only, so the cancelled one never reaches the engine — and since the bump is the statement that
         * <em>precedes</em> the movement it numbers, a batch built too eagerly would leave that line at {@code 1}
         * with no row under it. Read from the columns, the two facts are paired: a counter with a movement, or a
         * zero and no movement. A line numbered without a movement is a number nobody can attribute, which is what
         * the third assertion here refuses.</p>
         */
        @Test
        @DisplayName("should number each live line's one movement 1, and leave a cancelled line's counter at 0")
        void writeSeq_namesTheMovementAndNothingElse() throws Exception {
            var sold = createTestVariant(new BigDecimal("100.00"));
            var alsoSold = createTestVariant(new BigDecimal("50.00"));
            var skipped = createTestVariant(new BigDecimal("80.00"));
            var placed = createOrder(
                    line(null, sold.getId(), "4.00"),
                    line(null, alsoSold.getId(), "3.00"),
                    line(null, skipped.getId(), "6.00"));
            var skippedItemId = placed.itemId(skipped.getId());

            cancelItem(placed.orderId(), skippedItemId);
            assertNothingBooked(skipped.getId(), "80.00");

            chargeFromItsCurrentState(placed.orderId());

            for (var live : List.of(sold, alsoSold)) {
                var itemId = placed.itemId(live.getId());
                assertThat(writeSeqOfLine(itemId))
                        .as("the charge is this line's first and only booked write, so it takes 1")
                        .isEqualTo(1L);
                assertThat(saleWritesOfLine(itemId))
                        .as("one SALE row, numbered by the line it names: the number is what attributes the movement "
                                + "to that line's one write and to nothing else")
                        .containsExactly(1L);
            }

            assertThat(writeSeqOfLine(skippedItemId))
                    .as("the cancelled line is outside the charge's batch, so it books nothing and numbers nothing")
                    .isZero();
            assertThat(saleWritesOfLine(skippedItemId))
                    .as("and no movement names it: a counter with no row under it would be a number no write spent")
                    .isEmpty();
            assertThat(stockPositionOf(skipped.getId())).isEqualByComparingTo(new BigDecimal("80.00"));
            assertThat(stockPositionOf(sold.getId())).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(stockPositionOf(alsoSold.getId())).isEqualByComparingTo(new BigDecimal("47.00"));
        }

        /**
         * A charge the store cannot cover answers {@code 409} and hands <b>both</b> numbers back — including the one
         * taken by the line that could have been covered.
         *
         * <p><b>Why the covered line's counter is the interesting one.</b> The charge takes the bump for every line
         * of its batch before the engine runs anything, so the failing document has already spent numbers by the time
         * the insufficiency is thrown. The bump joins the charge's transaction ({@code REQUIRED}), which is what makes
         * the refusal give them back: a bump that committed on its own would leave the line at {@code 1} with no
         * movement, and the next charge of that document would number its first movement {@code 2} — a ledger whose
         * first write of a line is its second, with nothing in the row to say what the missing one was.</p>
         *
         * <p>Read from the columns after the refusal, because the responses cannot state whether a number was spent:
         * a {@code 409} with the counter at {@code 1} is exactly the defect this case exists for.</p>
         */
        @Test
        @DisplayName("should hand both numbers back when the charge refuses the document's stock")
        void refusedChargeConsumesNoSequence() throws Exception {
            var covered = createTestVariant(new BigDecimal("10.00"));
            var uncovered = createTestVariant(new BigDecimal("1.00"));
            var placed = createOrder(line(null, covered.getId(), "2.00"), line(null, uncovered.getId(), "5.00"));
            var coveredItemId = placed.itemId(covered.getId());
            var uncoveredItemId = placed.itemId(uncovered.getId());
            transitionOrder(placed.orderId(), activeStatusId);
            assertThat(List.of(writeSeqOfLine(coveredItemId), writeSeqOfLine(uncoveredItemId)))
                    .as("a document nobody has charged has numbered nothing")
                    .containsExactly(0L, 0L);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    chargeForItsTotal(placed.orderId(), savedPaymentMethodId())))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString(
                                    "Insufficient stock for variant " + uncovered.getId())));

            assertThat(writeSeqOfLine(coveredItemId))
                    .as("the covered line's bump rolled back with the transaction that threw, so the number is the "
                            + "line's to spend again")
                    .isZero();
            assertThat(writeSeqOfLine(uncoveredItemId))
                    .as("and the uncovered line's, taken in the same loop before the engine refused")
                    .isZero();
            assertThat(saleWritesOfLine(coveredItemId))
                    .as("no movement of the covered line survived the refusal")
                    .isEmpty();
            assertThat(saleWritesOfLine(uncoveredItemId)).isEmpty();
            assertThat(paymentsOf(placed.orderId())).isEmpty();
            assertNothingBooked(covered.getId(), "10.00");
            assertNothingBooked(uncovered.getId(), "1.00");
        }

        /** The line's own counter, which is the authority the movement's copy of it comes from. */
        private long writeSeqOfLine(UUID itemId) {
            return inTransaction(() -> jdbcTemplate.queryForObject(
                    "SELECT write_seq FROM sales_order_items WHERE id = ?", Long.class, itemId));
        }

        /**
         * The sequence each {@code SALE} row <b>of this line</b> was booked under, one entry per row.
         *
         * <p>Filtered on {@code source_id} — the line — and not on the variant, which is what makes the assertion a
         * statement about a write: the list answers how many movements this line booked <em>and</em> which numbers
         * they carry, so {@code containsExactly(1L)} refuses both a missing movement and a duplicated one.</p>
         */
        private List<Long> saleWritesOfLine(UUID itemId) {
            return inTransaction(() -> jdbcTemplate.queryForList("""
                    SELECT write_seq
                      FROM stock_movement
                     WHERE source_id = ? AND movement_type = ?
                     ORDER BY occurred_at, id
                    """, Long.class, itemId, StockLedgerWriter.SALE));
        }
    }

    /**
     * A store-scoped {@code lc-sales} principal whose claims carry the real company -> country ->
     * region -> zone -> store chain, so the store guard passes through the real stack instead of
     * being short-circuited. Extra store ids are appended to the {@code company_store_id} claim for
     * the multi-store reassignment case; the claim accepts a comma-separated list.
     */
    private RequestPostProcessor scopedSalesJwt(UUID... extraStoreIds) {
        var chain = storeChain();
        var storeIds = new ArrayList<UUID>();
        storeIds.add(chain.storeId());
        storeIds.addAll(List.of(extraStoreIds));
        var storeClaim = storeIds.stream().map(UUID::toString).collect(Collectors.joining(","));

        return jwt().authorities(ROLE_LC_SALES)
                .jwt(builder -> builder.claim("preferred_username", "seller")
                        .claim("company_id", chain.companyId().toString())
                        .claim("company_country_id", chain.companyCountryId().toString())
                        .claim("company_region_id", chain.regionId().toString())
                        .claim("company_zone_id", chain.zoneId().toString())
                        .claim("company_store_id", storeClaim));
    }

    /** The real company -> country -> region -> zone -> store ids behind the seeded store. */
    private StoreChain storeChain() {
        return inTransaction(() -> {
            var s = companyStoreRepository.findById(companyStoreId).orElseThrow();
            var z = s.getCompanyZone();
            var r = z.getCompanyRegion();
            var c = r.getCompanyCountry();
            return new StoreChain(c.getCompany().getId(), c.getId(), r.getId(), z.getId(), s.getId());
        });
    }

    /**
     * Reads inside a transaction that states this class's company: the store chain is LAZY and the service
     * commits its own transaction, so reading it on a detached entity would throw — and {@code company_stores}
     * is policy-armed, so a read with no tenant stated finds no row at all.
     *
     * <p>It is also the only way this class reads {@code sales_orders}, armed since {@code V65}, on the test
     * thread after a request: {@code TenantScopeFilter} clears the thread's scope in its {@code finally}, so
     * the scope {@code @BeforeEach} states is gone after the first {@code mockMvc.perform} of a test and a
     * bare {@code findById} then answers no row for an order the request just committed.</p>
     */
    private <T> T inTransaction(Supplier<T> reader) {
        return TenantFixtures.inTenant(companyId, reader);
    }

    /** The store chain ids, resolved inside a transaction so the lazy associations are readable. */
    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
