package com.lifecontrol.api.tax.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
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
 * S5 of issue #858, end to end: the rate a company declares through the API is the one a sale is taxed with.
 *
 * <p><b>What only this suite can prove.</b> {@code TaxRateControllerHttpIntegrationTest} proves the resource
 * writes the row {@code TaxRateService.resolve} answers; {@code SalesOrderIntegrationTest.LineTaxCopyTests}
 * proves a line copies the rate a <i>SQL fixture</i> wrote. Neither closes the loop the issue is about: a
 * company starts with no rate, its taxable sale is refused with the message ADR-0002 decision 6 states, and
 * after the rate is declared <b>through the ABM</b> the very same sale is accepted with {@code tax_rate =
 * 0.160000} on the line and {@code tax_amount} derived by {@code V90}'s trigger. That is the sentence S5
 * states, and it is only observable with both halves in one run.</p>
 *
 * <p><b>No SQL fixture writes a rate here.</b> The only writer of {@code tax_rate} in this class is
 * {@code POST /api/companies/{companyId}/tax-rates}, which is the point: a fixture would prove the sale path
 * against a row the ABM might not be able to state.</p>
 *
 * <p><b>Why the suite is not {@code @Transactional}, and what that costs.</b> It models
 * {@code SalesOrderIntegrationTest}: the sale, the rate and every read go through MockMvc and commit for real,
 * because a refusal inside a test-owned transaction marks it rollback-only and the "declare, then retry the
 * same sale" half could never run. The consequence is a fixture that must clean up after itself, which the
 * teardown below does in the one order the foreign keys allow — the document and the rows that hang off it,
 * the ledger it booked, the rate that committed, the shift, the store/location/settings tree, the catalog
 * rows the sale named, and the tenant they belong to — with every delete scoped to this suite's own rows,
 * because the container is shared with every other suite in the JVM.</p>
 *
 * <p><b>Why it seeds its own company.</b> {@code LineTaxCopyTests.noRateIsConfigured()} asserts the "no rate
 * stated" state for <i>its</i> company, and the container is shared by the whole JVM: a rate this class
 * declared against that tenant would make that case fail depending on execution order. The company is
 * therefore {@value #COMPANY_KEY}, and the rate rows of that company are cleared in both setup and teardown,
 * because a declared rate commits and would otherwise survive into the next case — including the one that needs
 * the "refused" state.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Tax rate ABM — the sale is refused, then accepted once the rate is declared through the API")
class TaxRateSaleAcceptanceTest extends AbstractPostgresIntegrationTest {

    private static final String TAX_RATE_URL = "/api/companies/{companyId}/tax-rates";
    private static final String SALES_ORDER_URL = "/api/sales-orders";

    /** This class's company, distinct from every other suite's, so no other case's rate decides this one. */
    private static final String COMPANY_KEY = "TAX-ACCEPTANCE-KEY";

    /** The discriminant of the fixture's opening receipt, so no assertion can confuse it with the document. */
    private static final String FIXTURE_OPENING = "TAX_ACCEPTANCE_OPENING";

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
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private SalesOrderItemRepository itemRepository;

    @Autowired
    private StockLedgerEngine stockLedgerEngine;

    private UUID companyId;
    private UUID companyStoreId;
    private UUID shiftId;
    private UUID salesLocationId;
    private UUID variantId;
    private StoreChain chain;

    /**
     * The fixture's own company, store, stocked {@code IVA} variant and open shift.
     *
     * <p>The scope is stated for the whole case the way {@code SalesOrderIntegrationTest} does it, so the
     * repository calls of the fixture (and of the teardown) meet the policies instead of silently matching no
     * rows — a {@code deleteAll()} under no scope deletes nothing and reports success.</p>
     */
    @BeforeEach
    void setUp() {
        companyId = TenantFixtures.findOrCreateAndFlush(
                        companyRepository, COMPANY_KEY, "Tax Acceptance Company", "TACO010101AA1")
                .getId();
        TenantScope.set(companyId, null);
        seedStoreTree();
        variantId = seedStockedIvaVariant();
        shiftId = seedOpenShift();
        clearTaxRates();
    }

    /**
     * The fourth case of S5: before the company states the rate, the sale is refused — and the refusal is the
     * one decision 6 describes, not a schema complaint.
     */
    @Test
    @DisplayName(
            "before the rate is declared the sale is refused, naming IVA and the document's date, and nothing is stored")
    void saleIsRefusedBeforeTheRateIsDeclared() throws Exception {
        var today = LocalDate.now();

        var refused = mockMvc.perform(post(SALES_ORDER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleBody())
                        .with(scopedJwt("lc-sales")))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a taxable code the company states no rate for is the caller's to fix, and a missing rate is "
                        + "never a silent zero (ADR-0002 decision 6)")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .as("the refusal names the code and the document's date — the two facts whoever states the rate "
                        + "needs — and is the business refusal, not a constraint the schema raised")
                .contains("IVA")
                .contains(today.toString())
                .doesNotContain("violates a data constraint");

        assertThat(countOfThisSuitesOrders())
                .as("a refused line refuses the document: no order row of this suite's store survives the "
                        + "rollback")
                .isZero();
        assertThat(countOfThisSuitesLines())
                .as("and no line row either — the copy runs before the line is written, not after")
                .isZero();
    }

    /**
     * S5's other half, stated the way a company actually reaches it: one {@code POST} on the new resource, and
     * the same sale that was refused a moment earlier is written with the declared rate on its line.
     */
    @Test
    @DisplayName("after the rate is declared through the API the same sale is accepted, taxed at 0.160000")
    void saleIsAcceptedOnceTheRateIsDeclaredThroughTheApi() throws Exception {
        var effectiveFrom = LocalDate.now().minusYears(1);

        mockMvc.perform(post(TAX_RATE_URL, companyId)
                        .with(scopedJwt("lc-tax"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taxCode": "IVA", "rate": 0.16, "effectiveFrom": "%s", "enabled": true}
                                """.formatted(effectiveFrom)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxCode").value("IVA"))
                .andExpect(jsonPath("$.effectiveFrom").value(effectiveFrom.toString()));

        var accepted = mockMvc.perform(post(SALES_ORDER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saleBody())
                        .with(scopedJwt("lc-sales")))
                .andExpect(status().isCreated())
                .andReturn();

        var root = objectMapper.readTree(accepted.getResponse().getContentAsString());
        var line = root.get("items").get(0);

        assertThat(line.get("taxCode").asText())
                .as("the code the sold variant's product declares, copied verbatim onto the line")
                .isEqualTo("IVA");
        assertThat(decimalOf(line, "taxRate"))
                .as("the rate the API declared, resolved for the document's date and snapshotted on the line")
                .isEqualByComparingTo(new BigDecimal("0.160000"));
        assertThat(decimalOf(line, "taxAmount"))
                .as("V90 derives round((subtotal - discount) * stored rate, 4) = round(200 * 0.16, 4)")
                .isEqualByComparingTo(new BigDecimal("32.0000"));
        assertThat(decimalOf(root, "taxTotal"))
                .as("and the document's tax total is its lines', which is what makes the declared rate reach the "
                        + "money the customer is asked for")
                .isEqualByComparingTo(new BigDecimal("32.0000"));

        // The row, not only the response: the two columns are derived by the schema, so reading them back is
        // what proves the numbers above are the row's and not a value the service computed for the answer.
        var stored = inTransaction(
                () -> itemRepository.findById(UUID.fromString(line.get("id").asText())));
        assertThat(stored).isPresent();
        assertThat(stored.orElseThrow().getTaxRate())
                .as("sales_order_items.tax_rate holds the snapshot of the stated rate")
                .isEqualByComparingTo(new BigDecimal("0.160000"));
        assertThat(stored.orElseThrow().getTaxAmount())
                .as("sales_order_items.tax_amount holds the trigger's derivation from it")
                .isEqualByComparingTo(new BigDecimal("32.0000"));
    }

    /**
     * How many order rows this suite's own fixture could have written: its store, in its tenant.
     *
     * <p><b>Why the count is scoped, and why that is not a weaker assertion.</b> {@code sales_orders} is shared
     * by the whole JVM and the suites here commit for real, so a bare {@code count()} answers "how many
     * documents does the container hold" — measured as a nonzero number in a full run, for a row an earlier
     * suite wrote. The property this case asserts is "this refused sale stored no order", which is a question
     * about <i>this</i> suite's store, and the store is exactly what the request names: a refused path that
     * wrote its own header would land on it.</p>
     */
    private long countOfThisSuitesOrders() {
        return inTransaction(() -> jdbc.queryForObject(
                "SELECT count(*) FROM sales_orders WHERE company_id = ? AND company_store_id = ?",
                Long.class,
                companyId,
                companyStoreId));
    }

    /**
     * How many line rows name this suite's own variant, which no other fixture shares: the variant is created
     * by {@link #seedStockedIvaVariant()} with a fresh barcode and name, in this suite's company.
     *
     * <p>{@code sales_order_items} carries no {@code company_id} — {@code V81} says so in as many words: a line
     * belongs to its document — so the variant is the scoping key that is this suite's own. The assertion is
     * still the strong one: a refused path that wrote its own line would name this variant, because the
     * variant is what the request names.</p>
     */
    private long countOfThisSuitesLines() {
        return inTransaction(() -> jdbc.queryForObject(
                "SELECT count(*) FROM sales_order_items WHERE product_variant_id = ?", Long.class, variantId));
    }

    /**
     * Leaves the shared container as this class found it — in the one order the foreign keys allow, and scoped
     * so that no sibling suite's rows go with it.
     *
     * <p><b>Why the order is this one.</b> {@code sale_payment} references the document
     * ({@code fk_payment_order_same_company}) and {@code sales_order_items} references the header, so a payment
     * or a line that outlives its parent refuses the parent's delete; {@code sales_orders.shift_id} references
     * the shift, so the shift cannot go while an order names it; {@code stock_movement} references the store
     * location and the variant with no cascade, so the ledger goes before the store tree and the catalog;
     * {@code tax_rate} references the company; and every level of the store/location/settings tree references
     * the one above it. Each violation would land in whichever case runs next, not in the one that wrote the
     * row, which is what made this teardown order-dependent.</p>
     *
     * <p><b>Why every delete names this suite's company.</b> Measured in the full suite: the previous
     * {@code shiftRepository.deleteAll()} was an unscoped delete, {@code shifts} carries no tenant policy, so
     * it reached another suite's shift — one a leftover {@code sales_orders} row still referenced — and the
     * whole teardown failed on {@code sales_orders_shift_id_fkey} with key {@code 3dfa87b7-…}, a shift that is
     * not this suite's. The same shape hangs off every repository {@code deleteAll()} this teardown used to
     * call, so each statement below is scoped through this suite's company id — its orders, its store, its
     * catalog, its tenant — and never by deleting a table.</p>
     *
     * <p><b>Null-safe, not exception-tolerant.</b> {@code companyId} is the first thing setup writes and the key
     * every statement below scopes by, so it is the only guard this teardown needs: it is the one id that says
     * the fixture never got far enough to leave anything scoped to this suite behind. A null store, shift,
     * variant or order id is deliberately not guarded — the statements reach those rows <i>through</i> the
     * company, which is what makes a fixture that failed half-way clean exactly what it built. A delete that
     * still fails raises: a teardown never reports a clean container it did not produce.</p>
     */
    @AfterEach
    void tearDown() {
        if (companyId == null) {
            // The fixture failed before it stated its tenant: nothing is scoped to this suite to remove.
            return;
        }
        try {
            TenantScope.set(companyId, null);
            deleteSalePayments(companyId);
            deleteTheDocument();
            deleteStockLedger(jdbc, companyId);
            clearTaxRates();
            deleteTheFixtureTree();
        } finally {
            TenantScope.clear();
        }
    }

    /**
     * The document this suite posted and the rows that hang off it: its lines, then the header.
     * {@code sales_order_items} carries no {@code company_id}, so its rows are reached through the orders of this
     * company.
     *
     * <p>The document's {@code status_transition} rows are deliberately <b>not</b> deleted: {@code V67} made that
     * history append-only and revoked {@code DELETE} from the application role, so removing them would mean
     * borrowing a permission the production path never has — measured, the statement answers
     * {@code permission denied for table status_transition}. They block nothing, because the table carries no
     * foreign key to the document.</p>
     */
    private void deleteTheDocument() {
        var ordersOfThisCompany = "SELECT id FROM sales_orders WHERE company_id = ?";
        deleteScopedToThisCompany(List.of(
                "DELETE FROM sales_order_items WHERE sales_order_id IN (" + ordersOfThisCompany + ")",
                "DELETE FROM sales_orders WHERE company_id = ?"));
    }

    /**
     * The fixture, leaf-first: the document counter the accepted sale allocated, the shift, the
     * store/location/settings tree, the catalog rows the sale named, and finally the tenant they belong to. All
     * of them are this suite's own, because the company is.
     */
    private void deleteTheFixtureTree() {
        var storesOfThisCompany = "SELECT id FROM company_stores WHERE company_id = ?";
        var regionsOfThisCompany =
                "SELECT id FROM company_regions WHERE company_country_id IN (SELECT id FROM company_countries WHERE company_id = ?)";
        deleteScopedToThisCompany(List.of(
                "DELETE FROM document_sequence WHERE company_id = ?",
                "DELETE FROM shifts WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM store_inventory_settings WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM product_variant_store_stock WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM store_locations WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM store_zones WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM store_areas WHERE company_store_id IN (" + storesOfThisCompany + ")",
                "DELETE FROM product_variants WHERE company_id = ?",
                "DELETE FROM products WHERE company_id = ?",
                "DELETE FROM company_stores WHERE company_id = ?",
                "DELETE FROM company_zones WHERE company_region_id IN (" + regionsOfThisCompany + ")",
                "DELETE FROM company_regions WHERE company_country_id IN (SELECT id FROM company_countries WHERE company_id = ?)",
                "DELETE FROM company_countries WHERE company_id = ?",
                "DELETE FROM companies WHERE id = ?"));
    }

    /**
     * Runs the deletes in one transaction that states this class's tenant.
     *
     * <p>The boundary is not decoration: a scope declared with no transaction installs nothing
     * ({@code set_tenant_scope} is {@code SET LOCAL}), so a raw {@code JdbcTemplate} delete outside one would
     * match no row under the policy and read as a clean container.</p>
     */
    private void deleteScopedToThisCompany(List<String> statements) {
        TenantFixtures.inTenant(companyId, () -> {
            statements.forEach(statement -> jdbc.update(statement, companyId));
        });
    }

    // ---- the sale, and the callers that make it ----

    /** The one sale this suite is about: two pieces of a product that declares {@code IVA}, at 100 each. */
    private String saleBody() throws Exception {
        var request = new SalesOrderRequest(
                null,
                companyStoreId,
                shiftId,
                "tax-acceptance",
                List.of(new SalesOrderItemRequest(
                        null, variantId, new BigDecimal("2.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null)));
        return objectMapper.writeValueAsString(request);
    }

    /**
     * A caller of this suite's own tenant, with the five {@code company_*} claims the scope guard reads and the
     * one functional role the endpoint under test requires.
     */
    private RequestPostProcessor scopedJwt(String role) {
        var storeChain = chain;
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))
                .jwt(builder -> builder.claim("preferred_username", "tax-acceptance")
                        .claim("company_id", storeChain.companyId().toString())
                        .claim(
                                "company_country_id",
                                storeChain.companyCountryId().toString())
                        .claim("company_region_id", storeChain.regionId().toString())
                        .claim("company_zone_id", storeChain.zoneId().toString())
                        .claim("company_store_id", storeChain.storeId().toString()));
    }

    // ---- the fixture ----

    /** The country, region, zone, store, sales location and settings row a sale needs to be addressable. */
    private void seedStoreTree() {
        var country = countryRepository
                .findByCountryCode("MX")
                .orElseGet(() -> countryRepository.save(Country.builder()
                        .countryCode("MX")
                        .countryName("Mexico")
                        .enabled(true)
                        .build()));

        TenantFixtures.inTenant(companyId, () -> {
            var company = companyRepository.findById(companyId).orElseThrow();
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(companyId, country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));
            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode("TAX-ACC-1")
                                    .regionName("Tax Acceptance Region")
                                    .enabled(true)
                                    .build()));
            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("TAX-ACC-1")
                            .zoneName("Tax Acceptance Zone")
                            .enabled(true)
                            .build()));
            var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Tax Acceptance Store")
                            .enabled(true)
                            .build()));

            companyStoreId = store.getId();
            salesLocationId = ensureSalesLocation(store);
            chain = new StoreChain(companyId, companyCountry.getId(), region.getId(), zone.getId(), store.getId());
        });
    }

    /**
     * The single location this store receives and sells from, plus the settings row that names it: the sales
     * path allocates the ledger against the store's own sales location.
     */
    private UUID ensureSalesLocation(CompanyStore store) {
        var area = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId()).stream()
                .filter(candidate -> "TAX-AREA".equals(candidate.getAreaCode()))
                .findFirst()
                .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                        .companyStore(store)
                        .areaCode("TAX-AREA")
                        .areaName("Sales Area")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));

        var zone = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                .filter(candidate -> "TAX-ZONE".equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                        .storeArea(area)
                        .zoneCode("TAX-ZONE")
                        .zoneName("Sales Zone")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));

        var locationId =
                storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId()).stream()
                        .filter(candidate -> "TAX-01".equals(candidate.getLocationCode()))
                        .findFirst()
                        .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                                .storeZone(zone)
                                .locationCode("TAX-01")
                                .locationName("Sales shelf")
                                .displayOrder(1)
                                .enabled(true)
                                .build()))
                        .getId();

        storeInventorySettingsRepository
                .findByCompanyStoreId(store.getId())
                .orElseGet(() -> storeInventorySettingsRepository.save(StoreInventorySettings.builder()
                        .companyStoreId(store.getId())
                        .receivingLocationId(locationId)
                        .salesLocationId(locationId)
                        .build()));
        return locationId;
    }

    /**
     * A product that declares {@code IVA} and one stocked variant of it.
     *
     * <p>The code is written through {@code Product.Builder} — the column {@code V89} added — so the fixture
     * states the article the sale will read instead of patching a line: the code belongs to the product, and a
     * line has no way to state one. The stock is booked as a real receipt through the production writer, the way
     * the sales-order suite does it, so the accepted sale has something to sell.</p>
     */
    private UUID seedStockedIvaVariant() {
        return TenantFixtures.inTenant(companyId, () -> {
            var product = productRepository.save(Product.builder()
                    .sku("SKU-TAX-ACC-" + UUID.randomUUID().toString().substring(0, 8))
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
            variant.setBarCode("BAR-TAX-ACC-" + UUID.randomUUID().toString().substring(0, 10));
            variant.setVariantName("Variant-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setEnabled(true);
            var saved = productVariantRepository.save(variant);

            productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                    .productVariantId(saved.getId())
                    .companyStoreId(companyStoreId)
                    .costPrice(new BigDecimal("60.00"))
                    .build());
            stockLedgerEngine.applyReceipt(
                    saved.getId(),
                    companyStoreId,
                    salesLocationId,
                    new BigDecimal("100.00"),
                    // An opening balance, not a purchase: no price is stated, so the movement books the declared
                    // cost_price, as this acceptance case always assumed.
                    null,
                    FIXTURE_OPENING,
                    UUID.randomUUID(),
                    "fixture");
            return saved.getId();
        });
    }

    /** An open shift of this store, which is what makes the sale a sale and not a malformed request. */
    private UUID seedOpenShift() {
        return TenantFixtures.inTenant(
                companyId,
                () -> shiftRepository.findByCompanyStoreId(companyStoreId).stream()
                        .findFirst()
                        .orElseGet(() -> shiftRepository.save(Shift.builder()
                                .companyStoreId(companyStoreId)
                                .userId("tax-acceptance")
                                .openedAt(LocalDateTime.now().minusHours(1))
                                .status("ABIERTO")
                                .enabled(true)
                                .build()))
                        .getId());
    }

    /**
     * Forgets every rate this company stated, so a case starts in the state a freshly provisioned company is in
     * — and so a rate written by an earlier case cannot decide this one.
     */
    private void clearTaxRates() {
        inTransaction(() -> jdbc.update("DELETE FROM tax_rate WHERE company_id = ?", companyId));
    }

    /** A read (or write) inside a transaction that states this class's tenant. */
    private <T> T inTransaction(Supplier<T> work) {
        return TenantFixtures.inTenant(companyId, work);
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        assertThat(node.get(field)).as("the response states %s", field).isNotNull();
        return node.get(field).decimalValue();
    }

    /** The real company → country → region → zone → store ids behind the seeded store. */
    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
