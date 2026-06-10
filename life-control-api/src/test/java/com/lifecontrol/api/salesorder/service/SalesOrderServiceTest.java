package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.SalesOrderAlreadyFinalizedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private StatusRepository statusRepository;

    @InjectMocks
    private SalesOrderService salesOrderService;

    private UUID orderId;
    private UUID customerId;
    private UUID companyStoreId;
    private UUID shiftId;
    private UUID variantId;
    private UUID itemId;

    private Status borradorStatus;
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
                .statusName("Borrador")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        enviadaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Enviada")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        canceladaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelada")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        cerradaStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cerrada")
                .statusType(salesOrderType)
                .enabled(true)
                .build();

        pendienteItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Pendiente")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        agregadoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Agregado")
                .statusType(salesOrderItemType)
                .enabled(true)
                .build();

        canceladoItemStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Cancelado")
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

        testOrderRequest = new SalesOrderRequest(
                customerId,
                companyStoreId,
                shiftId,
                "user123"
        );

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
                variantId,
                new BigDecimal("2.00"),
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                null
        );
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

            when(salesOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(expectedPage);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));

            Page<SalesOrderResponse> result = salesOrderService.getAllSalesOrders(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(orderId);
            assertThat(result.getContent().get(0).orderNumber()).isEqualTo("SO-20260610-00001");
            assertThat(result.getContent().get(0).statusName()).isEqualTo("Borrador");
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

            when(salesOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(expectedPage);

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
            assertThat(result.statusName()).isEqualTo("Borrador");
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
        @DisplayName("should create sales order with Borrador status and auto-generated order number")
        void createSalesOrder_Success() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.existsById(companyStoreId)).thenReturn(true);
            when(shiftRepository.existsById(shiftId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Borrador"))
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
            assertThat(result.statusName()).isEqualTo("Borrador");
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
            when(companyStoreRepository.existsById(companyStoreId)).thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessageContaining("Store not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw ShiftNotFoundException when shift FK invalid")
        void createSalesOrder_ShiftNotFound() {
            when(customerRepository.existsById(customerId)).thenReturn(true);
            when(companyStoreRepository.existsById(companyStoreId)).thenReturn(true);
            when(shiftRepository.existsById(shiftId)).thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.createSalesOrder(testOrderRequest))
                    .isInstanceOf(ShiftNotFoundException.class)
                    .hasMessageContaining("Shift not found with id");

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
            when(companyStoreRepository.existsById(companyStoreId)).thenReturn(true);
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
        @DisplayName("should throw SalesOrderNotFoundException when order not found")
        void updateSalesOrder_NotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salesOrderService.updateSalesOrder(orderId, testOrderRequest))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Sales order not found with id");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrder
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrder")
    class DeleteSalesOrderTests {

        @Test
        @DisplayName("should soft-delete sales order and all items")
        void deleteSalesOrder_Success() {
            var item2 = SalesOrderItem.builder()
                    .id(UUID.randomUUID())
                    .salesOrderId(orderId)
                    .productVariantId(variantId)
                    .quantity(BigDecimal.ONE)
                    .listPrice(new BigDecimal("50.00"))
                    .finalPrice(new BigDecimal("50.00"))
                    .statusId(pendienteItemStatus.getId())
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findBySalesOrderId(orderId)).thenReturn(List.of(testItem, item2));

            salesOrderService.deleteSalesOrder(orderId);

            verify(salesOrderRepository).findById(orderId);
            verify(salesOrderRepository).save(any(SalesOrder.class));
            verify(itemRepository).findBySalesOrderId(orderId);
            verify(itemRepository, times(2)).save(any(SalesOrderItem.class));
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
        @DisplayName("should transition Borrador → Enviada successfully")
        void updateStatus_BorradorToEnviada_Success() {
            var statusRequest = new UpdateSalesOrderStatusRequest(enviadaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));

            var updatedOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(enviadaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .createdAt(testOrder.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            // toResponse will query the NEW status (enviadaStatus)
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Enviada");
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should transition Borrador → Cancelada successfully")
        void updateStatus_BorradorToCancelada_Success() {
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
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(statusRepository.findById(canceladaStatus.getId())).thenReturn(Optional.of(canceladaStatus));

            SalesOrderResponse result = salesOrderService.updateSalesOrderStatus(orderId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelada");
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Borrador → Cerrada")
        void updateStatus_BorradorToCerrada_ThrowsInvalidTransition() {
            var statusRequest = new UpdateSalesOrderStatusRequest(cerradaStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(statusRepository.findById(cerradaStatus.getId())).thenReturn(Optional.of(cerradaStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderStatus(orderId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("inválida")
                    .hasMessageContaining("Borrador")
                    .hasMessageContaining("Cerrada");

            verify(salesOrderRepository, never()).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException on wrong status type")
        void updateStatus_WrongType_ThrowsIllegalArgumentException() {
            // status with SALES_ORDER_ITEM type instead of SALES_ORDER
            var wrongTypeStatus = Status.builder()
                    .id(UUID.randomUUID())
                    .statusName("Enviada")
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
            assertThat(result.get(0).statusName()).isEqualTo("Pendiente");
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
            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pendiente"))
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
            assertThat(result.statusName()).isEqualTo("Pendiente");
            verify(itemRepository).save(any(SalesOrderItem.class));
            // Verify totalAmount was recalculated (findById called in loadAndValidateBorradorSO + recalculateTotalAmount)
            verify(salesOrderRepository, times(2)).findById(orderId);
        }

        @Test
        @DisplayName("should recalculate totalAmount after adding item")
        void addSalesOrderItem_RecalculatesTotalAmount() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(productVariantRepository.existsById(variantId)).thenReturn(true);
            when(statusRepository.findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pendiente"))
                    .thenReturn(Optional.of(pendienteItemStatus));
            when(itemRepository.save(any(SalesOrderItem.class))).thenReturn(testItem);
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));

            salesOrderService.addSalesOrderItem(orderId, testItemRequest);

            // Verify the order was saved with updated total (90.00 = finalPrice of only item)
            verify(salesOrderRepository).save(any(SalesOrder.class));
        }

        @Test
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when order not in Borrador")
        void addSalesOrderItem_NotBorrador_ThrowsException() {
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
                    .hasMessageContaining("already Cerrada")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
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
        @DisplayName("should throw ProductVariantNotFoundException when variant not found")
        void addSalesOrderItem_VariantNotFound_ThrowsException() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(productVariantRepository.existsById(variantId)).thenReturn(false);

            assertThatThrownBy(() -> salesOrderService.addSalesOrderItem(orderId, testItemRequest))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found with id");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateSalesOrderItem
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
            when(productVariantRepository.existsById(variantId)).thenReturn(true);
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
        @DisplayName("should throw SalesOrderAlreadyFinalizedException when order not in Borrador")
        void updateSalesOrderItem_NotBorrador_ThrowsException() {
            var enviadaOrder = SalesOrder.builder()
                    .id(orderId)
                    .orderNumber(testOrder.getOrderNumber())
                    .customerId(customerId)
                    .companyStoreId(companyStoreId)
                    .shiftId(shiftId)
                    .userId("user123")
                    .orderDate(testOrder.getOrderDate())
                    .statusId(enviadaStatus.getId())
                    .totalAmount(BigDecimal.ZERO)
                    .enabled(true)
                    .build();

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(enviadaOrder));
            when(statusRepository.findById(enviadaStatus.getId())).thenReturn(Optional.of(enviadaStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItem(orderId, itemId, testItemRequest))
                    .isInstanceOf(SalesOrderAlreadyFinalizedException.class)
                    .hasMessageContaining("already Enviada")
                    .hasMessageContaining("cannot be modified");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }
    }

    // ─────────────────────────────────────────────
    // deleteSalesOrderItem
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteSalesOrderItem")
    class DeleteSalesOrderItemTests {

        @Test
        @DisplayName("should soft-delete item and recalculate total")
        void deleteSalesOrderItem_Success() {
            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(statusRepository.findById(borradorStatus.getId())).thenReturn(Optional.of(borradorStatus));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(itemRepository.findBySalesOrderIdAndEnabledTrue(orderId)).thenReturn(List.of());
            when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

            salesOrderService.deleteSalesOrderItem(orderId, itemId);

            verify(itemRepository).save(any(SalesOrderItem.class));
            verify(salesOrderRepository).save(any(SalesOrder.class));
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
    }

    // ─────────────────────────────────────────────
    // updateSalesOrderItemStatus
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateSalesOrderItemStatus")
    class UpdateSalesOrderItemStatusTests {

        @Test
        @DisplayName("should transition Pendiente → Agregado successfully")
        void updateItemStatus_PendienteToAgregado_Success() {
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

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItemStatus(
                    orderId, itemId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(itemId);
            assertThat(result.statusName()).isEqualTo("Agregado");
            verify(itemRepository).save(any(SalesOrderItem.class));
        }

        @Test
        @DisplayName("should transition Pendiente → Cancelado successfully")
        void updateItemStatus_PendienteToCancelado_Success() {
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

            SalesOrderItemResponse result = salesOrderService.updateSalesOrderItemStatus(
                    orderId, itemId, statusRequest);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Cancelado");
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on Pendiente → Cerrada")
        void updateItemStatus_PendienteToCerrada_ThrowsInvalidTransition() {
            // Must be SALES_ORDER_ITEM type to pass type validation, but "Cerrada" is not in allowed transitions
            var invalidTransitionStatus = Status.builder()
                    .id(UUID.randomUUID())
                    .statusName("Cerrada")
                    .statusType(salesOrderItemType)
                    .enabled(true)
                    .build();

            var statusRequest = new UpdateSalesOrderStatusRequest(invalidTransitionStatus.getId());

            when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
            when(itemRepository.findById(itemId)).thenReturn(Optional.of(testItem));
            when(statusRepository.findById(pendienteItemStatus.getId())).thenReturn(Optional.of(pendienteItemStatus));
            when(statusRepository.findById(invalidTransitionStatus.getId())).thenReturn(Optional.of(invalidTransitionStatus));

            assertThatThrownBy(() -> salesOrderService.updateSalesOrderItemStatus(orderId, itemId, statusRequest))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("inválida")
                    .hasMessageContaining("Pendiente")
                    .hasMessageContaining("Cerrada");

            verify(itemRepository, never()).save(any(SalesOrderItem.class));
        }
    }
}
