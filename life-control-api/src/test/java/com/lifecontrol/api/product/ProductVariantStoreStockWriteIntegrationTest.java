package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end coverage of the per-store write endpoint
 * {@code PUT /api/variants/{variantId}/stores/{storeId}}.
 *
 * <p>Closes two findings the parent feature left as follow-ups: {@code JD-B-005} (the write endpoint
 * had no end-to-end coverage, so the {@code ON CONFLICT} insert never executed in tests) and
 * {@code JD-B-006} (nothing proved that one definition can serve two stores with distinct stock).</p>
 *
 * <p>Runs against real PostgreSQL with Flyway enabled, because both findings are about SQL
 * behaviour that only exists in the database: the {@code DO NOTHING} branch of an
 * {@code INSERT ... ON CONFLICT}, the column defaults on insert, and the {@code UNIQUE} constraint
 * that keeps one row per {@code (variant, store)} pair. That constraint is also the reason the
 * insert cannot be replaced by a caught {@code DataIntegrityViolationException}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Product Variant Store Stock Write Integration Tests")
class ProductVariantStoreStockWriteIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private static final String STORE_STOCK_URL = "/api/variants/{variantId}/stores/{storeId}";
    private static final String VARIANTS_URL = "/api/products/{productId}/variants";

    private UUID productId;
    private UUID storeAId;
    private UUID storeBId;
    /** A variant no test has written to unless it asks for it. */
    private UUID bareVariantId;
    /** The single definition the two-store case shares. */
    private UUID sharedVariantId;

    private final List<UUID> createdVariantIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seedStoreHierarchy();

        var product = productRepository.save(Product.builder()
                .sku("VARSTOCK-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Variant Store Stock Product")
                .enabled(true)
                .build());
        productId = product.getId();

        // Neither variant starts with a store row: creating it is part of what is under test.
        bareVariantId = createVariant("Talla A");
        sharedVariantId = createVariant("Talla B");
    }

    @AfterEach
    void tearDown() {
        // Delete only this test's rows: the Testcontainers database is shared per JVM and the
        // tests are not transactional, so the next test would otherwise inherit these rows.
        if (!createdVariantIds.isEmpty() && storeAId != null && storeBId != null) {
            for (var variantId : createdVariantIds) {
                for (var storeId : List.of(storeAId, storeBId)) {
                    productVariantStoreStockRepository
                            .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                            .ifPresent(row -> productVariantStoreStockRepository.deleteById(row.getId()));
                }
            }
        }
        productVariantRepository.deleteAllById(createdVariantIds);
        createdVariantIds.clear();

        if (productId != null) {
            productRepository.deleteById(productId);
            productId = null;
        }
    }

    // ─────────────────────────── JD-B-005 ───────────────────────────

    @Test
    @DisplayName("JD-B-005: the first write inserts the row, and the next one keeps what it did not send")
    void upsertStoreStock_FirstWriteInsertsRow_SecondWriteKeepsOmittedFields() throws Exception {
        // Precondition that makes the insert path the only way through.
        assertThat(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                        bareVariantId, storeAId))
                .isFalse();

        var firstBody = objectMapper.createObjectNode();
        firstBody.put("listPrice", new BigDecimal("25.50"));
        firstBody.put("costPrice", new BigDecimal("12.00"));
        firstBody.put("stock", new BigDecimal("7"));

        mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstBody))
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.stock").value(7));

        var inserted = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        assertThat(inserted.getListPrice()).isEqualByComparingTo("25.50");
        assertThat(inserted.getCostPrice()).isEqualByComparingTo("12.00");
        assertThat(inserted.getStock()).isEqualByComparingTo("7");

        // The row now exists, so this write's insert takes the ON CONFLICT DO NOTHING branch
        // instead of failing on UNIQUE(product_variant_id, company_store_id). `costPrice` is sent as
        // an explicit null and `listPrice` is absent: both mean "keep the stored value", and
        // neither is a reset to zero.
        var secondBody = objectMapper.createObjectNode();
        secondBody.put("stock", new BigDecimal("11"));
        secondBody.putNull("costPrice");

        mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondBody))
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(11));

        var updated = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        // Same row: the conflict did nothing, it did not create a second one.
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(updated.getStock()).isEqualByComparingTo("11");
        assertThat(updated.getListPrice()).isEqualByComparingTo("25.50");
        assertThat(updated.getCostPrice()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("JD-B-005: an empty body creates the row with the column defaults")
    void upsertStoreStock_EmptyBody_InsertsRowWithColumnDefaults() throws Exception {
        mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()));

        var row = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        // stock has a NOT NULL DEFAULT 0 in the migration; the two prices have no default.
        assertThat(row.getStock()).isEqualByComparingTo("0");
        assertThat(row.getListPrice()).isNull();
        assertThat(row.getCostPrice()).isNull();
    }

    @Test
    @DisplayName("JD-B-005: an unknown variant is a 404 and writes nothing")
    void upsertStoreStock_UnknownVariant_IsNotFound() throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal("3"));

        mockMvc.perform(put(STORE_STOCK_URL, UUID.randomUUID(), storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                        bareVariantId, storeAId))
                .isFalse();
    }

    // ─────────────────────────── JD-B-006 ───────────────────────────

    @Test
    @DisplayName("JD-B-006: one definition serves two stores with distinct stock")
    void upsertStoreStock_OneDefinitionServesTwoStoresWithDistinctStock() throws Exception {
        writeStock(sharedVariantId, storeAId, "5");
        writeStock(sharedVariantId, storeBId, "9");

        var rowA = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(sharedVariantId, storeAId)
                .orElseThrow();
        var rowB = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(sharedVariantId, storeBId)
                .orElseThrow();

        assertThat(rowA.getId()).isNotEqualTo(rowB.getId());
        assertThat(rowA.getStock()).isEqualByComparingTo("5");
        assertThat(rowB.getStock()).isEqualByComparingTo("9");

        // Two store writes, still two definitions: the write hangs rows off the definition
        // instead of duplicating it, which is the whole point of the split.
        assertThat(productVariantRepository.findByProductId(productId)).hasSize(2);

        // And the read the UI uses reports each store's own stock for the same definition.
        mockMvc.perform(get(VARIANTS_URL, productId)
                        .param("storeId", storeAId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sharedVariantId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.content[0].stock").value(5));

        mockMvc.perform(get(VARIANTS_URL, productId)
                        .param("storeId", storeBId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sharedVariantId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeBId.toString()))
                .andExpect(jsonPath("$.content[0].stock").value(9));
    }

    @Test
    @DisplayName("JD-B-006: the definition stays absent from a store nobody wrote to")
    void upsertStoreStock_StoreWithoutARow_DoesNotListTheDefinition() throws Exception {
        writeStock(sharedVariantId, storeAId, "5");

        // Store B has no row for the definition, so the inner join leaves it out: writing a store
        // row is what makes a definition operational in that store.
        mockMvc.perform(get(VARIANTS_URL, productId)
                        .param("storeId", storeBId.toString())
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void writeStock(UUID variantId, UUID storeId, String stock) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal(stock));

        mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk());
    }

    private UUID createVariant(String variantName) {
        var saved = productVariantRepository.save(ProductVariant.builder()
                .productId(productId)
                .barCode("VS-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                .variantName(variantName)
                .enabled(true)
                .build());
        createdVariantIds.add(saved.getId());
        return saved.getId();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone chain plus two stores in
     * the same zone. Idempotent because the Testcontainers database is shared per JVM.
     */
    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("VARIANT-STOCK-WRITE-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("VARIANT-STOCK-WRITE-KEY")
                        .companyName("Variant Stock Write Test Company")
                        .rfc("VSWT010101ABC")
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
                        .regionCode("VS")
                        .regionName("Variant Stock Write Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("VS")
                        .zoneName("Variant Stock Write Zone")
                        .enabled(true)
                        .build()));

        storeAId = findOrCreateStore(zone, "Variant Stock Write Store A");
        storeBId = findOrCreateStore(zone, "Variant Stock Write Store B");
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
}
