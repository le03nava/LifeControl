package com.lifecontrol.api.goodsreceipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.exception.GoodsReceiptNotFoundException;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit coverage of the goods receipt reads and their W2-D10 tenant scoping.
 *
 * <p>The scoping decision is driven by the current user's JWT claims, which an integration test
 * cannot easily forge without extra infrastructure, so the collaborator interactions are asserted
 * here with a mocked {@link CurrentUserContext}: the admin path stays unscoped, a non-admin is
 * restricted to exactly its {@code company_store_ids}, an empty set never reaches the unscoped
 * query, and the id read authorizes the receipt's own store chain.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GoodsReceiptService read tests")
class GoodsReceiptServiceReadTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    private static final UUID RECEIPT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID STATUS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b4");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Mock
    private PurchaseOrderService purchaseOrderService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private StoreLocationRepository storeLocationRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    private GoodsReceiptService service;

    private GoodsReceipt receipt;
    private final Pageable pageable = PageRequest.of(0, 12);

    @BeforeEach
    void setUp() {
        service = new GoodsReceiptService(
                purchaseOrderRepository,
                purchaseOrderDetailRepository,
                purchaseOrderService,
                inventoryService,
                goodsReceiptRepository,
                storeInventorySettingsRepository,
                storeLocationRepository,
                statusRepository,
                currentUserContext);

        var company = Company.builder().id(COMPANY_ID).companyKey("GR-READ-KEY").build();
        var companyCountry =
                CompanyCountry.builder().id(COMPANY_COUNTRY_ID).company(company).build();
        var companyRegion = CompanyRegion.builder()
                .id(REGION_ID)
                .companyCountry(companyCountry)
                .build();
        var companyZone =
                CompanyZone.builder().id(ZONE_ID).companyRegion(companyRegion).build();
        var store = CompanyStore.builder()
                .id(STORE_ID)
                .companyZone(companyZone)
                .storeName("Store")
                .enabled(true)
                .build();
        var purchaseOrder = PurchaseOrder.builder()
                .id(PO_ID)
                .orderNumber("PO-20260603-00001")
                .companyStore(store)
                .enabled(true)
                .build();
        var location = StoreLocation.builder()
                .id(LOCATION_ID)
                .locationCode("GRL1")
                .locationName("Receiving")
                .enabled(true)
                .build();
        var status = new Status();
        status.setId(STATUS_ID);
        status.setStatusName("Registered");

        receipt = GoodsReceipt.builder()
                .id(RECEIPT_ID)
                .receiptNumber("GR-PO-20260603-00001-01")
                .purchaseOrder(purchaseOrder)
                .companyStore(store)
                .receivingLocation(location)
                .status(status)
                .receivedBy("receiver")
                .receivedAt(LocalDateTime.now())
                .comments("Reception")
                .enabled(true)
                .build();
    }

    private Page<GoodsReceipt> pageOf(GoodsReceipt... receipts) {
        return new PageImpl<>(List.of(receipts), pageable, receipts.length);
    }

    // ── GET list: tenant scoping ────────────────────────────────────────

    @Nested
    @DisplayName("getAllReceipts tenant scoping")
    class TenantScopingTests {

        @Test
        @DisplayName("should query unscoped for an admin, without the store filter")
        void adminGetsUnscopedQuery() {
            when(currentUserContext.isAdmin()).thenReturn(true);
            when(goodsReceiptRepository.findByEnabledTrueOrderByReceivedAtDesc(pageable))
                    .thenReturn(pageOf(receipt));

            var response = service.getAllReceipts(pageable, null);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().getFirst().id()).isEqualTo(RECEIPT_ID);
            verify(goodsReceiptRepository).findByEnabledTrueOrderByReceivedAtDesc(pageable);
            verify(goodsReceiptRepository, never()).findByCompanyStoreIdInAndSearchTerm(any(), any(), any());
            verify(goodsReceiptRepository, never())
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any());
            verify(currentUserContext, never()).getCompanyStoreIds();
        }

        @Test
        @DisplayName("should use the unscoped search query for an admin with a search term")
        void adminGetsUnscopedSearchQuery() {
            when(currentUserContext.isAdmin()).thenReturn(true);
            when(goodsReceiptRepository.findBySearchTerm("PO-2026", pageable)).thenReturn(pageOf(receipt));

            service.getAllReceipts(pageable, "PO-2026");

            verify(goodsReceiptRepository).findBySearchTerm("PO-2026", pageable);
            verify(goodsReceiptRepository, never()).findByCompanyStoreIdInAndSearchTerm(any(), any(), any());
            verify(goodsReceiptRepository, never())
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should filter a non-admin to exactly its company_store_ids with the unsearched store finder")
        void nonAdminGetsItsStoreIds() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of(STORE_ID, OTHER_STORE_ID));
            when(goodsReceiptRepository.findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(
                            Set.of(STORE_ID, OTHER_STORE_ID), pageable))
                    .thenReturn(pageOf(receipt));

            var response = service.getAllReceipts(pageable, null);

            assertThat(response.getContent()).hasSize(1);
            verify(goodsReceiptRepository)
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(
                            Set.of(STORE_ID, OTHER_STORE_ID), pageable);
            verify(goodsReceiptRepository, never()).findByCompanyStoreIdInAndSearchTerm(any(), any(), any());
            verify(goodsReceiptRepository, never()).findByEnabledTrueOrderByReceivedAtDesc(any());
            verify(goodsReceiptRepository, never()).findBySearchTerm(any(), any());
        }

        @Test
        @DisplayName("should filter a non-admin with a search term to its store ids")
        void nonAdminWithSearchGetsItsStoreIds() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of(STORE_ID));
            when(goodsReceiptRepository.findByCompanyStoreIdInAndSearchTerm(Set.of(STORE_ID), "PO-2026", pageable))
                    .thenReturn(pageOf(receipt));

            service.getAllReceipts(pageable, "PO-2026");

            verify(goodsReceiptRepository).findByCompanyStoreIdInAndSearchTerm(Set.of(STORE_ID), "PO-2026", pageable);
            verify(goodsReceiptRepository, never())
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any());
            verify(goodsReceiptRepository, never()).findBySearchTerm(any(), any());
        }

        @Test
        @DisplayName("should return an empty page and never touch the unscoped query when the store set is empty")
        void emptyStoreSetYieldsEmptyPageWithoutUnscopedQuery() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of());

            var response = service.getAllReceipts(pageable, null);

            assertThat(response.getContent()).isEmpty();
            assertThat(response.getTotalElements()).isZero();
            verifyNoInteractions(goodsReceiptRepository);
            verify(goodsReceiptRepository, never()).findByEnabledTrueOrderByReceivedAtDesc(any());
            verify(goodsReceiptRepository, never()).findBySearchTerm(any(), any());
            verify(goodsReceiptRepository, never()).findByCompanyStoreIdInAndSearchTerm(any(), any(), any());
            verify(goodsReceiptRepository, never())
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any());
        }

        @Test
        @DisplayName("should return an empty page and never touch the unscoped search when the store set is empty")
        void emptyStoreSetWithSearchNeverCallsUnscopedSearch() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of());

            var response = service.getAllReceipts(pageable, "PO-2026");

            assertThat(response.getContent()).isEmpty();
            verifyNoInteractions(goodsReceiptRepository);
            verify(goodsReceiptRepository, never()).findBySearchTerm(any(), any());
            verify(goodsReceiptRepository, never()).findByCompanyStoreIdInAndSearchTerm(any(), any(), any());
            verify(goodsReceiptRepository, never())
                    .findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any());
        }
    }

    // ── GET by id ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getReceipt")
    class GetReceiptTests {

        @Test
        @DisplayName("should authorize the receipt's own store chain and map the receipt")
        void authorizesTheReceiptStoreChain() {
            when(goodsReceiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

            var response = service.getReceipt(RECEIPT_ID);

            verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
            assertThat(response)
                    .extracting(
                            GoodsReceiptResponse::id,
                            GoodsReceiptResponse::receiptNumber,
                            GoodsReceiptResponse::orderNumber,
                            GoodsReceiptResponse::companyStoreId,
                            GoodsReceiptResponse::statusName)
                    .containsExactly(
                            RECEIPT_ID, "GR-PO-20260603-00001-01", "PO-20260603-00001", STORE_ID, "Registered");
        }

        @Test
        @DisplayName("should throw GoodsReceiptNotFoundException for an unknown id and authorize nothing")
        void unknownIdThrows404() {
            when(goodsReceiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReceipt(RECEIPT_ID)).isInstanceOf(GoodsReceiptNotFoundException.class);

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should pass the store id collection through the repository filter unchanged")
        void usesTheReceiptStoreForAuthorization() {
            var captor = ArgumentCaptor.forClass(UUID.class);
            when(goodsReceiptRepository.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt));

            service.getReceipt(RECEIPT_ID);

            verify(currentUserContext)
                    .verifyCompanyStoreAccess(
                            captor.capture(), captor.capture(), captor.capture(), captor.capture(), captor.capture());
            assertThat(captor.getAllValues())
                    .containsExactly(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
        }
    }
}
