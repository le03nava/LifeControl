package com.lifecontrol.api.goodsreceipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import com.lifecontrol.api.goodsreceipt.repository.GoodsReceiptRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.service.CompanyStoreScopeResolver;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The list and the detail must agree for the <em>same token</em> — record S1, S2 and S4.
 *
 * <p>Unlike {@code GoodsReceiptServiceReadTest}, which mocks the scope resolver and therefore only
 * pins wiring, this test drives a real {@link CurrentUserContext} from a real {@link Jwt}, a real
 * {@link CompanyStoreScopeResolver} and the real service, so the claim path, the scope derivation
 * and the authorization of the id-addressed read all run together. That combination is what the
 * independent verification found missing: the first version resolved the list from its own level's
 * claim only, and the detail denied rows the list returned.</p>
 *
 * <p>The repository is mocked, so the hierarchy constraint itself is emulated in the stubs (a claim
 * that does not cover the row returns nothing) and pinned for real — under a database — by
 * {@code CompanyStoreScopeProjectionTest#ancestorClaimsConstrainEveryLevel}. What this test proves
 * is the part the mock cannot hide: which claims the resolver hands to the query.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Goods receipts list/detail scope agreement")
class GoodsReceiptScopeAgreementTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID FOREIGN_COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID COUNTRY = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID REGION = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID ZONE = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
    private static final UUID RECEIPT = UUID.fromString("00000000-0000-0000-0000-0000000000d7");
    private static final UUID PO = UUID.fromString("00000000-0000-0000-0000-0000000000d8");
    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000d9");
    private static final UUID STATUS = UUID.fromString("00000000-0000-0000-0000-0000000000da");

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
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    private final CurrentUserContext currentUserContext = new CurrentUserContext();

    private GoodsReceiptService service;
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
                productVariantStoreStockRepository,
                statusRepository,
                currentUserContext,
                new CompanyStoreScopeResolver(currentUserContext, companyStoreRepository));

        var company = Company.builder().id(COMPANY).companyKey("SCOPE-KEY").build();
        var country = CompanyCountry.builder().id(COUNTRY).company(company).build();
        var region = CompanyRegion.builder().id(REGION).companyCountry(country).build();
        var zone = CompanyZone.builder().id(ZONE).companyRegion(region).build();
        var store = CompanyStore.builder()
                .id(STORE)
                .companyZone(zone)
                .storeName("Store")
                .enabled(true)
                .build();
        var purchaseOrder = PurchaseOrder.builder()
                .id(PO)
                .orderNumber("PO-SCOPE-1")
                .companyStore(store)
                .enabled(true)
                .build();
        var location = StoreLocation.builder()
                .id(LOCATION)
                .locationCode("L1")
                .locationName("Receiving")
                .enabled(true)
                .build();
        var status = new Status();
        status.setId(STATUS);
        status.setStatusName("Registered");

        var receipt = GoodsReceipt.builder()
                .id(RECEIPT)
                .receiptNumber("GR-SCOPE-1")
                .purchaseOrder(purchaseOrder)
                .companyStore(store)
                .receivingLocation(location)
                .status(status)
                .receivedBy("subject")
                .receivedAt(LocalDateTime.now())
                .enabled(true)
                .build();

        when(goodsReceiptRepository.findById(RECEIPT)).thenReturn(Optional.of(receipt));
        when(goodsReceiptRepository.findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(receipt), pageable, 1));
        when(goodsReceiptRepository.findByEnabledTrueOrderByReceivedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(receipt), pageable, 1));

        // The row belongs to COMPANY/COUNTRY/REGION/ZONE/STORE: a constraint that does not include
        // the row's ancestor returns nothing, which is what the JPQL does against a database.
        when(companyStoreRepository.findIdsByCompanyIdIn(any()))
                .thenAnswer(call -> contains(call.getArgument(0), COMPANY) ? List.of(STORE) : List.of());
        when(companyStoreRepository.findIdsByCompanyCountryIdIn(any(), any()))
                .thenAnswer(call -> contains(call.getArgument(1), COMPANY) ? List.of(STORE) : List.of());
        when(companyStoreRepository.findIdsByCompanyRegionIdIn(any(), any(), any()))
                .thenAnswer(call -> contains(call.getArgument(1), COUNTRY) && contains(call.getArgument(2), COMPANY)
                        ? List.of(STORE)
                        : List.of());
        when(companyStoreRepository.findIdsByCompanyZoneIdIn(any(), any(), any(), any()))
                .thenAnswer(call -> contains(call.getArgument(1), REGION)
                                && contains(call.getArgument(2), COUNTRY)
                                && contains(call.getArgument(3), COMPANY)
                        ? List.of(STORE)
                        : List.of());
        when(companyStoreRepository.findIdsByStoreIdIn(any(), any(), any(), any(), any()))
                .thenAnswer(call -> contains(call.getArgument(1), ZONE)
                                && contains(call.getArgument(2), REGION)
                                && contains(call.getArgument(3), COUNTRY)
                                && contains(call.getArgument(4), COMPANY)
                        ? List.of(STORE)
                        : List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static boolean contains(Object claim, UUID id) {
        return claim instanceof Set<?> set && set.contains(id);
    }

    private void token(List<String> roles, Object... claimPairs) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("subject");
        Map<String, Object> claims = new LinkedHashMap<>();
        for (var i = 0; i < claimPairs.length; i += 2) {
            claims.put((String) claimPairs[i], claimPairs[i + 1]);
        }
        claims.forEach(builder::claim);
        List<GrantedAuthority> authorities = new ArrayList<>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build(), authorities));
    }

    @Test
    @DisplayName("S1: a company holder reads its company's rows and its detail agrees")
    void companyHolderAgrees() {
        token(List.of("lc-company"), "company_id", COMPANY.toString());

        assertThat(service.getAllReceipts(pageable, null).getContent())
                .extracting(response -> response.id())
                .containsExactly(RECEIPT);
        assertThat(service.getReceipt(RECEIPT).id()).isEqualTo(RECEIPT);
    }

    @Test
    @DisplayName("S2: a country reader whose company claim is foreign sees no row, and the detail denies it")
    void countryReaderWithForeignCompanyClaim() {
        token(
                List.of("lc-company-country-read"),
                "company_id",
                FOREIGN_COMPANY.toString(),
                "company_country_id",
                COUNTRY.toString());

        assertThat(service.getAllReceipts(pageable, null).getContent()).isEmpty();
        assertThatThrownBy(() -> service.getReceipt(RECEIPT)).isInstanceOf(AccessDeniedException.class);
        // The query runs, but constrained by the ancestor claim the detail would deny on.
        verify(companyStoreRepository).findIdsByCompanyCountryIdIn(Set.of(COUNTRY), Set.of(FOREIGN_COMPANY));
    }

    @Test
    @DisplayName("S2: a region reader without the ancestor claims sees no row, and the detail denies it")
    void regionReaderWithoutAncestorClaims() {
        token(List.of("lc-company-region-read"), "company_region_id", REGION.toString());

        assertThat(service.getAllReceipts(pageable, null).getContent()).isEmpty();
        assertThatThrownBy(() -> service.getReceipt(RECEIPT)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(companyStoreRepository);
    }

    @Test
    @DisplayName("S2: a consistent country reader sees the row and its detail admits it")
    void consistentCountryReaderAgrees() {
        token(
                List.of("lc-company-country-read"),
                "company_id",
                COMPANY.toString(),
                "company_country_id",
                COUNTRY.toString());

        assertThat(service.getAllReceipts(pageable, null).getContent()).hasSize(1);
        assertThat(service.getReceipt(RECEIPT).id()).isEqualTo(RECEIPT);
    }

    @Test
    @DisplayName("S1 regression: a store reader still reads only its own store")
    void storeReaderAgrees() {
        token(
                List.of("lc-company-store-read"),
                "company_id",
                COMPANY.toString(),
                "company_country_id",
                COUNTRY.toString(),
                "company_region_id",
                REGION.toString(),
                "company_zone_id",
                ZONE.toString(),
                "company_store_id",
                STORE.toString());

        assertThat(service.getAllReceipts(pageable, null).getContent()).hasSize(1);
        assertThat(service.getReceipt(RECEIPT).id()).isEqualTo(RECEIPT);
    }

    @Test
    @DisplayName("S4: an empty effective scope yields an empty page and never the unscoped query")
    void emptyScopeNeverFallsThrough() {
        token(List.of("lc-company-region-read"), "company_region_id", "");

        var page = service.getAllReceipts(pageable, null);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(goodsReceiptRepository);
        verifyNoInteractions(companyStoreRepository);
    }

    @Test
    @DisplayName("S4: an admin stays unscoped")
    void adminStaysUnscoped() {
        token(List.of("lc-admin"));

        assertThat(service.getAllReceipts(pageable, null).getContent()).hasSize(1);
        verify(goodsReceiptRepository).findByEnabledTrueOrderByReceivedAtDesc(pageable);
        verifyNoInteractions(companyStoreRepository);
    }
}
