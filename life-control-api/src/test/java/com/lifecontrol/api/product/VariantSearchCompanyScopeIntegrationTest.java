package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/product-variants/search} is scoped by the company that owns the store in the query
 * string, and the only place that scope can be proved is SQL: the store grant says who may read
 * <em>this</em> store, it says nothing about which company's catalog the rows joined onto that store
 * belong to.
 *
 * <p>The fixture is the shape that makes the rule necessary rather than decorative — two separate
 * companies, one store each, a variant in each whose name matches the same text, and the one row that
 * makes the hole reachable: company B's variant also carries a store row at company A's store. Nothing
 * in the schema forbids that row (the search joins {@code product_variant_store_stock}, and stocking a
 * variant is checked against the store grant and the variant's existence, never against the pair's
 * company — {@code company_stores} has no {@code company_id} at all), so a search without the company
 * predicate returns both rows and the assertion of exactly one row fails instead of quietly agreeing
 * with itself.</p>
 *
 * <p>The two products cannot share a SKU or the two variants a barcode, because V39 left
 * {@code uk_products_sku} and {@code uk_product_variants_bar_code} global; that is T4's contract step
 * and is deliberately not anticipated here. The text both rows match is the variant and product name,
 * which carries no uniqueness at any level.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Product Variant Search — company scope integration")
class VariantSearchCompanyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "vscs";
    private static final String COMPANY_A_KEY = "VSCS-COMPANY-A";
    private static final String COMPANY_B_KEY = "VSCS-COMPANY-B";
    /** {@code companies.rfc} is {@code VARCHAR(13)}, so the fixture stays inside it. */
    private static final String COMPANY_A_RFC = "VSCSA010101AB";

    private static final String COMPANY_B_RFC = "VSCSB010101AB";
    private static final String SEARCH_URL = "/api/product-variants/search";

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

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

    private UUID companyAId;
    private UUID companyBId;
    private UUID storeAId;
    private UUID storeBId;

    private UUID productAId;
    private UUID productBId;
    private UUID variantAId;
    private UUID variantBId;

    private final List<UUID> createdStoreStockIds = new ArrayList<>();
    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdProductIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();

        var companyA = seedStoreFront(COMPANY_A_KEY, "VSCS Company A", COMPANY_A_RFC, "VSCS Store A");
        var companyB = seedStoreFront(COMPANY_B_KEY, "VSCS Company B", COMPANY_B_RFC, "VSCS Store B");
        companyAId = companyA.companyId();
        storeAId = companyA.storeId();
        companyBId = companyB.companyId();
        storeBId = companyB.storeId();

        // Both products and both variants carry the same searchable text, so only the company
        // predicate can separate them.
        var productA = createProduct(companyAId, "Martillo A");
        var productB = createProduct(companyBId, "Martillo B");
        productAId = productA.getId();
        productBId = productB.getId();

        variantAId = createVariant(productAId, companyAId, storeAId, "Talla A");
        variantBId = createVariant(productBId, companyBId, storeBId, "Talla B");

        // The reachable hole: company B's variant stocked at company A's store. Written here rather
        // than through the write endpoint so the search rule is proved on its own, and because the
        // rule that would refuse such a pair (a variant and a store of the same company) does not
        // exist yet — no constraint can express it, since `company_stores` has no company column and
        // the company is derived through zone -> region -> country.
        stockVariantAt(variantBId, storeAId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ─── The rule: the store's company is a query predicate ───────────────

    @Test
    @DisplayName("should return only company A's variant when searching through company A's store")
    void searchThroughCompanyAStore_ReturnsOnlyCompanyAVariant() throws Exception {
        // Company B's variant is present at company A's store (see setUp): without the company
        // predicate this returns two rows.
        var result = mockMvc.perform(get(SEARCH_URL)
                        .param("q", PREFIX)
                        .param("storeId", storeAId.toString())
                        .with(ownerPrincipal(companyAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantAId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).containsExactly(variantAId.toString());
    }

    @Test
    @DisplayName("should return only company B's variant when searching through company B's store")
    void searchThroughCompanyBStore_ReturnsOnlyCompanyBVariant() throws Exception {
        // The mirror case: company A's variant is not stocked in company B's store, so this one holds
        // before and after the predicate and pins that the filter did not simply empty the page.
        var result = mockMvc.perform(get(SEARCH_URL)
                        .param("q", PREFIX)
                        .param("storeId", storeBId.toString())
                        .with(ownerPrincipal(companyBId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantBId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeBId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).containsExactly(variantBId.toString());
    }

    // ─── Preserved behavior: the text match and the store filter still work ─

    @Test
    @DisplayName("should keep matching an exact barcode inside the store's company")
    void searchByExactBarcode_StillMatches() throws Exception {
        // `product_variants` is armed: the read states the company the variant belongs to.
        var barCode = TenantFixtures.inTenant(
                        companyAId,
                        () -> productVariantRepository.findById(variantAId).orElseThrow())
                .getBarCode();

        var result = mockMvc.perform(get(SEARCH_URL)
                        .param("q", barCode)
                        .param("storeId", storeAId.toString())
                        .with(ownerPrincipal(companyAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantAId.toString()))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).containsExactly(variantAId.toString());
    }

    @Test
    @DisplayName("should answer an empty page when the text matches nothing")
    void searchWithoutMatches_AnswersEmptyPage() throws Exception {
        var result = mockMvc.perform(get(SEARCH_URL)
                        .param("q", PREFIX + "-no-such-text")
                        .param("storeId", storeAId.toString())
                        .with(ownerPrincipal(companyAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).isEmpty();
    }

    // ─── Fixture ─────────────────────────────────────────────────────────

    private List<String> returnedIds(String responseBody) throws Exception {
        var ids = new ArrayList<String>();
        for (var node : objectMapper.readTree(responseBody).get("content")) {
            ids.add(node.get("id").asText());
        }
        return ids;
    }

    /** A product of {@code ownerId}: the company read and the armed INSERT state that tenant. */
    private Product createProduct(UUID ownerId, String name) {
        var product = TenantFixtures.inTenant(
                ownerId,
                () -> productRepository.save(Product.builder()
                        .sku("VSCS-"
                                + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .name(PREFIX + " " + name)
                        .company(companyRepository.findById(ownerId).orElseThrow())
                        .enabled(true)
                        .build()));
        createdProductIds.add(product.getId());
        return product;
    }

    /** A variant of {@code ownerId}: {@code product_variants} is armed for the insert. */
    /**
     * A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. The unit is required
     * since ADR-0006 decision 3 ({@code V55}), and a random uuid would be refused by the foreign key —
     * which would make the fixture fail for a reason that says nothing about the rule under test.
     */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    private UUID createVariant(UUID targetProductId, UUID ownerId, UUID companyStoreId, String variantName) {
        var variant = TenantFixtures.inTenant(
                ownerId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(targetProductId)
                        .companyId(ownerId)
                        .measureUnitId(pieceUnitId())
                        .barCode("VSCS-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName(PREFIX + " " + variantName)
                        .enabled(true)
                        .build()));
        createdVariantIds.add(variant.getId());

        stockVariantAt(variant.getId(), companyStoreId);
        return variant.getId();
    }

    private void stockVariantAt(UUID targetVariantId, UUID companyStoreId) {
        var storeStock = productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(targetVariantId)
                .companyStoreId(companyStoreId)
                .listPrice(new BigDecimal("10.00"))
                .costPrice(new BigDecimal("5.00"))
                .stock(BigDecimal.ZERO)
                .build());
        createdStoreStockIds.add(storeStock.getId());
    }

    private record StoreFront(UUID companyId, UUID storeId) {}

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain of one
     * tenant, so a suite that runs twice against the shared per-JVM container does not collide on the
     * unique keys. The catalog rows are per run and are removed in teardown.
     */
    private StoreFront seedStoreFront(String companyKey, String companyName, String rfc, String storeName) {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = TenantFixtures.findOrCreate(companyRepository, companyKey, companyName, rfc);

        // One boundary per tenant: every node and store is a row on a policy-armed table and references
        // the detached Company, so the find-or-create reads and the INSERTs both need that tenant.
        var storeId = TenantFixtures.inTenant(company.getId(), () -> {
            var companyCountry = companyCountryRepository
                    .findByCompanyIdAndCountryId(company.getId(), country.getId())
                    .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                            .company(company)
                            .country(country)
                            .build()));

            var region =
                    companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                            .findFirst()
                            .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                                    .companyCountry(companyCountry)
                                    .regionCode("VS")
                                    .regionName("VSCS Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("VS")
                            .zoneName("VSCS Zone")
                            .enabled(true)
                            .build()));

            return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> storeName.equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName(storeName)
                            .enabled(true)
                            .build()))
                    .getId();
        });

        return new StoreFront(company.getId(), storeId);
    }

    private void cleanUp() {
        // Leaf first: the per-store rows reference the variant, the variant references the product.
        productVariantStoreStockRepository.deleteAllById(createdStoreStockIds);
        createdStoreStockIds.clear();
        // `product_variants` and `products` are armed, so the ids are deleted once per tenant: a delete
        // under a tenant that does not own the id removes nothing, which is what makes two passes enough.
        for (var tenant : java.util.stream.Stream.of(companyAId, companyBId)
                .filter(java.util.Objects::nonNull)
                .toList()) {
            TenantFixtures.inTenant(tenant, () -> {
                productVariantRepository.deleteAllById(createdVariantIds);
                productRepository.deleteAllById(createdProductIds);
            });
        }
        createdVariantIds.clear();
        createdProductIds.clear();
    }

    /**
     * An admin principal that states {@code companyId}.
     *
     * <p>The variant search names no company in its path, so the request's tenant comes from the token's
     * claims (T5b) and a token carrying none states none: the store named by {@code storeId} is then
     * invisible and the answer is 404 for every case. The claim belongs to the fixture — each case states
     * the company whose store it searches through.</p>
     */
    private RequestPostProcessor ownerPrincipal(UUID companyId) {
        return jwt().authorities(ROLE_LC_ADMIN)
                .jwt(builder -> builder.claim("preferred_username", "admin").claim("company_id", companyId.toString()));
    }
}
