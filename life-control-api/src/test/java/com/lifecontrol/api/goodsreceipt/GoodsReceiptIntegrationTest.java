package com.lifecontrol.api.goodsreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptItem;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptItemRepository;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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
        goodsReceiptItemRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();

        seedCompanyHierarchy();
        seedStoreAndLocation();
        seedOrderReferences();
    }

    @AfterEach
    void tearDown() {
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
                .companyStoreId(store.getId())
                .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                .costPrice(new BigDecimal("10.00"))
                .listPrice(new BigDecimal("20.00"))
                .stock(BigDecimal.ZERO)
                .enabled(true)
                .build());

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
    @DisplayName("should apply V12 and start the context with ddl-auto=validate")
    void flywayAppliesV12AndSchemaValidates() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
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
}
