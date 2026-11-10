package com.lifecontrol.api.purchaseorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.document.DocumentNumberRenderer;
import com.lifecontrol.api.common.document.DocumentSequenceAllocator;
import com.lifecontrol.api.common.document.DocumentType;
import com.lifecontrol.api.common.state.DocumentStateCatalog;
import com.lifecontrol.api.common.state.InvalidStatusTransitionException;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.common.state.StatusTransitionRecorder;
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
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderFilter;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
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
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderService Tests")
class PurchaseOrderServiceTest {

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderDetailRepository detailRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private StatusTypeRepository statusTypeRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private DocumentSequenceAllocator documentSequenceAllocator;

    /**
     * The history writer, mocked: what this class asserts about it is <b>which</b> rows a path appends — the
     * family, the document, both ends of the move and the reason — because the row itself and its two deferred
     * checks are the database's ({@code StatusTransitionRecorderIntegrationTest}, {@code V69}).
     */
    @Mock
    private StatusTransitionRecorder statusTransitionRecorder;

    /**
     * The persistence context, mocked because the only thing this class asks of it is
     * {@code refresh} after a write: what a refresh returns is a row, and a row is what this suite has no
     * database for. The read-back itself is asserted where it can be observed — a scratch database, in
     * {@code PurchaseOrderMoneyMigrationIntegrationTest}, and the HTTP response in
     * {@code PurchaseOrderControllerTest}.
     */
    @Mock
    private EntityManager entityManager;

    /**
     * The real catalog over the mocked {@code statuses} read: the four refusals are its own contract and are
     * asserted in {@code DocumentStateCatalogTest}, so a mock here would only prove that a call was made. What
     * this fixture supplies instead is a repository whose rows carry the {@code code} the enum spells.
     */
    private DocumentStateCatalog documentStateCatalog;

    /**
     * The real derivation, not a mock: the store fixture carries the whole
     * company &rarr; country &rarr; region &rarr; zone &rarr; store chain, so the tenant the line
     * lookup asks for is the one the fixture describes.
     */
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver = new CompanyStoreOwnerResolver();

    private PurchaseOrderService service;

    private UUID poId, detailId, supplierId, storeId, pmId, productId, statusId, draftStatusId;
    private UUID companyId, companyCountryId, regionId, zoneId;
    private UUID variantId;
    private Supplier supplier;
    private CompanyStore store;
    private Company company;
    private CompanyCountry companyCountry;
    private CompanyRegion companyRegion;
    private CompanyZone companyZone;
    private PaymentMethod paymentMethod;
    private Product product;
    private ProductVariant productVariant;
    private final Map<Status, PurchaseOrderState> headerStates = new LinkedHashMap<>();
    private final Map<Status, PurchaseOrderDetailState> detailStates = new LinkedHashMap<>();
    private Status draftStatus, sentStatus, pendingStatus, inProcessStatus;
    private Status inTransitStatus, partialReceivedStatus, receivedStatus;
    private Status rejectedStatus, cancelledStatus;
    private Status acceptedStatus, inTransitPoStatus, receivedPoStatus;
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

        variantId = UUID.randomUUID();
        productVariant = new ProductVariant();
        productVariant.setId(variantId);
        productVariant.setProductId(productId);
        productVariant.setVariantName("Test Variant");
        productVariant.setEnabled(true);

        poStatusType = new StatusType();
        poStatusType.setId(UUID.randomUUID());
        poStatusType.setStatusTypeName("PURCHASE_ORDER");

        detailStatusType = new StatusType();
        detailStatusType.setId(UUID.randomUUID());
        detailStatusType.setStatusTypeName("PURCHASE_ORDER_DETAIL");

        draftStatus = status(draftStatusId, "Draft", "DRAFT", poStatusType);
        sentStatus = status(statusId, "Sent", "SENT", poStatusType);
        pendingStatus = status(UUID.randomUUID(), "Pending", "PENDING", detailStatusType);
        inProcessStatus = status(UUID.randomUUID(), "In Process", "IN_PROCESS", detailStatusType);
        inTransitStatus = status(UUID.randomUUID(), "In Transit", "IN_TRANSIT", detailStatusType);
        partialReceivedStatus = status(UUID.randomUUID(), "Partial Received", "PARTIAL_RECEIVED", detailStatusType);
        receivedStatus = status(UUID.randomUUID(), "Received", "RECEIVED", detailStatusType);
        rejectedStatus = status(UUID.randomUUID(), "Rejected", "REJECTED", detailStatusType);
        cancelledStatus = status(UUID.randomUUID(), "Cancelled", "CANCELLED", detailStatusType);
        acceptedStatus = status(UUID.randomUUID(), "Accepted", "ACCEPTED", poStatusType);
        inTransitPoStatus = status(UUID.randomUUID(), "In Transit", "IN_TRANSIT", poStatusType);
        receivedPoStatus = status(UUID.randomUUID(), "Received", "RECEIVED", poStatusType);

        // One pairing, declared next to the rows: the fixture states a catalog row and the machine reads the
        // constant it carries, exactly as `statuses.code` carries it in the database.
        headerStates.put(draftStatus, PurchaseOrderState.DRAFT);
        headerStates.put(sentStatus, PurchaseOrderState.SENT);
        headerStates.put(acceptedStatus, PurchaseOrderState.ACCEPTED);
        headerStates.put(inTransitPoStatus, PurchaseOrderState.IN_TRANSIT);
        headerStates.put(receivedPoStatus, PurchaseOrderState.RECEIVED);
        detailStates.put(pendingStatus, PurchaseOrderDetailState.PENDING);
        detailStates.put(inProcessStatus, PurchaseOrderDetailState.IN_PROCESS);
        detailStates.put(inTransitStatus, PurchaseOrderDetailState.IN_TRANSIT);
        detailStates.put(partialReceivedStatus, PurchaseOrderDetailState.PARTIAL_RECEIVED);
        detailStates.put(receivedStatus, PurchaseOrderDetailState.RECEIVED);
        detailStates.put(rejectedStatus, PurchaseOrderDetailState.REJECTED);
        detailStates.put(cancelledStatus, PurchaseOrderDetailState.CANCELLED);

