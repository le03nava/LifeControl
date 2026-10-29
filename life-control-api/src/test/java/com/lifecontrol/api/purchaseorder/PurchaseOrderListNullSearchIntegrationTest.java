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
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The list without a {@code search} term, executed as the <b>first</b> query of a fresh Hibernate
 * {@code SessionFactory} (record S4/S5).
 *
 * <p><b>The measured defect this class pins.</b> Before the fix, {@code GET /api/purchase-orders}
 * with the {@code search} parameter absent returned HTTP 500 on PostgreSQL 16:
 * {@code ERROR: function lower(bytea) does not exist}. The generated predicate was
 * {@code lower(...) like lower(('%'||?||'%')) escape ''}: the null {@code search} binding inside the
 * concatenation carried no type, so PostgreSQL resolved {@code ('%'||?||'%')} as {@code bytea} and
 * {@code LOWER} was applied to a {@code bytea}. The {@code cast(:search as string)} guard only typed
 * the {@code IS NULL} occurrence, not the two {@code LIKE} occurrences.</p>
 *
 * <p><b>Why this class needs its own Spring context.</b> Hibernate caches the parameter types of a
 * named query per {@code SessionFactory}: once any execution of the same query text binds a non-null
 * {@code search}, the null binding of a later execution is typed by that cached metadata and the
 * defect stops reproducing. That is exactly the false green measured in
 * {@code PurchaseOrderListFilterIntegrationTest#noFilters_returnTheSamePageAsBefore}, which passes when
 * its class runs whole (a sibling method queries with a non-null {@code search} first) and fails when
 * {@code --tests} runs it alone. A distinct context — forced by the unique {@code properties} value
 * below, which changes the context cache key — gives this class its own {@code SessionFactory}, so the
 * null-search execution asserted here is genuinely the first one and the test cannot pass by luck. Do
 * not merge this class into a shared context: the isolation <b>is</b> the test.</p>
 */
@SpringBootTest(properties = "lifecontrol.test.purchase-order-null-search-context=fresh-session-factory")
@AutoConfigureMockMvc
@DisplayName("Purchase Order List Null Search Integration Tests")
class PurchaseOrderListNullSearchIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

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

    @AfterEach
    void tearDown() {
        // Purchase order rows reference suppliers and stores; other integration classes that run later
        // in the same JVM delete those wholesale, so never leave an order behind.
        purchaseOrderDetailRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
    }

    @Test
    @DisplayName("should list the enabled orders when the search parameter is absent")
    void noSearchParameter_returnsTheOrders() throws Exception {
        var orderNumber = seedOrder();

        // No `search` param at all: an absent term must match every enabled order (S5).
        var result = mockMvc.perform(
                        get("/api/purchase-orders").param("size", "5").with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andReturn();

        var page = objectMapper.readTree(result.getResponse().getContentAsString());
        var orderNumbers = new HashSet<String>();
        page.get("content")
                .forEach(node -> orderNumbers.add(node.get("orderNumber").asText()));

        assertThat(orderNumbers).contains(orderNumber);
    }

    // ─── fixture ────────────────────────────────────────────────────────

    /**
     * Seeds one enabled order through the repositories, on its own company/region/zone/store chain so
     * it cannot collide with the fixture of {@code PurchaseOrderListFilterIntegrationTest} (the
     * singleton PostgreSQL container is shared by every integration class of the JVM).
     */
    private String seedOrder() {
        var company = companyRepository
                .findByCompanyKey("PO-NULLSEARCH-A")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("PO-NULLSEARCH-A")
                        .companyName("PO Null Search Company")
                        .rfc("PONSA01010ABC")
                        .enabled(true)
                        .build()));
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));
        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .filter(candidate -> "PO Null Search Region".equals(candidate.getRegionName()))
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("PNSR")
                        .regionName("PO Null Search Region")
                        .enabled(true)
                        .build()));
        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(candidate -> "PO Null Search Zone".equals(candidate.getZoneName()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("PNSZ")
                        .zoneName("PO Null Search Zone")
                        .enabled(true)
                        .build()));
        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> "NullSearchStore".equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("NullSearchStore")
                        .enabled(true)
                        .build()));
        var supplier = supplierRepository.save(Supplier.builder()
                .supplierName("Null Search Supplier")
                .rfc("PNS" + UUID.randomUUID().toString().substring(0, 10))
                .company(company)
                .enabled(true)
                .build());
        var paymentMethod = paymentMethodRepository
                .findByPaymentMethodNameIgnoreCase("PO Filter Transfer")
                .orElseGet(() -> paymentMethodRepository.save(PaymentMethod.builder()
                        .paymentMethodName("PO Filter Transfer")
                        .paymentMethodShortName("POFT")
                        .enabled(true)
                        .build()));
        var status = statusRepository
                .findByTypeNameAndStatusName("PURCHASE_ORDER", "Draft")
                .orElseThrow();

        var order = purchaseOrderRepository.save(PurchaseOrder.builder()
                .orderNumber("PO-NULLSEARCH-" + UUID.randomUUID().toString().substring(0, 12))
                .supplier(supplier)
                .companyStore(store)
                .paymentMethod(paymentMethod)
                .status(status)
                .comments("PO null search fixture")
                .enabled(true)
                .build());
        return order.getOrderNumber();
    }
}
