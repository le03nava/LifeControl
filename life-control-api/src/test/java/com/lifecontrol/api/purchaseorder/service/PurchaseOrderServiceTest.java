package com.lifecontrol.api.purchaseorder.service;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderDetailNotFoundException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderNotFoundException;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderService Tests")
class PurchaseOrderServiceTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderDetailRepository detailRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CompanyStoreRepository companyStoreRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private StatusRepository statusRepository;
    @Mock private StatusTypeRepository statusTypeRepository;

    @InjectMocks
    private PurchaseOrderService service;

    private UUID poId, detailId, supplierId, storeId, pmId, productId, statusId, draftStatusId;
    private UUID companyId, companyCountryId, regionId, zoneId;
    private Supplier supplier;
    private CompanyStore store;
    private Company company;
    private CompanyCountry companyCountry;
    private CompanyRegion companyRegion;
    private CompanyZone companyZone;
    private PaymentMethod paymentMethod;
    private Product product;
    private Status draftStatus, sentStatus, pendingStatus, inProcessStatus;
    private StatusType poStatusType, detailStatusType;
    private PurchaseOrder purchaseOrder;
    private PurchaseOrderDetail detail;

    @BeforeEach
    void setUp() {
        poId = UUID.randomUUID();
        detailId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        pmId = UUID.randomUUID();
        productId = UUID.randomUUID();
        draftStatusId = UUID.randomUUID();
        statusId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();

        supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setSupplierName("Test Supplier");
        supplier.setEnabled(true);

        company = new Company();
        company.setId(companyId);

        companyCountry = new CompanyCountry();
        companyCountry.setId(companyCountryId);
        companyCountry.setCompany(company);

        companyRegion = new CompanyRegion();
        companyRegion.setId(regionId);
        companyRegion.setCompanyCountry(companyCountry);

        companyZone = new CompanyZone();
        companyZone.setId(zoneId);
        companyZone.setCompanyRegion(companyRegion);

        store = new CompanyStore();
        store.setId(storeId);
        store.setStoreName("Test Store");
        store.setEnabled(true);
        store.setCompanyZone(companyZone);

        paymentMethod = new PaymentMethod();
        paymentMethod.setId(pmId);
        paymentMethod.setPaymentMethodName("Transfer");
        paymentMethod.setEnabled(true);

        product = new Product();
        product.setId(productId);
        product.setName("Test Product");
        product.setEnabled(true);

        poStatusType = new StatusType();
        poStatusType.setId(UUID.randomUUID());
        poStatusType.setStatusTypeName("PURCHASE_ORDER");

        detailStatusType = new StatusType();
        detailStatusType.setId(UUID.randomUUID());
        detailStatusType.setStatusTypeName("PURCHASE_ORDER_DETAIL");

        draftStatus = new Status();
        draftStatus.setId(draftStatusId);
        draftStatus.setStatusName("Draft");
        draftStatus.setStatusType(poStatusType);

        sentStatus = new Status();
        sentStatus.setId(statusId);
        sentStatus.setStatusName("Sent");
        sentStatus.setStatusType(poStatusType);

        pendingStatus = new Status();
        pendingStatus.setId(UUID.randomUUID());
        pendingStatus.setStatusName("Pending");
        pendingStatus.setStatusType(detailStatusType);

        inProcessStatus = new Status();
        inProcessStatus.setId(UUID.randomUUID());
        inProcessStatus.setStatusName("In Process");
        inProcessStatus.setStatusType(detailStatusType);

        purchaseOrder = PurchaseOrder.builder()
                .id(poId)
                .orderNumber("PO-20260603-00001")
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(draftStatus)
                .comments("Test PO")
                .enabled(true)
                .build();
        purchaseOrder.setDetails(new ArrayList<>());

        detail = PurchaseOrderDetail.builder()
                .id(detailId)
                .purchaseOrder(purchaseOrder)
                .product(product)
                .quantity(5)
                .unitPrice(new BigDecimal("100.00"))
                .total(new BigDecimal("500.00"))
                .receivedQuantity(0)
                .status(pendingStatus)
                .enabled(true)
                .build();
    }

    // ─── getAllPurchaseOrders ────────────────────────────────────────────

    @Nested
    @DisplayName("getAllPurchaseOrders")
    class GetAllPurchaseOrdersTests {

        @Test
        @DisplayName("should return paginated results")
        void returnsPaginatedResults() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            when(purchaseOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(page);

            var result = service.getAllPurchaseOrders(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().orderNumber()).isEqualTo("PO-20260603-00001");
            assertThat(result.getContent().getFirst().supplierName()).isEqualTo("Test Supplier");
        }

        @Test
        @DisplayName("should search by supplier or store name")
        void searchesBySupplierOrStoreName() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            when(purchaseOrderRepository.findBySearchTerm("Test", pageable)).thenReturn(page);

            var result = service.getAllPurchaseOrders(pageable, "Test");

            assertThat(result.getContent()).hasSize(1);
            verify(purchaseOrderRepository).findBySearchTerm("Test", pageable);
        }
    }

    // ─── getPurchaseOrderById ────────────────────────────────────────────

    @Nested
    @DisplayName("getPurchaseOrderById")
    class GetPurchaseOrderByIdTests {

        @Test
        @DisplayName("should return PO with details")
        void returnsPOWithDetails() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));

            var result = service.getPurchaseOrderById(poId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(poId);
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            assertThat(result.companyStoreName()).isEqualTo("Test Store");
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.regionId()).isEqualTo(regionId);
            assertThat(result.zoneId()).isEqualTo(zoneId);
        }

        @Test
        @DisplayName("should throw PurchaseOrderNotFoundException when not found")
        void throwsWhenNotFound() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPurchaseOrderById(poId))
                    .isInstanceOf(PurchaseOrderNotFoundException.class)
                    .hasMessageContaining("Orden de compra no encontrada");
        }
    }

    // ─── createPurchaseOrder ─────────────────────────────────────────────

    @Nested
    @DisplayName("createPurchaseOrder")
    class CreatePurchaseOrderTests {

        @Test
        @DisplayName("should create PO with Draft status and auto-generated order_number")
        void createsPOWithDraftAndOrderNumber() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, "Comments", List.of()
            );

            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.of(draftStatus));
            when(purchaseOrderRepository.findTopByOrderNumberStartingWithOrderByOrderNumberDesc(anyString()))
                    .thenReturn(Optional.empty());
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });

            var result = service.createPurchaseOrder(request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Draft");
            assertThat(result.orderNumber()).startsWith("PO-");
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            assertThat(result.companyStoreName()).isEqualTo("Test Store");
            assertThat(result.paymentMethodName()).isEqualTo("Transfer");
        }

        @Test
        @DisplayName("should compute detail totals automatically")
        void computesDetailTotals() {
            var detailReq = new PurchaseOrderDetailRequest(productId, 3, new BigDecimal("150.00"), "Detail comments", pendingStatus.getId());
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq)
            );

            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.of(draftStatus));
            when(statusRepository.findById(pendingStatus.getId())).thenReturn(Optional.of(pendingStatus));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(purchaseOrderRepository.findTopByOrderNumberStartingWithOrderByOrderNumberDesc(anyString()))
                    .thenReturn(Optional.empty());

            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var po = captor.getValue();
                po.setId(poId);
                return po;
            });

            var result = service.createPurchaseOrder(request);

            var savedPO = captor.getValue();
            assertThat(savedPO.getDetails()).hasSize(1);
            assertThat(savedPO.getDetails().getFirst().getTotal()).isEqualByComparingTo(new BigDecimal("450.00"));
        }

        @Test
        @DisplayName("should throw SupplierNotFoundException when supplier missing")
        void throwsWhenSupplierMissing() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, null, List.of()
            );
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(SupplierNotFoundException.class);
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when store missing")
        void throwsWhenStoreMissing() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, null, List.of()
            );
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when payment method missing")
        void throwsWhenPaymentMethodMissing() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, null, List.of()
            );
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(PaymentMethodNotFoundException.class);
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when status missing")
        void throwsWhenStatusMissing() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, null, List.of()
            );
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(StatusNotFoundException.class);
        }

        @Test
        @DisplayName("should throw when status has wrong type")
        void throwsWhenWrongStatusType() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, pendingStatus.getId(), null, List.of()
            );
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findById(pendingStatus.getId())).thenReturn(Optional.of(pendingStatus));

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PURCHASE_ORDER");
        }
    }

    // ─── updatePurchaseOrder ─────────────────────────────────────────────

    @Nested
    @DisplayName("updatePurchaseOrder")
    class UpdatePurchaseOrderTests {

        @Test
        @DisplayName("should update PO fields")
        void updatesPOFields() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, "Updated comments", List.of()
            );
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.of(draftStatus));
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            var result = service.updatePurchaseOrder(poId, request);

            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            verify(purchaseOrderRepository).save(any(PurchaseOrder.class));
        }

        @Test
        @DisplayName("should throw PurchaseOrderNotFoundException when PO missing")
        void throwsWhenPOMissing() {
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, null, List.of()
            );
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrder(poId, request))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);
        }
    }

    // ─── updatePurchaseOrderStatus ───────────────────────────────────────

    @Nested
    @DisplayName("updatePurchaseOrderStatus")
    class UpdatePurchaseOrderStatusTests {

        @Test
        @DisplayName("should update status on valid transition")
        void validTransitionSucceeds() {
            var request = new UpdatePurchaseOrderStatusRequest(sentStatus.getId());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(statusRepository.findById(sentStatus.getId())).thenReturn(Optional.of(sentStatus));
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            var result = service.updatePurchaseOrderStatus(poId, request);

            assertThat(result.statusName()).isEqualTo("Sent");
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on invalid transition")
        void invalidTransitionThrows() {
            var alreadySent = PurchaseOrder.builder()
                    .id(poId).orderNumber("PO-001")
                    .supplier(supplier).companyStore(store).paymentMethod(paymentMethod)
                    .status(sentStatus).enabled(true).build();

            var request = new UpdatePurchaseOrderStatusRequest(draftStatusId);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(alreadySent));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.of(draftStatus));

            assertThatThrownBy(() -> service.updatePurchaseOrderStatus(poId, request))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("Sent → Draft");
        }

        @Test
        @DisplayName("should throw when status has wrong type")
        void wrongStatusTypeThrows() {
            var request = new UpdatePurchaseOrderStatusRequest(pendingStatus.getId());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(statusRepository.findById(pendingStatus.getId())).thenReturn(Optional.of(pendingStatus));

            assertThatThrownBy(() -> service.updatePurchaseOrderStatus(poId, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ─── deletePurchaseOrder ─────────────────────────────────────────────

    @Nested
    @DisplayName("deletePurchaseOrder")
    class DeletePurchaseOrderTests {

        @Test
        @DisplayName("should soft-delete PO and all details")
        void softDeletesPOAndDetails() {
            purchaseOrder.getDetails().add(detail);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));

            service.deletePurchaseOrder(poId);

            assertThat(purchaseOrder.getEnabled()).isFalse();
            assertThat(detail.getEnabled()).isFalse();
            verify(purchaseOrderRepository).save(purchaseOrder);
        }

        @Test
        @DisplayName("should throw PurchaseOrderNotFoundException when PO missing")
        void throwsWhenPOMissing() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePurchaseOrder(poId))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);
        }
    }

    // ─── getPurchaseOrderDetails ─────────────────────────────────────────

    @Nested
    @DisplayName("getPurchaseOrderDetails")
    class GetPurchaseOrderDetailsTests {

        @Test
        @DisplayName("should return list of enabled details")
        void returnsEnabledDetails() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByPurchaseOrderIdAndEnabledTrue(poId)).thenReturn(List.of(detail));

            var result = service.getPurchaseOrderDetails(poId);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().productName()).isEqualTo("Test Product");
        }
    }

    // ─── addPurchaseOrderDetail ──────────────────────────────────────────

    @Nested
    @DisplayName("addPurchaseOrderDetail")
    class AddPurchaseOrderDetailTests {

        @Test
        @DisplayName("should add detail when PO is Draft")
        void addsDetailWhenDraft() {
            var detailReq = new PurchaseOrderDetailRequest(productId, 2, new BigDecimal("50.00"), "Note", pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(statusRepository.findById(pendingStatus.getId())).thenReturn(Optional.of(pendingStatus));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var d = inv.getArgument(0, PurchaseOrderDetail.class);
                d.setId(detailId);
                return d;
            });

            var result = service.addPurchaseOrderDetail(poId, detailReq);

            assertThat(result).isNotNull();
            assertThat(result.total()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.productName()).isEqualTo("Test Product");
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product missing")
        void throwsWhenProductMissing() {
            var detailReq = new PurchaseOrderDetailRequest(productId, 2, new BigDecimal("50.00"), "Note", pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addPurchaseOrderDetail(poId, detailReq))
                    .isInstanceOf(ProductNotFoundException.class);
        }
    }

    // ─── updatePurchaseOrderDetail ───────────────────────────────────────

    @Nested
    @DisplayName("updatePurchaseOrderDetail")
    class UpdatePurchaseOrderDetailTests {

        @Test
        @DisplayName("should update detail fields and recompute total")
        void updatesDetailAndRecomputesTotal() {
            var updatedReq = new PurchaseOrderDetailRequest(productId, 10, new BigDecimal("25.00"), "Updated", pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(statusRepository.findById(pendingStatus.getId())).thenReturn(Optional.of(pendingStatus));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            var result = service.updatePurchaseOrderDetail(poId, detailId, updatedReq);

            assertThat(result.quantity()).isEqualTo(10);
            assertThat(result.total()).isEqualByComparingTo(new BigDecimal("250.00"));
        }

        @Test
        @DisplayName("should throw PurchaseOrderDetailNotFoundException when detail missing")
        void throwsWhenDetailMissing() {
            var updatedReq = new PurchaseOrderDetailRequest(productId, 10, new BigDecimal("25.00"), "Updated", pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, updatedReq))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
        }
    }

    // ─── deletePurchaseOrderDetail ───────────────────────────────────────

    @Nested
    @DisplayName("deletePurchaseOrderDetail")
    class DeletePurchaseOrderDetailTests {

        @Test
        @DisplayName("should soft-delete detail")
        void softDeletesDetail() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));

            service.deletePurchaseOrderDetail(poId, detailId);

            assertThat(detail.getEnabled()).isFalse();
            verify(detailRepository).save(detail);
        }

        @Test
        @DisplayName("should throw PurchaseOrderDetailNotFoundException when detail missing")
        void throwsWhenDetailMissing() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePurchaseOrderDetail(poId, detailId))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
        }
    }

    // ─── updatePurchaseOrderDetailStatus ─────────────────────────────────

    @Nested
    @DisplayName("updatePurchaseOrderDetailStatus")
    class UpdatePurchaseOrderDetailStatusTests {

        @Test
        @DisplayName("should update detail status on valid transition")
        void validTransitionSucceeds() {
            var request = new UpdatePurchaseOrderStatusRequest(inProcessStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(statusRepository.findById(inProcessStatus.getId())).thenReturn(Optional.of(inProcessStatus));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            var result = service.updatePurchaseOrderDetailStatus(poId, detailId, request);

            assertThat(result.statusName()).isEqualTo("In Process");
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on invalid transition")
        void invalidTransitionThrows() {
            detail.setStatus(inProcessStatus);
            var backToPending = new Status();
            backToPending.setId(UUID.randomUUID());
            backToPending.setStatusName("Pending");
            backToPending.setStatusType(detailStatusType);

            var request = new UpdatePurchaseOrderStatusRequest(backToPending.getId());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(statusRepository.findById(backToPending.getId())).thenReturn(Optional.of(backToPending));

            assertThatThrownBy(() -> service.updatePurchaseOrderDetailStatus(poId, detailId, request))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        @DisplayName("should throw when status has wrong type")
        void wrongStatusTypeThrows() {
            var request = new UpdatePurchaseOrderStatusRequest(draftStatusId);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(statusRepository.findById(draftStatusId)).thenReturn(Optional.of(draftStatus));

            assertThatThrownBy(() -> service.updatePurchaseOrderDetailStatus(poId, detailId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PURCHASE_ORDER_DETAIL");
        }
    }
}
