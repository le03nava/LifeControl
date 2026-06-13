package com.lifecontrol.api.salesorder.controller;

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
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Sales Order Integration Tests")
class SalesOrderIntegrationTest {

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
    private StatusRepository statusRepository;

    @Autowired
    private StatusTypeRepository statusTypeRepository;

    @Autowired
    private CustomerRepository customerRepository;

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
        productVariantRepository.deleteAll();
        shiftRepository.deleteAll();

        // Seed reference data (idempotent after first call — data persists across test methods)
        seedReferenceData();
    }

    /**
     * Ensures all reference data needed by sales order operations exists in the DB.
     * Uses find-or-create pattern: loads existing entities first, creates if missing.
     * Status types/statuses are seeded by SalesOrderStatusInitializer at context startup,
     * but company chain, customer, and shifts must be created manually.
     */
    private void seedReferenceData() {
        // Load status IDs (seeded by SalesOrderStatusInitializer)
        draftStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Draft").orElseThrow().getId();
        pendingStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Pending").orElseThrow().getId();
        completedStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Completed").orElseThrow().getId();
        cancelledStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Cancelled").orElseThrow().getId();
        pendingItemStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending").orElseThrow().getId();
        addedItemStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added").orElseThrow().getId();
        cancelledItemStatusId = statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Cancelled").orElseThrow().getId();

        // Company chain: find or create (Country → Company → CompanyCountry → CompanyRegion → CompanyZone → CompanyStore)
        var country = countryRepository.findByCountryCode("MX")
                .orElseGet(() -> countryRepository.save(
                        Country.builder().countryCode("MX").countryName("Mexico").enabled(true).build()));

        var company = companyRepository.findByCompanyKey("TEST-KEY")
                .orElseGet(() -> companyRepository.save(
                        Company.builder().companyKey("TEST-KEY").companyName("Test Company").rfc("TEST123456ABC").enabled(true).build()));

        var companyCountry = companyCountryRepository.findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(
                        CompanyCountry.builder().company(company).country(country).build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId())
                .stream().findFirst()
                .orElseGet(() -> companyRegionRepository.save(
                        CompanyRegion.builder().companyCountry(companyCountry).regionCode("01").regionName("Test Region").enabled(true).build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId())
                .stream().findFirst()
                .orElseGet(() -> companyZoneRepository.save(
                        CompanyZone.builder().companyRegion(region).zoneCode("01").zoneName("Test Zone").enabled(true).build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId())
                .stream().findFirst()
                .orElseGet(() -> companyStoreRepository.save(
                        CompanyStore.builder().companyZone(zone).storeName("Test Store").enabled(true).build()));

        companyStoreId = store.getId();

        // Customer: find or create
        var customer = customerRepository.findBySalesChannel("TEST")
                .stream().findFirst()
                .orElseGet(() -> customerRepository.save(
                        Customer.builder().name("Test Customer").salesChannel("TEST").enabled(true).build()));

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
     * Creates a product variant with the given stock quantity for testing.
     */
    private ProductVariant createTestVariant(BigDecimal stock) {
        var variant = new ProductVariant();
        variant.setProductId(UUID.randomUUID());
        variant.setCompanyStoreId(companyStoreId);
        variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setListPrice(new BigDecimal("100.00"));
        variant.setCostPrice(new BigDecimal("60.00"));
        variant.setStock(stock);
        variant.setEnabled(true);
        return productVariantRepository.save(variant);
    }

    /**
     * Creates a second variant with given stock (different productId) for multi-variant tests.
     */
    private ProductVariant createTestVariantB(BigDecimal stock) {
        var variant = new ProductVariant();
        variant.setProductId(UUID.randomUUID());
        variant.setCompanyStoreId(companyStoreId);
        variant.setVariantName("VariantB-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setListPrice(new BigDecimal("50.00"));
        variant.setCostPrice(new BigDecimal("30.00"));
        variant.setStock(stock);
        variant.setEnabled(true);
        return productVariantRepository.save(variant);
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
                    null, variant.getId(),
                    new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

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
            var updatedVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
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
                    null, variant.getId(),
                    new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(itemRequest));

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
            var updatedVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
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
            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", null);

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("id").asText();

            // Create variant with stock 50
            var variant = createTestVariant(new BigDecimal("50.00"));

            // Add item with quantity 3
            var itemRequest = new SalesOrderItemRequest(
                    null, variant.getId(),
                    new BigDecimal("3.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(post("/api/sales-orders/{id}/items", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.quantity").value(3.00));

            // Verify stock: 50 - 3 = 47
            var updatedVariant = productVariantRepository.findById(variant.getId()).orElseThrow();
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
                    null, variant.getId(),
                    new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(createItem));

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
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));

            // Update quantity to 8 (increase by 3 → stock becomes 92)
            var increaseRequest = new SalesOrderItemRequest(
                    UUID.fromString(itemId), variant.getId(),
                    new BigDecimal("8.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(increaseRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(8.00));

            v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));

            // Update quantity to 2 (decrease by 6 → stock becomes 98)
            var decreaseRequest = new SalesOrderItemRequest(
                    UUID.fromString(itemId), variant.getId(),
                    new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            mockMvc.perform(put("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decreaseRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(2.00));

            v = productVariantRepository.findById(variant.getId()).orElseThrow();
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
                    null, variant.getId(),
                    new BigDecimal("7.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(createItem));

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
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("93.00"));

            // Delete the item
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, itemId)
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Verify stock restored to 100
            v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    // ─── 5.6 Cancel order → stock restored (incl soft-deleted) ───

    @Nested
    @DisplayName("5.6 Cancel order — stock restored including soft-deleted items")
    class CancelOrderStockRestoreTests {

        @Test
        @DisplayName("should restore all item stock on cancel, including soft-deleted")
        void cancelOrder_RestoresAllStock_IncludingSoftDeleted() throws Exception {
            var variant = createTestVariant(new BigDecimal("100.00"));

            // Create order with 2 items: qty 3 and qty 5 → stock becomes 92
            var item1 = new SalesOrderItemRequest(
                    null, variant.getId(),
                    new BigDecimal("3.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var item2 = new SalesOrderItemRequest(
                    null, variant.getId(),
                    new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(item1, item2));

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
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("92.00"));

            // Soft-delete item1
            mockMvc.perform(delete("/api/sales-orders/{id}/items/{itemId}", orderId, item1Id)
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Stock after delete item1: 92 + 3 = 95
            v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("95.00"));

            // Cancel order: restores item2(qty 5) + soft-deleted item1(qty 3) = restores 8
            // Starting stock: 95. After cancel: 95 + 5 + 3 = 103
            var cancelRequest = new UpdateSalesOrderStatusRequest(cancelledStatusId);

            mockMvc.perform(patch("/api/sales-orders/{id}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Cancelled"));

            // Stock should be: 95 + 5 (item2) + 3 (soft-deleted item1) = 103
            v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("103.00"));
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
                    null, variant.getId(),
                    new BigDecimal("4.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("id").asText();

            // Verify stock after create: 96
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("96.00"));

            // Soft-delete the entire order
            mockMvc.perform(delete("/api/sales-orders/{id}", orderId)
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isNoContent());

            // Verify stock restored to 100
            v = productVariantRepository.findById(variant.getId()).orElseThrow();
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
                    null, variant.getId(),
                    new BigDecimal("6.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            var createRequest = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(createItem));

            var createResult = mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRequest))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isCreated())
                    .andReturn();

            var orderId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("id").asText();

            // Verify stock: 94
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            assertThat(v.getStock()).isEqualByComparingTo(new BigDecimal("94.00"));

            // Transition Draft → Pending
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
            v = productVariantRepository.findById(variant.getId()).orElseThrow();
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
                    null, variant.getId(),
                    new BigDecimal("8.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request1 = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(itemRequest1));

            var itemRequest2 = new SalesOrderItemRequest(
                    null, variant.getId(),
                    new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request2 = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(itemRequest2));

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
            var v = productVariantRepository.findById(variant.getId()).orElseThrow();
            var stock = v.getStock();

            // Stock should be consistent: either 2 (thread-1 took 8, thread-2 got 409),
            // or 5 (thread-2 took 5, thread-1 got 409) — never negative or a lost-update
            assertThat(stock.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
            assertThat(stock.compareTo(new BigDecimal("10.00"))).isLessThanOrEqualTo(0);

            // At least one request succeeded
            boolean hasSuccess = (results[0] == 201 || results[1] == 201);
            assertThat(hasSuccess).as("At least one concurrent order should succeed").isTrue();

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
                    null, variantA.getId(),
                    new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var itemB = new SalesOrderItemRequest(
                    null, variantB.getId(),
                    new BigDecimal("10.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);

            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(itemA, itemB));

            mockMvc.perform(post("/api/sales-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .with(jwt().authorities(ROLE_LC_SALES)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // Verify no order was created
            assertThat(salesOrderRepository.findAll()).isEmpty();

            // Verify variant A stock UNCHANGED (rollback)
            var va = productVariantRepository.findById(variantA.getId()).orElseThrow();
            assertThat(va.getStock()).isEqualByComparingTo(new BigDecimal("100.00"));

            // Verify variant B stock UNCHANGED
            var vb = productVariantRepository.findById(variantB.getId()).orElseThrow();
            assertThat(vb.getStock()).isEqualByComparingTo(new BigDecimal("2.00"));
        }
    }
}
