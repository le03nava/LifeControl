package com.lifecontrol.api.purchaseorder.service;

import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailResponse;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderResponse;
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
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PurchaseOrderService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseOrderService.class);

    private static final Map<String, Set<String>> PO_TRANSITIONS = Map.ofEntries(
            Map.entry("Draft", Set.of("Sent", "Rechazada")),
            Map.entry("Sent", Set.of("Accepted", "Rechazada")),
            Map.entry("Accepted", Set.of("In Transit", "Rechazada")),
            Map.entry("In Transit", Set.of("Received", "Rechazada")),
            Map.entry("Received", Set.of("Facturada", "Rechazada")),
            Map.entry("Facturada", Set.of("Cerrada", "Rechazada")),
            Map.entry("Cerrada", Set.of()),
            Map.entry("Rechazada", Set.of())
    );

    private static final Map<String, Set<String>> DETAIL_TRANSITIONS = Map.ofEntries(
            Map.entry("Pending", Set.of("In Process", "Cancelada")),
            Map.entry("In Process", Set.of("In Transit", "Rejected", "Cancelada")),
            Map.entry("In Transit", Set.of("Partial Received", "Rejected", "Cancelada")),
            Map.entry("Partial Received", Set.of("Received", "Rejected")),
            Map.entry("Received", Set.of()),
            Map.entry("Rejected", Set.of()),
            Map.entry("Cancelada", Set.of())
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderDetailRepository detailRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final ProductRepository productRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final StatusRepository statusRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                PurchaseOrderDetailRepository detailRepository,
                                SupplierRepository supplierRepository,
                                CompanyStoreRepository companyStoreRepository,
                                ProductRepository productRepository,
                                PaymentMethodRepository paymentMethodRepository,
                                StatusRepository statusRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.detailRepository = detailRepository;
        this.supplierRepository = supplierRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.productRepository = productRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.statusRepository = statusRepository;
    }

    // ─── Purchase Order CRUD ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> getAllPurchaseOrders(Pageable pageable, String search) {
        Page<PurchaseOrder> orders;

        if (StringUtils.hasText(search)) {
            orders = purchaseOrderRepository.findBySearchTerm(search.trim(), pageable);
        } else {
            orders = purchaseOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        return orders.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse getPurchaseOrderById(UUID id) {
        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));
        return toResponse(po);
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) {
        logger.info("Creating purchase order");

        var supplier = validateSupplierExists(request.supplierId());
        var store = validateCompanyStoreExists(request.companyStoreId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        var status = request.statusId() != null
                ? validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER")
                : statusRepository.findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                        .orElseThrow(() -> new StatusNotFoundException(
                                "Default status 'Draft' not found for PURCHASE_ORDER type"));

        var orderNumber = generateOrderNumber();

        var po = PurchaseOrder.builder()
                .orderNumber(orderNumber)
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(status)
                .comments(request.comments())
                .enabled(true)
                .build();

        // Process details
        if (request.details() != null && !request.details().isEmpty()) {
            for (var detailReq : request.details()) {
                var product = validateProductExists(detailReq.productId());
                var detailStatus = validateStatusExistsAndType(detailReq.statusId(), "PURCHASE_ORDER_DETAIL");
                var total = detailReq.unitPrice().multiply(BigDecimal.valueOf(detailReq.quantity()));

                var detail = PurchaseOrderDetail.builder()
                        .purchaseOrder(po)
                        .product(product)
                        .quantity(detailReq.quantity())
                        .unitPrice(detailReq.unitPrice())
                        .total(total)
                        .receivedQuantity(0)
                        .comments(detailReq.comments())
                        .status(detailStatus)
                        .enabled(true)
                        .build();
                po.getDetails().add(detail);
            }
        }

        var saved = purchaseOrderRepository.save(po);
        logger.info("Purchase order created: id={}, orderNumber={}", saved.getId(), saved.getOrderNumber());

        return toResponse(saved);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrder(UUID id, PurchaseOrderRequest request) {
        logger.info("Updating purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        var supplier = validateSupplierExists(request.supplierId());
        var store = validateCompanyStoreExists(request.companyStoreId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        var status = request.statusId() != null
                ? validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER")
                : po.getStatus();

        po.setSupplier(supplier);
        po.setCompanyStore(store);
        po.setPaymentMethod(paymentMethod);
        po.setStatus(status);
        po.setComments(request.comments());

        var updated = purchaseOrderRepository.save(po);
        return toResponse(updated);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrderStatus(UUID id, UpdatePurchaseOrderStatusRequest request) {
        logger.info("Updating purchase order status: id={}", id);

        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        var newStatus = validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER");
        validatePOTransition(po.getStatus(), newStatus);

        po.setStatus(newStatus);
        var updated = purchaseOrderRepository.save(po);

        return toResponse(updated);
    }

    @Transactional
    public void deletePurchaseOrder(UUID id) {
        logger.info("Soft-deleting purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        po.setEnabled(false);
        for (var detail : po.getDetails()) {
            detail.setEnabled(false);
        }

        purchaseOrderRepository.save(po);
        logger.info("Purchase order soft-deleted: id={}", id);
    }

    @Transactional
    public PurchaseOrderResponse enablePurchaseOrder(UUID id) {
        logger.info("Re-enabling purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        po.setEnabled(true);
        for (var detail : po.getDetails()) {
            detail.setEnabled(true);
        }

        var saved = purchaseOrderRepository.save(po);
        return toResponse(saved);
    }

    // ─── Detail CRUD ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PurchaseOrderDetailResponse> getPurchaseOrderDetails(UUID purchaseOrderId) {
        purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        return detailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId)
                .stream()
                .map(this::toDetailResponse)
                .toList();
    }

    @Transactional
    public PurchaseOrderDetailResponse addPurchaseOrderDetail(UUID purchaseOrderId, PurchaseOrderDetailRequest request) {
        logger.info("Adding detail to purchase order: poId={}", purchaseOrderId);

        var po = loadAndValidateDraftPO(purchaseOrderId);
        var product = validateProductExists(request.productId());
        var detailStatus = validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER_DETAIL");
        var total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        var detail = PurchaseOrderDetail.builder()
                .purchaseOrder(po)
                .product(product)
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .total(total)
                .receivedQuantity(0)
                .comments(request.comments())
                .status(detailStatus)
                .enabled(true)
                .build();

        var saved = detailRepository.save(detail);
        logger.info("Detail added: id={}, poId={}", saved.getId(), purchaseOrderId);

        return toDetailResponse(saved);
    }

    @Transactional
    public PurchaseOrderDetailResponse updatePurchaseOrderDetail(UUID purchaseOrderId, UUID detailId,
                                                                  PurchaseOrderDetailRequest request) {
        logger.info("Updating detail: poId={}, detailId={}", purchaseOrderId, detailId);

        loadAndValidateDraftPO(purchaseOrderId);

        var detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        var product = validateProductExists(request.productId());
        var detailStatus = validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER_DETAIL");
        var total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        detail.setProduct(product);
        detail.setQuantity(request.quantity());
        detail.setUnitPrice(request.unitPrice());
        detail.setTotal(total);
        detail.setComments(request.comments());
        detail.setStatus(detailStatus);

        var updated = detailRepository.save(detail);
        return toDetailResponse(updated);
    }

    @Transactional
    public void deletePurchaseOrderDetail(UUID purchaseOrderId, UUID detailId) {
        logger.info("Soft-deleting detail: poId={}, detailId={}", purchaseOrderId, detailId);

        loadAndValidateDraftPO(purchaseOrderId);

        var detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        detail.setEnabled(false);
        detailRepository.save(detail);
        logger.info("Detail soft-deleted: id={}", detailId);
    }

    @Transactional
    public PurchaseOrderDetailResponse updatePurchaseOrderDetailStatus(UUID purchaseOrderId, UUID detailId,
                                                                        UpdatePurchaseOrderStatusRequest request) {
        logger.info("Updating detail status: poId={}, detailId={}", purchaseOrderId, detailId);

        purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        var detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        var newStatus = validateStatusExistsAndType(request.statusId(), "PURCHASE_ORDER_DETAIL");
        validateDetailTransition(detail.getStatus(), newStatus);

        // Update received_quantity for Partial Received or Received transitions
        if ("Partial Received".equals(newStatus.getStatusName()) || "Received".equals(newStatus.getStatusName())) {
            detail.setReceivedQuantity(detail.getQuantity());
        }

        detail.setStatus(newStatus);
        var updated = detailRepository.save(detail);

        return toDetailResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private PurchaseOrder loadAndValidateDraftPO(UUID id) {
        var po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        if (!"Draft".equals(po.getStatus().getStatusName())) {
            throw new InvalidStatusTransitionException(
                    po.getStatus().getStatusName(), "mutación de detalles");
        }
        return po;
    }

    private Supplier validateSupplierExists(UUID id) {
        return supplierRepository.findById(id)
                .filter(Supplier::getEnabled)
                .orElseThrow(() -> new SupplierNotFoundException(id));
    }

    private CompanyStore validateCompanyStoreExists(UUID id) {
        return companyStoreRepository.findById(id)
                .filter(CompanyStore::getEnabled)
                .orElseThrow(() -> new CompanyStoreNotFoundException(id));
    }

    private PaymentMethod validatePaymentMethodExists(UUID id) {
        return paymentMethodRepository.findById(id)
                .filter(PaymentMethod::getEnabled)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));
    }

    private Product validateProductExists(UUID id) {
        return productRepository.findById(id)
                .filter(Product::getEnabled)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Status validateStatusExistsAndType(UUID id, String expectedTypeName) {
        var status = statusRepository.findById(id)
                .orElseThrow(() -> new StatusNotFoundException(id));

        var statusType = status.getStatusType();
        if (!expectedTypeName.equalsIgnoreCase(statusType.getStatusTypeName())) {
            throw new IllegalArgumentException(
                    "El status proporcionado no corresponde al tipo " + expectedTypeName);
        }

        return status;
    }

    // ─── Status Transition Validation ───────────────────────────────────

    private void validatePOTransition(Status current, Status target) {
        var currentName = current.getStatusName();
        var targetName = target.getStatusName();

        var allowed = PO_TRANSITIONS.get(currentName);
        if (allowed == null || !allowed.contains(targetName)) {
            throw new InvalidStatusTransitionException(currentName, targetName);
        }
    }

    private void validateDetailTransition(Status current, Status target) {
        var currentName = current.getStatusName();
        var targetName = target.getStatusName();

        var allowed = DETAIL_TRANSITIONS.get(currentName);
        if (allowed == null || !allowed.contains(targetName)) {
            throw new InvalidStatusTransitionException(currentName, targetName);
        }
    }

    // ─── Order Number Generation ────────────────────────────────────────

    private String generateOrderNumber() {
        var today = LocalDate.now();
        var dateStr = today.format(DATE_FORMAT);
        var prefix = "PO-" + dateStr + "-";

        var maxOrder = purchaseOrderRepository
                .findTopByOrderNumberStartingWithOrderByOrderNumberDesc(prefix);

        var nextSeq = 1;
        if (maxOrder.isPresent()) {
            var lastOrderNumber = maxOrder.get().getOrderNumber();
            var seqPart = lastOrderNumber.substring(prefix.length());
            try {
                nextSeq = Integer.parseInt(seqPart) + 1;
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse sequence from order number: {}", lastOrderNumber);
            }
        }

        return prefix + String.format("%05d", nextSeq);
    }

    // ─── Response Mappers ───────────────────────────────────────────────

    private PurchaseOrderResponse toResponse(PurchaseOrder po) {
        List<PurchaseOrderDetailResponse> detailResponses;
        if (po.getDetails() != null) {
            detailResponses = po.getDetails().stream()
                    .filter(PurchaseOrderDetail::getEnabled)
                    .map(d -> toDetailResponse(d))
                    .toList();
        } else {
            detailResponses = List.of();
        }

        return new PurchaseOrderResponse(
                po.getId(),
                po.getOrderNumber(),
                po.getSupplier().getId(),
                po.getSupplier().getSupplierName(),
                po.getCompanyStore().getId(),
                po.getCompanyStore().getStoreName(),
                po.getPaymentMethod().getId(),
                po.getPaymentMethod().getPaymentMethodName(),
                po.getStatus().getId(),
                po.getStatus().getStatusName(),
                po.getComments(),
                po.getEnabled(),
                po.getCreatedAt(),
                po.getUpdatedAt(),
                detailResponses
        );
    }

    private PurchaseOrderDetailResponse toDetailResponse(PurchaseOrderDetail detail) {
        return new PurchaseOrderDetailResponse(
                detail.getId(),
                detail.getPurchaseOrder().getId(),
                detail.getProduct().getId(),
                detail.getProduct().getName(),
                detail.getQuantity(),
                detail.getUnitPrice(),
                detail.getTotal(),
                detail.getReceivedQuantity(),
                detail.getComments(),
                detail.getStatus().getId(),
                detail.getStatus().getStatusName(),
                detail.getCreatedAt(),
                detail.getUpdatedAt()
        );
    }
}
