package com.lifecontrol.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.paymentmethod.repository.PaymentMethodRepository;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderDetailRepository;
import com.lifecontrol.api.purchaseorder.repository.PurchaseOrderRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The location filters of {@code GET /api/purchase-orders} (record S4/S5) verified against real
 * PostgreSQL with Flyway enabled, so the JPQL predicates are checked by the database that runs them
 * in production instead of by H2.
 *
 * <p>Two companies are seeded, each with its own country/region/zone/store chain: company A owns two
 * regions (one of them with two zones so a zone can narrow a region) and company B stands in for a
 * foreign tenant. The purchase orders are written straight through the repository — the list contract
 * is the subject here, not the write path — and every assertion reads the HTTP response, so the query
 * parameter names are covered too.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Purchase Order List Filter Integration Tests")
class PurchaseOrderListFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String ALPHA = "ALPHA";
    private static final String BETA_ONE = "BETA-ONE";
    private static final String BETA_TWO = "BETA-TWO";
    private static final String GAMMA = "GAMMA";
    private static final String DISABLED = "DISABLED";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderDetailRepository purchaseOrderDetailRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CountryRepository countryRepository;

    private UUID companyAId;
    private UUID companyACountryId;
    private UUID regionAlphaId;
    private UUID regionBetaId;
    private UUID zoneBetaOneId;
    private UUID zoneBetaTwoId;
    private UUID storeAlphaId;
    private UUID storeBetaOneId;
    private UUID storeBetaTwoId;

    private UUID companyBId;
    private UUID storeGammaId;

    private Company companyA;
    private Company companyB;

    private PaymentMethod paymentMethod;

    private final Map<String, String> orderNumberByCase = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Details reference purchase orders, so they go first.
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();

        seedCompanyA();
        seedCompanyB();
        seedOrders();
    }

    @AfterEach
    void tearDown() {
        // Do not leave purchase order rows behind: they reference suppliers and stores, and other
        // integration classes that run later in the same JVM delete those wholesale.
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
    }

    // ─── fixture ────────────────────────────────────────────────────────

    private void seedCompanyA() {
        var company = findOrCreateCompany("PO-FILTER-A", "PO Filter Company A", "POFA010101ABC");
        var companyCountry = findOrCreateCompanyCountry(company);
        var regionAlpha = findOrCreateRegion(companyCountry, "PFA", "PO Filter Region Alpha");
        var regionBeta = findOrCreateRegion(companyCountry, "PFB", "PO Filter Region Beta");
        var zoneBetaOne = findOrCreateZone(regionBeta, "PFB1", "PO Filter Zone Beta One");
        var zoneBetaTwo = findOrCreateZone(regionBeta, "PFB2", "PO Filter Zone Beta Two");

        companyA = company;
        companyAId = company.getId();
        companyACountryId = companyCountry.getId();
        regionAlphaId = regionAlpha.getId();
        regionBetaId = regionBeta.getId();
        zoneBetaOneId = zoneBetaOne.getId();
        zoneBetaTwoId = zoneBetaTwo.getId();
        storeAlphaId = findOrCreateStore(
                        findOrCreateZone(regionAlpha, "PFA1", "PO Filter Zone Alpha"), "FilterSpec Store Alpha")
                .getId();
        storeBetaOneId =
                findOrCreateStore(zoneBetaOne, "FilterSpec Store Beta One").getId();
        storeBetaTwoId =
                findOrCreateStore(zoneBetaTwo, "FilterSpec Store Beta Two").getId();
    }

    private void seedCompanyB() {
        var company = findOrCreateCompany("PO-FILTER-B", "PO Filter Company B", "POFB010101ABC");
        var companyCountry = findOrCreateCompanyCountry(company);
        var region = findOrCreateRegion(companyCountry, "PFG", "PO Filter Region Gamma");
        var zone = findOrCreateZone(region, "PFG1", "PO Filter Zone Gamma");

        companyB = company;
        companyBId = company.getId();
        storeGammaId = findOrCreateStore(zone, "FilterSpec Store Gamma").getId();
    }

    /**
     * Five orders: one in region alpha (plus a disabled twin in the same store), two in region beta
     * sharing one supplier so a search can be narrowed by a zone, and one in the other company. The
     * shared supplier is what makes "filter AND search" observable: the search alone returns two
     * rows and the zone alone returns two, and only their conjunction returns one.
     *
     * <p>Every supplier takes the company of the order that names it: a supplier belongs to exactly one
     * company ({@code V42} makes {@code suppliers.company_id} mandatory), so a single supplier cannot
     * honestly stand for both companies — the one that does is the shared supplier of company A's two
     * beta orders, which is what the search case needs.</p>
     */
    private void seedOrders() {
        paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("PO Filter Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("PO Filter Transfer")
                        .paymentMethodShortName("POFT")
                        .enabled(true)
                        .build()));

        var sharedSupplier = createSupplier("FilterSpec Shared Supplier", companyA);
        var alphaSupplier = createSupplier("FilterSpec Alpha Supplier", companyA);
        var gammaSupplier = createSupplier("FilterSpec Gamma Supplier", companyB);

        orderNumberByCase.put(ALPHA, createOrder(storeAlphaId, alphaSupplier, true));
        orderNumberByCase.put(BETA_ONE, createOrder(storeBetaOneId, sharedSupplier, true));
        orderNumberByCase.put(BETA_TWO, createOrder(storeBetaTwoId, sharedSupplier, true));
        orderNumberByCase.put(GAMMA, createOrder(storeGammaId, gammaSupplier, true));
        orderNumberByCase.put(
                DISABLED, createOrder(storeAlphaId, createSupplier("FilterSpec Disabled Supplier", companyA), false));
    }

    private Company findOrCreateCompany(String key, String name, String rfc) {
        return companyRepository
                .findByCompanyKey(key)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(key)
                        .companyName(name)
                        .rfc(rfc)
                        .enabled(true)
                        .build()));
    }

    private CompanyCountry findOrCreateCompanyCountry(Company company) {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        return companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));
    }

    private CompanyRegion findOrCreateRegion(CompanyCountry companyCountry, String code, String name) {
        return companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .filter(region -> name.equals(region.getRegionName()))
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode(code)
                        .regionName(name)
                        .enabled(true)
                        .build()));
    }

    private CompanyZone findOrCreateZone(CompanyRegion region, String code, String name) {
        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(zone -> name.equals(zone.getZoneName()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(code)
                        .zoneName(name)
                        .enabled(true)
                        .build()));
    }

    private CompanyStore findOrCreateStore(CompanyZone zone, String name) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(store -> name.equals(store.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(name)
                        .enabled(true)
                        .build()));
    }

    private Supplier createSupplier(String name, Company company) {
        return supplierRepository.save(Supplier.builder()
                .supplierName(name)
                .rfc("POF" + UUID.randomUUID().toString().substring(0, 10))
                .company(company)
                .enabled(true)
                .build());
    }

    private String createOrder(UUID storeId, Supplier supplier, boolean enabled) {
        var status = draftStatus();
        var order = purchaseOrderRepository.save(PurchaseOrder.builder()
                .orderNumber("PO-FILTER-" + UUID.randomUUID().toString().substring(0, 12))
                .supplier(supplier)
                .companyStore(companyStoreRepository.findById(storeId).orElseThrow())
                .paymentMethod(paymentMethodOf())
                .status(status)
                .comments("PO filter fixture")
                .enabled(enabled)
                .build());
        return order.getOrderNumber();
    }

    private Status draftStatus() {
        return statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                .orElseThrow();
    }

    private PaymentMethod paymentMethodOf() {
        if (paymentMethod == null) {
            paymentMethod = paymentMethodRepository
                    .findByPaymentMethodNameIgnoreCase("PO Filter Transfer")
                    .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                            .paymentMethodName("PO Filter Transfer")
                            .paymentMethodShortName("POFT")
                            .enabled(true)
                            .build()));
        }
        return paymentMethod;
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private JsonNode listOrders(Consumer<MockHttpServletRequestBuilder> params) throws Exception {
        var request = get("/api/purchase-orders").param("size", "200").with(jwt().authorities(ROLE_LC_ADMIN));
        params.accept(request);
        var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> orderNumbers(JsonNode page) {
        var numbers = new HashSet<String>();
        page.get("content").forEach(node -> numbers.add(node.get("orderNumber").asText()));
        return numbers;
    }

    private String order(String testCase) {
        return orderNumberByCase.get(testCase);
    }

    // ─── region / zone / store ──────────────────────────────────────────

    @Test
    @DisplayName("should return only the orders of the filtered region")
    void regionFilter_returnsOnlyThatRegionsOrders() throws Exception {
        var page = listOrders(request -> request.param("regionId", regionAlphaId.toString()));

        assertThat(orderNumbers(page)).containsExactly(order(ALPHA));
        page.get("content")
                .forEach(node -> assertThat(node.get("regionId").asText()).isEqualTo(regionAlphaId.toString()));
    }

    @Test
    @DisplayName("should narrow a region to the filtered zone")
    void zoneFilter_narrowsWithinTheRegion() throws Exception {
        var regionPage = listOrders(request -> request.param("regionId", regionBetaId.toString()));
        assertThat(orderNumbers(regionPage)).containsExactlyInAnyOrder(order(BETA_ONE), order(BETA_TWO));

        var zonePage = listOrders(request ->
                request.param("regionId", regionBetaId.toString()).param("zoneId", zoneBetaOneId.toString()));
        assertThat(orderNumbers(zonePage)).containsExactly(order(BETA_ONE));
        zonePage.get("content")
                .forEach(node -> assertThat(node.get("zoneId").asText()).isEqualTo(zoneBetaOneId.toString()));
    }

    @Test
    @DisplayName("should narrow a zone to the filtered store")
    void companyStoreFilter_narrowsToThatStore() throws Exception {
        var page = listOrders(request -> request.param("companyId", companyAId.toString())
                .param("regionId", regionBetaId.toString())
                .param("zoneId", zoneBetaTwoId.toString())
                .param("companyStoreId", storeBetaTwoId.toString()));

        assertThat(orderNumbers(page)).containsExactly(order(BETA_TWO));
        page.get("content")
                .forEach(node -> assertThat(node.get("companyStoreId").asText()).isEqualTo(storeBetaTwoId.toString()));
    }

    // ─── company / company country ──────────────────────────────────────

    @Test
    @DisplayName("should exclude the orders of another company")
    void companyFilter_excludesOtherCompanyOrders() throws Exception {
        var companyAPage = listOrders(request -> request.param("companyId", companyAId.toString()));
        assertThat(orderNumbers(companyAPage))
                .containsExactlyInAnyOrder(order(ALPHA), order(BETA_ONE), order(BETA_TWO));
        companyAPage
                .get("content")
                .forEach(node -> assertThat(node.get("companyId").asText()).isEqualTo(companyAId.toString()));

        var companyBPage = listOrders(request -> request.param("companyId", companyBId.toString()));
        assertThat(orderNumbers(companyBPage)).containsExactly(order(GAMMA));
    }

    @Test
    @DisplayName("should narrow to the filtered company country")
    void companyCountryFilter_narrowsToThatCountry() throws Exception {
        var page = listOrders(request -> request.param("companyCountryId", companyACountryId.toString()));

        assertThat(orderNumbers(page)).containsExactlyInAnyOrder(order(ALPHA), order(BETA_ONE), order(BETA_TWO));
        page.get("content")
                .forEach(node ->
                        assertThat(node.get("companyCountryId").asText()).isEqualTo(companyACountryId.toString()));
    }

    // ─── search combined with the filters ───────────────────────────────

    @Test
    @DisplayName("should AND the search term with the location filters")
    void searchAndFilters_combineWithAnd() throws Exception {
        var searchOnly = listOrders(request -> request.param("search", "filterspec shared supplier"));
        assertThat(orderNumbers(searchOnly)).containsExactlyInAnyOrder(order(BETA_ONE), order(BETA_TWO));

        var searchPlusZone = listOrders(request -> request.param("search", "filterspec shared supplier")
                .param("regionId", regionBetaId.toString())
                .param("zoneId", zoneBetaOneId.toString()));
        assertThat(orderNumbers(searchPlusZone)).containsExactly(order(BETA_ONE));
    }

    @Test
    @DisplayName("should keep matching the supplier and the store name, case-insensitively")
    void search_matchesSupplierAndStoreName() throws Exception {
        var bySupplier = listOrders(request -> request.param("search", "FILTERSPEC ALPHA SUPPLIER"));
        assertThat(orderNumbers(bySupplier)).containsExactly(order(ALPHA));

        var byStore = listOrders(request -> request.param("search", "filterspec store beta two"));
        assertThat(orderNumbers(byStore)).containsExactly(order(BETA_TWO));
    }

    // ─── enabled and the unfiltered page ────────────────────────────────

    @Test
    @DisplayName("should never return a disabled order, filtered or not")
    void disabledOrder_neverAppears() throws Exception {
        var disabledSearch = listOrders(request -> request.param("search", "filterspec disabled supplier"));
        assertThat(orderNumbers(disabledSearch)).isEmpty();

        var regionPage = listOrders(request -> request.param("regionId", regionAlphaId.toString()));
        assertThat(orderNumbers(regionPage)).doesNotContain(order(DISABLED));

        var unfiltered = listOrders(request -> {});
        assertThat(orderNumbers(unfiltered)).doesNotContain(order(DISABLED));
    }

    @Test
    @DisplayName("should return the same page as before when no filter is given")
    void noFilters_returnTheSamePageAsBefore() throws Exception {
        var previous = purchaseOrderRepository.findByEnabledTrueOrderByCreatedAtDesc(PageRequest.of(0, 200));
        var previousOrderNumbers = previous.getContent().stream()
                .map(PurchaseOrder::getOrderNumber)
                .collect(Collectors.toSet());

        var page = listOrders(request -> {});

        assertThat(page.get("totalElements").asLong()).isEqualTo(previous.getTotalElements());
        assertThat(orderNumbers(page)).isEqualTo(previousOrderNumbers);
        assertThat(orderNumbers(page)).contains(order(ALPHA), order(BETA_ONE), order(BETA_TWO), order(GAMMA));
    }
}
