package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
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
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderStoreReassignmentNotAllowedException;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.exception.ShiftNotOpenException;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.service.StatusValidator;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SalesOrderService {

    private static final Logger logger = LoggerFactory.getLogger(SalesOrderService.class);

    private static final Map<String, Set<String>> SO_TRANSITIONS = Map.ofEntries(
            Map.entry("Draft", Set.of("Active", "Cancelled")),
            Map.entry("Active", Set.of("Pending", "Cancelled")),
            Map.entry("Pending", Set.of("Completed", "Cancelled")),
            Map.entry("Completed", Set.of()),
            Map.entry("Cancelled", Set.of()));

    private static final Map<String, Set<String>> SO_ITEM_TRANSITIONS = Map.ofEntries(
            Map.entry("Pending", Set.of("Added", "Cancelled")),
            Map.entry("Added", Set.of()),
            Map.entry("Cancelled", Set.of()));

    private static final String SHIFT_STATUS_OPEN = "ABIERTO";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final ShiftRepository shiftRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final StatusRepository statusRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CurrentUserContext currentUserContext;

    public SalesOrderService(
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository itemRepository,
            CustomerRepository customerRepository,
            CompanyStoreRepository companyStoreRepository,
            ShiftRepository shiftRepository,
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            StatusRepository statusRepository,
            PaymentMethodRepository paymentMethodRepository,
            CurrentUserContext currentUserContext) {
        this.salesOrderRepository = salesOrderRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.shiftRepository = shiftRepository;
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.statusRepository = statusRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.currentUserContext = currentUserContext;
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
        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));
        return toResponse(so);
    }

    @Transactional
    public SalesOrderResponse createSalesOrder(SalesOrderRequest request) {
        logger.info(
                "Creating sales order: customerId={}, companyStoreId={}",
                request.customerId(),
                request.companyStoreId());

        validateCustomerExists(request.customerId());
        validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftOpen(request.shiftId());
        }

        var status = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Draft")
                .orElseThrow(
                        () -> new StatusNotFoundException("Default status 'Draft' not found for SALES_ORDER type"));

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
            applyStockChanges(request.items(), Map.of(), Set.of(), Map.of(), request.companyStoreId());

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
            saved = salesOrderRepository
                    .findById(orderIdForReload)
                    .orElseThrow(() -> new SalesOrderNotFoundException(orderIdForReload));
        }

        return toResponse(saved);
    }

    @Transactional
    public SalesOrderResponse updateSalesOrder(UUID id, SalesOrderRequest request) {
        logger.info("Updating sales order: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        validateCustomerExists(request.customerId());
        validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftExists(request.shiftId());
        }

        // The order's store owns the stock its items already deducted: stock lives on the
        // (variant, store) row and every restoration path credits so.getCompanyStoreId().
        // Re-attributing an order that still holds items would leave the original store
        // permanently understated and credit stock to a store that never received the goods,
        // so the change is refused before anything is mutated. With no active items nothing
        // is deducted and the reassignment stays allowed.
        if (!request.companyStoreId().equals(so.getCompanyStoreId())
                && !itemRepository.findBySalesOrderIdAndEnabledTrue(id).isEmpty()) {
            throw new SalesOrderStoreReassignmentNotAllowedException(
                    id, so.getCompanyStoreId(), request.companyStoreId());
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
            applyStockChanges(
                    request.items(), oldQuantities, deletedItemIds, itemIdToVariantId, request.companyStoreId());

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
                    var item = itemRepository
                            .findById(reqItem.id())
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
            updated = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));
        }

        return toResponse(updated);
    }

    @Transactional
    public SalesOrderResponse chargeSalesOrder(UUID id, ChargeSalesOrderRequest request) {
        logger.info("Charging sales order: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // Ensure order is Pending (auto-promotes Active → Pending)
        ensureOrderIsPending(so);

        // Validate payment method exists
        if (!paymentMethodRepository.existsById(request.paymentMethodId())) {
            throw new PaymentMethodNotFoundException(request.paymentMethodId());
        }

        // Look up target statuses
        var completedStatus = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Completed")
                .orElseThrow(() -> new StatusNotFoundException("Completed status not found for SALES_ORDER"));

        var addedStatus = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Added")
                .orElseThrow(() -> new StatusNotFoundException("Added status not found for SALES_ORDER_ITEM"));

        // Set payment method on the order
        so.setPaymentMethodId(request.paymentMethodId());

        // Transition header to Completed
        so.setStatusId(completedStatus.getId());
        salesOrderRepository.save(so);

        // Transition non-Cancelled items to Added
        var items = itemRepository.findBySalesOrderId(id);

        var itemStatusIds = items.stream()
                .filter(SalesOrderItem::getEnabled)
                .map(SalesOrderItem::getStatusId)
                .collect(Collectors.toSet());

        var itemStatusesById = statusRepository.findAllById(itemStatusIds).stream()
                .collect(Collectors.toMap(Status::getId, status -> status));

        var itemsToUpdate = new ArrayList<SalesOrderItem>();
        for (var item : items) {
            if (item.getEnabled()) {
                var itemStatus = itemStatusesById.get(item.getStatusId());
                if (itemStatus == null) {
                    throw new StatusNotFoundException(item.getStatusId());
                }
                if (!"Cancelled".equals(itemStatus.getStatusName())) {
                    item.setStatusId(addedStatus.getId());
                    itemsToUpdate.add(item);
                }
            }
        }
        itemRepository.saveAll(itemsToUpdate);

        logger.info("Sales order charged successfully: id={}", id);

        // Reload to include updated items in response
        var updated = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));
        return toResponse(updated);
    }

    /**
     * Ensures the sales order is in Pending status before charging.
     * If the order is Active, it auto-promotes to Pending.
     * If the order is already Pending, this is a no-op.
     * Any other status throws InvalidSalesOrderChargeException.
     */
    private void ensureOrderIsPending(SalesOrder so) {
        var currentStatus = statusRepository
                .findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));
        var name = currentStatus.getStatusName();
        if ("Pending".equals(name)) return;
        if ("Active".equals(name)) {
            var pendingStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException("Status 'Pending' not found for SALES_ORDER type"));
            var pendingStatusId = pendingStatus.getId();
            validateSOTransition(currentStatus, pendingStatus);
            so.setStatusId(pendingStatusId);
            salesOrderRepository.save(so);
            logger.info("Order {} auto-promoted Active→Pending for charge", so.getId());
            return;
        }
        throw new InvalidSalesOrderChargeException(so.getId(), name);
    }

    @Transactional
    public SalesOrderResponse updateSalesOrderStatus(UUID id, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating sales order status: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        var currentStatus = statusRepository
                .findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));

        var newStatus = StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "SALES_ORDER");
        validateSOTransition(currentStatus, newStatus);

        // Restore stock when transitioning to Cancelled. Only enabled items still
        // hold stock: soft-deleted items were already restored when they were deleted,
        // so restoring them again would duplicate stock.
        if ("Cancelled".equals(newStatus.getStatusName())) {
            var allItems = itemRepository.findBySalesOrderIdAndEnabledTrue(id);

            // Group items by variantId and sort to prevent deadlocks
            var itemsByVariant = new HashMap<UUID, List<SalesOrderItem>>();
            for (var item : allItems) {
                itemsByVariant
                        .computeIfAbsent(item.getProductVariantId(), k -> new java.util.ArrayList<>())
                        .add(item);
            }

            var companyStoreId = so.getCompanyStoreId();
            var sortedVariantIds = itemsByVariant.keySet().stream().sorted().toList();
            for (var variantId : sortedVariantIds) {
                var storeStock = lockStoreStock(variantId, companyStoreId);

                var totalQty = itemsByVariant.get(variantId).stream()
                        .map(SalesOrderItem::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                storeStock.setStock(orZero(storeStock.getStock()).add(totalQty));
                productVariantStoreStockRepository.save(storeStock);
            }
        }

        so.setStatusId(newStatus.getId());
        var updated = salesOrderRepository.save(so);

        return toResponse(updated);
    }

    @Transactional
    public void deleteSalesOrder(UUID id) {
        logger.info("Soft-deleting sales order: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        var currentStatus = statusRepository
                .findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));

        // Restore stock ONLY if the order was not already Cancelled: the cancel
        // transition already restored it, and restoring again would duplicate stock.
        // Only enabled items still hold stock (soft-deleted items were restored on delete).
        if (!"Cancelled".equals(currentStatus.getStatusName())) {
            var items = itemRepository.findBySalesOrderIdAndEnabledTrue(id);
            if (!items.isEmpty()) {
                // Group items by variantId and sort to prevent deadlocks
                var itemsByVariant = new HashMap<UUID, List<SalesOrderItem>>();
                for (var item : items) {
                    itemsByVariant
                            .computeIfAbsent(item.getProductVariantId(), k -> new java.util.ArrayList<>())
                            .add(item);
                }

                var companyStoreId = so.getCompanyStoreId();
                var sortedVariantIds = itemsByVariant.keySet().stream().sorted().toList();
                for (var variantId : sortedVariantIds) {
                    var storeStock = lockStoreStock(variantId, companyStoreId);

                    var totalQty = itemsByVariant.get(variantId).stream()
                            .map(SalesOrderItem::getQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    storeStock.setStock(orZero(storeStock.getStock()).add(totalQty));
                    productVariantStoreStockRepository.save(storeStock);
                }
            }
        }

        so.setEnabled(false);
        salesOrderRepository.save(so);

        // Soft-delete all items
        for (var item : itemRepository.findBySalesOrderId(id)) {
            item.setEnabled(false);
            itemRepository.save(item);
        }

        logger.info("Sales order soft-deleted: id={}", id);
    }

    @Transactional
    public SalesOrderResponse enableSalesOrder(UUID id) {
        logger.info("Re-enabling sales order: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        so.setEnabled(true);
        var saved = salesOrderRepository.save(so);
        return toResponse(saved);
    }

    // ─── Item CRUD ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<SalesOrderItemResponse> getSalesOrderItems(UUID salesOrderId) {
        salesOrderRepository.findById(salesOrderId).orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        return itemRepository.findBySalesOrderIdAndEnabledTrue(salesOrderId).stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional
    public SalesOrderItemResponse addSalesOrderItem(UUID salesOrderId, SalesOrderItemRequest request) {
        logger.info("Adding item to sales order: soId={}", salesOrderId);

        var so = loadAndValidateModifiableSO(salesOrderId);
        validateProductVariantExists(request.productVariantId());

        // Check if this will be the first item (triggers Draft → Active transition)
        var existingItems = itemRepository.findBySalesOrderIdAndEnabledTrue(salesOrderId);
        var isFirstItem = existingItems.isEmpty();

        var defaultItemStatus = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                .orElseThrow(() ->
                        new StatusNotFoundException("Default status 'Pending' not found for SALES_ORDER_ITEM type"));

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
        applyStockChanges(List.of(request), Map.of(), Set.of(), Map.of(), so.getCompanyStoreId());

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        // Auto-transition from Draft → Active when the first item is added
        if (isFirstItem) {
            var activeStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER", "Active")
                    .orElseThrow(() -> new StatusNotFoundException("Status 'Active' not found for SALES_ORDER type"));
            var currentStatus = statusRepository
                    .findById(so.getStatusId())
                    .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));
            validateSOTransition(currentStatus, activeStatus);

            so.setStatusId(activeStatus.getId());
            salesOrderRepository.save(so);
            logger.info("Order {} auto-transitioned from Draft to Active", salesOrderId);
        }

        return toItemResponse(saved);
    }

    @Transactional
    public SalesOrderItemResponse updateSalesOrderItem(UUID salesOrderId, UUID itemId, SalesOrderItemRequest request) {
        logger.info("Updating item: soId={}, itemId={}", salesOrderId, itemId);

        var so = loadAndValidateModifiableSO(salesOrderId);

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        validateProductVariantExists(request.productVariantId());
        // Reconcile stock BEFORE saving. On variant change, applyStockChanges restores
        // the old variant's full quantity and deducts the new variant's full quantity;
        // on a simple quantity change the delta is applied to the current variant.
        var variantChanged = !item.getProductVariantId().equals(request.productVariantId());
        var quantityChanged = request.quantity().compareTo(item.getQuantity()) != 0;

        if (variantChanged || quantityChanged) {
            applyStockChanges(
                    List.of(request),
                    Map.of(item.getId(), item.getQuantity()),
                    Set.of(),
                    Map.of(item.getId(), item.getProductVariantId()),
                    so.getCompanyStoreId());
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

        var so = loadAndValidateModifiableSO(salesOrderId);

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        // Restore stock BEFORE soft-deleting the item, on the per-store row of the order's store.
        var storeStock = lockStoreStock(item.getProductVariantId(), so.getCompanyStoreId());
        storeStock.setStock(orZero(storeStock.getStock()).add(item.getQuantity()));
        productVariantStoreStockRepository.save(storeStock);

        item.setEnabled(false);
        itemRepository.save(item);

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        logger.info("Item soft-deleted: id={}", itemId);
    }

    @Transactional
    public SalesOrderItemResponse updateSalesOrderItemStatus(
            UUID salesOrderId, UUID itemId, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating item status: soId={}, itemId={}", salesOrderId, itemId);

        salesOrderRepository.findById(salesOrderId).orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        var currentStatus = statusRepository
                .findById(item.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(item.getStatusId()));

        var newStatus = StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "SALES_ORDER_ITEM");
        validateSOItemTransition(currentStatus, newStatus);

        item.setStatusId(newStatus.getId());
        var updated = itemRepository.save(item);

        return toItemResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private SalesOrder loadAndValidateModifiableSO(UUID id) {
        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        var status = statusRepository
                .findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));

        var name = status.getStatusName();
        if (!"Draft".equals(name) && !"Active".equals(name)) {
            throw new SalesOrderAlreadyFinalizedException(id, name);
        }
        return so;
    }

    private void validateCustomerExists(UUID id) {
        if (!customerRepository.existsById(id)) {
            throw new CustomerNotFoundException(id);
        }
    }

    /**
     * Resolves the store an order belongs to and authorizes the caller for it.
     *
     * <p>The request carries only the store id, so the company &rarr; country &rarr; region &rarr;
     * zone &rarr; store chain the guard needs is derived from the store itself. This mirrors the
     * check every other store-scoped write applies ({@code StoreLocationService},
     * {@code StoreInventorySettingsService}, {@code GoodsReceiptService},
     * {@code ProductVariantService}); {@code lc-admin} is exempt inside
     * {@link CurrentUserContext#verifyCompanyStoreAccess}. Without it any principal holding
     * {@code lc-sales} could create or move an order onto a store of another company, which is the
     * precondition that makes the store-scoped variant search readable.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope (403)
     */
    private void validateCompanyStoreExists(UUID id) {
        var store = companyStoreRepository.findById(id).orElseThrow(() -> new CompanyStoreNotFoundException(id));
        verifyStoreAccess(store);
    }

    /**
     * Authorizes the caller for the store an order references, deriving the full
     * company &rarr; country &rarr; region &rarr; zone &rarr; store chain from the store itself
     * because the request carries only the store id.
     */
    private void verifyStoreAccess(CompanyStore store) {
        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();

        currentUserContext.verifyCompanyStoreAccess(
                country.getCompany().getId(), country.getId(), region.getId(), zone.getId(), store.getId());
    }

    private void validateShiftExists(UUID id) {
        if (!shiftRepository.existsById(id)) {
            throw new ShiftNotFoundException(id);
        }
    }

    private void validateShiftOpen(UUID id) {
        var shift = shiftRepository.findById(id).orElseThrow(() -> new ShiftNotFoundException(id));
        if (!SHIFT_STATUS_OPEN.equals(shift.getStatus())) {
            throw new ShiftNotOpenException(id, shift.getStatus());
        }
    }

    private void validateProductVariantExists(UUID id) {
        // Requires a live definition, not merely a row: the split removed findByIdForUpdate, whose
        // query filtered enabled = true, so a soft-deleted variant must not become sellable again.
        if (!productVariantRepository.existsByIdAndEnabledTrue(id)) {
            throw new ProductVariantNotFoundException(id);
        }
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

        var maxOrder = salesOrderRepository.findTopByOrderNumberStartingWithOrderByOrderNumberDesc(prefix);

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
     * Acquires pessimistic write locks on all affected per-store stock rows, sorted by the
     * store-scoped row identity to prevent deadlocks.
     *
     * <p>The sort key is the pair {@code (companyStoreId, variantId)}, not the bare
     * {@code variantId}: after the variant-identity split the serialization point is the
     * {@code (variant, store)} row, so two orders in <em>different</em> stores that share a variant
     * definition must NOT queue on the same lock, and two orders that touch several rows must take
     * them in the same order. Sorting by the composite identity gives that order; sorting by
     * {@code variantId} alone would let two stores' orders interleave and deadlock.</p>
     *
     * @param newItems            items from the request (with variantId + quantity)
     * @param oldQuantities       map of existing item ID → quantity (empty for create/add)
     * @param deletedItemIds      set of item IDs being deleted (restore full quantity)
     * @param itemIdToVariantId   map of existing item ID → its current variant ID
     *                            (used to restore the old variant on variant change
     *                            and to restore stock for deleted items)
     * @param companyStoreId      the store whose stock rows are moved (the order's store)
     */
    private void applyStockChanges(
            List<SalesOrderItemRequest> newItems,
            Map<UUID, BigDecimal> oldQuantities,
            Set<UUID> deletedItemIds,
            Map<UUID, UUID> itemIdToVariantId,
            UUID companyStoreId) {

        // 1. Collect distinct variant IDs from new items (including the old variant of
        //    items whose variant changes) and from deleted items
        var variantIds = new HashSet<UUID>();
        for (var item : newItems) {
            variantIds.add(item.productVariantId());
            if (item.id() != null) {
                var oldVid = itemIdToVariantId.get(item.id());
                if (oldVid != null && !oldVid.equals(item.productVariantId())) {
                    variantIds.add(oldVid);
                }
            }
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

        // 2. Sort by the composite store-scoped row identity to prevent deadlocks
        var sortedKeys = variantIds.stream()
                .map(vid -> new StoreVariantKey(companyStoreId, vid))
                .sorted()
                .toList();

        // 3. Acquire pessimistic write locks on the per-store rows in sorted order
        var lockedRows = new HashMap<UUID, ProductVariantStoreStock>();
        for (var key : sortedKeys) {
            lockedRows.put(key.variantId(), lockStoreStock(key.variantId(), key.companyStoreId()));
        }

        // 4. Compute net stock delta per variant
        var stockDelta = new HashMap<UUID, BigDecimal>();
        for (var item : newItems) {
            var vid = item.productVariantId();
            var newQty = item.quantity();

            if (item.id() != null) {
                var oldQty = oldQuantities.getOrDefault(item.id(), BigDecimal.ZERO);
                var oldVid = itemIdToVariantId.get(item.id());
                if (oldVid != null && !oldVid.equals(vid)) {
                    // Variant change: restore the old variant's full quantity and
                    // deduct the new variant's full quantity
                    stockDelta.merge(vid, newQty, BigDecimal::add);
                    stockDelta.merge(oldVid, oldQty.negate(), BigDecimal::add);
                } else {
                    // Same variant (or old variant unknown): apply the quantity diff
                    var delta = newQty.subtract(oldQty);
                    stockDelta.merge(vid, delta, BigDecimal::add);
                }
            } else {
                // New item: deduct the full quantity
                stockDelta.merge(vid, newQty, BigDecimal::add);
            }
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

        // 6. Validate and apply against the per-store row of the order's store
        for (var entry : stockDelta.entrySet()) {
            var vid = entry.getKey();
            var delta = entry.getValue();
            var storeStock = lockedRows.get(vid);
            var available = orZero(storeStock.getStock());

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                // Selling requires a live definition. The inline-items paths (createSalesOrder,
                // updateSalesOrder) reach this loop without passing through
                // validateProductVariantExists, so the gate is repeated here. Restorations
                // (delta < 0) stay ungated: reversing a past sale must still work after the
                // variant was discontinued.
                validateProductVariantExists(vid);

                // Deduction needed — validate sufficient stock in THIS store
                if (available.compareTo(delta) < 0) {
                    throw new InsufficientStockException(vid, delta, available);
                }
                storeStock.setStock(available.subtract(delta));
                productVariantStoreStockRepository.save(storeStock);
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                // Restoration (delta is negative)
                storeStock.setStock(available.add(delta.negate()));
                productVariantStoreStockRepository.save(storeStock);
            }
            // delta == 0: no change, skip save
        }
    }

    /**
     * Locks the {@code (variant, store)} stock row, creating it first when it does not exist yet.
     * The conflict-tolerant insert mirrors {@code ProductVariantLocationRepository}'s balance
     * insert: it keeps the unique violation from aborting the transaction, so the pessimistic lock
     * that follows is always held over an existing row.
     */
    private ProductVariantStoreStock lockStoreStock(UUID variantId, UUID companyStoreId) {
        productVariantStoreStockRepository.insertStoreStockIfAbsent(variantId, companyStoreId);
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(variantId, companyStoreId)
                .orElseThrow(() -> new IllegalStateException("Product variant store stock row not found for variant "
                        + variantId + " and store " + companyStoreId + " after it was locked or created"));
    }

    /** NULL stock only means "never set" — the column default is 0. */
    private static BigDecimal orZero(BigDecimal stock) {
        return stock != null ? stock : BigDecimal.ZERO;
    }

    /** Composite identity of a per-store stock row: the lock/sort key of the stock movers. */
    private record StoreVariantKey(UUID companyStoreId, UUID variantId) implements Comparable<StoreVariantKey> {

        @Override
        public int compareTo(StoreVariantKey other) {
            var storeOrder = companyStoreId.compareTo(other.companyStoreId);
            return storeOrder != 0 ? storeOrder : variantId.compareTo(other.variantId);
        }
    }

    // ─── Total Amount Recalculation ────────────────────────────────────

    private void recalculateTotalAmount(UUID salesOrderId) {
        var items = itemRepository.findBySalesOrderIdAndEnabledTrue(salesOrderId);
        var total = items.stream().map(SalesOrderItem::getFinalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

        var so = salesOrderRepository
                .findById(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));
        so.setTotalAmount(total);
        salesOrderRepository.save(so);
    }

    // ─── Response Mappers ───────────────────────────────────────────────

    private SalesOrderResponse toResponse(SalesOrder so) {
        var items = itemRepository.findBySalesOrderIdAndEnabledTrue(so.getId()).stream()
                .map(this::toItemResponse)
                .toList();

        var statusName = statusRepository
                .findById(so.getStatusId())
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
                so.getPaymentMethodId(),
                so.getEnabled(),
                so.getCreatedAt(),
                so.getUpdatedAt(),
                items);
    }

    private SalesOrderItemResponse toItemResponse(SalesOrderItem item) {
        var statusName = statusRepository
                .findById(item.getStatusId())
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
                item.getUpdatedAt());
    }
}
