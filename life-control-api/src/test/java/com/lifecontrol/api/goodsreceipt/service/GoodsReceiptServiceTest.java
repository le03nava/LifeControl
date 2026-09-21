package com.lifecontrol.api.goodsreceipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.exception.DisabledProductVariantException;
import com.lifecontrol.api.goodsreceipt.exception.DisabledPurchaseOrderException;
import com.lifecontrol.api.goodsreceipt.exception.DisabledReceivingLocationException;
import com.lifecontrol.api.goodsreceipt.exception.DuplicateReceiptLineException;
import com.lifecontrol.api.goodsreceipt.exception.InvalidReceiptQuantityException;
import com.lifecontrol.api.goodsreceipt.exception.MissingPurchaseOrderVariantException;
import com.lifecontrol.api.goodsreceipt.exception.OverReceiptException;
import com.lifecontrol.api.goodsreceipt.exception.PurchaseOrderVariantNotInStoreException;
import com.lifecontrol.api.goodsreceipt.exception.ReceiptNumberTooLongException;
import com.lifecontrol.api.goodsreceipt.exception.UnreceivableDetailStatusException;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.exception.StoreLocationNotInStoreException;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderDetailNotFoundException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderNotFoundException;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage of {@link GoodsReceiptService#createReceipt}: the full validation matrix, the
 * all-or-nothing guarantee (no collaborator write on any rejection), the lock-first order, the
 * per-order receipt number and the fail-closed length guard. Persistence and the real transaction
 * are covered by the integration tests on PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GoodsReceiptService Tests")
class GoodsReceiptServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c6");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID PO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID DETAIL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID VARIANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final UUID STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID RECEIPT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e6");
    private static final UUID OTHER_PO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e7");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Mock
    private PurchaseOrderService purchaseOrderService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private StoreLocationRepository storeLocationRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private GoodsReceiptService service;

    private CompanyStore store;
    private PurchaseOrder purchaseOrder;
    private PurchaseOrderDetail detail;
    private ProductVariant variant;
    private StoreLocation location;
    private Status registeredStatus;

    @BeforeEach
    void setUp() {
        service = new GoodsReceiptService(
                purchaseOrderRepository,
                purchaseOrderDetailRepository,
                purchaseOrderService,
                inventoryService,
                goodsReceiptRepository,
                storeInventorySettingsRepository,
                storeLocationRepository,
                productVariantStoreStockRepository,
                statusRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("GR-KEY").build();
        var companyCountry =
                CompanyCountry.builder().id(COMPANY_COUNTRY_ID).company(company).build();
        var companyRegion = CompanyRegion.builder()
                .id(REGION_ID)
                .companyCountry(companyCountry)
                .build();
        var companyZone =
                CompanyZone.builder().id(ZONE_ID).companyRegion(companyRegion).build();
        store = CompanyStore.builder()
                .id(STORE_ID)
                .companyZone(companyZone)
                .storeName("Store")
                .enabled(true)
                .build();

        variant = ProductVariant.builder()
                .id(VARIANT_ID)
                .barCode("7501234567890")
                .variantName("Talla M")
                .enabled(true)
                .build();

        purchaseOrder = PurchaseOrder.builder()
                .id(PO_ID)
                .orderNumber("PO-20260603-00001")
                .companyStore(store)
                .enabled(true)
                .build();

        detail = PurchaseOrderDetail.builder()
                .id(DETAIL_ID)
                .purchaseOrder(purchaseOrder)
                .productVariant(variant)
                .quantity(5)
                .receivedQuantity(0)
                .enabled(true)
                .build();

        location = StoreLocation.builder()
                .id(LOCATION_ID)
                .locationCode("GRL1")
                .locationName("Receiving")
                .enabled(true)
                .build();

        var statusType = new StatusType();
        statusType.setId(UUID.randomUUID());
        statusType.setStatusTypeName("GOODS_RECEIPT");
        registeredStatus = new Status();
        registeredStatus.setId(STATUS_ID);
        registeredStatus.setStatusName("Registered");
        registeredStatus.setStatusType(statusType);
    }

    // ── Fixtures and stubs ──────────────────────────────────────────────

    private GoodsReceiptLineRequest line(UUID detailId, String quantity) {
        return new GoodsReceiptLineRequest(detailId, new BigDecimal(quantity), "line comment");
    }

    private GoodsReceiptRequest requestWith(UUID receivingLocationId, GoodsReceiptLineRequest... lines) {
        return new GoodsReceiptRequest(PO_ID, receivingLocationId, "Reception", List.of(lines));
    }

    private void stubExistingPurchaseOrder() {
        when(purchaseOrderRepository.findByIdForUpdate(PO_ID)).thenReturn(Optional.of(purchaseOrder));
    }

    private void stubResolvedLine() {
        when(purchaseOrderDetailRepository.findById(DETAIL_ID)).thenReturn(Optional.of(detail));
        when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(storeLocationRepository.findEnabledByCompanyStoreId(STORE_ID)).thenReturn(List.of(location));
        when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                .thenReturn(true);
    }

    private void stubHappyPath() {
        stubExistingPurchaseOrder();
        stubResolvedLine();
        when(storeInventorySettingsRepository.findById(STORE_ID))
                .thenReturn(Optional.of(StoreInventorySettings.builder()
                        .companyStoreId(STORE_ID)
                        .receivingLocationId(LOCATION_ID)
                        .salesLocationId(OTHER_LOCATION_ID)
                        .build()));
        when(goodsReceiptRepository.countByPurchaseOrderId(PO_ID)).thenReturn(0L);
        when(statusRepository.findByTypeNameAndStatusName("GOODS_RECEIPT", "Registered"))
                .thenReturn(Optional.of(registeredStatus));
        when(currentUserContext.getUsername()).thenReturn("receiver");
        when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(invocation -> {
            GoodsReceipt saved = invocation.getArgument(0);
            saved.setId(RECEIPT_ID);
            return saved;
        });
    }

    /** Every writer of the use case: a rejection must not touch any of them. */
    private void assertNoWrites() {
        verifyNoInteractions(inventoryService);
        verify(purchaseOrderService, never()).registerReceivedQuantity(any(), any(), anyInt());
        verify(goodsReceiptRepository, never()).save(any(GoodsReceipt.class));
        verify(storeInventorySettingsRepository, never()).save(any(StoreInventorySettings.class));
        verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        verify(purchaseOrderDetailRepository, never()).save(any(PurchaseOrderDetail.class));
        verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPathTests {

        @Test
        @DisplayName("should persist the receipt, apply the effects and return the mapped document")
        void registersReceiptWithEffectsAndResponse() {
            stubHappyPath();

            var response = service.createReceipt(requestWith(null, line(DETAIL_ID, "3")));

            assertThat(response.id()).isEqualTo(RECEIPT_ID);
            assertThat(response.receiptNumber()).isEqualTo("GR-PO-20260603-00001-01");
            assertThat(response.purchaseOrderId()).isEqualTo(PO_ID);
            assertThat(response.orderNumber()).isEqualTo("PO-20260603-00001");
            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.receivingLocationId()).isEqualTo(LOCATION_ID);
            assertThat(response.statusId()).isEqualTo(STATUS_ID);
            assertThat(response.statusName()).isEqualTo("Registered");
            assertThat(response.receivedBy()).isEqualTo("receiver");
            assertThat(response.comments()).isEqualTo("Reception");
            assertThat(response.enabled()).isTrue();
            assertThat(response.lines()).hasSize(1);
            assertThat(response.lines().getFirst().purchaseOrderDetailId()).isEqualTo(DETAIL_ID);
            assertThat(response.lines().getFirst().productVariantId()).isEqualTo(VARIANT_ID);
            assertThat(response.lines().getFirst().quantityReceived()).isEqualByComparingTo("3");

            verify(inventoryService)
                    .applyReceipt(
                            VARIANT_ID,
                            STORE_ID,
                            LOCATION_ID,
                            new BigDecimal("3"),
                            "GOODS_RECEIPT",
                            RECEIPT_ID,
                            "receiver");
            verify(purchaseOrderService).registerReceivedQuantity(PO_ID, DETAIL_ID, 3);
        }

        @Test
        @DisplayName("should use the store-configured receiving location when the request has no override")
        void usesConfiguredReceivingLocation() {
            stubHappyPath();

            service.createReceipt(requestWith(null, line(DETAIL_ID, "1")));

            verify(storeInventorySettingsRepository).findById(STORE_ID);
            verify(inventoryService)
                    .applyReceipt(
                            VARIANT_ID, STORE_ID, LOCATION_ID, BigDecimal.ONE, "GOODS_RECEIPT", RECEIPT_ID, "receiver");
        }

        @Test
        @DisplayName("should pass the accumulated total, not the delta, to registerReceivedQuantity")
        void passesAccumulatedTotal() {
            stubHappyPath();
            detail.setReceivedQuantity(2);

            service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "3")));

            verify(purchaseOrderService).registerReceivedQuantity(PO_ID, DETAIL_ID, 5);
        }

        @Test
        @DisplayName("should write the ledger movement before the line status change")
        void writesLedgerMovementBeforeStatusChange() {
            stubHappyPath();

            service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "3")));

            InOrder order = inOrder(inventoryService, purchaseOrderService);
            order.verify(inventoryService)
                    .applyReceipt(
                            VARIANT_ID,
                            STORE_ID,
                            LOCATION_ID,
                            new BigDecimal("3"),
                            "GOODS_RECEIPT",
                            RECEIPT_ID,
                            "receiver");
            order.verify(purchaseOrderService).registerReceivedQuantity(PO_ID, DETAIL_ID, 3);
        }
    }

    // ── Step 1: lock ────────────────────────────────────────────────────

    @Nested
    @DisplayName("purchase order resolution")
    class PurchaseOrderResolutionTests {

        @Test
        @DisplayName("should fail with PurchaseOrderNotFoundException when the locked order is missing")
        void lockedOrderMissing() {
            when(purchaseOrderRepository.findByIdForUpdate(PO_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);

            assertNoWrites();
            verifyNoInteractions(storeInventorySettingsRepository);
        }
    }

    // ── Step 2: tenant isolation ────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("should propagate AccessDeniedException before resolving anything else")
        void accessDeniedStopsTheUseCase() {
            stubExistingPurchaseOrder();
            doThrow(new AccessDeniedException("denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(any(), any(), any(), any(), any());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(AccessDeniedException.class);

            verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
            verify(purchaseOrderService, never()).requireReceivable(any(PurchaseOrder.class));
            verifyNoInteractions(storeInventorySettingsRepository);
            assertNoWrites();
        }
    }

    // ── Step 3: receptivity and enabled ─────────────────────────────────

    @Nested
    @DisplayName("receptivity")
    class ReceptivityTests {

        @Test
        @DisplayName("should propagate the reception guard's InvalidStatusTransitionException")
        void notReceivableStatus() {
            stubExistingPurchaseOrder();
            doThrow(new InvalidStatusTransitionException("Draft", "reception"))
                    .when(purchaseOrderService)
                    .requireReceivable(purchaseOrder);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(InvalidStatusTransitionException.class);

            assertNoWrites();
            verifyNoInteractions(storeInventorySettingsRepository);
        }

        @Test
        @DisplayName("should reject a disabled order with a precise message and no write")
        void disabledOrder() {
            stubExistingPurchaseOrder();
            purchaseOrder.setEnabled(false);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(DisabledPurchaseOrderException.class)
                    .hasMessageContaining("PO-20260603-00001");

            assertNoWrites();
            verifyNoInteractions(storeInventorySettingsRepository);
        }
    }

    // ── Step 4: receiving location ──────────────────────────────────────

    @Nested
    @DisplayName("receiving location")
    class ReceivingLocationTests {

        @Test
        @DisplayName("should fail when there is neither an override nor configured settings")
        void noSettingsAndNoOverride() {
            stubExistingPurchaseOrder();
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReceipt(requestWith(null, line(DETAIL_ID, "1"))))
                    .isInstanceOf(StoreInventorySettingsNotFoundException.class);

            assertNoWrites();
            verifyNoInteractions(storeLocationRepository);
        }

        @Test
        @DisplayName("should reject an unknown location with a 404")
        void unknownLocation() {
            stubExistingPurchaseOrder();
            when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(StoreLocationNotFoundException.class);

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a disabled location before checking ownership")
        void disabledLocation() {
            stubExistingPurchaseOrder();
            when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
            location.setEnabled(false);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(DisabledReceivingLocationException.class);

            // The enabled check must run first: the store-scoped finder only lists enabled locations,
            // so consulting it here would misreport a disabled location of the correct store.
            verify(storeLocationRepository, never()).findEnabledByCompanyStoreId(any());
            assertNoWrites();
        }

        @Test
        @DisplayName("should reject an enabled location that belongs to another store")
        void foreignLocation() {
            stubExistingPurchaseOrder();
            when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
            when(storeLocationRepository.findEnabledByCompanyStoreId(STORE_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(StoreLocationNotInStoreException.class);

            assertNoWrites();
        }
    }

    // ── Step 5: whole-request validation ────────────────────────────────

    @Nested
    @DisplayName("line validation")
    class LineValidationTests {

        @BeforeEach
        void stubBeforeValidation() {
            stubExistingPurchaseOrder();
            when(storeInventorySettingsRepository.findById(STORE_ID))
                    .thenReturn(Optional.of(StoreInventorySettings.builder()
                            .companyStoreId(STORE_ID)
                            .receivingLocationId(LOCATION_ID)
                            .salesLocationId(OTHER_LOCATION_ID)
                            .build()));
            when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
            when(storeLocationRepository.findEnabledByCompanyStoreId(STORE_ID)).thenReturn(List.of(location));
            when(purchaseOrderDetailRepository.findById(DETAIL_ID)).thenReturn(Optional.of(detail));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                    .thenReturn(true);
        }

        @Test
        @DisplayName("should reject a detail that does not exist")
        void detailMissing() {
            when(purchaseOrderDetailRepository.findById(DETAIL_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a detail that belongs to another purchase order")
        void detailNotInOrder() {
            var otherOrder = PurchaseOrder.builder().id(OTHER_PO_ID).build();
            detail.setPurchaseOrder(otherOrder);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a disabled detail")
        void detailDisabled() {
            detail.setEnabled(false);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);

            assertNoWrites();
        }

        @Test
        @DisplayName("should fail closed on a NULL-variant line naming the line index")
        void nullVariantLineFailsClosed() {
            detail.setProductVariant(null);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(MissingPurchaseOrderVariantException.class)
                    .hasMessageContaining("Reception line 0");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a variant with no per-store row in the order's store")
        void foreignVariant() {
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(VARIANT_ID, STORE_ID))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(PurchaseOrderVariantNotInStoreException.class)
                    .hasMessageContaining("Reception line 0");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a disabled variant before any write, naming the line")
        void disabledVariantIsRejectedBeforeAnyWrite() {
            // The variant is disabled after the order was raised; applyReceipt locks with
            // enabled = true, so without this pre-write check the receipt row would be written first.
            variant.setEnabled(false);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(DisabledProductVariantException.class)
                    .hasMessageContaining("Reception line 0")
                    .hasMessageContaining(VARIANT_ID.toString());

            assertNoWrites();
        }

        @Test
        @DisplayName(
                "should reject a detail status that cannot reach a received state before any write, naming the line")
        void unreceivableDetailStatusIsRejectedBeforeAnyWrite() {
            // The receipt service delegates the rule to PurchaseOrderService and adds the line index;
            // letting registerReceivedQuantity reject it would happen after the receipt was written.
            doThrow(new InvalidStatusTransitionException("Cancelled", "reception"))
                    .when(purchaseOrderService)
                    .requireDetailReceivable(detail);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(UnreceivableDetailStatusException.class)
                    .hasMessageContaining("Reception line 0")
                    .hasCauseInstanceOf(InvalidStatusTransitionException.class);

            verify(purchaseOrderService).requireDetailReceivable(detail);
            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a zero quantity")
        void zeroQuantity() {
            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "0"))))
                    .isInstanceOf(InvalidReceiptQuantityException.class)
                    .hasMessageContaining("greater than zero");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a negative quantity")
        void negativeQuantity() {
            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "-1"))))
                    .isInstanceOf(InvalidReceiptQuantityException.class)
                    .hasMessageContaining("greater than zero");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a non-integral quantity such as 2.5")
        void nonIntegralQuantity() {
            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "2.5"))))
                    .isInstanceOf(InvalidReceiptQuantityException.class)
                    .hasMessageContaining("whole number");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject an over-receipt computed from the accumulated quantity")
        void overReceipt() {
            detail.setReceivedQuantity(4);

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "2"))))
                    .isInstanceOf(OverReceiptException.class)
                    .hasMessageContaining("Reception line 0")
                    .hasMessageContaining("accumulated 6");

            assertNoWrites();
        }

        @Test
        @DisplayName("should reject a repeated purchase-order line in one request")
        void duplicateLine() {
            assertThatThrownBy(() ->
                            service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"), line(DETAIL_ID, "1"))))
                    .isInstanceOf(DuplicateReceiptLineException.class)
                    .hasMessageContaining("Reception line 1");

            assertNoWrites();
        }
    }

    // ── Step 6: receipt number ──────────────────────────────────────────

    @Nested
    @DisplayName("receipt number")
    class ReceiptNumberTests {

        @Test
        @DisplayName("should number the first receipt GR-<order>-01")
        void firstReceiptNumber() {
            stubHappyPath();
            when(goodsReceiptRepository.countByPurchaseOrderId(PO_ID)).thenReturn(0L);

            var response = service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1")));

            assertThat(response.receiptNumber()).isEqualTo("GR-PO-20260603-00001-01");
        }

        @Test
        @DisplayName("should number the second receipt GR-<order>-02")
        void secondReceiptNumber() {
            stubHappyPath();
            when(goodsReceiptRepository.countByPurchaseOrderId(PO_ID)).thenReturn(1L);

            var response = service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1")));

            assertThat(response.receiptNumber()).isEqualTo("GR-PO-20260603-00001-02");
        }

        @Test
        @DisplayName("should fail closed instead of truncating a number over 30 characters")
        void receiptNumberTooLongFailsClosed() {
            stubHappyPath();
            purchaseOrder.setOrderNumber("PO-20260603-00001-ABCDEFGHI");

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(ReceiptNumberTooLongException.class)
                    .hasMessageContaining("exceeds the maximum length of 30");

            assertNoWrites();
            verifyNoInteractions(statusRepository);
        }
    }

    // ── Step 7: status resolution ───────────────────────────────────────

    @Nested
    @DisplayName("receipt status")
    class ReceiptStatusTests {

        @Test
        @DisplayName("should fail with StatusNotFoundException when GOODS_RECEIPT/Registered is missing")
        void registeredStatusMissing() {
            stubHappyPath();
            when(statusRepository.findByTypeNameAndStatusName("GOODS_RECEIPT", "Registered"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createReceipt(requestWith(LOCATION_ID, line(DETAIL_ID, "1"))))
                    .isInstanceOf(StatusNotFoundException.class);

            assertNoWrites();
        }
    }
}
