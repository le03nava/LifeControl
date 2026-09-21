package com.lifecontrol.api.goodsreceipt.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptLineResponse;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.exception.DisabledProductVariantException;
import com.lifecontrol.api.goodsreceipt.exception.DisabledPurchaseOrderException;
import com.lifecontrol.api.goodsreceipt.exception.DisabledReceivingLocationException;
import com.lifecontrol.api.goodsreceipt.exception.DuplicateReceiptLineException;
import com.lifecontrol.api.goodsreceipt.exception.GoodsReceiptNotFoundException;
import com.lifecontrol.api.goodsreceipt.exception.InvalidReceiptQuantityException;
import com.lifecontrol.api.goodsreceipt.exception.MissingPurchaseOrderVariantException;
import com.lifecontrol.api.goodsreceipt.exception.OverReceiptException;
import com.lifecontrol.api.goodsreceipt.exception.PurchaseOrderVariantNotInStoreException;
import com.lifecontrol.api.goodsreceipt.exception.ReceiptNumberTooLongException;
import com.lifecontrol.api.goodsreceipt.exception.UnreceivableDetailStatusException;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptItem;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.exception.StoreLocationNotInStoreException;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderDetailNotFoundException;
import com.lifecontrol.api.purchaseorder.exception.PurchaseOrderNotFoundException;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Atomic reception use case: one transaction turns a validated request into a goods receipt, its
 * inventory effects and the purchase-order line statuses.
 *
 * <h2>Lock order of the slice</h2>
 * <p>{@code purchaseOrder -> storeStock -> locationBalance}. The receipt takes the purchase-order row
 * lock first and keeps it for the whole transaction; {@link InventoryService#applyReceipt} then locks
 * the per-store stock row (the serialization point after the variant-identity split) and the location
 * balance. No existing path locks a store stock row before a purchase order, so the global order is
 * cycle-free.</p>
 *
 * <h2>Why one transaction</h2>
 * <p>{@link #createReceipt} runs in a single transaction with no {@code REQUIRES_NEW} and no
 * {@code AFTER_COMMIT} listener: the receipt, the ledger movement, the balance updates and the
 * purchase-order line status must commit or roll back together. An {@code AFTER_COMMIT} listener
 * would move part of the effect outside the transaction and break that all-or-nothing property.</p>
 */
@Service
public class GoodsReceiptService {

    private static final Logger logger = LoggerFactory.getLogger(GoodsReceiptService.class);

    /** {@code inventory_movements.reference_type} discriminator written for every receipt line. */
    private static final String REFERENCE_TYPE = "GOODS_RECEIPT";

    /** Status family and the single status a new receipt is registered with. */
    private static final String RECEIPT_STATUS_TYPE = "GOODS_RECEIPT";

    private static final String REGISTERED_STATUS_NAME = "Registered";

    /** {@code goods_receipts.receipt_number} is {@code VARCHAR(30)}; the generator fails closed. */
    private static final int RECEIPT_NUMBER_MAX_LENGTH = 30;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderDetailRepository purchaseOrderDetailRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final InventoryService inventoryService;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final StoreInventorySettingsRepository storeInventorySettingsRepository;
    private final StoreLocationRepository storeLocationRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final StatusRepository statusRepository;
    private final CurrentUserContext currentUserContext;

    public GoodsReceiptService(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderDetailRepository purchaseOrderDetailRepository,
            PurchaseOrderService purchaseOrderService,
            InventoryService inventoryService,
            GoodsReceiptRepository goodsReceiptRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository,
            StoreLocationRepository storeLocationRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            StatusRepository statusRepository,
            CurrentUserContext currentUserContext) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderDetailRepository = purchaseOrderDetailRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.inventoryService = inventoryService;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
        this.storeLocationRepository = storeLocationRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.statusRepository = statusRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Registers one goods receipt against a purchase order.
     *
     * <p>The step order below is the contract, not a suggestion: the lock is always first, the
     * whole request is validated before any write, the receipt number is generated while the
     * purchase order is locked, and each line's ledger movement is written before its line status
     * changes.</p>
     *
     * @throws PurchaseOrderNotFoundException when the purchase order does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the purchase order's store
     * @throws com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException when the
     *     purchase order is not receivable ({@code Accepted} / {@code In Transit})
     * @throws DisabledPurchaseOrderException when the purchase order is soft-deleted
     * @throws StoreInventorySettingsNotFoundException when no receiving location is supplied and the
     *     store has no configured receiving location
     * @throws StoreLocationNotFoundException when the resolved receiving location does not exist
     * @throws DisabledReceivingLocationException when the resolved receiving location is disabled
     * @throws StoreLocationNotInStoreException when the resolved receiving location belongs to
     *     another store
     * @throws PurchaseOrderDetailNotFoundException when a line's detail does not belong to this
     *     purchase order or is disabled
     * @throws MissingPurchaseOrderVariantException when a line has no product variant
     * @throws PurchaseOrderVariantNotInStoreException when a line's variant belongs to another store
     * @throws InvalidReceiptQuantityException when a quantity is not positive or not a whole number
     * @throws OverReceiptException when a line would exceed its ordered quantity
     * @throws DuplicateReceiptLineException when one request repeats a purchase-order line
     * @throws StatusNotFoundException when the {@code GOODS_RECEIPT} / {@code Registered} status is
     *     missing
     * @throws ReceiptNumberTooLongException when the generated number would not fit the column
     */
    @Transactional
    public GoodsReceiptResponse createReceipt(GoodsReceiptRequest request) {
        // ── Step 1: lock first ────────────────────────────────────────────────────────────────
        // findByIdForUpdate is this transaction's FIRST database interaction. It takes a
        // PESSIMISTIC_WRITE lock on the purchase order for the whole transaction, which is also what
        // makes the per-order receipt number safe: step 6 counts the receipts of THAT order, so two
        // concurrent receptions against it queue on this row instead of reading the same count. A
        // string MAX(receipt_number) lookup was rejected (W2-D8): the suffix lives inside a
        // zero-padded text key, so text ordering stops matching the sequence once the suffix widens
        // ("...-100" sorts before "...-99").
        var purchaseOrder = purchaseOrderRepository
                .findByIdForUpdate(request.purchaseOrderId())
                .orElseThrow(() -> new PurchaseOrderNotFoundException(request.purchaseOrderId()));

        var companyStore = purchaseOrder.getCompanyStore();

        // ── Step 2: tenant isolation ───────────────────────────────────────────────────────────
        // The store is DERIVED from the purchase order, so it is not in the URL and @PreAuthorize
        // alone cannot scope it: without this check any user holding the receiving role could
        // receive against another store's order. Verify the full company -> country -> region ->
        // zone -> store path BEFORE any write.
        var companyZone = companyStore.getCompanyZone();
        var companyRegion = companyZone.getCompanyRegion();
        var companyCountry = companyRegion.getCompanyCountry();
        currentUserContext.verifyCompanyStoreAccess(
                companyCountry.getCompany().getId(),
                companyCountry.getId(),
                companyRegion.getId(),
                companyZone.getId(),
                companyStore.getId());

        // ── Step 3: receptivity and enabled ────────────────────────────────────────────────────
        // requireReceivable owns the W1 rule (Accepted / In Transit). findByIdForUpdate deliberately
        // does not filter by enabled, unlike the product-variant locking finder: a disabled order is
        // found, so this check turns it into a precise message instead of a confusing not-found.
        purchaseOrderService.requireReceivable(purchaseOrder);
        if (!Boolean.TRUE.equals(purchaseOrder.getEnabled())) {
            throw new DisabledPurchaseOrderException(purchaseOrder.getId(), purchaseOrder.getOrderNumber());
        }

        // ── Step 4: resolve and validate the receiving location ────────────────────────────────
        var receivingLocationId = resolveReceivingLocationId(request, companyStore.getId());
        var receivingLocation = storeLocationRepository
                .findById(receivingLocationId)
                .orElseThrow(() -> new StoreLocationNotFoundException(receivingLocationId));
        if (!Boolean.TRUE.equals(receivingLocation.getEnabled())) {
            throw new DisabledReceivingLocationException(receivingLocationId);
        }
        // The ownership check runs AFTER the enabled check on purpose: the store-scoped finder below
        // only lists ENABLED locations, so testing membership first would misreport a disabled
        // location of the correct store as foreign.
        if (!belongsToStore(receivingLocationId, companyStore.getId())) {
            throw new StoreLocationNotInStoreException(receivingLocationId);
        }

        // ── Step 5: validate the WHOLE request before any write ────────────────────────────────
        // One failing line must not leave earlier lines half-applied, so every line is validated
        // (read-only) before the first write happens further down.
        var resolvedLines = validateAndResolveLines(request, purchaseOrder, companyStore);

        // ── Step 6: generate the receipt number inside the lock ────────────────────────────────
        // The counter is per order precisely because that is what makes the purchase-order lock
        // sufficient: the order row is already locked, so COUNT(*) over that order's receipts is a
        // stable sequence position. Receipts are immutable (never edited or deleted), so the count
        // is exact. A MAX(receipt_number) string lookup was rejected: the suffix is zero-padded
        // text, so once it widens past the padded width the text no longer orders the sequence.
        long receiptCount = goodsReceiptRepository.countByPurchaseOrderId(purchaseOrder.getId());
        var receiptNumber = generateReceiptNumber(purchaseOrder, receiptCount);

        // ── Step 7: persist the receipt ────────────────────────────────────────────────────────
        var status = statusRepository
                .findByTypeNameAndStatusName(RECEIPT_STATUS_TYPE, REGISTERED_STATUS_NAME)
                .orElseThrow(() -> new StatusNotFoundException(
                        "Status '" + REGISTERED_STATUS_NAME + "' not found for " + RECEIPT_STATUS_TYPE + " type"));
        var receivedBy = currentUserContext.getUsername();

        var receipt = GoodsReceipt.builder()
                .receiptNumber(receiptNumber)
                .purchaseOrder(purchaseOrder)
                .companyStore(companyStore)
                .receivingLocation(receivingLocation)
                .status(status)
                .receivedBy(receivedBy)
                .comments(request.comments())
                .enabled(true)
                .build();
        // receivedAt is deliberately NOT set here: the entity's @PrePersist callback owns it.
        for (var line : resolvedLines) {
            receipt.getItems()
                    .add(GoodsReceiptItem.builder()
                            .goodsReceipt(receipt)
                            .purchaseOrderDetail(line.detail())
                            .productVariant(line.variant())
                            .quantityReceived(line.request().quantityReceived())
                            .comments(line.request().comments())
                            .build());
        }
        var saved = goodsReceiptRepository.save(receipt);

        // ── Step 8: apply the effects, per line ────────────────────────────────────────────────
        for (var line : resolvedLines) {
            // Ledger movement FIRST, line status SECOND: the movement must already exist when the
            // status change fires its event. applyReceipt also keeps the documented
            // purchaseOrder -> storeStock -> locationBalance lock order (the purchase order is
            // locked above).
            inventoryService.applyReceipt(
                    line.variant().getId(),
                    companyStore.getId(),
                    receivingLocationId,
                    line.request().quantityReceived(),
                    REFERENCE_TYPE,
                    saved.getId(),
                    receivedBy);
            // Reusing registerReceivedQuantity is mandatory, not a preference: it is the ONLY
            // allowed writer of the detail status (a second writer is forbidden by this feature). It
            // takes the ACCUMULATED TOTAL, not a delta, and derives Partial Received / Received.
            purchaseOrderService.registerReceivedQuantity(
                    purchaseOrder.getId(), line.detail().getId(), line.accumulatedQuantity());
        }

        logger.info(
                "Goods receipt registered: receiptNumber={}, purchaseOrderId={}, lines={}",
                saved.getReceiptNumber(),
                purchaseOrder.getId(),
                resolvedLines.size());

        // ── Step 9: return the persisted receipt ───────────────────────────────────────────────
        return toResponse(saved);
    }

    /**
     * Returns one receipt by id after authorizing it against its own store.
     *
     * <p>The store is not in the URL, so {@code @PreAuthorize} alone cannot scope this read: it only
     * proves the caller holds some store-scoped role. The receipt's company &rarr; country &rarr;
     * region &rarr; zone &rarr; store chain is resolved from its JPA associations and verified with
     * {@link CurrentUserContext#verifyCompanyStoreAccess}, the same shape
     * {@code StoreLocationService#getLocationById} relies on.</p>
     *
     * @throws GoodsReceiptNotFoundException when no receipt exists with the given id
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the receipt's store
     */
    @Transactional(readOnly = true)
    public GoodsReceiptResponse getReceipt(UUID id) {
        var receipt = goodsReceiptRepository.findById(id).orElseThrow(() -> new GoodsReceiptNotFoundException(id));

        var companyStore = receipt.getCompanyStore();
        var companyZone = companyStore.getCompanyZone();
        var companyRegion = companyZone.getCompanyRegion();
        var companyCountry = companyRegion.getCompanyCountry();
        currentUserContext.verifyCompanyStoreAccess(
                companyCountry.getCompany().getId(),
                companyCountry.getId(),
                companyRegion.getId(),
                companyZone.getId(),
                companyStore.getId());

        return toResponse(receipt);
    }

    /**
     * Returns a page of receipts, scoped to the stores the caller may see (W2-D10).
     *
     * <p>A flat list has no store in its path, so {@code @PreAuthorize} only proves the caller holds
     * <em>some</em> store-scoped role; without this filter any store-level user would see every
     * store's receipts, contradicting the store-scoped receiving role this slice introduces. Admins
     * are exempt and see the unscoped page. A non-admin is restricted to its
     * {@code company_store_ids}; an <strong>empty</strong> set yields an empty page and never the
     * unscoped query, because an empty set means the caller is scoped to no store at all.</p>
     */
    @Transactional(readOnly = true)
    public Page<GoodsReceiptResponse> getAllReceipts(Pageable pageable, String search) {
        var normalizedSearch = StringUtils.hasText(search) ? search.trim() : null;

        // W2-D10 tenant scoping: a flat list has no store in its path, so @PreAuthorize only proves
        // the caller holds SOME store-scoped role. Without this filter any store-level user would see
        // every store's receipts, contradicting the store-scoped receiving role. Admins are exempt
        // and read the unscoped page.
        if (currentUserContext.isAdmin()) {
            var page = normalizedSearch == null
                    ? goodsReceiptRepository.findByEnabledTrueOrderByReceivedAtDesc(pageable)
                    : goodsReceiptRepository.findBySearchTerm(normalizedSearch, pageable);
            return page.map(this::toResponse);
        }

        var storeIds = currentUserContext.getCompanyStoreIds();
        // An empty company_store_ids set means the caller is not scoped to any store: return an empty
        // page. Never fall through to the unscoped query, which would leak every store's receipts.
        if (storeIds.isEmpty()) {
            return Page.empty(pageable);
        }
        // Search/no-search is two store-scoped finders, not one with a nullable parameter: the same
        // choice shape as PurchaseOrderService#getAllPurchaseOrders. PostgreSQL cannot type a
        // null-bound `:search IS NULL` predicate, so the searched finder is only ever called with a
        // non-blank term and the unsearched one carries no search predicate at all.
        var page = StringUtils.hasText(normalizedSearch)
                ? goodsReceiptRepository.findByCompanyStoreIdInAndSearchTerm(storeIds, normalizedSearch, pageable)
                : goodsReceiptRepository.findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(storeIds, pageable);
        return page.map(this::toResponse);
    }

    /** Operator override first, the store's configured receiving location second. */
    private UUID resolveReceivingLocationId(GoodsReceiptRequest request, UUID companyStoreId) {
        if (request.receivingLocationId() != null) {
            return request.receivingLocationId();
        }
        return storeInventorySettingsRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new StoreInventorySettingsNotFoundException(companyStoreId))
                .getReceivingLocationId();
    }

    /**
     * "This location belongs to this store", via the store-scoped enabled-location finder W2b added.
     *
     * <p>The finder only returns enabled locations, which is why the caller checks {@code enabled}
     * before this method.</p>
     */
    private boolean belongsToStore(UUID storeLocationId, UUID companyStoreId) {
        return storeLocationRepository.findEnabledByCompanyStoreId(companyStoreId).stream()
                .anyMatch(location -> location.getId().equals(storeLocationId));
    }

    /**
     * Validates every line and resolves the entities the persistence and effect steps need.
     *
     * <p>Read-only: it must not write, so a rejected request changes nothing. Each rejection names
     * the offending line index (zero-based) and the reason.</p>
     */
    private List<ResolvedLine> validateAndResolveLines(
            GoodsReceiptRequest request, PurchaseOrder purchaseOrder, CompanyStore companyStore) {
        var resolved = new ArrayList<ResolvedLine>(request.lines().size());
        var seenDetailIds = new HashSet<UUID>();
        var lineIndex = 0;

        for (var line : request.lines()) {
            // A repeated line would move the same inventory twice; detect it before resolving the
            // repeat itself, so a valid first occurrence is still validated in request order.
            if (!seenDetailIds.add(line.purchaseOrderDetailId())) {
                throw new DuplicateReceiptLineException(lineIndex, line.purchaseOrderDetailId());
            }

            var detail = purchaseOrderDetailRepository
                    .findById(line.purchaseOrderDetailId())
                    .orElseThrow(() -> new PurchaseOrderDetailNotFoundException(line.purchaseOrderDetailId()));
            // Existence, ownership and enabled collapse to the same 404: a line of another order is
            // as unusable as a missing or soft-deleted one.
            if (!purchaseOrder.getId().equals(detail.getPurchaseOrder().getId())
                    || !Boolean.TRUE.equals(detail.getEnabled())) {
                throw new PurchaseOrderDetailNotFoundException(line.purchaseOrderDetailId());
            }

            var variant = detail.getProductVariant();
            if (variant == null) {
                throw new MissingPurchaseOrderVariantException(lineIndex, detail.getId());
            }
            // Store membership is no longer a column on the definition: it is the existence of the
            // per-store row `(variant, store)` in `product_variant_store_stock`. A definition that
            // exists globally but was never registered in this store is as unusable as a foreign one.
            if (!productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                    variant.getId(), companyStore.getId())) {
                throw new PurchaseOrderVariantNotInStoreException(lineIndex, detail.getId(), variant.getId());
            }
            // A disabled variant must be rejected here and not left to InventoryService.applyReceipt:
            // that mutator locks with enabled = true, so a variant disabled after the order was raised
            // passes this far, the receipt row is written, and only then the mutator throws a 404. The
            // more specific diagnoses above (NULL variant, foreign store) keep their own errors first.
            if (!Boolean.TRUE.equals(variant.getEnabled())) {
                throw new DisabledProductVariantException(lineIndex, detail.getId(), variant.getId());
            }

            // The status guard belongs to the pre-write phase for the same reason: leaving it to
            // PurchaseOrderService.registerReceivedQuantity would let the receipt and the inventory
            // effect be written before a terminal line status (Cancelled / Rejected) is rejected.
            try {
                purchaseOrderService.requireDetailReceivable(detail);
            } catch (InvalidStatusTransitionException e) {
                throw new UnreceivableDetailStatusException(lineIndex, detail.getId(), e.getMessage(), e);
            }

            var quantity = line.quantityReceived();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidReceiptQuantityException(lineIndex, "must be greater than zero", quantity);
            }
            if (quantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                throw new InvalidReceiptQuantityException(lineIndex, "must be a whole number", quantity);
            }

            var accumulated = BigDecimal.valueOf(detail.getReceivedQuantity()).add(quantity);
            if (accumulated.compareTo(BigDecimal.valueOf(detail.getQuantity())) > 0) {
                throw new OverReceiptException(
                        lineIndex, detail.getId(), accumulated, BigDecimal.valueOf(detail.getQuantity()));
            }

            resolved.add(
                    new ResolvedLine(line, detail, variant, quantity.intValueExact(), accumulated.intValueExact()));
            lineIndex++;
        }

        return resolved;
    }

    /** {@code GR-{orderNumber}-{NN}}, failing closed when it would not fit the column. */
    private String generateReceiptNumber(PurchaseOrder purchaseOrder, long receiptCount) {
        var receiptNumber = "GR-" + purchaseOrder.getOrderNumber() + "-" + String.format("%02d", receiptCount + 1);
        if (receiptNumber.length() > RECEIPT_NUMBER_MAX_LENGTH) {
            throw new ReceiptNumberTooLongException(
                    receiptNumber, purchaseOrder.getOrderNumber(), RECEIPT_NUMBER_MAX_LENGTH);
        }
        return receiptNumber;
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt receipt) {
        var lines = receipt.getItems().stream()
                .map(item -> new GoodsReceiptLineResponse(
                        item.getId(),
                        item.getPurchaseOrderDetail().getId(),
                        item.getProductVariant().getId(),
                        item.getQuantityReceived(),
                        item.getComments()))
                .toList();

        return new GoodsReceiptResponse(
                receipt.getId(),
                receipt.getReceiptNumber(),
                receipt.getPurchaseOrder().getId(),
                receipt.getPurchaseOrder().getOrderNumber(),
                receipt.getCompanyStore().getId(),
                receipt.getReceivingLocation().getId(),
                receipt.getStatus().getId(),
                receipt.getStatus().getStatusName(),
                receipt.getReceivedBy(),
                receipt.getReceivedAt(),
                receipt.getComments(),
                receipt.getEnabled(),
                lines);
    }

    /** One validated request line with the entities and derived quantities the later steps use. */
    private record ResolvedLine(
            GoodsReceiptLineRequest request,
            PurchaseOrderDetail detail,
            ProductVariant variant,
            int quantity,
            int accumulatedQuantity) {}
}
