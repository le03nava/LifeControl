package com.lifecontrol.api.salesorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.salesorder.exception.SalesOrderAlreadyFinalizedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotModifiableException;
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderStoreReassignmentNotAllowedException;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    private InventoryService inventoryService;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
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
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        activoStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Active")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        enviadaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pending")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        canceladaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelled")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        cerradaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Completed")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        pendienteItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pending")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        agregadoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Added")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        canceladoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelled")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        var now = LocalDateTime.now();

        testOrder = SalesOrder.builder()
                .id(orderId)
                .orderNumber("SO-20260610-00001")
                .customerId(customerId)
                .companyStoreId(companyStoreId)
                .shiftId(shiftId)
                .userId("user123")
                .orderDate(now)
                .statusId(borradorStatus.getId())
                .totalAmount(BigDecimal.ZERO)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testOrderRequest = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", null);

        testItem = SalesOrderItem.builder()
                .id(itemId)
                .salesOrderId(orderId)
                .productVariantId(variantId)
                .quantity(new BigDecimal("2.00"))
                .listPrice(new BigDecimal("100.00"))
                .discountApplied(new BigDecimal("10.00"))
                .finalPrice(new BigDecimal("90.00"))
                .statusId(pendienteItemStatus.getId())
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testItemRequest = new SalesOrderItemRequest(
                null, variantId, new BigDecimal("2.00"), new BigDecimal("100.00"), new BigDecimal("10.00"), null);

        // The stock-delta tests are about delta arithmetic, not about the gate that forbids selling a
        // soft-deleted definition, so the default is "sellable" and the tests that care about the
        // gate override it. Lenient because most tests never reach a deduction.
        lenient()
                .when(productVariantRepository.existsByIdAndEnabledTrue(any(UUID.class)))
                .thenReturn(true);
        // updateSalesOrder reads the terminal item status to tell a line that still holds stock from
        // one whose stock was already given back. Lenient because only the item-diff tests reach it.
        lenient()
                .when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Cancelled"))
                .thenReturn(Optional.of(canceladoItemStatus));
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
     * Asserts the exact deduction the service asked the engine for: variant, store, quantity and
     * the line-level ledger reference (W3-D6). This is the unit-level replacement for the old
     * assertion on the mocked per-store row; the resulting stock itself is asserted against real
     * PostgreSQL in {@code SalesOrderIntegrationTest} and against the mocked rows in
     * {@code InventoryServiceSaleMovementTest}.
     */
    private void verifyDeducted(UUID variantId, String quantity, UUID itemId) {
        verify(inventoryService)
                .applySaleDeduction(
                        eq(variantId),
                        eq(companyStoreId),
                        argThat(actual -> actual.compareTo(new BigDecimal(quantity)) == 0),
                        eq("SALES_ORDER_ITEM"),
                        eq(itemId),
                        any());
    }

    private void verifyReversed(UUID itemId) {
        verify(inventoryService).applySaleReversal(eq("SALES_ORDER_ITEM"), eq(itemId), any());
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
    }

    // ─────────────────────────────────────────────
    // createSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createSalesOrder")
    class CreateSalesOrderTests {

        @Test
        @DisplayName("should create sales order with Draft status and auto-generated order number")
        void createSalesOrder_Success() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(openShift("ABIERTO")));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Draft"))
                    .thenReturn(Optional.of(borradorStatus));
            when(salesOrderRepository.findTopByOrderNumberStartingWithOrderByOrderNumberDesc(any()))
                    .thenReturn(Optional.empty());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            SalesOrderResponse result = salesOrderService.createSalesOrder(testOrderRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(orderId);
            assertThat(result.orderNumber()).startsWith("SO-");
            assertThat(result.orderNumber()).containsPattern("SO-\\d{8}-\\d{5}");
            assertThat(result.statusName()).isEqualTo("Draft");
            assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.enabled()).isTrue();
            verify(salesOrderRepository).save(any(SalesOrder.class));
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
            verifyNoInteractions(inventoryService);
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
                    .statusId(canceladaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, testOrderRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already Cancelled")
                    .hasMessageContaining("cannot be modified");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verifyNoInteractions(inventoryService);
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(canceladoItemStatus.getId())
                    .enabled(true)
                    .build();
            var lineRequest = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.findById(companyStoreId)).thenReturn(Optional.of(testCompanyStore));
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(cancelledLine));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(cancelledLine));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(cancelledLine);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);

            var request = new SalesOrderRequest(customerId, companyStoreId, shiftId, "user123", List.of(lineRequest));
            salesOrderService.updateSalesOrder(orderId, request);

            // The terminal item status is the only signal that the line holds nothing, so the guard
            // must read the "SALES_ORDER_ITEM"/"Cancelled" status. With the lookup broken the line
            // would look like it still held 4 and the engine would deduct the 1-unit delta.
            verifyNoInteractions(inventoryService);
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrder")
    class DeleteSalesOrderTests {

        @Test
        @DisplayName("should soft-delete sales order and all items with stock restoration")
        void deleteSalesOrder_Success() {
            var variantId2 = UUID.randomUUID();
            var item2Id = UUID.randomUUID();

            var item2 = SalesOrderItem.builder()
                    .id(item2Id)
                    .salesOrderId(orderId)
                    .productVariantId(variantId2)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem, item2));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem, item2));

            salesOrderService.deleteSalesOrder(orderId);

            verify(salesOrderRepository).findById(orderId);
            verify(salesOrderRepository).save(any(SalesOrder.class));
            verify(itemRepository).findBySalesOrderId(orderId);
            verify(itemRepository, times(2)).save(any(SalesOrderItem.class));
            // Every line is restored through the engine under its own ledger reference (W3-D6).
            verifyReversed(itemId);
            verifyReversed(item2Id);
        }

        @Test
        @DisplayName("should not restore stock again when deleting an already-cancelled order")
        void deleteSalesOrder_AlreadyCancelled_NoStockRestoration() {
            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(canceladaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));

            salesOrderService.deleteSalesOrder(orderId);

            verify(salesOrderRepository).save(any(SalesOrder.class));
            verify(itemRepository).save(any(SalesOrderItem.class));
            // A cancelled order already restored its stock — delete must NOT ask the engine again.
            verifyNoInteractions(inventoryService);
        }

        @Test
        @DisplayName("should restore stock exactly once when cancelling then deleting the order")
        void deleteSalesOrder_AfterCancel_RestoresStockOnce() {
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            var cancelledOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(canceladaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            // ── Cancel transition ──
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of(testItem))
                    .thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(cancelledOrder);

            salesOrderService.updateSalesOrderStatus(orderId, statusRequest);
            verifyReversed(itemId);

            // ── Delete after cancel ──
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));

            salesOrderService.deleteSalesOrder(orderId);

            // Reversed exactly once (only during cancel), never again on delete.
            verify(inventoryService, times(1)).applySaleReversal(eq("SALES_ORDER_ITEM"), eq(itemId), any());
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void deleteSalesOrder_NotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

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
                    .statusId(borradorStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(false)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(disabledOrder));
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(activoStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // toResponse will query the NEW status (activoStatus)
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Active");
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should transition Draft → Cancelled successfully with stock restoration")
        void updateStatus_DraftToCancelled_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(canceladaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of(testItem))
                    .thenReturn(List.of());
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            // The line's stock is restored through the engine under its own ledger reference.
            verifyReversed(itemId);
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Draft → Completed")
        void updateStatus_DraftToCompleted_ThrowsInvalidTransition() {
            var statusRequest = new UpdateSalesOrderStatusRequest(cerradaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("Invalid status transition")
                    .hasMessageContaining("Draft")
                    .hasMessageContaining("Completed");

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

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(wrongTypeStatus.getId())).thenReturn(Optional.of(wrongTypeStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SALES_ORDER");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void updateStatus_OrderNotFound_ThrowsException() {
            var statusRequest = new UpdateSalesOrderStatusRequest(enviadaStatus.getId());
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");
        }

        @Test
        @DisplayName("should restore stock only for enabled items on cancel, not already-restored soft-deleted items")
        void updateStatus_CancelRestore_EnabledItemsOnly() {
            // Soft-deleted item (qty 3.00) already had its stock restored when it was
            // individually deleted, so it must NOT be restored again on cancel.
            var softDeletedItem = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(false) // soft-deleted
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(canceladaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(canceladaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
            // Only enabled items hold stock — a soft-deleted item was already restored
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of(testItem))
                    .thenReturn(List.of());
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            // Only the enabled line is handed to the engine; the soft-deleted one is never touched.
            verifyReversed(itemId);
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
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            List<SalesOrderItemResponse> result = salesOrderService.getSalesOrderItems(orderId);

            assertThat(result).isNotNull();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(itemId);
            assertThat(result.get(0).quantity()).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(result.get(0).finalPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
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
        @DisplayName("should add item with finalPrice = listPrice - discountApplied")
        void addSalesOrderItem_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.finalPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(result.listPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.discountApplied()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(result.statusName()).isEqualTo("Pending");
            verify(itemRepository).save(any(SalesOrderItem.class));
            // Stock is deducted through the engine for the saved line's quantity and reference.
            verifyDeducted(variantId, "2.00", itemId);
            // Verify totalAmount was recalculated (findById called in loadAndValidateModifiableSO +
            // recalculateTotalAmount)
            verify(salesOrderRepository, times(2)).findById(orderId);
        }

        @Test
        @DisplayName("should recalculate totalAmount after adding item")
        void addSalesOrderItem_RecalculatesTotalAmount() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            // Verify the order was saved with updated total (90.00 = finalPrice of only item)
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when order not in Draft or Active")
        void addSalesOrderItem_NotDraftOrActive_ThrowsException() {
            var cerradaOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(cerradaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cerradaOrder));
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already Completed")
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
                    .statusId(activoStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            // Not first item — item already exists
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.finalPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(result.statusName()).isEqualTo("Pending");
            verify(itemRepository).save(any(SalesOrderItem.class));
            verifyDeducted(variantId, "2.00", itemId);
        }

        @Test
        @DisplayName("should auto-transition Draft → Active when first item is added")
        void addSalesOrderItem_FirstItem_AutoTransitionsToActive() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            var savedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(new BigDecimal("100.00"))
                    .discountApplied(new BigDecimal("10.00"))
                    .finalPrice(new BigDecimal("90.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(savedItem);
            // First item — empty list (first call), then with item (subsequent calls for recalculate and toResponse)
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of())
                    .thenReturn(List.of(savedItem));
            // Auto-transition mocks
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Active"))
                    .thenReturn(Optional.of(activoStatus));
            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(testOrder);
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            SalesOrderItemResponse result = salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            // Verify order was saved with Active status (at least once, also saved during recalculate)
            verify(salesOrderRepository, atLeast(1))
                    .save(argThat(so -> so.getStatusId().equals(activoStatus.getId())));
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            // The gate requires a live definition: the split removed findByIdForUpdate, whose query
            // filtered enabled = true, so a soft-deleted variant must not become sellable again.
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(false);

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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.finalPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            verify(itemRepository).save(any(SalesOrderItem.class));
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should deduct stock when item quantity increases")
        void updateSalesOrderItem_QuantityIncrease_DeductsStock() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // Same variant that grew: only the difference is deducted (5.00 - 2.00 = 3.00).
            verifyDeducted(variantId, "3.00", itemId);
        }

        @Test
        @DisplayName("should restore stock when item quantity decreases")
        void updateSalesOrderItem_QuantityDecrease_RestoresStock() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("1.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // The line is given back in full and re-sold at its new quantity: net 100 + 2 - 1 = 101.
            verifyReversed(itemId);
            verifyDeducted(variantId, "1.00", itemId);
        }

        @Test
        @DisplayName("should not change stock when quantity unchanged")
        void updateSalesOrderItem_SameQuantity_NoStockChange() {
            // testItemRequest has qty 2.00, testItem has qty 2.00 → diff = 0 → no stock change
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            // An unchanged line must not reach the engine at all.
            verifyNoInteractions(inventoryService);
        }

        @Test
        @DisplayName("should restore old variant stock and deduct new variant stock when productVariantId changes")
        void updateSalesOrderItem_VariantChange_RestoresOldAndDeductsNew() {
            var newVariantId = UUID.randomUUID();

            var request = new SalesOrderItemRequest(
                    itemId, newVariantId, new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(newVariantId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            // The old line is given back in full and the new variant is sold for the new quantity.
            verifyReversed(itemId);
            verifyDeducted(newVariantId, "2.00", itemId);
        }

        @Test
        @DisplayName(
                "should restore old variant full quantity and deduct new quantity when both variant and quantity change")
        void updateSalesOrderItem_VariantChange_WithQuantityChange() {
            var newVariantId = UUID.randomUUID();

            var request = new SalesOrderItemRequest(
                    itemId, newVariantId, new BigDecimal("5.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(newVariantId))
                    .thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrderItem(orderId, itemId, request);

            verifyReversed(itemId);
            verifyDeducted(newVariantId, "5.00", itemId);
        }

        @Test
        @DisplayName("should throw InsufficientStockException on quantity increase beyond stock")
        void updateSalesOrderItem_InsufficientStock_Throws409() {
            var request = new SalesOrderItemRequest(
                    itemId, variantId, new BigDecimal("10.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null);

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            doThrow(new InsufficientStockException(variantId, new BigDecimal("8.00"), new BigDecimal("1.00")))
                    .when(inventoryService)
                    .applySaleDeduction(any(), any(), any(), any(), any(), any());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, request))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Insufficient stock for variant");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should throw SalesOrderItemNotFoundException when item not found")
        void updateSalesOrderItem_ItemNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when order not in Draft or Active")
        void updateSalesOrderItem_NotDraftOrActive_ThrowsException() {
            var cerradaOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(cerradaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(cerradaOrder));
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already Completed")
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
                    .statusId(activoStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(productVariantRepository.existsByIdAndEnabledTrue(variantId)).thenReturn(true);
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.finalPrice()).isEqualByComparingTo(new BigDecimal("90.00"));
            verify(itemRepository).save(any(SalesOrderItem.class));
            verify(salesOrderRepository).save(any(SalesOrder.class));
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(canceladoItemStatus.getId())
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(cancelledLine));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is Cancelled")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(inventoryService);
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(false)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(deletedLine));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderItemNotModifiableException.class)
                    .hasMessageContaining("is deleted")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
            verifyNoInteractions(inventoryService);
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrderItem
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrderItem")
    class DeleteSalesOrderItemTests {

        @Test
        @DisplayName("should soft-delete item with stock restoration and recalculate total")
        void deleteSalesOrderItem_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            verify(salesOrderRepository).save(any(SalesOrder.class));
            // The line is restored through the engine under its own ledger reference.
            verifyReversed(itemId);
        }

        @Test
        @DisplayName("should throw SalesOrderItemNotFoundException when item not found")
        void deleteSalesOrderItem_ItemNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.deleteSalesOrderItem(orderId, itemId))
                    .isInstanceOf(SalesOrderItemNotFoundException.class)
                    .hasMessageContaining("Sales order item not found with id");
        }

        @Test
        @DisplayName("should soft-delete item on Active order with stock restoration")
        void deleteSalesOrderItem_ActiveOrder_Success() {
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(activoStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(activeOrder));
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            verify(salesOrderRepository).save(any(SalesOrder.class));
            verifyReversed(itemId);
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
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));
            when(statusRepository.findById(agregadoItemStatus.getId())).thenReturn(Optional.of(agregadoItemStatus));

            var updatedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(testItem.getQuantity())
                    .listPrice(testItem.getListPrice())
                    .discountApplied(testItem.getDiscountApplied())
                    .finalPrice(testItem.getFinalPrice())
                    .statusId(agregadoItemStatus.getId())
                    .enabled(true)
                    .build();

            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(updatedItem);
            when(statusRepository.findById(agregadoItemStatus.getId())).thenReturn(Optional.of(agregadoItemStatus));

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
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));
            when(statusRepository.findById(canceladoItemStatus.getId())).thenReturn(Optional.of(canceladoItemStatus));

            var updatedItem = SalesOrderItem.builder()
                    .id(itemId)
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(testItem.getQuantity())
                    .listPrice(testItem.getListPrice())
                    .discountApplied(testItem.getDiscountApplied())
                    .finalPrice(testItem.getFinalPrice())
                    .statusId(canceladoItemStatus.getId())
                    .enabled(true)
                    .build();

            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(updatedItem);
            when(statusRepository.findById(canceladoItemStatus.getId())).thenReturn(Optional.of(canceladoItemStatus));

            SalesOrderItemResponse result =
                    salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelled");
            // W3-D4: cancelling a line restores it exactly as deleting it does, through the engine
            // under the line's own ledger reference. The double-restore guard lives in the engine's
            // uncovered-remainder arithmetic and is asserted end-to-end in SalesOrderIntegrationTest.
            verifyReversed(itemId);
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Pending → Completed")
        void updateItemStatus_PendingToCompleted_ThrowsInvalidTransition() {
            // Must be SALES_ORDER_ITEM type to pass type validation, but "Completed" is not in allowed transitions
            var invalidTransitionStatus = Status.builder()
                    .id(UUID.randomUUID())
                    .statusName("Completed")
                    .statusType(salesOrderItemType)
                    .enabled(true)
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(invalidTransitionStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));
            when(statusRepository.findById(invalidTransitionStatus.getId()))
                    .thenReturn(Optional.of(invalidTransitionStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("Invalid status transition")
                    .hasMessageContaining("Pending")
                    .hasMessageContaining("Completed");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }
    }

    // ─────────────────────────────────────────────
    // chargeSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("chargeSalesOrder")
    class ChargeSalesOrderTests {

        @Test
        @DisplayName("should charge Pending order successfully")
        void chargeSalesOrder_Success() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            // Create a Pending order
            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(enviadaStatus.getId())
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
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
                    .statusId(cerradaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .paymentMethodId(paymentMethodId)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));
            when(paymentMethodRepository.existsById(paymentMethodId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Completed"))
                    .thenReturn(Optional.of(cerradaStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added"))
                    .thenReturn(Optional.of(agregadoItemStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            when(statusRepository.findAllById(any())).thenReturn(List.of(pendienteItemStatus));
            // toResponse mocks
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            // Verify header saved with Completed status + payment method
            verify(salesOrderRepository)
                    .save(argThat(so -> so.getStatusId().equals(cerradaStatus.getId())
                            && paymentMethodId.equals(so.getPaymentMethodId())));
            // Verify both items saved in a single batch with Added status
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems)
                    .hasSize(2)
                    .allMatch(i -> agregadoItemStatus.getId().equals(i.getStatusId()));
        }

        @Test
        @DisplayName("should throw InvalidSalesOrderChargeException when order not in Pending or Active")
        void chargeSalesOrder_NotPendingOrActive_ThrowsException() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            // testOrder is Draft by default — Draft is neither Pending nor Active
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            assertThatThrownBy(() -> salesOrderService.chargeSalesOrder(orderId, request))
                    .isInstanceOf(InvalidSalesOrderChargeException.class)
                    .hasMessageContaining(orderId.toString())
                    .hasMessageContaining("Draft");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when payment method does not exist")
        void chargeSalesOrder_InvalidPaymentMethod_ThrowsException() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(enviadaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));
            when(paymentMethodRepository.existsById(paymentMethodId)).thenReturn(false);

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
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            var pendingOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(enviadaStatus.getId())
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
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var itemPending2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var itemCancelled = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(canceladoItemStatus.getId())
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
                    .statusId(cerradaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .paymentMethodId(paymentMethodId)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(pendingOrder))
                    .thenReturn(Optional.of(savedOrder));
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));
            when(paymentMethodRepository.existsById(paymentMethodId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Completed"))
                    .thenReturn(Optional.of(cerradaStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added"))
                    .thenReturn(Optional.of(agregadoItemStatus));
            when(itemRepository.findBySalesOrderId(orderId))
                    .thenReturn(List.of(itemPending1, itemPending2, itemCancelled));
            // Item status lookup: bulk fetch by the set of item statusIds
            when(statusRepository.findAllById(any())).thenReturn(List.of(pendienteItemStatus, canceladoItemStatus));
            // toResponse mocks
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

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
                    .allMatch(i -> agregadoItemStatus.getId().equals(i.getStatusId()))
                    .extracting(SalesOrderItem::getId)
                    .containsExactlyInAnyOrder(itemPending1.getId(), itemPending2.getId());
        }

        @Test
        @DisplayName("should auto-promote Active → Pending before charging")
        void chargeSalesOrder_ActiveOrder_AutoPromotesToPending() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            // Create an Active order
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(activoStatus.getId())
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
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
                    .statusId(cerradaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .paymentMethodId(paymentMethodId)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(activeOrder))
                    .thenReturn(Optional.of(savedOrder));
            // ensureOrderIsPending: lookup Active status
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));
            // ensureOrderIsPending: find Pending status for promotion
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Pending"))
                    .thenReturn(Optional.of(enviadaStatus));
            when(paymentMethodRepository.existsById(paymentMethodId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Completed"))
                    .thenReturn(Optional.of(cerradaStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added"))
                    .thenReturn(Optional.of(agregadoItemStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            when(statusRepository.findAllById(any())).thenReturn(List.of(pendienteItemStatus));
            // toResponse mocks — items have been transitioned to Added
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

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
            assertThat(savedItems)
                    .hasSize(2)
                    .allMatch(i -> agregadoItemStatus.getId().equals(i.getStatusId()));
        }

        @Test
        @DisplayName("should successfully charge an Active order end-to-end")
        void chargeSalesOrder_ActiveOrder_ThenChargesSuccessfully() {
            var paymentMethodId = UUID.randomUUID();
            var request = new ChargeSalesOrderRequest(paymentMethodId);

            // Create an Active order
            var activeOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber("SO-20260610-00001")
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(activoStatus.getId())
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
                    .finalPrice(new BigDecimal("100.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("3.00"))
                    .listPrice(new BigDecimal("50.00"))
                    .discountApplied(BigDecimal.ZERO)
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
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
                    .statusId(cerradaStatus.getId())
                    .totalAmount(new BigDecimal("250.00"))
                    .paymentMethodId(paymentMethodId)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(testOrder.getUpdatedAt())
                    .build();

            when(salesOrderRepository.findById(orderId))
                    .thenReturn(Optional.of(activeOrder))
                    .thenReturn(Optional.of(savedOrder));
            // ensureOrderIsPending: lookup Active status
            when(statusRepository.findById(activoStatus.getId())).thenReturn(Optional.of(activoStatus));
            // ensureOrderIsPending: find Pending status for promotion
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Pending"))
                    .thenReturn(Optional.of(enviadaStatus));
            when(paymentMethodRepository.existsById(paymentMethodId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Completed"))
                    .thenReturn(Optional.of(cerradaStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added"))
                    .thenReturn(Optional.of(agregadoItemStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(item1, item2));
            // Item status lookup: bulk fetch by the set of item statusIds
            when(statusRepository.findAllById(any())).thenReturn(List.of(pendienteItemStatus));
            // toResponse mocks
            // toResponse mocks — use fresh item copies since the originals were mutated in place
            var item1AfterCharge = SalesOrderItem.builder()
                    .id(item1.getId())
                    .salesOrderId(item1.getSalesOrderId())
                    .productVariantId(item1.getProductVariantId())
                    .quantity(item1.getQuantity())
                    .listPrice(item1.getListPrice())
                    .discountApplied(item1.getDiscountApplied())
                    .finalPrice(item1.getFinalPrice())
                    .statusId(agregadoItemStatus.getId())
                    .enabled(true)
                    .build();
            var item2AfterCharge = SalesOrderItem.builder()
                    .id(item2.getId())
                    .salesOrderId(item2.getSalesOrderId())
                    .productVariantId(item2.getProductVariantId())
                    .quantity(item2.getQuantity())
                    .listPrice(item2.getListPrice())
                    .discountApplied(item2.getDiscountApplied())
                    .finalPrice(item2.getFinalPrice())
                    .statusId(agregadoItemStatus.getId())
                    .enabled(true)
                    .build();

            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId))
                    .thenReturn(List.of(item1AfterCharge, item2AfterCharge));
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));
            when(statusRepository.findById(agregadoItemStatus.getId())).thenReturn(Optional.of(agregadoItemStatus));

            SalesOrderResponse result = salesOrderService.chargeSalesOrder(orderId, request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Completed");
            assertThat(result.paymentMethodId()).isEqualTo(paymentMethodId);
            assertThat(result.totalAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
            // Verify header saved with Completed status + payment method
            var saveCaptor = ArgumentCaptor.forClass(SalesOrder.class);
            verify(salesOrderRepository, times(2)).save(saveCaptor.capture());
            var savedValues = saveCaptor.getAllValues();
            assertThat(savedValues.get(1).getStatusId()).isEqualTo(cerradaStatus.getId());
            assertThat(savedValues.get(1).getPaymentMethodId()).isEqualTo(paymentMethodId);
            // Verify both items saved in a single batch with Added status
            var itemsCaptor = ArgumentCaptor.forClass(Iterable.class);
            verify(itemRepository).saveAll(itemsCaptor.capture());
            var savedItems = new ArrayList<SalesOrderItem>();
            itemsCaptor.getValue().forEach(i -> savedItems.add((SalesOrderItem) i));
            assertThat(savedItems)
                    .hasSize(2)
                    .allMatch(i -> agregadoItemStatus.getId().equals(i.getStatusId()));
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
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var existingItem2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid2)
                    .quantity(BigDecimal.ONE)
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();
            var existingItem3 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid3)
                    .quantity(new BigDecimal("5.00"))
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(existingItem3.getId()) && !item.getEnabled()));
            // Only the deleted line reaches the engine; item1 and item2 keep their quantity.
            verifyReversed(existingItem3.getId());
            verify(inventoryService, never()).applySaleDeduction(any(), any(), any(), any(), any(), any());
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
            when(statusRepository.findById(any())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> item.getId().equals(testItem.getId())
                            && item.getQuantity().compareTo(newQty) == 0
                            && item.getListPrice().compareTo(newPrice) == 0
                            && item.getDiscountApplied().compareTo(newDiscount) == 0
                            && item.getEnabled()));
            // Same variant that grew: only the difference 5.00 - 2.00 = 3.00 is deducted.
            verifyDeducted(variantId, "3.00", testItem.getId());
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
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(itemRepository)
                    .save(argThat(item -> savedItemId.equals(item.getId())
                            && item.getSalesOrderId().equals(orderId)
                            && item.getProductVariantId().equals(newVariantId)
                            && item.getQuantity().compareTo(new BigDecimal("3.00")) == 0));
            // The persisted line's generated id is the ledger reference of the deduction.
            verifyDeducted(newVariantId, "3.00", savedItemId);
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

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
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            verify(salesOrderRepository, atLeast(2)).save(any(SalesOrder.class));
            verifyDeducted(variantId, "1.00", savedItemId);
        }
    }

    // ─────────────────────────────────────────────
    // Stock Delta Computation & Restoration
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("Stock delta computation and restoration")
    class StockDeltaAndRestorationTests {

        @Test
        @DisplayName("should deduct stock for new items (create flow via updateSalesOrder)")
        void deltaComputation_NewItemOnly_DeductsStock() {
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
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> {
                var item = inv.<SalesOrderItem>getArgument(0);
                if (item.getId() == null) {
                    item.setId(savedItemId);
                }
                return item;
            });
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // A new line is sold in full, referencing the id the insert generated.
            verifyDeducted(newVariantId, "10.00", savedItemId);
        }

        @Test
        @DisplayName("should restore stock for deleted items only")
        void deltaComputation_DeletedItemOnly_RestoresStock() {
            var vid2 = UUID.randomUUID();

            var existingToDelete = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vid2)
                    .quantity(new BigDecimal("5.00"))
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();

            var existingKept = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(new BigDecimal("2.00"))
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // Only the deleted line is restored; the kept one keeps its quantity.
            verifyReversed(existingToDelete.getId());
            verify(inventoryService, never()).applySaleDeduction(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should deduct additional stock on quantity increase")
        void deltaComputation_QuantityIncrease_DeductsAdditional() {
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
            when(statusRepository.findById(any())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // Same variant that grew: only the difference 7.00 - 2.00 = 5.00 is deducted.
            verifyDeducted(variantId, "5.00", testItem.getId());
        }

        @Test
        @DisplayName("should restore stock on quantity decrease")
        void deltaComputation_QuantityDecrease_RestoresStock() {
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
            when(statusRepository.findById(any())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // The line is given back in full and re-sold at its new quantity: net 100 + 2 - 1 = 101.
            verifyReversed(testItem.getId());
            verifyDeducted(variantId, "1.00", testItem.getId());
        }

        @Test
        @DisplayName("should handle mix of add, modify, and delete in one update")
        void deltaComputation_MixOfAllThree() {
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
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
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
            when(statusRepository.findById(any())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // The modified line deducts its difference, the new line its full quantity and the
            // deleted line is restored — all three through the engine in one update.
            verifyDeducted(variantId, "1.00", testItem.getId());
            verifyDeducted(vidNew, "6.00", savedItemId);
            verifyReversed(existingToDelete.getId());
        }

        @Test
        @DisplayName("should restore old variant and deduct new variant when an item switches variants inline")
        void deltaComputation_VariantChange_RestoresOldAndDeductsNew() {
            var vidOld = UUID.randomUUID();

            var existingItem = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(vidOld)
                    .quantity(new BigDecimal("4.00"))
                    .listPrice(BigDecimal.TEN)
                    .finalPrice(BigDecimal.TEN)
                    .statusId(pendienteItemStatus.getId())
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
            when(statusRepository.findById(any())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.updateSalesOrder(orderId, request);

            // The old line is given back in full and the new variant sold for the new quantity.
            verifyReversed(existingItem.getId());
            verifyDeducted(variantId, "6.00", existingItem.getId());
        }

        @Test
        @DisplayName("should throw InsufficientStockException on update with insufficient stock")
        void deltaComputation_InsufficientStock_Throws409() {
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
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem));
            when(itemRepository.findById(testItem.getId())).thenReturn(Optional.of(testItem));
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new InsufficientStockException(variantId, new BigDecimal("3.00"), new BigDecimal("1.00")))
                    .when(inventoryService)
                    .applySaleDeduction(any(), any(), any(), any(), any(), any());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, request))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("Insufficient stock for variant")
                    .hasMessageContaining(variantId.toString());
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
}
