package com.lifecontrol.api.salesorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.state.InvalidStatusTransitionException;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.common.state.StatusTransitionRecorder;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.inventory.exception.InsufficientStockException;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine.SaleChange;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalePaymentRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.salesorder.exception.SalesOrderAlreadyFinalizedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotModifiableException;
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderStoreReassignmentNotAllowedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderTenderInconsistentException;
import com.lifecontrol.api.salesorder.exception.SalesOrderTenderNotCashException;
import com.lifecontrol.api.salesorder.model.SalePayment;
import com.lifecontrol.api.salesorder.model.SalePaymentDirection;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.salesorder.repository.SalePaymentRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.exception.ShiftNotOpenException;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import com.lifecontrol.api.support.TenantFixtures;
import com.lifecontrol.api.tax.service.TaxRateResolution;
import com.lifecontrol.api.tax.service.TaxRateService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalesOrderService Tests")
class SalesOrderServiceTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderItemRepository itemRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private StockLedgerEngine stockLedgerEngine;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    /**
     * The payments of a document. This unit's charge writes exactly one and asks the table what the document has
     * paid (ADR-0002 M3), so the mock is the sum's oracle here and the real {@code NOT EXISTS} over
     * {@code sale_payment_void} is proved against a database by the integration suite.
     */
    @Mock
    private SalePaymentRepository salePaymentRepository;

    /**
     * The payments the transaction under test appended.
     *
     * <p><b>Why a list and not a fixed stub.</b> The charge writes a row and then asks the same table what the
     * document has paid, which is what a database does inside one transaction: the sum includes the insert that
     * just happened. A stubbed constant would answer a different question — it would let a case prove the
     * transition while proving nothing about the amount that made it legal (ADR-0002 M3).</p>
     */
    private final List<SalePayment> appendedPayments = new ArrayList<>();

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private DocumentSequenceAllocator documentSequenceAllocator;

    /** The one writer of {@code status_transition}: every state change of this module goes through it. */
    @Mock
    private StatusTransitionRecorder statusTransitionRecorder;

    /** The catalog's code, which is the only way a request's {@code statusId} becomes a state. */
    @Mock
    private SalesOrderStatusCatalog statusCatalog;

    /**
     * The identity map the response mapper leaves to reach a line's derived money.
     *
     * <p><b>Why it must be here at all.</b> {@code subtotal}, the tax triple and {@code final_price} are written
     * by the schema (V81, V85) and mapped read-only, so the instance a write leaves behind carries none of them
     * and the mapper refreshes the row before it reports the line. A mock cannot run a trigger: the value is
     * pinned by {@code SalesOrderIntegrationTest}, and this suite pins that the service <b>asks</b>.</p>
     */
    @Mock
    private EntityManager entityManager;

    /**
     * The real derivation, not a mock: the store fixture carries the whole
     * company &rarr; country &rarr; region &rarr; zone &rarr; store chain, so the tenant every
     * variant lookup asks for is the one the fixture describes.
     */
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver = new CompanyStoreOwnerResolver();

    /**
     * The resolver a line's rate comes from (ADR-0002 decision 6). A mock, because what a rate row is and which
     * one applies on a date is {@code TaxRateServiceTest}'s and {@code TaxRateMigrationIntegrationTest}'s; what is
     * pinned here is only that the sale asks it — with the document's date and the product's code — and what the
     * sale does with each of its two answers.
     */
    @Mock
    private TaxRateService taxRateService;

    private SalesOrderService salesOrderService;

    private UUID orderId;
    private UUID customerId;
    private UUID companyStoreId;
    private UUID shiftId;
    private UUID variantId;
    private UUID itemId;
    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private CompanyStore testCompanyStore;

    private Status borradorStatus;
    private Status activoStatus;
    private Status enviadaStatus;
    private Status canceladaStatus;
    private Status cerradaStatus;
    private Status pendienteItemStatus;
    private Status agregadoItemStatus;
    private Status canceladoItemStatus;

    private StatusType salesOrderType;
    private StatusType salesOrderItemType;

    private SalesOrder testOrder;
    private SalesOrderRequest testOrderRequest;
    private SalesOrderItem testItem;
    private SalesOrderItemRequest testItemRequest;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        appendedPayments.clear();

        // ADR-0002 decision 1: the total is rounded to the document's currency, so every case that writes a
        // breakdown asks the catalog. MXN's minor unit is 2, which is the fixture's currency (TenantFixtures).
        lenient().when(salesOrderRepository.findCurrencyMinorUnits(any())).thenReturn(Optional.of(2));

        // The document's currency is a column no entity maps (V80's trigger is its only writer), so a response
        // reads it: the fixture answers the code every fixture company states.
        lenient()
                .when(salesOrderRepository.findCurrencyCode(any()))
                .thenReturn(Optional.of(TenantFixtures.SEED_CURRENCY));

        // M10's lock reads the same row the unlocked read does — in the database it is one row, so a fixture
        // that answered two different objects would be describing a state the schema cannot hold. A case that
        // wants to say something about the locked read states its own answer and wins over this one.
        lenient()
                .when(salesOrderRepository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> salesOrderRepository.findById(invocation.getArgument(0)));

        // The line's tax snapshot reads the code off the sold variant's product, and `NA` is the column's own
        // default since V89 — so every fixture whose product states nothing keeps the zero the schema gives it and
        // no case here depends on a rate table. A case that wants a taxed code states its own answer and wins.
        lenient()
                .when(productVariantRepository.findProductTaxCodeByVariantId(any()))
                .thenReturn(Optional.of("NA"));

        // M2: the parts are summed from the schema, not from the list the caller holds, so a document with no
        // line answers an empty sum — the state every fixture starts from.
        lenient().when(itemRepository.sumMoneyParts(any())).thenReturn(List.of());

        // The charge appends one row and then asks the table what the document has paid. These two stubs are
        // that pair: `save` keeps the row, `netPaid` adds what was kept.
        lenient().when(salePaymentRepository.save(any(SalePayment.class))).thenAnswer(invocation -> {
            var payment = invocation.getArgument(0, SalePayment.class);
            appendedPayments.add(payment);
            return payment;
        });
        lenient()
                .when(salePaymentRepository.netPaid(any(), any()))
                .thenAnswer(invocation ->
                        appendedPayments.stream().map(SalePayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        // The charge resolves every method its payments name, because ADR-0002 M4 reads the method's `is_cash`
        // and a boolean that only says "the row exists" cannot answer whether change is allowed. A card by
        // default, so a case that states no tender charges through a method that could never have taken one; a
        // case that wants a cash method states its own answer and wins over this one.
        lenient()
                .when(paymentMethodRepository.findById(any()))
                .thenAnswer(invocation -> Optional.of(PaymentMethod.builder()
                        .id(invocation.getArgument(0, UUID.class))
                        .paymentMethodName("Tarjeta")
                        .paymentMethodShortName("TARJETA")
                        .enabled(true)
                        .isCash(false)
                        .build()));
        customerId = UUID.randomUUID();
        companyStoreId = UUID.randomUUID();
        shiftId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        itemId = UUID.randomUUID();

        // The store guard derives company -> country -> region -> zone -> store from the store
        // itself, because the request carries only the store id, so the fixture must carry the
        // whole chain.
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        var company = Company.builder()
                .id(companyId)
                .companyKey("TEST-KEY")
                .companyName("Test Company")
                .rfc("TEST123456ABC")
                .enabled(true)
                .build();
        var companyCountry =
                CompanyCountry.builder().id(companyCountryId).company(company).build();
        var region = CompanyRegion.builder()
                .id(regionId)
                .companyCountry(companyCountry)
                .regionCode("01")
                .regionName("Test Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(zoneId)
                .companyRegion(region)
                .zoneCode("01")
                .zoneName("Test Zone")
                .enabled(true)
                .build();
        testCompanyStore = CompanyStore.builder()
                .id(companyStoreId)
                .companyZone(zone)
                .storeName("Test Store")
                .enabled(true)
                .build();

        salesOrderType = StatusType.builder()
                .id(UUID.randomUUID())
                .statusTypeName("SALES_ORDER")
                .enabled(true)
                .build();

        salesOrderItemType = StatusType.builder()
                .id(UUID.randomUUID())
                .statusTypeName("SALES_ORDER_ITEM")
                .enabled(true)
                .build();

        borradorStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Draft")
                .code("DRAFT")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        activoStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Active")
                .code("ACTIVE")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        enviadaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pending")
                .code("PENDING")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        canceladaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelled")
                .code("CANCELLED")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        cerradaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Completed")
                .code("COMPLETED")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        pendienteItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pending")
                .code("PENDING")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        agregadoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Added")
                .code("ADDED")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        canceladoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelled")
                .code("CANCELLED")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        var now = LocalDateTime.now();

        testOrder = SalesOrder.builder()
                .id(orderId)
                .orderNumber("SO-20260610-00001")
                .companyId(companyId)
                .customerId(customerId)
                .companyStoreId(companyStoreId)
                .shiftId(shiftId)
                .userId("user123")
                .orderDate(now)
                .status(SalesOrderState.DRAFT)
                .totalAmount(BigDecimal.ZERO)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testOrderRequest = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", null);

        // Every response projects its pair from the code, so the fixture answers the projection by (family,
        // code): the same pairing the rows above carry, read from the other side. Lenient because a case that
        // fails before its response is built has not proved anything about the label.
        var catalogRows = new java.util.HashMap<String, Status>();
        catalogRows.put("SALES_ORDER/DRAFT", borradorStatus);
        catalogRows.put("SALES_ORDER/ACTIVE", activoStatus);
        catalogRows.put("SALES_ORDER/PENDING", enviadaStatus);
        catalogRows.put("SALES_ORDER/CANCELLED", canceladaStatus);
        catalogRows.put("SALES_ORDER/COMPLETED", cerradaStatus);
        catalogRows.put("SALES_ORDER_ITEM/PENDING", pendienteItemStatus);
        catalogRows.put("SALES_ORDER_ITEM/ADDED", agregadoItemStatus);
        catalogRows.put("SALES_ORDER_ITEM/CANCELLED", canceladoItemStatus);
        lenient()
                .when(statusRepository.findByTypeNameAndCode(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(catalogRows.get(
                        invocation.getArgument(0, String.class) + "/" + invocation.getArgument(1, String.class))));

        salesOrderService = new SalesOrderService(
                salesOrderRepository,
                itemRepository,
                salePaymentRepository,
                customerRepository,
                companyStoreRepository,
                companyStoreOwnerResolver,
                shiftRepository,
                productVariantRepository,
                taxRateService,
                stockLedgerEngine,
                statusRepository,
                paymentMethodRepository,
                currentUserContext,
                documentSequenceAllocator,
                statusTransitionRecorder,
                statusCatalog,
                entityManager);

        testItem = SalesOrderItem.builder()
                .id(itemId)
                .salesOrderId(orderId)
                .productVariantId(variantId)
                .quantity(new BigDecimal("2.00"))
                .listPrice(new BigDecimal("100.00"))
                .discountApplied(new BigDecimal("10.00"))
                .status(SalesOrderItemState.PENDING)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testItemRequest = new SalesOrderItemRequest(
                null, variantId, new BigDecimal("2.00"), new BigDecimal("100.00"), new BigDecimal("10.00"), null);
    }

    private Shift openShift(String status) {
        return Shift.builder()
                .id(shiftId)
                .companyStoreId(companyStoreId)
                .userId("user123")
                .status(status)
                .enabled(true)
                .build();
    }

    /**
     * A live line (enabled, {@code Pending}) that belongs to some other order, so the only check
     * that can refuse an item-level write on it is the one that reads {@code salesOrderId} (F-5).
     */
    private SalesOrderItem foreignLine() {
        return SalesOrderItem.builder()
                .id(itemId)
                .salesOrderId(UUID.randomUUID())
                .productVariantId(variantId)
                .quantity(new BigDecimal("2.00"))
                .listPrice(new BigDecimal("100.00"))
                .discountApplied(BigDecimal.ZERO)
                .status(SalesOrderItemState.PENDING)
                .enabled(true)
                .build();
    }

    private void verifyReversed(UUID itemId) {
        verify(stockLedgerEngine)
                .applySaleBatch(
                        eq("SALES_ORDER_ITEM"),
                        any(),
                        argThat((List<SaleChange> changes) -> changes.stream()
                                .anyMatch(change -> change.reversal()
                                        && change.referenceId().equals(itemId))));
    }

    /**
     * Asserts that nothing at all reached the stock engine. Since ADR-0002 decision 7 the charge is the only
     * writer of stock: a create, a line edit, a line status change and a deletion of a document that was never
     * charged all have to ask for no movement, which is what makes the charge the single moment the stock moves.
     */
    private void verifyNoStockWork() {
        verify(stockLedgerEngine, never()).applySaleBatch(any(), any(), any());
    }

    /**
     * The one aggregate row {@code sumMoneyParts} answers with for a document holding lines: their summed
     * subtotal, their summed discount and their summed tax. ADR-0002 M2 reads the parts from the schema and
     * not from the list the caller holds, so a case that states the document's money stubs the row the schema
     * would return instead of the line list. A {@code null} column is the aggregate's answer when no enabled
     * line remains, which the writer reads as zero money.
     */
    private static List<Object[]> moneyPartsRow(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal) {
        return Collections.singletonList(new Object[] {subtotal, discountTotal, taxTotal});
    }

    /**
     * States the document's money as a previous recomputation left it, so that the recalculation a write runs
     * is observable: a test that started from the zero fixture would prove nothing by reading zero back.
     */
    private static void givenBreakdown(
            SalesOrder order,
            String subtotal,
            String discountTotal,
            String taxTotal,
            String roundingAdjustment,
            String totalAmount) {
        order.setSubtotal(new BigDecimal(subtotal));
        order.setDiscountTotal(new BigDecimal(discountTotal));
        order.setTaxTotal(new BigDecimal(taxTotal));
        order.setRoundingAdjustment(new BigDecimal(roundingAdjustment));
        order.setTotalAmount(new BigDecimal(totalAmount));
    }

    // ─────────────────────────────────────────────
    // the response's status pair
    // ─────────────────────────────────────────────

    /**
     * ADR-0004 step 9, asked from the reader's side: {@code statusId}/{@code statusName} are a projection of
     * the code and not a stored pointer.
     *
     * <p>The row stores the constant; the catalog row is found by {@code (family, code)}; the pair the screen
     * reads is that row's. The fixture renames the label to something no code path spells, which is what makes
     * this an assertion about derivation instead of about a lookup that happens to agree with the fixture: a
     * reader that still joined a stored {@code status_id} would answer the old name, and the state itself is
     * untouched by the rename — which is exactly the property ADR-0004 decision 7 buys.</p>
     */
    @Test
    @DisplayName("a response derives statusId and statusName from the code, and a renamed label changes only the label")
    void responseProjectsTheStatusPairFromTheCode() {
        var renamedDraft = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pedido")
                .code("DRAFT")
                .statusType(salesOrderType)
                .enabled(true)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
        when(statusRepository.findByTypeNameAndCode("SALES_ORDER", "DRAFT")).thenReturn(Optional.of(renamedDraft));

        var response = salesOrderService.getSalesOrderById(orderId);

        assertThat(response.statusId())
                .as("the pair comes from the catalog row of the code")
                .isEqualTo(renamedDraft.getId());
        assertThat(response.statusName())
                .as("a rename is a label: it changes what the screen says and nothing about the document")
                .isEqualTo("Pedido");
        assertThat(response.status())
                .as("and the code is the authority, unchanged by the rename")
                .isEqualTo("DRAFT");
    }

    // ─────────────────────────────────────────────
    // getAllSalesOrders
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getAllSalesOrders")
    class GetAllSalesOrdersTests {

        @Test
        @DisplayName("should return paginated sales orders without search")
        void getAllSalesOrders_Paginated() {
            var pageable = PageRequest.of(0, 12);
            var orders = List.of(testOrder);
            var expectedPage = new PageImpl<>(orders, pageable, 1);

            when(salesOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable))
                    .thenReturn(expectedPage);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            Page<SalesOrderResponse> result = salesOrderService.getAllSalesOrders(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(orderId);
            assertThat(result.getContent().get(0).orderNumber()).isEqualTo("SO-20260610-00001");
            assertThat(result.getContent().get(0).statusName()).isEqualTo("Draft");
            assertThat(result.getContent().get(0).totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getContent().get(0).enabled()).isTrue();
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(salesOrderRepository).findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        @Test
        @DisplayName("should return filtered results with search term")
        void getAllSalesOrders_WithSearch() {
            var pageable = PageRequest.of(0, 12);
            var orders = List.of(testOrder);
            var expectedPage = new PageImpl<>(orders, pageable, 1);
            var searchTerm = "SO-20260610";

            when(salesOrderRepository.findBySearchTerm(searchTerm, pageable)).thenReturn(expectedPage);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            Page<SalesOrderResponse> result = salesOrderService.getAllSalesOrders(pageable, searchTerm);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).orderNumber()).isEqualTo("SO-20260610-00001");
            verify(salesOrderRepository).findBySearchTerm(searchTerm, pageable);
        }

        @Test
        @DisplayName("should return empty page when no sales orders exist")
        void getAllSalesOrders_EmptyPage() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<SalesOrder>(List.of(), pageable, 0);

            when(salesOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable))
                    .thenReturn(expectedPage);

            Page<SalesOrderResponse> result = salesOrderService.getAllSalesOrders(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    // getSalesOrderById
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getSalesOrderById")
    class GetSalesOrderByIdTests {

        @Test
        @DisplayName("should return sales order when found")
        void getSalesOrderById_Found() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            SalesOrderResponse result = salesOrderService.getSalesOrderById(orderId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.orderNumber()).isEqualTo("SO-20260610-00001");
            assertThat(result.customerId()).isEqualTo(customerId);
            assertThat(result.companyStoreId()).isEqualTo(companyStoreId);
            assertThat(result.statusName()).isEqualTo("Draft");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when not found")
        void getSalesOrderById_NotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.getSalesOrderById(orderId))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");
        }

        /**
         * The two halves of T7a's sheet that no getter of {@link SalesOrder} can answer: the currency, which the
         * entity deliberately does not map because {@code V80}'s trigger is its only writer, and the payments,
         * which are rows of their own (ADR-0002 decision 5).
         *
         * <p><b>Why this case exists at the unit level and what it does not claim.</b> A mock cannot run the
         * trigger and cannot make the breakdown add up, so the numbers are pinned by
         * {@code SalesOrderIntegrationTest}. What it does pin is the shape: the mapper asks the narrow column
         * query for the code instead of returning null, reports the four parts the entity carries, and answers
         * the payment rows oldest first with the tender pair as stored — not a zero, which would be a customer
         * who handed over nothing.</p>
         */
        @Test
        @DisplayName("the response states the document's currency and its payments, which no getter can answer")
        void getSalesOrderById_StatesCurrencyAndPayments() {
            var methodId = UUID.randomUUID();
            var payment =
                    SalePayment.charge(companyId, orderId, methodId, new BigDecimal("200.0000"), null, null, "seller");
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.findCurrencyCode(orderId)).thenReturn(Optional.of(TenantFixtures.SEED_CURRENCY));
            when(salePaymentRepository.findBySalesOrderIdOrderByPaidAtAsc(orderId))
                    .thenReturn(List.of(payment));

            var result = salesOrderService.getSalesOrderById(orderId);

            assertThat(result.currency())
                    .as("the document's own code, read from the row because the entity does not map the column")
                    .isEqualTo(TenantFixtures.SEED_CURRENCY);
            assertThat(result.subtotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.discountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.taxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.roundingAdjustment()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.payments()).hasSize(1);

            var charge = result.payments().get(0);
            assertThat(charge.direction()).isEqualTo("CHARGE");
            assertThat(charge.methodId()).isEqualTo(methodId);
            assertThat(charge.amount()).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(charge.actor()).isEqualTo("seller");
            assertThat(charge.tenderedAmount())
                    .as("an absent tender is answered absent, never as zero")
                    .isNull();
            assertThat(charge.changeAmount()).isNull();
            verify(salesOrderRepository).findCurrencyCode(orderId);
            verify(salePaymentRepository).findBySalesOrderIdOrderByPaidAtAsc(orderId);
        }
    }

    // ─────────────────────────────────────────────
    // createSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createSalesOrder")
    class CreateSalesOrderTests {

        @Test
        @DisplayName("should create sales order with Draft status and a per-tenant allocated number")
        void createSalesOrder_Success() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(openShift("ABIERTO")));
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.SALES_ORDER), anyString()))
                    .thenReturn(1L);
            var captor = ArgumentCaptor.forClass(SalesOrder.class);
            when(salesOrderRepository.save(captor.capture())).thenAnswer(invocation -> {
                var order = invocation.getArgument(0, SalesOrder.class);
                order.setId(orderId);
                return order;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            SalesOrderResponse result = salesOrderService.createSalesOrder(testOrderRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.orderNumber()).startsWith("SO-");
            assertThat(result.orderNumber()).containsPattern("SO-\\d{6}-00001");
            assertThat(result.statusName()).isEqualTo("Draft");
            assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.enabled()).isTrue();
            verify(salesOrderRepository).save(any(SalesOrder.class));

            // ADR-0003 decisions 4 and 5: the row carries the identity in parts, the rendered string is
            // written once, and the period is the one the frozen order date produces.
            var created = captor.getValue();
            assertThat(created.getOrderNumber()).isEqualTo("SO-" + created.getSeqPeriod() + "-00001");
            assertThat(created.getDocNumber()).isEqualTo(created.getOrderNumber());
            assertThat(created.getDocType()).isEqualTo("SALES_ORDER");
            assertThat(created.getSeqValue()).isEqualTo(1L);
            assertThat(created.getCompanyId()).isEqualTo(companyId);
            assertThat(created.getSeqPeriod())
                    .isEqualTo(DocumentNumberRenderer.periodOf(
                            created.getOrderDate().toLocalDate()));
            verify(documentSequenceAllocator).allocate(companyId, DocumentType.SALES_ORDER, created.getSeqPeriod());
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer FK invalid")
        void createSalesOrder_CustomerNotFound() {
            when(customerRepository.existsById(customerId)).thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when store FK invalid")
        void createSalesOrder_CompanyStoreNotFound() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessageContaining("Store not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when shift FK invalid")
        void createSalesOrder_ShiftNotFound() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw ShiftNotOpenException when shift exists but is closed")
        void createSalesOrder_ShiftClosed_ThrowsException() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(openShift("CERRADO")));

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(ShiftNotOpenException.class)
                    .hasMessageContaining(shiftId.toString())
                    .hasMessageContaining("CERRADO");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when the caller holds no grant for the store")
        void createSalesOrder_StoreOutsideCallerScope_Throws403() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(AccessDeniedException.class);

            // The guard must receive the chain derived from the store, not the raw store id.
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);
            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrder")
    class UpdateSalesOrderTests {

        @Test
        @DisplayName("should update sales order fields and return response")
        void updateSalesOrder_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            SalesOrderResponse result = salesOrderService.updateSalesOrder(orderId, testOrderRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.customerId()).isEqualTo(customerId);
            assertThat(result.companyStoreId()).isEqualTo(companyStoreId);
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when the caller holds no grant for the store")
        void updateSalesOrder_StoreOutsideCallerScope_Throws403() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, companyStoreId);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, testOrderRequest))
                    .isInstanceOf(AccessDeniedException.class);

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should reject a store reassignment while the order has active items")
        void updateSalesOrder_StoreReassignmentWithActiveItems_ThrowsException() {
            var requestedStoreId = UUID.randomUUID();
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(requestedStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            var request = new SalesOrderRequest(customerId, requestedStoreId, shiftId, "user123", null);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, request))
                    .isInstanceOf(SalesOrderStoreReassignmentNotAllowedException.class)
                    .hasMessageContaining("cannot be reassigned from company store");

            // The rejection must leave the order and the engine untouched.
            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should allow a store reassignment when the order has no active items")
        void updateSalesOrder_StoreReassignmentWithoutActiveItems_Succeeds() {
            var requestedStoreId = UUID.randomUUID();
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(requestedStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);

            var request = new SalesOrderRequest(customerId, requestedStoreId, shiftId, "user123", null);

            var result = salesOrderService.updateSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void updateSalesOrder_NotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, testOrderRequest))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should reject modifying an order in a terminal status without touching stock")
        void updateSalesOrder_TerminalOrder_ThrowsException() {
            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, testOrderRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already CANCELLED")
                    .hasMessageContaining("cannot be modified");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should not sell a cancelled line re-enabled with a changed quantity through the order PUT")
        void updateSalesOrder_ReEnableCancelledLineWithChangedQuantity_NoStockMovement() {
            var cancelledLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();
            var lineRequest = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(cancelledLine));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(cancelledLine));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(cancelledLine);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(lineRequest));
            salesOrderService.updateSalesOrder(orderId, request);

            // The terminal item status is the only signal that the line holds nothing, so the guard
            // must read the "SALES_ORDER_ITEM"/"Cancelled" status. With the lookup broken the line
            // would look like it still held 4 and the engine would deduct the 1-unit delta.
            verifyNoInteractions(stockLedgerEngine);
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrder")
    class DeleteSalesOrderTests {

        @Test
        @DisplayName("should soft-delete sales order and all items without stock work")
        void deleteSalesOrder_UnchargedOrder_BooksNoStock() {
            var variantId2 = UUID.randomUUID();
            var item2Id = UUID.randomUUID();

            var item2 = SalesOrderItem.builder()
                    .id(item2Id)
                    .salesOrderId(orderId)
                    .productVariantId(variantId2)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem, item2));

            salesOrderService.deleteSalesOrder(orderId);

            // No `findById` verification here, and none is needed: the stub above is the pin. The deletion reads the
            // row WITH the lock (the charge takes the order's lock and then the stock positions, so the reverse
            // order would deadlock), and a regression to the unlocked read would leave that stub unused — which the
            // suite's strict stubbing refuses as an `UnnecessaryStubbingException` instead of passing quietly.
            verify(salesOrderRepository).save(any(SalesOrder.class));
            verify(itemRepository).findBySalesOrderId(orderId);
            verify(itemRepository, times(2)).save(any(SalesOrderItem.class));
            // A document that was never charged holds no stock, so deleting it gives nothing back
            // (ADR-0002 decision 7).
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should reverse every live line once when deleting an order its charge booked")
        void deleteSalesOrder_ChargedOrder_ReversesEveryLiveLine() {
            var completedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(completedOrder));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));

            salesOrderService.deleteSalesOrder(orderId);

            // The charge is what booked the stock, so the deletion of a charged document is the one place a
            // reversal exists at all.
            verifyReversed(itemId);
        }

        @Test
        @DisplayName("should book no stock when deleting an already-cancelled order")
        void deleteSalesOrder_AlreadyCancelled_NoStockRestoration() {
            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));

            salesOrderService.deleteSalesOrder(orderId);

            verify(salesOrderRepository).save(any(SalesOrder.class));
            verify(itemRepository).save(any(SalesOrderItem.class));
            // A cancelled document never charged, so it holds no stock and delete asks the engine for nothing.
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should book no stock when cancelling then deleting an uncharged order")
        void deleteSalesOrder_AfterCancel_BooksNoStock() {
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            // ── Cancel transition ──
            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(canceladaStatus.getId())).thenReturn(SalesOrderState.CANCELLED);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(cancelledOrder);

            salesOrderService.updateSalesOrderStatus(orderId, statusRequest);
            verifyNoStockWork();

            // ── Delete after cancel ──
            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));

            salesOrderService.deleteSalesOrder(orderId);

            // Neither step books a movement: the document never reached the charge.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void deleteSalesOrder_NotFound_ThrowsException() {
            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrder(orderId))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }
    }

    // ─────────────────────────────────────────────
    // enableSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("enableSalesOrder")
    class EnableSalesOrderTests {

        @Test
        @DisplayName("should re-enable a disabled sales order")
        void enableSalesOrder_Success() {
            var disabledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(LocalDateTime.now())
                    .status(SalesOrderState.DRAFT)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(false)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(disabledOrder));
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            SalesOrderResponse result = salesOrderService.enableSalesOrder(orderId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.enabled()).isTrue();
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrderStatus
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrderStatus")
    class UpdateSalesOrderStatusTests {

        @Test
        @DisplayName("should transition Draft → Active successfully")
        void updateStatus_DraftToActive_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(activoStatus.getId());

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            // The target is resolved through the catalog's code and not through its label (ADR-0004
            // decision 7): the service asks the catalog one question and the enum answers the rest.
            when(statusCatalog.requireOrderState(activoStatus.getId())).thenReturn(SalesOrderState.ACTIVE);

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // toResponse will query the NEW status (activoStatus)

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Active");
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should transition Draft → Cancelled without stock work")
        void updateStatus_DraftToCancelled_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(canceladaStatus.getId())).thenReturn(SalesOrderState.CANCELLED);

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            // A cancellation leaves a document that never charged, so it has nothing to give back.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Draft → Completed")
        void updateStatus_DraftToCompleted_ThrowsInvalidTransition() {
            var statusRequest = new UpdateSalesOrderStatusRequest(cerradaStatus.getId());

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(cerradaStatus.getId())).thenReturn(SalesOrderState.COMPLETED);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("Invalid status transition")
                    .hasMessageContaining("DRAFT")
                    .hasMessageContaining("COMPLETED");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException on wrong status type")
        void updateStatus_WrongType_ThrowsIllegalArgumentException() {
            // status with SALES_ORDER_ITEM type instead of SALES_ORDER
            var wrongTypeStatus = Status.builder()
                    .id(UUID.randomUUID())
                    .statusName("Pending")
                    .statusType(salesOrderItemType)
                    .enabled(true)
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(wrongTypeStatus.getId());

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            // The catalog is the one that refuses a row of another family, and the service must not swallow
            // it: the real refusal is asserted against the query in `SalesOrderStatusCatalogTest`.
            when(statusCatalog.requireOrderState(wrongTypeStatus.getId()))
                    .thenThrow(new IllegalArgumentException(
                            "The provided status does not belong to type SALES_ORDER: catalog status 'Pending' ("
                                    + wrongTypeStatus.getId() + ") belongs to SALES_ORDER_ITEM"));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SALES_ORDER");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void updateStatus_OrderNotFound_ThrowsException() {
            var statusRequest = new UpdateSalesOrderStatusRequest(enviadaStatus.getId());
            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");
        }

        @Test
        @DisplayName("should book nothing on cancel, whatever state its lines are in")
        void updateStatus_CancelBooksNoStock() {
            // A soft-deleted line (qty 3.00) and a live one: neither holds stock, because the charge is what
            // books it, so the cancellation asks the engine for nothing on either.
            var softDeletedItem = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(false) // soft-deleted
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            when(salesOrderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(canceladaStatus.getId())).thenReturn(SalesOrderState.CANCELLED);

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should take the order row lock before cancelling, so a charge cannot be overwritten")
        void updateStatus_TakesTheRowLock() {
            // The two reads answer the same row in the database, so the fixture's lenient delegate makes them
            // indistinguishable by state — which is exactly why the pin is the statement, not the answer: a
            // regression to the unlocked read leaves the locking one uninvoked, and this case is what says so.
            // Without the lock, a cancellation that read PENDING can reach a row a concurrent charge already
            // committed as COMPLETED; the transition it recorded is PENDING -> CANCELLED, so the schema's deferred
            // history check refuses the commit and a legal cancellation answers 500 instead of 409.
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(canceladaStatus.getId())).thenReturn(SalesOrderState.CANCELLED);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);

            salesOrderService.updateSalesOrderStatus(
                    orderId, new UpdateSalesOrderStatusRequest(canceladaStatus.getId()));

            verify(salesOrderRepository).findByIdForUpdate(orderId);
        }
    }

    // ─────────────────────────────────────────────
    // getSalesOrderItems
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getSalesOrderItems")
    class GetSalesOrderItemsTests {

        @Test
        @DisplayName("should return items list for existing order")
        void getSalesOrderItems_ReturnsItems() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            List<SalesOrderItemResponse> result = salesOrderService.getSalesOrderItems(orderId);

            assertThat(result).isNotNull();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(itemId);
            assertThat(result.get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.00"));
            // A mocked row runs no trigger, so the derived money is absent from it and the mapper asks the row.
            // The number itself is pinned by SalesOrderIntegrationTest; this pins that it is not invented here.
            verify(entityManager).refresh(any(SalesOrderItem.class));
            assertThat(result.get(0).statusName()).isEqualTo("Pending");
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void getSalesOrderItems_OrderNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.getSalesOrderItems(orderId))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");
        }
    }

    // ─────────────────────────────────────────────
    // addSalesOrderItem
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("addSalesOrderItem")
    class AddSalesOrderItemTests {

        @Test
        @DisplayName("should refuse an item naming another company's variant and write nothing")
        void addSalesOrderItem_ForeignCompanyVariant_IsRefusedAndNothingIsPersisted() {
            // The variant exists, is enabled and is sellable in the abstract: what it is not is a
            // variant of the company that owns the order's store. A bare existsById answers `true`
            // for it — no product, no store, no company — which is what this pins.
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found");

            // The question was asked of the tenant that owns the order's store, not of the table.
            verify(productVariantRepository).existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId);
            // Nothing persisted and nothing sold: no line, no deduction, no total recalculation.
            verify(itemRepository, never()).save(any());
            verify(stockLedgerEngine, never()).applySaleBatch(any(), any(), any());
            verify(salesOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("should add an item and report the line's money from the row, never from the request")
        void addSalesOrderItem_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            // V85 (D-h): final_price is the schema's, so the mapper refreshes the row instead of stating a unit
            // price; a mocked repository runs no trigger, so the value lives in the integration suite.
            verify(entityManager).refresh(any(SalesOrderItem.class));
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.discountApplied()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(result.statusName()).isEqualTo("Pending");
            verify(itemRepository).save(any(SalesOrderItem.class));
            // Nothing is deducted here: the line is a line, and the charge books it (ADR-0002 decision 7).
            verifyNoStockWork();
            // Verify totalAmount was recalculated (findById called in loadAndValidateModifiableSO +
            // recalculateTotalAmount)
            verify(salesOrderRepository, times(2)).findById(orderId);
        }

        @Test
        @DisplayName("should recalculate totalAmount after adding item")
        void addSalesOrderItem_RecalculatesTotalAmount() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            // The row the writer reads back states the line the add just wrote: 2 x 100.00 less 10.00 discount.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("200.0000"), new BigDecimal("10.0000"), new BigDecimal("0.0000")));

            salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            // The recalculation is proved on the document's own money. The five setters are the write now
            // (the row is managed), so a `save` would say the row was handed to the repository and nothing
            // about the number it left on it: subtotal - discount + tax, rounded once to the currency.
            assertThat(testOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(testOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(testOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("190.0000"));
        }

        @Test
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when the order has reached a terminal status")
        void addSalesOrderItem_TerminalOrder_ThrowsException() {
            var cerradaOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cerradaOrder));

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already COMPLETED")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should refuse the first item on a Cancelled order, the other terminal status")
        void addSalesOrderItem_CancelledOrder_ThrowsException() {
            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.CANCELLED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already CANCELLED")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should add item successfully when order is in Active status")
        void addSalesOrderItem_ActiveStatus_AddsItemSuccessfully() {
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // Not first item — item already exists
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            verify(entityManager).refresh(any(SalesOrderItem.class));
            assertThat(result.statusName()).isEqualTo("Pending");
            verify(itemRepository).save(any(SalesOrderItem.class));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should add item successfully when order is in Pending status")
        void addSalesOrderItem_PendingOrder_AddsItemSuccessfully() {
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // Not the first item — the order already holds one, so no activation is attempted
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            verify(itemRepository).save(any(SalesOrderItem.class));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should not activate a Pending order when it receives its first enabled item")
        void addSalesOrderItem_PendingOrderWithoutItems_DoesNotAttemptActivation() {
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // Every line was soft-deleted, so this add is the order's first *enabled* item
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            // Draft → Active belongs to a draft: Pending has no such transition, so it must not be attempted
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should auto-transition Draft → Active when first item is added")
        void addSalesOrderItem_FirstItem_AutoTransitionsToActive() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            var savedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(new BigDecimal("10.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(savedItem);
            // First item — empty list (first call), then with item (subsequent calls for recalculate and toResponse)
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of())
                    .thenReturn(List.of(savedItem));
            // Auto-transition mocks
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            // Verify order was saved with Active status (at least once, also saved during recalculate)
            verify(salesOrderRepository, atLeast(1)).save(argThat(so -> so.getStatus() == SalesOrderState.ACTIVE));
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void addSalesOrderItem_OrderNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");
        }

        @Test
        @DisplayName("should throw ProductVariantNotFoundException when the variant is missing or soft-deleted")
        void addSalesOrderItem_VariantNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            // The gate requires a live definition: the split removed findByIdForUpdate, whose query
            // filtered enabled = true, so a soft-deleted variant must not become sellable again.
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrderItem — stock adjustment
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrderItem")
    class UpdateSalesOrderItemTests {

        @Test
        @DisplayName("should update item and recalculate total")
        void updateSalesOrderItem_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // The line after the update: 2 x 100.00 less 10.00 of discount, as the schema sums it.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("200.0000"), new BigDecimal("10.0000"), new BigDecimal("0.0000")));

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            verify(entityManager).refresh(any(SalesOrderItem.class));
            verify(itemRepository).save(any(SalesOrderItem.class));
            // The total is stated on the document, not by a `save`: the parts are what the writer wrote.
            assertThat(testOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(testOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(testOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("190.0000"));
        }

        @Test
        @DisplayName("should book no stock when item quantity increases")
        void updateSalesOrderItem_QuantityIncrease_BooksNoStock() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // The line grew, and no engine call follows: there is no delta to book any more, because nothing
            // was booked when the line was created. The charge books the quantity the line states.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should book no stock when item quantity decreases")
        void updateSalesOrderItem_QuantityDecrease_BooksNoStock() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("1.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // Nothing to give back and nothing to re-sell: the line never held stock.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should not change stock when quantity unchanged")
        void updateSalesOrderItem_SameQuantity_NoStockChange() {
            // testItemRequest has qty 2.00, testItem has qty 2.00 → diff = 0 → no stock change
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            // An unchanged line must not reach the engine at all.
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should book no stock when productVariantId changes")
        void updateSalesOrderItem_VariantChange_BooksNoStock() {
            var newVariantId = UUID.randomUUID();

            var request = new SalesOrderItemRequest(
                    itemId, newVariantId, new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(newVariantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // A variant change on an uncharged document moves no ledger row: the charge books whichever variant
            // the line carries when it runs.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should book no stock when both variant and quantity change")
        void updateSalesOrderItem_VariantChange_WithQuantityChange() {
            var newVariantId = UUID.randomUUID();

            var request = new SalesOrderItemRequest(
                    itemId, newVariantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(newVariantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            verifyNoStockWork();
        }

        @Test
        @DisplayName("should accept a quantity the store cannot cover, leaving the refusal to the charge")
        void updateSalesOrderItem_BeyondStock_IsAcceptedAndBooksNoStock() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            // ADR-0002 decision 7: a line edit is not the moment stock is checked. Nothing here asks the engine,
            // so a quantity the store cannot cover is a document state the charge refuses, not an edit this path
            // can reject — and the line really is persisted.
            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            verify(itemRepository).save(any(SalesOrderItem.class));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should throw SalesOrderItemNotFoundException when item not found")
        void updateSalesOrderItem_ItemNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should refuse a line that belongs to another order")
        void updateSalesOrderItem_ForeignLine_ThrowsException() {
            // The line is enabled and Pending, so the line guard would let it through: the ownership
            // check is the only one that can refuse this write, which is what this probe isolates.
            var foreignLine = foreignLine();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(foreignLine));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");

            assertThat(foreignLine.getEnabled()).isTrue();
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when the order has reached a terminal status")
        void updateSalesOrderItem_TerminalOrder_ThrowsException() {
            var cerradaOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cerradaOrder));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already COMPLETED")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should update item on Active order successfully")
        void updateSalesOrderItem_ActiveOrder_Success() {
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // The line after the update: 2 x 100.00 less 10.00 of discount, as the schema sums it.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("200.0000"), new BigDecimal("10.0000"), new BigDecimal("0.0000")));

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            verify(entityManager).refresh(any(SalesOrderItem.class));
            verify(itemRepository).save(any(SalesOrderItem.class));
            // Read on the Active order itself: the recalculation is not a status-dependent write.
            assertThat(activeOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("200.0000"));
            assertThat(activeOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(activeOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("190.0000"));
        }

        @Test
        @DisplayName("should update item on Pending order successfully")
        void updateSalesOrderItem_PendingOrder_Success() {
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            verify(itemRepository).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should reject changing a Cancelled line and touch no stock")
        void updateSalesOrderItem_CancelledLine_ThrowsException() {
            var cancelledLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(cancelledLine));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is CANCELLED")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should reject changing a soft-deleted line and touch no stock")
        void updateSalesOrderItem_SoftDeletedLine_ThrowsException() {
            var deletedLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(false)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(deletedLine));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is deleted")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrderItem
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrderItem")
    class DeleteSalesOrderItemTests {

        @Test
        @DisplayName("should soft-delete item without stock work and recalculate total")
        void deleteSalesOrderItem_Success() {
            // The document's money before the delete: the fixture's line was its only one, so these are the
            // parts the previous recomputation left on the row.
            givenBreakdown(testOrder, "200.0000", "10.0000", "0.0000", "0.0000", "190.00");
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            // The deleted line was the only enabled one, so the schema's sum answers three NULLs.
            when(itemRepository.sumMoneyParts(orderId)).thenReturn(moneyPartsRow(null, null, null));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            // The recalculation read back from the document: with no line left, every part is zero and the
            // total is the currency's zero. A `save` would only say the row was handed to the repository.
            assertThat(testOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.0000"));
            // A line of an uncharged document holds no stock, so removing it gives nothing back: the charge is
            // what would have booked it, and the line it no longer sees is simply not booked (ADR-0002
            // decision 7). The reversal exists only for a document whose charge booked something.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should throw SalesOrderItemNotFoundException when item not found")
        void deleteSalesOrderItem_ItemNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrderItem(orderId, itemId))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");
        }

        @Test
        @DisplayName("should refuse a line that belongs to another order")
        void deleteSalesOrderItem_ForeignLine_ThrowsException() {
            // Enabled and Pending, so the only check that can refuse the delete is the ownership one
            // — and the refusal must come before any reversal is asked of the engine.
            var foreignLine = foreignLine();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(foreignLine));

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrderItem(orderId, itemId))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");

            assertThat(foreignLine.getEnabled()).isTrue();
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should reject deleting a Cancelled line and touch no stock")
        void deleteSalesOrderItem_CancelledLine_ThrowsException() {
            // A Cancelled line already gave its stock back, so the delete must be refused by the same
            // line guard the update path runs (F-7), before any reversal reaches the engine.
            var cancelledLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(cancelledLine));

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrderItem(orderId, itemId))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is CANCELLED")
                    .hasMessageContaining("cannot be modified");

            assertThat(cancelledLine.getEnabled()).isTrue();
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should reject deleting a soft-deleted line and touch no stock")
        void deleteSalesOrderItem_SoftDeletedLine_ThrowsException() {
            // A soft-deleted line is never re-enabled by the item-level endpoint, so a delete on it
            // would ask the engine to reverse a line that already gave its stock back (F-7).
            var deletedLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(false)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(deletedLine));

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrderItem(orderId, itemId))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is deleted")
                    .hasMessageContaining("cannot be modified");

            assertThat(deletedLine.getEnabled()).isFalse();
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("should soft-delete item on Active order without stock work")
        void deleteSalesOrderItem_ActiveOrder_Success() {
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            // The document stood on the fixture's line before the delete.
            givenBreakdown(activeOrder, "200.0000", "10.0000", "0.0000", "0.0000", "190.00");
            when(itemRepository.sumMoneyParts(orderId)).thenReturn(moneyPartsRow(null, null, null));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            assertThat(activeOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(activeOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.0000"));
            // Active is not charged either: the document holds no stock until the charge books it.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should soft-delete item on Pending order without stock work")
        void deleteSalesOrderItem_PendingOrder_Success() {
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            // The document stood on the fixture's line before the delete.
            givenBreakdown(pendingOrder, "200.0000", "10.0000", "0.0000", "0.0000", "190.00");
            when(itemRepository.sumMoneyParts(orderId)).thenReturn(moneyPartsRow(null, null, null));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            assertThat(pendingOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(pendingOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(pendingOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(pendingOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(pendingOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("0.0000"));
            // Pending is the state a charge starts from, not one that holds stock: the line is deleted from a
            // document that never booked it.
            verifyNoStockWork();
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrderItemStatus
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrderItemStatus")
    class UpdateSalesOrderItemStatusTests {

        @Test
        @DisplayName("should transition Pending → Added successfully")
        void updateItemStatus_PendingToAdded_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(agregadoItemStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(statusCatalog.requireItemState(agregadoItemStatus.getId())).thenReturn(SalesOrderItemState.ADDED);

            var updatedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(testItem.getQuantity())
                    .listPrice(testItem.getListPrice())
                    .discountApplied(testItem.getDiscountApplied())
                    .status(SalesOrderItemState.ADDED)
                    .enabled(true)
                    .build();

            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(updatedItem);

            SalesOrderItemResponse result =
                    salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.statusName()).isEqualTo("Added");
            verify(itemRepository).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should transition Pending → Cancelled successfully")
        void updateItemStatus_PendingToCancelled_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladoItemStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(statusCatalog.requireItemState(canceladoItemStatus.getId())).thenReturn(SalesOrderItemState.CANCELLED);

            var updatedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(testItem.getQuantity())
                    .listPrice(testItem.getListPrice())
                    .discountApplied(testItem.getDiscountApplied())
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();

            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(updatedItem);

            SalesOrderItemResponse result =
                    salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            // A cancelled line holds no stock to give back: nothing is booked before the charge (ADR-0002
            // decision 7), and the charge skips a line in this state. The reversal that W3-D4 used to run here
            // has one home now, `deleteSalesOrder`, and only for a document the charge booked.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Added → Cancelled")
        void updateItemStatus_AddedToCancelled_ThrowsInvalidTransition() {
            // ADR-0004 decision 3: "an item of a charged order cannot be cancelled" is the absence of an
            // edge out of ADDED, not a comparison against a label. It is also the only refused pair the item
            // machine can be asked about — ADDED and CANCELLED are terminal, so every move out of either is
            // refused, and a state outside the enum never reaches the machine at all (the catalog refuses it
            // first, which `SalesOrderStatusCatalogTest` pins).
            var addedLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.ADDED)
                    .enabled(true)
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(canceladoItemStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(addedLine));
            when(statusCatalog.requireItemState(canceladoItemStatus.getId())).thenReturn(SalesOrderItemState.CANCELLED);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("Invalid status transition")
                    .hasMessageContaining("ADDED")
                    .hasMessageContaining("CANCELLED");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(stockLedgerEngine);
        }
    }

    // ─────────────────────────────────────────────
    // chargeSalesOrder
    // ─────────────────────────────────────────────

    /**
     * An amount for a case whose document is refused before its money is read — the state guard's 400 and the
     * missing method's 404. It is not a number any case asserts: it only has to be one a request may state,
     * because bean validation refuses a charge whose payment applies no money at all.
     */
    private static final String UNREAD_AMOUNT = "1.0000";

    /**
     * One payment for the amount the case states, which is always the sum its {@code sumMoneyParts} stub answers:
     * since T7b a charge's payments add up to the document's total exactly (ADR-0002 M3), so a case that stubbed
     * 350.0000 and stated 200.0000 would be asserting a refusal instead of a charge.
     *
     * <p>No tender is stated: {@code is_cash} is read only when one is, and the tender's own arithmetic is pinned
     * where the constraint is — {@code SalesOrderIntegrationTest}.</p>
     */
    private ChargeSalesOrderRequest chargeOf(UUID paymentMethodId, String amount) {
        return new ChargeSalesOrderRequest(
                List.of(new SalePaymentRequest(paymentMethodId, new BigDecimal(amount), null, null)));
    }

    @Nested
    @DisplayName("chargeSalesOrder")
    class ChargeSalesOrderTests {

        @Test
        @DisplayName("should charge Pending order successfully")
        void chargeSalesOrder_Success() {
            var paymentMethodId = UUID.randomUUID();
            // 350.0000 is what the breakdown below sums to, because a charge's payments add up to it exactly (M3).
            var request = chargeOf(paymentMethodId, "350.0000");

            // Create a Pending order
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            // Items: 2 enabled Pending items
            var item1 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            // Reloaded order after save (Completed status + payment method)
            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            // toResponse mocks
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's own money, read from the schema: the two lines are 2 x 100.00 and 3 x 50.00, with
            // no discount and no tax, so the sum is 350.0000 and the charge states 350.00.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("350.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            // Verify header saved with Completed status. The document states no payment method any more: V87 dropped
            // sales_orders.payment_method_id once V86 populated the rows, so the tender the charge took lives in the
            // payment row asserted by the cases that read `appendedPayments`, and there is no second place to assert
            // it here.
            verify(salesOrderRepository).save(argThat(so -> so.getStatus() == SalesOrderState.COMPLETED));
            // Verify both items saved in a single batch with Added status
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems).hasSize(2).allMatch(i -> i.getStatus() == SalesOrderItemState.ADDED);
        }

        @Test
        @DisplayName("should throw InvalidSalesOrderChargeException when order not in Pending or Active")
        void chargeSalesOrder_NotPendingOrActive_ThrowsException() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, UNREAD_AMOUNT);

            // testOrder is DRAFT by default — DRAFT is neither PENDING nor ACTIVE
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, request))
                    .isInstanceOf(InvalidSalesOrderChargeException.class)
                    .hasMessageContaining(orderId.toString())
                    .hasMessageContaining("DRAFT");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when payment method does not exist")
        void chargeSalesOrder_InvalidPaymentMethod_ThrowsException() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, UNREAD_AMOUNT);

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(paymentMethodRepository.findById(paymentMethodId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, request))
                    .isInstanceOf(PaymentMethodNotFoundException.class)
                    .hasMessageContaining(paymentMethodId.toString());

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should leave Cancelled items untouched while transitioning Pending items to Added")
        void chargeSalesOrder_MixedItems_OnlyTransitionsNonCancelled() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "30.0000");

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            // 3 items: Pending, Pending, Cancelled
            var itemPending1 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var itemPending2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var itemCancelled = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();

            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(itemRepository.findBySalesOrderId(orderId))
                    .thenReturn(List.of(itemPending1, itemPending2, itemCancelled));
            // Item status lookup: bulk fetch by the set of item statusIds
            // toResponse mocks
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's own money, read from the schema: three lines of 1 x 10.00 — the cancelled one is
            // still enabled, so it is still summed — no discount and no tax, so the total is 30.00.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("30.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            // Only the 2 Pending items are persisted (as Added); Cancelled stays untouched
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems)
                    .hasSize(2)
                    .allMatch(i -> i.getStatus() == SalesOrderItemState.ADDED)
                    .extracting(SalesOrderItem::getId)
                    .containsExactlyInAnyOrder(itemPending1.getId(), itemPending2.getId());
        }

        /**
         * The one batch the charge handed the engine, in the order it handed it over.
         *
         * <p>{@code times(1)} is the assertion and not a convenience: ADR-0002 decision 7 says the charge books the
         * document <b>once</b>, so a second batch would be a second moment the stock moved, and a batch per line
         * would be the per-line path T6b removed.</p>
         */
        @SuppressWarnings("unchecked")
        private List<SaleChange> theChargeBatch() {
            var captor = ArgumentCaptor.forClass(List.class);
            verify(stockLedgerEngine, times(1)).applySaleBatch(eq("SALES_ORDER_ITEM"), any(), captor.capture());
            return (List<SaleChange>) captor.getValue();
        }

        /**
         * ADR-0002 decision 7, the half T6b moved: the charge books the document as <b>one</b> batch, with one
         * deduction per live line and each line's own write sequence.
         *
         * <p><b>Why one batch and not one call per line.</b> The engine orders a batch itself — it locks every
         * distinct {@code (store, variant)} in key order and runs every reversal before every deduction — so a
         * caller sending its lines one at a time would own an ordering the engine already owns, and two charges of
         * one store would be free to invert it.</p>
         *
         * <p><b>Why the write sequence is asserted per line and not just counted.</b> {@code V73} keys a movement on
         * {@code (source, write_seq)}, and the number comes from the line's own counter — which the charge is now
         * the only writer of. A batch that numbered both lines with the first line's bump would be a plausible
         * batch (two deductions, correct quantities) and the second movement would be refused by the unique key at
         * the database instead of here.</p>
         */
        @Test
        @DisplayName("should book one batch with one deduction per live line, each numbered by its own write sequence")
        void chargeSalesOrder_BooksOneBatchOfOneDeductionPerLiveLine() {
            var paymentMethodId = UUID.randomUUID();
            var secondVariantId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "350.0000");

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            var firstLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var secondLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(secondVariantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(new BigDecimal("350.0000"))
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(firstLine, secondLine));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("350.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));
            // The counter the charge bumps: the numbers the two movements are booked under (V73).
            when(itemRepository.bumpWriteSeq(firstLine.getId())).thenReturn(11L);
            when(itemRepository.bumpWriteSeq(secondLine.getId())).thenReturn(12L);

            salesOrderService.chargeSalesOrder(orderId, request);

            var changes = theChargeBatch();
            assertThat(changes)
                    .as("one deduction per live line, and no reversal anywhere in a charge's batch")
                    .hasSize(2)
                    .allMatch(change ->
                            !change.reversal() && change.companyStoreId().equals(companyStoreId));
            assertThat(changes)
                    .as("each deduction carries the line's own write sequence, not a shared number")
                    .extracting(SaleChange::referenceId, SaleChange::writeSeq)
                    .containsExactlyInAnyOrder(tuple(firstLine.getId(), 11L), tuple(secondLine.getId(), 12L));
            assertThat(changes)
                    .anySatisfy(change -> {
                        assertThat(change.productVariantId()).isEqualTo(variantId);
                        assertThat(change.quantity()).isEqualByComparingTo(new BigDecimal("2.00"));
                    })
                    .anySatisfy(change -> {
                        assertThat(change.productVariantId()).isEqualTo(secondVariantId);
                        assertThat(change.quantity()).isEqualByComparingTo(new BigDecimal("3.00"));
                    });
            // The bump is taken once per booked line, before the movement it numbers (V73).
            verify(itemRepository).bumpWriteSeq(firstLine.getId());
            verify(itemRepository).bumpWriteSeq(secondLine.getId());
            verify(itemRepository, times(2)).bumpWriteSeq(any());
        }

        /**
         * A cancelled line and a soft-deleted line hold nothing, so the charge neither books nor numbers them.
         *
         * <p><b>Why the two are one rule here and were two rules before T6b.</b> Under the old arrangement a line
         * cancelled or deleted individually reversed its own deduction, and the paths that ran later had to tell
         * the two apart to avoid crediting twice. Nothing is booked before the charge now, so both are simply
         * lines the charge does not sell — and a bump for either would be a number consumed by a movement that
         * never happens.</p>
         */
        @Test
        @DisplayName("should deduct nothing for a cancelled line and for a soft-deleted line")
        void chargeSalesOrder_CancelledAndSoftDeletedLinesAreNotBooked() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "120.0000");

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            var liveLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var cancelledLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();
            var softDeletedLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("7.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(false)
                    .build();

            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(new BigDecimal("120.0000"))
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(itemRepository.findBySalesOrderId(orderId))
                    .thenReturn(List.of(liveLine, cancelledLine, softDeletedLine));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's money still sums the cancelled line: it keeps `enabled = true`, which is the
            // accounting question T4 owes and not this case's subject.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("120.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));
            when(itemRepository.bumpWriteSeq(liveLine.getId())).thenReturn(5L);

            salesOrderService.chargeSalesOrder(orderId, request);

            var changes = theChargeBatch();
            assertThat(changes)
                    .as("the only line that holds anything is the live one")
                    .singleElement()
                    .satisfies(change -> {
                        assertThat(change.referenceId()).isEqualTo(liveLine.getId());
                        assertThat(change.writeSeq()).isEqualTo(5L);
                        assertThat(change.quantity()).isEqualByComparingTo(BigDecimal.ONE);
                    });
            verify(itemRepository, never()).bumpWriteSeq(cancelledLine.getId());
            verify(itemRepository, never()).bumpWriteSeq(softDeletedLine.getId());
        }

        /**
         * A document with no live line sends the engine no batch at all.
         *
         * <p><b>Why the refusal is not what this case asserts.</b> A charge whose lines are all cancelled is
         * refused only when its total rounds to zero, and a cancelled line still contributes its money — so this
         * document really does reach {@code Completed} with a payment and no stock work. The assertion is
         * therefore that the engine is asked for <b>nothing</b>: an empty batch would be a call whose argument says
         * "book no movement", which is a different statement from never asking.</p>
         */
        @Test
        @DisplayName("should send the engine no batch at all when the document has no live line")
        void chargeSalesOrder_NoLiveLine_BooksNoBatch() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "40.0000");

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            var cancelledLine = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.CANCELLED)
                    .enabled(true)
                    .build();

            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyId(companyId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(new BigDecimal("40.0000"))
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(cancelledLine));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("40.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            salesOrderService.chargeSalesOrder(orderId, request);

            verifyNoStockWork();
            verify(itemRepository, never()).bumpWriteSeq(any());
        }

        @Test
        @DisplayName("should auto-promote Active → Pending before charging")
        void chargeSalesOrder_ActiveOrder_AutoPromotesToPending() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "350.0000");

            // Create an Active order
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            // Items: 2 enabled Pending items
            var item1 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            // Reloaded order after save (Completed status + payment method)
            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(activeOrder))
                    .thenReturn(Optional.of(savedOrder));
            // ensureOrderIsPending: lookup Active status
            // ensureOrderIsPending: find Pending status for promotion
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            // toResponse mocks — items have been transitioned to Added
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's own money, read from the schema: 2 x 100.00 and 3 x 50.00, no discount and no tax,
            // so the sum is 350.0000 and the charge states 350.00.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("350.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            // Verify the order was saved twice: once by ensureOrderIsPending (Pending status)
            // and once by charge (Completed status + payment method)
            verify(salesOrderRepository, times(2)).save(any(SalesOrder.class));
            // Verify both items saved in a single batch with Added status
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems).hasSize(2).allMatch(i -> i.getStatus() == SalesOrderItemState.ADDED);
        }

        @Test
        @DisplayName("should successfully charge an Active order end-to-end")
        void chargeSalesOrder_ActiveOrder_ThenChargesSuccessfully() {
            var paymentMethodId = UUID.randomUUID();
            var request = chargeOf(paymentMethodId, "350.0000");

            // Create an Active order
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.ACTIVE)
                    .totalAmount(new BigDecimal("250.00"))
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            // Items: 2 enabled Pending items
            var item1 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            // Reloaded order after save (Completed status + payment method)
            var savedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(new BigDecimal("250.00"))
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(activeOrder))
                    .thenReturn(Optional.of(savedOrder));
            // ensureOrderIsPending: lookup Active status
            // ensureOrderIsPending: find Pending status for promotion
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            // toResponse mocks
            // toResponse mocks — use fresh item copies since the originals were mutated in place
            var item1AfterCharge = SalesOrderItem.builder()
                    .id(item1.getId())
                    .salesOrderId(item1.getSalesOrderId())
                    .productVariantId(item1.getProductVariantId())
                    .quantity(item1.getQuantity())
                    .listPrice(item1.getListPrice())
                    .discountApplied(item1.getDiscountApplied())
                    .status(SalesOrderItemState.ADDED)
                    .enabled(true)
                    .build();
            var item2AfterCharge = SalesOrderItem.builder()
                    .id(item2.getId())
                    .salesOrderId(item2.getSalesOrderId())
                    .productVariantId(item2.getProductVariantId())
                    .quantity(item2.getQuantity())
                    .listPrice(item2.getListPrice())
                    .discountApplied(item2.getDiscountApplied())
                    .status(SalesOrderItemState.ADDED)
                    .enabled(true)
                    .build();

            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of(item1AfterCharge, item2AfterCharge));
            // The document's own money, read from the schema: 2 x 100.00 and 3 x 50.00, no discount and no tax,
            // so the sum is 350.0000 and the charge states 350.00. The reloaded order this case asserts on is the
            // fixture's next answer and keeps the 250.00 it was built with.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("350.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            assertThat(appendedPayments)
                    .as("the tender is a row, now that V87 dropped sales_orders.payment_method_id: the method the "
                            + "request named is on the payment the charge appended, and the response has no "
                            + "single-method component left to answer it a second time (ADR-0002 step 6)")
                    .singleElement()
                    .satisfies(payment -> assertThat(payment.getMethodId()).isEqualTo(paymentMethodId));
            assertThat(result.totalAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
            // Verify header saved with Completed status, and nothing about a method: the column that stated one is
            // dropped by V87 and the payment row above is what states it now.
            var saveCaptor = ArgumentCaptor.forClass(SalesOrder.class);
            verify(salesOrderRepository, times(2)).save(saveCaptor.capture());
            var savedValues = saveCaptor.getAllValues();
            assertThat(savedValues.get(1).getStatus()).isEqualTo(SalesOrderState.COMPLETED);
            // Verify both items saved in a single batch with Added status
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems).hasSize(2).allMatch(i -> i.getStatus() == SalesOrderItemState.ADDED);
        }

        /**
         * The state a charge is taken from, as this fixture states it: one Pending document of its store.
         *
         * <p>Pending and not Active because a case about the payment guards has no subject in the
         * auto-promotion: the guards run after it, and an Active document would add a transition the refusal
         * below would have to roll back.</p>
         */
        private SalesOrder pendingOrder() {
            return SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
        }

        /**
         * ADR-0002 M10 at the statement that holds it: {@code chargeSalesOrder} reads the order <b>with</b> the
         * row lock.
         *
         * <p><b>Why the pin is the statement and not the answer.</b> In the database the locked and the unlocked
         * read answer the same row — the fixture's lenient delegate makes them indistinguishable by state, which
         * is exactly why a case that only asserted the outcome would pass over a regression to the unlocked read.
         * So the two answers are stated differently on purpose: the unlocked read answers the document and the
         * locked one answers nothing, so a charge that kept the lock is refused as a missing order while one that
         * dropped it would sail past the guard. Without the lock, two retries of one charge both read
         * {@code PENDING}, both append a payment and both book the stock — and {@code sale_payment} is append-only,
         * so the second row could not be taken back.</p>
         *
         * <p><b>Why {@code doReturn} and not {@code when}.</b> The fixture's lenient answer for the locked read
         * <em>calls</em> the unlocked one, so a {@code when(salesOrderRepository.findByIdForUpdate(…))} would record
         * the inner call as the invocation being stubbed and quietly re-stub the unlocked read instead.
         * {@code doReturn} states the answer without invoking the method, which is the only shape that can state
         * two different answers for the two reads.</p>
         */
        @Test
        @DisplayName("should take the order row lock before charging, so a retried charge appends no second payment")
        void chargeSalesOrder_TakesTheRowLock() {
            var request = chargeOf(UUID.randomUUID(), UNREAD_AMOUNT);

            lenient().when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder()));
            doReturn(Optional.empty()).when(salesOrderRepository).findByIdForUpdate(orderId);

            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, request))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");

            verify(salesOrderRepository).findByIdForUpdate(orderId);
            assertThat(appendedPayments)
                    .as("the charge never reached the payments, because it never reached the row")
                    .isEmpty();
            verifyNoStockWork();
        }

        /**
         * ADR-0002 M4 through the domain guard: a tender on a method that is not cash is refused, by name.
         *
         * <p><b>Why the request is otherwise valid.</b> 400 handed over for 350 applied is a consistent pair, so
         * the arithmetic guards pass and the only rule left that can refuse it is the method's {@code is_cash} —
         * the fact {@code ck_payment_tender} cannot read, because a {@code CHECK} cannot see another table. A
         * refusal that named the amount instead of the method would send an operator to recount a drawer that is
         * already correct.</p>
         */
        @Test
        @DisplayName("should refuse a tender on a method that is not cash, naming the method (M4)")
        void chargeSalesOrder_TenderOnANonCashMethod_IsRefusedByName() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(
                    paymentMethodId,
                    new BigDecimal("350.0000"),
                    new BigDecimal("400.0000"),
                    new BigDecimal("50.0000"))));

            doReturn(Optional.of(pendingOrder())).when(salesOrderRepository).findByIdForUpdate(orderId);

            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, request))
                    .isInstanceOf(SalesOrderTenderNotCashException.class)
                    .hasMessageContaining("which is not a cash method")
                    .hasMessageContaining("'Tarjeta'")
                    .hasMessageContaining(paymentMethodId.toString());

            assertThat(appendedPayments)
                    .as("the refusal runs before the insert, so no row states a tender nobody may take")
                    .isEmpty();
            verifyNoStockWork();
        }

        /**
         * The tender's arithmetic, refused before it can reach {@code ck_payment_tender}.
         *
         * <p><b>The two shapes a cash sale can state wrongly.</b> A change larger than the tender leaves (250 handed
         * over for a 350 payment leaves nothing, and the request says 50) is a pair that does not add up; a tender
         * below the amount it is supposed to cover (300 for 350) is not a tender at all. Both are the request's
         * mistake, so both are a {@code 400} that names the payment and the number, never the constraint's generic
         * {@code 409} at flush.</p>
         */
        @Test
        @DisplayName("should refuse a change larger than the tender leaves, and a tender below the amount (M4)")
        void chargeSalesOrder_InconsistentTender_IsRefused() {
            var cashMethodId = UUID.randomUUID();
            when(paymentMethodRepository.findById(cashMethodId))
                    .thenReturn(Optional.of(PaymentMethod.builder()
                            .id(cashMethodId)
                            .paymentMethodName("Efectivo")
                            .paymentMethodShortName("EFECTIVO")
                            .enabled(true)
                            .isCash(true)
                            .build()));
            doReturn(Optional.of(pendingOrder())).when(salesOrderRepository).findByIdForUpdate(orderId);

            // 300 handed over for 350 applied leaves -50, which no change can be: the tender is below the amount.
            var belowTheAmount = new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(
                    cashMethodId, new BigDecimal("350.0000"), new BigDecimal("300.0000"), BigDecimal.ZERO)));
            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, belowTheAmount))
                    .isInstanceOf(SalesOrderTenderInconsistentException.class)
                    .hasMessageContaining("cannot be less than what the payment applies to the sale")
                    .hasMessageContaining("ck_payment_tender holds the same arithmetic");

            // 400 handed over for 350 applied leaves 50, and the request states a change of 60.
            var changeLargerThanTheTenderLeaves = new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(
                    cashMethodId, new BigDecimal("350.0000"), new BigDecimal("400.0000"), new BigDecimal("60.0000"))));
            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, changeLargerThanTheTenderLeaves))
                    .isInstanceOf(SalesOrderTenderInconsistentException.class)
                    .hasMessageContaining("the change of a cash payment is tendered - amount = 50.0000");

            assertThat(appendedPayments).isEmpty();
            verifyNoStockWork();
        }

        /**
         * The accepting half of M4: a cash method with a consistent pair is charged, and the row keeps the pair.
         *
         * <p><b>Why the row and not only the outcome.</b> {@code tendered_amount} and {@code change_amount} are the
         * only record of what the customer handed over — the payment's {@code amount} is what applies to the sale —
         * so a charge that accepted the pair and then dropped it would leave a drawer that cannot be reconciled with
         * the sale it settled. The numbers here are the request's, and the difference between them is exactly the
         * change {@code ck_payment_tender} holds.</p>
         */
        @Test
        @DisplayName("should charge a cash method with a consistent tender and keep the pair on the row (M4)")
        void chargeSalesOrder_CashMethodWithAConsistentTender_IsAccepted() {
            var cashMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(List.of(new SalePaymentRequest(
                    cashMethodId, new BigDecimal("350.0000"), new BigDecimal("400.0000"), new BigDecimal("50.0000"))));

            var pending = pendingOrder();
            var line = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var charged = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(new BigDecimal("350.0000"))
                    .enabled(true)
                    .build();

            when(paymentMethodRepository.findById(cashMethodId))
                    .thenReturn(Optional.of(PaymentMethod.builder()
                            .id(cashMethodId)
                            .paymentMethodName("Efectivo")
                            .paymentMethodShortName("EFECTIVO")
                            .enabled(true)
                            .isCash(true)
                            .build()));
            doReturn(Optional.of(pending)).when(salesOrderRepository).findByIdForUpdate(orderId);
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(charged));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(line));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("350.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));
            when(itemRepository.bumpWriteSeq(line.getId())).thenReturn(1L);

            var result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result.statusName()).isEqualTo("Completed");
            assertThat(appendedPayments).singleElement().satisfies(payment -> {
                assertThat(payment.getDirection()).isEqualTo(SalePaymentDirection.CHARGE);
                assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("350.0000"));
                assertThat(payment.getTenderedAmount()).isEqualByComparingTo(new BigDecimal("400.0000"));
                assertThat(payment.getChangeAmount()).isEqualByComparingTo(new BigDecimal("50.0000"));
            });
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrder — inline items diff
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrder - inline items diff")
    class UpdateSalesOrderInlineItemsTests {

        @Test
        @DisplayName("should soft-delete items present in DB but absent from request")
        void updateSalesOrder_SoftDeletesMissingItems() {
            var vid2 = UUID.randomUUID();
            var vid3 = UUID.randomUUID();

            var existingItem1 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var existingItem2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid2)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();
            var existingItem3 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid3)
                    .quantity(new BigDecimal("5.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            var reqItem1 = new SalesOrderItemRequest(
                    existingItem1.getId(),
                    existingItem1.getProductVariantId(),
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    null);
            var reqItem2 = new SalesOrderItemRequest(
                    existingItem2.getId(),
                    existingItem2.getProductVariantId(),
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    null);
            var request =
                    new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem1, reqItem2));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderId(orderId))
                    .thenReturn(List.of(existingItem1, existingItem2, existingItem3));
            when(itemRepository.findById(existingItem1.getId())).thenReturn(Optional.of(existingItem1));
            when(itemRepository.findById(existingItem2.getId())).thenReturn(Optional.of(existingItem2));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(existingItem3.getId()) && !item.getEnabled()));
            // The diff is a document edit: the dropped line is soft-deleted and nothing is booked for it,
            // because a line holds no stock until the charge books it (ADR-0002 decision 7). The retained lines
            // keep their quantity and the engine is asked nothing at all.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should update changed fields on existing items with matching IDs")
        void updateSalesOrder_UpdatesChangedItems() {
            var newQty = new BigDecimal("5.00");
            var newPrice = new BigDecimal("200.00");
            var newDiscount = new BigDecimal("20.00");

            var reqItem = new SalesOrderItemRequest(
                    testItem.getId(), testItem.getProductVariantId(), newQty, newPrice, newDiscount, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(newQty) == 0
                            && item.getListPrice().compareTo(newPrice) == 0
                            && item.getDiscountApplied().compareTo(newDiscount) == 0
                            && item.getEnabled()));
            // The line grew and the diff books nothing: the charge books the quantity the line states when it
            // runs, so there is no delta for this path to compute.
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should insert new items when request items have null id")
        void updateSalesOrder_InsertsNewItems() {
            UUID newVariantId = UUID.randomUUID();
            UUID savedItemId = UUID.randomUUID();

            var reqItem = new SalesOrderItemRequest(
                    null, newVariantId, new BigDecimal("3.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of());
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> savedItemId.equals(item.getId())
                            && item.getSalesOrderId().equals(orderId)
                            && item.getProductVariantId().equals(newVariantId)
                            && item.getQuantity().compareTo(new BigDecimal("3.00")) == 0));
            // The insert is a line and nothing else: its generated id is the reference the charge's ledger
            // row will name, not a deduction this path runs (ADR-0002 decision 7).
            verifyNoStockWork();
        }

        @Test
        @DisplayName("should skip item diff when items list is null")
        void updateSalesOrder_NullItemsSkipsDiff() {
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository, never()).findBySalesOrderId(any());
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should recalculate total amount after item changes")
        void updateSalesOrder_RecalculatesTotalAfterItemChanges() {
            var savedItemId = UUID.randomUUID();
            var reqItem = new SalesOrderItemRequest(
                    null, variantId, BigDecimal.ONE, new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of());
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's lines after the diff: the request's new line, 1 x 100.00, no discount, no tax.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("100.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            salesOrderService.updateSalesOrder(orderId, request);

            // The recalculation after the item diff is read on the document's own money: the parts and the
            // total are what the writer left, and a `save` alone would not have stated either.
            assertThat(testOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(testOrder.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTaxTotal()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getRoundingAdjustment()).isEqualByComparingTo(new BigDecimal("0.0000"));
            assertThat(testOrder.getTotalAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
            // The recalculation is the whole write: the new line books no stock here.
            verifyNoStockWork();
        }
    }

    // ─────────────────────────────────────────────
    // The inline item diff books no stock
    // ─────────────────────────────────────────────

    /**
     * The order-level line diff, asked the question that is left after ADR-0002 decision 7: does it book anything?
     *
     * <p><b>What this class used to be.</b> It was {@code StockDeltaAndRestorationTests}, and it pinned the delta
     * arithmetic of the inline diff — a new line sold in full, a grown line sold by its difference, a shrunk or
     * re-pointed line given back and re-sold — because the document held stock from the moment a line existed.
     * Under decision 7 the booking happens once, in {@code chargeSalesOrder}, from the lines the document holds at
     * that moment; nothing on the create, the add, the update, the delete or the re-enable path moves a unit.
     * There is therefore no delta to compute and nothing to restore, and a case that still expected one would be
     * asserting the arrangement this unit removed.</p>
     *
     * <p><b>What it asserts instead, per scenario.</b> Every case keeps its own line diff — the add, the growth, the
     * shrink, the drop, the variant change, the mixture — and states the new invariant on the same write: the
     * lines land in the document exactly as the request states them, and the stock engine is asked for
     * <b>nothing</b>. That is what makes the charge the single moment the stock moves, and it is why the refusal
     * for a line the store cannot cover is no longer here: it is the charge's, and the case that used to expect
     * the 409 here asserts that the same request is accepted and books nothing.</p>
     */
    @Nested
    @DisplayName("the inline item diff books no stock (the charge does)")
    class InlineItemDiffBooksNoStockTests {

        @Test
        @DisplayName("a new line is persisted and books no stock")
        void inlineDiff_NewItemOnly_BooksNoStock() {
            var newVariantId = UUID.randomUUID();
            var savedItemId = UUID.randomUUID();

            var reqItem = new SalesOrderItemRequest(
                    null, newVariantId, new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of());
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            // The line is the whole write, in full and enabled: the quantity the charge will book.
            verify(itemRepository)
                    .save(argThat(item -> savedItemId.equals(item.getId())
                            && item.getQuantity().compareTo(new BigDecimal("10.00")) == 0
                            && item.getEnabled()));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("a dropped line is soft-deleted and books no stock")
        void inlineDiff_DeletedItemOnly_BooksNoStock() {
            var vid2 = UUID.randomUUID();

            var existingToDelete = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid2)
                    .quantity(new BigDecimal("5.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            var existingKept = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            // Request: keep existingKept only (existingToDelete is absent → deleted)
            var reqItem = new SalesOrderItemRequest(
                    existingKept.getId(),
                    existingKept.getProductVariantId(),
                    new BigDecimal("2.00"),
                    BigDecimal.TEN,
                    BigDecimal.ZERO,
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(existingKept, existingToDelete));
            when(itemRepository.findById(existingKept.getId())).thenReturn(Optional.of(existingKept));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            // The dropped line is disabled and the kept one is untouched: the charge books only what remains,
            // so this path never gives a unit back.
            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(existingToDelete.getId()) && !item.getEnabled()));
            assertThat(existingKept.getEnabled()).isTrue();
            assertThat(existingKept.getQuantity()).isEqualByComparingTo(new BigDecimal("2.00"));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("a grown line carries the new quantity and books no stock")
        void inlineDiff_QuantityIncrease_BooksNoStock() {
            var reqItem = new SalesOrderItemRequest(
                    testItem.getId(),
                    testItem.getProductVariantId(),
                    new BigDecimal("7.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(new BigDecimal("7.00")) == 0));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("a shrunk line carries the new quantity and books no stock")
        void inlineDiff_QuantityDecrease_BooksNoStock() {
            var reqItem = new SalesOrderItemRequest(
                    testItem.getId(),
                    testItem.getProductVariantId(),
                    BigDecimal.ONE,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));

            salesOrderService.updateSalesOrder(orderId, request);

            // Nothing is given back and nothing is re-sold: the charge books the 1.00 the line now states, and
            // there was no earlier booking to undo.
            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(BigDecimal.ONE) == 0));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("an add, a change and a drop in one update book no stock")
        void inlineDiff_MixOfAllThree_BooksNoStock() {
            var vidNew = UUID.randomUUID();
            var vidOld = UUID.randomUUID();
            var savedItemId = UUID.randomUUID();

            // Existing item: testItem (variantId, qty 2.00) — will be kept, qty changed to 3
            var existingToDelete = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vidOld)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            var reqItemModify = new SalesOrderItemRequest(
                    testItem.getId(),
                    variantId,
                    new BigDecimal("3.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);
            var reqItemNew = new SalesOrderItemRequest(
                    null, vidNew, new BigDecimal("6.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);
            var request = new SalesOrderRequest(
                    customerId, companyStoreId, shiftId, "user123", List.of(reqItemModify, reqItemNew));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem, existingToDelete));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            // The document's three edits are three line writes and no stock work: the charge books what the
            // surviving lines state, so there is no mixture of units for this path to reconcile.
            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(new BigDecimal("3.00")) == 0));
            verify(itemRepository)
                    .save(argThat(item -> savedItemId.equals(item.getId())
                            && item.getProductVariantId().equals(vidNew)
                            && item.getQuantity().compareTo(new BigDecimal("6.00")) == 0));
            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(existingToDelete.getId()) && !item.getEnabled()));
            verifyNoStockWork();
        }

        @Test
        @DisplayName("a line that switches variant inline books no stock")
        void inlineDiff_VariantChange_BooksNoStock() {
            var vidOld = UUID.randomUUID();

            var existingItem = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vidOld)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(BigDecimal.TEN)
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            var reqItem = new SalesOrderItemRequest(
                    existingItem.getId(),
                    variantId,
                    new BigDecimal("6.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(existingItem));
            when(itemRepository.findById(existingItem.getId())).thenReturn(Optional.of(existingItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            // The line now names the new variant, which is the variant the charge will sell: the old one is
            // never given back because this path never took it.
            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(existingItem.getId())
                            && item.getProductVariantId().equals(variantId)
                            && item.getQuantity().compareTo(new BigDecimal("6.00")) == 0));
            verifyNoStockWork();
        }

        /**
         * A quantity the store cannot cover is a document state, not an edit this path can refuse.
         *
         * <p><b>What changed and why the case is kept.</b> It used to assert the engine's
         * {@code InsufficientStockException} from {@code updateSalesOrder}: the diff deducted, so a quantity
         * beyond the position failed the write. Under ADR-0002 decision 7 the diff books nothing, so the same
         * request succeeds, persists the line and asks the engine for no movement — and the refusal belongs to the
         * charge, which is where {@code SalesOrderIntegrationTest} measures it (409, no payment, no transition, no
         * movement). Stating that here is the honest retargeting of the old expectation instead of deleting it.</p>
         */
        @Test
        @DisplayName("a quantity beyond the position is accepted and books no stock, because the charge refuses it")
        void inlineDiff_BeyondStock_IsAcceptedAndBooksNoStock() {
            var reqItem = new SalesOrderItemRequest(
                    testItem.getId(),
                    testItem.getProductVariantId(),
                    new BigDecimal("5.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null);
            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(reqItem));

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(new BigDecimal("5.00")) == 0));
            verifyNoStockWork();
        }
    }

    // ─────────────────────────────────────────────
    // InsufficientStockException
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("InsufficientStockException")
    class InsufficientStockExceptionTests {

        @Test
        @DisplayName("should format message with variantId, requested, and available")
        void exceptionMessageFormat() {
            var variantId = UUID.randomUUID();
            var requested = new BigDecimal("5.00");
            var available = new BigDecimal("3.00");

            var ex = new InsufficientStockException(variantId, requested, available);

            assertThat(ex.getMessage())
                    .contains("Insufficient stock for variant")
                    .contains(variantId.toString())
                    .contains("requested 5.00")
                    .contains("available 3.00");
        }

        @Test
        @DisplayName("should expose variantId, requested, available via getters")
        void exceptionGetters() {
            var variantId = UUID.randomUUID();
            var requested = new BigDecimal("10.00");
            var available = new BigDecimal("2.00");

            var ex = new InsufficientStockException(variantId, requested, available);

            assertThat(ex.getVariantId()).isEqualTo(variantId);
            assertThat(ex.getRequested()).isEqualByComparingTo(requested);
            assertThat(ex.getAvailable()).isEqualByComparingTo(available);
        }

        @Test
        @DisplayName("should be a RuntimeException subclass")
        void exceptionIsRuntimeException() {
            var ex = new InsufficientStockException(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO);

            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    // ─────────────────────────────────────────────
    // The typed state machine and the history it writes (ADR-0004 T5)
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("state moves and their history rows")
    class StatusHistoryTests {

        @Test
        @DisplayName("creation writes the order's creation row and one per line, in the same transaction")
        void createSalesOrder_recordsEveryCreationRow() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.SALES_ORDER), anyString()))
                    .thenReturn(1L);
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(invocation -> {
                var order = invocation.getArgument(0, SalesOrder.class);
                order.setId(orderId);
                return order;
            });
            var createdLines = new ArrayList<SalesOrderItem>();
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(invocation -> {
                var line = invocation.getArgument(0, SalesOrderItem.class);
                line.setId(UUID.randomUUID());
                createdLines.add(line);
                return line;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenAnswer(invocation -> createdLines);
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

            var itemRequests = List.of(
                    new SalesOrderItemRequest(
                            null, variantId, new BigDecimal("1.00"), new BigDecimal("10.00"), BigDecimal.ZERO, null),
                    new SalesOrderItemRequest(
                            null, variantId, new BigDecimal("1.00"), new BigDecimal("20.00"), BigDecimal.ZERO, null));
            var request = new SalesOrderRequest(customerId, companyStoreId, null, "user123", itemRequests);

            var result = salesOrderService.createSalesOrder(request);

            // ADR-0004 decision 5 side A: the document is born with its history, or the commit is refused.
            verify(statusTransitionRecorder)
                    .recordCreation(StatusSubject.SALES_ORDER, companyId, orderId, SalesOrderState.DRAFT);
            verify(statusTransitionRecorder, times(2))
                    .recordCreation(
                            eq(StatusSubject.SALES_ORDER_ITEM),
                            eq(companyId),
                            any(UUID.class),
                            eq(SalesOrderItemState.PENDING));
            assertThat(result.status())
                    .as("the response carries the code of the state")
                    .isEqualTo("DRAFT");
            assertThat(result.items()).hasSize(2).allMatch(item -> "PENDING".equals(item.status()));
        }

        @Test
        @DisplayName("a move writes one row with both ends, and the state and the catalog pointer move together")
        void updateSalesOrderStatus_recordsOneMoveAndKeepsThePairCoherent() {
            var statusRequest = new UpdateSalesOrderStatusRequest(activoStatus.getId());
            var captor = ArgumentCaptor.forClass(SalesOrder.class);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(activoStatus.getId())).thenReturn(SalesOrderState.ACTIVE);
            when(salesOrderRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());

            var result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            var moved = captor.getValue();
            assertThat(moved.getStatus())
                    .as("the enum's column is written, not only read")
                    .isEqualTo(SalesOrderState.ACTIVE);
            verify(statusTransitionRecorder)
                    .recordMove(
                            StatusSubject.SALES_ORDER,
                            companyId,
                            orderId,
                            SalesOrderState.DRAFT,
                            SalesOrderState.ACTIVE,
                            "status update");
            assertThat(result.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a forbidden move is a 409 that writes no row, no state and no stock")
        void updateSalesOrderStatus_forbiddenMove_WritesNothing() {
            var statusRequest = new UpdateSalesOrderStatusRequest(cerradaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusCatalog.requireOrderState(cerradaStatus.getId())).thenReturn(SalesOrderState.COMPLETED);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("DRAFT")
                    .hasMessageContaining("COMPLETED");

            assertThat(testOrder.getStatus())
                    .as("the refused move left the order where it was")
                    .isEqualTo(SalesOrderState.DRAFT);
            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verifyNoInteractions(statusTransitionRecorder);
            verifyNoInteractions(stockLedgerEngine);
        }

        @Test
        @DisplayName("a forbidden cancel restores no stock: the guard runs before the reversal")
        void updateSalesOrderStatus_forbiddenCancel_RestoresNothing() {
            // A COMPLETED order is terminal, so CANCELLED is not one of its successors and the reversal the
            // cancel path would run must never happen (ADR-0004 T2: the row is left untouched).
            var completedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.COMPLETED)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(completedOrder));
            when(statusCatalog.requireOrderState(canceladaStatus.getId())).thenReturn(SalesOrderState.CANCELLED);

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("COMPLETED")
                    .hasMessageContaining("CANCELLED");

            verifyNoInteractions(stockLedgerEngine);
            verifyNoInteractions(statusTransitionRecorder);
            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("charge moves the order and only the lines that are waiting for it")
        void chargeSalesOrder_movesTheWaitingLinesOnly() {
            var paymentMethodId = UUID.randomUUID();
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .companyId(companyId)
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .status(SalesOrderState.PENDING)
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
            var waitingLine = lineIn(SalesOrderItemState.PENDING);
            var chargedLine = lineIn(SalesOrderItemState.ADDED);
            var cancelledLine = lineIn(SalesOrderItemState.CANCELLED);

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(pendingOrder));
            when(itemRepository.findBySalesOrderId(orderId))
                    .thenReturn(List.of(waitingLine, chargedLine, cancelledLine));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // The document's own money, read from the schema: three lines of 1 x 10.00 — waiting, already
            // charged and cancelled, all still enabled — so the sum is 30.0000 and the total is 30.00.
            when(itemRepository.sumMoneyParts(orderId))
                    .thenReturn(moneyPartsRow(
                            new BigDecimal("30.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000")));

            salesOrderService.chargeSalesOrder(orderId, chargeOf(paymentMethodId, "30.0000"));

            verify(statusTransitionRecorder)
                    .recordMove(
                            StatusSubject.SALES_ORDER,
                            companyId,
                            orderId,
                            SalesOrderState.PENDING,
                            SalesOrderState.COMPLETED,
                            "charge");
            // One line move and no more: ADDED is already charged and CANCELLED is terminal, so neither has
            // an edge to record — the rule that used to be `!"Cancelled".equals(statusName)`.
            verify(statusTransitionRecorder, times(1))
                    .recordMove(
                            eq(StatusSubject.SALES_ORDER_ITEM),
                            eq(companyId),
                            eq(waitingLine.getId()),
                            eq(SalesOrderItemState.PENDING),
                            eq(SalesOrderItemState.ADDED),
                            eq("charge"));
            verify(statusTransitionRecorder, times(2))
                    .recordMove(any(StatusSubject.class), any(), any(), any(), any(), any());
            assertThat(waitingLine.getStatus()).isEqualTo(SalesOrderItemState.ADDED);
            assertThat(chargedLine.getStatus())
                    .as("an already charged line is not moved to itself")
                    .isEqualTo(SalesOrderItemState.ADDED);
            assertThat(cancelledLine.getStatus()).isEqualTo(SalesOrderItemState.CANCELLED);
        }

        private SalesOrderItem lineIn(SalesOrderItemState state) {
            return SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(new BigDecimal("10.00"))
                    .status(state)
                    .enabled(true)
                    .build();
        }
    }

    // ─── The line's tax snapshot (ADR-0002 decision 6, S1 and S6) ───────

    /**
     * The copy's two halves, as only a unit suite can separate them: <b>when</b> a sale asks the catalog and the
     * rate table and when it must not, and what it does with each of the two answers.
     *
     * <p>What a rate row is and which one a date resolves belongs to {@code TaxRateServiceTest}; what the stored
     * rate becomes on the row — {@code tax_amount} and {@code final_price} — belongs to
     * {@code SalesOrderIntegrationTest}, because a trigger does the arithmetic and a mock runs no trigger. This
     * suite pins the asking.</p>
     */
    @Nested
    @DisplayName("the line's tax snapshot: resolved when a row is born, never when an existing one is edited")
    class LineTaxSnapshotTests {

        @Test
        @DisplayName("adding a line asks for the product's code and the document's date, and stores both")
        void addItem_asksForTheCodeAndTheDocumentDate_andStoresBoth() {
            sellableVariant();
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("IVA"));
            when(taxRateService.resolve(
                            companyId, "IVA", testOrder.getOrderDate().toLocalDate()))
                    .thenReturn(Optional.of(new TaxRateResolution(
                            UUID.randomUUID(), "IVA", new BigDecimal("0.160000"), LocalDate.of(2026, 1, 1))));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            var written = ArgumentCaptor.forClass(SalesOrderItem.class);
            verify(itemRepository).save(written.capture());
            assertThat(written.getValue().getTaxCode())
                    .as("the code comes from the sold variant's product; the request has no field for one (S2)")
                    .isEqualTo("IVA");
            assertThat(written.getValue().getTaxRate())
                    .as("the rate the document's date resolved is copied onto the line, not left to the schema")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            verify(taxRateService)
                    .resolve(companyId, "IVA", testOrder.getOrderDate().toLocalDate());
        }

        @Test
        @DisplayName("a code that needs a rate and has none refuses the line, naming the code and the date")
        void addItem_withNoRate_isRefusedNamingTheCodeAndTheDate() {
            sellableVariant();
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("IVA"));
            when(taxRateService.resolve(any(), any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .as("the handler maps this class to 400, which is the shape the currency path already uses "
                            + "for the same sentence: a value the catalog does not back is the caller's to fix")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IVA")
                    .hasMessageContaining(testOrder.getOrderDate().toLocalDate().toString());

            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("a zero-by-definition code answers 0 without asking the rate table at all (D-a)")
        void addItem_withAZeroByDefinitionCode_neverAsksTheRateTable() {
            sellableVariant();
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("EXENTO"));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            var written = ArgumentCaptor.forClass(SalesOrderItem.class);
            verify(itemRepository).save(written.capture());
            assertThat(written.getValue().getTaxCode())
                    .as("the code travels even though the rate is zero: Exento and Tasa 0% are two different "
                            + "zeroes and only the code tells them apart")
                    .isEqualTo("EXENTO");
            assertThat(written.getValue().getTaxRate()).isEqualByComparingTo(BigDecimal.ZERO);
            verifyNoInteractions(taxRateService);
        }

        @Test
        @DisplayName("editing an existing line never resolves anything: its stored rate is the snapshot")
        void updateItem_neverResolvesARate() {
            var taxedLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .taxCode("IVA")
                    .taxRate(new BigDecimal("0.160000"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(taxedLine));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            salesOrderService.updateSalesOrderItem(
                    orderId,
                    itemId,
                    new SalesOrderItemRequest(
                            itemId,
                            variantId,
                            new BigDecimal("3.00"),
                            new BigDecimal("100.00"),
                            BigDecimal.ZERO,
                            null));

            // Decision 6: the rate was copied when the row was created and a later edit cannot restate it.
            // Re-resolving here could only pick up a rate stated after the sale, and the document's date — the
            // thing a resolution is about — did not move.
            verifyNoInteractions(taxRateService);
            verify(productVariantRepository, never()).findProductTaxCodeByVariantId(any());
            assertThat(taxedLine.getTaxCode()).isEqualTo("IVA");
            assertThat(taxedLine.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.160000"));
            assertThat(taxedLine.getQuantity()).isEqualByComparingTo(new BigDecimal("3.00"));
        }

        @Test
        @DisplayName("a line moved to another variant resolves again: it is no longer the same article")
        void updateItem_toAnotherVariant_takesTheNewArticlesSnapshot() {
            var otherVariantId = UUID.randomUUID();
            var taxedLine = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .taxCode("IVA")
                    .taxRate(new BigDecimal("0.160000"))
                    .status(SalesOrderItemState.PENDING)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(taxedLine));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(otherVariantId, companyId))
                    .thenReturn(true);
            when(productVariantRepository.findProductTaxCodeByVariantId(otherVariantId))
                    .thenReturn(Optional.of("IVA"));
            // A rate the OLD line did not carry. An 8% answer here is only reachable if the resolution really
            // ran again: the stored snapshot says 16%, and re-resolution is the one thing an edit may not do
            // for a line that stays the same article.
            when(taxRateService.resolve(
                            companyId, "IVA", testOrder.getOrderDate().toLocalDate()))
                    .thenReturn(Optional.of(new TaxRateResolution(
                            UUID.randomUUID(), "IVA", new BigDecimal("0.080000"), LocalDate.of(2026, 1, 1))));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            salesOrderService.updateSalesOrderItem(
                    orderId,
                    itemId,
                    new SalesOrderItemRequest(
                            itemId,
                            otherVariantId,
                            new BigDecimal("2.00"),
                            new BigDecimal("100.00"),
                            BigDecimal.ZERO,
                            null));

            assertThat(taxedLine.getProductVariantId()).isEqualTo(otherVariantId);
            assertThat(taxedLine.getTaxCode())
                    .as("a different article is a different fact, not a rate change: its product declares the "
                            + "code, so the previous article's snapshot cannot survive the swap")
                    .isEqualTo("IVA");
            assertThat(taxedLine.getTaxRate())
                    .as("the document's date resolves the new article's rate; the notes on the same-variant case "
                            + "explain why it must not run there")
                    .isEqualByComparingTo(new BigDecimal("0.080000"));
        }

        /** The three answers every line write needs before it reaches the tax: the order, its store and the variant. */
        private void sellableVariant() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId))
                    .thenReturn(true);
        }
    }
}
