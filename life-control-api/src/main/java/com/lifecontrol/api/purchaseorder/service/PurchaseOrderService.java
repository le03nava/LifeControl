package com.lifecontrol.api.purchaseorder.service;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailResponse;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderResponse;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderCreatedEvent;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderDetailStatusChangedEvent;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderStatusChangedEvent;
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
import com.lifecontrol.api.status.service.StatusValidator;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PurchaseOrderService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseOrderService.class);

    private static final Map<String, Set<String>> PO_TRANSITIONS = Map.ofEntries(
            Map.entry("Draft", Set.of("Sent", "Rejected")),
            Map.entry("Sent", Set.of("Accepted", "Rejected")),
            Map.entry("Accepted", Set.of("In Transit", "Rejected")),
            Map.entry("In Transit", Set.of("Received", "Rejected")),
            Map.entry("Received", Set.of("Billed", "Rejected")),
            Map.entry("Billed", Set.of("Closed", "Rejected")),
            Map.entry("Closed", Set.of()),
            Map.entry("Rejected", Set.of()));

    private static final Map<String, Set<String>> DETAIL_TRANSITIONS = Map.ofEntries(
            Map.entry("Pending", Set.of("In Process", "Cancelled")),
            Map.entry("In Process", Set.of("In Transit", "Rejected", "Cancelled")),
            Map.entry("In Transit", Set.of("Partial Received", "Rejected", "Cancelled")),
            Map.entry("Partial Received", Set.of("Received", "Rejected")),
            Map.entry("Received", Set.of()),
            Map.entry("Rejected", Set.of()),
            Map.entry("Cancelled", Set.of()));

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderDetailRepository detailRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final StatusRepository statusRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PurchaseOrderService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderDetailRepository detailRepository,
            SupplierRepository supplierRepository,
            CompanyStoreRepository companyStoreRepository,
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            PaymentMethodRepository paymentMethodRepository,
            StatusRepository statusRepository,
            ApplicationEventPublisher eventPublisher) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.detailRepository = detailRepository;
        this.supplierRepository = supplierRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.statusRepository = statusRepository;
        this.eventPublisher = eventPublisher;
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
        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));
        return toResponse(po);
    }

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) {
        logger.info("Creating purchase order");

        var supplier = validateSupplierExists(request.supplierId());
        var store = validateCompanyStoreExists(request.companyStoreId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        var status = request.statusId() != null
                ? StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "PURCHASE_ORDER")
                : statusRepository
                        .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
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
            var companyStoreId = store.getId();
            for (var detailReq : request.details()) {
                var product = validateProductExists(detailReq.productId());
                var productVariant = resolveProductVariant(detailReq.productVariantId(), product, companyStoreId);
                var detailStatus = StatusValidator.requireStatusOfType(
                        statusRepository, detailReq.statusId(), "PURCHASE_ORDER_DETAIL");
                var total = detailReq.unitPrice().multiply(BigDecimal.valueOf(detailReq.quantity()));

                var detail = PurchaseOrderDetail.builder()
                        .purchaseOrder(po)
                        .product(product)
                        .productVariant(productVariant)
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

        eventPublisher.publishEvent(new PurchaseOrderCreatedEvent(
                this, saved.getId(), saved.getOrderNumber(), supplier.getId(), store.getId()));

        return toResponse(saved);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrder(UUID id, PurchaseOrderRequest request) {
        logger.info("Updating purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        var supplier = validateSupplierExists(request.supplierId());
        var store = validateCompanyStoreExists(request.companyStoreId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        var status = request.statusId() != null
                ? StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "PURCHASE_ORDER")
                : po.getStatus();

        po.setSupplier(supplier);
        po.setCompanyStore(store);
        po.setPaymentMethod(paymentMethod);
        po.setStatus(status);
        po.setComments(request.comments());

        // ─── Process details (full replacement) ─────────────────────
        if (request.details() != null) {
            // Soft-delete existing details
            for (var existingDetail : po.getDetails()) {
                existingDetail.setEnabled(false);
                detailRepository.save(existingDetail);
            }
            po.getDetails().clear();

            if (!request.details().isEmpty()) {
                var companyStoreId = store.getId();
                var defaultDetailStatus = statusRepository
                        .findByTypeNameAndStatusName("PURCHASE_ORDER_DETAIL", "Pending")
                        .orElseThrow(() -> new StatusNotFoundException(
                                "Default status 'Pending' not found for PURCHASE_ORDER_DETAIL type"));

                for (var detailReq : request.details()) {
                    var product = validateProductExists(detailReq.productId());
                    var productVariant = resolveProductVariant(detailReq.productVariantId(), product, companyStoreId);
                    var detailStatus = detailReq.statusId() != null
                            ? StatusValidator.requireStatusOfType(
                                    statusRepository, detailReq.statusId(), "PURCHASE_ORDER_DETAIL")
                            : defaultDetailStatus;
                    var total = detailReq.unitPrice().multiply(BigDecimal.valueOf(detailReq.quantity()));

                    var detail = PurchaseOrderDetail.builder()
                            .purchaseOrder(po)
                            .product(product)
                            .productVariant(productVariant)
                            .quantity(detailReq.quantity())
                            .unitPrice(detailReq.unitPrice())
                            .total(total)
                            .receivedQuantity(0)
                            .comments(detailReq.comments())
                            .status(detailStatus)
                            .enabled(true)
                            .build();
                    detailRepository.save(detail);
                }
            }
        }

        var updated = purchaseOrderRepository.save(po);
        return toResponse(updated);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrderStatus(UUID id, UpdatePurchaseOrderStatusRequest request) {
        logger.info("Updating purchase order status: id={}", id);

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        var newStatus = StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "PURCHASE_ORDER");
        validatePOTransition(po.getStatus(), newStatus);

        var previousStatus = po.getStatus().getStatusName();
        po.setStatus(newStatus);
        var updated = purchaseOrderRepository.save(po);

        eventPublisher.publishEvent(new PurchaseOrderStatusChangedEvent(
                this, updated.getId(), updated.getOrderNumber(), previousStatus, newStatus.getStatusName()));

        return toResponse(updated);
    }

    @Transactional
    public void deletePurchaseOrder(UUID id) {
        logger.info("Soft-deleting purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

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

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

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
        purchaseOrderRepository
                .findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        return detailRepository.findByPurchaseOrderIdAndEnabledTrue(purchaseOrderId).stream()
                .map(this::toDetailResponse)
                .toList();
    }

    @Transactional
    public PurchaseOrderDetailResponse addPurchaseOrderDetail(
            UUID purchaseOrderId, PurchaseOrderDetailRequest request) {
        logger.info("Adding detail to purchase order: poId={}", purchaseOrderId);

        var po = loadAndValidateDraftPO(purchaseOrderId);
        var product = validateProductExists(request.productId());
        var productVariant = resolveProductVariant(
                request.productVariantId(), product, po.getCompanyStore().getId());
        var detailStatus =
                StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "PURCHASE_ORDER_DETAIL");
        var total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        var detail = PurchaseOrderDetail.builder()
                .purchaseOrder(po)
                .product(product)
                .productVariant(productVariant)
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
    public PurchaseOrderDetailResponse updatePurchaseOrderDetail(
            UUID purchaseOrderId, UUID detailId, PurchaseOrderDetailRequest request) {
        logger.info("Updating detail: poId={}, detailId={}", purchaseOrderId, detailId);

        loadAndValidateDraftPO(purchaseOrderId);

        var detail = detailRepository
                .findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        var product = validateProductExists(request.productId());
        var productVariant = resolveProductVariant(
                request.productVariantId(),
                product,
                detail.getPurchaseOrder().getCompanyStore().getId());
        var detailStatus =
                StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "PURCHASE_ORDER_DETAIL");
        var total = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        detail.setProduct(product);
        detail.setProductVariant(productVariant);
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

        var detail = detailRepository
                .findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        detail.setEnabled(false);
        detailRepository.save(detail);
        logger.info("Detail soft-deleted: id={}", detailId);
    }

    /**
     * Guards the reception entry point: only a purchase order the supplier has
     * accepted (or that is already on its way) can register received quantities.
     * Any other header status is rejected as an invalid transition to "reception".
     *
     * @param purchaseOrder the already-loaded purchase order to check
     * @throws InvalidStatusTransitionException when the header cannot receive
     */
    public void requireReceivable(PurchaseOrder purchaseOrder) {
        var currentStatus = purchaseOrder.getStatus().getStatusName();
        if (!"Accepted".equalsIgnoreCase(currentStatus) && !"In Transit".equalsIgnoreCase(currentStatus)) {
            throw new InvalidStatusTransitionException(currentStatus, "reception");
        }
    }

    /**
     * Purchase-order reception entry point. Called by the goods-receipt use case
     * (workstream W2) to register how much of a line was physically received; this
     * class owns the reception state machine.
     *
     * <p>The derivation of the line status ({@code Partial Received} when the
     * received quantity is below the ordered quantity, {@code Received} when it
     * reaches it) and the {@link #requireReceivable(PurchaseOrder)} guard are
     * required by W2 and are unit-tested here.</p>
     *
     * <p>The derived line status must be reachable in {@code DETAIL_TRANSITIONS}
     * from the line's current status; terminal statuses stay terminal. When every
     * enabled line of an {@code In Transit} order is {@code Received}, the header
     * is promoted to {@code Received} inside the {@code PO_TRANSITIONS} envelope.
     * The existing {@link PurchaseOrderDetailStatusChangedEvent} is published with
     * the real previous/new status and both quantities.</p>
     */
    @Transactional
    public PurchaseOrderDetailResponse registerReceivedQuantity(
            UUID purchaseOrderId, UUID detailId, int receivedQuantity) {
        logger.info(
                "Registering received quantity: poId={}, detailId={}, receivedQuantity={}",
                purchaseOrderId,
                detailId,
                receivedQuantity);

        var po = purchaseOrderRepository
                .findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));
        requireReceivable(po);

        var detail = detailRepository
                .findById(detailId)
                .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));

        // The entry point is given both ids independently, so a mismatched pair would
        // silently mutate another order's line (and then evaluate the header against the
        // wrong order). Treat the detail as not found for this purchase order.
        if (!purchaseOrderId.equals(detail.getPurchaseOrder().getId()) || !Boolean.TRUE.equals(detail.getEnabled())) {
            throw new PurchaseOrderDetailNotFoundException(detailId);
        }

        if (receivedQuantity <= 0) {
            throw new IllegalArgumentException("receivedQuantity must be greater than 0");
        }
        if (receivedQuantity > detail.getQuantity()) {
            throw new IllegalArgumentException("receivedQuantity must not exceed the ordered quantity");
        }

        var targetStatusName = receivedQuantity < detail.getQuantity() ? "Partial Received" : "Received";
        var targetStatus = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER_DETAIL", targetStatusName)
                .orElseThrow(() -> new StatusNotFoundException(
                        "Status '" + targetStatusName + "' not found for PURCHASE_ORDER_DETAIL type"));

        var previousStatus = detail.getStatus().getStatusName();
        if (!isDetailStatusReachable(previousStatus, targetStatus.getStatusName())) {
            throw new InvalidStatusTransitionException(previousStatus, targetStatus.getStatusName());
        }

        detail.setReceivedQuantity(receivedQuantity);
        detail.setStatus(targetStatus);
        var updated = detailRepository.save(detail);

        // Promote the header only once every enabled line is fully received.
        if ("In Transit".equalsIgnoreCase(po.getStatus().getStatusName()) && allEnabledDetailsReceived(po)) {
            var receivedHeaderStatus = statusRepository
                    .findByTypeNameAndStatusName("PURCHASE_ORDER", "Received")
                    .orElseThrow(
                            () -> new StatusNotFoundException("Status 'Received' not found for PURCHASE_ORDER type"));
            validatePOTransition(po.getStatus(), receivedHeaderStatus);
            var previousHeaderStatus = po.getStatus().getStatusName();
            po.setStatus(receivedHeaderStatus);
            purchaseOrderRepository.save(po);
            eventPublisher.publishEvent(new PurchaseOrderStatusChangedEvent(
                    this, po.getId(), po.getOrderNumber(), previousHeaderStatus, receivedHeaderStatus.getStatusName()));
        }

        eventPublisher.publishEvent(new PurchaseOrderDetailStatusChangedEvent(
                this,
                po.getId(),
                po.getOrderNumber(),
                updated.getId(),
                updated.getProduct().getId(),
                previousStatus,
                targetStatus.getStatusName(),
                updated.getReceivedQuantity(),
                updated.getQuantity()));

        return toDetailResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private PurchaseOrder loadAndValidateDraftPO(UUID id) {
        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        if (!"Draft".equals(po.getStatus().getStatusName())) {
            throw new InvalidStatusTransitionException(po.getStatus().getStatusName(), "detail mutation");
        }
        return po;
    }

    private Supplier validateSupplierExists(UUID id) {
        return supplierRepository
                .findById(id)
                .filter(Supplier::getEnabled)
                .orElseThrow(() -> new SupplierNotFoundException(id));
    }

    private CompanyStore validateCompanyStoreExists(UUID id) {
        return companyStoreRepository
                .findById(id)
                .filter(CompanyStore::getEnabled)
                .orElseThrow(() -> new CompanyStoreNotFoundException(id));
    }

    private PaymentMethod validatePaymentMethodExists(UUID id) {
        return paymentMethodRepository
                .findById(id)
                .filter(PaymentMethod::getEnabled)
                .orElseThrow(() -> new PaymentMethodNotFoundException(id));
    }

    private Product validateProductExists(UUID id) {
        return productRepository
                .findById(id)
                .filter(Product::getEnabled)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * Resolves an optional variant reference against the line's product and the
     * purchase order's store. {@code null} leaves the relation unset; a variant
     * that is not reachable through both keys is a 404.
     */
    private ProductVariant resolveProductVariant(UUID productVariantId, Product product, UUID companyStoreId) {
        if (productVariantId == null) {
            return null;
        }
        return productVariantRepository
                .findByIdAndProductIdAndCompanyStoreIdAndEnabledTrue(productVariantId, product.getId(), companyStoreId)
                .orElseThrow(() -> new ProductVariantNotFoundException(productVariantId));
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

    /**
     * Bounded breadth-first walk over {@code DETAIL_TRANSITIONS}. The visited set
     * caps the walk at the size of the table, so a malformed table can never loop
     * forever. Terminal statuses have no outgoing edges, so only themselves are
     * reachable from them (they stay terminal).
     */
    private boolean isDetailStatusReachable(String fromStatus, String targetStatus) {
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        visited.add(fromStatus);
        pending.add(fromStatus);

        while (!pending.isEmpty()) {
            var current = pending.poll();
            if (current.equalsIgnoreCase(targetStatus)) {
                return true;
            }
            for (var next : DETAIL_TRANSITIONS.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    pending.add(next);
                }
            }
        }
        return false;
    }

    private boolean allEnabledDetailsReceived(PurchaseOrder po) {
        var enabledLines = 0;
        for (var line : po.getDetails()) {
            if (!Boolean.TRUE.equals(line.getEnabled())) {
                continue;
            }
            enabledLines++;
            var fullyReceived = "Received".equalsIgnoreCase(line.getStatus().getStatusName())
                    && line.getReceivedQuantity() != null
                    && line.getReceivedQuantity() >= line.getQuantity();
            if (!fullyReceived) {
                return false;
            }
        }
        return enabledLines > 0;
    }

    // ─── Order Number Generation ────────────────────────────────────────

    private String generateOrderNumber() {
        var today = LocalDate.now();
        var dateStr = today.format(DATE_FORMAT);
        var prefix = "PO-" + dateStr + "-";

        var maxOrder = purchaseOrderRepository.findTopByOrderNumberStartingWithOrderByOrderNumberDesc(prefix);

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

        var companyId = Optional.ofNullable(po.getCompanyStore())
                .map(CompanyStore::getCompanyZone)
                .map(CompanyZone::getCompanyRegion)
                .map(CompanyRegion::getCompanyCountry)
                .map(CompanyCountry::getCompany)
                .map(Company::getId)
                .orElse(null);

        var companyCountryId = Optional.ofNullable(po.getCompanyStore())
                .map(CompanyStore::getCompanyZone)
                .map(CompanyZone::getCompanyRegion)
                .map(CompanyRegion::getCompanyCountry)
                .map(CompanyCountry::getId)
                .orElse(null);

        var regionId = Optional.ofNullable(po.getCompanyStore())
                .map(CompanyStore::getCompanyZone)
                .map(CompanyZone::getCompanyRegion)
                .map(CompanyRegion::getId)
                .orElse(null);

        var zoneId = Optional.ofNullable(po.getCompanyStore())
                .map(CompanyStore::getCompanyZone)
                .map(CompanyZone::getId)
                .orElse(null);

        return new PurchaseOrderResponse(
                po.getId(),
                po.getOrderNumber(),
                po.getSupplier().getId(),
                po.getSupplier().getSupplierName(),
                po.getCompanyStore().getId(),
                po.getCompanyStore().getStoreName(),
                companyId,
                companyCountryId,
                regionId,
                zoneId,
                po.getPaymentMethod().getId(),
                po.getPaymentMethod().getPaymentMethodName(),
                po.getStatus().getId(),
                po.getStatus().getStatusName(),
                po.getComments(),
                po.getEnabled(),
                po.getCreatedAt(),
                po.getUpdatedAt(),
                detailResponses);
    }

    private PurchaseOrderDetailResponse toDetailResponse(PurchaseOrderDetail detail) {
        var productVariant = detail.getProductVariant();
        return new PurchaseOrderDetailResponse(
                detail.getId(),
                detail.getPurchaseOrder().getId(),
                detail.getProduct().getId(),
                detail.getProduct().getName(),
                productVariant != null ? productVariant.getId() : null,
                productVariant != null ? productVariant.getVariantName() : null,
                detail.getQuantity(),
                detail.getUnitPrice(),
                detail.getTotal(),
                detail.getReceivedQuantity(),
                detail.getComments(),
                detail.getStatus().getId(),
                detail.getStatus().getStatusName(),
                detail.getCreatedAt(),
                detail.getUpdatedAt());
    }
}
