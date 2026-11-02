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
import com.lifecontrol.api.product.model.VariantListing;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.product.repository.VariantListingRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The four states of ADR-0006 decision 6, read where they are decided: the two store-scoped queries.
 *
 * <p>Decision 6 (<code>docs/adr/0006-catalog-identity.md:60-64</code>) splits one fact into two — "sellable
 * in this store" is {@code variant_listing}, "there is stock here" is the position — and migration step 5
 * (<code>:153-156</code>) states the read consequence: the POS and the search "join the listing (price) and
 * the position projection (quantity) independently, so all four states are representable and a variant with
 * stock and no listing is returned, flagged, instead of missing".</p>
 *
 * <p><b>Why this cannot be a mock.</b> The defect the slice removes is a join: both queries
 * {@code INNER JOIN}ed {@code product_variant_store_stock}, so "stock arrived without a price" was not a
 * state the response could describe — it was a row that did not exist. Only real SQL proves the two joins
 * are independent, and only a real row per state proves all four are still reachable.</p>
 *
 * <p><b>The fixture is the table, one variant per row.</b> Four variants of one {@code MULTI} product in one
 * store: no listing and no stock (invisible), listing only (visible, nothing to sell), stock only
 * (<b>stock without price</b>, the state this slice exists to stop hiding) and both (sellable). The article
 * is {@code MULTI} because since {@code V56} a {@code SIMPLE} product refuses a second variant and this suite
 * is about the read, not about the mode.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Variant listing reads (ADR-0006 decision 6)")
class ProductVariantListingReadIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String VARIANTS_URL = "/api/companies/{companyId}/products/{productId}/variants";
    private static final String SEARCH_URL = "/api/product-variants/search";

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
    private VariantListingRepository variantListingRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

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

    private UUID companyId;
    private UUID productId;
    private UUID storeId;

    /** Listing and stock: the only complete state, and the one the till sells. */
    private UUID sellableId;
    /** Listing, no stock: visible and refuses the sale — a price with no quantity behind it. */
    private UUID listedOnlyId;
    /** Stock, no listing: the state this slice exists to stop hiding. */
    private UUID stockOnlyId;
    /** Neither: the store does not carry the variant, so it stays invisible. */
    private UUID carriedNowhereId;

    private final List<UUID> createdVariantIds = new ArrayList<>();
    private final List<UUID> createdStoreStockIds = new ArrayList<>();
    private final List<UUID> createdListingIds = new ArrayList<>();
    private final List<UUID> createdProductIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seedStoreHierarchy();

        productId = newProduct();
        sellableId = createVariant("Sellable");
        listedOnlyId = createVariant("Listed only");
        stockOnlyId = createVariant("Stock only");
        carriedNowhereId = createVariant("Carried nowhere");

        listing(sellableId, "50.00");
        storeRow(sellableId, "7", "20.00");

        listing(listedOnlyId, "30.00");

        storeRow(stockOnlyId, "4", "5.00");
    }

    @AfterEach
    void tearDown() {
        // `variant_listing` is armed on company_id (V58), so its rows are removed under this suite's tenant;
        // an unscoped delete would remove nothing and leave rows for the next test.
        if (!createdListingIds.isEmpty()) {
            TenantFixtures.inTenant(companyId, () -> variantListingRepository.deleteAllById(createdListingIds));
        }
        createdListingIds.clear();
        if (!createdStoreStockIds.isEmpty()) {
            productVariantStoreStockRepository.deleteAllById(createdStoreStockIds);
        }
        createdStoreStockIds.clear();
        TenantFixtures.inTenant(companyId, () -> {
            if (!createdVariantIds.isEmpty()) {
                productVariantRepository.deleteAllById(createdVariantIds);
            }
            if (!createdProductIds.isEmpty()) {
                productRepository.deleteAllById(createdProductIds);
            }
        });
        createdVariantIds.clear();
        createdProductIds.clear();
    }

    // ─── the four states ──────────────────────────────────────────────────

    @Test
    @DisplayName("the store-scoped list returns the stock-without-price state instead of hiding it")
    void listVariants_ReturnsTheStockWithoutPriceVariant() throws Exception {
        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeId.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                // Three of the four: the store that carries neither a listing nor stock is still invisible.
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        var byId = contentById(result.getResponse().getContentAsString());

        assertThat(byId).doesNotContainKey(carriedNowhereId.toString());

        // Listing and stock: sellable.
        var sellable = byId.get(sellableId.toString());
        assertThat(sellable.get("listed").asBoolean()).isTrue();
        assertThat(decimal(sellable.get("listPrice"))).isEqualByComparingTo("50.00");
        assertThat(decimal(sellable.get("stock"))).isEqualByComparingTo("7");
        assertThat(decimal(sellable.get("costPrice")))
                .as("cost is the inbound movement's, so it stays on the store row")
                .isEqualByComparingTo("20.00");

        // Listing, no stock: visible with no quantity, which is "refuses the sale".
        var listedOnly = byId.get(listedOnlyId.toString());
        assertThat(listedOnly.get("listed").asBoolean()).isTrue();
        assertThat(decimal(listedOnly.get("listPrice"))).isEqualByComparingTo("30.00");
        assertThat(decimal(listedOnly.get("stock")))
                .as("no per-store row, so no position: absent is not the same fact as zero")
                .isNull();

        // Stock, no listing: returned, and the flag says why the till cannot sell it yet.
        var stockOnly = byId.get(stockOnlyId.toString());
        assertThat(stockOnly.get("listed"))
                .as("the flag is what makes 'stock without price' reportable instead of invisible")
                .isNotNull();
        assertThat(stockOnly.get("listed").asBoolean()).isFalse();
        assertThat(decimal(stockOnly.get("listPrice"))).isNull();
        assertThat(decimal(stockOnly.get("stock"))).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("the list predicate projects the four states, without a row for the store that carries neither")
    void listVariants_QueryProjectsTheFourStates() {
        // The query level, because the two joins are what this slice changed: a mock can confirm the call,
        // never that the LEFT JOINs are independent.
        var page = TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.findStoreScopedByProductIdAndStoreId(
                        companyId, productId, storeId, PageRequest.of(0, 20)));

        assertThat(page.getContent())
                .extracting(response -> response.id())
                .containsExactlyInAnyOrder(sellableId, listedOnlyId, stockOnlyId);
        assertThat(page.getTotalElements()).isEqualTo(3);

        var sellable = page.getContent().stream()
                .filter(response -> response.id().equals(sellableId))
                .findFirst()
                .orElseThrow();
        assertThat(sellable.listed()).isTrue();
        assertThat(sellable.listPrice()).isEqualByComparingTo("50.00");
        assertThat(sellable.stock()).isEqualByComparingTo("7");
        assertThat(sellable.costPrice()).isEqualByComparingTo("20.00");
        assertThat(sellable.companyStoreId())
                .as("the store is the queried one whether the row came from the listing or the position")
                .isEqualTo(storeId);

        var listedOnly = page.getContent().stream()
                .filter(response -> response.id().equals(listedOnlyId))
                .findFirst()
                .orElseThrow();
        assertThat(listedOnly.listed()).isTrue();
        assertThat(listedOnly.listPrice()).isEqualByComparingTo("30.00");
        assertThat(listedOnly.stock()).isNull();
        assertThat(listedOnly.companyStoreId())
                .as("a listing-only row still states its store: the listing names it")
                .isEqualTo(storeId);

        var stockOnly = page.getContent().stream()
                .filter(response -> response.id().equals(stockOnlyId))
                .findFirst()
                .orElseThrow();
        assertThat(stockOnly.listed()).isFalse();
        assertThat(stockOnly.listPrice()).isNull();
        assertThat(stockOnly.stock()).isEqualByComparingTo("4");
        assertThat(stockOnly.companyStoreId()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("the search returns the stock-without-price variant too")
    void searchVariants_ReturnsTheStockWithoutPriceVariant() throws Exception {
        // The barcode is the sharpest query: it matches exactly one row, so neither the page size nor the
        // text predicate can explain what is missing. This row has a store row and no listing, which is the
        // state that used to come back with nothing in the projection to name it.
        var result = mockMvc.perform(get(SEARCH_URL)
                        .param("q", barCodeOf(stockOnlyId))
                        .param("storeId", storeId.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(stockOnlyId.toString()))
                .andExpect(jsonPath("$.content[0].listed").value(false))
                .andExpect(jsonPath("$.content[0].listPrice").doesNotExist())
                .andExpect(jsonPath("$.content[0].stock").value(4))
                .andReturn();

        assertThat(decimal(contentById(result.getResponse().getContentAsString())
                        .get(stockOnlyId.toString())
                        .get("listPrice")))
                .as("no listing, so no price — and the flag is what says so")
                .isNull();
    }

    // ─── the row V58 backfilled ───────────────────────────────────────────

    @Test
    @DisplayName("a priced row from before the split keeps serving its price, and the listing is what is read")
    void aPreSlicePricedRow_KeepsServingItsBackfilledPrice() throws Exception {
        // The state V58 left behind: the store row still carries the price it always had, and the migration
        // copied it into `variant_listing`. Both variants below hold the same store-row price; only the
        // second one has a listing that disagrees, and that is what tells "the copy is read" apart from
        // "the old column is still the source".
        var backfilledId = createVariant("Backfilled");
        storeRow(backfilledId, "2", "15.00");
        listing(backfilledId, "100.50");

        var divergedId = createVariant("Diverged");
        storeRowWithPrice(divergedId, "3", "100.50", "15.00");
        listing(divergedId, "200.00");

        var result = mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeId.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andReturn();

        var byId = contentById(result.getResponse().getContentAsString());

        assertThat(decimal(byId.get(backfilledId.toString()).get("listPrice")))
                .as("the row V58 copied still serves the price it was copied with")
                .isEqualByComparingTo("100.50");
        assertThat(decimal(byId.get(divergedId.toString()).get("listPrice")))
                .as("with both representations present and disagreeing, the listing is the price")
                .isEqualByComparingTo("200.00");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /**
     * An {@code lc-admin} principal that states this suite's company.
     *
     * <p>The search endpoint names no company at all — it takes a store id and a query — so the tenant of
     * the request comes from the token's claims and a token carrying none states none: {@code
     * company_stores} is a policy-armed table, the store lookup then answers no row, and the request is a
     * 404 that says nothing about the state under test. The list endpoint names the company in its path and
     * works either way; the claim is stated for both so one principal reads the same in every case.</p>
     */
    private RequestPostProcessor adminJwt() {
        return jwt().jwt(builder -> builder.claim("company_id", companyId.toString()))
                .authorities(ROLE_LC_ADMIN);
    }

    /** The page's content keyed by variant id, so an assertion names a state instead of an index. */
    private Map<String, JsonNode> contentById(String responseBody) {
        var byId = new HashMap<String, JsonNode>();
        for (var node : objectMapper.readTree(responseBody).get("content")) {
            byId.put(node.get("id").asText(), node);
        }
        return byId;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private String barCodeOf(UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.findById(variantId).orElseThrow().getBarCode());
    }

    private UUID newProduct() {
        var product = TenantFixtures.inTenant(
                companyId,
                () -> productRepository.save(Product.builder()
                        .sku("VARLIST-" + UUID.randomUUID().toString().substring(0, 8))
                        .name("Variant Listing Read Product")
                        .company(companyRepository.findById(companyId).orElseThrow())
                        .variantMode(Product.VARIANT_MODE_MULTI)
                        .enabled(true)
                        .build()));
        createdProductIds.add(product.getId());
        return product.getId();
    }

    private UUID createVariant(String variantName) {
        var saved = TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(productId)
                        .companyId(companyId)
                        .measureUnitId(pieceUnitId())
                        .barCode("VL-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName(variantName)
                        .enabled(true)
                        .build()));
        createdVariantIds.add(saved.getId());
        return saved.getId();
    }

    /** One listing, the way the price write and the V58 backfill both create it. */
    private UUID listing(UUID variantId, String listPrice) {
        var saved = TenantFixtures.inTenant(
                companyId,
                () -> variantListingRepository.save(VariantListing.builder()
                        .companyId(companyId)
                        .productVariantId(variantId)
                        .companyStoreId(storeId)
                        .listPrice(new BigDecimal(listPrice))
                        .currency(TenantFixtures.SEED_CURRENCY)
                        .enabled(true)
                        .build()));
        createdListingIds.add(saved.getId());
        return saved.getId();
    }

    /** A per-store position row with no price, which is what a receipt leaves behind. */
    private UUID storeRow(UUID variantId, String stock, String costPrice) {
        return storeRowWithPrice(variantId, stock, null, costPrice);
    }

    private UUID storeRowWithPrice(UUID variantId, String stock, String listPrice, String costPrice) {
        var row = productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                .productVariantId(variantId)
                .companyStoreId(storeId)
                .stock(new BigDecimal(stock))
                .listPrice(listPrice == null ? null : new BigDecimal(listPrice))
                .costPrice(costPrice == null ? null : new BigDecimal(costPrice))
                .build());
        createdStoreStockIds.add(row.getId());
        return row.getId();
    }

    /**
     * A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. The unit is required since
     * ADR-0006 decision 3 ({@code V55}), and a random uuid would be refused by the foreign key.
     */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone chain plus one store. */
    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = TenantFixtures.findOrCreate(
                companyRepository, "VARIANT-LISTING-READ-KEY", "Variant Listing Read Company", "VLRD010101ABC");
        companyId = company.getId();

        TenantFixtures.inTenant(companyId, () -> {
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
                                    .regionCode("VL")
                                    .regionName("Variant Listing Read Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("VL")
                            .zoneName("Variant Listing Read Zone")
                            .enabled(true)
                            .build()));

            storeId = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> "Variant Listing Read Store".equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Variant Listing Read Store")
                            .enabled(true)
                            .build()))
                    .getId();
        });
    }
}
