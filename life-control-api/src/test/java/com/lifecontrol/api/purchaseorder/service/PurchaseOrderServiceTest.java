package com.lifecontrol.api.purchaseorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.lifecontrol.api.tax.service.TaxRateResolution;
import com.lifecontrol.api.tax.service.TaxRateService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * The resolver, mocked: this suite asserts <b>when</b> the copy asks for a rate and with which date, which is
     * the snapshot rule (S3). What a resolution returns is the tax layer's own contract
     * ({@code TaxRateServiceTest}); what the schema derives from the rate is the migration suite's.
     */
    @Mock
    private TaxRateService taxRateService;

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
                // The document's own date (`V64:187-188`): a purchase order has no `order_date` column, so the
                // tax copy resolves against `created_at`, which a row loaded from the repository always states.
                .createdAt(LocalDateTime.of(2026, 6, 3, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 3, 10, 0))
                .build();
        purchaseOrder.setDetails(new ArrayList<>());

        // Every line copies its product's code. `NA` is the fixture's product and the column's default, so the
        // cases that are not about tax never consult the rate table; the copy's own cases stub this per test.
        lenient()
                .when(productVariantRepository.findProductTaxCodeByVariantId(any()))
                .thenReturn(Optional.of("NA"));

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
                taxRateService,
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

        @Test
        @DisplayName("should create the line in Pending, and consult no catalog row, when the payload omits statusId")
        void createsLineInPendingWhenStatusIdIsOmitted() {
            var detailReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(2),
                    new BigDecimal("50.00"),
                    "Note",
                    null);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var d = inv.getArgument(0, PurchaseOrderDetail.class);
                d.setId(detailId);
                d.setTotal(new BigDecimal("100.0000"));
                return d;
            });

            var result = service.addPurchaseOrderDetail(poId, detailReq);

            // The absence resolves to the constant every line is born in, which is the same state the
            // whole-order create path names for the same absence.
            assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.statusId()).isEqualTo(pendingStatus.getId());
            // ...and it resolves it without a catalog read: a caller that carries no state catalog in hand
            // causes no lookup at all, which is what made the route unusable (S1, D1).
            verify(statusRepository, never()).findWithStatusTypeById(any());
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
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.of(detail));
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
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, updatedReq))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
        }

        @Test
        @DisplayName("should keep the stored status and evaluate no transition when the payload omits statusId")
        void keepsTheStoredStatusWhenStatusIdIsOmitted() {
            // A state that is not the initial one, so a fallback to PENDING would be visible here.
            detail.setStatus(PurchaseOrderDetailState.IN_PROCESS);
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId, variantId, BigDecimal.valueOf(3), new BigDecimal("10.00"), "Edited", null);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId))
                    .thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var d = inv.getArgument(0, PurchaseOrderDetail.class);
                d.setTotal(new BigDecimal("30.0000"));
                return d;
            });

            var result = service.updatePurchaseOrderDetail(poId, detailId, updatedReq);

            assertThat(result.status()).isEqualTo("IN_PROCESS");
            assertThat(result.statusId()).isEqualTo(inProcessStatus.getId());
            assertThat(detail.getStatus()).isEqualTo(PurchaseOrderDetailState.IN_PROCESS);
        }

        @Test
        @DisplayName("should still refuse a state the payload sends that the line cannot reach")
        void refusesAForbiddenExplicitTransition() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    BigDecimal.valueOf(3),
                    new BigDecimal("10.00"),
                    "Edited",
                    receivedStatus.getId());

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId))
                    .thenReturn(Optional.of(detail));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId))
                    .thenReturn(Optional.of(productVariant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variantId, storeId))
                    .thenReturn(true);
            catalogRow(receivedStatus);

            // PENDING reaches IN_PROCESS and CANCELLED, never RECEIVED.
            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, updatedReq))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: PENDING → RECEIVED");
            // Refused before the first setter: the row still states what it stated, and nothing is written.
            assertThat(detail.getQuantity()).isEqualByComparingTo("5");
            assertThat(detail.getUnitPrice()).isEqualByComparingTo("100.00");
            verify(detailRepository, never()).save(any());
        }

        @Test
        @DisplayName("should answer 404 and write nothing when the line belongs to another order")
        void refusesALineOfAnotherOrder() {
            var updatedReq = new PurchaseOrderDetailRequest(
                    productId, variantId, BigDecimal.valueOf(3), new BigDecimal("10.00"), "Edited", null);

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, updatedReq))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
            // The path's second id is part of the lookup, not a decoration: the same line is reachable only
            // through the order that owns it (S3, D3).
            verify(detailRepository).findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId);
            verify(detailRepository, never()).save(any());
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
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.of(detail));

            service.deletePurchaseOrderDetail(poId, detailId);

            assertThat(detail.getEnabled()).isFalse();
            verify(detailRepository).save(detail);
        }

        @Test
        @DisplayName("should throw PurchaseOrderDetailNotFoundException when detail missing")
        void throwsWhenDetailMissing() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePurchaseOrderDetail(poId, detailId))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
        }

        @Test
        @DisplayName("should answer 404 and leave every row alone when the line belongs to another order")
        void refusesALineOfAnotherOrder() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePurchaseOrderDetail(poId, detailId))
                    .isInstanceOf(PurchaseOrderDetailNotFoundException.class);
            verify(detailRepository).findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId);
            verify(detailRepository, never()).save(any());
        }
    }

    // ─── The Draft guard on the three detail writes (S4) ─────────────────

    @Nested
    @DisplayName("detail writes on an order that is not Draft")
    class DetailWriteDraftGuardTests {

        @Test
        @DisplayName("should refuse POST, PUT and DELETE with the transition conflict and save nothing")
        void refusesEveryDetailWriteWhenTheOrderLeftDraft() {
            purchaseOrder.setStatus(PurchaseOrderState.SENT);
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            var detailReq = new PurchaseOrderDetailRequest(
                    productId, variantId, BigDecimal.ONE, new BigDecimal("10.00"), "Note", null);

            // One rule, three routes: the guard is the same call and it answers the same conflict.
            assertThatThrownBy(() -> service.addPurchaseOrderDetail(poId, detailReq))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: SENT → detail mutation");
            assertThatThrownBy(() -> service.updatePurchaseOrderDetail(poId, detailId, detailReq))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: SENT → detail mutation");
            assertThatThrownBy(() -> service.deletePurchaseOrderDetail(poId, detailId))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: SENT → detail mutation");

            // Nothing stored moved: no line was written and none was even read.
            verify(detailRepository, never()).save(any());
            verify(detailRepository, never()).findByIdAndPurchaseOrderIdAndEnabledTrue(any(), any());
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
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.of(detail));
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
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.of(detail));
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

    // ─── The line's tax snapshot (S3, S4 and S5 of odd/tasks/purchase-order-line-tax.md) ───

    /**
     * The copy's halves, as only a unit suite can separate them: <b>when</b> a purchase line asks the
     * product for its code and the rate table for a rate, when it must not, and what it keeps of each answer.
     *
     * <p>A full replacement is where "born" and "edited" stop being the same question: {@code updatePurchaseOrder}
     * soft-deletes the stored lines and writes new rows, so each replacement line takes a create's copy. That is
     * the last case here, pinned as intended after review finding R4-1 rather than left to be rediscovered.</p>
     *
     * <p>What a rate row is and which one a date resolves belongs to {@code TaxRateServiceTest}; what the stored
     * rate becomes on the row — {@code tax_amount} and {@code total} — belongs to
     * {@code PurchaseOrderMoneyMigrationIntegrationTest}, because a trigger does the arithmetic and a mock runs
     * no trigger. This suite pins the asking and the storing.</p>
     */
    @Nested
    @DisplayName("the line's tax snapshot: resolved when a line is born, never when an existing one is edited")
    class LineTaxSnapshotTests {

        @Test
        @DisplayName("creating the order with a line stores the product's code and the document's rate, and a newer"
                + " catalog row never restates them")
        void createWithLines_storesTheProductsCodeAndTheDocumentsRate() {
            var lineReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    new BigDecimal("3.00"),
                    new BigDecimal("150.00"),
                    null,
                    pendingStatus.getId());
            var request = stubCreateOf(List.of(lineReq));
            stubStockedVariant(productVariant);
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("IVA"));
            when(taxRateService.resolve(eq(companyId), eq("IVA"), any()))
                    .thenReturn(Optional.of(new TaxRateResolution(
                            UUID.randomUUID(), "IVA", new BigDecimal("0.160000"), LocalDate.of(2026, 1, 1))));
            var written = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(written.capture())).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });

            var result = service.createPurchaseOrder(request);

            var stored = written.getValue().getDetails().getFirst();
            assertThat(stored.getTaxCode())
                    .as("the code comes from the product through the variant; the request has no field for one (S2)")
                    .isEqualTo("IVA");
            assertThat(stored.getTaxRate())
                    .as("the rate the document's date resolved is copied onto the line instead of being joined "
                            + "at read time (S3)")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            assertThat(result.details().getFirst().taxCode()).isEqualTo("IVA");
            assertThat(result.details().getFirst().taxRate())
                    .as("the response states the pair the row keeps (S8)")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            // The date is the document's own — its `created_at`, because a purchase order has no `order_date`
            // column (`V64:187-188`) — and not one the caller could state.
            verify(taxRateService).resolve(eq(companyId), eq("IVA"), any());

            // The catalog moves: the company publishes a newer IVA row at 8%. A resolver asked at read time would
            // answer the next question with this rate, and the historical purchase would be restated by a
            // catalog change — the defect this half of the case exists for.
            //
            // Lenient because a correct implementation never asks again, and never asking is precisely what the
            // assertions below measure; a strict stub would fail the case for the stub it did not need.
            lenient()
                    .when(taxRateService.resolve(eq(companyId), eq("IVA"), any()))
                    .thenReturn(Optional.of(new TaxRateResolution(
                            UUID.randomUUID(), "IVA", new BigDecimal("0.080000"), LocalDate.of(2026, 7, 1))));
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(written.getValue()));
            when(detailRepository.findByPurchaseOrderIdAndEnabledTrue(poId)).thenReturn(List.of(stored));

            var reRead = service.getPurchaseOrderDetails(poId);

            assertThat(reRead.getFirst().taxCode()).isEqualTo("IVA");
            assertThat(reRead.getFirst().taxRate())
                    .as("the row still carries the rate it was written with, so reading it again cannot pick up "
                            + "the 8% published afterwards")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            verify(taxRateService, times(1)).resolve(any(), eq("IVA"), any());
        }

        @Test
        @DisplayName("every zero-by-definition code answers 0 without asking the rate table at all (S4)")
        void createWithAZeroByDefinitionCode_neverAsksTheRateTable() {
            var lineReq = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    new BigDecimal("3.00"),
                    new BigDecimal("150.00"),
                    null,
                    pendingStatus.getId());
            var request = stubCreateOf(List.of(lineReq));
            stubStockedVariant(productVariant);
            var written = ArgumentCaptor.forClass(PurchaseOrder.class);
            when(purchaseOrderRepository.save(written.capture())).thenAnswer(inv -> {
                var po = inv.getArgument(0, PurchaseOrder.class);
                po.setId(poId);
                return po;
            });

            for (var code : List.of("NA", "TASA_0", "EXENTO")) {
                when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                        .thenReturn(Optional.of(code));

                var result = service.createPurchaseOrder(request);

                var stored = written.getValue().getDetails().getFirst();
                assertThat(stored.getTaxCode())
                        .as("the code travels even though the rate is zero: Exento and Tasa 0% are two different "
                                + "zeroes and only the code tells them apart")
                        .isEqualTo(code);
                assertThat(stored.getTaxRate())
                        .as("%s is zero by definition, so the line is written with a 0 and not with a rate", code)
                        .isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(result.details().getFirst().taxRate()).isEqualByComparingTo(BigDecimal.ZERO);
                // The amount is the schema's: round(subtotal * 0, 4) = 0 on a rate this layer stated. The zero is
                // proven on the row by the migration suite; what this suite can prove is that Java invents no
                // amount of its own, which a second writer would.
                assertThat(stored.getTaxAmount()).isNull();
            }

            // The interaction, not just the number: a 0 that came from a lookup is a code whose missing rate row
            // silently untaxes, which is the failure S4 and S5 exist to separate.
            verifyNoInteractions(taxRateService);
        }

        @Test
        @DisplayName("a taxable code with no rate on the document's date refuses the create-with-lines request,"
                + " naming the code and the date, and writes nothing")
        void createWithLines_withATaxableCodeAndNoRate_isRefusedAndWritesNothing() {
            // The path that matters most: two lines, so the first one is already built when the second one is
            // refused. A 400 thrown after the first write is the defect this case exists for.
            var firstVariant = productVariant;
            var secondVariant = variantNamed("Second Variant");
            var firstLine = new PurchaseOrderDetailRequest(
                    productId,
                    firstVariant.getId(),
                    new BigDecimal("1.00"),
                    new BigDecimal("10.00"),
                    null,
                    pendingStatus.getId());
            var secondLine = new PurchaseOrderDetailRequest(
                    productId,
                    secondVariant.getId(),
                    new BigDecimal("2.00"),
                    new BigDecimal("20.00"),
                    null,
                    pendingStatus.getId());
            var request = stubCreateOf(List.of(firstLine, secondLine));
            stubStockedVariant(firstVariant);
            stubStockedVariant(secondVariant);
            // The first line is untaxed by definition and asks nothing; the second declares IVA and the company
            // has stated no enabled rate for it on the document's date.
            when(productVariantRepository.findProductTaxCodeByVariantId(firstVariant.getId()))
                    .thenReturn(Optional.of("TASA_0"));
            when(productVariantRepository.findProductTaxCodeByVariantId(secondVariant.getId()))
                    .thenReturn(Optional.of("IVA"));
            when(taxRateService.resolve(eq(companyId), eq("IVA"), any())).thenReturn(Optional.empty());

            var refused = catchThrowable(() -> service.createPurchaseOrder(request));

            assertThat(refused)
                    .as("the handler maps this class to 400, the shape the currency path already uses for the "
                            + "same sentence: a value the catalog does not back is the caller's to fix (S5)")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IVA");
            var askedOn = ArgumentCaptor.forClass(LocalDate.class);
            verify(taxRateService).resolve(eq(companyId), eq("IVA"), askedOn.capture());
            assertThat(refused.getMessage())
                    .as("the refusal names the date it looked for, so the operator knows which row to publish")
                    .contains(askedOn.getValue().toString());

            // Nothing is stored: no order row, no line row, no history and no event. The order is written after
            // every line is built and the lines after that, which is what makes the refusal a refusal.
            verify(purchaseOrderRepository, never()).save(any(PurchaseOrder.class));
            verify(detailRepository, never()).save(any(PurchaseOrderDetail.class));
            verify(detailRepository, never()).saveAll(any());
            verifyNoInteractions(statusTransitionRecorder);
            verify(eventPublisher, never()).publishEvent(any());
            // The one thing the refusal does NOT give back, asserted rather than glossed: ADR-0003 decision 4
            // allocates the number before the lines are resolved, so a refused request has already taken a value
            // from the sequence. `createPurchaseOrder` records that as the cost the path cannot avoid; the day
            // the allocation moves below the line loop this assertion fails and the record changes with it.
            verify(documentSequenceAllocator).allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString());
        }

        @Test
        @DisplayName("editing quantity and price keeps the stored pair and re-derives from it; only a line moved to"
                + " another variant resolves again")
        void updateDetail_keepsTheStoredPairAndReResolvesOnlyWhenTheArticleChanges() {
            var taxedLine = PurchaseOrderDetail.builder()
                    .id(detailId)
                    .purchaseOrder(purchaseOrder)
                    .product(product)
                    .productVariant(productVariant)
                    .quantity(new BigDecimal("2.00"))
                    .unitPrice(new BigDecimal("100.00"))
                    .taxCode("IVA")
                    .taxRate(new BigDecimal("0.160000"))
                    .receivedQuantity(BigDecimal.ZERO)
                    .status(PurchaseOrderDetailState.PENDING)
                    .enabled(true)
                    .build();

            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(detailRepository.findByIdAndPurchaseOrderIdAndEnabledTrue(detailId, poId)).thenReturn(Optional.of(taxedLine));
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            stubStockedVariant(productVariant);
            catalogRow(pendingStatus);
            when(detailRepository.save(any(PurchaseOrderDetail.class))).thenAnswer(inv -> {
                var line = inv.getArgument(0, PurchaseOrderDetail.class);
                // The trigger's answer to 3 x 100.00 at the STORED 16%: 300.00 + 48.00. A re-resolution at the 8%
                // the catalog states afterwards would answer 324.0000, and that difference is what the assertion
                // below separates — the rate the derivation reads has to be the one the row already carries.
                line.setTotal(new BigDecimal("348.0000"));
                return line;
            });

            var result = service.updatePurchaseOrderDetail(
                    poId,
                    detailId,
                    new PurchaseOrderDetailRequest(
                            productId,
                            variantId,
                            new BigDecimal("3.00"),
                            new BigDecimal("100.00"),
                            null,
                            pendingStatus.getId()));

            assertThat(result.taxCode()).isEqualTo("IVA");
            assertThat(result.taxRate()).isEqualByComparingTo(new BigDecimal("0.160000"));
            assertThat(result.total())
                    .as("the amounts come from the stored rate: the edit moved the quantity, not the snapshot")
                    .isEqualByComparingTo(new BigDecimal("348.0000"));
            assertThat(taxedLine.getTaxCode()).isEqualTo("IVA");
            assertThat(taxedLine.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.160000"));
            // Neither the rate table nor the product is asked again: the row stays the same article, so its pair
            // is the snapshot of the document that created it (S3).
            verifyNoInteractions(taxRateService);
            verify(productVariantRepository, never()).findProductTaxCodeByVariantId(any());
            // The derived pair is read back, because the numbers the response states are the row's and not the
            // ones this instance carried before the write.
            verify(entityManager).refresh(any(PurchaseOrderDetail.class));

            // ── The single exception: the line stops being the same article ──
            var otherVariant = variantNamed("Other Variant");
            stubStockedVariant(otherVariant);
            when(productVariantRepository.findProductTaxCodeByVariantId(otherVariant.getId()))
                    .thenReturn(Optional.of("IVA"));
            when(taxRateService.resolve(eq(companyId), eq("IVA"), any()))
                    .thenReturn(Optional.of(new TaxRateResolution(
                            UUID.randomUUID(), "IVA", new BigDecimal("0.080000"), LocalDate.of(2026, 6, 1))));

            service.updatePurchaseOrderDetail(
                    poId,
                    detailId,
                    new PurchaseOrderDetailRequest(
                            productId,
                            otherVariant.getId(),
                            new BigDecimal("3.00"),
                            new BigDecimal("100.00"),
                            null,
                            pendingStatus.getId()));

            assertThat(taxedLine.getTaxCode()).isEqualTo("IVA");
            assertThat(taxedLine.getTaxRate())
                    .as("a line moved to another variant is no longer the article whose tax was copied, so the "
                            + "copy is taken again — the rule sales states at `SalesOrderService:380-384`")
                    .isEqualByComparingTo(new BigDecimal("0.080000"));
            assertThat(taxedLine.getProductVariant()).isSameAs(otherVariant);
            // And it is the DOCUMENT's date that is resolved for, never today's: an edit does not move the
            // document's period, so it cannot move the rate either.
            verify(taxRateService).resolve(companyId, "IVA", LocalDate.of(2026, 6, 3));
        }

        /**
         * <b>Decision (review finding R4-1, kept as intended and specified rather than silently kept).</b> A full
         * replacement — {@code updatePurchaseOrder}, the PUT that re-sends the document's whole line set —
         * soft-deletes the stored rows and creates <b>new</b> ones, so every replacement line copies what a created
         * line copies: the product's <b>current</b> {@code tax_code} and the rate in effect on the order's
         * {@code created_at}. It is <b>not</b> the "editing does not re-resolve" rule, which governs
         * {@code updatePurchaseOrderDetail} on an existing row (the case above), because that row is the one the
         * document already froze and this one has never existed.
         *
         * <p>Two conditions move the snapshot, and both are named in the spec as intended: the product's
         * {@code tax_code} changed, or a {@code tax_rate} row with {@code effective_from <= created_at} was
         * published <b>after</b> the order was created. The reassuring half is what bounds the damage, so the case
         * pins it first: a rate published effective <b>after</b> {@code created_at} is invisible to the copy, so a
         * normal rate change does not restate history.</p>
         */
        @Test
        @DisplayName("replacing the whole line set re-copies each new row from the product's current code and the"
                + " rate in effect on the document's date: a rate published afterwards never moves it, a changed"
                + " code does (R4-1)")
        void replacingTheLines_reCopiesFromTheProductsCurrentCodeAndTheDocumentsOwnDate() {
            // The line the order was created with: IVA at 16%, the pair a created line stores.
            var storedLine = PurchaseOrderDetail.builder()
                    .id(detailId)
                    .purchaseOrder(purchaseOrder)
                    .product(product)
                    .productVariant(productVariant)
                    .quantity(new BigDecimal("3.00"))
                    .unitPrice(new BigDecimal("150.00"))
                    .taxCode("IVA")
                    .taxRate(new BigDecimal("0.160000"))
                    .receivedQuantity(BigDecimal.ZERO)
                    .status(PurchaseOrderDetailState.PENDING)
                    .enabled(true)
                    .build();
            purchaseOrder.getDetails().add(storedLine);

            // The catalog the company has published: IVA at 16% since 2026-01-01, and a 5% row effective
            // 2026-07-01 — after the order's own date, 2026-06-03 (`purchaseOrder.createdAt` in the fixture).
            //
            // The stub answers by the DATE it is asked for, so an implementation that resolved against today, or
            // against the newest row, answers 0.05 and the assertion below sees it. Nothing else in the fixture
            // can produce 0.16, which is what keeps the reassuring half from being decorative.
            var inEffectOnTheDocumentDate = new TaxRateResolution(
                    UUID.randomUUID(), "IVA", new BigDecimal("0.160000"), LocalDate.of(2026, 1, 1));
            var publishedAfterwards = new TaxRateResolution(
                    UUID.randomUUID(), "IVA", new BigDecimal("0.050000"), LocalDate.of(2026, 7, 1));
            when(taxRateService.resolve(eq(companyId), eq("IVA"), any())).thenAnswer(inv -> {
                LocalDate askedOn = inv.getArgument(2);
                return askedOn.isBefore(publishedAfterwards.effectiveFrom())
                        ? Optional.of(inEffectOnTheDocumentDate)
                        : Optional.of(publishedAfterwards);
            });
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("IVA"));

            stubReplacementUpdate();
            var sameLine = new PurchaseOrderDetailRequest(
                    productId,
                    variantId,
                    new BigDecimal("3.00"),
                    new BigDecimal("150.00"),
                    null,
                    pendingStatus.getId());
            var request = new PurchaseOrderRequest(
                    supplierId, storeId, pmId, draftStatusId, "Same line, sent again", List.of(sameLine));
            var written = ArgumentCaptor.forClass(PurchaseOrderDetail.class);
            when(detailRepository.save(written.capture())).thenAnswer(inv -> inv.getArgument(0));

            service.updatePurchaseOrder(poId, request);

            // ── Half 1: a rate published after `created_at` does not move the snapshot ──
            var replacement = written.getAllValues().getLast();
            assertThat(replacement)
                    .as("a full replacement writes a NEW row; the stored one is soft-deleted, which is exactly why "
                            + "this copy is a create's copy and not an edit's")
                    .isNotSameAs(storedLine);
            assertThat(storedLine.getEnabled())
                    .as("the row the document was created with is replaced, not edited")
                    .isFalse();
            assertThat(replacement.getTaxCode()).isEqualTo("IVA");
            assertThat(replacement.getTaxRate())
                    .as("the 5% row is effective from 2026-07-01 and the order was created on 2026-06-03, so a "
                            + "normal rate change does not restate a historical document")
                    .isEqualByComparingTo(new BigDecimal("0.160000"));
            var askedOn = ArgumentCaptor.forClass(LocalDate.class);
            verify(taxRateService).resolve(eq(companyId), eq("IVA"), askedOn.capture());
            assertThat(askedOn.getValue())
                    .as("the date is the document's own `created_at`, not the instant this edit runs")
                    .isEqualTo(LocalDate.of(2026, 6, 3));

            // ── Half 2: a changed product code does move the snapshot — what R4-1 flagged, now pinned ──
            // The product stops being taxable. The next replacement line is a new row too, so it reads the
            // product's current code instead of inheriting the one the replaced row carried.
            when(productVariantRepository.findProductTaxCodeByVariantId(variantId))
                    .thenReturn(Optional.of("EXENTO"));

            service.updatePurchaseOrder(poId, request);

            var recoded = written.getAllValues().getLast();
            assertThat(recoded).isNotSameAs(replacement);
            assertThat(recoded.getTaxCode())
                    .as("the replacement row is new, so it carries the product's CURRENT code; the code the "
                            + "replaced row froze stays on the soft-deleted row it belongs to")
                    .isEqualTo("EXENTO");
            assertThat(recoded.getTaxRate())
                    .as("EXENTO is zero by definition, so the new row states 0 for the new code")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            // The interaction, not only the number: the second write asked the product again, once per write. This
            // is what separates "the new row copied the current code" from "the new row inherited the replaced
            // row's pair", which would leave the product repository untouched on this PUT.
            verify(productVariantRepository, times(2)).findProductTaxCodeByVariantId(variantId);
            // Still exactly one ask across both writes: the zero-by-definition code answers 0 without the rate
            // table, and the first write was the only one that had a rate to resolve.
            verify(taxRateService, times(1)).resolve(any(), any(), any());
        }

        /**
         * Everything a successful create needs that no tax case decides: the store's company, its supplier, the
         * payment method, the catalog rows, the product and the number. Each case states its own tax answer.
         */
        private PurchaseOrderRequest stubCreateOf(List<PurchaseOrderDetailRequest> lines) {
            var request = new PurchaseOrderRequest(supplierId, storeId, pmId, draftStatusId, "Comments", lines);
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            catalogRow(pendingStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            when(documentSequenceAllocator.allocate(eq(companyId), eq(DocumentType.PURCHASE_ORDER), anyString()))
                    .thenReturn(1L);
            return request;
        }

        /**
         * Everything the full-replacement route needs that no tax case decides. It is not {@link #stubCreateOf}
         * because only a create allocates a document number, and a stub this route never reaches would fail the
         * case for the stub it did not need instead of for the behaviour it asserts.
         */
        private void stubReplacementUpdate() {
            when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(purchaseOrder));
            when(companyStoreRepository.findById(storeId)).thenReturn(Optional.of(store));
            when(supplierRepository.findByIdAndCompanyId(supplierId, companyId)).thenReturn(Optional.of(supplier));
            when(paymentMethodRepository.findById(pmId)).thenReturn(Optional.of(paymentMethod));
            catalogRow(draftStatus);
            catalogRow(pendingStatus);
            when(productRepository.findById(productId)).thenReturn(Optional.of(product));
            stubStockedVariant(productVariant);
            when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(purchaseOrder);
        }

        /** A variant of the fixture's product that the order's store stocks, which is what a line must name. */
        private void stubStockedVariant(ProductVariant variant) {
            when(productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variant.getId(), productId, companyId))
                    .thenReturn(Optional.of(variant));
            when(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(variant.getId(), storeId))
                    .thenReturn(true);
        }

        /** A second article of the same product, for the case that moves a line to another one. */
        private ProductVariant variantNamed(String name) {
            var variant = new ProductVariant();
            variant.setId(UUID.randomUUID());
            variant.setProductId(productId);
            variant.setVariantName(name);
            variant.setEnabled(true);
            return variant;
        }
    }
}
