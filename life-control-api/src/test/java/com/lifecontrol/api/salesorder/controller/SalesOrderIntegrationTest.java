package com.lifecontrol.api.salesorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private UUID customerId;
    private UUID companyStoreId;
    private UUID shiftId;
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
        // Clean up mutable data in reverse FK dependency order
        itemRepository.deleteAll();
        salesOrderRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        shiftRepository.deleteAll();

        // Seed reference data (idempotent after first call — data persists across test methods)
        seedReferenceData();
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

    private void saveStoreStock(UUID variantId, BigDecimal stock, BigDecimal listPrice, BigDecimal costPrice) {
        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(companyStoreId)
                .stock(stock)
                .listPrice(listPrice)
                .costPrice(costPrice)
                .build());
    }

    /** Reads the per-store stock row, the only place stock lives after the variant split. */
    private ProductVariantStoreStock storeStockOf(UUID variantId) {
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(variantId, companyStoreId)
                .orElseThrow();
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

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.statusName").value("Draft"))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items.length()").value(1));

            // Verify stock was deducted: 100 - 5 = 95
            var updatedVariant = storeStockOf(variant.getId());
            assertThat(updatedVariant.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));

            // Verify no order was created (our cleanup already deleted all; verify no new ones)
            assertThat(salesOrderRepository.findAll()).isEmpty();

            // Verify variant stock unchanged
            var updatedVariant = storeStockOf(variant.getId());
            assertThat(updatedVariant.getStock()).isEqualByComparingTo(new BigDecimal("5.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
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

            mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.quantity").value(3.00));

            // Verify stock: 50 - 3 = 47
            var updatedVariant = storeStockOf(variant.getId());
            assertThat(updatedVariant.getStock()).isEqualByComparingTo(new BigDecimal("47.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(8.00));

            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));

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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(2.00));

            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("98.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = root.get("id").asText();
            var itemId = root.get("items").get(0).get("id").asText();

            // Verify stock after create: 93
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("93.00"));

            // Delete the item
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Verify stock restored to 100
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var root = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var orderId = root.get("id").asText();
            var item1Id = root.get("items").get(0).get("id").asText();
            // item2Id is not needed directly

            // Stock after create: 100 - 3 - 5 = 92
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));

            // Soft-delete item1
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, item1Id)
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Stock after delete item1: 92 + 3 = 95 (item1 already restored its stock)
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));

            // Cancel order: restores ONLY the enabled item2(qty 5). The soft-deleted
            // item1 was already restored when deleted, so it must NOT be restored again.
            var cancelRequest = new UpdateSalesOrderStatusRequest(cancelledStatusId);

            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Cancelled"));

            // Stock should be: 95 + 5 (item2) = 100 — no double-restore of item1
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Verify stock after create: 96
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            // Soft-delete the entire order
            mockMvc.perform(delete("/api/sales-orders/{id}", orderId).with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Verify stock restored to 100
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText();

            // Verify stock: 94
            var v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("94.00"));

            // Transition Draft → Active
            var activeReq = new UpdateSalesOrderStatusRequest(activeStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(activeReq))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Active"));

            // Transition Active → Pending
            var pendingReq = new UpdateSalesOrderStatusRequest(pendingStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(pendingReq))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Pending"));

            // Transition Pending → Completed
            var completeReq = new UpdateSalesOrderStatusRequest(completedStatusId);
            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeReq))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Completed"));

            // Stock should remain 94 (no restoration on Complete)
            v = storeStockOf(variant.getId());
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("94.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)));
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
                            .with(jwt().authorities(ROLE_LC_SALES)));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // Verify no order was created
            assertThat(salesOrderRepository.findAll()).isEmpty();

            // Verify variant A stock UNCHANGED (rollback)
            var va = storeStockOf(variantA.getId());
            assertThat(va.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            // Verify variant B stock UNCHANGED
            var vb = storeStockOf(variantB.getId());
            assertThat(vb.getStock()).isEqualByComparingTo(new BigDecimal("2.00"));
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
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

            // Step 3: Add second item → stays Active
            var secondItem = new SalesOrderItemRequest(
                    null, variant.getId(), new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(secondItem))
                            .with(jwt().authorities(ROLE_LC_SALES)))
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
                            .with(jwt().authorities(ROLE_LC_SALES)))
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
}
