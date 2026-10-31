package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * The per-store write and the company-scoped variant lookups, proved on PostgreSQL.
 *
 * <p>Two rules live here, and both are about a pair the schema cannot refuse.</p>
 *
 * <p><b>The store-stock pair.</b> {@code product_variant_store_stock} says a variant is stocked and
 * priced at a store; nothing says the two sides belong to the same company, because
 * {@code company_stores} carries no {@code company_id} at all — the company is derived through
 * zone &rarr; region &rarr; country. So {@code PUT /api/variants/{variantId}/stores/{storeId}} could
 * be used to attach company B's variant to company A's store, and every store-scoped read of company
 * A would then be reading rows of another tenant. The rule that closes it is in the service, and this
 * suite is where it is proved against real SQL, together with the half that matters most: nothing is
 * persisted.</p>
 *
 * <p><b>The lookups.</b> The three consumer paths (purchasing, sales, inventory) scope a variant to
 * the company of the document's store. In a mocked suite that can only be proved by the arguments a
 * mock was asked for; here it is proved as a predicate, because a post-filter and a query filter
 * answer the same question one step too late for a row that is already in the result set.</p>
 *
 * <p>The fixture never relies on the per-company keys V40 introduced to keep the two tenants apart —
 * SKUs and barcodes are randomised — so a failure is always about the company and never about a
 * uniqueness collision.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Product Variant Stock — company scope integration")
class ProductVariantStockCompanyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "pvcs";
    private static final String COMPANY_A_KEY = "PVCS-COMPANY-A";
    private static final String COMPANY_B_KEY = "PVCS-COMPANY-B";
    /** {@code companies.rfc} is {@code VARCHAR(13)}, so the fixture stays inside it. */
    private static final String COMPANY_A_RFC = "PVCSA010101AB";

    private static final String COMPANY_B_RFC = "PVCSB010101AB";
    private static final String STORE_STOCK_URL = "/api/variants/{variantId}/stores/{storeId}";

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
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

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

    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdProductIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();

        var companyA = seedStoreFront(COMPANY_A_KEY, "PVCS Company A", COMPANY_A_RFC, "PVCS Store A");
        var companyB = seedStoreFront(COMPANY_B_KEY, "PVCS Company B", COMPANY_B_RFC, "PVCS Store B");
        companyAId = companyA.companyId();
        storeAId = companyA.storeId();
        companyBId = companyB.companyId();
        storeBId = companyB.storeId();

        var productA = createProduct(companyAId, "Company A product");
        var productB = createProduct(companyBId, "Company B product");
        productAId = productA.getId();
        productBId = productB.getId();

        variantAId = createVariant(productAId, companyAId, "Talla A");
        variantBId = createVariant(productBId, companyBId, "Talla B");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ─── The rule: the store-stock pair is one company's ──────────────────

    @Nested
    @DisplayName("the store-stock write")
    class StoreStockWriteTests {

        @Test
        @DisplayName("should refuse to stock company B's variant in company A's store and write nothing")
        void foreignCompanyVariant_IsRefusedAndNothingIsPersisted() throws Exception {
            var body = objectMapper.createObjectNode();
            body.put("listPrice", new BigDecimal("30.00"));

            // The path names no company, so the tenant comes from the token's claims: without one the
            // variant is invisible and this 404 would be the policy's, not the ownership rule's.
            mockMvc.perform(put(STORE_STOCK_URL, variantBId, storeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(ownerPrincipal(companyBId)))
                    .andExpect(status().isNotFound());

            // Nothing persisted: no row for the pair, and no ledger row either.
            assertThat(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                            variantBId, storeAId))
                    .isFalse();
            assertThat(inventoryMovementRepository.findAll().stream()
                            .filter(movement -> variantBId.equals(movement.getProductVariantId())))
                    .isEmpty();
        }

        @Test
        @DisplayName("should keep accepting the variant's own company's store")
        void sameCompanyPair_IsStillWritable() throws Exception {
            var body = objectMapper.createObjectNode();
            body.put("listPrice", new BigDecimal("30.00"));

            mockMvc.perform(put(STORE_STOCK_URL, variantBId, storeBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(ownerPrincipal(companyBId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyStoreId").value(storeBId.toString()));

            var row = productVariantStoreStockRepository
                    .findByProductVariantIdAndCompanyStoreId(variantBId, storeBId)
                    .orElseThrow();
            assertThat(row.getListPrice()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("should still answer 404 when the store does not exist at all")
        void unknownStore_IsStillNotFound() throws Exception {
            var body = objectMapper.createObjectNode();
            body.put("listPrice", new BigDecimal("30.00"));

            mockMvc.perform(put(STORE_STOCK_URL, variantAId, UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound());

            assertThat(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                            variantAId, storeAId))
                    .isFalse();
        }
    }

    // ─── The rule: the three consumer lookups are predicates, not post-filters ─

    @Nested
    @DisplayName("the consumer lookups")
    class ConsumerLookupTests {

        @Test
        @DisplayName("should resolve a variant by (product, company) only inside its own company")
        void purchasingLookup_IsCompanyScoped() {
            // The purchasing path: a line names a product and a variant, and the store supplies the
            // company. Company A's answer must not contain company B's variant even though the pair
            // (variant, product) is exactly right.
            assertThat(byProduct(variantBId, productBId, companyBId)).isPresent();
            assertThat(byProduct(variantBId, productBId, companyAId)).isEmpty();
            assertThat(byProduct(variantAId, productAId, companyAId)).isPresent();
        }

        @Test
        @DisplayName("should answer a bare (variant, company) existence question only for its owner")
        void salesLookup_IsCompanyScoped() {
            assertThat(existsFor(variantBId, companyBId)).isTrue();
            assertThat(existsFor(variantBId, companyAId)).isFalse();
        }

        @Test
        @DisplayName("should resolve a variant by (id, company) only inside its own company")
        void inventoryLookup_IsCompanyScoped() {
            // What the receipt and the sale deduction resolve before they take any lock.
            assertThat(byId(variantBId, companyBId)).isPresent();
            assertThat(byId(variantBId, companyAId)).isEmpty();
        }

        @Test
        @DisplayName("should still refuse a soft-deleted variant in its own company")
        void scopedLookups_StillFilterDisabled() {
            var variant = TenantFixtures.inTenant(
                    companyAId,
                    () -> productVariantRepository.findById(variantAId).orElseThrow());
            variant.setEnabled(false);
            TenantFixtures.inTenant(companyAId, () -> productVariantRepository.save(variant));

            assertThat(byId(variantAId, companyAId)).isEmpty();
            assertThat(existsFor(variantAId, companyAId)).isFalse();
            assertThat(byProduct(variantAId, productAId, companyAId)).isEmpty();
        }

        // `product_variants` is policy-armed, so every one of these reads states the company its
        // predicate already names — the fixture's job, not the finder's: the predicate is unchanged.

        private java.util.Optional<com.lifecontrol.api.product.model.ProductVariant> byId(
                UUID variantId, UUID companyId) {
            return TenantFixtures.inTenant(
                    companyId, () -> productVariantRepository.findByIdAndCompanyIdAndEnabledTrue(variantId, companyId));
        }

        private boolean existsFor(UUID variantId, UUID companyId) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> productVariantRepository.existsByIdAndCompanyIdAndEnabledTrue(variantId, companyId));
        }

        private java.util.Optional<com.lifecontrol.api.product.model.ProductVariant> byProduct(
                UUID variantId, UUID productId, UUID companyId) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> productVariantRepository.findByIdAndProductIdAndCompanyIdAndEnabledTrue(
                            variantId, productId, companyId));
        }
    }

    // ─── Fixture ─────────────────────────────────────────────────────────

    /** A product of {@code ownerId}: the company read and the armed INSERT state that tenant. */
    private Product createProduct(UUID ownerId, String name) {
        var product = TenantFixtures.inTenant(
                ownerId,
                () -> productRepository.save(Product.builder()
                        .sku("PVCS-"
                                + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .name(PREFIX + " " + name)
                        .company(companyRepository.findById(ownerId).orElseThrow())
                        .enabled(true)
                        .build()));
        createdProductIds.add(product.getId());
        return product;
    }

    /** A variant of {@code ownerId}: {@code product_variants} is armed for the insert. */
    private UUID createVariant(UUID targetProductId, UUID ownerId, String variantName) {
        var variant = TenantFixtures.inTenant(
                ownerId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(targetProductId)
                        .companyId(ownerId)
                        .barCode("PVCS-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName(PREFIX + " " + variantName)
                        .enabled(true)
                        .build()));
        createdVariantIds.add(variant.getId());
        return variant.getId();
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
                                    .regionCode("PV")
                                    .regionName("PVCS Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("PV")
                            .zoneName("PVCS Zone")
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
        for (var variantId : createdVariantIds) {
            for (var storeId : List.of(storeAId, storeBId)) {
                if (storeId == null) {
                    continue;
                }
                productVariantStoreStockRepository
                        .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                        .ifPresent(row -> productVariantStoreStockRepository.deleteById(row.getId()));
            }
        }
        inventoryMovementRepository.deleteAll(inventoryMovementRepository.findAll().stream()
                .filter(movement -> createdVariantIds.contains(movement.getProductVariantId()))
                .toList());
        // `products` and `product_variants` are armed, so the ids are deleted once per tenant: a delete
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
     * <p>{@code /api/variants/{variantId}/stores/{storeId}} names no company in its path, so the tenant of
     * the request comes from the token's claims and a token carrying none states none: the variant and the
     * store are then invisible and the answer is 404 whatever the ownership rule says.</p>
     */
    private RequestPostProcessor ownerPrincipal(UUID companyId) {
        return jwt().authorities(ROLE_LC_ADMIN)
                .jwt(builder -> builder.claim("preferred_username", "admin").claim("company_id", companyId.toString()));
    }
}
