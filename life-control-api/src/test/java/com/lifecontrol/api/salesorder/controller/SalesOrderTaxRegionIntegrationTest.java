package com.lifecontrol.api.salesorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #855, {@code S1}, {@code S2}, {@code S5} and {@code S8}, end to end for a <b>sale</b>: the same code on
 * the same day costs 8% at a store in the frontier region and 16% at a store in another one, and the line says
 * which of the two rows it came from.
 *
 * <p><b>What only this suite can prove.</b> {@code TaxRateServiceTest} pins the precedence against a mocked
 * repository and {@code TaxRateControllerHttpIntegrationTest} pins it against stored rows; neither shows the
 * number reaching a document. Here two stores of <b>one company</b> sell the same article on the same day, and
 * the verdict is the line's own {@code tax_code}, {@code tax_rate} and {@code tax_amount} — the snapshot an
 * auditor reads months later ({@code S2}). Before {@code V95} the pair was unrepresentable, so this case could
 * not exist: the company-wide key allowed one rate per code per day and the second store would have been
 * charged the first one's amount.</p>
 *
 * <p><b>Why the rates are a SQL fixture and not two API calls.</b> What the ABM writes and what it refuses is
 * {@code TaxRateControllerHttpIntegrationTest}'s; what this suite is about is the {@code store &rarr; zone
 * &rarr; region} chain the document writer reads. A fixture states the rows and the assertions state the
 * documents, which keeps a failure here about the resolution and not about a create payload.</p>
 *
 * <p><b>Why the suite is not {@code @Transactional}.</b> It models {@code SalesOrderIntegrationTest}: the sales
 * commit for real, because {@code sales_orders} carries a deferred constraint trigger that only fires at commit
 * — a rollback would let a document that production refuses pass here. The consequence is a fixture that cleans
 * up after itself, which the teardown below does in the one order the foreign keys allow, with every delete
 * scoped to this suite's own company because the container is shared with every other suite in the JVM.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sales tax by region — one code, one day, two places, two rates")
class SalesOrderTaxRegionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SALES_ORDER_URL = "/api/sales-orders";

    /** This class's company, distinct from every other suite's, so no sibling's fixture decides this one. */
    private static final String COMPANY_KEY = "TAX-REGION-SALES-KEY";

    /** The two regions the case compares, and the codes the fixture writes them under. */
    private static final String FRONTIER_REGION_CODE = "FRONTERA";

    private static final String CENTRE_REGION_CODE = "CENTRO";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private SalesOrderItemRepository itemRepository;

    private UUID companyId;
    private UUID variantId;
    private StoreTree frontier;
    private StoreTree centre;

    @BeforeEach
    void setUp() {
        companyId = TenantFixtures.findOrCreateAndFlush(
                        companyRepository, COMPANY_KEY, "Tax Region Sales Company", "TRSA010101AA1")
                .getId();
        TenantScope.set(companyId, null);
        clearTaxRates();
        frontier = seedStore(FRONTIER_REGION_CODE, "Frontera Store");
        centre = seedStore(CENTRE_REGION_CODE, "Centre Store");
        variantId = seedIvaVariant();
    }

    /**
     * S2's first half: the frontier's store takes the region's 8% while the other store takes the company's 16%,
     * for the same code, the same article and the same day.
     */
    @Test
    @DisplayName("the same IVA sale on the same day is taxed at 8% in the frontier region and 16% in another")
    void theSameSaleIsTaxedByRegion() throws Exception {
        var effectiveFrom = LocalDate.now().minusYears(1);
        configureCompanyWideRate("IVA", "0.16", effectiveFrom);
        configureRegionalRate("IVA", "0.08", effectiveFrom, frontier.regionId());

        var inTheFrontier = placeSale(frontier);

        var atTheCentre = placeSale(centre);

        assertThat(inTheFrontier.get("taxCode").asText())
                .as("the code is still the product's: V95 gave the amount a place, not the code a second home")
                .isEqualTo("IVA");
        assertThat(decimalOf(inTheFrontier, "taxRate"))
                .as("the region's row is the one that answers a document sold in that region")
                .isEqualByComparingTo(new BigDecimal("0.080000"));
        assertThat(decimalOf(inTheFrontier, "taxAmount"))
                .as("V90 derives round(200 * 0.08, 4) from the stored rate")
                .isEqualByComparingTo(new BigDecimal("16.0000"));

        assertThat(decimalOf(atTheCentre, "taxRate"))
                .as("a store in no region with rows keeps today's behaviour: the company-wide 16%")
                .isEqualByComparingTo(new BigDecimal("0.160000"));
        assertThat(decimalOf(atTheCentre, "taxAmount"))
                .as("and the amount follows the rate the row is charged at, not the frontier's")
                .isEqualByComparingTo(new BigDecimal("32.0000"));

        // The rows, not only the responses: the stored pair is what an auditor reads months later, and it is what
        // identifies which of the two rows the line came from (S2) — the rates differ, so code + rate does.
        assertThat(storedLine(inTheFrontier))
                .as("the frontier's line stores the region's code and rate")
                .satisfies(item -> {
                    assertThat(item.getTaxCode()).isEqualTo("IVA");
                    assertThat(item.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.080000"));
                    assertThat(item.getTaxAmount()).isEqualByComparingTo(new BigDecimal("16.0000"));
                });
        assertThat(storedLine(atTheCentre))
                .as("and the other store's line stores the company-wide one, on the same day and the same code")
                .satisfies(item -> {
                    assertThat(item.getTaxCode()).isEqualTo("IVA");
                    assertThat(item.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.160000"));
                    assertThat(item.getTaxAmount()).isEqualByComparingTo(new BigDecimal("32.0000"));
                });
    }

    /**
     * D2's refusal, at the document: a region that states the code and has nothing for the document's day refuses
     * the sale instead of charging the company-wide rate — and refuses it before anything is stored.
     */
    @Test
    @DisplayName(
            "a frontier store with no rate for the document's day is refused, naming the region, and stores nothing")
    void aRegionGapRefusesTheSaleAndStoresNothing() throws Exception {
        configureCompanyWideRate("IVA", "0.16", LocalDate.now().minusYears(1));
        // The region has stated IVA — from a day that has not arrived — so it is authoritative for the code and
        // has no answer for this document.
        configureRegionalRate("IVA", "0.08", LocalDate.now().plusMonths(1), frontier.regionId());

        var refused = mockMvc.perform(post(SALES_ORDER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleBody(frontier))
                        .with(scopedJwt(frontier)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("the line is refused rather than charged the 16% of a place it was not sold in (D2)")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .as("and the refusal names the code, the region and the document's date")
                .contains("IVA")
                .contains(FRONTIER_REGION_CODE)
                .contains(LocalDate.now().toString())
                .doesNotContain("violates a data constraint");

        assertThat(countOrdersOf(frontier))
                .as("a refused line refuses the document: no order row of the frontier store survives")
                .isZero();
        assertThat(countLines())
                .as("and no line row either — the copy runs before the line is written")
                .isZero();
    }

    // ---- the documents ----

    /** The one sale this suite places: two pieces of the {@code IVA} product at 100 each, at the given store. */
    private String saleBody(StoreTree tree) throws Exception {
        var request = new SalesOrderRequest(
                null,
                tree.storeId(),
                openShiftOf(tree),
                "tax-region",
                List.of(new SalesOrderItemRequest(
                        null, variantId, new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null)));
        return objectMapper.writeValueAsString(request);
    }

    /** Posts the sale and returns the line the response reports. */
    private JsonNode placeSale(StoreTree tree) throws Exception {
        var created = mockMvc.perform(post(SALES_ORDER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleBody(tree))
                        .with(scopedJwt(tree)))
                .andExpect(status().isCreated())
                .andReturn();
        var root = objectMapper.readTree(created.getResponse().getContentAsString());
        return root.get("items").get(0);
    }

    /** The stored row behind a line the response reported, read in this company's scope. */
    private SalesOrderItem storedLine(JsonNode line) {
        var id = UUID.fromString(line.get("id").asText());
        return inTransaction(() -> itemRepository.findById(id)).orElseThrow();
    }

    private long countOrdersOf(StoreTree tree) {
        return inTransaction(() -> jdbc.queryForObject(
                "SELECT count(*) FROM sales_orders WHERE company_id = ? AND company_store_id = ?",
                Long.class,
                companyId,
                tree.storeId()));
    }

    private long countLines() {
        return inTransaction(() -> jdbc.queryForObject(
                "SELECT count(*) FROM sales_order_items WHERE product_variant_id = ?", Long.class, variantId));
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        assertThat(node.get(field)).as("the response states %s", field).isNotNull();
        return node.get(field).decimalValue();
    }

    // ---- the fixture ----

    /**
     * A region, a zone under it and a store under that, all of this class's company.
     *
     * <p>The chain is the one {@code V95}'s axis is read from, so a store that is in a region is what makes a
     * region-scoped rate reachable from a document.</p>
     */
    private StoreTree seedStore(String regionCode, String storeName) {
        return TenantFixtures.inTenant(companyId, () -> {
            var company = companyRepository.findById(companyId).orElseThrow();
            var country = countryRepository
                    .findByCountryCode("MX")
                    .orElseGet(() -> countryRepository.save(Country.builder()
                            .countryCode("MX")
                            .countryName("Mexico")
                            .enabled(true)
                            .build()));
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(companyId, country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));
            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .filter(candidate -> regionCode.equals(candidate.getRegionCode()))
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode(regionCode)
                                    .regionName("Region " + regionCode)
                                    .enabled(true)
                                    .build()));
            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode(regionCode)
                            .zoneName("Zone " + regionCode)
                            .enabled(true)
                            .build()));
            var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> storeName.equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName(storeName)
                            .enabled(true)
                            .build()));
            return new StoreTree(companyId, companyCountry.getId(), region.getId(), zone.getId(), store.getId());
        });
    }

    /** A product that declares {@code IVA} and one variant of it, which both stores can sell. */
    private UUID seedIvaVariant() {
        return TenantFixtures.inTenant(companyId, () -> {
            var product = productRepository.save(Product.builder()
                    .sku("SKU-TAX-REGION-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("IVA product")
                    .taxCode("IVA")
                    .company(companyRepository.findById(companyId).orElseThrow())
                    .enabled(true)
                    .build());

            var variant = new ProductVariant();
            variant.setProductId(product.getId());
            variant.setCompanyId(companyId);
            variant.setMeasureUnitId(
                    measureUnitRepository.findBySatCode("H87").orElseThrow().getId());
            variant.setBarCode("BAR-TAX-REGION-" + UUID.randomUUID().toString().substring(0, 10));
            variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setEnabled(true);
            return productVariantRepository.save(variant).getId();
        });
    }

    /** An open shift of the store, which is what makes the sale a sale and not a malformed request. */
    private UUID openShiftOf(StoreTree tree) {
        return TenantFixtures.inTenant(
                companyId,
                () -> shiftRepository.findByCompanyStoreId(tree.storeId()).stream()
                        .findFirst()
                        .orElseGet(() -> shiftRepository.save(Shift.builder()
                                .companyStoreId(tree.storeId())
                                .userId("tax-region")
                                .openedAt(LocalDateTime.now().minusHours(1))
                                .status("ABIERTO")
                                .enabled(true)
                                .build()))
                        .getId());
    }

    /**
     * The company-wide rate: {@code company_region_id} is absent, which is the shape every row that predates
     * {@code V95} has.
     */
    private void configureCompanyWideRate(String taxCode, String rate, LocalDate effectiveFrom) {
        inTransaction(() -> jdbc.update(
                "INSERT INTO tax_rate (id, company_id, tax_code, rate, effective_from, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, true)",
                UUID.randomUUID(),
                companyId,
                taxCode,
                new BigDecimal(rate),
                java.sql.Date.valueOf(effectiveFrom)));
    }

    /** The region's rate: the same columns plus the place it applies to. */
    private void configureRegionalRate(String taxCode, String rate, LocalDate effectiveFrom, UUID regionId) {
        inTransaction(() -> jdbc.update(
                "INSERT INTO tax_rate (id, company_id, tax_code, company_region_id, rate, effective_from, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true)",
                UUID.randomUUID(),
                companyId,
                taxCode,
                regionId,
                new BigDecimal(rate),
                java.sql.Date.valueOf(effectiveFrom)));
    }

    /** Forgets every rate this company stated, so a case starts from a freshly provisioned company. */
    private void clearTaxRates() {
        inTransaction(() -> jdbc.update("DELETE FROM tax_rate WHERE company_id = ?", companyId));
    }

    /**
     * A caller of this suite's own tenant, stating the five {@code company_*} claims the store guard reads for
     * <b>the store under test</b> — two stores of two regions are two different scopes, and a token that named one
     * region would be refused by the guard for the other's sale.
     */
    private RequestPostProcessor scopedJwt(StoreTree tree) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.claim("preferred_username", "tax-region")
                        .claim("company_id", tree.companyId().toString())
                        .claim("company_country_id", tree.companyCountryId().toString())
                        .claim("company_region_id", tree.regionId().toString())
                        .claim("company_zone_id", tree.zoneId().toString())
                        .claim("company_store_id", tree.storeId().toString()));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return TenantFixtures.inTenant(companyId, work);
    }

    /**
     * Leaves the shared container as this class found it — in the one order the foreign keys allow, and scoped so
     * that no sibling suite's rows go with it.
     *
     * <p>{@code sales_order_items} references the header, {@code shifts} is referenced by the orders,
     * {@code tax_rate} references the company, and every level of the store tree references the one above it, so
     * the deletes run leaf first. {@code status_transition} is deliberately not deleted: {@code V67} made it
     * append-only and revoked {@code DELETE} from the application role, and it carries no foreign key to the
     * document, so it blocks nothing.</p>
     */
    @AfterEach
    void tearDown() {
        if (companyId == null) {
            return;
        }
        try {
            TenantScope.set(companyId, null);
            // The ledger and the payments first, through the base class's helpers: both are append-only and both
            // reference rows the deletes below remove. This suite never charges, so the calls are insurance
            // against a case that grows one rather than a measurement of today's rows.
            deleteSalePayments(companyId);
            deleteStockLedger(jdbc, companyId);
            var ordersOfThisCompany = "SELECT id FROM sales_orders WHERE company_id = ?";
            var storesOfThisCompany = "SELECT id FROM company_stores WHERE company_id = ?";
            var regionsOfThisCompany =
                    "SELECT id FROM company_regions WHERE company_country_id IN (SELECT id FROM company_countries WHERE company_id = ?)";
            deleteScopedToThisCompany(List.of(
                    "DELETE FROM sales_order_items WHERE sales_order_id IN (" + ordersOfThisCompany + ")",
                    "DELETE FROM sales_orders WHERE company_id = ?",
                    "DELETE FROM document_sequence WHERE company_id = ?",
                    "DELETE FROM tax_rate WHERE company_id = ?",
                    "DELETE FROM shifts WHERE company_store_id IN (" + storesOfThisCompany + ")",
                    "DELETE FROM product_variant_store_stock WHERE company_store_id IN (" + storesOfThisCompany + ")",
                    "DELETE FROM product_variants WHERE company_id = ?",
                    "DELETE FROM products WHERE company_id = ?",
                    "DELETE FROM company_stores WHERE company_id = ?",
                    "DELETE FROM company_zones WHERE company_region_id IN (" + regionsOfThisCompany + ")",
                    "DELETE FROM company_regions WHERE company_country_id IN (SELECT id FROM company_countries WHERE company_id = ?)",
                    "DELETE FROM company_countries WHERE company_id = ?",
                    "DELETE FROM companies WHERE id = ?"));
        } finally {
            TenantScope.clear();
        }
    }

    /** Runs the deletes in one transaction that states this class's tenant, so the policy filters by it. */
    private void deleteScopedToThisCompany(List<String> statements) {
        TenantFixtures.inTenant(companyId, () -> statements.forEach(statement -> jdbc.update(statement, companyId)));
    }

    /** The real company &rarr; country &rarr; region &rarr; zone &rarr; store ids behind one of the two stores. */
    private record StoreTree(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
