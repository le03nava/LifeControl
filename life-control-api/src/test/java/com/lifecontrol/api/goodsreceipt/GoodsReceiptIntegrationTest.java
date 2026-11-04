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

import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.tenant.TenantScope;
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
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.exception.DisabledProductVariantException;
import com.lifecontrol.api.goodsreceipt.exception.OverReceiptException;
import com.lifecontrol.api.goodsreceipt.exception.UnreceivableDetailStatusException;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptItem;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptState;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptItemRepository;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.goodsreceipt.service.GoodsReceiptService;
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
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
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
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence-level verification of the goods receipt schema against real PostgreSQL with Flyway
 * enabled and {@code ddl-auto=validate}, so a green run proves {@code V12__goods_receipts.sql} and
 * the {@link GoodsReceipt}/{@link GoodsReceiptItem} entity mappings agree.
 *
 * <p>Covers the V12 flight-check, the seeded {@code GOODS_RECEIPT}/{@code Registered} status, the
 * database-level guards the schema promises (the per-tenant {@code uq_goods_receipts_doc_number}, the
 * positive-quantity CHECK and the required {@code product_variant_id} NOT NULL), the round trip of a receipt
 * with one line, and the count-based per-order receipt counter.</p>
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
    private DocumentSequenceAllocator documentSequenceAllocator;

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
    private MeasureUnitRepository measureUnitRepository;

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
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    /** One creation row of one receipt, as {@code from|to|actor|reason}; see {@link #creationRowsOf}. */
    private static final String CREATION_ROW_SQL = """
            SELECT coalesce(from_status, '<none>') || '|' || to_status || '|' || actor
                   || '|' || coalesce(reason, '<none>')
              FROM status_transition
             WHERE document_type = 'GOODS_RECEIPT' AND document_id = ?
             ORDER BY seq
            """;

    private static final SimpleGrantedAuthority ROLE_LC_COMPANY_STORE =
            new SimpleGrantedAuthority("ROLE_lc-company-store");

    private static final SimpleGrantedAuthority ROLE_LC_COMPANY_STORE_READ =
            new SimpleGrantedAuthority("ROLE_lc-company-store-read");

    private CompanyStore store;

    private Company company;
    private StoreLocation receivingLocation;
    private Supplier supplier;
    private PaymentMethod paymentMethod;
    private PurchaseOrderState draftStatus;
    private PurchaseOrderDetailState pendingDetailStatus;
    private GoodsReceiptState registeredStatus;

    @BeforeEach
    void setUp() {
        // ADR-0003 made `goods_receipts` a tenant table, so every statement about it needs the scope the
        // application's filter installs per request. Without it the deletes below delete NOTHING — RLS
        // answers zero rows and reports success — and the receipts this suite leaves behind pin its store
        // location, which the store suites then cannot delete (measured: 125 failures in a full run, and
        // none in an isolated one). The scope is stated here, once, and cleared at the end of the teardown.
        seedCompanyHierarchy();
        TenantScope.set(company.getId(), null);

        // Goods receipt rows reference purchase orders, products/variants, company stores, store
        // locations and statuses with no cascade. Leaked rows would break the deleteAll() calls of
        // later integration classes in this shared JVM, so clean leaf-first before and after. The two
        // legacy stock tables are not in that list any more: V74 dropped their `stock` columns and V75
        // dropped `inventory_movements`, so the store row below is the only one a receipt still reads.
        productVariantStoreStockRepository.deleteAll();
        storeInventorySettingsRepository.deleteAll();
        goodsReceiptItemRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();

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
        // The receipts this suite applies also book the stock ledger (T5a of odd/tasks/inventory-ledger.md),
        // and those rows reference product_variants and store_locations with no cascade while the ledger is
        // append-only. Deleting them here is what keeps the next suite in this shared JVM able to delete the
        // variant, the location or the store they name.
        if (company != null) {
            deleteStockLedger(jdbcTemplate, company.getId());
        }
        productVariantStoreStockRepository.deleteAll();
        storeInventorySettingsRepository.deleteAll();
        // Every receipt, not only this tenant's: the scoping cases of this suite deliberately write receipts
        // for a second company, and a scoped delete cannot reach them (they would pin a store location no
        // other class can delete). The helper states why it uses the superuser.
        deleteAllGoodsReceipts();
        goodsReceiptItemRepository.deleteAll();
        goodsReceiptRepository.deleteAll();
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        // Clear the scope last: with it installed every delete above actually matched rows, and a scope left
        // on the thread would leak into whatever suite runs next on this one.
        TenantScope.clear();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone chain.
     *
     * <p>One {@link TenantFixtures#inTenant} around the children, not one per statement: every child of
     * the chain is a row on a policy-armed table and references the detached {@code Company} above, so
     * both its {@code INSERT} and the flush's unsaved-value probe need the parent's tenant installed.</p>
     */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        company = TenantFixtures.findOrCreate(
                companyRepository, "GOODS-RECEIPT-KEY", "Goods Receipt Test Company", "GRTS010101ABC");

        store = TenantFixtures.inTenant(company.getId(), () -> {
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

            return companyStoreRepository.findByCompanyZoneId(companyZone.getId()).stream()
                    .filter(candidate -> STORE_NAME.equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(companyZone)
                            .storeName(STORE_NAME)
                            .enabled(true)
                            .build()));
        });
    }

    /**
     * Find-or-create the store &rarr; area &rarr; zone &rarr; location tree and keep the leaf.
     *
     * <p>The area's and zone's {@code INSERT} probe the detached {@code CompanyStore} they reference, and
     * {@code company_stores} is armed: without the store's tenant the probe answers no rows and the child
     * looks unsaved.</p>
     */
    private void seedStoreAndLocation() {
        TenantFixtures.inTenant(company.getId(), () -> {
            var area = findOrCreateArea(store, AREA_CODE, "Warehouse", 1);
            var zone = findOrCreateZone(area, ZONE_CODE, "Aisle", 1);
            receivingLocation = findOrCreateLocation(zone, LOCATION_CODE, "Receiving shelf", 1);
        });
    }

    /**
     * Find-or-create the supplier, payment method and the Flyway-seeded statuses the write needs.
     *
     * <p>The supplier is looked up <b>within</b> the company the seed creates, because a supplier
     * belongs to exactly one company ({@code V42} makes {@code suppliers.company_id} mandatory and
     * replaces the global RFC key with {@code (company_id, rfc)}): an unscoped lookup would hand back a
     * row of another company, which is the shape the catalog no longer has.</p>
     */
    private void seedOrderReferences() {
        TenantFixtures.inTenant(company.getId(), () -> {
            supplier = supplierRepository.findByCompanyIdAndEnabledTrue(company.getId(), Pageable.unpaged()).stream()
                    .filter(candidate -> "GRTS010101ABC".equals(candidate.getRfc()))
                    .findFirst()
                    .orElseGet(() -> supplierRepository.save(Supplier.builder()
                            .supplierName("Goods Receipt Supplier")
                            .rfc("GRTS010101ABC")
                            .company(company)
                            .enabled(true)
                            .build()));
        });

        paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("Goods Receipt Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("Goods Receipt Transfer")
                        .paymentMethodShortName("GRT")
                        .enabled(true)
                        .build()));

        // The states, not the catalog rows: `V71` dropped `status_id`, so a fixture that writes a document
        // writes the enum constant and the label is projected from it.
        draftStatus = PurchaseOrderState.DRAFT;
        pendingDetailStatus = PurchaseOrderDetailState.PENDING;
        registeredStatus = GoodsReceiptState.REGISTERED;
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

    /**
     * The identity parts ADR-0003 requires on a document fixture, taken from the real counter.
     *
     * <p>A hand-written {@code seq_value} would repeat across the orders one company's fixtures create in
     * one period, and {@code uq_purchase_orders_doc_identity} would refuse the second one for a reason that
     * says nothing about the case under test. The allocator needs an open transaction, which every caller
     * here already has through {@code TenantFixtures.inTenant}.</p>
     */
    private DocIdentity allocateIdentity(DocumentType type, UUID companyId) {
        var period = DocumentNumberRenderer.periodOf(LocalDate.now());
        var seqValue = documentSequenceAllocator.allocate(companyId, type, period);
        return new DocIdentity(period, seqValue, DocumentNumberRenderer.renderPeriodNumber(type, period, seqValue));
    }

    /** A period-numbered document's identity parts, so a fixture states all three together. */
    private record DocIdentity(String period, long seqValue, String docNumber) {}

    /** A purchase order with one variant-backed line, wired to the seeded store and statuses. */
    private ReceptionFixtures createReceptionFixtures() {
        return TenantFixtures.inTenant(company.getId(), () -> {
            var identity = allocateIdentity(DocumentType.PURCHASE_ORDER, company.getId());
            var product = productRepository.save(Product.builder()
                    .sku("GR-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("Goods Receipt Product")
                    .company(company)
                    .enabled(true)
                    .build());

            var variant = productVariantRepository.save(ProductVariant.builder()
                    .productId(product.getId())
                    .companyId(company.getId())
                    .measureUnitId(pieceUnitId())
                    .barCode("GR-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                    .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                    .enabled(true)
                    .build());
            saveStoreStock(variant.getId());

            var order = purchaseOrderRepository.saveAndFlush(PurchaseOrder.builder()
                    .orderNumber(identity.docNumber())
                    .docType(DocumentType.PURCHASE_ORDER.code())
                    .seqPeriod(identity.period())
                    .seqValue(identity.seqValue())
                    .docNumber(identity.docNumber())
                    .supplier(supplier)
                    .companyStore(store)
                    // V43: the order's owner is the company of its store, which this fixture already owns.
                    .company(company)
                    .paymentMethod(paymentMethod)
                    .status(draftStatus)
                    .comments("Reception order")
                    .enabled(true)
                    .build());
            // ADR-0004 decision 5 side A: the fixture inserts a document, so it owes the creation row the
            // trigger checks — in the same transaction, because the pair is deferred to commit.
            recordCreation("PURCHASE_ORDER", order.getId(), order.getStatus().name());

            var detail = purchaseOrderDetailRepository.saveAndFlush(PurchaseOrderDetail.builder()
                    .purchaseOrder(order)
                    .product(product)
                    .productVariant(variant)
                    .quantity(BigDecimal.valueOf(5))
                    .unitPrice(new BigDecimal("10.00"))
                    .total(new BigDecimal("50.00"))
                    .receivedQuantity(BigDecimal.ZERO)
                    .comments("Reception line")
                    .status(pendingDetailStatus)
                    .enabled(true)
                    .build());
            recordCreation(
                    "PURCHASE_ORDER_DETAIL", detail.getId(), detail.getStatus().name());

            return new ReceptionFixtures(order, detail, variant);
        });
    }

    private GoodsReceipt newReceipt(PurchaseOrder order, String receiptNumber) {
        return GoodsReceipt.builder()
                .receiptNumber(receiptNumber)
                .companyId(order.getCompany().getId())
                .docType(DocumentType.GOODS_RECEIPT.code())
                .docNumber(receiptNumber)
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

    /**
     * A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. The unit is required
     * since ADR-0006 decision 3 ({@code V55}), and a random uuid would be refused by the foreign key —
     * which would make the fixture fail for a reason that says nothing about the rule under test.
     */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    /**
     * The per-store row backing the variant: the cost an inbound movement has to state and the price the old
     * fixture seeded. No stock: {@code V74} dropped the column, and every unit a case asserts on entered through
     * a writer.
     */
    private void saveStoreStock(UUID variantId) {
        productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(store.getId())
                .costPrice(new BigDecimal("10.00"))
                .listPrice(new BigDecimal("20.00"))
                .build());
    }

    /**
     * One row of {@code stock_movement}, as the fields a receipt case asserts.
     *
     * <p>Since T3c the ledger is the only record of a movement: the legacy {@code inventory_movements} row is no
     * longer written, so a case that wants to say what was booked reads here, and the projections below are the
     * only balances.</p>
     */
    private record LedgerRow(
            String movementType, String sourceType, UUID sourceId, BigDecimal quantity, String actor) {}

    private List<LedgerRow> ledgerRowsOf(UUID variantId) {
        return inTransaction(() -> jdbcTemplate.query(
                """
                SELECT movement_type, source_type, source_id, quantity, actor
                  FROM stock_movement
                 WHERE product_variant_id = ?
                 ORDER BY occurred_at, id
                """,
                (rs, row) -> new LedgerRow(
                        rs.getString("movement_type"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),
                        rs.getBigDecimal("quantity"),
                        rs.getString("actor")),
                variantId));
    }

    /** The ledger's projection for one location, which is where a location balance lives now (T5b). */
    private BigDecimal locationBalanceOf(UUID variantId, UUID storeLocationId) {
        return inTransaction(() -> jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                BigDecimal.class,
                storeLocationId,
                variantId));
    }

    /** The ledger's projection for the store: the number the aggregate column used to hold (T5b). */
    private BigDecimal storePositionOf(UUID variantId) {
        return inTransaction(() -> jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                BigDecimal.class,
                store.getId(),
                variantId));
    }

    /**
     * How many {@code stock_movement} rows the ledger holds, read under this suite's tenant.
     *
     * <p>This is the count a rejected request is judged by, and it is asked here instead of on a legacy row count:
     * {@code inventory_movements} was the table that used to answer "was a movement written", and {@code V75}
     * dropped it. The ledger is the only record of a movement, so the ledger answers.</p>
     */
    private Integer ledgerMovementRows() {
        return inTransaction(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movement", Integer.class));
    }

    /** The same question for the per-location projection {@code V61}'s trigger derives from the movements. */
    private Integer balanceRows() {
        return inTransaction(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_balance", Integer.class));
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
        return TenantFixtures.inTenant(company.getId(), () -> {
            var identity = allocateIdentity(DocumentType.PURCHASE_ORDER, company.getId());
            var order = purchaseOrderRepository.saveAndFlush(PurchaseOrder.builder()
                    .orderNumber(identity.docNumber())
                    .docType(DocumentType.PURCHASE_ORDER.code())
                    .seqPeriod(identity.period())
                    .seqValue(identity.seqValue())
                    .docNumber(identity.docNumber())
                    .supplier(supplier)
                    .companyStore(store)
                    // V43: the owner is the company of the store the order hangs off.
                    .company(company)
                    .paymentMethod(paymentMethod)
                    .status(orderState(statusName))
                    .comments("Reception order")
                    .enabled(true)
                    .build());
            // The order is a document: without its creation row the commit that stores it is refused.
            recordCreation("PURCHASE_ORDER", order.getId(), order.getStatus().name());
            return order;
        });
    }

    /** One variant-backed line of a receivable order, with its own product and variant. */
    private PurchaseOrderDetail addReceivableLine(PurchaseOrder order, String quantity, String detailStatusName) {
        return TenantFixtures.inTenant(company.getId(), () -> {
            var product = productRepository.save(Product.builder()
                    .sku("GR-SKU-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("Goods Receipt Product")
                    .company(company)
                    .enabled(true)
                    .build());
            var variant = productVariantRepository.save(ProductVariant.builder()
                    .productId(product.getId())
                    .companyId(company.getId())
                    .measureUnitId(pieceUnitId())
                    .barCode("GR-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                    .variantName("Variant-" + UUID.randomUUID().toString().substring(0, 8))
                    .enabled(true)
                    .build());
            saveStoreStock(variant.getId());
            var detail = purchaseOrderDetailRepository.saveAndFlush(PurchaseOrderDetail.builder()
                    .purchaseOrder(order)
                    .product(product)
                    .productVariant(variant)
                    .quantity(new BigDecimal(quantity))
                    .unitPrice(new BigDecimal("10.00"))
                    .total(new BigDecimal("50.00"))
                    .receivedQuantity(BigDecimal.ZERO)
                    .comments("Reception line")
                    .status(detailState(detailStatusName))
                    .enabled(true)
                    .build());
            // A line is a document of its own family, with its own pair of triggers.
            recordCreation(
                    "PURCHASE_ORDER_DETAIL", detail.getId(), detail.getStatus().name());
            return detail;
        });
    }

    /**
     * Calls the service directly under this class's company.
     *
     * <p>The production path states the tenant from the caller's claims at the transaction boundary; a
     * test that calls the service instead of the controller has no principal carrying a tenant, so the
     * fixture states it around the call. Without it the service's first read — the purchase order it locks
     * — runs on a policy-armed table with no tenant and answers not-found.</p>
     */
    private GoodsReceiptResponse createReceipt(GoodsReceiptRequest request) {
        return TenantFixtures.inTenant(company.getId(), () -> goodsReceiptService.createReceipt(request));
    }

    /**
     * The order constant a catalog label spells: the fixtures name states the way the catalog labels them
     * ({@code "In Transit"}), and the document stores the constant ({@code IN_TRANSIT}).
     */
    private static PurchaseOrderState orderState(String label) {
        return PurchaseOrderState.valueOf(
                label.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
    }

    /** The line's half of {@link #orderState(String)}, over the detail machine — which is not the item set. */
    private static PurchaseOrderDetailState detailState(String label) {
        return PurchaseOrderDetailState.valueOf(
                label.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
    }

    /** A request that uses the store's configured receiving location (no operator override). */
    private GoodsReceiptRequest receptionRequest(PurchaseOrder order, GoodsReceiptLineRequest... lines) {
        return new GoodsReceiptRequest(order.getId(), null, "Reception", List.of(lines));
    }

    private GoodsReceiptLineRequest line(UUID detailId, String quantity) {
        return new GoodsReceiptLineRequest(detailId, new BigDecimal(quantity), "Received line");
    }

    /**
     * Reads a lazy association inside a transaction that states this class's company: the service commits
     * its own transaction, so reading {@code detail.status} afterwards on the detached entity would throw,
     * and a policy-armed row such as a purchase order is invisible to a read with no tenant stated.
     *
     * <p><b>Two ways a read on the test thread ends up with no tenant stated, and both are this helper's</b>
     * (measured while closing the five failures {@code V65}'s arming produced):</p>
     *
     * <ul>
     *   <li><b>A declared finder runs with no transaction.</b> Spring Data gives a transaction to the methods
     *       {@code SimpleJpaRepository} implements ({@code count}, {@code findById}, {@code saveAndFlush}),
     *       and to nothing else — a finder the interface declares logs {@code No need to create transaction
     *       ...: This method is not transactional}. The scope is installed at {@code doBegin}, so a declared
     *       finder on an armed table reads zero rows.</li>
     *   <li><b>The request filter clears the thread's scope.</b> {@code TenantScopeFilter} clears in its
     *       {@code finally}, and clearing is right for a served request; it also means the scope this
     *       class's {@code @BeforeEach} states is gone after the first {@code mockMvc.perform} of a test.
     *       Any read of an armed table after a request therefore has to state it again, which is what this
     *       helper does instead of widening anything.</li>
     * </ul>
     */
    private <T> T inTransaction(java.util.function.Supplier<T> reader) {
        return TenantFixtures.inTenant(company.getId(), reader);
    }

    /**
     * The label of a line's state, <b>projected from its code</b>.
     *
     * <p>These two helpers used to read {@code detail.getStatus().getStatusName()}, the catalog pointer the row
     * stored. {@code V71} dropped that column, so the same question is asked the way every response asks it now:
     * the document carries the constant and the catalog row is found by {@code (family, code)}. The assertions
     * that call this helper are unchanged, which is the point — the label still has to be the one the screens
     * show, and it is now derived instead of stored.</p>
     */
    private String detailStatusName(UUID detailId) {
        return inTransaction(() -> {
            var state = purchaseOrderDetailRepository
                    .findById(detailId)
                    .orElseThrow()
                    .getStatus();
            return statusRepository
                    .findByTypeNameAndCode("PURCHASE_ORDER_DETAIL", state.name())
                    .orElseThrow()
                    .getStatusName();
        });
    }

    /** The order's half of {@link #detailStatusName(UUID)}, same projection. */
    private String orderStatusName(UUID orderId) {
        return inTransaction(() -> {
            var state = purchaseOrderRepository.findById(orderId).orElseThrow().getStatus();
            return statusRepository
                    .findByTypeNameAndCode("PURCHASE_ORDER", state.name())
                    .orElseThrow()
                    .getStatusName();
        });
    }

    private static boolean hasConstraintViolationCause(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The columns of a creation row of one receipt, read in a transaction that states this class's tenant.
     *
     * <p>{@code status_transition} is FORCEd since {@code V68}, so an unscoped read answers no row at all and
     * would make "the service wrote nothing" and "the service wrote the row" print the same green. The
     * {@code coalesce} is not cosmetic: {@code from_status} is {@code NULL} by definition on a creation row, and
     * a {@code NULL} in a string concatenation turns the whole row into {@code NULL}.</p>
     */
    private List<String> creationRowsOf(UUID receiptId) {
        return inTransaction(() -> jdbcTemplate.queryForList(CREATION_ROW_SQL, String.class, receiptId));
    }

    /**
     * Appends the creation row ADR-0004 decision 5 side A requires of the writer, for a fixture that inserts a
     * document directly instead of through its service.
     *
     * <p>{@code to_status} is the document's own typed state, which is the only thing the row stores since
     * {@code V71} dropped {@code status_id}: the fixture cannot state a state that disagrees with the document,
     * because there is no second column to disagree with. The actor says a fixture wrote it:
     * {@code unknown:migration} belongs to the migration alone. It must run in the same transaction as the
     * document's insert, which is what {@link #saveReceipt} arranges: the check is deferred to commit, so two
     * commits are one commit too many.</p>
     */
    private void recordCreation(String documentType, UUID documentId, String state) {
        jdbcTemplate.update("""
                INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,
                                               actor, reason)
                VALUES (?, ?, ?, NULL, ?, 'unknown:test', 'fixture')
                """, company.getId(), documentType, documentId, state);
    }

    /**
     * Saves a receipt <b>with</b> its creation row, in one transaction.
     *
     * <p>Since {@code V70} the receipt table carries the deferred pair, so a fixture that inserts the document
     * and then its history in two commits cannot insert it at all: the first commit is the one that is judged.
     * One transaction is the shape the service itself uses, and this helper is that shape.</p>
     */
    private GoodsReceipt saveReceipt(GoodsReceipt receipt) {
        return inTransaction(() -> {
            var saved = goodsReceiptRepository.saveAndFlush(receipt);
            recordCreation("GOODS_RECEIPT", saved.getId(), saved.getStatus().name());
            return saved;
        });
    }

    private record ReceptionFixtures(PurchaseOrder order, PurchaseOrderDetail detail, ProductVariant variant) {}

    @Test
    @DisplayName("should apply every migration on the classpath and start the context with ddl-auto=validate")
    void flywayAppliesLatestAndSchemaValidates() {
        assertThat(flyway.info().current()).isNotNull();
        // The assertion is against the newest migration the classpath carries, read from the
        // migration files themselves rather than from Flyway's own report: a file Flyway does not
        // recognise (a missing __, a lower-case v) is on disk and absent from the report, so the two
        // sources disagree exactly where a hand-written literal stayed green. `flyway.info().pending()`
        // below is what proves nothing was left unapplied.
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();

        // The table exists and is empty: the read itself is the "schema is there" proof.
        assertThat(goodsReceiptRepository.count()).isZero();
    }

    @Test
    @DisplayName("should resolve the seeded GOODS_RECEIPT / REGISTERED catalog row by type and code")
    void registeredStatusResolves() {
        var status = statusRepository
                .findByTypeNameAndCode("GOODS_RECEIPT", GoodsReceiptState.REGISTERED.name())
                .orElseThrow();

        assertThat(status.getStatusName())
                .as("the catalog keeps its label: it is what every response projects the pair from")
                .isEqualTo("Registered");
        assertThat(status.getStatusType().getStatusTypeName()).isEqualTo("GOODS_RECEIPT");
        assertThat(status.getEnabled()).isTrue();
        assertThat(status.getCode()).isEqualTo(GoodsReceiptState.REGISTERED.name());
    }

    @Test
    @DisplayName("should reject a duplicate doc_number of one tenant at the database level")
    void duplicateReceiptNumberIsRejected() {
        var fixtures = createReceptionFixtures();
        saveReceipt(newReceipt(fixtures.order(), "GR-DUP-001"));

        assertThatThrownBy(() -> saveReceipt(newReceipt(fixtures.order(), "GR-DUP-001")))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        // `V64` dropped the global UNIQUE on `receipt_number`; what refuses the duplicate now
                        // is ADR-0003 decision 5's per-tenant net over the rendered string.
                        .as("database constraint violation for the duplicate doc_number")
                        .isTrue());

        assertThat(goodsReceiptRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject zero and negative quantity_received through the CHECK constraint")
    void nonPositiveQuantityIsRejectedByCheckConstraint() {
        var fixtures = createReceptionFixtures();
        var receipt = saveReceipt(newReceipt(fixtures.order(), "GR-CHK-001"));

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
        var receipt = saveReceipt(newReceipt(fixtures.order(), "GR-NULL-001"));

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

        saveReceipt(receipt);

        var reloaded = goodsReceiptRepository.findById(receipt.getId()).orElseThrow();
        assertThat(reloaded.getReceiptNumber()).isEqualTo("GR-RT-001");
        assertThat(reloaded.getPurchaseOrder().getId())
                .isEqualTo(fixtures.order().getId());
        assertThat(reloaded.getCompanyStore().getId()).isEqualTo(store.getId());
        assertThat(reloaded.getReceivingLocation().getId()).isEqualTo(receivingLocation.getId());
        assertThat(reloaded.getStatus())
                .as("the row stores the state, and the label it used to store is now projected from it")
                .isEqualTo(GoodsReceiptState.REGISTERED);
        assertThat(statusRepository
                        .findByTypeNameAndCode(
                                "GOODS_RECEIPT", reloaded.getStatus().name())
                        .orElseThrow()
                        .getStatusName())
                .isEqualTo("Registered");
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
                .companyId(company.getId())
                .docType(DocumentType.GOODS_RECEIPT.code())
                .docNumber("GR-DEF-001")
                .purchaseOrder(fixtures.order())
                .companyStore(store)
                .receivingLocation(receivingLocation)
                .status(registeredStatus)
                .receivedBy("receiver")
                .comments("Reception without an explicit received_at")
                .enabled(true)
                .build();
        assertThat(receipt.getReceivedAt()).isNull();

        saveReceipt(receipt);

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
        assertThat(receiptsOf(fixtures.order())).isZero();

        saveReceipt(newReceipt(fixtures.order(), "GR-CNT-001"));

        assertThat(receiptsOf(fixtures.order())).isEqualTo(1);

        // A different order keeps its own count: the counter is scoped to the purchase order.
        var other = createReceptionFixtures();
        assertThat(receiptsOf(other.order())).isZero();
    }

    /**
     * The per-order receipt counter, read inside a transaction that states this class's company.
     *
     * <p><b>Why the read cannot be the bare repository call.</b> {@code countByPurchaseOrderId} is a
     * <i>declared</i> finder, and Spring Data runs a declared finder with <b>no transaction</b>
     * (measured: {@code TransactionInterceptor - No need to create transaction for
     * SimpleJpaRepository.countByPurchaseOrderId: This method is not transactional}); only the methods
     * {@code SimpleJpaRepository} itself implements — {@code count}, {@code findById}, {@code save} — get
     * one. ADR-0005's scope is installed at {@code doBegin} and nowhere else, so with no transaction no
     * scope is stated and an armed table answers zero rows whatever is stored: since {@code V65} armed
     * {@code goods_receipts}, the bare call reported 0 for a receipt the same thread had just committed, while
     * {@code count()} — a method {@code SimpleJpaRepository} implements, so it does get a transaction —
     * reported 1 on the same table in the same case. The counter is a read of a tenant table like any other,
     * so it states the tenant the write used — not a wider scope, and not a changed expectation.
     */
    private long receiptsOf(PurchaseOrder order) {
        return inTransaction(() -> goodsReceiptRepository.countByPurchaseOrderId(order.getId()));
    }

    @Nested
    @DisplayName("atomic reception")
    class AtomicReceptionTests {

        @Test
        @DisplayName("should write the receipt, the ledger row, both balances and the detail quantity")
        void happyPathAppliesEveryEffect() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, "5", "In Transit");
            var variantId = detail.getProductVariant().getId();

            var response = createReceipt(receptionRequest(order, line(detail.getId(), "3")));

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

            var ledgerRows = ledgerRowsOf(variantId);
            assertThat(ledgerRows).hasSize(1);
            var booked = ledgerRows.getFirst();
            assertThat(booked.movementType())
                    .as("the ledger's own vocabulary, not the legacy MovementType the dropped row carried")
                    .isEqualTo("RECEIPT");
            assertThat(booked.sourceType())
                    .as("a receipt line books its own line, so its source is the line and not the mirror (S2)")
                    .isEqualTo(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
            assertThat(booked.sourceId())
                    .as("the line, not the document both lines of a receipt would share")
                    .isEqualTo(items.getFirst().getId());
            assertThat(booked.quantity()).isEqualByComparingTo("3");
            assertThat(booked.actor()).isEqualTo("receiver");

            // S4: the trigger's projections are the only balances. The receipt writes the ledger row and nothing
            // else, so reading the frozen columns would assert a number this slice stops maintaining.
            assertThat(locationBalanceOf(variantId, receivingLocation.getId())).isEqualByComparingTo("3");
            assertThat(storePositionOf(variantId)).isEqualByComparingTo("3");

            var reloadedDetail =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(reloadedDetail.getReceivedQuantity()).isEqualByComparingTo("3");
            assertThat(detailStatusName(detail.getId())).isEqualTo("Partial Received");
        }

        @Test
        @DisplayName("should accumulate two receipts, move the line to Received and promote the header")
        void secondReceiptCompletesTheLineAndPromotesTheHeader() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, "5", "In Transit");

            var first = createReceipt(receptionRequest(order, line(detail.getId(), "2")));
            var afterFirst =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(afterFirst.getReceivedQuantity()).isEqualByComparingTo("2");
            assertThat(detailStatusName(detail.getId())).isEqualTo("Partial Received");
            assertThat(orderStatusName(order.getId())).isEqualTo("In Transit");
            assertThat(first.receiptNumber()).endsWith("-01");

            var second = createReceipt(receptionRequest(order, line(detail.getId(), "3")));

            var afterSecond =
                    purchaseOrderDetailRepository.findById(detail.getId()).orElseThrow();
            assertThat(afterSecond.getReceivedQuantity()).isEqualByComparingTo("5");
            assertThat(detailStatusName(detail.getId())).isEqualTo("Received");
            assertThat(orderStatusName(order.getId())).isEqualTo("Received");
            assertThat(second.receiptNumber()).endsWith("-02");
            assertThat(goodsReceiptRepository.count()).isEqualTo(2);
            assertThat(ledgerRowsOf(detail.getProductVariant().getId()))
                    .as("one RECEIPT row per receipt line, and no legacy row beside it")
                    .hasSize(2);
        }

        @Test
        @DisplayName("should leave no receipt, ledger, balance or detail change when the second line over-receives")
        void overReceiptInSecondLineChangesNothing() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, "5", "In Transit");
            var secondLine = addReceivableLine(order, "5", "In Transit");
            var secondVariantId = secondLine.getProductVariant().getId();

            assertThatThrownBy(() -> createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "10"))))
                    .isInstanceOf(OverReceiptException.class);

            assertThat(goodsReceiptRepository.count()).isZero();
            assertThat(goodsReceiptItemRepository.count()).isZero();
            assertThat(ledgerMovementRows()).isZero();
            assertThat(balanceRows()).isZero();
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
            assertThat(ledgerRowsOf(secondVariantId))
                    .as("and the over-received line booked no movement: the ledger is the only record of a receipt, "
                            + "so a rejected document leaves nothing behind there")
                    .isEmpty();
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
            var detail = addReceivableLine(order, "5", "In Transit");
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

            var created = objectMapper.readTree(createResult.getResponse().getContentAsString());
            var receiptId = UUID.fromString(created.get("id").asText());
            var lineId = UUID.fromString(created.get("lines").get(0).get("id").asText());

            // The receipt count is a read of an armed table on the test thread <b>after</b> a request, and
            // `TenantScopeFilter` clears the thread's scope in its `finally`: read unscoped it answers zero
            // for the receipt the POST just committed, so the scope the write used is stated here again.
            long receiptsAfterPost = inTransaction(goodsReceiptRepository::count);
            assertThat(receiptsAfterPost).isEqualTo(1L);
            assertThat(goodsReceiptItemRepository.count()).isEqualTo(1);
            var ledgerRows = ledgerRowsOf(variantId);
            assertThat(ledgerRows).hasSize(1);
            assertThat(ledgerRows.getFirst().movementType()).isEqualTo("RECEIPT");
            assertThat(ledgerRows.getFirst().sourceType())
                    .as("read back through GET, the ledger's receipt identity is still the line")
                    .isEqualTo(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
            assertThat(ledgerRows.getFirst().sourceId())
                    .as("and the id the line holds, not the receipt's")
                    .isEqualTo(lineId);
            assertThat(ledgerRows.getFirst().quantity()).isEqualByComparingTo("3");

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
            var firstLine = addReceivableLine(order, "5", "In Transit");
            var secondLine = addReceivableLine(order, "5", "In Transit");
            var secondVariant = secondLine.getProductVariant();
            secondVariant.setEnabled(false);
            // A merge on a detached variant is an UPDATE on an armed table: unscoped it matches zero rows
            // and Hibernate reports an optimistic-lock failure instead of the fixture's intent.
            TenantFixtures.inTenant(company.getId(), () -> productVariantRepository.saveAndFlush(secondVariant));

            var historyRowsBefore = goodsReceiptHistoryRows();
            assertThatThrownBy(() -> createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "3"))))
                    .isInstanceOf(DisabledProductVariantException.class)
                    .hasMessageContaining("Reception line 1");

            assertNoReceptionEffects(order, historyRowsBefore, firstLine, secondLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(secondLine.getId())).isEqualTo("In Transit");
        }

        @Test
        @DisplayName(
                "should persist nothing when the second line's detail status cannot reach a received state: the write phase is unreachable for input errors, first line included")
        void unreceivableDetailStatusInSecondLineChangesNothing() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, "5", "In Transit");
            var secondLine = addReceivableLine(order, "5", "Cancelled");

            var historyRowsBefore = goodsReceiptHistoryRows();
            assertThatThrownBy(() -> createReceipt(
                            receptionRequest(order, line(firstLine.getId(), "3"), line(secondLine.getId(), "3"))))
                    .isInstanceOf(UnreceivableDetailStatusException.class)
                    .hasMessageContaining("Reception line 1");

            assertNoReceptionEffects(order, historyRowsBefore, firstLine, secondLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(secondLine.getId())).isEqualTo("Cancelled");
        }

        @Test
        @DisplayName(
                "should answer 400 naming the line and persist nothing when a terminal Received detail still has quantity outstanding")
        void terminalReceivedDetailIsRejectedBeforeAnyWrite() throws Exception {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var firstLine = addReceivableLine(order, "5", "In Transit");
            // A Received line with quantity outstanding is reachable through the public purchase-order
            // API, which accepts an arbitrary detail statusId and hard-codes receivedQuantity = 0.
            var terminalLine = addReceivableLine(order, "5", "Received");

            var request = new GoodsReceiptRequest(
                    order.getId(),
                    null,
                    "Reception",
                    List.of(line(firstLine.getId(), "3"), line(terminalLine.getId(), "3")));

            var historyRowsBefore = goodsReceiptHistoryRows();
            // The old "... || isDetailStatusReachable(current, \"Received\")" predicate admitted the
            // terminal Received line (the reachability walk is reflexive), so the write phase ran and
            // the mutator answered 409 after it. The corrected guard rejects the line pre-write, so the
            // receipt error category answers 400 and names the offending line.
            mockMvc.perform(post("/api/goods-receipts")
                            .with(adminPrincipal())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(containsString("Reception line 1")))
                    .andExpect(jsonPath("$.message")
                            .value(containsString(terminalLine.getId().toString())));

            assertNoReceptionEffects(order, historyRowsBefore, firstLine, terminalLine);
            assertThat(detailStatusName(firstLine.getId())).isEqualTo("In Transit");
            assertThat(detailStatusName(terminalLine.getId())).isEqualTo("Received");
        }

        /**
         * The write phase is unreachable for these input errors, so every effect table and every
         * touched row must be byte-for-byte untouched — for the rejected line and, critically, for
         * the earlier valid one, which a per-line loop could otherwise have half-applied.
         */
        private void assertNoReceptionEffects(
                PurchaseOrder order, long historyRowsBefore, PurchaseOrderDetail... lines) {
            assertThat(goodsReceiptRepository.count()).isZero();
            assertThat(goodsReceiptItemRepository.count()).isZero();
            assertThat(ledgerMovementRows()).isZero();
            assertThat(balanceRows()).isZero();
            for (var detail : lines) {
                assertThat(purchaseOrderDetailRepository
                                .findById(detail.getId())
                                .orElseThrow()
                                .getReceivedQuantity())
                        .isZero();
                assertThat(ledgerRowsOf(detail.getProductVariant().getId()))
                        .as("a rejected request books no movement for any line it named, including the valid one "
                                + "a per-line loop could have half-applied")
                        .isEmpty();
            }
            assertThat(orderStatusName(order.getId())).isEqualTo("In Transit");
            // ADR-0004 decision 5: a rejected request writes no receipt, so it writes no receipt history
            // either. Asked as "the count did not move" and not as "the count is zero", because
            // `status_transition` is append-only: the rows of every earlier case of this class are still
            // there, and a zero would be a statement about the whole table instead of about this request.
            // The scope is the receipt family, which is what keeps the fixture's own purchase-order rows out
            // of the question exactly as the assertions above exclude them.
            assertThat(goodsReceiptHistoryRows())
                    .as("a rejected request appends no receipt history row")
                    .isEqualTo(historyRowsBefore);
        }

        /**
         * How many {@code status_transition} rows the receipt family holds, read with this class's tenant
         * stated: the table is FORCEd, so an unscoped count answers zero for rows that exist.
         */
        private long goodsReceiptHistoryRows() {
            return inTransaction(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM status_transition WHERE document_type = 'GOODS_RECEIPT'", Long.class));
        }
    }

    @Nested
    @DisplayName("status history (ADR-0004 decision 5)")
    class StatusHistoryTests {

        @Test
        @DisplayName("should append exactly one creation row for the receipt it writes, with the caller as its actor")
        void createReceiptAppendsItsCreationRow() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, "5", "In Transit");

            var response = createReceipt(receptionRequest(order, line(detail.getId(), "3")));

            assertThat(response.status())
                    .as("the code beside the label, from the row the receipt was born with")
                    .isEqualTo("REGISTERED");
            assertThat(creationRowsOf(response.id()))
                    .as("one row and no predecessor: the receipt is born REGISTERED and is never moved, so this "
                            + "creation row is its whole history")
                    .containsExactly("<none>|REGISTERED|receiver-sub|<none>");
        }

        @Test
        @DisplayName("should leave no creation row when the receipt's transaction rolls back")
        void rolledBackReceiptLeavesNoCreationRow() {
            seedReceivingSettings();
            var order = createReceivableOrder("In Transit");
            var detail = addReceivableLine(order, "5", "In Transit");

            // The service's own transaction is joined here instead of started, so the receipt and its history
            // row are written and then discarded together: the pair is only a pair if both directions roll
            // back, and a row that outlived its document would be a history nobody could resolve.
            var template = new TransactionTemplate(transactionManager);
            var receiptId = new UUID[1];
            template.executeWithoutResult(status -> {
                TenantFixtures.stateTenantWithinTheOpenTransaction(company.getId(), jdbcTemplate);
                var response = goodsReceiptService.createReceipt(receptionRequest(order, line(detail.getId(), "3")));
                receiptId[0] = response.id();
                assertThat(jdbcTemplate.queryForList(CREATION_ROW_SQL, String.class, response.id()))
                        .as("the row is there while the transaction both writes share is open")
                        .hasSize(1);
                status.setRollbackOnly();
            });

            var receipts = inTransaction(goodsReceiptRepository::count);
            assertThat(receipts).as("the rolled-back receipt is not a receipt").isZero();
            assertThat(creationRowsOf(receiptId[0]))
                    .as("and it leaves no history behind either")
                    .isEmpty();
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
            saveReceipt(receiptFor(store, mine1, "GR-MINE-001"));
            saveReceipt(receiptFor(store, mine2, "GR-MINE-002"));

            var foreignStore = createOtherStore();
            var foreignOrder = persistedOrder(foreignStore, "PO-GR-LIST-FOREIGN");
            saveReceipt(receiptFor(foreignStore, foreignOrder, "GR-OTHER-001"));

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
            saveReceipt(receiptFor(store, mine, "GR-ADMIN-001"));
            var foreignStore = createOtherStore();
            var foreignOrder = persistedOrder(foreignStore, "PO-GR-ADMIN-B");
            saveReceipt(receiptFor(foreignStore, foreignOrder, "GR-ADMIN-002"));

            mockMvc.perform(get("/api/goods-receipts").with(adminPrincipal()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[*].receiptNumber").value(hasItems("GR-ADMIN-001", "GR-ADMIN-002")));
        }

        @Test
        @DisplayName("should return an empty page for a store-scoped principal with no store claim, leaking nothing")
        void emptyStoreSetYieldsEmptyPage() throws Exception {
            var mine = persistedOrder(store, "PO-GR-EMPTY");
            saveReceipt(receiptFor(store, mine, "GR-EMPTY-001"));

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
            var detail = addReceivableLine(order, "5", "In Transit");
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
            assertThat(ledgerMovementRows())
                    .as("and no movement: the write phase never started, so the ledger booked nothing")
                    .isZero();
        }

        @Test
        @DisplayName("should reject a foreign-store principal reading another store's receipt with 403")
        void readForeignStoreReceiptIsForbidden() throws Exception {
            var order = persistedOrder(store, "PO-GR-FOREIGN-READ");
            var receipt = saveReceipt(receiptFor(store, order, "GR-FOREIGN-READ-001"));
            var chain = storeChain();

            mockMvc.perform(get("/api/goods-receipts/{id}", receipt.getId())
                            .with(storeScopedJwt(chain, UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }

        /**
         * Record S1 of advisory A2: the list hides a disabled receipt on every branch while the detail
         * admits it. Both halves are asserted over real JWTs and PostgreSQL, because a mocked
         * repository cannot see whether a JPQL predicate is still there.
         *
         * <p>The searched branch is a separate JPQL string with its own predicate, so it is asserted
         * separately: searching for the disabled receipt's own order number has to find nothing.</p>
         */
        @Test
        @DisplayName(
                "should hide a disabled receipt from the unscoped list and its searched branch while an enabled sibling stays visible")
        void disabledReceiptIsHiddenFromBothUnscopedListBranches() throws Exception {
            var disabledOrder = persistedOrder(store, "PO-GR-DISABLED-A");
            var disabled = receiptFor(store, disabledOrder, "GR-DISABLED-001");
            disabled.setEnabled(false);
            saveReceipt(disabled);
            var enabledOrder = persistedOrder(store, "PO-GR-DISABLED-B");
            saveReceipt(receiptFor(store, enabledOrder, "GR-DISABLED-002"));

            mockMvc.perform(get("/api/goods-receipts").with(adminPrincipal()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].receiptNumber").value("GR-DISABLED-002"));

            // The disabled receipt settles PO-GR-DISABLED-A, so its own order number is the search
            // term that would surface it if findBySearchTerm lost `gr.enabled = true`.
            mockMvc.perform(get("/api/goods-receipts")
                            .param("search", "PO-GR-DISABLED-A")
                            .with(adminPrincipal()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content").isEmpty());
        }

        /**
         * Record S1 of advisory A2 on the store-scoped side: the two derived/&#64;Query finders that
         * carry {@code enabled = true} next to the store predicate. A store principal must not see the
         * disabled receipt of its own store, searched or unsearched.
         */
        @Test
        @DisplayName("should hide a disabled receipt from the store-scoped list branch, searched and unsearched")
        void disabledReceiptIsHiddenFromTheStoreScopedBranch() throws Exception {
            var disabledOrder = persistedOrder(store, "PO-GR-DISABLED-SCOPED-A");
            var disabled = receiptFor(store, disabledOrder, "GR-SCOPED-DISABLED-001");
            disabled.setEnabled(false);
            saveReceipt(disabled);
            var enabledOrder = persistedOrder(store, "PO-GR-DISABLED-SCOPED-B");
            saveReceipt(receiptFor(store, enabledOrder, "GR-SCOPED-DISABLED-002"));

            var chain = storeChain();
            var storeScoped = storeScopedJwt(chain, chain.storeId());

            mockMvc.perform(get("/api/goods-receipts").with(storeScoped))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].receiptNumber").value("GR-SCOPED-DISABLED-002"));

            mockMvc.perform(get("/api/goods-receipts")
                            .param("search", "PO-GR-DISABLED-SCOPED-A")
                            .with(storeScopedJwt(chain, chain.storeId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content").isEmpty());
        }

        /**
         * The other half of the rule, and the reason the list filter is not a bug: a disabled receipt
         * stays readable by id, with its flag in the body, exactly as {@code CustomerService} reads a
         * soft-deleted customer. If this ever has to change, it changes as a decision, not by drift.
         */
        @Test
        @DisplayName("should still admit a disabled receipt by id, with its enabled flag in the body")
        void disabledReceiptRemainsReadableById() throws Exception {
            var order = persistedOrder(store, "PO-GR-DISABLED-DETAIL");
            var receipt = receiptFor(store, order, "GR-DISABLED-DETAIL-001");
            receipt.setEnabled(false);
            var disabled = saveReceipt(receipt);

            mockMvc.perform(get("/api/goods-receipts/{id}", disabled.getId()).with(adminPrincipal()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(disabled.getId().toString()))
                    .andExpect(jsonPath("$.receiptNumber").value("GR-DISABLED-DETAIL-001"))
                    .andExpect(jsonPath("$.enabled").value(false));
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
            return TenantFixtures.inTenant(company.getId(), () -> {
                var identity = allocateIdentity(DocumentType.PURCHASE_ORDER, company.getId());
                var order = purchaseOrderRepository.saveAndFlush(PurchaseOrder.builder()
                        .orderNumber(orderNumber)
                        .docType(DocumentType.PURCHASE_ORDER.code())
                        .seqPeriod(identity.period())
                        .seqValue(identity.seqValue())
                        .docNumber(identity.docNumber())
                        .supplier(supplier)
                        .companyStore(companyStore)
                        // V43: the store may be the other one of the same zone; its company is still this
                        // fixture's, and that is the owner the order stores.
                        .company(company)
                        .paymentMethod(paymentMethod)
                        .status(draftStatus)
                        .comments("List order")
                        .enabled(true)
                        .build());
                recordCreation(
                        "PURCHASE_ORDER", order.getId(), order.getStatus().name());
                return order;
            });
        }

        private GoodsReceipt receiptFor(CompanyStore companyStore, PurchaseOrder order, String receiptNumber) {
            return GoodsReceipt.builder()
                    .receiptNumber(receiptNumber)
                    .companyId(order.getCompany().getId())
                    .docType(DocumentType.GOODS_RECEIPT.code())
                    .docNumber(receiptNumber)
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

    /**
     * An admin principal that states this class's company.
     *
     * <p>{@code /api/goods-receipts} names no company in its path, so the tenant comes from the token's
     * claims (T5b): a token carrying none states none, and every policy-armed table the request touches
     * then answers zero rows or fails to resolve a lazy parent. The claim belongs to the fixture and not to
     * the assertions: these cases are about store scoping, and an administrator is admitted for its company
     * with or without a per-company claim.</p>
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor adminPrincipal() {
        return jwt().authorities(ROLE_LC_ADMIN)
                .jwt(builder -> builder.claim("preferred_username", "admin")
                        .claim("company_id", company.getId().toString()));
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