        // Every response projects its pair from the code, so the fixture answers the projection by (family,
        // code): that is the same pairing the rows above carry, read from the other side. Lenient because a
        // case that fails before its response is built has not proved anything about the label.
        var catalogRows = new java.util.HashMap<String, Status>();
        headerStates.forEach((row, state) -> catalogRows.put("PURCHASE_ORDER/" + state.name(), row));
        detailStates.forEach((row, state) -> catalogRows.put("PURCHASE_ORDER_DETAIL/" + state.name(), row));
        lenient()
                .when(statusRepository.findByTypeNameAndCode(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(catalogRows.get(
                        invocation.getArgument(0, String.class) + "/" + invocation.getArgument(1, String.class))));

        purchaseOrder = PurchaseOrder.builder()
                .id(poId)
                .orderNumber("PO-20260603-00001")
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(PurchaseOrderState.DRAFT)
                .comments("Test PO")
                .enabled(true)
                .build();
        purchaseOrder.setDetails(new ArrayList<>());

        // The order's owner is resolved from its store on every write (V43), so the company the
        // fixture's store chain already describes is the one that lookup returns. Lenient because most
        // cases here stop before that lookup runs, and a case that never reaches it has not proved
        // anything about the owner.
        lenient().when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        documentStateCatalog = new DocumentStateCatalog(statusRepository);

        service = new PurchaseOrderService(
                purchaseOrderRepository,
                detailRepository,
                supplierRepository,
                companyStoreRepository,
                companyStoreOwnerResolver,
                companyRepository,
                productRepository,
                productVariantRepository,
                productVariantStoreStockRepository,
                paymentMethodRepository,
                statusRepository,
                eventPublisher,
                currentUserContext,
                documentSequenceAllocator,
                statusTransitionRecorder,
                documentStateCatalog,
                entityManager);

        detail = PurchaseOrderDetail.builder()
                .id(detailId)
                .purchaseOrder(purchaseOrder)
                .product(product)
                .quantity(BigDecimal.valueOf(5))
                .unitPrice(new BigDecimal("100.00"))
                .total(new BigDecimal("500.00"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(PurchaseOrderDetailState.PENDING)
                .enabled(true)
                .build();
    }

    /**
     * One catalog row with its code, which is what both readers of a status need: the enum the machine decides
     * with, and the row a response projects {@code statusId}/{@code statusName} from.
     *
     * <p>Lenient because a case that is refused by the catalog — a row of another family, a code the enum does
     * not declare — never reaches the projection, and strict stubs would fail such a case for the stub it did
     * not need instead of for the refusal it asserts.</p>
     */
    private Status status(UUID id, String name, String code, StatusType type) {
        return Status.builder()
                .id(id)
                .statusName(name)
                .code(code)
                .statusType(type)
                .enabled(true)
                .build();
    }

    /** The typed state a fixture catalog row carries, refused loudly when a case names an unpaired row. */
    private PurchaseOrderState headerStateOf(Status row) {
        var state = headerStates.get(row);
        if (state == null) {
            throw new IllegalStateException("no typed header state for fixture row " + row.getStatusName());
        }
        return state;
    }

    /** The line's half of {@link #headerStateOf(Status)}. */
    private PurchaseOrderDetailState detailStateOf(Status row) {
        var state = detailStates.get(row);
        if (state == null) {
            throw new IllegalStateException("no typed detail state for fixture row " + row.getStatusName());
        }
        return state;
    }

    /** The one catalog row, stubbed for the request-side reader: the family fetch by id. */
    private void catalogRow(Status status) {
        lenient().when(statusRepository.findWithStatusTypeById(status.getId())).thenReturn(Optional.of(status));
    }

    // ─── getAllPurchaseOrders ────────────────────────────────────────────

    @Nested
    @DisplayName("getAllPurchaseOrders")
    class GetAllPurchaseOrdersTests {

        @Test
        @DisplayName("should return the unfiltered page when neither search nor filters are given")
        void returnsPaginatedResults() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            when(purchaseOrderRepository.findByFilters(null, null, null, null, null, null, pageable))
                    .thenReturn(page);

            var result = service.getAllPurchaseOrders(pageable, null, PurchaseOrderFilter.NONE);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().orderNumber()).isEqualTo("PO-20260603-00001");
            assertThat(result.getContent().getFirst().supplierName()).isEqualTo("Test Supplier");
        }

        @Test
        @DisplayName("should pass the trimmed search term to the one filtered query")
        void passesTheTrimmedSearchTerm() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            when(purchaseOrderRepository.findByFilters("Test", null, null, null, null, null, pageable))
                    .thenReturn(page);

            var result = service.getAllPurchaseOrders(pageable, "  Test  ", PurchaseOrderFilter.NONE);

            assertThat(result.getContent()).hasSize(1);
            verify(purchaseOrderRepository).findByFilters("Test", null, null, null, null, null, pageable);
        }

        @Test
        @DisplayName("should pass a null search when the term is blank")
        void passesNullForBlankSearch() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            when(purchaseOrderRepository.findByFilters(null, null, null, null, null, null, pageable))
                    .thenReturn(page);

            service.getAllPurchaseOrders(pageable, "   ", PurchaseOrderFilter.NONE);

