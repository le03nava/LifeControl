package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.model.MeasureUnitType;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence-level verification that {@code GET /api/companies/{companyId}/products/{productId}/variants} can be
 * narrowed to a single store through the optional {@code storeId} query param.
 *
 * <p>After the variant-identity split the definitions are GLOBAL and the per-store presence lives
 * in {@code product_variant_store_stock}, so the filter is applied by the explicit {@code @Query}
 * join, not by the controller. Runs against real PostgreSQL with Flyway enabled; a valid result
 * proves the join maps to real SQL. The parameterless call must keep returning the product's
 * definitions, with the store-scoped fields null.</p>
 *
 * <p>The store-scoped branch is also predicated on the company that owns the store, and that half is
 * only provable here: a mock can confirm which arguments reached the call, never that the predicate
 * kept a foreign store's rows out. The case that makes it necessary is the last test — a variant of
 * this company stocked at a store of another one, which the store join reaches and the company
 * predicate does not.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Product Variant Store Filter Integration Tests")
class ProductVariantStoreFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

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

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private UUID companyId;
    private UUID productId;
    private UUID storeAId;
    private UUID storeBId;
    private UUID foreignStoreId;
    private UUID variantAId;
    private UUID variantBId;

    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdStoreStockIds = new ArrayList<>();
    private final List<UUID> createdMeasureUnitIds = new ArrayList<>();
    private UUID createdProductId;

    @BeforeEach
    void setUp() {
        seedStoreHierarchy();

        var product = productRepository.save(Product.builder()
                .sku("VARSTORE-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Variant Store Filter Product")
                .company(companyRepository.findById(companyId).orElseThrow())
                .enabled(true)
                .build());
        productId = product.getId();
        createdProductId = productId;

        variantAId = createVariant(productId, storeAId, "Talla A").getId();
        variantBId = createVariant(productId, storeBId, "Talla B").getId();
    }

    @AfterEach
    void tearDown() {
        // Delete only this test's rows so other suites sharing the per-JVM container are untouched.
        productVariantStoreStockRepository.deleteAllById(createdStoreStockIds);
        createdStoreStockIds.clear();
        productVariantRepository.deleteAllById(createdVariantIds);
        createdVariantIds.clear();

        if (createdProductId != null) {
            productRepository.deleteById(createdProductId);
            createdProductId = null;
        }

        // The catalog rows go last: a variant may reference them until its own row is gone.
        measureUnitRepository.deleteAllById(createdMeasureUnitIds);
        createdMeasureUnitIds.clear();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone chain plus two stores in
     * the same zone. Idempotent because the Testcontainers database is shared per JVM.
     */
    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("VARIANT-FILTER-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("VARIANT-FILTER-KEY")
                        .companyName("Variant Filter Test Company")
                        .rfc("VFTC010101ABC")
                        .enabled(true)
                        .build()));
        companyId = company.getId();

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("VF")
                        .regionName("Variant Filter Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("VF")
                        .zoneName("Variant Filter Zone")
                        .enabled(true)
                        .build()));

        storeAId = findOrCreateStore(zone, "Variant Filter Store A");
        storeBId = findOrCreateStore(zone, "Variant Filter Store B");

        // A second tenant with its own tree and one store: the counterpart of the company predicate
        // of the store-scoped branch, which is the tenant the store belongs to and not this suite's.
        var foreignZone = seedForeignZone();
        foreignStoreId = findOrCreateStore(foreignZone, "Variant Filter Foreign Store");
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone chain of a second tenant. */
    private CompanyZone seedForeignZone() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("VARIANT-FILTER-FOREIGN-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("VARIANT-FILTER-FOREIGN-KEY")
                        .companyName("Variant Filter Foreign Company")
                        .rfc("VFFC010101AB")
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("VF")
                        .regionName("Variant Filter Foreign Region")
                        .enabled(true)
                        .build()));

        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("VF")
                        .zoneName("Variant Filter Foreign Zone")
                        .enabled(true)
                        .build()));
    }

    private UUID findOrCreateStore(CompanyZone zone, String storeName) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> storeName.equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(storeName)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private ProductVariant createVariant(UUID targetProductId, UUID companyStoreId, String variantName) {
        var variant = ProductVariant.builder()
                .productId(targetProductId)
                .barCode("VF-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                .variantName(variantName)
                .enabled(true)
                .build();

        var saved = productVariantRepository.save(variant);
        createdVariantIds.add(saved.getId());

        stockVariantAt(saved.getId(), companyStoreId);
        return saved;
    }

    private List<String> returnedIds(String responseBody) throws Exception {
        var ids = new ArrayList<String>();
        for (var node : objectMapper.readTree(responseBody).get("content")) {
            ids.add(node.get("id").asText());
        }
        return ids;
    }

    private static final String VARIANTS_URL = "/api/companies/{companyId}/products/{productId}/variants";

    @Test
    @DisplayName("should return only store A's variants when storeId is store A")
    void listVariants_StoreA_ReturnsOnlyStoreAVariants() throws Exception {
        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeAId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantAId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).containsExactly(variantAId.toString());
    }

    @Test
    @DisplayName("should return only store B's variants when storeId is store B")
    void listVariants_StoreB_ReturnsOnlyStoreBVariants() throws Exception {
        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeBId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(variantBId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeBId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).containsExactly(variantBId.toString());
    }

    @Test
    @DisplayName("should return variants from every store when storeId is omitted")
    void listVariants_WithoutStoreId_ReturnsVariantsFromBothStores() throws Exception {
        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString()))
                .containsExactlyInAnyOrder(variantAId.toString(), variantBId.toString());
    }

    @Test
    @DisplayName("should hide a disabled global definition by default and list it with includeDisabled=true")
    void listVariants_IncludeDisabled_ControlsDisabledGlobalDefinitions() throws Exception {
        softDeleteVariant(variantAId);

        // Default: the soft-deleted definition is filtered out, so re-enable would be unreachable.
        var defaultResult = mockMvc.perform(
                        get(VARIANTS_URL, companyId, productId).with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(returnedIds(defaultResult.getResponse().getContentAsString()))
                .containsExactly(variantBId.toString());

        // Opt-in: the disabled definition reappears so it can be re-enabled.
        var includeResult = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("includeDisabled", "true")
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        assertThat(returnedIds(includeResult.getResponse().getContentAsString()))
                .containsExactlyInAnyOrder(variantAId.toString(), variantBId.toString());
    }

    @Test
    @DisplayName("should keep excluding a disabled variant from the store-scoped branch even with includeDisabled=true")
    void listVariants_StoreScoped_IgnoresIncludeDisabled() throws Exception {
        softDeleteVariant(variantAId);

        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeAId.toString())
                        .param("includeDisabled", "true")
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andReturn();

        assertThat(returnedIds(result.getResponse().getContentAsString())).isEmpty();
    }

    /** Flips the definition's {@code enabled} flag without deleting the per-store row. */
    private void softDeleteVariant(UUID targetVariantId) {
        var variant = productVariantRepository.findById(targetVariantId).orElseThrow();
        variant.setEnabled(false);
        productVariantRepository.save(variant);
    }

    @Test
    @DisplayName("should answer nothing when the store belongs to another company")
    void listVariants_StoreOfAnotherCompany_ReturnsNothing() throws Exception {
        // The row the store join reaches belongs to this company's product but to the other tenant's
        // store. Stocking a variant in a store of another company is not refused anywhere (the pair
        // has no constraint, and `company_stores` has no company column), so the company predicate of
        // the query is what keeps it out: without it this call returns the variant.
        stockVariantAt(variantAId, foreignStoreId);

        mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", foreignStoreId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Writes one more per-store row for an existing variant. */
    private UUID stockVariantAt(UUID targetVariantId, UUID targetStoreId) {
        var storeStock = productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(targetVariantId)
                .companyStoreId(targetStoreId)
                .listPrice(new BigDecimal("10.00"))
                .costPrice(new BigDecimal("5.00"))
                .stock(BigDecimal.ZERO)
                .build());
        createdStoreStockIds.add(storeStock.getId());
        return storeStock.getId();
    }

    /**
     * D4's two reads are raw HQL (an entity join onto {@code measure_units} through a plain UUID
     * column), so a mock cannot prove them: this test runs them as real SQL.
     */
    @Test
    @DisplayName("D4: the unit_type reads return what the product's variants use and can exclude one")
    void usedMeasureUnitTypes_ReadRealSql() {
        var pieceUnit = measureUnitRepository.save(MeasureUnit.builder()
                .measureUnitName("Pieza")
                .measureUnitShortName("Pza")
                .unitType(MeasureUnitType.PRODUCT)
                // The catalog is seeded from the SAT list and sat_code is unique, so the fixture
                // cannot reuse a real clave: it generates one of its own.
                .satCode("Z" + UUID.randomUUID().toString().substring(0, 4).toUpperCase())
                .enabled(true)
                .build());
        createdMeasureUnitIds.add(pieceUnit.getId());
        assignMeasureUnit(variantAId, pieceUnit.getId());

        // Variant B still declares no unit, so the join drops it: the product uses exactly one type.
        assertThat(productVariantRepository.findUsedMeasureUnitTypesByProductId(productId))
                .containsExactly(MeasureUnitType.PRODUCT);
        // Excluding the only variant that declares a unit leaves no constraint at all.
        assertThat(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(
                        productId, variantAId))
                .isEmpty();
        // Excluding the unitless variant keeps the one in use, which is what D4 compares against.
        assertThat(productVariantRepository.findUsedMeasureUnitTypesByProductIdExcludingVariantId(
                        productId, variantBId))
                .containsExactly(MeasureUnitType.PRODUCT);
    }

    private void assignMeasureUnit(UUID targetVariantId, UUID measureUnitId) {
        var variant = productVariantRepository.findById(targetVariantId).orElseThrow();
        variant.setMeasureUnitId(measureUnitId);
        productVariantRepository.save(variant);
    }
}
