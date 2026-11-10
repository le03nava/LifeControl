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
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine.SaleChange;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalePaymentRequest;
import com.lifecontrol.api.salesorder.dto.SalePaymentResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.salesorder.exception.SalesOrderAlreadyFinalizedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderEmptyChargeException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderItemNotModifiableException;
import com.lifecontrol.api.salesorder.exception.SalesOrderNotFoundException;
import com.lifecontrol.api.salesorder.exception.SalesOrderPaymentInsufficientException;
import com.lifecontrol.api.salesorder.exception.SalesOrderPaymentTotalMismatchException;
import com.lifecontrol.api.salesorder.exception.SalesOrderStoreReassignmentNotAllowedException;
import com.lifecontrol.api.salesorder.exception.SalesOrderTenderInconsistentException;
import com.lifecontrol.api.salesorder.exception.SalesOrderTenderNotCashException;
import com.lifecontrol.api.salesorder.model.SalePayment;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.salesorder.repository.SalePaymentRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.exception.ShiftNotOpenException;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import com.lifecontrol.api.tax.service.TaxCodeService;
import com.lifecontrol.api.tax.service.TaxRateService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * this delivery, and the row that carries that label is now a projection of this constant instead of an
     * input: no catalog lookup is needed to create an order, and {@link DocumentStates} is what decides every
     * move after it.
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
    private final SalePaymentRepository salePaymentRepository;
    private final CustomerRepository customerRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final ShiftRepository shiftRepository;
    private final ProductVariantRepository productVariantRepository;
    private final TaxRateService taxRateService;
    private final StockLedgerEngine stockLedgerEngine;
    private final StatusRepository statusRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CurrentUserContext currentUserContext;
    private final DocumentSequenceAllocator documentSequenceAllocator;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final SalesOrderStatusCatalog statusCatalog;

    /**
     * The identity map the response mappers read through, and the only way to leave it: a line's derived money is
     * written by the schema (V81, V85), so the instance a write left behind carries the value the insert sent —
     * nothing — until it is refreshed. See {@link #refreshDerivedLineMoney(SalesOrderItem)}.
     */
    private final EntityManager entityManager;

    public SalesOrderService(
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository itemRepository,
            SalePaymentRepository salePaymentRepository,
            CustomerRepository customerRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            ShiftRepository shiftRepository,
            ProductVariantRepository productVariantRepository,
            TaxRateService taxRateService,
            StockLedgerEngine stockLedgerEngine,
            StatusRepository statusRepository,
            PaymentMethodRepository paymentMethodRepository,
            CurrentUserContext currentUserContext,
            DocumentSequenceAllocator documentSequenceAllocator,
            StatusTransitionRecorder statusTransitionRecorder,
            SalesOrderStatusCatalog statusCatalog,
            EntityManager entityManager) {
        this.salesOrderRepository = salesOrderRepository;
        this.itemRepository = itemRepository;
        this.salePaymentRepository = salePaymentRepository;
        this.customerRepository = customerRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.shiftRepository = shiftRepository;
        this.productVariantRepository = productVariantRepository;
        this.taxRateService = taxRateService;
        this.stockLedgerEngine = stockLedgerEngine;
        this.statusRepository = statusRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.currentUserContext = currentUserContext;
        this.documentSequenceAllocator = documentSequenceAllocator;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.statusCatalog = statusCatalog;
        this.entityManager = entityManager;
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

        var companyId = companyStoreOwnerResolver.companyIdOf(store);

        // ADR-0003 decision 4: the document date is read ONCE and the period comes from that value, so a
        // sale created at 23:59 on the last day of a month cannot land in the next period depending on
        // when its transaction reaches the allocator. The same `orderDate` is what the row stores.
        var orderDate = LocalDateTime.now();
        var period = DocumentNumberRenderer.periodOf(orderDate.toLocalDate());

        // The allocation is the last write before the row is created, not the last write before commit:
        // `sales_orders` needs its number at INSERT time, so "allocate as the last write before commit" is
        // unattainable on this path. The sequence row's lock is therefore held while the lines are written,
        // which is the cost the ADR records rather than one this code can avoid. No stock work runs here any
        // more: the charge books it (ADR-0002 decision 7).
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

        // The lines are persisted here and nothing else: a document holds no stock until it is charged
        // (ADR-0002 decision 7), so the charge is what books the movement and what refuses a sale the
        // store cannot cover. The id is still what the charge's ledger reference names (W3-D6).
        if (request.items() != null && !request.items().isEmpty()) {
            for (var reqItem : request.items()) {
                var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                // The copy: a row is born here, so its code and rate are resolved now and never again. The
                // document's own date is the one allocated above and stored on the header, which is what makes
                // "the rate that applies" a fact about this sale and not about when a reader looks at it.
                var tax = lineTaxFor(companyId, reqItem.productVariantId(), orderDate);
                var newItem = SalesOrderItem.builder()
                        .salesOrderId(saved.getId())
                        .productVariantId(reqItem.productVariantId())
                        .quantity(reqItem.quantity())
                        .listPrice(reqItem.listPrice())
                        .discountApplied(discount)
                        .taxCode(tax.taxCode())
                        .taxRate(tax.taxRate())
                        .promotionId(reqItem.promotionId())
                        .status(INITIAL_ITEM_STATE)
                        .enabled(true)
                        .build();
                var savedItem = itemRepository.save(newItem);
                recordItemCreated(companyId, savedItem);
            }

            recomputeBreakdown(saved.getId());
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

        var so = salesOrderRepository.findByIdForUpdate(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        validateCustomerExists(request.customerId());
        validateCompanyStoreExists(request.companyStoreId());
        if (request.shiftId() != null) {
            validateShiftExists(request.shiftId());
        }

        // A store change with active lines stays refused, and after T6b the reason is a narrower one: nothing
        // is booked before the charge any more, so the charge would simply book at the new store and the
        // refusal is stricter than the invariant behind it. Narrowing it is a decision of its own and is
        // recorded as a follow-up in odd/tasks/money-first-class.md (T6b log); relaxing it inside the slice
        // that moved the booking would be a second, unstated rule change.
        if (!request.companyStoreId().equals(so.getCompanyStoreId())
                && !itemRepository.findBySalesOrderIdAndEnabledTrue(id).isEmpty()) {
            throw new SalesOrderStoreReassignmentNotAllowedException(
                    id, so.getCompanyStoreId(), request.companyStoreId());
        }

        // A terminal order is closed: no transition leads out of it and the charge refuses it, so a line
        // edited here would be a line no charge can ever book. Refuse before the header is mutated.
        requireOrderNotTerminal(so);

        so.setCustomerId(request.customerId());
        so.setCompanyStoreId(request.companyStoreId());
        so.setShiftId(request.shiftId());
        so.setUserId(request.userId());

        var updated = salesOrderRepository.save(so);

        // Item diff: add/update/delete inline items atomically
        if (request.items() != null && !request.items().isEmpty()) {
            var existingItems = itemRepository.findBySalesOrderId(id);

            // Every line of the request keeps its identity across the diff: a line the request drops is
            // soft-deleted and a line it names is re-enabled. Nothing here books stock any more, which is
            // what makes the whole diff a document edit rather than a stock operation (ADR-0002 decision 7).
            var requestIds = request.items().stream()
                    .map(SalesOrderItemRequest::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // DELETE: items in DB but not in request → soft-delete
            for (var existing : existingItems) {
                if (!requestIds.contains(existing.getId())) {
                    existing.setEnabled(false);
                    itemRepository.save(existing);
                }
            }

            // UPDATE (existing) or INSERT (new). A new line's generated id is still the reference the
            // charge's ledger row names (W3-D6), so it is persisted here and booked there.
            for (var reqItem : request.items()) {
                if (reqItem.id() != null) {
                    // UPDATE: find by id, update fields, re-enable
                    var item = itemRepository
                            .findById(reqItem.id())
                            .orElseThrow(() -> new SalesOrderItemNotFoundException(reqItem.id()));
                    var variantChanged = !item.getProductVariantId().equals(reqItem.productVariantId());
                    item.setProductVariantId(reqItem.productVariantId());
                    item.setQuantity(reqItem.quantity());
                    item.setListPrice(reqItem.listPrice());
                    var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                    item.setDiscountApplied(discount);
                    item.setPromotionId(reqItem.promotionId());
                    item.setEnabled(true);
                    // NO RE-RESOLUTION, and this is the rule rather than an omission (ADR-0002 decision 6, S1).
                    // The rate was copied when the line's row was created, and that copy is the snapshot that
                    // makes a rate change unable to rewrite history. The document's date did not move either —
                    // it is the header's `order_date`, stated once at creation — so resolving again here could
                    // only ever pick up a rate stated after this sale existed, and would restate a past sale in
                    // the one place decision 6 says it must not happen. The derivation still runs: the update
                    // names `list_price`, `quantity`, `discount_applied` and `tax_rate`, so `V90`'s trigger
                    // restates `tax_amount` and `final_price` from the STORED rate, which is the right arithmetic
                    // when a discount or a quantity changes.
                    //
                    // THE ONE EXCEPTION, because it is a different fact and not a rate change: a line moved to
                    // another variant stops being the same article, so the code its product declares and the rate
                    // the document's date resolves are taken again. Keeping the previous article's snapshot would
                    // tax this sale with a code its product does not declare — a wrong number, silently, which is
                    // the failure this unit exists to remove.
                    if (variantChanged) {
                        var tax = lineTaxFor(so.getCompanyId(), reqItem.productVariantId(), so.getOrderDate());
                        item.setTaxCode(tax.taxCode());
                        item.setTaxRate(tax.taxRate());
                    }
                    itemRepository.save(item);
                } else {
                    // INSERT: new item with default "Pending" status
                    var discount = reqItem.discountApplied() != null ? reqItem.discountApplied() : BigDecimal.ZERO;
                    // A new row is a new sale, so the copy runs here for the same reason it runs on the create.
                    var tax = lineTaxFor(so.getCompanyId(), reqItem.productVariantId(), so.getOrderDate());
                    var newItem = SalesOrderItem.builder()
                            .salesOrderId(id)
                            .productVariantId(reqItem.productVariantId())
                            .quantity(reqItem.quantity())
                            .listPrice(reqItem.listPrice())
                            .discountApplied(discount)
                            .taxCode(tax.taxCode())
                            .taxRate(tax.taxRate())
                            .promotionId(reqItem.promotionId())
                            .status(INITIAL_ITEM_STATE)
                            .enabled(true)
                            .build();
                    var savedItem = itemRepository.save(newItem);
                    recordItemCreated(so.getCompanyId(), savedItem);
                }
            }

            // Recalculate order total after item mutations
            recomputeBreakdown(id);

            // Reload to include updated items in response
            updated = salesOrderRepository.findById(id).orElseThrow(() -> new SalesOrderNotFoundException(id));
        }

        return toResponse(updated);
    }

    @Transactional
    public SalesOrderResponse chargeSalesOrder(UUID id, ChargeSalesOrderRequest request) {
        logger.info("Charging sales order: id={}", id);

        // ADR-0002 M10: the lock is taken by the statement that reads, and everything below runs on it. It is
        // what makes a retried request a no-op instead of a second payment: the retry serializes behind the
        // first commit, loads the row again and finds it Completed, so the ADR-0004 guard refuses the move
        // before a payment row exists. Loading without the lock and locking later would leave the read that
        // decides stale, which is exactly the state the second charge needs in order to slip through.
        var so = salesOrderRepository.findByIdForUpdate(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // Ensure order is Pending (auto-promotes Active → Pending)
        ensureOrderIsPending(so);

        // The payments the charge is asked to append. Every one of them is judged here, before the breakdown and
        // before any row exists: the method is resolved (404 if the catalog does not have it, and its `is_cash` is
        // what M4 reads), the tender is refused on a method that is not cash, and the tender arithmetic is refused
        // as a 400 rather than met at flush as the constraint's generic 409. Nothing has been written at this point
        // except the auto-promotion above, which is part of this transaction and rolls back with the refusal.
        var payments = request.payments();
        validateChargePayments(so, payments);

        // The parts first, and the payment second: the payment states the document's total as its amount, so a
        // payment written before the breakdown would state the previous order of magnitude — or the zero a
        // brand-new document carries. This is the half of decision 7 that makes M1 true at the only moment a
        // document becomes money that has moved.
        recomputeBreakdown(so);

        // Nothing to pay is a refusal, not a zero payment. `V83` holds `CHECK (amount > 0)`, so the insert below
        // would be refused at flush as a `DataIntegrityViolationException` — a 409 the handler states as a data
        // constraint and that names neither the order nor the reason. The guard runs here, before the write, so
        // the business rule explains itself and no payment row is ever attempted. It runs BEFORE the M3 guard
        // below on purpose: a document whose enabled lines add up to zero has nothing to pay whatever a client
        // sends, and "there is nothing to pay" is the refusal that says so.
        if (so.getTotalAmount().signum() <= 0) {
            throw new SalesOrderEmptyChargeException(so.getId(), so.getTotalAmount());
        }

        // ADR-0002 M3, the half that judges the request: the payments apply to the sale, so they add up to the
        // document's total exactly — not "at least", which would accept a row stating money the document does not
        // cost. The table-reading guard below the inserts stays: this one judges what was asked for, that one
        // judges what landed, and only the second can see a previous request's rows or a void.
        requirePaymentsCoverTheTotalExactly(so, payments);

        // One row per payment, the whole of what the request stated: the direction is written here and not taken
        // from the request (D-g — the charge takes money in and never gives it back), and the tender pair is
        // written as the operator counted it, absent when nobody counted cash.
        var actor = currentUserContext.getUsername();
        for (var payment : payments) {
            salePaymentRepository.save(SalePayment.charge(
                    so.getCompanyId(),
                    so.getId(),
                    payment.paymentMethodId(),
                    payment.amount(),
                    payment.tenderedAmount(),
                    payment.changeAmount(),
                    actor));
        }

        // ADR-0002 M3: the document transitions to Completed only when its non-voided payments cover its total.
        // The sum is the schema's — a refund subtracts and a void row excludes — so this guard and any report
        // that reads the same rows cannot disagree about what the customer paid.
        var netPaid = salePaymentRepository.netPaid(so.getCompanyId(), so.getId());
        var paid = netPaid == null ? BigDecimal.ZERO : netPaid;
        if (paid.compareTo(so.getTotalAmount()) < 0) {
            throw new SalesOrderPaymentInsufficientException(so.getId(), paid, so.getTotalAmount());
        }

        // ADR-0002 decision 7: the stock is booked HERE, by the charge, inside this transaction and under
        // the order row lock (M10) taken above, as ONE batch. Until this line runs the document holds no
        // stock, which is exactly why a cancellation or a deletion before the charge has nothing to give
        // back. The batch's inner order is the engine's contract and not this caller's — it locks every
        // distinct (store, variant) before it runs anything — so the lock order here is order row, then
        // stock position, and two concurrent charges cannot invert it. A sale the store cannot cover throws
        // from here, and the payment, the breakdown, the lines and the transitions roll back with it.
        var items = itemRepository.findBySalesOrderId(id);
        var stockOperations = new ArrayList<SaleChange>();
        for (var item : items) {
            if (!Boolean.TRUE.equals(item.getEnabled()) || item.getStatus() == SalesOrderItemState.CANCELLED) {
                continue;
            }
            // The bump is taken before the movement it numbers (V73): the charge is the one write that books
            // a line, so it is the one writer of that line's sequence.
            stockOperations.add(deduct(
                    so.getCompanyStoreId(),
                    item.getProductVariantId(),
                    item.getQuantity(),
                    item.getId(),
                    nextWriteSeq(item.getId())));
        }
        applyStockChanges(stockOperations);

        // Transition header to Completed
        moveOrder(so, SalesOrderState.COMPLETED, "charge");

        // Transition the lines that are waiting for it to Added. A line's classification is its typed
        // state and not a catalog lookup: CANCELLED is terminal (the batch above skipped it, so there is no
        // edge out of it) and ADDED is already charged, so only PENDING moves — the same rule that used
        // to be the `!"Cancelled".equals(statusName)` test, stated once, next to the enum (ADR-0004
        // decision 3).
        var itemsToUpdate = new ArrayList<SalesOrderItem>();
        for (var item : items) {
            if (!Boolean.TRUE.equals(item.getEnabled()) || item.getStatus() != SalesOrderItemState.PENDING) {
                continue;
            }
            moveItem(item, so.getCompanyId(), SalesOrderItemState.ADDED, "charge");
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
            moveOrder(so, SalesOrderState.PENDING, "auto-promoted before charge");
            logger.info("Order {} auto-promoted Active→Pending for charge", so.getId());
            return;
        }
        throw new InvalidSalesOrderChargeException(so.getId(), currentState == null ? "missing" : currentState.name());
    }

    /**
     * Everything a charge's payments have to satisfy before a row is written: the method exists, a tender is only
     * stated on a cash method (M4), and the tender arithmetic is the constraint's own (S4).
     *
     * <p><b>Why all three run here and not in the schema.</b> {@code fk_sale_payment_method} makes a missing method a
     * foreign-key error at flush, {@code ck_payment_tender} holds the arithmetic, and neither can read
     * {@code payment_methods.is_cash} — a {@code CHECK} cannot see another table, so M4 has no schema form at all.
     * The refusals therefore say what the rule is, in the request's own terms: the payment's position, its method
     * by name and id, and the two numbers that disagree.</p>
     *
     * <p><b>Why the position and not the id.</b> A payment carries no identity until it is inserted, so "payment 2
     * of 3" is the only handle a client holding the body it just sent can use to find the row being refused.</p>
     *
     * <p><b>Why {@code findById} and not {@code existsById}.</b> The charge used to ask only whether the catalog
     * carried the id, which cannot answer whether change is allowed. The row now carries the answer, so the read
     * is the same statement with the flag on it — one per payment, and a sale of three payments costs three.</p>
     */
    private void validateChargePayments(SalesOrder so, List<SalePaymentRequest> payments) {
        for (var index = 0; index < payments.size(); index++) {
            var payment = payments.get(index);
            var position = (index + 1) + " of " + payments.size();
            var method = paymentMethodRepository
                    .findById(payment.paymentMethodId())
                    .orElseThrow(() -> new PaymentMethodNotFoundException(payment.paymentMethodId()));
            var methodLabel = "'" + method.getPaymentMethodName() + "' (" + method.getId() + ")";

            // A payment that states no tender is a charge through a card, a transfer or a cash payment nobody
            // counted, and is judged by nothing here: the column is optional and `null` is not zero.
            if (payment.tenderedAmount() == null && payment.changeAmount() == null) {
                continue;
            }

            // ADR-0002 M4. `is_cash` is a NOT NULL column, so a null here is a row this service cannot judge and
            // refuses rather than a cash method it guesses.
            if (!Boolean.TRUE.equals(method.getIsCash())) {
                throw new SalesOrderTenderNotCashException(
                        so.getId(),
                        position,
                        payment.tenderedAmount(),
                        payment.changeAmount(),
                        method.getId(),
                        method.getPaymentMethodName());
            }

            // The pair is stated together or not at all, which is `ck_payment_tender`'s first clause read before it
            // has a row to judge.
            if (payment.tenderedAmount() == null || payment.changeAmount() == null) {
                throw SalesOrderTenderInconsistentException.halfAPair(
                        so.getId(), position, methodLabel, payment.tenderedAmount(), payment.changeAmount());
            }

            if (payment.tenderedAmount().compareTo(payment.amount()) < 0) {
                throw SalesOrderTenderInconsistentException.tenderBelowAmount(
                        so.getId(), position, methodLabel, payment.amount(), payment.tenderedAmount());
            }

            // `change = tendered - amount`, compared as numbers and not as strings: the column is 19,4, so a
            // request stating 50 against 100.0000 - 50.0000 is the same change and must not be refused for scale.
            var expectedChange = payment.tenderedAmount().subtract(payment.amount());
            if (payment.changeAmount().compareTo(expectedChange) != 0) {
                throw SalesOrderTenderInconsistentException.changeIsNotTheDifference(
                        so.getId(),
                        position,
                        methodLabel,
                        payment.amount(),
                        payment.tenderedAmount(),
                        payment.changeAmount());
            }
        }
    }

    /**
     * ADR-0002 M3, read as the brief reads it: the payments apply to the sale, so their sum is the total exactly.
     *
     * <p><b>What this is not.</b> Not "at least the total": a sum above it would write a row stating money the
     * document does not cost, and the document's own tender columns are where "the customer handed over 500"
     * belongs. It is also not the guard that runs after the inserts — that one sums the rows the table holds, which
     * is the only statement M3 can make about the document, and it stays.</p>
     *
     * <p>The comparison is {@code compareTo} and not {@code equals}: the request states 19,4 (or fewer) decimals and
     * the column states 19,4, so {@code 200.00} and {@code 200.0000} are the same money.</p>
     */
    private void requirePaymentsCoverTheTotalExactly(SalesOrder so, List<SalePaymentRequest> payments) {
        var statedTotal = payments.stream().map(SalePaymentRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (statedTotal.compareTo(so.getTotalAmount()) != 0) {
            throw new SalesOrderPaymentTotalMismatchException(so.getId(), statedTotal, so.getTotalAmount());
        }
    }

    @Transactional
    public SalesOrderResponse updateSalesOrderStatus(UUID id, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating sales order status: id={}", id);

        // The row is read WITH the lock, and that is the whole of the fix this method needs: a cancellation that
        // read the row unlocked could reach a row a concurrent charge had already committed as COMPLETED. What it
        // recorded is PENDING -> CANCELLED — the state it read — so `V67`'s deferred side-B check refuses the
        // commit for a transition the document never made, and the request answers a 500: a legal cancellation
        // failing with an internal error, with the reversal gate downstream of it never reachable. The data does
        // survive, because the schema's deferred check is a real last line of defence; correctness resting on it
        // rather than on the lock is the defect. Taking the lock first means the loser reads COMPLETED, the state
        // machine refuses the move and the answer is the 409 it always should have been.
        var so = salesOrderRepository.findByIdForUpdate(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // The request names a catalog row; the machine only reads its code (ADR-0004 decision 7). This is
        // also where a status of another family, an uncoded row or a code the enum lacks is refused.
        var newState = statusCatalog.requireOrderState(request.statusId());
        // The guard runs before the write below, and the order of the two is the invariant: a refused move is a
        // 409 that leaves the row and the history exactly as they were, so a write that ran first would be a
        // refusal that already changed something (ADR-0004 T2).
        DocumentStates.requireTransition(so.getStatus(), newState);

        // No reversal here: a cancellation only ever leaves Draft, Active or Pending, and none of those holds
        // stock — the charge books it, and COMPLETED is terminal for this document (ADR-0004 decision 3). A
        // reversal exists only for an order whose charge booked something, and that path is `deleteSalesOrder`.
        var updated = moveOrder(so, newState, "status update");

        return toResponse(updated);
    }

    @Transactional
    public void deleteSalesOrder(UUID id) {
        logger.info("Soft-deleting sales order: id={}", id);

        // The row lock comes FIRST, before the reversal below touches a stock position: the charge takes the
        // order's lock and then the positions, so a path that reversed first and locked the order second would
        // take the same two rows in the opposite order and deadlock with it (40P01, surfaced as a 500). This path
        // reverses stock since T6b, which is what makes the order of the two locks its business.
        var so = salesOrderRepository.findByIdForUpdate(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        // A reversal exists only for an order whose charge booked something, and only a COMPLETED document
        // ever got that far (ADR-0002 decision 7): everything before it holds no stock, and CANCELLED never
        // charged. The engine credits only a line's uncovered remainder, so a line already given back stays a
        // no-op and is never credited twice (W3-D6).
        if (so.getStatus() == SalesOrderState.COMPLETED) {
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

        var discountApplied = request.discountApplied() != null ? request.discountApplied() : BigDecimal.ZERO;

        // A row is born on this path too, so the tax snapshot is resolved here and nowhere else on it (S1).
        var tax = lineTaxFor(so.getCompanyId(), request.productVariantId(), so.getOrderDate());

        var item = SalesOrderItem.builder()
                .salesOrderId(salesOrderId)
                .productVariantId(request.productVariantId())
                .quantity(request.quantity())
                .listPrice(request.listPrice())
                .discountApplied(discountApplied)
                .taxCode(tax.taxCode())
                .taxRate(tax.taxRate())
                .promotionId(request.promotionId())
                .status(INITIAL_ITEM_STATE)
                .enabled(true)
                .build();

        var saved = itemRepository.save(item);
        recordItemCreated(so.getCompanyId(), saved);
        logger.info("Item added: id={}, soId={}", saved.getId(), salesOrderId);

        // No stock work: the line is a line, and the charge is what books it (ADR-0002 decision 7).

        // Recalculate order total
        recomputeBreakdown(salesOrderId);

        // Auto-transition from Draft → Active when the first item is added. Only a draft activates:
        // a Pending order whose lines were all soft-deleted has no transition back to Active, so
        // asking for one would refuse a legal add with a 409 that says nothing about it.
        if (isFirstItem && so.getStatus() == SalesOrderState.DRAFT) {
            moveOrder(so, SalesOrderState.ACTIVE, "first line added");
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

        var discountApplied = request.discountApplied() != null ? request.discountApplied() : BigDecimal.ZERO;

        // NO RE-RESOLUTION when the row stays the same article, for the reason stated on the order-level PUT:
        // this path mutates an existing row's quantity, price and discount, and the rate on it is the snapshot of
        // the sale that created it. The derivation restates `tax_amount` and `final_price` from that stored rate,
        // which is the arithmetic these setters actually change. A line moved to another variant is the exception,
        // for the same reason as there: it is no longer the article whose tax was copied.
        var variantChanged = !item.getProductVariantId().equals(request.productVariantId());
        item.setProductVariantId(request.productVariantId());
        item.setQuantity(request.quantity());
        item.setListPrice(request.listPrice());
        item.setDiscountApplied(discountApplied);
        item.setPromotionId(request.promotionId());

        if (variantChanged) {
            var tax = lineTaxFor(so.getCompanyId(), request.productVariantId(), so.getOrderDate());
            item.setTaxCode(tax.taxCode());
            item.setTaxRate(tax.taxRate());
        }

        var updated = itemRepository.save(item);

        // Recalculate order total
        recomputeBreakdown(salesOrderId);

        return toItemResponse(updated);
    }

    @Transactional
    public void deleteSalesOrderItem(UUID salesOrderId, UUID itemId) {
        logger.info("Soft-deleting item: soId={}, itemId={}", salesOrderId, itemId);

        loadAndValidateModifiableSO(salesOrderId);

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        validateItemIsModifiable(item);

        // Nothing to restore: a line holds no stock until the charge books it (ADR-0002 decision 7), so a
        // document that has not been charged gives nothing back. The charge books the lines that remain.
        item.setEnabled(false);
        itemRepository.save(item);

        // Recalculate order total
        recomputeBreakdown(salesOrderId);

        logger.info("Item soft-deleted: id={}", itemId);
    }

    @Transactional
    public SalesOrderItemResponse updateSalesOrderItemStatus(
            UUID salesOrderId, UUID itemId, UpdateSalesOrderStatusRequest request) {
        logger.info("Updating item status: soId={}, itemId={}", salesOrderId, itemId);

        var so = salesOrderRepository
                .findByIdForUpdate(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));

        var item = itemRepository.findById(itemId).orElseThrow(() -> new SalesOrderItemNotFoundException(itemId));

        if (!item.getSalesOrderId().equals(salesOrderId)) {
            throw new SalesOrderItemNotFoundException(itemId);
        }

        var newState = statusCatalog.requireItemState(request.statusId());
        // Same order as the order-level put and for the same reason: the guard decides before the line's
        // reversal runs, so a refused move restores nothing.
        DocumentStates.requireTransition(item.getStatus(), newState);

        // A cancelled line holds no stock and moves no ledger row: nothing is booked before the charge
        // (ADR-0002 decision 7), and the charge skips a line in this state.
        var updated = moveItem(item, so.getCompanyId(), newState, "status update");

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
     *
     * <p><b>Why the load takes the row lock.</b> Every path that writes a line and then recomputes the
     * breakdown needs the order's lock, and {@link #recomputeBreakdown(UUID)} takes it <em>after</em> the line
     * write. {@code chargeSalesOrder} takes the same lock and then reads the lines, so a path that wrote the
     * line first and locked the order second would take the two rows in the opposite order and deadlock with
     * it (40P01, surfaced as a 500). Taking the order's lock before this method's caller reads or writes its
     * first line makes both orders identical — order, then line — and costs no extra statement: it is the
     * load this method already performed, with the lock the write was always going to take.</p>
     */
    private SalesOrder loadAndValidateModifiableSO(UUID id) {
        var so = salesOrderRepository.findByIdForUpdate(id).orElseThrow(() -> new SalesOrderNotFoundException(id));

        requireOrderNotTerminal(so);
        return so;
    }

    /**
     * Rejects a write against an order that has reached a terminal status.
     *
     * <p>A terminal status has no outgoing transition, so the order is closed: a line written into it is a line
     * the charge can never book, because the charge walks the enabled lines of a Pending or Active document only.
     * The concrete case is the order-level item diff on a {@code Cancelled} order — the line would sit there with
     * nothing booked and no path would ever sell it or give it back. {@code Completed} is terminal for the same
     * reason and is refused too.</p>
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
     * Rejects an item-level write against a line the charge will never book. A {@code Cancelled} line is terminal
     * and outside the set the charge walks; a soft-deleted line is never re-enabled by the item-level endpoint, so
     * editing it would change a line that no path sells and none gives back. The order-level PUT is the supported
     * route to revive either one, because it re-enables the line before the charge can see it again (W3-D11).
     *
     * @throws SalesOrderItemNotModifiableException (409) when the line is cancelled or soft-deleted
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
     * because the stock the charge will book for this line lives at that store.
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

    // ─── The line's tax snapshot (ADR-0002 decision 6) ──────────────────

    /**
     * The two facts a line snapshots when its row is created: the catalog code its product declares, and the rate
     * that applies to the document's date.
     *
     * <p>It is a value and not two setters at each of the three creation sites, so the two columns cannot be
     * stated apart: a code without its rate would be a line whose {@code ck_item_tax} disagrees with the sale.
     */
    private record LineTax(String taxCode, BigDecimal taxRate) {}

    /**
     * Resolves the tax a line being written must carry: the code from the sold variant's product, and the rate the
     * document's own date resolves for it (ADR-0002 decision 6 verbatim in S1 of
     * {@code odd/tasks/product-tax-code.md}).
     *
     * <p><b>Why the code comes from the product and not from the request.</b> S2: «El código vive en el catálogo del
     * artículo». {@code SalesOrderItemRequest} has no tax field and deliberately gains none — a client that could
     * state one would be stating a fiscal fact about an article it is only selling, and the server would have
     * nothing to check it against.</p>
     *
     * <p><b>Why an unresolvable rate is a refusal and not zero</b> (D-a, S6). {@code tax_rate} is empty in every
     * environment today and {@code NA} is the column's default, so "no rate means refuse" applied to every code
     * would refuse every sale in the tree, while "no rate means zero" applied to every code is exactly the silent
     * zero decision 6 exists to forbid. The two halves are therefore split by the code: the ones that are zero by
     * definition ({@link TaxCodeService#ZERO_RATE_CODES}) state {@code 0} with no lookup, and every other code
     * either resolves or refuses the line naming the code and the document's date — the two facts whoever
     * states the rate needs.</p>
     *
     * <p><b>Why the refusal is an {@code IllegalArgumentException}.</b> The {@code GlobalExceptionHandler} already
     * maps that class to {@code 400} with the message as the body, which is the shape the company currency's
     * {@code DisabledCurrencyException} uses for the same kind of sentence ("the catalog is the reference data you
     * picked from, and this value is not assignable"). No new exception type is needed, and its name would promise
     * a distinction nothing branches on. The line is refused before it is written, so the whole document rolls back
     * and no order row survives a refusal.</p>
     *
     * <p><b>Why no rate change can reach a line twice.</b> This method has exactly three callers, and all three
     * create a row (the order's create, the order-level PUT's insert branch and {@code addSalesOrderItem}). Every
     * path that mutates an existing row — the PUT's update branch and {@code updateSalesOrderItem} — deliberately
     * does not call it: the stored pair is the snapshot of the sale, and re-resolving on an edit could only pick up
     * a backdated rate that must not touch a past sale.</p>
     *
     * @param companyId the company that files the rate — the order's tenant, never a global default
     * @param productVariantId the variant the line sells, the only path from a line to a product's code
     * @param orderDate the order's own {@code order_date}; its date is the document's date, never "today"
     * @throws IllegalArgumentException (400) when the code needs a rate and the company states none for that date
     * @throws ProductVariantNotFoundException (404) when the variant is not readable, which also means the sale the
     *     line would be written into has nothing to sell
     */
    private LineTax lineTaxFor(UUID companyId, UUID productVariantId, LocalDateTime orderDate) {
        var taxCode = productVariantRepository
                .findProductTaxCodeByVariantId(productVariantId)
                // The code is NOT NULL since V89, so an empty answer is not "a product with no code": it is a
                // variant (or its product) this tenant cannot see, which is the 404 the other two line-write
                // sites already answer for the same reason.
                .orElseThrow(() -> new ProductVariantNotFoundException(productVariantId));

        if (TaxCodeService.isZeroRateByDefinition(taxCode)) {
            return new LineTax(taxCode, BigDecimal.ZERO);
        }

        var documentDate = orderDate.toLocalDate();
        return taxRateService
                .resolve(companyId, taxCode, documentDate)
                .map(resolution -> new LineTax(resolution.taxCode(), resolution.rate()))
                .orElseThrow(() -> new IllegalArgumentException("Cannot write the line: tax code "
                        + taxCode + " has no enabled rate applicable on " + documentDate
                        + ", the document's date. The sale is refused rather than taxed at zero (ADR-0002 "
                        + "decision 6, D-a): if the line is not taxable its product must declare a code that is "
                        + "zero by definition (" + String.join(", ", TaxCodeService.ZERO_RATE_CODES)
                        + "), and otherwise the "
                        + "company has to state the rate that applies from " + documentDate + " onward."));
    }

    // ─── The typed state machine ────────────────────────────────────────
    //
    // Every path that moves an order or a line goes through the two helpers below and through nothing else.
    // The decision itself is `DocumentStates` over `SalesOrderState`/`SalesOrderItemState` (ADR-0004
    // decisions 2 and 3), which replaced the two `Map<String, Set<String>>` tables and the four name
    // comparisons that used to sit next to them.
    //
    // T8 deleted the two `writeState` seams that used to live here. They existed for exactly one reason: the
    // row carried a typed state *and* a `status_id` catalog pointer, so every move had to write both and a path
    // that wrote one was a row that said two things. `V71` dropped the pointer, its indexes and the bridge of
    // `V66`, so a move is now one assignment to one column and there is nothing to pair.

    /**
     * Moves an order: the guard decides, the state is written, the row is saved and the move is appended to
     * the history — all in the caller's transaction, which is what makes the deferred checks of ADR-0004
     * decision 5 judge the state and its row together.
     *
     * @throws InvalidStatusTransitionException (409) when the enum does not declare the move; nothing is
     *     written in that case, because the guard runs before the first write
     */
    private SalesOrder moveOrder(SalesOrder so, SalesOrderState to, String reason) {
        var from = so.getStatus();
        DocumentStates.requireTransition(from, to);
        so.setStatus(to);
        var saved = salesOrderRepository.save(so);
        flushDocuments();
        statusTransitionRecorder.recordMove(StatusSubject.SALES_ORDER, so.getCompanyId(), so.getId(), from, to, reason);
        return saved;
    }

    /**
     * The line's half of {@link #moveOrder(SalesOrder, SalesOrderState, String)}. The tenant is passed
     * in because a line carries none: {@code sales_order_items} has no {@code company_id}, and the history row
     * names the tenant of its parent order.
     */
    private SalesOrderItem moveItem(SalesOrderItem line, UUID companyId, SalesOrderItemState to, String reason) {
        var from = line.getStatus();
        DocumentStates.requireTransition(from, to);
        line.setStatus(to);
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
     * Sends one batch of stock work to {@link StockLedgerEngine}, the only writer of the per-store
     * aggregate, the per-location balances and the ledger. Its one caller is the charge, so the batch is
     * the whole of a document's stock movement and it joins the charge's transaction and its order row
     * lock. The batch's ordering is the engine's contract (F-1), not this caller's: it locks every distinct
     * {@code (store, variant)} row in key order before it runs anything, and runs every reversal before every
     * deduction.
     */
    private void applyStockChanges(List<SaleChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        stockLedgerEngine.applySaleBatch(SALE_REFERENCE_TYPE, currentUserContext.getUsername(), changes);
    }

    /**
     * Reverses every line of the list through the engine. It has one caller, {@code deleteSalesOrder}, and only
     * for a document its charge booked: everything before {@code COMPLETED} holds no stock. A line already given
     * back — soft-deleted earlier, or cancelled individually — keeps a zero uncovered ledger remainder, so its
     * reversal is a no-op and the aggregate is never credited twice (W3-D6).
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

    private SaleChange deduct(UUID companyStoreId, UUID variantId, BigDecimal quantity, UUID itemId, long writeSeq) {
        return SaleChange.deduction(companyStoreId, variantId, quantity, itemId, writeSeq);
    }

    private SaleChange reverse(UUID companyStoreId, UUID variantId, UUID itemId) {
        return SaleChange.reversal(companyStoreId, variantId, itemId);
    }

    /**
     * The line's next write sequence: the locked bump of {@link
     * SalesOrderItemRepository#bumpWriteSeq(UUID)}, as a number this method refuses to invent.
     *
     * <p><b>One statement, taken before the movement it numbers</b> ({@code V73}). The bump has to happen here
     * rather than in the inventory engine because the counter is the document's: this service owns the
     * transaction that writes the line and books the stock, so a rolled-back charge gives the number back and a
     * committed one has exactly as many numbers as movements. Since ADR-0002 decision 7 it has one consumer — the
     * charge's batch, which runs after the lines are persisted and before the engine books anything — so a
     * refused charge never consumes a number it does not use: a gap is allowed, a movement with no number is
     * not.</p>
     *
     * <p><b>A missing row is a defect, not zero.</b> {@code UPDATE … RETURNING} answers nothing when the id names
     * no line, and the repository's primitive result turns that into its own empty-result failure rather than a
     * {@code 0}: booking the fact under {@code 0} would put it in the ledger under the identity of "this source
     * books once" and hide the defect behind a valid key.</p>
     */
    private long nextWriteSeq(UUID itemId) {
        return itemRepository.bumpWriteSeq(itemId);
    }

    // ─── The Breakdown (ADR-0002 M1, M2 and M8) ───────────────────────

    /** The scale every money column of this schema states, and the scale the parts are summed at. */
    private static final int MONEY_SCALE = 4;

    /**
     * Writes the document's four parts and its total from the lines it holds, under the order's row lock.
     *
     * <p><b>Why this is one method and the only writer of those five columns.</b> ADR-0002 invariant M2 states
     * that the parts are the sum of the lines and that the sum is recomputed in the same transaction as the
     * line write, under the order's row lock. Every path that creates, edits or removes a line calls this, and
     * the charge calls it again before it states an amount — so a document's money has one definition and one
     * moment, and `ck_order_total` (M1) judges a state this method just produced instead of a state it hopes
     * for.</p>
     *
     * <p><b>Why the lines come from a query and not from the list the caller already holds.</b> The line's
     * {@code subtotal} is derived by a trigger on the way in, so a line saved a statement ago carries a value
     * the persistence context never saw; summing the entities would add the {@code null} the trigger replaces
     * with a number. {@code sumMoneyParts} reads the rows the writer just produced, inside the same
     * transaction.</p>
     *
     * <p><b>Why the rounding is here and what it is bounded by.</b> Decision 1 rounds to the currency's
     * {@code minor_units} once, at the document boundary, which is this total and nothing below it: rounding a
     * line is how a total stops matching the paper. The adjustment is therefore always smaller than one minor
     * unit by construction, and `ck_order_rounding_bounded` (V80) still holds the line at {@code abs(...) <= 1}
     * for the case where some future writer states one by hand.</p>
     */
    private void recomputeBreakdown(UUID salesOrderId) {
        var so = salesOrderRepository
                .findByIdForUpdate(salesOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(salesOrderId));
        recomputeBreakdown(so);
    }

    /**
     * The same recomputation on a row the caller has already locked, which is the charge's path: it takes the
     * lock once, at the top, and re-reading the row here would only be a second statement for a lock it holds.
     */
    private void recomputeBreakdown(SalesOrder so) {
        var row = itemRepository.sumMoneyParts(so.getId()).stream().findFirst().orElse(null);
        var subtotal = moneyPart(row, 0);
        var discountTotal = moneyPart(row, 1);
        var taxTotal = moneyPart(row, 2);

        var exactTotal = subtotal.subtract(discountTotal).add(taxTotal);
        var total = exactTotal.setScale(minorUnitsOf(so.getId()), RoundingMode.HALF_UP);

        so.setSubtotal(subtotal);
        so.setDiscountTotal(discountTotal);
        so.setTaxTotal(taxTotal);
        so.setRoundingAdjustment(total.subtract(exactTotal));
        so.setTotalAmount(total);
        // No `save`: the row is loaded managed by the statement that took the lock, so the five setters above are
        // the write and Hibernate flushes it with the rest of the transaction — which is also what makes
        // `ck_order_total` judge the parts and the total in the same statement that writes them.
    }

    /**
     * One column of the aggregate row, as a number: the sum is {@code NULL} when the document holds no enabled
     * line, and an absent sum is zero money and not a missing value.
     */
    private BigDecimal moneyPart(Object[] row, int index) {
        if (row == null || row[index] == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        return ((BigDecimal) row[index]).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * How many decimals the document's currency is rounded to, or a refusal: a document whose currency the
     * catalog does not hold has no scale, and picking one would be the platform choosing a tenant's money
     * (ADR-0002 decision 2).
     */
    private int minorUnitsOf(UUID salesOrderId) {
        return salesOrderRepository
                .findCurrencyMinorUnits(salesOrderId)
                .orElseThrow(() -> new IllegalStateException("Sales order " + salesOrderId
                        + " states a currency the catalog does not hold, so its total cannot be rounded"));
    }

    // ─── Response Mappers ───────────────────────────────────────────────

    /**
     * The catalog row a state is labelled by: where {@code statusId} and {@code statusName} come from now that
     * no column stores them.
     *
     * <p><b>The pair is a projection of the code, and that is the whole of ADR-0004's step 9 for a reader.</b>
     * The row stores the enum constant; {@code statuses} keeps one row per constant, keyed by the family and
     * the {@code code} (decision 7), and this lookup turns the constant back into the id and the label the
     * screens have always read. A screen may read either half — {@code status} is the authority and
     * {@code statusId}/{@code statusName} are the presentation of it — and neither is stored on the document
     * any more, so a rename in the catalog changes the label and never the state.</p>
     *
     * <p><b>A missing row leaves the pair null and does not fail the read.</b> That is the shape this mapper
     * had before the drop ({@code statusName} was already {@code null} when the catalog row it joined was
     * gone), and it keeps a GET from turning into a {@code 500} over one deleted catalog row. The state is
     * still answered, so nothing is lost that the document owns; and {@code V66} step 4 plus {@code V71}'s
     * assertion block are what guarantee the row is there in the first place.</p>
     */
    private Status catalogRowOf(StatusSubject subject, SalesOrderState state) {
        if (state == null) {
            return null;
        }
        return statusRepository
                .findByTypeNameAndCode(subject.name(), state.name())
                .orElse(null);
    }

    /** The line's half of {@link #catalogRowOf(StatusSubject, SalesOrderState)}. */
    private Status catalogRowOf(StatusSubject subject, SalesOrderItemState state) {
        if (state == null) {
            return null;
        }
        return statusRepository
                .findByTypeNameAndCode(subject.name(), state.name())
                .orElse(null);
    }

    /**
     * The currency code the document states, which the entity cannot answer because it does not map the column.
     *
     * <p><b>Why a read and not a getter.</b> {@code sales_orders.currency} has one writer — {@code V80}'s trigger,
     * which derives it from the owning company — so {@link SalesOrder} deliberately maps no field for it and no
     * path in this service can set one. The code a response prints is therefore a read of the row, and the
     * narrowest one is the repository's own column query, not a second load of the document.</p>
     *
     * <p><b>A missing answer is a refusal and never a default.</b> The column is {@code NOT NULL} since {@code V80},
     * so an order this service has just answered states a currency; an empty answer means the read is broken — or
     * that the row is not visible to this caller — and printing {@code null} would hand the client a total with no
     * money behind it. That is the same failure class D-c removed from the create path, one layer down.</p>
     */
    private String currencyOf(SalesOrder so) {
        return salesOrderRepository
                .findCurrencyCode(so.getId())
                .orElseThrow(() -> new IllegalStateException("Sales order " + so.getId()
                        + " states a currency the read could not see, so the response cannot print its money"));
    }

    /**
     * The document's payments, oldest first (ADR-0002 decision 5).
     *
     * <p><b>One statement per document, never one per line.</b> The payments are read once, by the document, so a
     * document of fifty lines costs the same as one of a single line. The order is the row's {@code paid_at} and
     * not its id: two payments of one sale are ordered by when the money moved, which is the order a drawer reads
     * them in.</p>
     */
    private List<SalePaymentResponse> paymentsOf(SalesOrder so) {
        return salePaymentRepository.findBySalesOrderIdOrderByPaidAtAsc(so.getId()).stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    private SalesOrderResponse toResponse(SalesOrder so) {
        var items = itemRepository.findBySalesOrderIdAndEnabledTrue(so.getId()).stream()
                .map(this::toItemResponse)
                .toList();

        // Resolved once per response: the order's own pair, from the order's own code.
        var status = catalogRowOf(StatusSubject.SALES_ORDER, so.getStatus());

        return new SalesOrderResponse(
                so.getId(),
                so.getOrderNumber(),
                so.getCustomerId(),
                so.getCompanyStoreId(),
                so.getShiftId(),
                so.getUserId(),
                so.getOrderDate(),
                status == null ? null : status.getId(),
                status == null ? null : status.getStatusName(),
                so.getStatus() == null ? null : so.getStatus().name(),
                currencyOf(so),
                so.getSubtotal(),
                so.getDiscountTotal(),
                so.getTaxTotal(),
                so.getRoundingAdjustment(),
                so.getTotalAmount(),
                so.getEnabled(),
                so.getCreatedAt(),
                so.getUpdatedAt(),
                items,
                paymentsOf(so));
    }

    /**
     * One payment row as the API answers it: every column of {@code sale_payment} and nothing derived.
     *
     * <p>The direction is answered as its code, like the document's and the line's states, because it is the
     * vocabulary a report groups by and the label would be a second one. The tender pair is answered as it is
     * stored, {@code null} together on every method that is not cash and on a cash charge that did not state a
     * tender: an absent pair is a fact about the payment, and a zero here would report a customer who handed over
     * nothing.</p>
     */
    private SalePaymentResponse toPaymentResponse(SalePayment payment) {
        return new SalePaymentResponse(
                payment.getId(),
                payment.getMethodId(),
                payment.getDirection() == null ? null : payment.getDirection().name(),
                payment.getAmount(),
                payment.getTenderedAmount(),
                payment.getChangeAmount(),
                payment.getReference(),
                payment.getPaidAt(),
                payment.getActor());
    }

    /**
     * The line's money the schema writes — {@code subtotal} (`V81`), {@code tax_amount} (`V90`) and
     * {@code final_price} (`V90`) — and the reason a response cannot simply read the instance a write left behind.
     *
     * <p><b>What goes wrong without it.</b> Those three columns are {@code insertable = false, updatable =
     * false}, so Hibernate omits them from the insert and never populates the field; the first-level cache then
     * answers every query inside the transaction with that same instance. A response built from it would report
     * {@code null} for every derived column, which is worse than a stale number: it is an absence a client
     * cannot tell apart from a line that has no money. The line's {@code tax_code} and {@code tax_rate} are
     * written by the application since T4 and need no refresh; they are on the same row, so the refresh that
     * fetches the three derived columns brings them back too — and reading them from the row is what makes the
     * response state what was stored rather than what was sent.</p>
     *
     * <p><b>Why the check is the column and not a flag.</b> All three are {@code NOT NULL} in the schema, so a
     * non-null {@code final_price} is proof the instance was read from a row, and a plain GET — the common
     * path — needs no second statement. A null is proof it was built by this application, and that is the only
     * case that pays for the round trip.</p>
     */
    private void refreshDerivedLineMoney(SalesOrderItem item) {
        if (item == null || item.getFinalPrice() != null) {
            return;
        }
        itemRepository.flush();
        entityManager.refresh(item);
    }

    private SalesOrderItemResponse toItemResponse(SalesOrderItem item) {
        refreshDerivedLineMoney(item);

        // The line's pair, from the line's code and its own family: `PENDING` exists in three families.
        var status = catalogRowOf(StatusSubject.SALES_ORDER_ITEM, item.getStatus());

        return new SalesOrderItemResponse(
                item.getId(),
                item.getSalesOrderId(),
                item.getProductVariantId(),
                item.getQuantity(),
                item.getListPrice(),
                item.getDiscountApplied(),
                item.getSubtotal(),
                item.getTaxCode(),
                item.getTaxRate(),
                item.getTaxAmount(),
                item.getFinalPrice(),
                item.getProductNameSnapshot(),
                item.getVariantNameSnapshot(),
                item.getPromotionId(),
                status == null ? null : status.getId(),
                status == null ? null : status.getStatusName(),
                item.getStatus() == null ? null : item.getStatus().name(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
