package com.lifecontrol.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Persistence-level verification of the {@code product_variant_id} link added to
 * {@code purchase_order_details} by {@code V9__purchase_order_detail_variant.sql}.
 *
 * <p>Runs against real PostgreSQL with Flyway enabled and {@code ddl-auto=validate},
 * so a green run proves the Flyway column and the {@code PurchaseOrderDetail}
 * entity mapping agree. Also covers the variant ownership rule (404 when the
 * variant does not belong to the line's product and the order's store) and the
 * removal of the manual detail-status route.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Purchase Order Detail Variant Integration Tests")
class PurchaseOrderDetailVariantIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private StatusRepository statusRepository;

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

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private UUID supplierId;
    private UUID storeId;
    private UUID paymentMethodId;
    private UUID draftStatusId;
    private UUID pendingDetailStatusId;

    @BeforeEach
    void setUp() {
        // Details reference purchase orders, so they must go first.
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();

        seedReferenceData();
    }

    @AfterEach
    void tearDown() {
        // Do not leave purchase order rows behind: they reference products, and other
        // integration classes that run later in the same JVM delete products wholesale.
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
    }

    /**
     * Find-or-create the supplier, payment method and company chain the purchase
     * order write paths require; statuses come from Flyway {@code V3}. Idempotent
     * because the Testcontainers database is shared per JVM and survives methods.
     */
    private void seedReferenceData() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("PO-RECEIPT-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("PO-RECEIPT-KEY")
                        .companyName("PO Receipt Test Company")
                        .rfc("PORC010101ABC")
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
                        .regionCode("POC")
                        .regionName("PO Receipt Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("POC")
                        .zoneName("PO Receipt Zone")
                        .enabled(true)
                        .build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> "PO Receipt Store".equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("PO Receipt Store")
                        .enabled(true)
                        .build()));

        storeId = store.getId();

        var supplier = supplierRepository
                .findByRfc("PORC010101ABC")
                .orElseGet(() -> supplierRepository.save(Supplier.builder()
                        .supplierName("PO Receipt Supplier")
                        .rfc("PORC010101ABC")
                        .enabled(true)
                        .build()));

        supplierId = supplier.getId();

        var paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("PO Receipt Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("PO Receipt Transfer")
                        .paymentMethodShortName("PORT")
                        .enabled(true)
                        .build()));

        paymentMethodId = paymentMethod.getId();

        draftStatusId = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                .orElseThrow()
                .getId();
        pendingDetailStatusId = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER_DETAIL", "Pending")
                .orElseThrow()
                .getId();
    }

    private Product createProduct() {
        return productRepository.save(Product.builder()
                .sku("PO-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                .name("PO Receipt Product")
                .enabled(true)
                .build());
    }

    private ProductVariant createVariant(UUID productId, UUID variantStoreId) {
        var variant = new ProductVariant();
        variant.setProductId(productId);
        variant.setBarCode("PO-BAR-" + UUID.randomUUID().toString().substring(0, 12));
        variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setEnabled(true);
        variant = productVariantRepository.save(variant);

        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variant.getId())
                .companyStoreId(variantStoreId)
                .costPrice(new BigDecimal("10.00"))
                .listPrice(new BigDecimal("20.00"))
                .stock(BigDecimal.ZERO)
                .build());
        return variant;
    }

    private UUID createEmptyPurchaseOrder() throws Exception {
        var request =
                new PurchaseOrderRequest(supplierId, storeId, paymentMethodId, draftStatusId, "Receipt test", null);

        var result = mockMvc.perform(post("/api/purchase-orders")
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    /**
     * Builds a full create/update payload whose single line omits {@code productVariantId}.
     * The header fields are valid, so a 400 can only come from the nested detail constraint.
     */
    private String orderPayloadWithLineMissingVariant(UUID productId) throws Exception {
        var line = new LinkedHashMap<String, Object>();
        line.put("productId", productId.toString());
        line.put("quantity", 2);
        line.put("unitPrice", new BigDecimal("15.00"));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("supplierId", supplierId.toString());
        payload.put("companyStoreId", storeId.toString());
        payload.put("paymentMethodId", paymentMethodId.toString());
        payload.put("statusId", draftStatusId.toString());
        payload.put("comments", "Nested detail validation");
        payload.put("details", List.of(line));
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * Builds a valid header whose {@code details} list holds a single JSON null entry.
     * Before the container-element constraint the service dereferenced it and answered 500.
     */
    private String orderPayloadWithNullDetailEntry() {
        return "{\"supplierId\":\"" + supplierId
                + "\",\"companyStoreId\":\"" + storeId
                + "\",\"paymentMethodId\":\"" + paymentMethodId
                + "\",\"statusId\":\"" + draftStatusId
                + "\",\"comments\":\"Null detail entry\",\"details\":[null]}";
    }

    private UUID addVariantDetail(UUID purchaseOrderId, UUID productId, UUID variantId, int quantity) throws Exception {
        var detailRequest = new PurchaseOrderDetailRequest(
                productId, variantId, quantity, new BigDecimal("25.00"), "Reception line", pendingDetailStatusId);

        var result = mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(detailRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private UUID purchaseOrderStatusId(String statusName) {
        return statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", statusName)
                .orElseThrow()
                .getId();
    }

    private void patchHeaderStatus(UUID purchaseOrderId, String statusName) throws Exception {
        var request = new UpdatePurchaseOrderStatusRequest(purchaseOrderStatusId(statusName));

        mockMvc.perform(patch("/api/purchase-orders/{id}/status", purchaseOrderId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value(statusName));
    }

    private void assertHeaderStatus(UUID purchaseOrderId, String statusName) throws Exception {
        mockMvc.perform(get("/api/purchase-orders/{id}", purchaseOrderId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value(statusName));
    }

    private void assertDetailState(UUID purchaseOrderId, UUID detailId, String statusName, int receivedQuantity)
            throws Exception {
        var result = mockMvc.perform(get("/api/purchase-orders/{id}/details", purchaseOrderId)
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode matching = null;
        for (var node : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (detailId.toString().equals(node.get("id").asText())) {
                matching = node;
                break;
            }
        }

        assertThat(matching)
                .as("detail %s in order %s", detailId, purchaseOrderId)
                .isNotNull();
        assertThat(matching.get("statusName").asText()).isEqualTo(statusName);
        assertThat(matching.get("receivedQuantity").asInt()).isEqualTo(receivedQuantity);
    }

    @Nested
    @DisplayName("valid variant link")
    class ValidVariantTests {

        @Test
        @DisplayName("should persist the variant link and round-trip id and name on read")
        void detailWithVariantRoundTrips() throws Exception {
            var product = createProduct();
            var variant = createVariant(product.getId(), storeId);
            var purchaseOrderId = createEmptyPurchaseOrder();

            var detailRequest = new PurchaseOrderDetailRequest(
                    product.getId(),
                    variant.getId(),
                    4,
                    new BigDecimal("25.00"),
                    "With variant",
                    pendingDetailStatusId);

            mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(detailRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(
                            jsonPath("$.productVariantId").value(variant.getId().toString()))
                    .andExpect(jsonPath("$.productVariantName").value(variant.getVariantName()));

            mockMvc.perform(get("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].productVariantId")
                            .value(variant.getId().toString()))
                    .andExpect(jsonPath("$[0].productVariantName").value(variant.getVariantName()));

            var persisted = purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId);
            assertThat(persisted).hasSize(1);
            assertThat(persisted.getFirst().getProductVariant().getId()).isEqualTo(variant.getId());
        }
    }

    @Nested
    @DisplayName("missing variant contract")
    class MissingVariantContractTests {

        @Test
        @DisplayName("should reject an add payload without productVariantId with 400 and persist nothing")
        void addPayloadWithoutVariantIsRejected() throws Exception {
            var product = createProduct();
            var purchaseOrderId = createEmptyPurchaseOrder();

            // The key is absent, not present-and-null, to pin the contract at the wire level.
            var payload = "{\"productId\":\"" + product.getId() + "\",\"quantity\":2,\"unitPrice\":15.00}";

            mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.productVariantId").value("productVariantId is required"));

            assertThat(purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId))
                    .isEmpty();
        }

        @Test
        @DisplayName("should reject an add payload with a blank productVariantId with 400 and persist nothing")
        void addPayloadWithBlankVariantIsRejected() throws Exception {
            var product = createProduct();
            var purchaseOrderId = createEmptyPurchaseOrder();

            // Jackson coerces the empty string to null, so the same @NotNull contract fires.
            var payload = "{\"productId\":\"" + product.getId()
                    + "\",\"productVariantId\":\"\",\"quantity\":2,\"unitPrice\":15.00}";

            mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.productVariantId").value("productVariantId is required"));

            assertThat(purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId))
                    .isEmpty();
        }

        @Test
        @DisplayName("V14 makes product_variant_id NOT NULL: a variant-less row can no longer be persisted")
        void variantlessRowIsRejectedByDatabase() throws Exception {
            var product = createProduct();
            var purchaseOrderId = createEmptyPurchaseOrder();
            var po = purchaseOrderRepository.findById(purchaseOrderId).orElseThrow();
            var pendingStatus = statusRepository.findById(pendingDetailStatusId).orElseThrow();

            // V9 left the column nullable for legacy rows; V14 dropped that tolerance together
            // with the discarded data (D5), so the database itself now rejects the shape the old
            // HTTP contract could still produce.
            assertThatThrownBy(() -> purchaseOrderDetailRepository.saveAndFlush(PurchaseOrderDetail.builder()
                            .purchaseOrder(po)
                            .product(product)
                            .quantity(2)
                            .unitPrice(new BigDecimal("15.00"))
                            .total(new BigDecimal("30.00"))
                            .receivedQuantity(0)
                            .comments("Legacy row")
                            .status(pendingStatus)
                            .enabled(true)
                            .build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("nested detail validation on the order write routes")
    class NestedDetailValidationTests {

        @Test
        @DisplayName("should reject a create payload whose line omits productVariantId with 400 and persist nothing")
        void createPayloadWithLineMissingVariantIsRejected() throws Exception {
            var product = createProduct();
            var ordersBefore = purchaseOrderRepository.count();

            mockMvc.perform(post("/api/purchase-orders")
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderPayloadWithLineMissingVariant(product.getId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.errors['details[0].productVariantId']").value("productVariantId is required"));

            assertThat(purchaseOrderRepository.count())
                    .as("no order persisted for the rejected create")
                    .isEqualTo(ordersBefore);
        }

        @Test
        @DisplayName("should reject a create payload with a null detail entry with 400 and persist nothing")
        void createPayloadWithNullDetailEntryIsRejected() throws Exception {
            var ordersBefore = purchaseOrderRepository.count();

            mockMvc.perform(post("/api/purchase-orders")
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderPayloadWithNullDetailEntry()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors['details[0]']").value("details must not contain null entries"));

            assertThat(purchaseOrderRepository.count())
                    .as("no order persisted for the rejected create")
                    .isEqualTo(ordersBefore);
        }

        @Test
        @DisplayName("should reject an update payload whose line omits productVariantId with 400 and persist nothing")
        void updatePayloadWithLineMissingVariantIsRejected() throws Exception {
            var product = createProduct();
            var purchaseOrderId = createEmptyPurchaseOrder();

            mockMvc.perform(put("/api/purchase-orders/{id}", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderPayloadWithLineMissingVariant(product.getId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.errors['details[0].productVariantId']").value("productVariantId is required"));

            assertThat(purchaseOrderRepository.findById(purchaseOrderId))
                    .as("order still exists after the rejected update")
                    .isPresent();
            assertThat(purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("variant ownership rule")
    class VariantOwnershipTests {

        @Test
        @DisplayName("should reject a variant that belongs to another product with 404 and persist nothing")
        void variantFromAnotherProductReturns404() throws Exception {
            var product = createProduct();
            var otherProduct = createProduct();
            var foreignVariant = createVariant(otherProduct.getId(), storeId);
            var purchaseOrderId = createEmptyPurchaseOrder();

            var detailRequest = new PurchaseOrderDetailRequest(
                    product.getId(),
                    foreignVariant.getId(),
                    3,
                    new BigDecimal("20.00"),
                    "Foreign product",
                    pendingDetailStatusId);

            mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(detailRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));

            assertThat(purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId))
                    .isEmpty();
        }

        @Test
        @DisplayName("should reject a variant that belongs to another store with 404 and persist nothing")
        void variantFromAnotherStoreReturns404() throws Exception {
            var product = createProduct();
            var otherStore = createSecondStore();
            var foreignVariant = createVariant(product.getId(), otherStore);
            var purchaseOrderId = createEmptyPurchaseOrder();

            var detailRequest = new PurchaseOrderDetailRequest(
                    product.getId(),
                    foreignVariant.getId(),
                    3,
                    new BigDecimal("20.00"),
                    "Foreign store",
                    pendingDetailStatusId);

            mockMvc.perform(post("/api/purchase-orders/{id}/details", purchaseOrderId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(detailRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));

            assertThat(purchaseOrderDetailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId))
                    .isEmpty();
        }

        private UUID createSecondStore() {
            var zone = companyStoreRepository.findById(storeId).orElseThrow().getCompanyZone();

            var otherStore = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> "PO Receipt Other Store".equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("PO Receipt Other Store")
                            .enabled(true)
                            .build()));

            return otherStore.getId();
        }
    }

    @Nested
    @DisplayName("reception state machine against real statuses")
    class ReceptionStateMachineTests {

        @Test
        @DisplayName("should advance lines and promote the header using the seeded status names")
        void receptionFlowAdvancesLinesAndPromotesHeader() throws Exception {
            var product = createProduct();
            var variantA = createVariant(product.getId(), storeId);
            var variantB = createVariant(product.getId(), storeId);
            var purchaseOrderId = createEmptyPurchaseOrder();

            // addPurchaseOrderDetail only accepts Draft lines, so the lines exist before the header moves.
            var line1 = addVariantDetail(purchaseOrderId, product.getId(), variantA.getId(), 5);
            var line2 = addVariantDetail(purchaseOrderId, product.getId(), variantB.getId(), 4);

            patchHeaderStatus(purchaseOrderId, "Sent");
            patchHeaderStatus(purchaseOrderId, "Accepted");
            patchHeaderStatus(purchaseOrderId, "In Transit");

            // Partial receipt of line 1: Partial Received while the sibling line is still pending.
            purchaseOrderService.registerReceivedQuantity(purchaseOrderId, line1, 2);
            assertDetailState(purchaseOrderId, line1, "Partial Received", 2);
            assertHeaderStatus(purchaseOrderId, "In Transit");

            // Full receipt of line 1 alone must not promote the header while line 2 is pending.
            purchaseOrderService.registerReceivedQuantity(purchaseOrderId, line1, 5);
            assertDetailState(purchaseOrderId, line1, "Received", 5);
            assertHeaderStatus(purchaseOrderId, "In Transit");

            // Full receipt of the last enabled line promotes the header through PO_TRANSITIONS.
            purchaseOrderService.registerReceivedQuantity(purchaseOrderId, line2, 4);
            assertDetailState(purchaseOrderId, line2, "Received", 4);
            assertHeaderStatus(purchaseOrderId, "Received");
        }

        @Test
        @DisplayName("should find every seeded status name used by the transition tables")
        void seededStatusNamesMatchTransitionTables() {
            for (var name :
                    List.of("Draft", "Sent", "Accepted", "In Transit", "Received", "Rejected", "Billed", "Closed")) {
                assertThat(statusRepository.findByTypeNameAndStatusName("PURCHASE_ORDER", name))
                        .as("PURCHASE_ORDER status %s", name)
                        .isPresent();
            }

            for (var name : List.of(
                    "Pending", "In Process", "In Transit", "Partial Received", "Received", "Rejected", "Cancelled")) {
                assertThat(statusRepository.findByTypeNameAndStatusName("PURCHASE_ORDER_DETAIL", name))
                        .as("PURCHASE_ORDER_DETAIL status %s", name)
                        .isPresent();
            }
        }
    }

    @Nested
    @DisplayName("removed manual detail-status route")
    class RemovedRouteTests {

        @Test
        @DisplayName("should no longer resolve to any handler")
        void removedDetailStatusRouteIsUnmapped() throws Exception {
            var statusRequest = new UpdatePurchaseOrderStatusRequest(UUID.randomUUID());

            var result = mockMvc.perform(patch(
                                    "/api/purchase-orders/{id}/details/{detailId}/status",
                                    UUID.randomUUID(),
                                    UUID.randomUUID())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusRequest)))
                    .andReturn();

            // The route is gone, so the resource handler raises NoResourceFoundException. The
            // global catch-all maps it to 500; asserting the exception proves nothing is mapped.
            assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
        }
    }
}