            verify(purchaseOrderRepository).findByFilters(null, null, null, null, null, null, pageable);
        }

        @Test
        @DisplayName("should forward each of the five location filters")
        void forwardsEveryLocationFilter() {
            var pageable = PageRequest.of(0, 10);
            var page = new PageImpl<>(List.of(purchaseOrder), pageable, 1);
            var filter = new PurchaseOrderFilter(companyId, companyCountryId, regionId, zoneId, storeId);
            when(purchaseOrderRepository.findByFilters(
                            null, companyId, companyCountryId, regionId, zoneId, storeId, pageable))
                    .thenReturn(page);

            service.getAllPurchaseOrders(pageable, null, filter);

            verify(purchaseOrderRepository)
                    .findByFilters(null, companyId, companyCountryId, regionId, zoneId, storeId, pageable);
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
                    .hasMessageContaining("Purchase order not found");
        }
    }

    // ─── createPurchaseOrder ─────────────────────────────────────────────

    @Nested
    @DisplayName("createPurchaseOrder")
    class CreatePurchaseOrderTests {

        @Test
        @DisplayName("should create PO with Draft status and auto-generated order_number")
        void createsPOWithDraftAndOrderNumber() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of());

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });

            var result = service.createPurchaseOrder(request);

            assertThat(result).isNotNull();
            assertThat(result.statusName()).isEqualTo("Draft");
            assertThat(result.orderNumber()).startsWith("PO-");
            assertThat(result.orderNumber()).containsPattern("PO-\\d{6}-00001");
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            assertThat(result.companyStoreName()).isEqualTo("Test Store");
            assertThat(result.paymentMethodName()).isEqualTo("Transfer");
            verify(eventPublisher).publishEvent(any(PurchaseOrderCreatedEvent.class));

            // ADR-0003 decisions 4 and 5: the parts are stored, the rendered string is written once,
            // and the period is the frozen date's, not a second clock read.
            var created = captor.getValue();
            assertThat(created.getOrderNumber()).isEqualTo("PO-" + created.getSeqPeriod() + "-00001");
            assertThat(created.getDocNumber()).isEqualTo(created.getOrderNumber());
            assertThat(created.getDocType()).isEqualTo("PURCHASE_ORDER");
            assertThat(created.getSeqValue()).isEqualTo(1L);
            verify(documentSequenceAllocator).allocate(companyId, DocumentType.PURCHASE_ORDER, created.getSeqPeriod());
        }

        @Test
        @DisplayName("should number the order in the period of the date its own row records")
        void numbersTheOrderInThePeriodOfTheDateItsRowRecords() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of());

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });

            service.createPurchaseOrder(request);

            // `purchase_orders` carries no order_date (V1:317-328 has only created_at/updated_at), so the
            // date the row records IS the document's own date, and ADR-0003 decision 4 says one date is read
            // for the period. A period taken from one clock read and a created_at stamped by a second one is
            // how a number for October ends up on a row dated November, so the order has to carry the very
            // instant its number was allocated in and the pair has to agree.
            var created = captor.getValue();
            assertThat(created.getCreatedAt())
                    .as("the order carries the instant its number was allocated in, not a later reading at flush")
                    .isNotNull();
            assertThat(created.getSeqPeriod())
                    .as("the period is the period of the date the row records")
                    .isEqualTo(DocumentNumberRenderer.periodOf(
                            created.getCreatedAt().toLocalDate()));
            assertThat(created.getUpdatedAt())
                    .as("a row born just now has not been updated since")
                    .isEqualTo(created.getCreatedAt());
        }

        @Test
        @DisplayName("should store the store's company on the order, alongside the supplier it names")
        void storesTheStoresCompanyOnTheOrder() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of());

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);

            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var saved = captor.getValue();
                saved.setId(poId);
                return saved;
            });

            service.createPurchaseOrder(request);

            // V43's composite key is (company_id, supplier_id): the owner has to come from the store,
            // because the supplier is the half being constrained and deriving it there would make the
            // key a tautology.
            assertThat(captor.getValue().getCompany())
                    .as("the order's owner is the company of its store")
                    .isSameAs(company);
            assertThat(captor.getValue().getCompanyStore().getId()).isEqualTo(storeId);
        }

        @Test
        @DisplayName("should leave the line money to the schema instead of computing it")
        void doesNotWriteLineMoney() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("150.00"),
                    "Detail comments",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            catalogRow(pendingStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);

            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var po = captor.getValue();
                po.setId(poId);
                return po;
            });

            var result = service.createPurchaseOrder(request);

            var savedPO = captor.getValue();
            assertThat(savedPO.getDetails()).hasSize(1);
            var savedLine = savedPO.getDetails().getFirst();
            assertThat(savedLine.getUnitPrice()).isEqualByComparingTo("150.00");
            assertThat(savedLine.getQuantity()).isEqualByComparingTo("3");
            // V92 derives both columns from those two numbers, so a service that also wrote them would be a
            // second writer whose value the trigger overwrites on any statement that names the column (S2).
            assertThat(savedLine.getSubtotal()).isNull();
            assertThat(savedLine.getTotal()).isNull();
            // The document and each line are read back instead of assumed: the four money columns are the
            // schema's answer to the lines just written (S1, S3), and the response is built from the
            // refreshed entities rather than from the ones the persistence context had (S5).
            verify(entityManager).refresh(any(PurchaseOrder.class));
            verify(entityManager).refresh(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should refuse a line naming another company's variant and persist nothing")
        void refusesLineNamingAnotherCompanysVariant() {
            // The hole this pins: the variant belongs to another company, is enabled, and is even
            // stocked at the order's store. Nothing in the schema forbids the (variant, store) pair
            // — company_stores has no company_id and the composite FKs only tie a variant to its
            // product — so the only gate is the company the lookup is asked for.
            var detailReq = new PurchaseOrderDetailRequest(
                    productId, variantId, BigDecimal.ONE, new BigDecimal("10.00"), null, pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            // The allocation runs before the lines are resolved, so even a refused line has already taken a
            // value; the case is about the refusal, not about numbering.
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            // The order's store belongs to companyId, and no variant of that company carries this id:
            // that is what a variant of another tenant looks like once the lookup is scoped.
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining("Product variant not found");

            // The company asked for is the one that owns the order's store — not the product's, not
            // the caller's — which is the only thing that separates a reachable variant from a
            // foreign one.
            verify(productVariantRepository)
                    .findByIdAndProductIdAndCompanyIdAndEnabledTrue(variantId, productId, companyId);
            // The per-store half is never reached, and the refusal is the whole line, not just the
            // link: no stock question, no order, no line and no event.
            verify(productVariantStoreStockRepository, never()).existsByProductVariantIdAndCompanyStoreId(any(), any());
            verify(purchaseOrderRepository, never()).save(any());
            verify(detailRepository, never()).saveAll(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should throw SupplierNotFoundException when supplier missing")
        void throwsWhenSupplierMissing() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            // The store is resolved first: its company is what the supplier lookup is scoped by.
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(SupplierNotFoundException.class);
        }

        @Test
        @DisplayName("should refuse a supplier of another company and persist nothing")
        void refusesSupplierOfAnotherCompanyAndPersistsNothing() {
            // The order's store belongs to companyId, and that is the tenant the order belongs to; the
            // supplier id names a row that exists under a different company. The scoped lookup is asked
            // for the store's company, so the foreign row is indistinguishable from none — and the order
            // must not be written on the strength of a supplier that exists somewhere else.
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of());

            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .hasMessageContaining(supplierId.toString());

            // The company asked for is the one that owns the order's store — not the supplier's own.
            verify(supplierRepository).findByIdAndCompanyId(supplierId, companyId);
            // Nothing is stored: no order, no line, no event, and the payment method is never asked for.
            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
            verify(detailRepository, never()).saveAll(any());
            verify(eventPublisher, never()).publishEvent(any());
            verify(paymentMethodRepository, never()).findById(any());
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when store missing")
        void throwsWhenStoreMissing() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
        }

        @Test
        @DisplayName("should throw PaymentMethodNotFoundException when payment method missing")
        void throwsWhenPaymentMethodMissing() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(PaymentMethodNotFoundException.class);
        }

        @Test
        @DisplayName("should throw StatusNotFoundException when status missing")
        void throwsWhenStatusMissing() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(statusRepository.findWithStatusTypeById(draftStatusId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request)).isInstanceOf(StatusNotFoundException.class);
        }

        @Test
        @DisplayName("should throw when status has wrong type")
        void throwsWhenWrongStatusType() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, pendingStatus.getId(), null, List.of());
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(pendingStatus);

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PURCHASE_ORDER");
        }

        @Test
        @DisplayName("should throw AccessDeniedException when the caller holds no grant for the store")
        void throwsWhenStoreOutsideCallerScope() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);

            assertThatThrownBy(() -> service.createPurchaseOrder(request)).isInstanceOf(AccessDeniedException.class);

            // The guard must receive the chain derived from the store, not the raw store id.
            verify(currentUserContext).verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
            // A store the caller may not touch is refused before the supplier is even asked for.
            verify(supplierRepository, never()).findByIdAndCompanyId(any(), any());
            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
        }
    }

    // ─── updatePurchaseOrder ─────────────────────────────────────────────

    @Nested
    @DisplayName("updatePurchaseOrder")
    class UpdatePurchaseOrderTests {

        @Test
        @DisplayName("should update PO fields")
        void updatesPOFields() {
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Updated comments", List.of());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            var result = service.updatePurchaseOrder(poId, request);

            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo("Test Supplier");
            verify(purchaseOrderRepository).save(any(PurchaseOrder.class));
        }

        @Test
        @DisplayName("should throw PurchaseOrderNotFoundException when PO missing")
        void throwsWhenPOMissing() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrder(poId, request))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);
        }

        @Test
        @DisplayName("should resolve a valid variant when replacing details")
        void replacesDetailsWithValidVariant() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("40.00"),
                    "Replacement",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Replaced", List.of(detailReq));

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(pendingStatus);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            var captor = ArgumentCaptor.forClass(PurchaseOrderDetail.class);
            when(detailRepository.save(captor.capture())).thenReturn(detail);

            service.updatePurchaseOrder(poId, request);

            assertThat(captor.getValue().getProductVariant()).isSameAs(productVariant);
            // Replacing the lines moves the document's money, so the write path reads the order back before
            // the response states it (S5).
            verify(entityManager).refresh(any(PurchaseOrder.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when the caller holds no grant for the store")
        void throwsWhenStoreOutsideCallerScope() {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, null, List.of());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);

            assertThatThrownBy(() -> service.updatePurchaseOrder(poId, request))
                    .isInstanceOf(AccessDeniedException.class);

            verify(supplierRepository, never()).findByIdAndCompanyId(any(), any());
            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
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
            catalogRow(sentStatus);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            var result = service.updatePurchaseOrderStatus(poId, request);

            assertThat(result.statusName()).isEqualTo("Sent");
            verify(eventPublisher).publishEvent(any(PurchaseOrderStatusChangedEvent.class));
        }

        @Test
        @DisplayName("should throw InvalidStatusTransitionException on invalid transition")
        void invalidTransitionThrows() {
            var alreadySent = PurchaseOrder.builder()
                    .id(poId)
                    .orderNumber("PO-001")
                    .supplier(supplier)
                    .companyStore(store)
                    .paymentMethod(paymentMethod)
                    .status(PurchaseOrderState.SENT)
                    .enabled(true)
                    .build();

            var request = new UpdatePurchaseOrderStatusRequest(draftStatusId);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(alreadySent));
            catalogRow(draftStatus);

            assertThatThrownBy(() -> service.updatePurchaseOrderStatus(poId, request))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("SENT → DRAFT");
        }

        @Test
        @DisplayName("should throw when status has wrong type")
        void wrongStatusTypeThrows() {
            var request = new UpdatePurchaseOrderStatusRequest(pendingStatus.getId());
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            catalogRow(pendingStatus);

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

    // ─── enablePurchaseOrder ────────────────────────────────────────────

    @Nested
    @DisplayName("enablePurchaseOrder")
    class EnablePurchaseOrderTests {

        @Test
        @DisplayName("should re-enable the order and its lines and read the derived money back")
        void reEnablesTheOrderAndItsLines() {
            // Stated explicitly because both builders default `enabled` to true: a case that left them alone
            // would pass without the enable doing anything.
            purchaseOrder.setEnabled(false);
            detail.setEnabled(false);
            purchaseOrder.getDetails().add(detail);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            service.enablePurchaseOrder(poId);

            assertThat(purchaseOrder.getEnabled())
                    .as("the order is enabled again")
                    .isTrue();
            assertThat(detail.getEnabled()).as("the line is enabled again").isTrue();
            // Re-enabling a line is a write of it, so the document's money moved under the V93 trigger and the
            // instance `findById` loaded carries the stale number: the write path reads both back (S5).
            verify(entityManager).refresh(any(PurchaseOrder.class));
            verify(entityManager).refresh(any(PurchaseOrderDetail.class));
            verify(purchaseOrderRepository).save(purchaseOrder);
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
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(2),
                    new BigDecimal("50.00"),
                    "Note",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(pendingStatus);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var d = inv.getArgument(0, PurchaseOrderDetail.class);
                d.setId(detailId);
                // The trigger's answer to 2 x 50.00. The decimals are what tells the two apart: a service that
                // stated the money itself would answer 100.00, because that is what the deleted lineTotal did.
                d.setTotal(new BigDecimal("100.0000"));
                return d;
            });

            var result = service.addPurchaseOrderDetail(poId, detailReq);

            assertThat(result).isNotNull();
            assertThat(result.total()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(result.productName()).isEqualTo("Test Product");
            // The response is only able to state the row's number because the write path asked for it: the
            // entity in the persistence context carries null until it is refreshed (S5).
            verify(entityManager).refresh(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should throw ProductNotFoundException when product missing")
        void throwsWhenProductMissing() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId, null, BigDecimal.valueOf(2), new BigDecimal("50.00"), "Note", pendingStatus.getId());

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
        @DisplayName("should update detail fields and read the derived total back")
        void updatesDetailAndReadsBackTheDerivedTotal() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(10),
                    new BigDecimal("25.00"),
                    "Updated",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(pendingStatus);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var d = inv.getArgument(0, PurchaseOrderDetail.class);
                // The fixture carries the old 500.00; the trigger is what answers 10 x 25.00 here.
                d.setTotal(new BigDecimal("250.0000"));
                return d;
            });

            var result = service.updatePurchaseOrderDetail(poId, detailId, updatedReq);

            assertThat(result.quantity()).isEqualByComparingTo("10");
            assertThat(result.total()).isEqualByComparingTo(new BigDecimal("250.0000"));
            verify(entityManager).refresh(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should throw PurchaseOrderDetailNotFoundException when detail missing")
        void throwsWhenDetailMissing() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId, null, BigDecimal.valueOf(10), new BigDecimal("25.00"), "Updated", pendingStatus.getId());

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

    // ─── Product variant resolution ─────────────────────────────────────

    @Nested
    @DisplayName("product variant resolution")
    class ProductVariantResolutionTests {

        @Test
        @DisplayName("createPurchaseOrder rejects a null productVariantId: the legacy null tolerance was dropped")
        void createWithNullVariantIsRejected() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    null,
                    BigDecimal.valueOf(3),
                    new BigDecimal("150.00"),
                    "Detail comments",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);

            // The column is NOT NULL since V14 and the request is @NotNull, so the null branch of
            // resolveProductVariant was removed: a null id now fails the resolution instead of
            // silently leaving the relation unset.
            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(ProductVariantNotFoundException.class);

            verify(productVariantRepository, never()).save(any());
        }

        @Test
        @DisplayName("createPurchaseOrder resolves and sets a valid variant")
        void createWithValidVariantSetsRelation() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("150.00"),
                    "Detail comments",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            catalogRow(pendingStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);

            var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(captor.capture())).thenAnswer(inv -> {
                var po = captor.getValue();
                po.setId(poId);
                return po;
            });

            service.createPurchaseOrder(request);

            assertThat(captor.getValue().getDetails().getFirst().getProductVariant())
                    .isSameAs(productVariant);
        }

        @Test
        @DisplayName("createPurchaseOrder rejects a variant from another product or store")
        void createWithForeignVariantThrows() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("150.00"),
                    "Detail comments",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createPurchaseOrder(request))
                    .isInstanceOf(ProductVariantNotFoundException.class)
                    .hasMessageContaining(variantId.toString());
        }

        @Test
        @DisplayName("addPurchaseOrderDetail resolves and sets a valid variant")
        void addDetailWithValidVariantSetsRelation() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(2),
                    new BigDecimal("50.00"),
                    "Note",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(pendingStatus);

            var captor = ArgumentCaptor.forClass(PurchaseOrderDetail.class);
            when(detailRepository.save(captor.capture())).thenAnswer(inv -> {
                var d = captor.getValue();
                d.setId(detailId);
                return d;
            });

            service.addPurchaseOrderDetail(poId, detailReq);

            assertThat(captor.getValue().getProductVariant()).isSameAs(productVariant);
        }

        @Test
        @DisplayName("addPurchaseOrderDetail rejects a foreign variant")
        void addDetailWithForeignVariantThrows() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(2),
                    new BigDecimal("50.00"),
                    "Note",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addPurchaseOrderDetail(poId, detailReq))
                    .isInstanceOf(ProductVariantNotFoundException.class);
        }

        @Test
        @DisplayName("updatePurchaseOrderDetail rejects a foreign variant")
        void updateDetailWithForeignVariantThrows() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(10),
                    new BigDecimal("25.00"),
                    "Updated",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, updatedReq))
                    .isInstanceOf(ProductVariantNotFoundException.class);
        }

        @Test
        @DisplayName("updatePurchaseOrderDetail sets a valid variant on the persisted entity")
        void updateDetailWithValidVariantSetsRelation() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(10),
                    new BigDecimal("25.00"),
                    "Updated",
                    pendingStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(pendingStatus);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            service.updatePurchaseOrderDetail(poId, detailId, updatedReq);

            assertThat(detail.getProductVariant()).isSameAs(productVariant);
        }
    }

    // ─── requireReceivable ───────────────────────────────────────────────

    @Nested
    @DisplayName("requireReceivable")
    class RequireReceivableTests {

        @Test
        @DisplayName("should reject Draft, Sent, Received and Rejected headers")
        void rejectsNonReceivableStatuses() {
            for (var state : List.of(
                    PurchaseOrderState.DRAFT,
                    PurchaseOrderState.SENT,
                    PurchaseOrderState.RECEIVED,
                    PurchaseOrderState.REJECTED)) {

                purchaseOrder.setStatus(state);
                assertThatThrownBy(() -> service.requireReceivable(purchaseOrder))
                        .as("a header in %s cannot receive", state)
                        .isInstanceOf(InvalidStatusTransitionException.class)
                        .hasMessageContaining(state.name())
                        .hasMessageContaining("reception");
            }
        }

        @Test
        @DisplayName("should allow Accepted and In Transit headers")
        void allowsAcceptedAndInTransit() {
            purchaseOrder.setStatus(PurchaseOrderState.ACCEPTED);
            assertThatCode(() -> service.requireReceivable(purchaseOrder)).doesNotThrowAnyException();
            purchaseOrder.setStatus(PurchaseOrderState.IN_TRANSIT);
            assertThatCode(() -> service.requireReceivable(purchaseOrder)).doesNotThrowAnyException();
        }
    }

    // ─── requireDetailReceivable ─────────────────────────────────────────

    /**
     * Whole-matrix unit coverage for the detail-level guard. It is deliberately direct: the guard
     * previously relied on the goods-receipt integration case, which exercised only the Cancelled
     * line and therefore never pinned the terminal {@code Received} regression.
     */
    @Nested
    @DisplayName("requireDetailReceivable")
    class RequireDetailReceivableTests {

        @Test
        @DisplayName("should admit every status that can still reach Partial Received")
        void admitsNonTerminalStatuses() {
            for (var state : List.of(
                    PurchaseOrderDetailState.PENDING,
                    PurchaseOrderDetailState.IN_PROCESS,
                    PurchaseOrderDetailState.IN_TRANSIT,
                    PurchaseOrderDetailState.PARTIAL_RECEIVED)) {

                detail.setStatus(state);
                assertThatCode(() -> service.requireDetailReceivable(detail))
                        .as("line in %s must be receivable", state)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("should reject the terminal statuses: Received, Rejected and Cancelled")
        void rejectsTerminalStatuses() {
            for (var state : List.of(
                    PurchaseOrderDetailState.RECEIVED,
                    PurchaseOrderDetailState.REJECTED,
                    PurchaseOrderDetailState.CANCELLED)) {

                detail.setStatus(state);
                assertThatThrownBy(() -> service.requireDetailReceivable(detail))
                        .as("line in %s must be unreceivable", state)
                        .isInstanceOf(InvalidStatusTransitionException.class)
                        .hasMessageContaining(state.name())
                        .hasMessageContaining("reception");
            }
        }

        @Test
        @DisplayName("should reject a terminal Received line even though the reachability walk is reflexive")
        void terminalReceivedLineIsRejected() {
            // The regression that motivated this guard: receivedQuantity is below quantity, so the
            // mutator would derive Partial Received, but the reflexive walk made the old
            // "... || isDetailStatusReachable(current, \"Received\")" predicate admit the terminal
            // Received line and let the write happen before the mutator threw a 409.
            detail.setStatus(PurchaseOrderDetailState.RECEIVED);
            detail.setReceivedQuantity(BigDecimal.valueOf(0));

            assertThatThrownBy(() -> service.requireDetailReceivable(detail))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("RECEIVED")
                    .hasMessageContaining("reception");
        }
    }

    // ─── registerReceivedQuantity ────────────────────────────────────────

    @Nested
    @DisplayName("registerReceivedQuantity")
    class RegisterReceivedQuantityTests {

        private void stubReceivableHeaderAndDetail(Status detailStatus) {
            purchaseOrder.setStatus(PurchaseOrderState.ACCEPTED);
            detail.setStatus(detailStateOf(detailStatus));
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
        }

        private void stubDetailSave() {
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);
        }

        @Test
        @DisplayName("should derive Partial Received from Pending on a partial quantity")
        void partialFromPending() {
            stubReceivableHeaderAndDetail(pendingStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3));

            assertThat(result.statusName()).isEqualTo("Partial Received");
            assertThat(result.receivedQuantity()).isEqualByComparingTo("3");
            assertThat(detail.getStatus()).isSameAs(PurchaseOrderDetailState.PARTIAL_RECEIVED);
        }

        @Test
        @DisplayName("should derive Partial Received from In Process on a partial quantity")
        void partialFromInProcess() {
            stubReceivableHeaderAndDetail(inProcessStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3));

            assertThat(result.statusName()).isEqualTo("Partial Received");
        }

        @Test
        @DisplayName("should derive Partial Received from In Transit on a partial quantity")
        void partialFromInTransit() {
            stubReceivableHeaderAndDetail(inTransitStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3));

            assertThat(result.statusName()).isEqualTo("Partial Received");
        }

        @Test
        @DisplayName("should keep Partial Received when another partial quantity arrives")
        void partialFromPartialReceived() {
            stubReceivableHeaderAndDetail(partialReceivedStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3));

            assertThat(result.statusName()).isEqualTo("Partial Received");
        }

        @Test
        @DisplayName("should derive Received from Pending when the full quantity arrives")
        void receivedFromPending() {
            stubReceivableHeaderAndDetail(pendingStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(5));

            assertThat(result.statusName()).isEqualTo("Received");
            assertThat(detail.getReceivedQuantity()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("should derive Received from In Transit when the full quantity arrives")
        void receivedFromInTransit() {
            stubReceivableHeaderAndDetail(inTransitStatus);
            stubDetailSave();

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(5));

            assertThat(result.statusName()).isEqualTo("Received");
        }

        @Test
        @DisplayName("should reject reception on a terminal Rejected line")
        void rejectedLineIsTerminal() {
            stubReceivableHeaderAndDetail(rejectedStatus);

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3)))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("REJECTED");

            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should reject reception on a terminal Cancelled line")
        void cancelledLineIsTerminal() {
            stubReceivableHeaderAndDetail(cancelledStatus);

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3)))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("CANCELLED");

            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should reject a non-positive received quantity")
        void nonPositiveQuantityThrows() {
            stubReceivableHeaderAndDetail(inTransitStatus);

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than 0");

            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should reject a received quantity above the ordered quantity")
        void overOrderedQuantityThrows() {
            stubReceivableHeaderAndDetail(inTransitStatus);

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(6)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed");

            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should promote an In Transit header to Received when every enabled line is Received")
        void promotesHeaderWhenFullyReceived() {
            purchaseOrder.setStatus(PurchaseOrderState.IN_TRANSIT);
            detail.setStatus(PurchaseOrderDetailState.IN_TRANSIT);
            purchaseOrder.getDetails().clear();
            purchaseOrder.getDetails().add(detail);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            var result = service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(5));

            assertThat(result.statusName()).isEqualTo("Received");
            assertThat(purchaseOrder.getStatus()).isSameAs(PurchaseOrderState.RECEIVED);
            verify(eventPublisher)
                    .publishEvent(argThat(event -> event instanceof PurchaseOrderStatusChangedEvent statusEvent
                            && "IN_TRANSIT".equals(statusEvent.getFromStatus())
                            && "RECEIVED".equals(statusEvent.getToStatus())));
        }

        @Test
        @DisplayName("should keep an In Transit header while another enabled line is still pending")
        void keepsHeaderWhileSiblingLinePending() {
            var sibling = PurchaseOrderDetail.builder()
                    .id(UUID.randomUUID())
                    .purchaseOrder(purchaseOrder)
                    .product(product)
                    .quantity(BigDecimal.valueOf(2))
                    .unitPrice(new BigDecimal("10.00"))
                    .total(new BigDecimal("20.00"))
                    .receivedQuantity(BigDecimal.ZERO)
                    .status(PurchaseOrderDetailState.PENDING)
                    .enabled(true)
                    .build();

            purchaseOrder.setStatus(PurchaseOrderState.IN_TRANSIT);
            detail.setStatus(PurchaseOrderDetailState.IN_TRANSIT);
            purchaseOrder.getDetails().clear();
            purchaseOrder.getDetails().add(detail);
            purchaseOrder.getDetails().add(sibling);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(5));

            assertThat(purchaseOrder.getStatus()).isSameAs(PurchaseOrderState.IN_TRANSIT);
        }

        @Test
        @DisplayName("should publish the event with the real previous/new status and both quantities")
        void publishesEventWithRealQuantities() {
            stubReceivableHeaderAndDetail(inProcessStatus);
            stubDetailSave();

            service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3));

            var captor = ArgumentCaptor.forClass(PurchaseOrderDetailStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            var event = captor.getValue();
            assertThat(event.getFromStatus()).isEqualTo("IN_PROCESS");
            assertThat(event.getToStatus()).isEqualTo("PARTIAL_RECEIVED");
            assertThat(event.getReceivedQuantity()).isEqualByComparingTo("3");
            assertThat(event.getOrderedQuantity()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("should reject a detail that belongs to another purchase order and save nothing")
        void detailFromAnotherOrderThrows() {
            stubReceivableHeaderAndDetail(pendingStatus);
            var otherOrder = PurchaseOrder.builder()
                    .id(UUID.randomUUID())
                    .orderNumber("PO-OTHER-00001")
                    .supplier(supplier)
                    .companyStore(store)
                    .paymentMethod(paymentMethod)
                    .status(PurchaseOrderState.ACCEPTED)
                    .enabled(true)
                    .build();
            detail.setPurchaseOrder(otherOrder);

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(3)))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);

            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
        }

        @Test
        @DisplayName("should throw PurchaseOrderNotFoundException when the order is missing")
        void missingOrderThrows() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(1)))
                    .isInstanceOf(PurchaseOrderNotFoundException.class);
        }

        @Test
        @DisplayName("should throw PurchaseOrderDetailNotFoundException when the detail is missing")
        void missingDetailThrows() {
            purchaseOrder.setStatus(PurchaseOrderState.ACCEPTED);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(1)))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
        }
    }

    // ─── The typed state machine and its history ───────────────────────

    /**
     * ADR-0004 decisions 2–5 in the purchase module: one guard decides every move, every move appends
     * exactly one {@code status_transition} row, a refused move writes nothing at all, and the two derived
     * paths — the receipt's line promotion and the header promotion it triggers — append like any other path.
     *
     * <p><b>Why the recorder is asserted and not the table.</b> What this class owns is <em>which</em> rows a
     * path asks for: the family, the document, both ends of the move and the reason. Whether the row lands, and
     * whether a commit without it is refused, is the database's own contract —
     * {@code StatusTransitionRecorderIntegrationTest} for the writer and
     * {@code PurchaseOrderStatusHistoryMigrationIntegrationTest} for the pair of deferred triggers.</p>
     *
     * <p><b>The receipt cases are the old walk, pinned one step at a time.</b> `DETAIL_TRANSITIONS` used to be
     * walked as a reachability graph, so a line could arrive at {@code Partial Received} straight from
     * {@code Pending} — skipping {@code In Process} and {@code In Transit} — and the guard now walks the enum's
     * successors instead of a string map. Each case below is one source state of that walk, so a change in the
     * machine that quietly narrows what a receipt may do fails here instead of in a warehouse.</p>
     */
    @Nested
    @DisplayName("the typed machine and its history")
    class TypedStateMachineTests {

        @Test
        @DisplayName("creation appends the order's row and one row per line it creates")
        @SuppressWarnings("unchecked")
        void creationAppendsTheDocumentsRows() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("150.00"),
                    "Note",
                    pendingStatus.getId());
            var request =
                    new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", List.of(detailReq));

            catalogRow(draftStatus);
            catalogRow(pendingStatus);
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });
            when(detailRepository.saveAll(any())).thenAnswer(inv -> {
                List<PurchaseOrderDetail> lines = inv.getArgument(0);
                lines.forEach(line -> line.setId(detailId));
                return lines;
            });

            service.createPurchaseOrder(request);

            verify(statusTransitionRecorder)
                    .recordCreation(StatusSubject.PURCHASE_ORDER, companyId, poId, PurchaseOrderState.DRAFT);
            verify(statusTransitionRecorder)
                    .recordCreation(
                            StatusSubject.PURCHASE_ORDER_DETAIL, companyId, detailId, PurchaseOrderDetailState.PENDING);
        }

        @Test
        @DisplayName("a move appends exactly one row, with both ends named as the constants")
        void aMoveAppendsExactlyOneRow() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            catalogRow(sentStatus);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);

            service.updatePurchaseOrderStatus(poId, new UpdatePurchaseOrderStatusRequest(sentStatus.getId()));

            assertThat(purchaseOrder.getStatus()).isSameAs(PurchaseOrderState.SENT);
            verify(statusTransitionRecorder)
                    .recordMove(
                            StatusSubject.PURCHASE_ORDER,
                            companyId,
                            poId,
                            PurchaseOrderState.DRAFT,
                            PurchaseOrderState.SENT,
                            "status update");
            verify(statusTransitionRecorder, never()).recordCreation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a forbidden move is a 409 that writes nothing: no row, no save and no history")
        void aForbiddenMoveWritesNothing() {
            var alreadySent = PurchaseOrder.builder()
                    .id(poId)
                    .orderNumber("PO-OTHER-00002")
                    .supplier(supplier)
                    .companyStore(store)
                    .paymentMethod(paymentMethod)
                    .status(PurchaseOrderState.SENT)
                    .enabled(true)
                    .build();
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(alreadySent));
            catalogRow(draftStatus);

            assertThatThrownBy(() -> service.updatePurchaseOrderStatus(
                            poId, new UpdatePurchaseOrderStatusRequest(draftStatusId)))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("SENT → DRAFT");

            assertThat(alreadySent.getStatus()).isSameAs(PurchaseOrderState.SENT);
            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
            verifyNoInteractions(statusTransitionRecorder);
        }

        @Test
        @DisplayName("a receipt moves Pending straight to Partial Received, as the old walk allowed")
        void receiptMovesPendingToPartialReceived() {
            assertReceipt(
                    PurchaseOrderDetailState.PENDING,
                    pendingStatus,
                    BigDecimal.valueOf(3),
                    PurchaseOrderDetailState.PARTIAL_RECEIVED,
                    partialReceivedStatus);
        }

        @Test
        @DisplayName("a receipt moves In Process to Partial Received")
        void receiptMovesInProcessToPartialReceived() {
            assertReceipt(
                    PurchaseOrderDetailState.IN_PROCESS,
                    inProcessStatus,
                    BigDecimal.valueOf(3),
                    PurchaseOrderDetailState.PARTIAL_RECEIVED,
                    partialReceivedStatus);
        }

        @Test
        @DisplayName("a receipt moves In Transit to Partial Received")
        void receiptMovesInTransitToPartialReceived() {
            assertReceipt(
                    PurchaseOrderDetailState.IN_TRANSIT,
                    inTransitStatus,
                    BigDecimal.valueOf(3),
                    PurchaseOrderDetailState.PARTIAL_RECEIVED,
                    partialReceivedStatus);
        }

        @Test
        @DisplayName("a receipt moves Pending straight to Received when the whole quantity arrives")
        void receiptMovesPendingToReceived() {
            assertReceipt(
                    PurchaseOrderDetailState.PENDING,
                    pendingStatus,
                    BigDecimal.valueOf(5),
                    PurchaseOrderDetailState.RECEIVED,
                    receivedStatus);
        }

        @Test
        @DisplayName("a receipt moves In Transit to Received")
        void receiptMovesInTransitToReceived() {
            assertReceipt(
                    PurchaseOrderDetailState.IN_TRANSIT,
                    inTransitStatus,
                    BigDecimal.valueOf(5),
                    PurchaseOrderDetailState.RECEIVED,
                    receivedStatus);
        }

        @Test
        @DisplayName("a second partial receipt on a partially received line moves nothing and appends nothing")
        void aSecondPartialReceiptAppendsNothing() {
            purchaseOrder.setStatus(PurchaseOrderState.ACCEPTED);
            detail.setStatus(PurchaseOrderDetailState.PARTIAL_RECEIVED);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(2));

            assertThat(detail.getStatus())
                    .as("the state did not move, so there is no move to record: history is a change, not a write")
                    .isSameAs(PurchaseOrderDetailState.PARTIAL_RECEIVED);
            verify(detailRepository).save(detail);
            verifyNoInteractions(statusTransitionRecorder);
        }

        @Test
        @DisplayName("the header promotion appends its own row, in the same transaction as the line's")
        void theHeaderPromotionAppendsItsOwnRow() {
            purchaseOrder.setStatus(PurchaseOrderState.IN_TRANSIT);
            detail.setStatus(PurchaseOrderDetailState.IN_TRANSIT);
            purchaseOrder.getDetails().clear();
            purchaseOrder.getDetails().add(detail);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            service.registerReceivedQuantity(poId, detailId, BigDecimal.valueOf(5));

            assertThat(purchaseOrder.getStatus()).isSameAs(PurchaseOrderState.RECEIVED);
            verify(statusTransitionRecorder)
                    .recordReachableMove(
                            StatusSubject.PURCHASE_ORDER_DETAIL,
                            companyId,
                            detailId,
                            PurchaseOrderDetailState.IN_TRANSIT,
                            PurchaseOrderDetailState.RECEIVED,
                            "receipt");
            verify(statusTransitionRecorder)
                    .recordMove(
                            StatusSubject.PURCHASE_ORDER,
                            companyId,
                            poId,
                            PurchaseOrderState.IN_TRANSIT,
                            PurchaseOrderState.RECEIVED,
                            "all lines received");
        }

        @Test
        @DisplayName("a status_id whose code the enum does not declare is refused, naming the code")
        void aCodeTheEnumLacksIsRefused() {
            var shipped = status(UUID.randomUUID(), "Shipped", "SHIPPED", poStatusType);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            catalogRow(shipped);

            assertThatThrownBy(() -> service.updatePurchaseOrderStatus(
                            poId, new UpdatePurchaseOrderStatusRequest(shipped.getId())))
                    .as("the catalog's code is the authority, and a code no constant spells is a 400 that names it")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SHIPPED")
                    .hasMessageContaining("PurchaseOrderState");

            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
            verifyNoInteractions(statusTransitionRecorder);
        }

        /**
         * One receipt from one source state: the line is moved to {@code to}, the pointer follows it and the
         * history gets exactly the pair of ends the row now carries.
         */
        private void assertReceipt(
                PurchaseOrderDetailState from,
                Status fromRow,
                BigDecimal received,
                PurchaseOrderDetailState to,
                Status toRow) {
            purchaseOrder.setStatus(PurchaseOrderState.ACCEPTED);
            detail.setStatus(from);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findById(detailId)).thenReturn(Optional.of(detail));
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenReturn(detail);

            var result = service.registerReceivedQuantity(poId, detailId, received);

            assertThat(result.statusName()).isEqualTo(toRow.getStatusName());
            assertThat(detail.getStatus()).isSameAs(to);
            verify(statusTransitionRecorder)
                    .recordReachableMove(StatusSubject.PURCHASE_ORDER_DETAIL, companyId, detailId, from, to, "receipt");
        }
    }
}
