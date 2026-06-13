package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
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
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SalesOrderService {

    private static final Logger logger = LoggerFactory.getLogger(SalesOrderService.class);

    private static final Map<String, Set<String>> SO_TRANSITIONS = Map.ofEntries(
            Map.entry("Draft", Set.of("Pending", "Cancelled")),
            Map.entry("Pending", Set.of("Completed", "Cancelled")),
            Map.entry("Completed", Set.of()),
            Map.entry("Cancelled", Set.of())
    );

    private static final Map<String, Set<String>> SO_ITEM_TRANSITIONS = Map.ofEntries(
            Map.entry("Pending", Set.of("Added", "Cancelled")),
            Map.entry("Added", Set.of()),
            Map.entry("Cancelled", Set.of())
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final ShiftRepository shiftRepository;
    private final ProductVariantRepository productVariantRepository;
    private final StatusRepository statusRepository;

    public SalesOrderService(SalesOrderRepository salesOrderRepository,
                              SalesOrderItemRepository itemRepository,
                              CustomerRepository customerRepository,
                              CompanyStoreRepository companyStoreRepository,
                              ShiftRepository shiftRepository,
                              ProductVariantRepository productVariantRepository,
                              StatusRepository statusRepository) {
        this.salesOrderRepository = salesOrderRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.shiftRepository = shiftRepository;
        this.productVariantRepository = productVariantRepository;
        this.statusRepository = statusRepository;
    }

    // ─── Sales Order CRUD ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<SalesOrderResponse> getAllSalesOrders(Pageable pageable, String search) {
        Page<SalesOrder> orders;

        if (StringUtils.hasText(search)) {
            orders = salesOrderRepository.findBySearchTerm(search.trim(), pageable);
        } else {
            orders = salesOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        return orders.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SalesOrderResponse getSalesOrderById(UUID id) {
        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));
        return toResponse(so);
    }

    @Transactional
    public SalesOrderResponse createSalesOrder(SalesOrderRequest request) {
        logger.info("Creating sales order: customerId={}, companyStoreId={}", request.customerId(), request.companyStoreId());

        validateCustomerExists(request.customerId());
        validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftExists(request.shiftId());
        }

        var status = statusRepository.findByTypeNameAndStatusName("SALES_ORDER", "Draft")
                .orElseThrow(() -> new StatusNotFoundException(
                        "Default status 'Draft' not found for SALES_ORDER type"));

        var orderNumber = generateOrderNumber();

        var so = SalesOrder.builder()
                .orderNumber(orderNumber)
                .customerId(request.customerId())
                .companyStoreId(request.companyStoreId())
                .shiftId(request.shiftId())
                .userId(request.userId())
                .orderDate(LocalDateTime.now())
                .statusId(status.getId())
                .totalAmount(BigDecimal.ZERO)
                .enabled(true)
                .build();

        var saved = salesOrderRepository.save(so);
        logger.info("Sales order created: id={}, orderNumber={}", saved.getId(), saved.getOrderNumber());

        // Save items and deduct stock inline
        if (request.items() != null && !request.items().isEmpty()) {
            applyStockChanges(request.items(), Map.of(), Set.of(), Map.of());

            var defaultItemStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException(
                            "Default status 'Pending' not found for SALES_ORDER_ITEM type"));

            for (var reqItem : request.items()) {
                var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                var newItem = SalesOrderItem.builder()
                        .salesOrderId(saved.getId())
                        .productVariantId(reqItem.productVariantId())
                        .quantity(reqItem.quantity())
                        .listPrice(reqItem.listPrice())
                        .discountApplied(discount)
                        .finalPrice(reqItem.listPrice().subtract(discount))
                        .promotionId(reqItem.promotionId())
                        .statusId(defaultItemStatus.getId())
                        .enabled(true)
                        .build();
                itemRepository.save(newItem);
            }

            recalculateTotalAmount(saved.getId());
            var orderIdForReload = saved.getId();
            saved = salesOrderRepository.findById(orderIdForReload)
                    .orElseThrow(() -> new SalesOrderNotFoundException(orderIdForReload));
        }

        return toResponse(saved);
    }

    @Transactional
    public SalesOrderResponse updateSalesOrder(UUID id, SalesOrderRequest request) {
        logger.info("Updating sales order: id={}", id);

        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));

        validateCustomerExists(request.customerId());
        validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftExists(request.shiftId());
        }

        so.setCustomerId(request.customerId());
        so.setCompanyStoreId(request.companyStoreId());
        so.setShiftId(request.shiftId());
        so.setUserId(request.userId());

        var updated = salesOrderRepository.save(so);

        // Item diff: add/update/delete inline items atomically
        if (request.items() != null && !request.items().isEmpty()) {
            var existingItems = itemRepository.findBySalesOrderId(id);

            // Build maps for stock change computation BEFORE any item mutations
            var oldQuantities = new HashMap<UUID, BigDecimal>();
            var itemIdToVariantId = new HashMap<UUID, UUID>();
            for (var existing : existingItems) {
                oldQuantities.put(existing.getId(), existing.getQuantity());
                itemIdToVariantId.put(existing.getId(), existing.getProductVariantId());
            }

            var requestIds = request.items().stream()
                    .map(SalesOrderItemRequest::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // Collect items being deleted (in DB but not in request)
            var deletedItemIds = new HashSet<UUID>();
            for (var existing : existingItems) {
                if (!requestIds.contains(existing.getId())) {
                    deletedItemIds.add(existing.getId());
                }
            }

            // Apply stock changes BEFORE saving any item mutations
            applyStockChanges(request.items(), oldQuantities, deletedItemIds, itemIdToVariantId);

            // DELETE: items in DB but not in request → soft-delete
            for (var existing : existingItems) {
                if (!requestIds.contains(existing.getId())) {
                    existing.setEnabled(false);
                    itemRepository.save(existing);
                }
            }

            // UPDATE (existing) or INSERT (new)
            var defaultItemStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException(
                            "Default status 'Pending' not found for SALES_ORDER_ITEM type"));

            for (var reqItem : request.items()) {
                if (reqItem.id() != null) {
                    // UPDATE: find by id, update fields, re-enable
                    var item = itemRepository.findById(reqItem.id())
                            .orElseThrow(() -> new SalesOrderItemNotFoundException(reqItem.id()));
                    item.setProductVariantId(reqItem.productVariantId());
                    item.setQuantity(reqItem.quantity());
                    item.setListPrice(reqItem.listPrice());
                    var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                    item.setDiscountApplied(discount);
                    item.setFinalPrice(reqItem.listPrice().subtract(discount));
                    item.setPromotionId(reqItem.promotionId());
                    item.setEnabled(true);
                    itemRepository.save(item);
                } else {
                    // INSERT: new item with default "Pending" status
                    var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                    var newItem = SalesOrderItem.builder()
                            .salesOrderId(id)
                            .productVariantId(reqItem.productVariantId())
                            .quantity(reqItem.quantity())
                            .listPrice(reqItem.listPrice())
                            .discountApplied(discount)
                            .finalPrice(reqItem.listPrice().subtract(discount))
                            .promotionId(reqItem.promotionId())
                            .statusId(defaultItemStatus.getId())
                            .enabled(true)
                            .build();
                    itemRepository.save(newItem);
                }
            }

            // Recalculate order total after item mutations
            recalculateTotalAmount(id);

            // Reload to include updated items in response
            updated = salesOrderRepository.findById(id)
                    .orElseThrow(() -> new SalesOrderNotFoundException(id));
        }

        return toResponse(updated);
    }

    @Transactional
    public SalesOrderResponse updateSalesOrderStatus(UUID id, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating sales order status: id={}", id);

        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));

        var currentStatus = statusRepository.findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));

        var newStatus = validateStatusExistsAndType(request.statusId(), "SALES_ORDER");
        validateSOTransition(currentStatus, newStatus);

        // Restore stock when transitioning to Cancelled
        if ("Cancelled".equals(newStatus.getStatusName())) {
            var allItems = itemRepository.findBySalesOrderId(id);

            // Group items by variantId and sort to prevent deadlocks
            var itemsByVariant = new HashMap<UUID, List<SalesOrderItem>>();
            for (var item : allItems) {
                itemsByVariant.computeIfAbsent(item.getProductVariantId(), k -> new java.util.ArrayList<>())
                        .add(item);
            }

            var sortedVariantIds = itemsByVariant.keySet().stream().sorted().toList();
            for (var variantId : sortedVariantIds) {
                var variant = productVariantRepository.findByIdForUpdate(variantId)
                        .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

                var totalQty = itemsByVariant.get(variantId).stream()
                        .map(SalesOrderItem::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                variant.setStock(variant.getStock().add(totalQty));
                productVariantRepository.save(variant);
            }
        }

        so.setStatusId(newStatus.getId());
        var updated = salesOrderRepository.save(so);

        return toResponse(updated);
    }

    @Transactional
    public void deleteSalesOrder(UUID id) {
        logger.info("Soft-deleting sales order: id={}", id);

        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));

        // Restore stock for all items BEFORE soft-deleting
        var items = itemRepository.findBySalesOrderId(id);
        if (!items.isEmpty()) {
            // Group items by variantId and sort to prevent deadlocks
            var itemsByVariant = new HashMap<UUID, List<SalesOrderItem>>();
            for (var item : items) {
                itemsByVariant.computeIfAbsent(item.getProductVariantId(), k -> new java.util.ArrayList<>())
                        .add(item);
            }

            var sortedVariantIds = itemsByVariant.keySet().stream().sorted().toList();
            for (var variantId : sortedVariantIds) {
                var variant = productVariantRepository.findByIdForUpdate(variantId)
                        .orElseThrow(() -> new ProductVariantNotFoundException(variantId));

                var totalQty = itemsByVariant.get(variantId).stream()
                        .map(SalesOrderItem::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                variant.setStock(variant.getStock().add(totalQty));
                productVariantRepository.save(variant);
            }
        }

        so.setEnabled(false);
        salesOrderRepository.save(so);

        // Soft-delete all items
        for (var item : items) {
            item.setEnabled(false);
            itemRepository.save(item);
        }

        logger.info("Sales order soft-deleted: id={}", id);
    }

    @Transactional
    public SalesOrderResponse enableSalesOrder(UUID id) {
        logger.info("Re-enabling sales order: id={}", id);

        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));

        so.setEnabled(true);
        var saved = salesOrderRepository.save(so);
        return toResponse(saved);
    }

    // ─── Item CRUD ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<SalesOrderItemResponse> getSalesOrderItems(UUID salesOrderId) {
        salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        return itemRepository.findBySalesOrderIdAndEnabledTrue(salesOrderId)
                .stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional
    public SalesOrderItemResponse addSalesOrderItem(UUID salesOrderId, SalesOrderItemRequest request) {
        logger.info("Adding item to sales order: soId={}", salesOrderId);

        var so = loadAndValidateDraftSO(salesOrderId);
        validateProductVariantExists(request.productVariantId());

        var defaultItemStatus = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                .orElseThrow(() -> new StatusNotFoundException(
                        "Default status 'Pending' not found for SALES_ORDER_ITEM type"));

        var discountApplied = request.discountApplied() != null ? request.discountApplied() : BigDecimal.ZERO;
        var finalPrice = request.listPrice().subtract(discountApplied);

        var item = SalesOrderItem.builder()
                .salesOrderId(salesOrderId)
                .productVariantId(request.productVariantId())
                .quantity(request.quantity())
                .listPrice(request.listPrice())
                .discountApplied(discountApplied)
                .finalPrice(finalPrice)
                .promotionId(request.promotionId())
                .statusId(defaultItemStatus.getId())
                .enabled(true)
                .build();

        var saved = itemRepository.save(item);
        logger.info("Item added: id={}, soId={}", saved.getId(), salesOrderId);

        // Deduct stock for the new item
        applyStockChanges(List.of(request), Map.of(), Set.of(), Map.of());

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        return toItemResponse(saved);
    }

    @Transactional
    public SalesOrderItemResponse updateSalesOrderItem(UUID salesOrderId, UUID itemId,
                                                        SalesOrderItemRequest request) {
        logger.info("Updating item: soId={}, itemId={}", salesOrderId, itemId);

        loadAndValidateDraftSO(salesOrderId);

        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        validateProductVariantExists(request.productVariantId());

        // Compute quantity diff and adjust stock BEFORE saving
        var oldQty = item.getQuantity();
        var newQty = request.quantity();
        var diff = newQty.subtract(oldQty);

        if (diff.compareTo(BigDecimal.ZERO) != 0) {
            var variant = productVariantRepository.findByIdForUpdate(request.productVariantId())
                    .orElseThrow(() -> new ProductVariantNotFoundException(request.productVariantId()));

            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                // Quantity increase — deduct additional stock
                if (variant.getStock().compareTo(diff) < 0) {
                    throw new InsufficientStockException(variant.getId(), diff, variant.getStock());
                }
                variant.setStock(variant.getStock().subtract(diff));
            } else {
                // Quantity decrease — restore stock
                variant.setStock(variant.getStock().add(diff.negate()));
            }
            productVariantRepository.save(variant);
        }

        var discountApplied = request.discountApplied() != null ? request.discountApplied() : BigDecimal.ZERO;
        var finalPrice = request.listPrice().subtract(discountApplied);

        item.setProductVariantId(request.productVariantId());
        item.setQuantity(request.quantity());
        item.setListPrice(request.listPrice());
        item.setDiscountApplied(discountApplied);
        item.setFinalPrice(finalPrice);
        item.setPromotionId(request.promotionId());

        var updated = itemRepository.save(item);

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        return toItemResponse(updated);
    }

    @Transactional
    public void deleteSalesOrderItem(UUID salesOrderId, UUID itemId) {
        logger.info("Soft-deleting item: soId={}, itemId={}", salesOrderId, itemId);

        loadAndValidateDraftSO(salesOrderId);

        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        // Restore stock BEFORE soft-deleting the item
        var variant = productVariantRepository.findByIdForUpdate(item.getProductVariantId())
                .orElseThrow(() -> new ProductVariantNotFoundException(item.getProductVariantId()));
        variant.setStock(variant.getStock().add(item.getQuantity()));
        productVariantRepository.save(variant);

        item.setEnabled(false);
        itemRepository.save(item);

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        logger.info("Item soft-deleted: id={}", itemId);
    }

    @Transactional
    public SalesOrderItemResponse updateSalesOrderItemStatus(UUID salesOrderId, UUID itemId,
                                                              UpdateSalesOrderStatusRequest request) {
        logger.info("Updating item status: soId={}, itemId={}", salesOrderId, itemId);

        salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        var currentStatus = statusRepository.findById(item.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(item.getStatusId()));

        var newStatus = validateStatusExistsAndType(request.statusId(), "SALES_ORDER_ITEM");
        validateSOItemTransition(currentStatus, newStatus);

        item.setStatusId(newStatus.getId());
        var updated = itemRepository.save(item);

        return toItemResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private SalesOrder loadAndValidateDraftSO(UUID id) {
        var so = salesOrderRepository.findById(id)
                .orElseThrow(() -> new SalesOrderNotFoundException(id));

        var status = statusRepository.findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));

        if (!"Draft".equals(status.getStatusName())) {
            throw new SalesOrderAlreadyFinalizedException(id, status.getStatusName());
        }
        return so;
    }

    private void validateCustomerExists(UUID id) {
        if (!customerRepository.existsById(id)) {
            throw new CustomerNotFoundException(id);
        }
    }

    private void validateCompanyStoreExists(UUID id) {
        if (!companyStoreRepository.existsById(id)) {
            throw new CompanyStoreNotFoundException(id);
        }
    }

    private void validateShiftExists(UUID id) {
        if (!shiftRepository.existsById(id)) {
            throw new ShiftNotFoundException(id);
        }
    }

    private void validateProductVariantExists(UUID id) {
        if (!productVariantRepository.existsById(id)) {
            throw new ProductVariantNotFoundException(id);
        }
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

    private void validateSOTransition(Status current, Status target) {
        var currentName = current.getStatusName();
        var targetName = target.getStatusName();

        var allowed = SO_TRANSITIONS.get(currentName);
        if (allowed == null || !allowed.contains(targetName)) {
            throw new InvalidStatusTransitionException(currentName, targetName);
        }
    }

    private void validateSOItemTransition(Status current, Status target) {
        var currentName = current.getStatusName();
        var targetName = target.getStatusName();

        var allowed = SO_ITEM_TRANSITIONS.get(currentName);
        if (allowed == null || !allowed.contains(targetName)) {
            throw new InvalidStatusTransitionException(currentName, targetName);
        }
    }

    // ─── Order Number Generation ────────────────────────────────────────

    private String generateOrderNumber() {
        var today = LocalDate.now();
        var dateStr = today.format(DATE_FORMAT);
        var prefix = "SO-" + dateStr + "-";

        var maxOrder = salesOrderRepository
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

    // ─── Stock Deduction ───────────────────────────────────────────────

    /**
     * Applies stock mutations for a set of items atomically within the caller's transaction.
     * Acquires pessimistic write locks on all affected variants, sorted by ID to prevent deadlocks.
     *
     * @param newItems            items from the request (with variantId + quantity)
     * @param oldQuantities       map of existing item ID → quantity (empty for create/add)
     * @param deletedItemIds      set of item IDs being deleted (restore full quantity)
     * @param itemIdToVariantId   map of deleted item ID → variant ID (empty for create/add)
     */
    private void applyStockChanges(
            List<SalesOrderItemRequest> newItems,
            Map<UUID, BigDecimal> oldQuantities,
            Set<UUID> deletedItemIds,
            Map<UUID, UUID> itemIdToVariantId) {

        // 1. Collect distinct variant IDs from new items and deleted items
        var variantIds = new HashSet<UUID>();
        for (var item : newItems) {
            variantIds.add(item.productVariantId());
        }
        for (var deletedId : deletedItemIds) {
            var vid = itemIdToVariantId.get(deletedId);
            if (vid != null) {
                variantIds.add(vid);
            }
        }

        if (variantIds.isEmpty()) {
            return;
        }

        // 2. Sort to prevent deadlocks
        var sortedIds = variantIds.stream().sorted().toList();

        // 3. Acquire pessimistic write locks in sorted order
        var lockedVariants = new HashMap<UUID, ProductVariant>();
        for (var vid : sortedIds) {
            var variant = productVariantRepository.findByIdForUpdate(vid)
                    .orElseThrow(() -> new ProductVariantNotFoundException(vid));
            lockedVariants.put(vid, variant);
        }

        // 4. Compute net stock delta per variant
        var stockDelta = new HashMap<UUID, BigDecimal>();
        for (var item : newItems) {
            var vid = item.productVariantId();
            var newQty = item.quantity();
            var oldQty = item.id() != null ? oldQuantities.getOrDefault(item.id(), BigDecimal.ZERO) : BigDecimal.ZERO;
            var delta = newQty.subtract(oldQty); // positive = deduct, negative = restore
            stockDelta.merge(vid, delta, BigDecimal::add);
        }

        // 5. Add restoration for deleted items
        for (var deletedId : deletedItemIds) {
            var vid = itemIdToVariantId.get(deletedId);
            if (vid != null) {
                var deletedQty = oldQuantities.getOrDefault(deletedId, BigDecimal.ZERO);
                // Deletion restores stock → negative delta (restore)
                stockDelta.merge(vid, deletedQty.negate(), BigDecimal::add);
            }
        }

        // 6. Validate and apply
        for (var entry : stockDelta.entrySet()) {
            var vid = entry.getKey();
            var delta = entry.getValue();
            var variant = lockedVariants.get(vid);

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                // Deduction needed — validate sufficient stock
                if (variant.getStock().compareTo(delta) < 0) {
                    throw new InsufficientStockException(vid, delta, variant.getStock());
                }
                variant.setStock(variant.getStock().subtract(delta));
                productVariantRepository.save(variant);
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                // Restoration (delta is negative)
                variant.setStock(variant.getStock().add(delta.negate()));
                productVariantRepository.save(variant);
            }
            // delta == 0: no change, skip save
        }
    }

    // ─── Total Amount Recalculation ────────────────────────────────────

    private void recalculateTotalAmount(UUID salesOrderId) {
        var items = itemRepository.findBySalesOrderIdAndEnabledTrue(salesOrderId);
        var total = items.stream()
                .map(SalesOrderItem::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));
        so.setTotalAmount(total);
        salesOrderRepository.save(so);
    }

    // ─── Response Mappers ───────────────────────────────────────────────

    private SalesOrderResponse toResponse(SalesOrder so) {
        var items = itemRepository.findBySalesOrderIdAndEnabledTrue(so.getId())
                .stream()
                .map(this::toItemResponse)
                .toList();

        var statusName = statusRepository.findById(so.getStatusId())
                .map(Status::getStatusName)
                .orElse(null);

        return new SalesOrderResponse(
                so.getId(),
                so.getOrderNumber(),
                so.getCustomerId(),
                so.getCompanyStoreId(),
                so.getShiftId(),
                so.getUserId(),
                so.getOrderDate(),
                so.getStatusId(),
                statusName,
                so.getTotalAmount(),
                so.getEnabled(),
                so.getCreatedAt(),
                so.getUpdatedAt(),
                items
        );
    }

    private SalesOrderItemResponse toItemResponse(SalesOrderItem item) {
        var statusName = statusRepository.findById(item.getStatusId())
                .map(Status::getStatusName)
                .orElse(null);

        return new SalesOrderItemResponse(
                item.getId(),
                item.getSalesOrderId(),
                item.getProductVariantId(),
                item.getQuantity(),
                item.getListPrice(),
                item.getDiscountApplied(),
                item.getFinalPrice(),
                item.getPromotionId(),
                item.getStatusId(),
                statusName,
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
