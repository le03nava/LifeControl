package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.state.DocumentStates;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.common.state.StatusTransitionRecorder;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.inventory.service.InventoryService.SaleChange;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
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
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.exception.ShiftNotOpenException;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

    /**
     * The state every order is born in (ADR-0004 decision 1). It was the catalog label {@code "Draft"} until
     * this slice; the row is still looked up by name because the catalog is the label of a code, but the
     * state itself is the constant, and {@link com.lifecontrol.api.common.state.DocumentStates} is what
     * decides every move after it.
     */
    private static final SalesOrderState INITIAL_ORDER_STATE = SalesOrderState.DRAFT;

    /** The state every line is born in (ADR-0004 decision 3), same reasoning as {@link #INITIAL_ORDER_STATE}. */
    private static final SalesOrderItemState INITIAL_ITEM_STATE = SalesOrderItemState.PENDING;

    private static final String SHIFT_STATUS_OPEN = "ABIERTO";

    /**
     * Ledger reference discriminator of a sales-order line: every {@code SALE} and
     * {@code SALE_REVERSAL} movement carries this type and the line id as its reference, so the
     * reversal of one line is exact and independent of the order's other lines (W3-D6).
     */
    private static final String SALE_REFERENCE_TYPE = "SALES_ORDER_ITEM";

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final ShiftRepository shiftRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryService inventoryService;
    private final StatusRepository statusRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CurrentUserContext currentUserContext;
    private final DocumentSequenceAllocator documentSequenceAllocator;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final SalesOrderStatusCatalog statusCatalog;

    public SalesOrderService(
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository itemRepository,
            CustomerRepository customerRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            ShiftRepository shiftRepository,
            ProductVariantRepository productVariantRepository,
            InventoryService inventoryService,
            StatusRepository statusRepository,
            PaymentMethodRepository paymentMethodRepository,
            CurrentUserContext currentUserContext,
            DocumentSequenceAllocator documentSequenceAllocator,
            StatusTransitionRecorder statusTransitionRecorder,
            SalesOrderStatusCatalog statusCatalog) {
        this.salesOrderRepository = salesOrderRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.shiftRepository = shiftRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryService = inventoryService;
        this.statusRepository = statusRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.currentUserContext = currentUserContext;
        this.documentSequenceAllocator = documentSequenceAllocator;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.statusCatalog = statusCatalog;
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
        // The store is resolved once and kept: it authorizes the caller AND it is the only thing that
        // answers which tenant owns the number this order needs, so a second lookup could only disagree
        // with the guard that already passed.
        var store = validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftOpen(request.shiftId());
        }

        var status = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Draft")
                .orElseThrow(
                        () -> new StatusNotFoundException("Default status 'Draft' not found for SALES_ORDER type"));

        var companyId = companyStoreOwnerResolver.companyIdOf(store);

        // ADR-0003 decision 4: the document date is read ONCE and the period comes from that value, so a
        // sale created at 23:59 on the last day of a month cannot land in the next period depending on
        // when its transaction reaches the allocator. The same `orderDate` is what the row stores.
        var orderDate = LocalDateTime.now();
        var period = DocumentNumberRenderer.periodOf(orderDate.toLocalDate());

        // The allocation is the last write before the row is created, not the last write before commit:
        // `sales_orders` needs its number at INSERT time, so "allocate as the last write before commit" is
        // unattainable on this path. The sequence row's lock is therefore held while the lines and their
        // stock work run, which is the cost the ADR records rather than one this code can avoid.
        var seqValue = documentSequenceAllocator.allocate(companyId, DocumentType.SALES_ORDER, period);
        var docNumber = DocumentNumberRenderer.renderPeriodNumber(DocumentType.SALES_ORDER, period, seqValue);

        var so = SalesOrder.builder()
                // `V64` dropped the global key on `order_number`: it carries the same string as
                // `doc_number` for one more release, and two tenants may legally print the same number.
                .orderNumber(docNumber)
                .companyId(companyId)
                .docType(DocumentType.SALES_ORDER.code())
                .seqPeriod(period)
                .seqValue(seqValue)
                .docNumber(docNumber)
                .customerId(request.customerId())
                .companyStoreId(store.getId())
                .shiftId(request.shiftId())
                .userId(request.userId())
                .orderDate(orderDate)
                .statusId(status.getId())
                .status(INITIAL_ORDER_STATE)
                .totalAmount(BigDecimal.ZERO)
                .enabled(true)
                .build();

        var saved = salesOrderRepository.save(so);
        // ADR-0004 decision 5 side A: a document born without its creation row cannot commit, so the two
        // writes share this transaction and the deferred trigger judges them together.
        flushDocuments();
        statusTransitionRecorder.recordCreation(
                StatusSubject.SALES_ORDER, companyId, saved.getId(), INITIAL_ORDER_STATE);
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
                        .status(INITIAL_ITEM_STATE)
                        .enabled(true)
                        .build();
                var savedItem = itemRepository.save(newItem);
                recordItemCreated(companyId, savedItem);
                savedItems.add(savedItem);
            }

            var operations = new ArrayList<SaleChange>();
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
            // contributes no stock work at all. The status is resolved fail-closed: guessing here
            // would classify a cancelled line as live and sell the stock it already gave back (F-2).
            var oldQuantities = new HashMap<UUID, BigDecimal>();
            var itemIdToVariantId = new HashMap<UUID, UUID>();
            var deadItemIds = new HashSet<UUID>();
            for (var existing : existingItems) {
                if (isCancelledLine(existing)) {
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
                            .status(INITIAL_ITEM_STATE)
                            .enabled(true)
                            .build();
                    var savedItem = itemRepository.save(newItem);
                    recordItemCreated(so.getCompanyId(), savedItem);
                    savedItems.add(savedItem);
                }
            }

            // Apply stock AFTER the lines exist, through the engine. Deleted lines are registered
            // first — a deleted line was deducted under its own ledger reference — then every
            // persisted line contributes its delta. A re-enabled line that held nothing (soft-deleted)
            // is sold in full; a terminal Cancelled line moves nothing.
            var operations = new ArrayList<SaleChange>();
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
        moveOrder(so, completedStatus.getId(), SalesOrderState.COMPLETED, "charge");

        // Transition the lines that are waiting for it to Added. A line's classification is its typed
        // state and not a catalog lookup: CANCELLED is terminal (its reversal is booked, so there is no
        // edge out of it) and ADDED is already charged, so only PENDING moves — the same rule that used
        // to be the `!"Cancelled".equals(statusName)` test, stated once, next to the enum (ADR-0004
        // decision 3).
        var items = itemRepository.findBySalesOrderId(id);

        var itemsToUpdate = new ArrayList<SalesOrderItem>();
        for (var item : items) {
            if (!Boolean.TRUE.equals(item.getEnabled()) || item.getStatus() != SalesOrderItemState.PENDING) {
                continue;
            }
            moveItem(item, so.getCompanyId(), addedStatus.getId(), SalesOrderItemState.ADDED, "charge");
            itemsToUpdate.add(item);
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
        var currentState = so.getStatus();
        if (currentState == SalesOrderState.PENDING) return;
        if (currentState == SalesOrderState.ACTIVE) {
            var pendingStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER", "Pending")
                    .orElseThrow(() -> new StatusNotFoundException("Status 'Pending' not found for SALES_ORDER type"));
            moveOrder(so, pendingStatus.getId(), SalesOrderState.PENDING, "auto-promoted before charge");
            logger.info("Order {} auto-promoted Active→Pending for charge", so.getId());
            return;
        }
        throw new InvalidSalesOrderChargeException(so.getId(), currentState == null ? "missing" : currentState.name());
    }

    @Transactional
    public SalesOrderResponse updateSalesOrderStatus(UUID id, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating sales order status: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // The request names a catalog row; the machine only reads its code (ADR-0004 decision 7). This is
        // also where a status of another family, an uncoded row or a code the enum lacks is refused.
        var newState = statusCatalog.requireOrderState(request.statusId());
        // The guard runs BEFORE the reversal below, and the order of the two is the invariant: a refused
        // move is a 409 that leaves the row, the history and the stock exactly as they were, so a stock
        // side effect that ran first would be a refusal that already changed something (ADR-0004 T2).
        DocumentStates.requireTransition(so.getStatus(), newState);

        // Restore stock when transitioning to Cancelled. Only enabled items still hold stock:
        // soft-deleted items were already reversed when deleted, and a line cancelled individually
        // was already reversed then, so the ledger makes those reversals a no-op here (W3-D4).
        if (newState == SalesOrderState.CANCELLED) {
            restoreAllLines(so.getCompanyStoreId(), itemRepository.findBySalesOrderIdAndEnabledTrue(id));
        }

        var updated = moveOrder(so, request.statusId(), newState, "status update");

        return toResponse(updated);
    }

    @Transactional
    public void deleteSalesOrder(UUID id) {
        logger.info("Soft-deleting sales order: id={}", id);

        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // Restore stock ONLY if the order was not already Cancelled: the cancel transition already
        // restored it, and restoring again would duplicate stock. Only enabled items still hold
        // stock (soft-deleted items were reversed on delete); a line already restored keeps a zero
        // ledger remainder, so the engine's reversal no-ops it and cannot credit it twice.
        if (so.getStatus() != SalesOrderState.CANCELLED) {
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
        validateProductVariantExists(request.productVariantId(), so.getCompanyStoreId());

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
                .status(INITIAL_ITEM_STATE)
                .enabled(true)
                .build();

        var saved = itemRepository.save(item);
        recordItemCreated(so.getCompanyId(), saved);
        logger.info("Item added: id={}, soId={}", saved.getId(), salesOrderId);

        // Deduct stock for the new item, referencing its id so a later reversal is exact (W3-D6).
        applyStockChanges(List.of(
                deduct(so.getCompanyStoreId(), saved.getProductVariantId(), saved.getQuantity(), saved.getId())));

        // Recalculate order total
        recalculateTotalAmount(salesOrderId);

        // Auto-transition from Draft → Active when the first item is added. Only a draft activates:
        // a Pending order whose lines were all soft-deleted has no transition back to Active, so
        // asking for one would refuse a legal add with a 409 that says nothing about it.
        if (isFirstItem && so.getStatus() == SalesOrderState.DRAFT) {
            var activeStatus = statusRepository
                    .findByTypeNameAndStatusName("SALES_ORDER", "Active")
                    .orElseThrow(() -> new StatusNotFoundException("Status 'Active' not found for SALES_ORDER type"));
            moveOrder(so, activeStatus.getId(), SalesOrderState.ACTIVE, "first line added");
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

        validateProductVariantExists(request.productVariantId(), so.getCompanyStoreId());

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
            var operations = new ArrayList<SaleChange>();
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

        validateItemIsModifiable(item);

        // Restore through the engine BEFORE soft-deleting the item, referencing the line id so the
        // reversal credits exactly the locations the sale took from (W3-D6). With the line guard
        // above (F-7), a line whose ledger reference is already fully reversed — individually
        // cancelled or soft-deleted — is no longer reachable through this endpoint, so the engine's
        // remainder arithmetic is defence in depth rather than the active rule: the supported route
        // to remove a cancelled line is the order-level PUT (W3-D13), which deals in the whole line
        // set and soft-deletes it without a second reversal.
        // See odd/tasks/sales-location-aware-stock.md.
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

        var newState = statusCatalog.requireItemState(request.statusId());
        // Same order as the order-level put and for the same reason: the guard decides before the line's
        // reversal runs, so a refused move restores nothing.
        DocumentStates.requireTransition(item.getStatus(), newState);

        // W3-D4: cancelling a line restores it exactly as deleting it does, writing the reversal
        // that matches its SALE movement. A line cancelled and later deleted must not restore
        // twice: the first reversal leaves a zero uncovered remainder for the line's reference, so
        // the second reversal is a no-op by the engine's remainder arithmetic (W3-D6).
        if (newState == SalesOrderItemState.CANCELLED) {
            applyStockChanges(List.of(reverse(so.getCompanyStoreId(), item.getProductVariantId(), item.getId())));
        }

        var updated = moveItem(item, so.getCompanyId(), request.statusId(), newState, "status update");

        return toItemResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    /**
     * Loads an order that still accepts a line-level write, and refuses a closed one.
     *
     * <p>One rule for every write surface (F-3): only a <em>terminal</em> status closes the order.
     * These endpoints used to be stricter than the order-level {@code PUT} and refused a
     * {@code Pending} order as well, which froze the line table in the very state the operator sits
     * in after the first scanned line, while the same order still accepted a full item diff through
     * the {@code PUT}. The stricter rule had no mechanical reason behind it: both surfaces write
     * their stock through the same engine call, and charge re-reads the order.</p>
     */
    private SalesOrder loadAndValidateModifiableSO(UUID id) {
        var so = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        requireOrderNotTerminal(so);
        return so;
    }

    /**
     * Rejects a write against an order that has reached a terminal status.
     *
     * <p>A terminal status has no outgoing transition, so the order is closed: letting a write run
     * would move stock that no automatic path can bring back. The concrete case is the order-level
     * item diff on a {@code Cancelled} order: a new line was inserted and its full quantity deducted,
     * while order cancel and order delete both skip a {@code Cancelled} order and charge refuses it,
     * so the deduction was stranded. {@code Completed} is terminal for the same reason and is refused
     * too.</p>
     *
     * <p>It answers nothing because it is the whole rule: no write surface is stricter than this one
     * since F-3 closed the item-level endpoints on the same policy.</p>
     *
     * @throws SalesOrderAlreadyFinalizedException (409) when the status is terminal
     */
    private void requireOrderNotTerminal(SalesOrder so) {
        var state = so.getStatus();
        // `DocumentStates.isTerminal` answers true for a document with no state as well, and that is the
        // fail-closed side: a state this enum does not know cannot be moved by anything this service does.
        if (DocumentStates.isTerminal(state)) {
            throw new SalesOrderAlreadyFinalizedException(so.getId(), state == null ? "missing" : state.name());
        }
    }

    /**
     * Rejects an item-level write against a line that no longer holds stock. A {@code Cancelled}
     * line was reversed in full, so selling it again would leave a {@code SALE} row the rule says no
     * line holds; a soft-deleted line is never re-enabled by the item-level endpoint, so a deduction
     * on it would be stranded. The order-level PUT is the supported route to revive either one,
     * because it re-enables the line before re-selling it (W3-D11).
     *
     * @throws SalesOrderItemNotModifiableException (409) when the line is cancelled or soft-deleted
     * @throws StatusNotFoundException when the {@code Cancelled} reference row is missing, because a
     *     line that cannot be classified could be one that already gave its stock back (F-2)
     */
    private void validateItemIsModifiable(SalesOrderItem item) {
        if (!item.getEnabled()) {
            throw new SalesOrderItemNotModifiableException(item.getId(), "deleted");
        }
        if (isCancelledLine(item)) {
            throw new SalesOrderItemNotModifiableException(item.getId(), SalesOrderItemState.CANCELLED.name());
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
    private CompanyStore validateCompanyStoreExists(UUID id) {
        var store = companyStoreRepository.findById(id).orElseThrow(() -> new CompanyStoreNotFoundException(id));
        verifyStoreAccess(store);
        return store;
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
                companyStoreOwnerResolver.companyIdOf(store),
                country.getId(),
                region.getId(),
                zone.getId(),
                store.getId());
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

    /**
     * Refuses a variant the order's store cannot sell, and refuses it as if it did not exist.
     *
     * <p>The check has to be asked of a company. A bare {@code existsByIdAndEnabledTrue} answers
     * whether <em>a row</em> exists: no product, no store, no tenant, and it accepts another company's
     * variant as readily as this one's — which is the weakest question this feature can ask of a
     * catalog that became per company. The company asked for is the one that owns the order's store,
     * because the stock the line is about to deduct lives at that store.
     *
     * <p>The store row is read rather than the whole entity cached on the order: {@code SalesOrder}
     * carries only {@code company_store_id}. A missing store is a 404 of its own, since there is no
     * tenant to ask the question of.</p>
     *
     * <p>Contract kept whole: a soft-deleted variant is still refused, because the finder filters
     * {@code enabled = true} — the split removed {@code findByIdForUpdate}, whose query carried that
     * filter, and a variant must not become sellable again by being deleted.</p>
     *
     * @throws ProductVariantNotFoundException when the variant does not exist, is soft-deleted, or
     *     belongs to another company
     * @throws CompanyStoreNotFoundException when the order's store does not exist
     */
    private void validateProductVariantExists(UUID id, UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));

        if (!productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(
                id, companyStoreOwnerResolver.companyIdOf(store))) {
            throw new ProductVariantNotFoundException(id);
        }
    }

    // ─── The typed state machine ────────────────────────────────────────
    //
    // Every path that moves an order or a line goes through the two helpers below and through nothing else.
    // The decision itself is `DocumentStates` over `SalesOrderState`/`SalesOrderItemState` (ADR-0004
    // decisions 2 and 3), which replaced the two `Map<String, Set<String>>` tables and the four name
    // comparisons that used to sit next to them.

    /**
     * Writes a document's state and the catalog pointer together, and that pairing is the only reason this
     * method exists: {@code status} is the enum's column and {@code status_id} is the pointer the screens and
     * every existing reader still use, so a path that writes one and forgets the other either trips
     * {@code V66}'s bridge (which refuses the two disagreeing) or leaves the response and the row saying
     * different things.
     *
     * <p><b>This is the seam T8 deletes.</b> ADR-0004 migration step 9 drops {@code status_id} and its four
     * indexes once every reader uses the new column; when that lands, this method loses one of its two
     * arguments, its body becomes the {@code setStatus} that is left, and each of its call sites keeps
     * working with the unused id removed. Until then it is the one place the pair is written — not repeated
     * at the eight call sites — so T8 has one thing to delete instead of eight to find.</p>
     */
    private static void writeState(SalesOrder order, UUID statusId, SalesOrderState state) {
        order.setStatusId(statusId);
        order.setStatus(state);
    }

    /** The line's half of {@link #writeState(SalesOrder, UUID, SalesOrderState)}, deleted by the same T8. */
    private static void writeState(SalesOrderItem line, UUID statusId, SalesOrderItemState state) {
        line.setStatusId(statusId);
        line.setStatus(state);
    }

    /**
     * Moves an order: the guard decides, the pair is written, the row is saved and the move is appended to
     * the history — all in the caller's transaction, which is what makes the deferred checks of ADR-0004
     * decision 5 judge the state and its row together.
     *
     * @throws InvalidStatusTransitionException (409) when the enum does not declare the move; nothing is
     *     written in that case, because the guard runs before the first write
     */
    private SalesOrder moveOrder(SalesOrder so, UUID targetStatusId, SalesOrderState to, String reason) {
        var from = so.getStatus();
        DocumentStates.requireTransition(from, to);
        writeState(so, targetStatusId, to);
        var saved = salesOrderRepository.save(so);
        flushDocuments();
        statusTransitionRecorder.recordMove(StatusSubject.SALES_ORDER, so.getCompanyId(), so.getId(), from, to, reason);
        return saved;
    }

    /**
     * The line's half of {@link #moveOrder(SalesOrder, UUID, SalesOrderState, String)}. The tenant is passed
     * in because a line carries none: {@code sales_order_items} has no {@code company_id}, and the history row
     * names the tenant of its parent order.
     */
    private SalesOrderItem moveItem(
            SalesOrderItem line, UUID companyId, UUID targetStatusId, SalesOrderItemState to, String reason) {
        var from = line.getStatus();
        DocumentStates.requireTransition(from, to);
        writeState(line, targetStatusId, to);
        var saved = itemRepository.save(line);
        flushDocuments();
        statusTransitionRecorder.recordMove(StatusSubject.SALES_ORDER_ITEM, companyId, line.getId(), from, to, reason);
        return saved;
    }

    /**
     * Makes the document writes visible to the trigger that is about to judge them.
     *
     * <p><b>Why a flush, and not just an order of writes.</b> ADR-0004 decision 5's own checks are
     * <em>deferred</em> to commit, which is what lets the two writes of a transaction happen in either order.
     * Decision 6's check is not: it is a {@code BEFORE INSERT} trigger on {@code status_transition}, so it is
     * immediate — it resolves the document the row names and refuses a row whose document it cannot see under
     * that {@code company_id}. Hibernate defers its INSERTs and UPDATEs to the flush, so without this call the
     * resolver reads a document the database has not received yet, and the history row is refused with a
     * message about the wrong tenant that actually means "not written yet". It flushes the whole persistence
     * context on purpose: a line's tenant is its parent order's, so a line's row needs the order's write
     * visible as well.</p>
     */
    private void flushDocuments() {
        salesOrderRepository.flush();
    }

    /**
     * Appends the row that creates a line. The state itself is written by the builder of a line that has just
     * been born, because the row does not exist until it is saved — and a creation row needs the id.
     */
    private void recordItemCreated(UUID companyId, SalesOrderItem line) {
        flushDocuments();
        statusTransitionRecorder.recordCreation(
                StatusSubject.SALES_ORDER_ITEM, companyId, line.getId(), INITIAL_ITEM_STATE);
    }

    // ─── Stock Movement Through the Inventory Engine ───────────────────

    /**
     * Sends one batch of stock work to {@link InventoryService}, the only writer of the per-store
     * aggregate, the per-location balances and the ledger. The batch's ordering is the engine's
     * contract (F-1), not this caller's: it locks every distinct {@code (store, variant)} row in key
     * order before it runs anything, and runs every reversal before every deduction. A reversal is
     * reference-scoped, so a deduction of the same reference running first would put a
     * {@code SALE} row in front of it and the reversal would credit the fresh sale back.
     */
    private void applyStockChanges(List<SaleChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        inventoryService.applySaleBatch(SALE_REFERENCE_TYPE, currentUserContext.getUsername(), changes);
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
            List<SaleChange> operations) {

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
        var operations = new ArrayList<SaleChange>();
        for (var item : items) {
            operations.add(reverse(companyStoreId, item.getProductVariantId(), item.getId()));
        }
        applyStockChanges(operations);
    }

    /**
     * A line in the terminal {@code CANCELLED} state: it holds no stock and is not revived.
     *
     * <p><b>Classification cannot fail any more, and that is the repair of F-2.</b>
     * {@code odd/tasks/sales-location-aware-stock.md} records the original defect — a lookup that answered
     * {@code orElse(null)} classified every line as "not cancelled", so a cancelled line was modified again
     * and the order-level PUT sold it as if it still held stock — and the fix then was to refuse the write
     * when the {@code Cancelled} catalog row could not be read. The state is a column now (ADR-0004 decision
     * 3), so there is nothing to read and nothing to guess: a line whose state is not {@code CANCELLED} is
     * live, and the question has one answer.</p>
     */
    private static boolean isCancelledLine(SalesOrderItem line) {
        return line.getStatus() == SalesOrderItemState.CANCELLED;
    }

    private SaleChange deduct(UUID companyStoreId, UUID variantId, BigDecimal quantity, UUID itemId) {
        return SaleChange.deduction(companyStoreId, variantId, quantity, itemId);
    }

    private SaleChange reverse(UUID companyStoreId, UUID variantId, UUID itemId) {
        return SaleChange.reversal(companyStoreId, variantId, itemId);
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
                so.getStatus() == null ? null : so.getStatus().name(),
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
                item.getStatus() == null ? null : item.getStatus().name(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
