package com.lifecontrol.api.purchaseorder.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.state.DocumentStateCatalog;
import com.lifecontrol.api.common.state.DocumentStates;
import com.lifecontrol.api.common.state.InvalidStatusTransitionException;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.common.state.StatusTransitionRecorder;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailResponse;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderFilter;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderResponse;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderCreatedEvent;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderDetailStatusChangedEvent;
import com.lifecontrol.api.purchaseorder.event.PurchaseOrderStatusChangedEvent;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderDetailNotFoundException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderNotFoundException;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /**
     * The state every purchase order is born in (ADR-0004 decision 1). The constant is the whole input: the
     * catalog row that labels it is a projection of the code now that {@code V71} dropped the pointer, so
     * creating an order no longer reads the catalog, and {@link DocumentStates} decides every move after it.
     */
    private static final PurchaseOrderState INITIAL_ORDER_STATE = PurchaseOrderState.DRAFT;

    /** The state every line is born in (ADR-0004 decision 3), same reasoning as {@link #INITIAL_ORDER_STATE}. */
    private static final PurchaseOrderDetailState INITIAL_DETAIL_STATE = PurchaseOrderDetailState.PENDING;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderDetailRepository detailRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final StatusRepository statusRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserContext currentUserContext;
    private final DocumentSequenceAllocator documentSequenceAllocator;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final DocumentStateCatalog documentStateCatalog;

    public PurchaseOrderService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderDetailRepository detailRepository,
            SupplierRepository supplierRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            CompanyRepository companyRepository,
            ProductRepository productRepository,
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            PaymentMethodRepository paymentMethodRepository,
            StatusRepository statusRepository,
            ApplicationEventPublisher eventPublisher,
            CurrentUserContext currentUserContext,
            DocumentSequenceAllocator documentSequenceAllocator,
            StatusTransitionRecorder statusTransitionRecorder,
            DocumentStateCatalog documentStateCatalog) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.detailRepository = detailRepository;
        this.supplierRepository = supplierRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.companyRepository = companyRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.statusRepository = statusRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserContext = currentUserContext;
        this.documentSequenceAllocator = documentSequenceAllocator;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.documentStateCatalog = documentStateCatalog;
    }

    // ─── Purchase Order CRUD ────────────────────────────────────────────

    /**
     * The paginated list of purchase orders. Both the {@code search} term and the location
     * {@code filter} are optional and combine with {@code AND}, so an empty filter returns the same
     * page the module returned before the filters existed (record S4).
     *
     * <p>The blank/none search branch is gone: the trimmed term is handed to the single
     * {@code findByFilters} query, whose {@code cast(:search as string) IS NULL} predicate is what
     * makes a missing term match everything. Trimming stays here so the query never receives a
     * whitespace-only pattern.</p>
     */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> getAllPurchaseOrders(
            Pageable pageable, String search, PurchaseOrderFilter filter) {
        var term = StringUtils.hasText(search) ? search.trim() : null;

        var orders = purchaseOrderRepository.findByFilters(
                term,
                filter.companyId(),
                filter.companyCountryId(),
                filter.regionId(),
                filter.zoneId(),
                filter.companyStoreId(),
                pageable);

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

        // The store comes first because it is the only thing that answers *which company* this order
        // belongs to, and the supplier may only be one of that company's. Reversing the two would mean
        // asking for a supplier before the tenant is known, which is how a purchase order of company A
        // could name a supplier only company B created.
        var store = validateCompanyStoreExists(request.companyStoreId());
        var companyId = companyStoreOwnerResolver.companyIdOf(store);
        var company = resolveCompany(companyId);
        var supplier = validateSupplierExists(companyId, request.supplierId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        // The request names a catalog row and the machine only reads its code (ADR-0004 decision 7); the row is
        // read for nothing else, because `V71` dropped the `status_id` column the pointer used to be written to.
        // A request that names nothing is born in `DRAFT`, and the label of that code is projected on the way
        // out, not stored on the way in.
        var orderState = request.statusId() != null ? orderStateOf(request.statusId()) : INITIAL_ORDER_STATE;

        // ADR-0003 decision 4: ONE date is read and the period comes from it, so the allocation and the
        // render cannot disagree about which period the document belongs to. `purchase_orders` carries no
        // `order_date` column (V1:313-325 has only created_at/updated_at), so this value is the document's
        // own date for numbering purposes and nothing else — the ADR names a column the table does not have.
        var documentDate = LocalDate.now();
        var period = DocumentNumberRenderer.periodOf(documentDate);

        // The allocation is the last write before the row is created, not the last write before commit:
        // `purchase_orders` needs its number at INSERT time, so "allocate as the last write before commit"
        // is unattainable on this path. The sequence row's lock is therefore held while the lines are
        // resolved and written, which is the cost the ADR records rather than one this code can avoid.
        var seqValue = documentSequenceAllocator.allocate(companyId, DocumentType.PURCHASE_ORDER, period);
        var docNumber = DocumentNumberRenderer.renderPeriodNumber(DocumentType.PURCHASE_ORDER, period, seqValue);

        var po = PurchaseOrder.builder()
                // `V64` dropped the global key on `order_number`: it carries the same string as
                // `doc_number` for one more release, and two tenants may legally print the same number.
                .orderNumber(docNumber)
                .docType(DocumentType.PURCHASE_ORDER.code())
                .seqPeriod(period)
                .seqValue(seqValue)
                .docNumber(docNumber)
                .supplier(supplier)
                .companyStore(store)
                // The owner comes from the store, never from the supplier: the supplier is the thing the
                // composite key constrains, so deriving the company from it would make the key a
                // tautology and would let an order of company A carry company B's owner.
                .company(company)
                .paymentMethod(paymentMethod)
                .status(orderState)
                .comments(request.comments())
                .enabled(true)
                .build();

        // Build the lines first: a line cannot be written before the order it belongs to has an id.
        // The collection maps the inverse side ({@code mappedBy}), so adding to it never writes a
        // row, and the 201 would then report lines no read route can return.
        var newDetails = new ArrayList<PurchaseOrderDetail>();
        if (request.details() != null && !request.details().isEmpty()) {
            // A line may omit statusId: the field is optional on the DTO, and both write routes answer such a
            // line with the PENDING default (issue #605). The default is the constant now — there is no catalog
            // row to resolve for it, because the row is the label of this code and not an input to it.
            for (var detailReq : request.details()) {
                var product = validateProductExists(detailReq.productId());
                var productVariant = resolveProductVariant(detailReq.productVariantId(), product, store);
                var detailState =
                        detailReq.statusId() != null ? detailStateOf(detailReq.statusId()) : INITIAL_DETAIL_STATE;
                var total = lineTotal(detailReq.unitPrice(), detailReq.quantity());

                var detail = PurchaseOrderDetail.builder()
                        .purchaseOrder(po)
                        .product(product)
                        .productVariant(productVariant)
                        .quantity(detailReq.quantity())
                        .unitPrice(detailReq.unitPrice())
                        .total(total)
                        .receivedQuantity(BigDecimal.ZERO)
                        .comments(detailReq.comments())
                        .status(detailState)
                        .enabled(true)
                        .build();
                newDetails.add(detail);
            }
        }

        var saved = purchaseOrderRepository.save(po);

        // ADR-0004 decision 5 side A: a document born without its creation row cannot commit, so the two writes
        // share this transaction and the deferred trigger judges them together. The flush comes first because
        // decision 6's check is immediate and reads the document back.
        flushDocuments();
        statusTransitionRecorder.recordCreation(StatusSubject.PURCHASE_ORDER, companyId, saved.getId(), orderState);

        // Persist the lines with the same explicit save the update route already uses, so both
        // write routes agree on what a successful response means (issue #593).
        if (!newDetails.isEmpty()) {
            detailRepository.saveAll(newDetails);
            flushDocuments();
            for (var detail : newDetails) {
                statusTransitionRecorder.recordCreation(
                        StatusSubject.PURCHASE_ORDER_DETAIL, companyId, detail.getId(), detail.getStatus());
            }
            saved.getDetails().addAll(newDetails);
        }
        logger.info("Purchase order created: id={}, orderNumber={}", saved.getId(), saved.getOrderNumber());

        eventPublisher.publishEvent(new PurchaseOrderCreatedEvent(
                this, saved.getId(), saved.getOrderNumber(), supplier.getId(), store.getId()));

        return toResponse(saved);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrder(UUID id, PurchaseOrderRequest request) {
        logger.info("Updating purchase order: id={}", id);

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        // Same order as the create route and for the same reason: the store answers which company's
        // suppliers this order may name, so it is resolved before the supplier is.
        var store = validateCompanyStoreExists(request.companyStoreId());
        var companyId = companyStoreOwnerResolver.companyIdOf(store);
        var company = resolveCompany(companyId);
        var supplier = validateSupplierExists(companyId, request.supplierId());
        var paymentMethod = validatePaymentMethodExists(request.paymentMethodId());
        // The request may name a state, and naming a different one is a move like the status route's: it is
        // decided by the same guard and recorded by the same writer. The guard runs BEFORE the lines are
        // replaced below, so a refused move leaves the stored lines exactly as they were (ADR-0004 T2).
        var targetState = request.statusId() != null ? orderStateOf(request.statusId()) : po.getStatus();
        if (targetState != po.getStatus()) {
            DocumentStates.requireTransition(po.getStatus(), targetState);
        }

        po.setSupplier(supplier);
        po.setCompanyStore(store);
        // Moving an order to another company's store moves its owner with it, and the supplier was just
        // checked against that company — so the stored copy cannot disagree with either.
        po.setCompany(company);
        po.setPaymentMethod(paymentMethod);
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
                var replacementDetails = new ArrayList<PurchaseOrderDetail>();
                for (var detailReq : request.details()) {
                    var product = validateProductExists(detailReq.productId());
                    var productVariant = resolveProductVariant(detailReq.productVariantId(), product, store);
                    var detailState =
                            detailReq.statusId() != null ? detailStateOf(detailReq.statusId()) : INITIAL_DETAIL_STATE;
                    var total = lineTotal(detailReq.unitPrice(), detailReq.quantity());

                    var detail = PurchaseOrderDetail.builder()
                            .purchaseOrder(po)
                            .product(product)
                            .productVariant(productVariant)
                            .quantity(detailReq.quantity())
                            .unitPrice(detailReq.unitPrice())
                            .total(total)
                            .receivedQuantity(BigDecimal.ZERO)
                            .comments(detailReq.comments())
                            .status(detailState)
                            .enabled(true)
                            .build();
                    replacementDetails.add(detailRepository.save(detail));
                }
                // Each replacement line is a new document, so each one owes its creation row; the lines just
                // soft-deleted above moved no state and owe nothing (side A ignores a write that leaves
                // `status` alone).
                flushDocuments();
                for (var detail : replacementDetails) {
                    statusTransitionRecorder.recordCreation(
                            StatusSubject.PURCHASE_ORDER_DETAIL, companyId, detail.getId(), detail.getStatus());
                }
            }
        }

        // One write, one column: a named state that is the state the order already holds is not a move and
        // appends no row.
        PurchaseOrder updated;
        if (targetState != po.getStatus()) {
            updated = moveOrder(po, targetState, "update");
        } else {
            po.setStatus(targetState);
            updated = purchaseOrderRepository.save(po);
        }
        return toResponse(updated);
    }

    @Transactional
    public PurchaseOrderResponse updatePurchaseOrderStatus(UUID id, UpdatePurchaseOrderStatusRequest request) {
        logger.info("Updating purchase order status: id={}", id);

        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        // The request names a catalog row; the machine only reads its code (ADR-0004 decision 7). This is also
        // where a status of another family, an uncoded row or a code the enum lacks is refused.
        var newState = orderStateOf(request.statusId());
        // The guard runs BEFORE the first write, so a refused move is a 409 that leaves the row, the history
        // and the response exactly as they were (ADR-0004 T2).
        DocumentStates.requireTransition(po.getStatus(), newState);

        var previousState = po.getStatus();
        var updated = moveOrder(po, newState, "status update");

        eventPublisher.publishEvent(new PurchaseOrderStatusChangedEvent(
                this, updated.getId(), updated.getOrderNumber(), previousState.name(), newState.name()));

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
        var productVariant = resolveProductVariant(request.productVariantId(), product, po.getCompanyStore());
        var detailState = detailStateOf(request.statusId());
        var total = lineTotal(request.unitPrice(), request.quantity());

        var detail = PurchaseOrderDetail.builder()
                .purchaseOrder(po)
                .product(product)
                .productVariant(productVariant)
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .total(total)
                .receivedQuantity(BigDecimal.ZERO)
                .comments(request.comments())
                .status(detailState)
                .enabled(true)
                .build();

        var saved = detailRepository.save(detail);
        // The line is a document of its own, so it owes its own creation row; side A refuses the commit without
        // it, and decision 6's immediate check is why the write is flushed first.
        flushDocuments();
        statusTransitionRecorder.recordCreation(
                StatusSubject.PURCHASE_ORDER_DETAIL, companyIdOf(saved.getPurchaseOrder()), saved.getId(), detailState);
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
                request.productVariantId(), product, detail.getPurchaseOrder().getCompanyStore());
        var detailState = detailStateOf(request.statusId());
        // The line's own guard, before any field is written: a forbidden move leaves the row untouched.
        if (detailState != detail.getStatus()) {
            DocumentStates.requireTransition(detail.getStatus(), detailState);
        }
        var total = lineTotal(request.unitPrice(), request.quantity());

        detail.setProduct(product);
        detail.setProductVariant(productVariant);
        detail.setQuantity(request.quantity());
        detail.setUnitPrice(request.unitPrice());
        detail.setTotal(total);
        detail.setComments(request.comments());

        PurchaseOrderDetail updated;
        if (detailState != detail.getStatus()) {
            updated = moveDetail(detail, detailState, "line update");
        } else {
            detail.setStatus(detailState);
            updated = detailRepository.save(detail);
        }
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
        // The header's state and not its label: the two states that admit a receipt are constants, so renaming
        // either catalog row cannot open or close this door (ADR-0004 decisions 1 and 7).
        var currentState = purchaseOrder.getStatus();
        if (currentState != PurchaseOrderState.ACCEPTED && currentState != PurchaseOrderState.IN_TRANSIT) {
            throw new InvalidStatusTransitionException(
                    currentState == null ? "missing" : currentState.name(), "reception");
        }
    }

    /**
     * Detail-level companion of {@link #requireReceivable(PurchaseOrder)}: a
     * purchase-order line whose current status can no longer reach the receiving
     * state — a terminal {@code Received}, {@code Cancelled} or {@code Rejected}
     * line — cannot register received quantities, whatever the ordered amount says.
     * Any other line status is left to {@code registerReceivedQuantity} to derive
     * {@code Partial Received} / {@code Received}.
     *
     * <p>It asks {@link DocumentStates#isReachable} over
     * {@link PurchaseOrderDetailState#successors()}, so the enum is the single source of truth and the two
     * private string maps are gone. The goods-receipt use case calls it during its pre-write validation, so the
     * rejection happens before the receipt row and its inventory effect are written instead of after.</p>
     *
     * <p>The check asks for {@code PARTIAL_RECEIVED} only, deliberately: reachability is <em>reflexive</em> —
     * the walk starts at the current state — so asking for {@code RECEIVED} is trivially true for a line
     * already sitting in the terminal {@code RECEIVED} state. An "or {@code RECEIVED}" disjunct therefore
     * admitted exactly that terminal line, the receipt row and the inventory effect were written, and only
     * {@code registerReceivedQuantity} rejected the line afterwards with a {@code 409}.
     * {@code PARTIAL_RECEIVED} is the real prerequisite: {@code PENDING}, {@code IN_PROCESS} and
     * {@code IN_TRANSIT} reach it through the chain, {@code PARTIAL_RECEIVED} matches by reflexivity (a
     * partially received line keeps receiving), and every admitted state reaches <strong>both</strong> of the
     * mutator's derived targets ({@code PARTIAL_RECEIVED} and {@code RECEIVED}) — so the guarded set equals the
     * mutator's accepted set without the quantity ever being needed as an input, which is why the quantity is
     * not a parameter.</p>
     *
     * @param detail the already-loaded purchase order detail to check
     * @throws InvalidStatusTransitionException when the detail cannot reach the
     *     receiving state
     */
    public void requireDetailReceivable(PurchaseOrderDetail detail) {
        var currentState = detail.getStatus();
        // PARTIAL_RECEIVED is the receiving prerequisite. Reachability is reflexive, so an extra "or RECEIVED"
        // disjunct would wrongly admit a terminal RECEIVED line; every state admitted here reaches both of the
        // mutator's derived targets, so the mutator can never reject an admitted line.
        if (!DocumentStates.isReachable(currentState, PurchaseOrderDetailState.PARTIAL_RECEIVED)) {
            throw new InvalidStatusTransitionException(
                    currentState == null ? "missing" : currentState.name(), "reception");
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
     * <p>The derived line state must be reachable from the line's current one — the enum's closed set walked as
     * a graph, which is what the old {@code DETAIL_TRANSITIONS} table was — and terminal states stay terminal.
     * When every enabled line of an {@code IN_TRANSIT} order is {@code RECEIVED}, the header is promoted through
     * {@link DocumentStates#requireTransition} like any other move. Both rows are appended through
     * {@link StatusTransitionRecorder} in this transaction, and the existing
     * {@link PurchaseOrderDetailStatusChangedEvent} is published with the real previous/new state and both
     * quantities.</p>
     */
    @Transactional
    public PurchaseOrderDetailResponse registerReceivedQuantity(
            UUID purchaseOrderId, UUID detailId, BigDecimal receivedQuantity) {
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

        if (receivedQuantity == null || receivedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("receivedQuantity must be greater than 0");
        }
        if (receivedQuantity.compareTo(detail.getQuantity()) > 0) {
            throw new IllegalArgumentException("receivedQuantity must not exceed the ordered quantity");
        }

        // The derived state, and nothing else: the catalog label that used to be looked up beside it was the
        // `status_id` pointer, which `V71` dropped. The state is derived from the two quantities and is the
        // only thing this method decides with.
        var targetState = receivedQuantity.compareTo(detail.getQuantity()) < 0
                ? PurchaseOrderDetailState.PARTIAL_RECEIVED
                : PurchaseOrderDetailState.RECEIVED;

        var previousState = detail.getStatus();
        // The derived move is decided before the quantity is written, so a refused line keeps what it had. It is
        // asked over the graph and not over one hop: a line whose goods arrived may never have been marked
        // IN_TRANSIT, and that arrival is one fact, not three.
        DocumentStates.requireReachable(previousState, targetState);

        detail.setReceivedQuantity(receivedQuantity);
        detail.setStatus(targetState);
        var updated = detailRepository.save(detail);
        // A receipt that lands in the state the line already holds moved nothing — a second partial quantity on a
        // partially received line is the real case — and history is a change, not a write.
        if (previousState != targetState) {
            flushDocuments();
            statusTransitionRecorder.recordReachableMove(
                    StatusSubject.PURCHASE_ORDER_DETAIL,
                    companyIdOf(po),
                    detail.getId(),
                    previousState,
                    targetState,
                    "receipt");
        }

        // Promote the header only once every enabled line is fully received.
        if (po.getStatus() == PurchaseOrderState.IN_TRANSIT && allEnabledDetailsReceived(po)) {
            var receivedHeaderState = PurchaseOrderState.RECEIVED;
            // The header's own move goes through the guard like every other path, and it is a declared edge
            // (IN_TRANSIT → RECEIVED), so it is recorded as a move and not as a derived arrival.
            DocumentStates.requireTransition(po.getStatus(), receivedHeaderState);
            var previousHeaderState = po.getStatus();
            moveOrder(po, receivedHeaderState, "all lines received");
            eventPublisher.publishEvent(new PurchaseOrderStatusChangedEvent(
                    this, po.getId(), po.getOrderNumber(), previousHeaderState.name(), receivedHeaderState.name()));
        }

        eventPublisher.publishEvent(new PurchaseOrderDetailStatusChangedEvent(
                this,
                po.getId(),
                po.getOrderNumber(),
                updated.getId(),
                updated.getProduct().getId(),
                previousState == null ? "missing" : previousState.name(),
                targetState.name(),
                updated.getReceivedQuantity(),
                updated.getQuantity()));

        return toDetailResponse(updated);
    }

    // ─── FK Validation Helpers ──────────────────────────────────────────

    private PurchaseOrder loadAndValidateDraftPO(UUID id) {
        var po = purchaseOrderRepository.findById(id).orElseThrow(() -> new PurchaseOrderNotFoundException(id));

        // Only a DRAFT order accepts a line-level write: its lines may still be created, edited and removed,
        // and any other state has left that surface behind (ADR-0004 decision 1; a null state cannot move).
        if (po.getStatus() != PurchaseOrderState.DRAFT) {
            throw new InvalidStatusTransitionException(
                    po.getStatus() == null ? "missing" : po.getStatus().name(), "detail mutation");
        }
        return po;
    }

    /**
     * Resolves the company that owns the order's store.
     *
     * <p>The id comes from {@link CompanyStoreOwnerResolver}, the one walk from a store to its tenant,
     * and it is the same id the supplier lookup is scoped by — so the company stored on the order and
     * the company the supplier was checked against are one value, not two derivations that could drift.
     * There is no access check here: {@link #validateCompanyStoreExists} already authorized the caller
     * for that store, and therefore for that company.</p>
     *
     * @throws CompanyNotFoundException when the company does not exist, which the store's own foreign
     *     keys make unreachable
     */
    private Company resolveCompany(UUID companyId) {
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Resolves the enabled supplier <b>of the company that owns the order's store</b>, or answers 404.
     *
     * <p>The company is a parameter instead of something derived here because each caller already holds
     * the store it validated ({@link #validateCompanyStoreExists}), and the store is the only thing that
     * says which tenant's suppliers this order may name. The lookup itself is company-scoped, so a
     * supplier of another company is {@link SupplierNotFoundException} — the same 404 an unknown id gets,
     * which is what keeps a cross-tenant reference unreachable rather than merely refused.</p>
     */
    private Supplier validateSupplierExists(UUID companyId, UUID id) {
        return supplierRepository
                .findByIdAndCompanyId(id, companyId)
                .filter(Supplier::getEnabled)
                .orElseThrow(() -> new SupplierNotFoundException(id));
    }

    /**
     * Resolves the enabled store a purchase order belongs to and authorizes the caller for it,
     * keeping the existing {@code enabled} filter and {@link CompanyStoreNotFoundException} for an
     * unknown or disabled store.
     *
     * <p>The request carries only the store id, so the company &rarr; country &rarr; region &rarr;
     * zone &rarr; store chain the guard needs is derived from the store itself. This mirrors the
     * check every other store-scoped write applies ({@code StoreLocationService},
     * {@code StoreInventorySettingsService}, {@code GoodsReceiptService},
     * {@code ProductVariantService}); {@code lc-admin} is exempt inside
     * {@link CurrentUserContext#verifyCompanyStoreAccess}. Without it any principal holding
     * {@code lc-sales} could create or move a purchase order onto a store of another company.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist or is disabled
     * @throws org.springframework.security.access.AccessDeniedException when the caller holds no
     *     grant for the store's scope (403)
     */
    private CompanyStore validateCompanyStoreExists(UUID id) {
        var store = companyStoreRepository
                .findById(id)
                .filter(CompanyStore::getEnabled)
                .orElseThrow(() -> new CompanyStoreNotFoundException(id));
        verifyStoreAccess(store);
        return store;
    }

    /**
     * Authorizes the caller for the store a purchase order references, deriving the full
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
     * Resolves the variant reference against the line's product, the purchase order's store and the
     * company that owns that store: the {@code (variant, product)} pair must exist on the definition,
     * the definition must belong to the store's company, and the {@code (variant, store)} pair must
     * exist on the per-store row. A variant that is not reachable through all three is a 404.
     *
     * <p>The company predicate is not decoration. The product is resolved globally and the store's
     * stock row is a plain pair, so without it a line could name a product and variant of one tenant
     * against a store of another — both halves exist, both are enabled, and the order is written. The
     * company comes from the store, never from the product: `V40`'s composite foreign keys already
     * ensure the variant agrees with its own product, and agreeing with <b>this store</b> is the only
     * thing left to check.</p>
     *
     * <p>The HTTP contract requires the variant: {@link PurchaseOrderDetailRequest} rejects a missing
     * {@code productVariantId} with 400 before this method runs, and since V14 the database column is
     * {@code NOT NULL} too. The legacy {@code null} tolerance was dropped with the discarded data
     * (decision D5): a {@code null} id now fails the {@code @NotNull} gate instead of silently
     * leaving the relation unset.</p>
     */
    private ProductVariant resolveProductVariant(UUID productVariantId, Product product, CompanyStore store) {
        var variant = productVariantRepository
                .findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                        productVariantId, product.getId(), companyStoreOwnerResolver.companyIdOf(store))
                .orElseThrow(() -> new ProductVariantNotFoundException(productVariantId));

        if (!productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                productVariantId, store.getId())) {
            throw new ProductVariantNotFoundException(productVariantId);
        }
        return variant;
    }

    private boolean allEnabledDetailsReceived(PurchaseOrder po) {
        var enabledLines = 0;
        for (var line : po.getDetails()) {
            if (!Boolean.TRUE.equals(line.getEnabled())) {
                continue;
            }
            enabledLines++;
            var fullyReceived = line.getStatus() == PurchaseOrderDetailState.RECEIVED
                    && line.getReceivedQuantity() != null
                    && line.getReceivedQuantity().compareTo(line.getQuantity()) >= 0;
            if (!fullyReceived) {
                return false;
            }
        }
        return enabledLines > 0;
    }

    // ─── The typed state machine ────────────────────────────────────────
    //
    // Every path that moves an order or a line goes through the helpers below and through nothing else. The
    // decision itself is `DocumentStates` over `PurchaseOrderState`/`PurchaseOrderDetailState` (ADR-0004
    // decisions 2 and 3), which replaced the two `Map<String, Set<String>>` tables, the private walk that read
    // them and every `getStatusName()` comparison that used to sit next to them.

    /**
     * Resolves the state a request's {@code status_id} names, through the catalog's {@code code} (ADR-0004
     * decision 7). This is where a status of another family, a row with no code and a code the enum does not
     * declare are refused, each with the message {@code DocumentStateCatalog} owns.
     */
    private PurchaseOrderState orderStateOf(UUID statusId) {
        return documentStateCatalog.requireState(StatusSubject.PURCHASE_ORDER, statusId, PurchaseOrderState.class);
    }

    /** The line's half of {@link #orderStateOf(UUID)}, over the detail machine — which is not the item set. */
    private PurchaseOrderDetailState detailStateOf(UUID statusId) {
        return documentStateCatalog.requireState(
                StatusSubject.PURCHASE_ORDER_DETAIL, statusId, PurchaseOrderDetailState.class);
    }

    /**
     * The catalog row a state is labelled by: where {@code statusId} and {@code statusName} come from now that
     * no column stores them.
     *
     * <p><b>The pair is a projection of the code.</b> The row stores the enum constant, {@code statuses} keeps
     * one row per constant keyed by the family and the {@code code} (ADR-0004 decision 7), and this lookup
     * turns the constant back into the id and the label the screens have always read. A screen may read either
     * half — {@code status} is the authority and the pair is its presentation — and neither is stored on the
     * document any more, so a rename in the catalog changes a label and never a state.</p>
     *
     * <p><b>A missing row leaves the pair null and does not fail the read</b>, which is the shape this mapper
     * already had ({@code statusName} was {@code null} whenever the catalog row it joined was gone) and keeps
     * one deleted catalog row from turning a GET into a {@code 500}. The state is still answered, and
     * {@code V66} step 4 plus {@code V71}'s assertion block are what guarantee the row exists.</p>
     */
    private Status catalogRowOf(StatusSubject subject, PurchaseOrderState state) {
        if (state == null) {
            return null;
        }
        return statusRepository
                .findByTypeNameAndCode(subject.name(), state.name())
                .orElse(null);
    }

    /** The line's half of {@link #catalogRowOf(StatusSubject, PurchaseOrderState)}. */
    private Status catalogRowOf(StatusSubject subject, PurchaseOrderDetailState state) {
        if (state == null) {
            return null;
        }
        return statusRepository
                .findByTypeNameAndCode(subject.name(), state.name())
                .orElse(null);
    }

    /**
     * Moves an order: the guard decides, the state is written, the row is saved and the move is appended to the
     * history — all in the caller's transaction, which is what makes the deferred checks of ADR-0004 decision 5
     * judge the state and its row together.
     *
     * <p><b>One assignment, not two.</b> Until {@code V71} this method also wrote the {@code status_id} pointer
     * through {@code writeState}, because the row carried the state and the catalog row it was labelled by. The
     * column is gone, so the seam that kept the pair coherent is gone with it and what is left is
     * {@code setStatus}.</p>
     *
     * @throws InvalidStatusTransitionException (409) when the enum does not declare the move; nothing is written
     *     in that case, because the guard runs before the first write
     */
    private PurchaseOrder moveOrder(PurchaseOrder po, PurchaseOrderState to, String reason) {
        var from = po.getStatus();
        DocumentStates.requireTransition(from, to);
        po.setStatus(to);
        var saved = purchaseOrderRepository.save(po);
        flushDocuments();
        statusTransitionRecorder.recordMove(
                StatusSubject.PURCHASE_ORDER, companyIdOf(po), po.getId(), from, to, reason);
        return saved;
    }

    /**
     * The line's half of {@link #moveOrder(PurchaseOrder, PurchaseOrderState, String)}. The tenant is
     * derived from the parent order because a line carries none: {@code purchase_order_details} has no
     * {@code company_id}, and the history row names the tenant of the order it belongs to.
     */
    private PurchaseOrderDetail moveDetail(PurchaseOrderDetail detail, PurchaseOrderDetailState to, String reason) {
        var from = detail.getStatus();
        DocumentStates.requireTransition(from, to);
        detail.setStatus(to);
        var saved = detailRepository.save(detail);
        flushDocuments();
        statusTransitionRecorder.recordMove(
                StatusSubject.PURCHASE_ORDER_DETAIL,
                companyIdOf(detail.getPurchaseOrder()),
                detail.getId(),
                from,
                to,
                reason);
        return saved;
    }

    /**
     * The tenant of a document, derived from its store, which is the only thing that answers it ({@code V43};
     * {@code CompanyStoreOwnerResolver} is the one walk). Passed explicitly where the caller already holds the
     * company, so the walk runs once per write instead of twice.
     */
    private UUID companyIdOf(PurchaseOrder po) {
        return companyStoreOwnerResolver.companyIdOf(po.getCompanyStore());
    }

    /**
     * Makes the document writes visible to the trigger that is about to judge them.
     *
     * <p><b>Why a flush, and not just an order of writes.</b> ADR-0004 decision 5's checks are <em>deferred</em>
     * to commit, which is what lets the two writes of a transaction happen in either order. Decision 6's check
     * is not: it is a {@code BEFORE INSERT} trigger on {@code status_transition}, so it is immediate — it
     * resolves the document the row names and refuses a row whose document it cannot see under that
     * {@code company_id}. Hibernate defers its INSERTs and UPDATEs to the flush, so without this call the
     * resolver reads a document the database has not received yet, and the history row is refused with a message
     * about the wrong tenant that actually means "not written yet". It flushes the whole persistence context on
     * purpose: a line's tenant is its parent order's, so a line's row needs the order's write visible as well.</p>
     */
    private void flushDocuments() {
        purchaseOrderRepository.flush();
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

        // Resolved once per response: the order's own pair, from the order's own code.
        var orderStatus = catalogRowOf(StatusSubject.PURCHASE_ORDER, po.getStatus());

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
                orderStatus == null ? null : orderStatus.getId(),
                orderStatus == null ? null : orderStatus.getStatusName(),
                po.getStatus() == null ? null : po.getStatus().name(),
                po.getComments(),
                po.getEnabled(),
                po.getCreatedAt(),
                po.getUpdatedAt(),
                detailResponses);
    }

    /**
     * {@code unitPrice * quantity} at the scale of the {@code total} column: two decimals, rounded
     * {@code HALF_UP}. Money is stored and returned at that scale, so the write response and the row a
     * later read returns carry the same number; returning the raw product instead let the column round
     * it, and the POST response then disagreed with the GET.
     */
    private static BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal quantity) {
        return unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }

    private PurchaseOrderDetailResponse toDetailResponse(PurchaseOrderDetail detail) {
        var productVariant = detail.getProductVariant();
        // The line's pair, from the line's code and its own family: `RECEIVED` exists in three families.
        var detailStatus = catalogRowOf(StatusSubject.PURCHASE_ORDER_DETAIL, detail.getStatus());
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
                detailStatus == null ? null : detailStatus.getId(),
                detailStatus == null ? null : detailStatus.getStatusName(),
                detail.getStatus() == null ? null : detail.getStatus().name(),
                detail.getCreatedAt(),
                detail.getUpdatedAt());
    }
}
