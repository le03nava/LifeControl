package com.lifecontrol.api.goodsreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.exception.DisabledProductVariantException;
import com.lifecontrol.api.goodsreceipt.exception.OverReceiptException;
import com.lifecontrol.api.goodsreceipt.exception.UnreceivableDetailStatusException;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptItem;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptItemRepository;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.goodsreceipt.service.GoodsReceiptService;
import com.lifecontrol.api.inventory.model.MovementType;
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
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.exception.ConstraintViolationException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Persistence-level verification of the goods receipt schema against real PostgreSQL with Flyway
 * enabled and {@code ddl-auto=validate}, so a green run proves {@code V12__goods_receipts.sql} and
 * the {@link GoodsReceipt}/{@link GoodsReceiptItem} entity mappings agree.
 *
 * <p>Covers the V12 flight-check, the seeded {@code GOODS_RECEIPT}/{@code Registered} status, the
 * database-level guards the schema promises (the {@code receipt_number} UNIQUE, the positive-quantity
 * CHECK and the required {@code product_variant_id} NOT NULL), the round trip of a receipt with one
 * line, and the count-based per-order receipt counter.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Goods Receipt Integration Tests")
class GoodsReceiptIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String STORE_NAME = "Goods Receipt Test Store";
    private static final String AREA_CODE = "GRA1";
    private static final String ZONE_CODE = "GRZ1";
    private static final String LOCATION_CODE = "GRL1";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GoodsReceiptRepository goodsReceiptRepository;

    @Autowired
    private GoodsReceiptItemRepository goodsReceiptItemRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private StatusRepository statusRepository;

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

    @Autowired
    private GoodsReceiptService goodsReceiptService;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private ProductVariantLocationRepository productVariantLocationRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final SimpleGrantedAuthority ROLE_LC_COMPANY_STORE =
            new SimpleGrantedAuthority("ROLE_lc-company-store");

    private static final SimpleGrantedAuthority ROLE_LC_COMPANY_STORE_READ =
            new SimpleGrantedAuthority("ROLE_lc-company-store-read");

    private CompanyStore store;
    private StoreLocation receivingLocation;
    private Supplier supplier;
    private PaymentMethod paymentMethod;
    private Status draftStatus;
    private Status pendingDetailStatus;
    private Status registeredStatus;

    @BeforeEach
    void setUp() {
        // Goods receipt rows reference purchase orders, products/variants, company stores, store
        // locations and statuses with no cascade. Leaked rows would break the deleteAll() calls of
        // later integration classes in this shared JVM, so clean leaf-first before and after.
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
        storeInventorySettingsRepository.deleteAll();
        goodsReceiptItemRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();

        seedCompanyHierarchy();
        seedStoreAndLocation();
        seedOrderReferences();
    }

    @BeforeEach
    void bindRequestAndSecurityContext() {
        // GoodsReceiptService reads the request-scoped CurrentUserContext. Calling it outside a web
        // request needs both a bound request (so the scoped proxy can resolve) and a JWT
        // authentication. The admin authority makes verifyCompanyStoreAccess a pass-through, so the
        // test exercises the persistence and the transaction, not the authorization rule.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("receiver-sub")
                .claim("preferred_username", "receiver")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(ROLE_LC_ADMIN)));
    }

    @AfterEach
    void clearRequestAndSecurityContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        inventoryMovementRepository.deleteAll();
        productVariantLocationRepository.deleteAll();
        productVariantStoreStockRepository.deleteAll();
        storeInventorySettingsRepository.deleteAll();
        goodsReceiptItemRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("GOODS-RECEIPT-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("GOODS-RECEIPT-KEY")
                        .companyName("Goods Receipt Test Company")
                        .rfc("GRTS010101ABC")
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
                        .regionCode("GRT")
                        .regionName("Goods Receipt Region")
                        .enabled(true)
                        .build()));

        var companyZone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("GRT")
                        .zoneName("Goods Receipt Zone")
                        .enabled(true)
                        .build()));

        store = companyStoreRepository.findByCompanyZoneId(companyZone.getId()).stream()
                .filter(candidate -> STORE_NAME.equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(companyZone)
                        .storeName(STORE_NAME)
                        .enabled(true)
                        .build()));
    }

    /** Find-or-create the store &rarr; area &rarr; zone &rarr; location tree and keep the leaf. */
    private void seedStoreAndLocation() {
        var area = findOrCreateArea(store, AREA_CODE, "Warehouse", 1);
        var zone = findOrCreateZone(area, ZONE_CODE, "Aisle", 1);
        receivingLocation = findOrCreateLocation(zone, LOCATION_CODE, "Receiving shelf", 1);
    }

    /** Find-or-create the supplier, payment method and the Flyway-seeded statuses the write needs. */
    private void seedOrderReferences() {
        supplier = supplierRepository
                .findByRfc("GRTS010101ABC")
                .orElseGet(() -> supplierRepository.save(Supplier.builder()
                        .supplierName("Goods Receipt Supplier")
                        .rfc("GRTS010101ABC")
                        .enabled(true)
                        .build()));

        paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("Goods Receipt Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("Goods Receipt Transfer")
                        .paymentMethodShortName("GRT")
                        .enabled(true)
                        .build()));

        draftStatus = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                .orElseThrow();
        pendingDetailStatus = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER_DETAIL", "Pending")
                .orElseThrow();
        registeredStatus = statusRepository
                .findByTypeNameAndStatusName("GOODS_RECEIPT", "Registered")
                .orElseThrow();
    }

    private StoreArea findOrCreateArea(CompanyStore companyStore, String code, String name, int displayOrder) {
        return storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(companyStore.getId()).stream()
                .filter(area -> code.equals(area.getAreaCode()))
                .findFirst()
                .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                        .companyStore(companyStore)
                        .areaCode(code)
                        .areaName(name)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    private StoreZone findOrCreateZone(StoreArea area, String code, String name, int displayOrder) {
        return storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                .filter(zone -> code.equals(zone.getZoneCode()))
                .findFirst()
                .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                        .storeArea(area)
                        .zoneCode(code)
                        .zoneName(name)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    private StoreLocation findOrCreateLocation(StoreZone zone, String code, String name, int displayOrder) {
        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId()).stream()
                .filter(location -> code.equals(location.getLocationCode()))
                .findFirst()
                .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                        .storeZone(zone)
                        .locationCode(code)
                        .locationName(name)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    /** A purchase order with one variant-backed line, wired to the seeded store and statuses. */
    private ReceptionFixtures createReceptionFixtures() {
        var product = productRepository.save(Product.builder()
                .sku("GR-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                .name("Goods Receipt Product")
                .enabled(true)
                .build());

        var variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .barCode("GR-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                .enabled(true)
                .build());
        saveStoreStock(variant.getId());

        var order = purchaseOrderRepository.save(PurchaseOrder.builder()
                .orderNumber("PO-GR-" + UUID.randomUUID().toString().substring(0, 8))
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(draftStatus)
                .comments("Reception order")
                .enabled(true)
                .build());

        var detail = purchaseOrderDetailRepository.save(PurchaseOrderDetail.builder()
                .purchaseOrder(order)
                .product(product)
                .productVariant(variant)
                .quantity(5)
                .unitPrice(new BigDecimal("10.00"))
                .total(new BigDecimal("50.00"))
                .receivedQuantity(0)
                .comments("Reception line")
                .status(pendingDetailStatus)
                .enabled(true)
                .build());

        return new ReceptionFixtures(order, detail, variant);
    }

    private GoodsReceipt newReceipt(PurchaseOrder order, String receiptNumber) {
        return GoodsReceipt.builder()
                .receiptNumber(receiptNumber)
                .purchaseOrder(order)
                .companyStore(store)
                .receivingLocation(receivingLocation)
                .status(registeredStatus)
                .receivedBy("receiver")
                .receivedAt(LocalDateTime.now())
                .comments("Reception")
                .enabled(true)
                .build();
    }

    private GoodsReceiptItem newLine(
            GoodsReceipt receipt, PurchaseOrderDetail detail, ProductVariant variant, String quantity) {
        return GoodsReceiptItem.builder()
                .goodsReceipt(receipt)
                .purchaseOrderDetail(detail)
                .productVariant(variant)
                .quantityReceived(new BigDecimal(quantity))
                .comments("Received line")
                .build();
    }

    /** Per-store row backing the variant: stock starts at zero, prices mirror the old fixture. */
    private void saveStoreStock(UUID variantId) {
        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(store.getId())
                .stock(BigDecimal.ZERO)
                .costPrice(new BigDecimal("10.00"))
                .listPrice(new BigDecimal("20.00"))
                .build());
    }

    /** Reads the per-store stock row, the only place stock lives after the variant split. */
    private ProductVariantStoreStock storeStockOf(UUID variantId) {
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(variantId, store.getId())
                .orElseThrow();
    }

    /** A store configured to receive at {@link #receivingLocation}. */
    private void seedReceivingSettings() {
        storeInventorySettingsRepository.save(StoreInventorySettings.builder()
                .companyStoreId(store.getId())
                .receivingLocationId(receivingLocation.getId())
                .salesLocationId(receivingLocation.getId())
                .build());
    }

    /** A purchase order in the given header status, ready to be received against. */
    private PurchaseOrder createReceivableOrder(String statusName) {
        return purchaseOrderRepository.save(PurchaseOrder.builder()
                .orderNumber("PO-GR-" + UUID.randomUUID().toString().substring(0, 8))
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(statusByName("PURCHASE_ORDER", statusName))
                .comments("Reception order")
                .enabled(true)
                .build());
    }

    /** One variant-backed line of a receivable order, with its own product and variant. */
    private PurchaseOrderDetail addReceivableLine(PurchaseOrder order, int quantity, String detailStatusName) {
        var product = productRepository.save(Product.builder()
                .sku("GR-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                .name("Goods Receipt Product")
                .enabled(true)
                .build());
        var variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .barCode("GR-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                .enabled(true)
                .build());
        saveStoreStock(variant.getId());
        return purchaseOrderDetailRepository.save(PurchaseOrderDetail.builder()
                .purchaseOrder(order)
                .product(product)
                .productVariant(variant)
                .quantity(quantity)
                .unitPrice(new BigDecimal("10.00"))
                .total(new BigDecimal("50.00"))
                .receivedQuantity(0)
                .comments("Reception line")
                .status(statusByName("PURCHASE_ORDER_DETAIL", detailStatusName))
                .enabled(true)
                .build());
    }

    private Status statusByName(String typeName, String statusName) {
        return statusRepository
                .findByTypeNameAndStatusName(typeName, statusName)
                .orElseThrow();
    }

    /** A request that uses the store's configured receiving location (no operator override). */
    private GoodsReceiptRequest receptionRequest(PurchaseOrder order, GoodsReceiptLineRequest... lines) {
        return new GoodsReceiptRequest(order.getId(), null, "Reception", List.of(lines));
    }

    private GoodsReceiptLineRequest line(UUID detailId, String quantity) {
        return new GoodsReceiptLineRequest(detailId, new BigDecimal(quantity), "Received line");
    }

    /**
     * Reads a lazy association inside a transaction: the service commits its own transaction, so
     * reading {@code detail.status} afterwards on the detached entity would throw.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> reader) {
        return new TransactionTemplate(transactionManager).execute(status -> reader.get());
    }

    private String detailStatusName(UUID detailId) {
        return inTransaction(() -> purchaseOrderDetailRepository
                .findById(detailId)
                .orElseThrow()
                .getStatus()
                .getStatusName());
    }

    private String orderStatusName(UUID orderId) {
        return inTransaction(() -> purchaseOrderRepository
                .findById(orderId)
                .orElseThrow()
                .getStatus()
                .getStatusName());
    }

    private static boolean hasConstraintViolationCause(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private record ReceptionFixtures(PurchaseOrder order, PurchaseOrderDetail detail, ProductVariant variant) {}

    @Test
    @DisplayName("should apply every migration up to V14 and start the context with ddl-auto=validate")
    void flywayAppliesLatestAndSchemaValidates() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
        assertThat(flyway.info().pending()).isEmpty();

        // The table exists and is empty: the read itself is the "schema is there" proof.
        assertThat(goodsReceiptRepository.count()).isZero();
    }

    @Test
    @DisplayName("should resolve the seeded GOODS_RECEIPT / Registered status by type and name")
    void registeredStatusResolves() {
        var status = statusRepository
                .findByTypeNameAndStatusName("GOODS_RECEIPT", "Registered")
                .orElseThrow();

        assertThat(status.getStatusName()).isEqualTo("Registered");
        assertThat(status.getStatusType().getStatusTypeName()).isEqualTo("GOODS_RECEIPT");
        assertThat(status.getEnabled()).isTrue();
        assertThat(status.getId()).isEqualTo(registeredStatus.getId());
    }

    @Test
    @DisplayName("should reject a duplicate receipt_number at the database level")
    void duplicateReceiptNumberIsRejected() {
        var fixtures = createReceptionFixtures();
        goodsReceiptRepository.saveAndFlush(newReceipt(fixtures.order(), "GR-DUP-001"));

        assertThatThrownBy(() -> goodsReceiptRepository.saveAndFlush(newReceipt(fixtures.order(), "GR-DUP-001")))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("database constraint violation for the duplicate receipt_number")
                        .isTrue());

        assertThat(goodsReceiptRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject zero and negative quantity_received through the CHECK constraint")
    void nonPositiveQuantityIsRejectedByCheckConstraint() {
        var fixtures = createReceptionFixtures();
        var receipt = goodsReceiptRepository.saveAndFlush(newReceipt(fixtures.order(), "GR-CHK-001"));

        assertThatThrownBy(() -> goodsReceiptItemRepository.saveAndFlush(
                        newLine(receipt, fixtures.detail(), fixtures.variant(), "0.00")))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("CHECK rejects quantity_received = 0")
                        .isTrue());

        assertThatThrownBy(() -> goodsReceiptItemRepository.saveAndFlush(
                        newLine(receipt, fixtures.detail(), fixtures.variant(), "-1.00")))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("CHECK rejects a negative quantity_received")
                        .isTrue());

        assertThat(goodsReceiptItemRepository.count()).isZero();
    }

    @Test
    @DisplayName("should reject a null product_variant_id at the database level")
    void nullProductVariantIsRejectedByNotNullConstraint() {
        var fixtures = createReceptionFixtures();
        var receipt = goodsReceiptRepository.saveAndFlush(newReceipt(fixtures.order(), "GR-NULL-001"));

        // Inserted through raw SQL on purpose: the entity mapping is non-nullable, so Hibernate would
        // stop the null before it reached the database. This proves the DB-level NOT NULL backstop.
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO goods_receipt_items
                            (goods_receipt_id, purchase_order_detail_id, product_variant_id, quantity_received)
                        VALUES (?, ?, CAST(? AS UUID), ?)
                        """, receipt.getId(), fixtures.detail().getId(), null, new BigDecimal("1.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(goodsReceiptItemRepository.count()).isZero();
    }

    @Test
    @DisplayName("should round-trip a persisted receipt with one line and its foreign keys satisfied")
    void receiptWithItemRoundTrips() {
        var fixtures = createReceptionFixtures();
        var receipt = newReceipt(fixtures.order(), "GR-RT-001");
        receipt.getItems().add(newLine(receipt, fixtures.detail(), fixtures.variant(), "3.00"));

        goodsReceiptRepository.saveAndFlush(receipt);

        var reloaded = goodsReceiptRepository.findById(receipt.getId()).orElseThrow();
        assertThat(reloaded.getReceiptNumber()).isEqualTo("GR-RT-001");
        assertThat(reloaded.getPurchaseOrder().getId())
                .isEqualTo(fixtures.order().getId());
        assertThat(reloaded.getCompanyStore().getId()).isEqualTo(store.getId());
        assertThat(reloaded.getReceivingLocation().getId()).isEqualTo(receivingLocation.getId());
        assertThat(reloaded.getStatus().getId()).isEqualTo(registeredStatus.getId());
        assertThat(reloaded.getReceivedBy()).isEqualTo("receiver");
        assertThat(reloaded.getReceivedAt()).isNotNull();
        assertThat(reloaded.getComments()).isEqualTo("Reception");
        assertThat(reloaded.getEnabled()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        var lines = goodsReceiptItemRepository.findByGoodsReceiptId(receipt.getId());
        assertThat(lines).hasSize(1);
        var line = lines.getFirst();
        assertThat(line.getGoodsReceipt().getId()).isEqualTo(receipt.getId());
        assertThat(line.getPurchaseOrderDetail().getId())
                .isEqualTo(fixtures.detail().getId());
        assertThat(line.getProductVariant().getId())
                .isEqualTo(fixtures.variant().getId());
        assertThat(line.getQuantityReceived()).isEqualByComparingTo("3.00");
        assertThat(line.getComments()).isEqualTo("Received line");
        assertThat(line.getCreatedAt()).isNotNull();
        assertThat(line.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should assign received_at at persist time and keep the Auditable timestamps populated")
    void receivedAtIsAssignedAtPersistTimeAndAuditableTimestampsSurvive() {
        var fixtures = createReceptionFixtures();

        // Built without receivedAt(...) on purpose: the table's DEFAULT CURRENT_TIMESTAMP is
        // unreachable through JPA, because Hibernate names the column in the INSERT with an explicit
        // NULL, so only the entity-level callback can fill it.
        var receipt = GoodsReceipt.builder()
                .receiptNumber("GR-DEF-001")
                .purchaseOrder(fixtures.order())
                .companyStore(store)
                .receivingLocation(receivingLocation)
                .status(registeredStatus)
                .receivedBy("receiver")
                .comments("Reception without an explicit received_at")
                .enabled(true)
                .build();
        assertThat(receipt.getReceivedAt()).isNull();

        goodsReceiptRepository.saveAndFlush(receipt);

        var reloaded = goodsReceiptRepository.findById(receipt.getId()).orElseThrow();
        assertThat(reloaded.getReceivedAt()).isNotNull();
        // A callback named onCreate() would override Auditable#onCreate and leave both of these null.
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should count zero receipts for an order without receptions and increment after one")
    void perOrderCounterCountsReceipts() {
        var fixtures = createReceptionFixtures();
        assertThat(goodsReceiptRepository.countByPurchaseOrderId(
                        fixtures.order().getId()))
                .isZero();

        goodsReceiptRepository.saveAndFlush(newReceipt(fixtures.order(), "GR-CNT-001"));

        assertThat(goodsReceiptRepository.countByPurchaseOrderId(
                        fixtures.order().getId()))
                .isEqualTo(1);

        // A different order keeps its own count: the counter is scoped to the purchase order.
        var other = createReceptionFixtures();
        assertThat(goodsReceiptRepository.countByPurchaseOrderId(other.order().getId()))
                .isZero();
    }

    @Nested
    @DisplayName("atomic reception")
    class AtomicReceptionTests {

        @Test
        @DisplayName("should write the receipt, the ledger row, both balances and the detail quantity")
        void happyPathAppliesEveryEffect() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, 5, "In Transit");
            var variantId = detail.getProductVariant().getId();

            var response = goodsReceiptService.createReceipt(receptionRequest(order, line(detail.getId(), "3")));

            assertThat(goodsReceiptRepository.count()).isEqualTo(1);
            assertThat(response.receiptNumber()).isEqualTo("GR-" + order.getOrderNumber() + "-01");
            assertThat(response.orderNumber()).isEqualTo(order.getOrderNumber());
            assertThat(response.companyStoreId()).isEqualTo(store.getId());
            assertThat(response.receivingLocationId()).isEqualTo(receivingLocation.getId());
            assertThat(response.statusName()).isEqualTo("Registered");
            assertThat(response.receivedBy()).isEqualTo("receiver");
            assertThat(response.receivedAt()).isNotNull();
            assertThat(response.lines()).hasSize(1);

            var items = goodsReceiptItemRepository.findByGoodsReceiptId(response.id());
            assertThat(items).hasSize(1);
            assertThat(items.getFirst().getPurchaseOrderDetail().getId()).isEqualTo(detail.getId());
            assertThat(items.getFirst().getProductVariant().getId()).isEqualTo(variantId);
            assertThat(items.getFirst().getQuantityReceived()).isEqualByComparingTo("3");

            var movements = inventoryMovementRepository.findAll();
            assertThat(movements).hasSize(1);
            assertThat(movements.getFirst().getMovementType()).isEqualTo(MovementType.RECEIPT);
            assertThat(movements.getFirst().getReferenceType()).isEqualTo("GOODS_RECEIPT");
            assertThat(movements.getFirst().getReferenceId()).isEqualTo(response.id());
            assertThat(movements.getFirst().getQuantity()).isEqualByComparingTo("3");
            assertThat(movements.getFirst().getCreatedBy()).isEqualTo("receiver");

            var balance = productVariantLocationRepository
                    .findByProductVariantIdAndStoreLocationId(variantId, receivingLocation.getId())
                    .orElseThrow();
            assertThat(balance.getStock()).isEqualByComparingTo("3");
            assertThat(storeStockOf(variantId).getStock()).isEqualByComparingTo("3");

            var reloadedDetail =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(reloadedDetail.getReceivedQuantity()).isEqualTo(3);
            assertThat(detailStatusName(detail.getId())).isEqualTo("Partial Received");
        }

        @Test
        @DisplayName("should accumulate two receipts, move the line to Received and promote the header")
        void secondReceiptCompletesTheLineAndPromotesTheHeader() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, 5, "In Transit");

            var first = goodsReceiptService.createReceipt(receptionRequest(order, line(detail.getId(), "2")));
            var afterFirst =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(afterFirst.getReceivedQuantity()).isEqualTo(2);
            assertThat(detailStatusName(detail.getId())).isEqualTo("Partial Received");
            assertThat(orderStatusName(order.getId())).isEqualTo("In Transit");
            assertThat(first.receiptNumber()).endsWith("-01");

            var second = goodsReceiptService.createReceipt(receptionRequest(order, line(detail.getId(), "3")));

            var afterSecond =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(afterSecond.getReceivedQuantity()).isEqualTo(5);
            assertThat(detailStatusName(detail.getId())).isEqualTo("Received");
            assertThat(orderStatusName(order.getId())).isEqualTo("Received");
            assertThat(second.receiptNumber()).endsWith("-02");
            assertThat(goodsReceiptRepository.count()).isEqualTo(2);
            assertThat(inventoryMovementRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("should leave no receipt, ledger, balance or detail change when the second line over-receives")
        void overReceiptInSecondLineChangesNothing() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, 5, "In Transit");
            var secondLine = addReceivableLine(order, 5, "In Transit");
            var secondVariantId = secondLine.getProductVariant().getId();

            assertThatThrownBy(() -> goodsReceiptService.createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "10"))))
                    .isInstanceOf(OverReceiptException.class);

            assertThat(goodsReceiptRepository.count()).isZero();
            assertThat(goodsReceiptItemRepository.count()).isZero();
            assertThat(inventoryMovementRepository.count()).isZero();
            assertThat(productVariantLocationRepository.count()).isZero();
            assertThat(purchaseOrderDetailRepository
                            .findById(firstLine.getId())
                            .orElseThrow()
                            .getReceivedQuantity())
                    .isZero();
            assertThat(purchaseOrderDetailRepository
                            .findById(secondLine.getId())
                            .orElseThrow()
                            .getReceivedQuantity())
                    .isZero();
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(orderStatusName(order.getId())).isEqualTo("In Transit");
            assertThat(storeStockOf(secondVariantId).getStock()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("controller round trip")
    class ControllerRoundTripTests {

        @Test
        @DisplayName("should persist a receipt through POST and read it back through GET with a store-scoped principal")
        void postCreatesAndGetReadsBack() throws Exception {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, 5, "In Transit");
            var variantId = detail.getProductVariant().getId();
            var chain = storeChain();

            // Store-scoped caller: the JWT claims carry the real store chain, so both the create's
            // order-store check and the read's receipt-store check pass through the real stack.
            var scopedJwt = jwt().authorities(ROLE_LC_COMPANY_STORE)
                    .jwt(builder -> builder.claim("preferred_username", "receiver")
                            .claim("company_id", chain.companyId().toString())
                            .claim(
                                    "company_country_id",
                                    chain.companyCountryId().toString())
                            .claim("company_region_id", chain.regionId().toString())
                            .claim("company_zone_id", chain.zoneId().toString())
                            .claim("company_store_id", chain.storeId().toString()));

            var request = new GoodsReceiptRequest(order.getId(), null, "Reception", List.of(line(detail.getId(), "3")));

            var createResult = mockMvc.perform(post("/api/goods-receipts")
                            .with(scopedJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.receiptNumber").value("GR-" + order.getOrderNumber() + "-01"))
                    .andExpect(jsonPath("$.orderNumber").value(order.getOrderNumber()))
                    .andExpect(jsonPath("$.companyStoreId").value(store.getId().toString()))
                    .andExpect(jsonPath("$.receivedBy").value("receiver"))
                    .andExpect(jsonPath("$.lines[0].quantityReceived").value(3))
                    .andReturn();

            var receiptId = UUID.fromString(objectMapper
                    .readTree(createResult.getResponse().getContentAsString())
                    .get("id")
                    .asText());

            assertThat(goodsReceiptRepository.count()).isEqualTo(1);
            assertThat(goodsReceiptItemRepository.count()).isEqualTo(1);
            var movements = inventoryMovementRepository.findAll();
            assertThat(movements).hasSize(1);
            assertThat(movements.getFirst().getMovementType()).isEqualTo(MovementType.RECEIPT);
            assertThat(movements.getFirst().getReferenceType()).isEqualTo("GOODS_RECEIPT");
            assertThat(movements.getFirst().getReferenceId()).isEqualTo(receiptId);
            assertThat(movements.getFirst().getQuantity()).isEqualByComparingTo("3");

            mockMvc.perform(get("/api/goods-receipts/{id}", receiptId).with(scopedJwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(receiptId.toString()))
                    .andExpect(jsonPath("$.receiptNumber").value("GR-" + order.getOrderNumber() + "-01"))
                    .andExpect(jsonPath("$.lines[0].productVariantId").value(variantId.toString()));
        }
    }

    @Nested
    @DisplayName("pre-write validation leaves the write phase unreachable")
    class PreWriteValidationTests {

        @Test
        @DisplayName(
                "should persist nothing when the second line's variant is disabled: the write phase is unreachable for input errors, first line included")
        void disabledVariantInSecondLineChangesNothing() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, 5, "In Transit");
            var secondLine = addReceivableLine(order, 5, "In Transit");
            var secondVariant = secondLine.getProductVariant();
            secondVariant.setEnabled(false);
            productVariantRepository.saveAndFlush(secondVariant);

            assertThatThrownBy(() -> goodsReceiptService.createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "3"))))
                    .isInstanceOf(DisabledProductVariantException.class)
                    .hasMessageContaining("Reception line 1");

            assertNoReceptionEffects(order, firstLine, secondLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(secondLine.getId())).isEqualTo("In Transit");
        }

        @Test
        @DisplayName(
                "should persist nothing when the second line's detail status cannot reach a received state: the write phase is unreachable for input errors, first line included")
        void unreceivableDetailStatusInSecondLineChangesNothing() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, 5, "In Transit");
            var secondLine = addReceivableLine(order, 5, "Cancelled");

            assertThatThrownBy(() -> goodsReceiptService.createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "3"))))
                    .isInstanceOf(UnreceivableDetailStatusException.class)
                    .hasMessageContaining("Reception line 1");

            assertNoReceptionEffects(order, firstLine, secondLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(secondLine.getId())).isEqualTo("Cancelled");
        }

        @Test
        @DisplayName(
                "should answer 400 naming the line and persist nothing when a terminal Received detail still has quantity outstanding")
        void terminalReceivedDetailIsRejectedBeforeAnyWrite() throws Exception {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, 5, "In Transit");
            // A Received line with quantity outstanding is reachable through the public purchase-order
            // API, which accepts an arbitrary detail statusId and hard-codes receivedQuantity = 0.
            var terminalLine = addReceivableLine(order, 5, "Received");

            var request = new GoodsReceiptRequest(
                    order.getId(),
                    null,
                    "Reception",
                    List.of(line(firstLine.getId(), "3"), line(terminalLine.getId(), "3")));

            // The old "... || isDetailStatusReachable(current, \"Received\")" predicate admitted the
            // terminal Received line (the reachability walk is reflexive), so the write phase ran and
            // the mutator answered 409 after it. The corrected guard rejects the line pre-write, so the
            // receipt error category answers 400 and names the offending line.
            mockMvc.perform(post("/api/goods-receipts")
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(containsString("Reception line 1")))
                    .andExpect(jsonPath("$.message")
                            .value(containsString(terminalLine.getId().toString())));

            assertNoReceptionEffects(order, firstLine, terminalLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(terminalLine.getId())).isEqualTo("Received");
        }

        /**
         * The write phase is unreachable for these input errors, so every effect table and every
         * touched row must be byte-for-byte untouched — for the rejected line and, critically, for
         * the earlier valid one, which a per-line loop could otherwise have half-applied.
         */
        private void assertNoReceptionEffects(PurchaseOrder order, PurchaseOrderDetail... lines) {
            assertThat(goodsReceiptRepository.count()).isZero();
            assertThat(goodsReceiptItemRepository.count()).isZero();
            assertThat(inventoryMovementRepository.count()).isZero();
            assertThat(productVariantLocationRepository.count()).isZero();
            for (var detail : lines) {
                assertThat(purchaseOrderDetailRepository
                                .findById(detail.getId())
                                .orElseThrow()
                                .getReceivedQuantity())
                        .isZero();
                assertThat(storeStockOf(detail.getProductVariant().getId()).getStock())
                        .isEqualByComparingTo("0");
            }
            assertThat(orderStatusName(order.getId())).isEqualTo("In Transit");
        }
    }

    @Nested
    @DisplayName("list query and store scoping over real JWTs")
    class ListAndStoreScopingTests {

        @Test
        @DisplayName(
                "should execute the store-filtered list query on PostgreSQL, unsearched and searched, and return exactly the caller's store")
        void storeScopedListRunsOnPostgresAndNarrowsBySearch() throws Exception {
            var mine1 = persistedOrder(store, "PO-GR-LIST-A");
            var mine2 = persistedOrder(store, "PO-GR-LIST-B");
            goodsReceiptRepository.saveAndFlush(receiptFor(store, mine1, "GR-MINE-001"));
            goodsReceiptRepository.saveAndFlush(receiptFor(store, mine2, "GR-MINE-002"));

            var foreignStore = createOtherStore();
            var foreignOrder = persistedOrder(foreignStore, "PO-GR-LIST-FOREIGN");
            goodsReceiptRepository.saveAndFlush(receiptFor(foreignStore, foreignOrder, "GR-OTHER-001"));

            var chain = storeChain();
            var principal = storeScopedJwt(chain, chain.storeId());

            // Unsearched: both @Query branches execute against PostgreSQL here. The caller sees its
            // two receipts and never the other store's, exactly the W2-D10 filter.
            mockMvc.perform(get("/api/goods-receipts").with(principal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[*].receiptNumber").value(hasItems("GR-MINE-001", "GR-MINE-002")));

            // Searched: the non-null search branch of the same store-filtered query narrows them.
            mockMvc.perform(get("/api/goods-receipts")
                            .param("search", "PO-GR-LIST-B")
                            .with(principal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].receiptNumber").value("GR-MINE-002"));
        }

        @Test
        @DisplayName("should return the unscoped page for an admin principal")
        void adminListIsUnscoped() throws Exception {
            var mine = persistedOrder(store, "PO-GR-ADMIN-A");
            goodsReceiptRepository.saveAndFlush(receiptFor(store, mine, "GR-ADMIN-001"));
            var foreignStore = createOtherStore();
            var foreignOrder = persistedOrder(foreignStore, "PO-GR-ADMIN-B");
            goodsReceiptRepository.saveAndFlush(receiptFor(foreignStore, foreignOrder, "GR-ADMIN-002"));

            mockMvc.perform(get("/api/goods-receipts").with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[*].receiptNumber").value(hasItems("GR-ADMIN-001", "GR-ADMIN-002")));
        }

        @Test
        @DisplayName("should return an empty page for a store-scoped principal with no store claim, leaking nothing")
        void emptyStoreSetYieldsEmptyPage() throws Exception {
            var mine = persistedOrder(store, "PO-GR-EMPTY");
            goodsReceiptRepository.saveAndFlush(receiptFor(store, mine, "GR-EMPTY-001"));

            var noStoreClaim = jwt().authorities(ROLE_LC_COMPANY_STORE_READ)
                    .jwt(builder -> builder.claim("preferred_username", "reader"));

            mockMvc.perform(get("/api/goods-receipts").with(noStoreClaim))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content").isEmpty());
        }

        @Test
        @DisplayName(
                "should reject a foreign-store principal creating against another store's order with 403 and write nothing")
        void createFromForeignStorePrincipalIsForbidden() throws Exception {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, 5, "In Transit");
            var chain = storeChain();
            var request = new GoodsReceiptRequest(order.getId(), null, "Reception", List.of(line(detail.getId(), "3")));

            // The role passes @PreAuthorize; the store claim does not match the order's store, so
            // CurrentUserContext.verifyCompanyStoreAccess throws, GlobalExceptionHandler maps it to
            // 403 and the write phase never starts.
            mockMvc.perform(post("/api/goods-receipts")
                            .with(storeScopedJwt(chain, UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            assertThat(goodsReceiptRepository.count()).isZero();
            assertThat(goodsReceiptItemRepository.count()).isZero();
            assertThat(inventoryMovementRepository.count()).isZero();
        }

        @Test
        @DisplayName("should reject a foreign-store principal reading another store's receipt with 403")
        void readForeignStoreReceiptIsForbidden() throws Exception {
            var order = persistedOrder(store, "PO-GR-FOREIGN-READ");
            var receipt = goodsReceiptRepository.saveAndFlush(receiptFor(store, order, "GR-FOREIGN-READ-001"));
            var chain = storeChain();

            mockMvc.perform(get("/api/goods-receipts/{id}", receipt.getId())
                            .with(storeScopedJwt(chain, UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }

        /** A store-scoped principal whose claims carry the real chain, with a chosen store id. */
        private org.springframework.test.web.servlet.request.RequestPostProcessor storeScopedJwt(
                StoreChain chain, UUID storeId) {
            return jwt().authorities(ROLE_LC_COMPANY_STORE)
                    .jwt(builder -> builder.claim("preferred_username", "receiver")
                            .claim("company_id", chain.companyId().toString())
                            .claim(
                                    "company_country_id",
                                    chain.companyCountryId().toString())
                            .claim("company_region_id", chain.regionId().toString())
                            .claim("company_zone_id", chain.zoneId().toString())
                            .claim("company_store_id", storeId.toString()));
        }

        /** A second store in the same zone, so the store filter has something to exclude. */
        private CompanyStore createOtherStore() {
            return inTransaction(() -> {
                var zone = companyStoreRepository
                        .findById(store.getId())
                        .orElseThrow()
                        .getCompanyZone();
                return companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("Goods Receipt Other Store "
                                + UUID.randomUUID().toString().substring(0, 8))
                        .enabled(true)
                        .build());
            });
        }

        private PurchaseOrder persistedOrder(CompanyStore companyStore, String orderNumber) {
            return purchaseOrderRepository.save(PurchaseOrder.builder()
                    .orderNumber(orderNumber)
                    .supplier(supplier)
                    .companyStore(companyStore)
                    .paymentMethod(paymentMethod)
                    .status(draftStatus)
                    .comments("List order")
                    .enabled(true)
                    .build());
        }

        private GoodsReceipt receiptFor(CompanyStore companyStore, PurchaseOrder order, String receiptNumber) {
            return GoodsReceipt.builder()
                    .receiptNumber(receiptNumber)
                    .purchaseOrder(order)
                    .companyStore(companyStore)
                    .receivingLocation(receivingLocation)
                    .status(registeredStatus)
                    .receivedBy("receiver")
                    .receivedAt(LocalDateTime.now())
                    .comments("List receipt")
                    .enabled(true)
                    .build();
        }
    }

    /** The real company -> country -> region -> zone -> store ids behind {@link #store}. */
    private StoreChain storeChain() {
        return inTransaction(() -> {
            var s = companyStoreRepository.findById(store.getId()).orElseThrow();
            var z = s.getCompanyZone();
            var r = z.getCompanyRegion();
            var c = r.getCompanyCountry();
            return new StoreChain(c.getCompany().getId(), c.getId(), r.getId(), z.getId(), s.getId());
        });
    }

    /** The store chain ids, resolved inside a transaction so the lazy associations are readable. */
    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
