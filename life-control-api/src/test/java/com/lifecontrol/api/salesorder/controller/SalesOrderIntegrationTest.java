package com.lifecontrol.api.salesorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
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
import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sales Order Integration Tests")
class SalesOrderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository itemRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

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
    private ProductVariantLocationRepository productVariantLocationRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID customerId;
    private UUID companyStoreId;
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

    @BeforeEach
    void setUp() {
        // Clean up mutable data in reverse FK dependency order. inventory_movements and
        // product_variant_locations reference product_variants with no cascade, so they must go
        // before the variants (and before the products) or the delete fails on the FK.
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
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
        // Leave no inventory rows behind: this JVM shares its PostgreSQL container with the other
        // integration classes, and a leftover movement or balance would block their product deletes.
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        // store_inventory_settings references store_locations, so the store-tree integration
        // classes cannot delete locations wholesale while this row exists. It is recreated by
        // seedReferenceData on the next test.
        if (companyStoreId != null) {
            storeInventorySettingsRepository
                    .findById(companyStoreId)
                    .ifPresent(storeInventorySettingsRepository::delete);
        }
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

        var company = companyRepository
                .findByCompanyKey("TEST-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("TEST-KEY")
                        .companyName("Test Company")
                        .rfc("TEST123456ABC")
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

        companyStoreId = store.getId();

        // The sales path now deducts through InventoryService, which needs a per-location balance
        // and the store's sales location to allocate against. Seed them once per store.
        ensureInventoryLocations(store);

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
    private Product createTestProduct() {
        return productRepository.save(Product.builder()
                .sku("SKU-" + UUID.randomUUID().toString().substring(0, 12))
                .name("Test Product")
                .enabled(true)
                .build());
    }

    /**
     * Creates a product variant with the given stock quantity for testing.
     */
    private ProductVariant createTestVariant(BigDecimal stock) {
        var variant = new ProductVariant();
        variant.setProductId(createTestProduct().getId());
        variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
        variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setEnabled(true);
        variant = productVariantRepository.save(variant);
        saveStoreStock(variant.getId(), stock, new BigDecimal("100.00"), new BigDecimal("60.00"));
        return variant;
    }

    /**
     * Creates a second variant with given stock (different productId) for multi-variant tests.
     */
    private ProductVariant createTestVariantB(BigDecimal stock) {
        var variant = new ProductVariant();
        variant.setProductId(createTestProduct().getId());
        variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
        variant.setVariantName("VariantB-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setEnabled(true);
        variant = productVariantRepository.save(variant);
        saveStoreStock(variant.getId(), stock, new BigDecimal("50.00"), new BigDecimal("30.00"));
        return variant;
    }

    /**
     * Creates an enabled variant deliberately not stocked in the store: no aggregate row and no
     * location balance. A sale of it is a stock insufficiency, not a malformed request.
     */
    private ProductVariant createVariantWithoutStoreStock() {
        var variant = new ProductVariant();
        variant.setProductId(createTestProduct().getId());
        variant.setBarCode("BAR-" + UUID.randomUUID().toString().substring(0, 12));
        variant.setVariantName("VariantNoStore-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setEnabled(true);
        return productVariantRepository.save(variant);
    }

    private void saveStoreStock(UUID variantId, BigDecimal stock, BigDecimal listPrice, BigDecimal costPrice) {
        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(companyStoreId)
                .stock(stock)
                .listPrice(listPrice)
                .costPrice(costPrice)
                .build());
        // The engine allocates against location balances, so the seeded aggregate is also the
        // seeded balance of the store's priority sales location. Tests that need a split across
        // locations overwrite this with seedSplitBalances(...).
        productVariantLocationRepository.save(ProductVariantLocation.builder()
                .productVariantId(variantId)
                .storeLocationId(salesLocationId)
                .stock(stock)
                .build());
    }

    /** Reads the per-store stock row, the only place the aggregate lives after the variant split. */
    private ProductVariantStoreStock storeStockOf(UUID variantId) {
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(variantId, companyStoreId)
                .orElseThrow();
    }

    /** Reads one location balance of a variant — the table the sales suite was blind to before W3. */
    private BigDecimal locationStockOf(UUID variantId, UUID locationId) {
        return productVariantLocationRepository
                .findByProductVariantIdAndStoreLocationId(variantId, locationId)
                .orElseThrow()
                .getStock();
    }

    /** The ledger rows of one variant, oldest first, read through the append-only repository. */
    private List<InventoryMovement> movementsOf(UUID variantId) {
        return inventoryMovementRepository.findAll().stream()
                .filter(movement -> variantId.equals(movement.getProductVariantId()))
                .toList();
    }

    /**
     * Splits a variant's balance between the priority sales location and a second location, so a
     * deduction larger than the priority balance has to spill over. The aggregate keeps the sum.
     */
    private void seedSplitBalances(UUID variantId, BigDecimal salesLocationStock, BigDecimal secondaryStock) {
        var priority = productVariantLocationRepository
                .findByProductVariantIdAndStoreLocationId(variantId, salesLocationId)
                .orElseThrow();
        priority.setStock(salesLocationStock);
        productVariantLocationRepository.save(priority);
        productVariantLocationRepository.save(ProductVariantLocation.builder()
                .productVariantId(variantId)
                .storeLocationId(secondaryLocationId)
                .stock(secondaryStock)
                .build());
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

    private List<InventoryMovement> movementsOfType(UUID variantId, MovementType type) {
        return movementsOf(variantId).stream()
                .filter(movement -> movement.getMovementType() == type)
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

    // ─── 5.1 Create order with items → stock deducted ─────────────

    @Nested
    @DisplayName("5.1 Create order with items — stock deducted")
    class CreateOrderStockDeductionTests {

        @Test
        @DisplayName("should create order and deduct stock per variant")
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

            var itemId = UUID.fromString(objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .get("items")
                    .get(0)
                    .get("id")
                    .asText());

            // Verify the deduction reached both sides: aggregate and location 100 - 5 = 95
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // The ledger it never read before now explains the move: one SALE row per location
            // consumed, positive quantity, referencing the line that caused it (W3-D6).
            var movements = movementsOf(variant.getId());
            assertThat(movements).hasSize(1);
            assertThat(movements.get(0).getMovementType()).isEqualTo(MovementType.SALE);
            assertThat(movements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(movements.get(0).getStoreLocationId()).isEqualTo(salesLocationId);
            assertThat(movements.get(0).getReferenceType()).isEqualTo("SALES_ORDER_ITEM");
            assertThat(movements.get(0).getReferenceId()).isEqualTo(itemId);
        }
    }

    // ─── 5.2 Create order with insufficient stock → 409 ──────────

    @Nested
    @DisplayName("5.2 Create order with insufficient stock — 409")
    class CreateOrderInsufficientStockTests {

        @Test
        @DisplayName("should return 409 Conflict when stock insufficient, no order created")
        void createOrder_InsufficientStock_Returns409() throws Exception {
            var variant = createTestVariant(new BigDecimal("5.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));

            // Verify no order was created (our cleanup already deleted all; verify no new ones)
            assertThat(salesOrderRepository.findAll()).isEmpty();

            // Verify variant stock unchanged on both sides, and the ledger still empty: a failed
            // deduction must leave no SALE row behind.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }

        @Test
        @DisplayName("should return 409, not 400, when the variant is not stocked in the order's store")
        void createOrder_VariantNotStockedInStore_Returns409() throws Exception {
            var variant = createVariantWithoutStoreStock();

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("1.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

            // The variant exists and is enabled but has no product_variant_store_stock row in this
            // store: that is zero sellable stock (409), not a bad request (400) or a server error.
            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));

            // Nothing was written for the unstocked variant, on any of the three tables.
            assertThat(salesOrderRepository.findAll()).isEmpty();
            assertThat(productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreId(
                            variant.getId(), companyStoreId))
                    .isEmpty();
            assertThat(productVariantLocationRepository.findByProductVariantIdAndCompanyStoreId(
                            variant.getId(), companyStoreId))
                    .isEmpty();
            assertThat(movementsOf(variant.getId())).isEmpty();
        }
    }

    // ─── 5.3 Add item to order → stock deducted ──────────────────

    @Nested
    @DisplayName("5.3 Add item to order — stock deducted")
    class AddItemStockDeductionTests {

        @Test
        @DisplayName("should deduct stock when item added to existing order")
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

            // Verify aggregate and location stock: 50 - 3 = 47
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("47.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("47.00"));

            var movements = movementsOf(variant.getId());
            assertThat(movements).hasSize(1);
            assertThat(movements.get(0).getMovementType()).isEqualTo(MovementType.SALE);
            assertThat(movements.get(0).getReferenceId()).isEqualTo(itemId);
        }
    }

    // ─── 5.4 Update item quantity → correct delta ────────────────

    @Nested
    @DisplayName("5.4 Update item quantity — correct delta adjustment")
    class UpdateItemQuantityDeltaTests {

        @Test
        @DisplayName("should deduct more on increase, restore on decrease")
        void updateItem_QuantityChange_CorrectDelta() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with item (qty 5 → stock becomes 95)
            var createItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = root.get("id").asText();
            var itemId = root.get("items").get(0).get("id").asText();

            // Verify initial stock: 100 - 5 = 95
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));

            // Update quantity to 8 (increase by 3 → stock becomes 92)
            var increaseRequest = new SalesOrderItemRequest(
                    UUID.fromString(itemId),
                    variant.getId(),
                    new BigDecimal("8.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(increaseRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(8.00));

            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("92.00"));

            // Update quantity to 2 (decrease by 6 → stock becomes 98)
            var decreaseRequest = new SalesOrderItemRequest(
                    UUID.fromString(itemId),
                    variant.getId(),
                    new BigDecimal("2.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decreaseRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(2.00));

            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("98.00"));

            // The decrease reversed the line in full and re-sold the new quantity, so the ledger
            // carries a compensating SALE_REVERSAL next to the SALE rows, all positive (W3-D7).
            var movements = movementsOf(variant.getId());
            assertThat(movements)
                    .extracting(InventoryMovement::getMovementType)
                    .contains(MovementType.SALE, MovementType.SALE_REVERSAL);
            assertThat(movements).allSatisfy(m -> assertThat(m.getQuantity()).isGreaterThan(BigDecimal.ZERO));
        }
    }

    // ─── 5.5 Delete item → stock restored ────────────────────────

    @Nested
    @DisplayName("5.5 Delete item — stock restored")
    class DeleteItemStockRestoredTests {

        @Test
        @DisplayName("should restore stock when item is soft-deleted")
        void deleteItem_StockRestored() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with item (qty 7 → stock becomes 93)
            var createItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("7.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = root.get("id").asText();
            var itemId = root.get("items").get(0).get("id").asText();

            // Verify aggregate and location stock after create: 93
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("93.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("93.00"));

            // Delete the item
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // Verify both sides restored to 100, and the reversal is on the ledger for this line.
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));

            var reversals = movementsOf(variant.getId()).stream()
                    .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL)
                    .toList();
            assertThat(reversals).hasSize(1);
            assertThat(reversals.get(0).getReferenceId()).isEqualTo(UUID.fromString(itemId));
        }

        @Test
        @DisplayName("should re-deduct a re-enabled deleted line exactly as on first creation (W3-D11)")
        void deleteItemThenReEnableSameQuantity_DeductsAgain() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();

            // The PUT carries the deleted line's id back: a soft-deleted line was already given its
            // stock back, so it holds nothing and re-enabling it is a fresh hold, deducted in full.
            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "4.00"));

            assertThat(itemEnabled(itemId)).isTrue();
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));
            // SALE(4), SALE_REVERSAL(4) from the delete, SALE(4) from the re-enable.
            assertThat(movementsOfType(variant.getId(), MovementType.SALE)).hasSize(2);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
        }
    }

    // ─── 5.6 Cancel order → stock restored (enabled items only) ───

    @Nested
    @DisplayName("5.6 Cancel order — stock restored once, soft-deleted items excluded")
    class CancelOrderStockRestoreTests {

        @Test
        @DisplayName(
                "should restore stock only for enabled items on cancel, without double-restoring soft-deleted ones")
        void cancelOrder_RestoresEnabledItemsOnly_NoDoubleRestore() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with 2 items: qty 3 and qty 5 → stock becomes 92
            var item1 = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("3.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var item2 = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(item1, item2));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = root.get("id").asText();
            var item1Id = root.get("items").get(0).get("id").asText();
            var item2Id = root.get("items").get(1).get("id").asText();

            // Stock after create: 100 - 3 - 5 = 92
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("92.00"));

            // Soft-delete item1
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, item1Id)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // Stock after delete item1: 92 + 3 = 95 (item1 already restored its stock)
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // Cancel order: restores ONLY the enabled item2(qty 5). The soft-deleted
            // item1 was already restored when deleted, so it must NOT be restored again.
            var cancelRequest = new UpdateSalesOrderStatusRequest(cancelledStatusId);

            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Cancelled"));

            // Stock should be: 95 + 5 (item2) = 100 — no double-restore of item1, on both sides.
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));

            // Each line has exactly one reversal: item1's from its deletion, item2's from the
            // cancellation. A second reversal of either would have credited stock twice (W3-D6).
            assertThat(movementsOf(variant.getId()).stream()
                            .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL)
                            .map(InventoryMovement::getReferenceId)
                            .toList())
                    .containsExactlyInAnyOrder(UUID.fromString(item1Id), UUID.fromString(item2Id));
        }
    }

    // ─── 5.7 Delete order (soft) → stock restored ────────────────

    @Nested
    @DisplayName("5.7 Delete order (soft) — stock restored")
    class DeleteOrderStockRestoreTests {

        @Test
        @DisplayName("should restore stock when entire order is soft-deleted")
        void deleteOrder_StockRestored() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with item (qty 4 → stock becomes 96)
            var createItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("4.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Verify aggregate and location stock after create: 96
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));

            // Soft-delete the entire order
            mockMvc.perform(delete("/api/sales-orders/{id}", orderId).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // Verify both sides restored to 100
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    // ─── 5.8 Complete order → stock unchanged ────────────────────

    @Nested
    @DisplayName("5.8 Complete order — stock unchanged")
    class CompleteOrderStockUnchangedTests {

        @Test
        @DisplayName("should NOT restore stock when order completes")
        void completeOrder_StockUnchanged() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with item (qty 6 → stock becomes 94)
            var createItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("6.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Verify stock: 94
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("94.00"));

            // Transition Draft → Active
            var activeReq = new UpdateSalesOrderStatusRequest(activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(activeReq))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Active"));

            // Transition Active → Pending
            var pendingReq = new UpdateSalesOrderStatusRequest(pendingStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(pendingReq))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Pending"));

            // Transition Pending → Completed
            var completeReq = new UpdateSalesOrderStatusRequest(completedStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeReq))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Completed"));

            // Stock should remain 94 (no restoration on Complete), on both sides
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("94.00"));
            assertThat(movementsOf(variant.getId()).stream()
                            .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL))
                    .isEmpty();
        }
    }

    // ─── 5.9 Concurrent orders same variant → serialized ─────────

    @Nested
    @DisplayName("5.9 Concurrent orders same variant — serialized, no lost updates")
    class ConcurrentOrdersSerializedTests {

        @Test
        @DisplayName("should serialize concurrent mutations on same variant with no lost updates")
        void concurrentOrders_Serialized_NoLostUpdates() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));

            var latch = new CountDownLatch(2);
            var startSignal = new CountDownLatch(1);

            var itemRequest1 = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("8.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request1 = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest1));

            var itemRequest2 = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request2 = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest2));

            var results = new int[2];

            // Thread 1: create order with qty 8
            var future1 = CompletableFuture.runAsync(() -> {
                try {
                    latch.countDown();
                    startSignal.await(5, TimeUnit.SECONDS);
                    var result = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request1))
                            .with(scopedSalesJwt()));
                    results[0] = result.andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    results[0] = 500;
                }
            });

            // Thread 2: create order with qty 5
            var future2 = CompletableFuture.runAsync(() -> {
                try {
                    latch.countDown();
                    startSignal.await(5, TimeUnit.SECONDS);
                    var result = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request2))
                            .with(scopedSalesJwt()));
                    results[1] = result.andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    results[1] = 500;
                }
            });

            // Wait for both threads to be ready, then release
            latch.await(5, TimeUnit.SECONDS);
            startSignal.countDown();

            CompletableFuture.allOf(future1, future2).get(30, TimeUnit.SECONDS);

            // Verify final state is consistent
            var v = storeStockOf(variant.getId());
            var stock = v.getStock();

            // Stock should be consistent: either 2 (thread-1 took 8, thread-2 got 409),
            // or 5 (thread-2 took 5, thread-1 got 409) — never negative or a lost-update
            assertThat(stock.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
            assertThat(stock.compareTo(new BigDecimal("10.00"))).isLessThanOrEqualTo(0);

            // At least one request succeeded
            boolean hasSuccess = (results[0] == 201 || results[1] == 201);
            assertThat(hasSuccess)
                    .as("At least one concurrent order should succeed")
                    .isTrue();

            // Stock must reflect one successful deduction — either 2 or 5 depending on race winner
            if (hasSuccess) {
                assertThat(stock).isIn(new BigDecimal("2.00"), new BigDecimal("5.00"));
                // And the location balance must agree with the aggregate, never drift from it.
                assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(stock);
            }
        }
    }

    // ─── 5.10 Multi-item create, one insufficient → full rollback

    @Nested
    @DisplayName("5.10 Multi-item create with insufficient — full rollback")
    class MultiItemCreateRollbackTests {

        @Test
        @DisplayName("should rollback entire order when any item has insufficient stock")
        void multiItemCreate_OneInsufficient_FullRollback() throws Exception {
            var variantA = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("2.00"));

            // Order: A qty 5 (sufficient), B qty 10 (insufficient — only 2 available)
            var itemA = new SalesOrderItemRequest(
                    null, variantA.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var itemB = new SalesOrderItemRequest(
                    null, variantB.getId(), new BigDecimal("10.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemA, itemB));

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // Verify no order was created
            assertThat(salesOrderRepository.findAll()).isEmpty();

            // Verify variant A stock UNCHANGED (rollback), on both sides, and no ledger row
            var va = storeStockOf(variantA.getId());
            assertThat(va.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variantA.getId())).isEmpty();

            // Verify variant B stock UNCHANGED
            var vb = storeStockOf(variantB.getId());
            assertThat(vb.getStock()).isEqualByComparingTo(new BigDecimal("2.00"));
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

            // Verify order is now Active (status updated in DB)
            var orderAfterFirstItem =
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow();
            var statusAfterFirstItem =
                    statusRepository.findById(orderAfterFirstItem.getStatusId()).orElseThrow();
            assertThat(statusAfterFirstItem.getStatusName()).isEqualTo("Active");

            // Verify stock deducted: 100 - 3 = 97
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("97.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("97.00"));

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
            var orderAfterSecondItem =
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow();
            var statusAfterSecondItem = statusRepository
                    .findById(orderAfterSecondItem.getStatusId())
                    .orElseThrow();
            assertThat(statusAfterSecondItem.getStatusName()).isEqualTo("Active");

            // Verify stock deducted: 97 - 2 = 95
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // Step 4: Charge → auto-promotes Active → Pending → Completed
            var paymentMethod = PaymentMethod.builder()
                    .paymentMethodName("Efectivo-" + UUID.randomUUID())
                    .paymentMethodShortName("EFECTIVO")
                    .enabled(true)
                    .build();
            paymentMethod = paymentMethodRepository.save(paymentMethod);
            var paymentMethodId = paymentMethod.getId();
            var chargeRequest = new ChargeSalesOrderRequest(paymentMethodId);

            mockMvc.perform(patch("/api/sales-orders/{id}/charge", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(chargeRequest))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Completed"))
                    .andExpect(jsonPath("$.paymentMethodId").value(paymentMethodId.toString()));

            // Verify final order status is Completed
            var finalOrder =
                    salesOrderRepository.findById(UUID.fromString(orderId)).orElseThrow();
            var finalStatus =
                    statusRepository.findById(finalOrder.getStatusId()).orElseThrow();
            assertThat(finalStatus.getStatusName()).isEqualTo("Completed");
            assertThat(finalOrder.getPaymentMethodId()).isEqualTo(paymentMethodId);

            // Verify stock unchanged after charge (no restoration on Complete)
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // Verify items transitioned to Added
            var items = itemRepository.findBySalesOrderId(UUID.fromString(orderId));
            for (var item : items) {
                if (item.getEnabled()) {
                    var itemStatus =
                            statusRepository.findById(item.getStatusId()).orElseThrow();
                    assertThat(itemStatus.getStatusName()).isEqualTo("Added");
                }
            }
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

            // The order deducted its 2 units from its own store: 10 - 2 = 8, on both sides
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("8.00"));

            var zone = companyStoreRepository
                    .findById(companyStoreId)
                    .orElseThrow()
                    .getCompanyZone();
            var otherStore = companyStoreRepository.save(CompanyStore.builder()
                    .companyZone(zone)
                    .storeName("Other Store " + UUID.randomUUID().toString().substring(0, 8))
                    .enabled(true)
                    .build());

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

            // The original store keeps its deduction and the other store gains nothing, on either
            // the aggregate or a location balance. Every reversal later depends on this rule: an
            // order's store never changes while it holds stock.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("8.00"));
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
            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // @BeforeEach deletes all orders, so an empty table proves nothing was persisted.
            assertThat(salesOrderRepository.findAll()).isEmpty();
            // The denied request must not have deducted stock, on either side.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).isEmpty();
        }
    }

    // ─── 5.13 Deduction across two locations — exact reversal ───

    @Nested
    @DisplayName("5.13 Deduction spills across locations — reversal restores each exactly")
    class SpilloverReversalTests {

        @Test
        @DisplayName("should spill the sale past the priority location and reverse each location exactly")
        void deductSpillsAndDeleteRestoresExactLocations() throws Exception {
            var variant = createTestVariant(new BigDecimal("13.00"));
            // Priority sales location holds 3, the second location holds 10, the aggregate holds 13.
            seedSplitBalances(variant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

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
            var itemId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("items")
                    .get(0)
                    .get("id")
                    .asText();

            // The priority location drains to zero and the remainder comes from the second one.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("8.00"));

            var sales = movementsOf(variant.getId()).stream()
                    .filter(m -> m.getMovementType() == MovementType.SALE)
                    .toList();
            assertThat(sales).hasSize(2);
            assertThat(sales)
                    .extracting(InventoryMovement::getStoreLocationId, InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(salesLocationId, new BigDecimal("3.00")),
                            tuple(secondaryLocationId, new BigDecimal("2.00")));
            assertThat(sales).allSatisfy(m -> assertThat(m.getReferenceId()).isEqualTo(UUID.fromString(itemId)));

            // Deleting the line reverses the ledger, so each location gets back exactly what it gave
            // — never a re-run of the priority allocation, which would credit all 5 to the first one.
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));

            var reversals = movementsOf(variant.getId()).stream()
                    .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL)
                    .toList();
            assertThat(reversals).hasSize(2);
            assertThat(reversals)
                    .extracting(InventoryMovement::getStoreLocationId, InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(salesLocationId, new BigDecimal("3.00")),
                            tuple(secondaryLocationId, new BigDecimal("2.00")));
        }
    }

    // ─── 5.14 Cancelled line restores, and never twice ───────────

    @Nested
    @DisplayName("5.14 Cancelling a line restores it — and a later delete does not restore twice")
    class CancelledItemRestoreTests {

        @Test
        @DisplayName("should restore the cancelled line exactly like a deletion (W3-D4)")
        void cancelItem_RestoresTheLineAndWritesTheReversal() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("4.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

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
            var itemId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("items")
                    .get(0)
                    .get("id")
                    .asText();

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));

            var cancelItem = new UpdateSalesOrderStatusRequest(cancelledItemStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/items/{itemId}/status", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelItem))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Cancelled"));

            // The cancellation now restores the line, where before W3-D4 it restored nothing.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId()).stream()
                            .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL)
                            .count())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("should not restore twice when a cancelled line is later deleted")
        void cancelItemThenDelete_DoesNotRestoreTwice() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("4.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

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
            var itemId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("items")
                    .get(0)
                    .get("id")
                    .asText();

            var cancelItem = new UpdateSalesOrderStatusRequest(cancelledItemStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/items/{itemId}/status", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelItem))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());

            // The second reversal finds a zero uncovered remainder (SUM(SALE) - SUM(SALE_REVERSAL))
            // for this line's reference and writes nothing, so the stock is not credited again.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId()).stream()
                            .filter(m -> m.getMovementType() == MovementType.SALE_REVERSAL)
                            .count())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("should not move stock when a cancelled line is re-enabled with a changed quantity (W3-D11)")
        void cancelItemThenReEnable_DoesNotHoldStock() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelItem(placed.orderId(), itemId);
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            // Cancelled is terminal in the item status machine, so re-enabling the flag revives no
            // hold: the line already gave its stock back and must not be deducted a second time.
            // The quantity is deliberately different from the original 4.00: with the same quantity
            // the delta was zero whether or not the cancelled-line guard existed, so deleting the
            // guard left the test green. A changed quantity only moves stock when the cancelled line
            // is wrongly treated as still holding its deduction.
            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "5.00"));

            assertThat(itemRepository.findById(itemId).orElseThrow().getStatusId())
                    .isEqualTo(cancelledItemStatusId);
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            // The ledger stays SALE(4) + SALE_REVERSAL(4): no third row for the cancelled line.
            assertThat(movementsOfType(variant.getId(), MovementType.SALE)).hasSize(1);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
            assertThat(movementsOf(variant.getId())).hasSize(2);
        }
    }

    // ─── 5.15 PUT /{id} item diff — post-state on both sides ─────

    @Nested
    @DisplayName("5.15 Updating an order's line set — aggregate, location and ledger")
    class UpdateOrderItemDiffTests {

        @Test
        @DisplayName("should sell a newly added line and reference its generated id")
        void updateOrder_LineAdded_SellsTheNewLine() throws Exception {
            var variantA = createTestVariant(new BigDecimal("100.00"));
            var variantB = createTestVariantB(new BigDecimal("50.00"));

            var placed = createOrder(line(null, variantA.getId(), "2.00"));
            var retained = line(placed.itemId(variantA.getId()), variantA.getId(), "2.00");

            var after = updateOrderItems(placed.orderId(), retained, line(null, variantB.getId(), "3.00"));

            // The kept line owes nothing; the added line is sold in full from its priority location.
            assertThat(storeStockOf(variantA.getId()).getStock()).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(storeStockOf(variantB.getId()).getStock()).isEqualByComparingTo(new BigDecimal("47.00"));
            assertThat(locationStockOf(variantB.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("47.00"));

            var sales = movementsOfType(variantB.getId(), MovementType.SALE);
            assertThat(sales).hasSize(1);
            assertThat(sales.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(sales.get(0).getReferenceType()).isEqualTo("SALES_ORDER_ITEM");
            assertThat(sales.get(0).getReferenceId()).isEqualTo(after.itemId(variantB.getId()));
            assertThat(movementsOfType(variantA.getId(), MovementType.SALE)).hasSize(1);
        }

        @Test
        @DisplayName("should deduct only the difference when a line grows")
        void updateOrder_QuantityGrown_DeductsTheDifference() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            var itemId = placed.itemId(variant.getId());

            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "5.00"));

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("95.00"));

            // A pure growth deducts the delta only: no reversal is written for the line.
            assertThat(movementsOf(variant.getId()))
                    .extracting(
                            InventoryMovement::getMovementType,
                            InventoryMovement::getReferenceId,
                            InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(MovementType.SALE, itemId, new BigDecimal("2.00")),
                            tuple(MovementType.SALE, itemId, new BigDecimal("3.00")));
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .isEmpty();
        }

        @Test
        @DisplayName("should reverse the whole line exactly and resell the smaller quantity")
        void updateOrder_QuantityShrunk_ReversesExactlyThenResells() throws Exception {
            var variant = createTestVariant(new BigDecimal("13.00"));
            seedSplitBalances(variant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var placed = createOrder(line(null, variant.getId(), "5.00"));
            var itemId = placed.itemId(variant.getId());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("8.00"));

            updateOrderItems(placed.orderId(), line(itemId, variant.getId(), "2.00"));

            // The line was given back to the exact locations that gave it (3 + 2), then 2 were sold
            // again from the priority location: 0 + 3 - 2 and 8 + 2.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("11.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(variant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));

            assertThat(movementsOf(variant.getId()))
                    .allSatisfy(m -> assertThat(m.getReferenceId()).isEqualTo(itemId));
            assertThat(movementsOf(variant.getId()))
                    .extracting(
                            InventoryMovement::getMovementType,
                            InventoryMovement::getStoreLocationId,
                            InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(MovementType.SALE, salesLocationId, new BigDecimal("3.00")),
                            tuple(MovementType.SALE, secondaryLocationId, new BigDecimal("2.00")),
                            tuple(MovementType.SALE_REVERSAL, salesLocationId, new BigDecimal("3.00")),
                            tuple(MovementType.SALE_REVERSAL, secondaryLocationId, new BigDecimal("2.00")),
                            tuple(MovementType.SALE, salesLocationId, new BigDecimal("2.00")));
        }

        @Test
        @DisplayName("should reverse a deleted line exactly and soft-delete it")
        void updateOrder_LineDeleted_ReversesExactly() throws Exception {
            var variantA = createTestVariant(new BigDecimal("13.00"));
            seedSplitBalances(variantA.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));
            var variantB = createTestVariantB(new BigDecimal("10.00"));

            var placed = createOrder(line(null, variantA.getId(), "5.00"), line(null, variantB.getId(), "1.00"));
            var itemAId = placed.itemId(variantA.getId());
            var itemBId = placed.itemId(variantB.getId());
            assertThat(storeStockOf(variantA.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(storeStockOf(variantB.getId()).getStock()).isEqualByComparingTo(new BigDecimal("9.00"));

            // Omitting A from the request deletes it; B keeps its quantity.
            updateOrderItems(placed.orderId(), line(itemBId, variantB.getId(), "1.00"));

            assertThat(storeStockOf(variantA.getId()).getStock()).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(variantA.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(variantA.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(itemEnabled(itemAId)).isFalse();

            assertThat(movementsOfType(variantA.getId(), MovementType.SALE_REVERSAL))
                    .extracting(
                            InventoryMovement::getReferenceId,
                            InventoryMovement::getStoreLocationId,
                            InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(itemAId, salesLocationId, new BigDecimal("3.00")),
                            tuple(itemAId, secondaryLocationId, new BigDecimal("2.00")));
            assertThat(storeStockOf(variantB.getId()).getStock()).isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(movementsOf(variantB.getId())).hasSize(1);
        }

        @Test
        @DisplayName("should reverse the old variant exactly and sell the new one in one update")
        void updateOrder_VariantChanged_ReversesOldExactlyAndSellsNew() throws Exception {
            var first = createTestVariant(new BigDecimal("13.00"));
            var second = createTestVariant(new BigDecimal("13.00"));
            // Keep the sold variant first in the engine's (store, variant) ordering so the composite
            // is characterized without depending on the generated ids.
            var oldVariant = first.getId().compareTo(second.getId()) < 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("3.00"), new BigDecimal("10.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "5.00"));
            var itemId = placed.itemId(oldVariant.getId());

            updateOrderItems(placed.orderId(), line(itemId, newVariant.getId(), "2.00"));

            assertThat(storeStockOf(oldVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("13.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(storeStockOf(newVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("11.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("11.00"));

            assertThat(movementsOfType(oldVariant.getId(), MovementType.SALE_REVERSAL))
                    .extracting(
                            InventoryMovement::getReferenceId,
                            InventoryMovement::getStoreLocationId,
                            InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(itemId, salesLocationId, new BigDecimal("3.00")),
                            tuple(itemId, secondaryLocationId, new BigDecimal("2.00")));
            assertThat(movementsOfType(newVariant.getId(), MovementType.SALE))
                    .extracting(InventoryMovement::getReferenceId, InventoryMovement::getQuantity)
                    .containsExactly(tuple(itemId, new BigDecimal("2.00")));
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(newVariant.getId());
        }

        @Test
        @DisplayName("should reverse before deducting across several changed lines in one batch")
        void updateOrder_MultipleVariantChanges_AllSellTheNewVariant() throws Exception {
            var variants = List.of(
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")),
                    createTestVariant(new BigDecimal("100.00")));
            var byId = variants.stream()
                    .sorted(Comparator.comparing(ProductVariant::getId))
                    .toList();
            // Two new variants that sort before both old ones, so an unordered batch would run each
            // line's deduction before its reversal.
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

            assertThat(storeStockOf(oldOne.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(storeStockOf(oldTwo.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(storeStockOf(newOne.getId()).getStock()).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(storeStockOf(newTwo.getId()).getStock()).isEqualByComparingTo(new BigDecimal("98.00"));
            assertThat(movementsOfType(newOne.getId(), MovementType.SALE)).hasSize(1);
            assertThat(movementsOfType(newOne.getId(), MovementType.SALE_REVERSAL))
                    .isEmpty();
            assertThat(movementsOfType(newTwo.getId(), MovementType.SALE)).hasSize(1);
            assertThat(movementsOfType(newTwo.getId(), MovementType.SALE_REVERSAL))
                    .isEmpty();
        }
    }

    // ─── 5.16 /{id}/items/{itemId} variant change ────────────────

    @Nested
    @DisplayName("5.16 Changing one line's variant — old restored exactly, new sold")
    class UpdateItemVariantChangeTests {

        @Test
        @DisplayName("should restore the old variant exactly and sell the new one")
        void updateItem_VariantChange_RestoresOldExactlyAndSellsNew() throws Exception {
            var first = createTestVariant(new BigDecimal("10.00"));
            var second = createTestVariant(new BigDecimal("10.00"));
            var oldVariant = first.getId().compareTo(second.getId()) < 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("1.00"), new BigDecimal("9.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "4.00"));
            var itemId = placed.itemId(oldVariant.getId());
            // The sale spilled: 1 from the priority location and 3 from the second one.
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("6.00"));

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(line(itemId, newVariant.getId(), "3.00")))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(3.00));

            assertThat(storeStockOf(oldVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(storeStockOf(newVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("7.00"));

            assertThat(movementsOfType(oldVariant.getId(), MovementType.SALE_REVERSAL))
                    .extracting(
                            InventoryMovement::getReferenceId,
                            InventoryMovement::getStoreLocationId,
                            InventoryMovement::getQuantity)
                    .containsExactlyInAnyOrder(
                            tuple(itemId, salesLocationId, new BigDecimal("1.00")),
                            tuple(itemId, secondaryLocationId, new BigDecimal("3.00")));
            assertThat(movementsOfType(newVariant.getId(), MovementType.SALE))
                    .extracting(InventoryMovement::getReferenceId, InventoryMovement::getQuantity)
                    .containsExactly(tuple(itemId, new BigDecimal("3.00")));
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(newVariant.getId());
        }

        @Test
        @DisplayName("should still sell the new variant when its id sorts before the old one")
        void updateItem_VariantChange_NewVariantSortsFirst_StillSellsNew() throws Exception {
            var first = createTestVariant(new BigDecimal("10.00"));
            var second = createTestVariant(new BigDecimal("10.00"));
            // Force the adversarial but reachable ordering: the service sorts the batch by
            // (store, variant), so the new variant's deduction runs before the old one's reversal.
            var oldVariant = first.getId().compareTo(second.getId()) > 0 ? first : second;
            var newVariant = oldVariant == first ? second : first;
            seedSplitBalances(oldVariant.getId(), new BigDecimal("1.00"), new BigDecimal("9.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "4.00"));
            var itemId = placed.itemId(oldVariant.getId());

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(line(itemId, newVariant.getId(), "3.00")))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk());

            // The outcome must not depend on the internal ordering: old restored exactly, new sold.
            assertThat(storeStockOf(oldVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(oldVariant.getId(), secondaryLocationId))
                    .isEqualByComparingTo(new BigDecimal("9.00"));
            assertThat(storeStockOf(newVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(locationStockOf(newVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(movementsOfType(newVariant.getId(), MovementType.SALE)).hasSize(1);
            assertThat(movementsOfType(newVariant.getId(), MovementType.SALE_REVERSAL))
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
            assertThat(storeStockOf(high.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(storeStockOf(mid.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            updateOrderItems(placed.orderId(), line(itemId, low.getId(), "4.00"));
            assertThat(storeStockOf(mid.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(storeStockOf(low.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            updateOrderItems(placed.orderId(), line(itemId, high.getId(), "4.00"));
            assertThat(storeStockOf(low.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(storeStockOf(high.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            // Every batch reversed the variant it held and then sold the new one: no variant keeps
            // an unsold fresh SALE, so every legacy variant is back at its full balance.
            assertThat(movementsOf(mid.getId()))
                    .allSatisfy(m -> assertThat(m.getReferenceId()).isEqualTo(itemId));
            assertThat(movementsOfType(high.getId(), MovementType.SALE)).hasSize(2);
            assertThat(movementsOfType(high.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
        }
    }

    // ─── 5.17 Update-path insufficient stock — nothing written ───

    @Nested
    @DisplayName("5.17 Updating with insufficient stock — 409 and no writes")
    class UpdateInsufficientStockTests {

        @Test
        @DisplayName("should write nothing when a grown line cannot be covered")
        void updateOrder_QuantityGrow_InsufficientStock_WritesNothing() throws Exception {
            var variant = createTestVariant(new BigDecimal("10.00"));
            var placed = createOrder(line(null, variant.getId(), "2.00"));
            var itemId = placed.itemId(variant.getId());

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(itemId, variant.getId(), "50.00")));
            mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(scopedSalesJwt()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // The failed deduction rolled the update back: the line still holds 2 and its sale is
            // still the only row of the ledger.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(itemRepository.findById(itemId).orElseThrow().getQuantity())
                    .isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(movementsOf(variant.getId()))
                    .extracting(InventoryMovement::getMovementType, InventoryMovement::getQuantity)
                    .containsExactly(tuple(MovementType.SALE, new BigDecimal("2.00")));
        }

        @Test
        @DisplayName("should roll back the old variant's reversal when the new one cannot be covered")
        void updateOrder_VariantChange_InsufficientNewVariant_WritesNothing() throws Exception {
            var oldVariant = createTestVariant(new BigDecimal("100.00"));
            var scarceVariant = createTestVariantB(new BigDecimal("1.00"));

            var placed = createOrder(line(null, oldVariant.getId(), "5.00"));
            var itemId = placed.itemId(oldVariant.getId());

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
                    .andExpect(status().isConflict());

            // The old variant's reversal is registered in the same batch, but the failed deduction of
            // the new variant rolls every write of the transaction back.
            assertThat(storeStockOf(oldVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(locationStockOf(oldVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("95.00"));
            assertThat(storeStockOf(scarceVariant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(locationStockOf(scarceVariant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(movementsOf(oldVariant.getId()))
                    .extracting(InventoryMovement::getMovementType, InventoryMovement::getQuantity)
                    .containsExactly(tuple(MovementType.SALE, new BigDecimal("5.00")));
            assertThat(movementsOf(scarceVariant.getId())).isEmpty();
            assertThat(itemRepository.findById(itemId).orElseThrow().getProductVariantId())
                    .isEqualTo(oldVariant.getId());
        }
    }

    // ─── 5.18 Second reversal is a no-op across orderings ────────

    @Nested
    @DisplayName("5.18 Reversing twice — the second reversal writes nothing")
    class SecondReversalGuardTests {

        @Test
        @DisplayName("should not restore again when a deleted line's order is later cancelled")
        void deleteItemThenCancelOrder_NoSecondReversal() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            cancelOrder(placed.orderId());

            // The soft-deleted line is excluded from the cancel, so the second reversal never runs.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();
            assertThat(movementsOf(variant.getId())).hasSize(2);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
        }

        @Test
        @DisplayName("should not restore again when an order is cancelled after one of its lines was")
        void cancelLineThenCancelOrder_NoSecondReversal() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelItem(placed.orderId(), itemId);
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            // The individually cancelled line is still enabled, so the cancel DOES hand it to the
            // engine again; the zero ledger remainder makes that second reversal a no-op.
            cancelOrder(placed.orderId());

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).hasSize(2);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
        }

        @Test
        @DisplayName("should not restore again when a line of a cancelled order is then cancelled")
        void cancelOrderThenCancelLine_NoSecondReversal() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());

            cancelOrder(placed.orderId());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            // The cancel already restored the line; cancelling the line itself asks again and the
            // zero remainder keeps the balances still.
            cancelItem(placed.orderId(), itemId);

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).hasSize(2);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
        }

        @Test
        @DisplayName("should not move stock again when a cancelled and deleted order is re-enabled")
        void cancelOrderThenDeleteThenReEnable_NoStockMovement() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));

            cancelOrder(placed.orderId());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            mockMvc.perform(delete("/api/sales-orders/{id}", placed.orderId()).with(scopedSalesJwt()))
                    .andExpect(status().isNoContent());
            assertThat(salesOrderRepository
                            .findById(placed.orderId())
                            .orElseThrow()
                            .getEnabled())
                    .isFalse();
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            mockMvc.perform(patch("/api/sales-orders/{id}/enable", placed.orderId())
                            .with(scopedSalesJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            // Re-enabling restores the flag, never the stock: no third movement and no balance move.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).hasSize(2);
            assertThat(movementsOfType(variant.getId(), MovementType.SALE_REVERSAL))
                    .hasSize(1);
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
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            var body = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(null, variant.getId(), "2.00")));
            var response = mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // Cancelled is terminal: order-cancel and order-delete both skip it, so a line added to
            // this order would deduct and never be reversed. The balance must not move on either
            // side. It cannot be charged either — charge refuses a non-Pending order.
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).hasSize(2);
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("should refuse a changed line set on a Completed order")
        void updateOrder_OnCompletedOrder_Rejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            completeOrder(placed.orderId());
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            var body = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(line(itemId, variant.getId(), "6.00")));
            var response = mockMvc.perform(put("/api/sales-orders/{id}", placed.orderId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId)).isEqualByComparingTo(new BigDecimal("96.00"));
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("should refuse changing a Cancelled line through the item endpoint")
        void updateItem_OnCancelledLine_Rejected() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));
            var placed = createOrder(line(null, variant.getId(), "4.00"));
            var itemId = placed.itemId(variant.getId());
            cancelItem(placed.orderId(), itemId);
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            var body = line(itemId, variant.getId(), "5.00");
            var response = mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A cancelled line holds nothing: the item endpoint must not sell it again, which would
            // leave a SALE row no line accounts for (the reversal of its reference is a no-op).
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(movementsOf(variant.getId())).hasSize(2);
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
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            var body = line(itemId, variant.getId(), "5.00");
            var response = mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", placed.orderId(), itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(scopedSalesJwt()))
                    .andReturn();

            // A soft-deleted line was given back and the item endpoint never re-enables it, so a
            // deduction here is stranded until someone re-cancels the line by hand. The supported
            // route is the order-level PUT, which re-enables and re-sells the line (W3-D11).
            assertThat(storeStockOf(variant.getId()).getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(locationStockOf(variant.getId(), salesLocationId))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(itemEnabled(itemId)).isFalse();
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
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
     * Reads a lazy association inside a transaction: the store chain is LAZY and the service commits
     * its own transaction, so reading it on a detached entity would throw.
     */
    private <T> T inTransaction(Supplier<T> reader) {
        return new TransactionTemplate(transactionManager).execute(status -> reader.get());
    }

    /** The store chain ids, resolved inside a transaction so the lazy associations are readable. */
    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
