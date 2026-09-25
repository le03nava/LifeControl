package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
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
import java.util.Comparator;
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

    /**
     * Ledger reference discriminator of a sales-order line: every {@code SALE} and
     * {@code SALE_REVERSAL} movement carries this type and the line id as its reference, so the
     * reversal of one line is exact and independent of the order's other lines (W3-D6).
     */
    private static final String SALE_REFERENCE_TYPE = "SALES_ORDER_ITEM";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final ShiftRepository shiftRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryService inventoryService;
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
            InventoryService inventoryService,
            StatusRepository statusRepository,
            PaymentMethodRepository paymentMethodRepository,
            CurrentUserContext currentUserContext) {
        this.salesOrderRepository = salesOrderRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.shiftRepository = shiftRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryService = inventoryService;
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

        // Persist the lines first so each one has the id the ledger reference needs (W3-D6), then
        // move the stock through the engine. Both happen inside this transaction, so a failed
        // deduction rolls the inserted lines back.
        if (request.items() != null && !request.items().isEmpty()) {
            var defaultItemStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException(
                            "Default status 'Pending' not found for SALES_ORDER_ITEM type"));

            var savedItems = new ArrayList<SalesOrderItem>();
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
                savedItems.add(itemRepository.save(newItem));
            }

            var operations = new ArrayList<StockOperation>();
            for (var savedItem : savedItems) {
                operations.add(deduct(
                        saved.getCompanyStoreId(),
                        savedItem.getProductVariantId(),
                        savedItem.getQuantity(),
                        savedItem.getId()));
            }
            applyStockChanges(operations);

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

        // A terminal order is closed: no transition leads out of it, charge refuses it, and both
        // order cancel and order delete skip it, so any line work here would strand its deduction.
        // Refuse before the header is mutated or the item diff runs.
        requireOrderNotTerminal(so);

        so.setCustomerId(request.customerId());
        so.setCompanyStoreId(request.companyStoreId());
        so.setShiftId(request.shiftId());
        so.setUserId(request.userId());

        var updated = salesOrderRepository.save(so);

        // Item diff: add/update/delete inline items atomically
        if (request.items() != null && !request.items().isEmpty()) {
            var existingItems = itemRepository.findBySalesOrderId(id);

            // Read the pre-mutation state BEFORE any item change: the stock engine needs the old
            // variant and quantity of every line that actually holds stock. The quantity kept on a
            // line is not that state — a soft-deleted line and an individually cancelled line were
            // already given back by the engine — so a line that holds nothing contributes no old
            // state and is sold in full when it comes back enabled (W3-D11). Cancelled is terminal in
            // the item status machine, so re-enabling a cancelled line revives no hold and it
            // contributes no stock work at all.
            var cancelledItemStatusId = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Cancelled")
                    .map(Status::getId)
                    .orElse(null);

            var oldQuantities = new HashMap<UUID, BigDecimal>();
            var itemIdToVariantId = new HashMap<UUID, UUID>();
            var deadItemIds = new HashSet<UUID>();
            for (var existing : existingItems) {
                if (isCancelledLine(existing, cancelledItemStatusId)) {
                    deadItemIds.add(existing.getId());
                } else if (existing.getEnabled()) {
                    oldQuantities.put(existing.getId(), existing.getQuantity());
                    itemIdToVariantId.put(existing.getId(), existing.getProductVariantId());
                }
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

            // DELETE: items in DB but not in request → soft-delete
            for (var existing : existingItems) {
                if (!requestIds.contains(existing.getId())) {
                    existing.setEnabled(false);
                    itemRepository.save(existing);
                }
            }

            // UPDATE (existing) or INSERT (new), collecting the persisted lines so a new line's
            // generated id can be the ledger reference of its deduction (W3-D6).
            var defaultItemStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException(
                            "Default status 'Pending' not found for SALES_ORDER_ITEM type"));

            var savedItems = new ArrayList<SalesOrderItem>();
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
                    savedItems.add(itemRepository.save(item));
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
                    savedItems.add(itemRepository.save(newItem));
                }
            }

            // Apply stock AFTER the lines exist, through the engine. Deleted lines are registered
            // first — a deleted line was deducted under its own ledger reference — then every
            // persisted line contributes its delta. A re-enabled line that held nothing (soft-deleted)
            // is sold in full; a terminal Cancelled line moves nothing.
            var operations = new ArrayList<StockOperation>();
            for (var existing : existingItems) {
                if (deletedItemIds.contains(existing.getId()) && !deadItemIds.contains(existing.getId())) {
                    operations.add(reverse(request.companyStoreId(), existing.getProductVariantId(), existing.getId()));
                }
            }
            for (var savedItem : savedItems) {
                if (deadItemIds.contains(savedItem.getId())) {
                    continue;
                }
                collectLineStockOperations(
                        savedItem,
                        oldQuantities.get(savedItem.getId()),
                        itemIdToVariantId.get(savedItem.getId()),
                        request.companyStoreId(),
                        operations);
            }
            applyStockChanges(operations);

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

        // Restore stock when transitioning to Cancelled. Only enabled items still hold stock:
        // soft-deleted items were already reversed when deleted, and a line cancelled individually
        // was already reversed then, so the ledger makes those reversals a no-op here (W3-D4).
        if ("Cancelled".equals(newStatus.getStatusName())) {
            restoreAllLines(so.getCompanyStoreId(), itemRepository.findBySalesOrderIdAndEnabledTrue(id));
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

        // Restore stock ONLY if the order was not already Cancelled: the cancel transition already
        // restored it, and restoring again would duplicate stock. Only enabled items still hold
        // stock (soft-deleted items were reversed on delete); a line already restored keeps a zero
        // ledger remainder, so the engine's reversal no-ops it and cannot credit it twice.
        if (!"Cancelled".equals(currentStatus.getStatusName())) {
            restoreAllLines(so.getCompanyStoreId(), itemRepository.findBySalesOrderIdAndEnabledTrue(id));
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

        // Deduct stock for the new item, referencing its id so a later reversal is exact (W3-D6).
        applyStockChanges(List.of(
                deduct(so.getCompanyStoreId(), saved.getProductVariantId(), saved.getQuantity(), saved.getId())));

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

        validateItemIsModifiable(item);

        validateProductVariantExists(request.productVariantId());

        var oldVariantId = item.getProductVariantId();
        var oldQuantity = item.getQuantity();
        var variantChanged = !oldVariantId.equals(request.productVariantId());
        var quantityChanged = request.quantity().compareTo(oldQuantity) != 0;

        var discountApplied = request.discountApplied() != null ? request.discountApplied() : BigDecimal.ZERO;
        var finalPrice = request.listPrice().subtract(discountApplied);

        item.setProductVariantId(request.productVariantId());
        item.setQuantity(request.quantity());
        item.setListPrice(request.listPrice());
        item.setDiscountApplied(discountApplied);
        item.setFinalPrice(finalPrice);
        item.setPromotionId(request.promotionId());

        // Reconcile stock through the engine, after the line carries its new values: a variant change
        // gives back the old line in full and sells the new variant, an increase deducts the
        // difference and a decrease gives the line back and re-sells the new quantity. Every
        // movement keeps the line id as its reference, so the store can reverse it exactly (W3-D6).
        if (variantChanged || quantityChanged) {
            var operations = new ArrayList<StockOperation>();
            collectLineStockOperations(item, oldQuantity, oldVariantId, so.getCompanyStoreId(), operations);
            applyStockChanges(operations);
        }

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

        // Restore through the engine BEFORE soft-deleting the item, referencing the line id so the
        // reversal credits exactly the locations the sale took from (W3-D6). A line already
        // reversed — individually cancelled, or the order cancelled — has no uncovered ledger
        // remainder and the engine no-ops it, so it is never credited twice.
        applyStockChanges(List.of(reverse(so.getCompanyStoreId(), item.getProductVariantId(), item.getId())));

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

        var so = salesOrderRepository
                .findById(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        var currentStatus = statusRepository
                .findById(item.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(item.getStatusId()));

        var newStatus = StatusValidator.requireStatusOfType(statusRepository, request.statusId(), "SALES_ORDER_ITEM");
        validateSOItemTransition(currentStatus, newStatus);

        // W3-D4: cancelling a line restores it exactly as deleting it does, writing the reversal
        // that matches its SALE movement. A line cancelled and later deleted must not restore
        // twice: the first reversal leaves a zero uncovered remainder for the line's reference, so
        // the second reversal is a no-op by the engine's remainder arithmetic (W3-D6).
        if ("Cancelled".equals(newStatus.getStatusName())) {
            applyStockChanges(List.of(reverse(so.getCompanyStoreId(), item.getProductVariantId(), item.getId())));
        }

        item.setStatusId(newStatus.getId());
        var updated = itemRepository.save(item);

        return toItemResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private SalesOrder loadAndValidateModifiableSO(UUID id) {
        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        var name = requireOrderNotTerminal(so);

        // The item-level endpoints are stricter than the order-level PUT: they operate only while
        // the order is still being assembled. A Pending order is about to be charged, so it is
        // refused here even though it is not terminal.
        if (!"Draft".equals(name) && !"Active".equals(name)) {
            throw new SalesOrderAlreadyFinalizedException(id, name);
        }
        return so;
    }

    /**
     * Rejects a write against an order that has reached a terminal status, and returns the order's
     * status name so a caller that needs a stricter rule does not read the status twice.
     *
     * <p>A terminal status has no outgoing transition, so the order is closed: letting a write run
     * would move stock that no automatic path can bring back. The concrete case is the order-level
     * item diff on a {@code Cancelled} order: a new line was inserted and its full quantity deducted,
     * while order cancel and order delete both skip a {@code Cancelled} order and charge refuses it,
     * so the deduction was stranded. {@code Completed} is terminal for the same reason and is refused
     * too.</p>
     *
     * @throws SalesOrderAlreadyFinalizedException (409) when the status is terminal
     */
    private String requireOrderNotTerminal(SalesOrder so) {
        var status = statusRepository
                .findById(so.getStatusId())
                .orElseThrow(() -> new StatusNotFoundException(so.getStatusId()));
        var name = status.getStatusName();
        if (isTerminalStatus(name)) {
            throw new SalesOrderAlreadyFinalizedException(so.getId(), name);
        }
        return name;
    }

    /**
     * A status with no outgoing transition is terminal. An unknown status counts as terminal as
     * well: {@link #validateSOTransition} accepts nothing from it either.
     */
    private static boolean isTerminalStatus(String statusName) {
        var allowed = SO_TRANSITIONS.get(statusName);
        return allowed == null || allowed.isEmpty();
    }

    /**
     * Rejects an item-level write against a line that no longer holds stock. A {@code Cancelled}
     * line was reversed in full, so selling it again would leave a {@code SALE} row the rule says no
     * line holds; a soft-deleted line is never re-enabled by the item-level endpoint, so a deduction
     * on it would be stranded. The order-level PUT is the supported route to revive either one,
     * because it re-enables the line before re-selling it (W3-D11).
     *
     * @throws SalesOrderItemNotModifiableException (409) when the line is cancelled or soft-deleted
     */
    private void validateItemIsModifiable(SalesOrderItem item) {
        if (!item.getEnabled()) {
            throw new SalesOrderItemNotModifiableException(item.getId(), "deleted");
        }
        var cancelledItemStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER_ITEM", "Cancelled")
                .map(Status::getId)
                .orElse(null);
        if (isCancelledLine(item, cancelledItemStatusId)) {
            throw new SalesOrderItemNotModifiableException(item.getId(), "Cancelled");
        }
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

    // ─── Stock Movement Through the Inventory Engine ───────────────────

    /**
     * One piece of stock work of a sales operation, keyed by the {@code (store, variant)} row the
     * engine locks first. The key exists only for ordering: the engine derives the locations it
     * moves from the store's settings or from the ledger.
     */
    private record StockOperation(StoreVariantKey key, boolean reversal, Runnable action) {}

    /**
     * Applies a batch of stock work through {@link InventoryService}, the only writer of the
     * per-store aggregate, the per-location balances and the ledger.
     *
     * <p>The batch is applied in a fixed order that satisfies both invariants at once. First every
     * distinct {@code (store, variant)} row is locked in {@link StoreVariantKey} order, so the
     * acquisition order is ascending and deadlock-free even though the actions run in a different
     * order. Then every reversal runs before every deduction. The engine takes the per-store row as
     * its FIRST lock and holds it to the commit, so the documented {@code storeStock -> locationBalance}
     * order is untouched.</p>
     *
     * <p>Reversals must precede deductions because a reversal is reference-scoped: it credits
     * whatever the line's ledger reference still holds, so a deduction of the same reference in the
     * same batch would put a {@code SALE} row in front of it. A variant change registers the
     * reversal of the old variant and the deduction of the new one under one line reference and two
     * different sort keys, so with the old single sorted pass the deduction could win and the
     * reversal would then credit the new variant back, leaving it unsold.</p>
     */
    private void applyStockChanges(List<StockOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }
        var ordered = operations.stream()
                .sorted(Comparator.comparing(StockOperation::key))
                .toList();

        // 1. Lock every distinct row first, in key order. The actions below invert the key order
        //    whenever a reversal sorts after a deduction, so the locks cannot be left to the actions.
        ordered.stream()
                .map(StockOperation::key)
                .distinct()
                .forEach(key -> inventoryService.lockStoreStock(key.variantId(), key.companyStoreId()));

        // 2. Every reversal before every deduction.
        ordered.stream()
                .filter(StockOperation::reversal)
                .forEach(operation -> operation.action().run());
        ordered.stream()
                .filter(operation -> !operation.reversal())
                .forEach(operation -> operation.action().run());
    }

    /**
     * Registers the engine work of one persisted line. A line with no old state deducts its full
     * quantity; a line whose variant changed gives back its old reference and sells the new variant;
     * a line that only grew deducts the difference; a line that shrank gives back the old reference
     * and re-sells its new quantity. Every movement carries the line id as its reference, so a later
     * reversal is exact (W3-D6).
     */
    private void collectLineStockOperations(
            SalesOrderItem line,
            BigDecimal oldQuantity,
            UUID oldVariantId,
            UUID companyStoreId,
            List<StockOperation> operations) {

        var newVariantId = line.getProductVariantId();
        var newQuantity = line.getQuantity();
        var itemId = line.getId();

        if (oldVariantId == null || oldQuantity == null) {
            operations.add(deduct(companyStoreId, newVariantId, newQuantity, itemId));
            return;
        }
        if (!oldVariantId.equals(newVariantId)) {
            operations.add(reverse(companyStoreId, oldVariantId, itemId));
            operations.add(deduct(companyStoreId, newVariantId, newQuantity, itemId));
            return;
        }
        var delta = newQuantity.subtract(oldQuantity);
        if (delta.signum() > 0) {
            operations.add(deduct(companyStoreId, newVariantId, delta, itemId));
        } else if (delta.signum() < 0) {
            operations.add(reverse(companyStoreId, oldVariantId, itemId));
            operations.add(deduct(companyStoreId, newVariantId, newQuantity, itemId));
        }
    }

    /**
     * Reverses every line of the list through the engine. A line already reversed — soft-deleted
     * earlier, cancelled individually, or restored by a previous order cancellation — keeps a zero
     * uncovered ledger remainder, so its reversal is a no-op and the aggregate is never credited
     * twice (W3-D6).
     */
    private void restoreAllLines(UUID companyStoreId, List<SalesOrderItem> items) {
        var operations = new ArrayList<StockOperation>();
        for (var item : items) {
            operations.add(reverse(companyStoreId, item.getProductVariantId(), item.getId()));
        }
        applyStockChanges(operations);
    }

    /** A line whose status is the terminal {@code Cancelled}: it holds no stock and is not revived. */
    private static boolean isCancelledLine(SalesOrderItem line, UUID cancelledItemStatusId) {
        return cancelledItemStatusId != null && cancelledItemStatusId.equals(line.getStatusId());
    }

    private StockOperation deduct(UUID companyStoreId, UUID variantId, BigDecimal quantity, UUID itemId) {
        return new StockOperation(
                new StoreVariantKey(companyStoreId, variantId),
                false,
                () -> inventoryService.applySaleDeduction(
                        variantId,
                        companyStoreId,
                        quantity,
                        SALE_REFERENCE_TYPE,
                        itemId,
                        currentUserContext.getUsername()));
    }

    private StockOperation reverse(UUID companyStoreId, UUID variantId, UUID itemId) {
        return new StockOperation(
                new StoreVariantKey(companyStoreId, variantId),
                true,
                () -> inventoryService.applySaleReversal(
                        SALE_REFERENCE_TYPE, itemId, currentUserContext.getUsername()));
    }

    /** Composite identity of a per-store stock row: the order/sort key of the stock movers. */
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
