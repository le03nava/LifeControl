package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.lifecontrol.api.inventory.ledger.StockLedgerEngine;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.VariantListing;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.product.repository.VariantListingRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

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
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private VariantListingRepository variantListingRepository;

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

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private StockLedgerEngine stockLedgerEngine;

    /** The writer the measure-unit guard asks whether the variant has history (S7). */
    @Autowired
    private StockLedgerWriter stockLedgerWriter;

    /**
     * The handle every balance is read through: {@code stock_position}, {@code stock_balance} and
     * {@code stock_movement} are the only numbers and the only record of a movement since T3c. The shared ledger
     * cleanup of the base class deletes the projections through it too.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String STORE_STOCK_URL = "/api/variants/{variantId}/stores/{storeId}";
    private static final String VARIANTS_URL = "/api/companies/{companyId}/products/{productId}/variants";

    private UUID companyId;
    private UUID productId;
    private UUID storeAId;
    private UUID storeBId;
    /** Store A's configured sales location, the destination of every manual stock edit. */
    private UUID locationA;
    /** Store B's configured sales location. */
    private UUID locationB;
    /** A variant no test has written to unless it asks for it. */
    private UUID bareVariantId;
    /** The single definition the two-store case shares. */
    private UUID sharedVariantId;

    private final List<UUID> createdVariantIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seedStoreHierarchy();

        // The company read and the armed INSERT share one stated tenant.
        var product = TenantFixtures.inTenant(
                companyId,
                () -> productRepository.save(Product.builder()
                        .sku("VARSTOCK-" + UUID.randomUUID().toString().substring(0, 8))
                        .name("Variant Store Stock Product")
                        .company(companyRepository.findById(companyId).orElseThrow())
                        // Two variants below, so the article is a family: since ADR-0006 decision 4 (V56)
                        // a SIMPLE product refuses the second variant, and this suite is about the
                        // per-store stock editor, not about the mode.
                        .variantMode(Product.VARIANT_MODE_MULTI)
                        .enabled(true)
                        .build()));
        productId = product.getId();

        // Neither variant starts with a store row: creating it is part of what is under test.
        bareVariantId = createVariant("Talla A");
        sharedVariantId = createVariant("Talla B");

        // W3-D14 makes a store_inventory_settings row the precondition for setting stock by hand,
        // so both stores are configured here. The edit destination is the sales location.
        seedInventorySettings(storeAId, locationA);
        seedInventorySettings(storeBId, locationB);
    }

    @AfterEach
    void tearDown() {
        // The per-store stock editor books the stock ledger (W3-D14), so since T5a of
        // odd/tasks/inventory-ledger.md it also writes movements. Those rows reference the variants and
        // the locations with no cascade and the ledger is append-only, so they go first — otherwise the
        // variant deletes below fail on fk_movement_variant for a row the application role cannot remove.
        if (companyId != null) {
            deleteStockLedger(jdbcTemplate, companyId);
        }

        // Listings before the variants: `variant_listing` references the variant (C6) with no cascade, and
        // the table is armed, so each pair is removed under this suite's tenant.
        if (companyId != null && storeAId != null && storeBId != null) {
            TenantFixtures.inTenant(companyId, () -> {
                for (var variantId : List.copyOf(createdVariantIds)) {
                    for (var storeId : List.of(storeAId, storeBId)) {
                        variantListingRepository
                                .findByProductVariantIdAndCompanyStoreId(variantId, storeId)
                                .ifPresent(listing -> variantListingRepository.deleteById(listing.getId()));
                    }
                }
            });
        }

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
        // `product_variants` and `products` are armed: an unscoped delete removes nothing, so the ids are
        // removed under this suite's tenant.
        TenantFixtures.inTenant(companyId, () -> {
            productVariantRepository.deleteAllById(createdVariantIds);
            if (productId != null) {
                productRepository.deleteById(productId);
            }
        });
        createdVariantIds.clear();
        productId = null;

        // The settings rows are the W3-D14 precondition and this test creates them, so they must
        // not outlive it: the store-tree integration classes tear down with
        // storeLocationRepository.deleteAll(), and a leftover settings row would make that delete
        // fail on store_inventory_settings_receiving_location_id_fkey.
        if (storeAId != null && storeBId != null) {
            storeInventorySettingsRepository.findById(storeAId).ifPresent(storeInventorySettingsRepository::delete);
            storeInventorySettingsRepository.findById(storeBId).ifPresent(storeInventorySettingsRepository::delete);
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
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.stock").value(7));

        var inserted = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        // The price is a listing since ADR-0006 decision 6, so it is NOT on the store row any more.
        assertThat(inserted.getListPrice()).isNull();
        assertThat(listingPriceOf(bareVariantId, storeAId)).isEqualByComparingTo("25.50");
        assertThat(inserted.getCostPrice()).isEqualByComparingTo("12.00");
        // The quantity the write stated is the ledger's position: the row's own `stock` column is written by
        // nobody since T3c, so reading it would assert a frozen number.
        assertThat(storePositionOf(storeAId, bareVariantId)).isEqualByComparingTo("7");

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
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(11));

        var updated = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        // Same row: the conflict did nothing, it did not create a second one.
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(storePositionOf(storeAId, bareVariantId)).isEqualByComparingTo("11");
        assertThat(updated.getCostPrice()).isEqualByComparingTo("12.00");
        // A write that names no price leaves the listing alone: "absent" has never meant "clear".
        assertThat(listingPriceOf(bareVariantId, storeAId)).isEqualByComparingTo("25.50");
    }

    // ─────────────────────────── ADR-0006 decision 6: price is a listing ───────────────────────────

    @Test
    @DisplayName("a price written through the endpoint lands in variant_listing, not in the store row")
    void priceLandsInTheListingAndNotInTheStoreRow() throws Exception {
        writePrice(bareVariantId, storeAId, "25.50");

        var listing = listingOf(bareVariantId, storeAId).orElseThrow();
        assertThat(listing.getListPrice()).isEqualByComparingTo("25.50");
        assertThat(listing.getCompanyId())
                .as("the listing states the tenant its variant and its store share (C6)")
                .isEqualTo(companyId);
        assertThat(listing.getCurrency())
                .as("the currency is read from the company (V57), never invented at the write")
                .isEqualTo(TenantFixtures.inTenant(
                        companyId,
                        () -> companyRepository
                                .findById(companyId)
                                .orElseThrow()
                                .getCurrency()));

        // The transitional copy is NOT kept up to date: `product_variant_store_stock.list_price` is the
        // column ADR-0001 retires, and two writers of one price is the defect this slice removes.
        assertThat(productVariantStoreStockRepository
                        .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                        .orElseThrow()
                        .getListPrice())
                .isNull();

        // A second price updates the same listing instead of stacking a second row.
        writePrice(bareVariantId, storeAId, "30.00");
        assertThat(listingPriceOf(bareVariantId, storeAId)).isEqualByComparingTo("30.00");
        assertThat(listingOf(bareVariantId, storeAId).orElseThrow().getId())
                .as("one listing per (variant, store): the upsert updates, it does not insert a second")
                .isEqualTo(listing.getId());
    }

    @Test
    @DisplayName("a write that names no price neither creates a listing nor wipes the one that exists")
    void aWriteThatNamesNoPriceKeepsTheListing() throws Exception {
        // Stock only, on a variant nobody priced: the store row is created and no listing is.
        writeStock(sharedVariantId, storeBId, "2");
        assertThat(listingOf(sharedVariantId, storeBId))
                .as("a stock write does not invent a price")
                .isEmpty();

        // Then a price, then a stock write again: the price survives the second write.
        writePrice(sharedVariantId, storeBId, "18.75");
        writeStock(sharedVariantId, storeBId, "6");

        assertThat(listingPriceOf(sharedVariantId, storeBId)).isEqualByComparingTo("18.75");
        assertThat(storePositionOf(storeBId, sharedVariantId)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("JD-B-005: an empty body creates the row with the column defaults")
    void upsertStoreStock_EmptyBody_InsertsRowWithColumnDefaults() throws Exception {
        mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyStoreId").value(storeAId.toString()));

        var row = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(bareVariantId, storeAId)
                .orElseThrow();
        // The quantity on hand is the ledger's position, and no movement was booked, so no row exists. The
        // stored `stock` column this case asserted the INSERT default of is gone with V74, so what is left to
        // read is the projection: an empty list is the ledger saying nothing was ever booked for this key. The
        // two prices have no default and still say so.
        assertThat(positionRowsOf(storeAId, bareVariantId)).isEmpty();
        assertThat(row.getListPrice()).isNull();
        assertThat(row.getCostPrice()).isNull();
        // And no listing: `variant_listing.list_price` is NOT NULL, so there is no such thing as a
        // price-less listing to create here.
        assertThat(listingOf(bareVariantId, storeAId)).isEmpty();
    }

    @Test
    @DisplayName("JD-B-005: an unknown variant is a 404 and writes nothing")
    void upsertStoreStock_UnknownVariant_IsNotFound() throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal("3"));

        mockMvc.perform(put(STORE_STOCK_URL, UUID.randomUUID(), storeAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
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
        // Each store's stock is the ledger's position for that pair, not a column on the row: the row's own
        // `stock` has been frozen since T3c, when the write path stopped maintaining it.
        assertThat(storePositionOf(storeAId, sharedVariantId)).isEqualByComparingTo("5");
        assertThat(storePositionOf(storeBId, sharedVariantId)).isEqualByComparingTo("9");

        // Two store writes, still two definitions: the write hangs rows off the definition
        // instead of duplicating it, which is the whole point of the split.
        assertThat(TenantFixtures.inTenant(companyId, () -> productVariantRepository.findByProductId(productId)))
                .hasSize(2);

        // And the read the UI uses reports each store's own stock for the same definition.
        mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeAId.toString())
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sharedVariantId.toString()))
                .andExpect(jsonPath("$.content[0].companyStoreId").value(storeAId.toString()))
                .andExpect(jsonPath("$.content[0].stock").value(5));

        mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeBId.toString())
                        .with(editorJwt()))
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
        mockMvc.perform(get(VARIANTS_URL, companyId, productId)
                        .param("storeId", storeBId.toString())
                        .with(editorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ─────────────────────────── S3-T4 / S3-T5: the editor and the invariant
    // ───────────────────────────

    @Nested
    @DisplayName("W3-D14: the per-store stock editor books the ledger, and the projections follow it")
    class StoreStockEditorMaintainsTheBalanceInvariantTests {

        @Test
        @DisplayName("raising stock books one ADJUSTMENT_IN at the sales location")
        void raisesStockAcrossTheLedgerAndItsProjections() throws Exception {
            var body = objectMapper.createObjectNode();
            body.put("stock", new BigDecimal("7"));

            mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(editorJwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stock").value(7));

            // ONE row explains the delta, at the destination location: the store's sales_location_id, which the
            // edit asserts a total against. Its source is an id of its own — a manual edit names no document
            // (D-2) — and the actor is the operator the token names.
            var rows = ledgerRowsOf(bareVariantId);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.movementType())
                    .as("the direction is the movement type, and the quantity stays positive (W3-D7)")
                    .isEqualTo(StockLedgerWriter.ADJUSTMENT_IN);
            assertThat(row.quantity()).isEqualByComparingTo("7");
            assertThat(row.storeLocationId()).isEqualTo(locationA);
            assertThat(row.sourceType()).isEqualTo(StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE);
            assertThat(row.sourceId())
                    .as("a manual edit states a source of its own, so the row is one fact (D-2)")
                    .isNotNull();
            assertThat(row.actor()).isEqualTo("stock-editor");

            // And both balances are that row's projection: stock_position and stock_balance are derived from it
            // by the V61 trigger, so the number is stated once and read twice.
            assertThat(storePositionOf(storeAId, bareVariantId)).isEqualByComparingTo("7");
            assertThat(locationStockOf(bareVariantId, locationA)).isEqualByComparingTo("7");

            assertInvariant(bareVariantId, storeAId);
        }

        @Test
        @DisplayName("lowering stock writes a positive ADJUSTMENT_OUT and the projections follow it")
        void lowersStockWithAPositiveDecreaseMovement() throws Exception {
            // A step down the ledger has no precedent for: raise to 11, then lower to 4.
            writeStock(bareVariantId, storeAId, "11");
            assertInvariant(bareVariantId, storeAId);

            writeStock(bareVariantId, storeAId, "4");

            assertThat(storePositionOf(storeAId, bareVariantId)).isEqualByComparingTo("4");
            assertThat(locationStockOf(bareVariantId, locationA)).isEqualByComparingTo("4");

            var rows = ledgerRowsOf(bareVariantId);
            assertThat(rows).hasSize(2);
            var increase = rows.stream()
                    .filter(row -> StockLedgerWriter.ADJUSTMENT_IN.equals(row.movementType()))
                    .findFirst()
                    .orElseThrow();
            var decrease = rows.stream()
                    .filter(row -> StockLedgerWriter.ADJUSTMENT_OUT.equals(row.movementType()))
                    .findFirst()
                    .orElseThrow();
            assertThat(increase.quantity()).isEqualByComparingTo("11");
            // W3-D7 holds: the decrease is positive and the direction lives in the type.
            assertThat(decrease.quantity()).isEqualByComparingTo("7");
            assertThat(decrease.storeLocationId()).isEqualTo(locationA);
            assertThat(decrease.sourceId())
                    .as("each side of the edit books a source of its own, so the two rows are two facts" + " (D-2)")
                    .isNotNull()
                    .isNotEqualTo(increase.sourceId());

            assertInvariant(bareVariantId, storeAId);
        }

        @Test
        @DisplayName("a decrease draws from the location holding the stock, not from the sales location")
        void decreaseDrawsFromTheLocationHoldingTheStock() throws Exception {
            // The reachable state applyReceipt produces when the receiving and sales locations differ:
            // 10 units land on the dock ("VSD") while the sales location (locationA) stays at zero.
            // The editor lowering the aggregate to 5 used to debit sales to -5 (F18); it must draw
            // the decrease from the dock instead.
            var storeA = TenantFixtures.inTenant(
                    companyId, () -> companyStoreRepository.findById(storeAId).orElseThrow());
            var dock = findOrCreateLocation(storeA, "VSD", "Variant Stock Write Dock A", 2);

            // The editor creates the per-store row (the receipt needs it) and leaves the ledger untouched.
            writeStock(bareVariantId, storeAId, "0");
            // The service is called directly, so the fixture states the tenant its `company_stores` read
            // needs — the same fact the write above states by naming the company in the path.
            TenantFixtures.inTenant(
                    companyId,
                    () -> stockLedgerEngine.applyReceipt(
                            bareVariantId,
                            storeAId,
                            dock.getId(),
                            new BigDecimal("10"),
                            StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                            UUID.randomUUID(),
                            "receiver"));

            writeStock(bareVariantId, storeAId, "5");

            // The sales location never goes negative: before the fix the whole decrease landed on it.
            assertThat(locationStockOf(bareVariantId, locationA)).isEqualByComparingTo("0");
            assertThat(locationStockOf(bareVariantId, dock.getId())).isEqualByComparingTo("5");

            var decreases = ledgerRowsOf(bareVariantId).stream()
                    .filter(row -> StockLedgerWriter.ADJUSTMENT_OUT.equals(row.movementType()))
                    .toList();
            assertThat(decreases).hasSize(1);
            assertThat(decreases.getFirst().storeLocationId()).isEqualTo(dock.getId());
            assertThat(decreases.getFirst().quantity()).isEqualByComparingTo("5");

            assertInvariant(bareVariantId, storeAId);
        }

        @Test
        @DisplayName("a store with no settings row is refused with a typed 4xx and writes nothing")
        void refusesWithoutStoreInventorySettings() throws Exception {
            storeInventorySettingsRepository.findById(storeBId).ifPresent(storeInventorySettingsRepository::delete);

            var body = objectMapper.createObjectNode();
            body.put("stock", new BigDecimal("3"));

            mockMvc.perform(put(STORE_STOCK_URL, bareVariantId, storeBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))
                            .with(editorJwt()))
                    .andExpect(status().isNotFound());

            // The refusal rolls the whole write back: no aggregate row, no location row, no ledger.
            assertThat(productVariantStoreStockRepository.existsByProductVariantIdAndCompanyStoreId(
                            bareVariantId, storeBId))
                    .isFalse();
            assertThat(ledgerRowsOf(bareVariantId)).isEmpty();
        }

        @Test
        @DisplayName("an edit that sends the same stock writes no movement")
        void sameStockWritesNoMovement() throws Exception {
            writeStock(bareVariantId, storeAId, "5");
            assertThat(ledgerRowsOf(bareVariantId)).hasSize(1);

            writeStock(bareVariantId, storeAId, "5");

            // The ledger records facts, not no-ops: the edit sends the number the position already states, so
            // the delta is zero and nothing is booked.
            assertThat(ledgerRowsOf(bareVariantId)).hasSize(1);
            assertInvariant(bareVariantId, storeAId);
        }

        /**
         * D3's guard reads this probe, and a mock cannot prove it: it is a query over real rows.
         *
         * <p><b>It is one probe since T3c.</b> It used to be two — a stock floor over
         * {@code product_variant_store_stock} plus a row in {@code inventory_movements} — and neither table is
         * written any more, so asking them would answer "no history" for a variant with plenty and the refusal
         * would disappear without a failing test. That is exactly what S7 exists to prevent, and
         * {@code StockLedgerWriter#hasMovements} is the whole answer now.</p>
         */
        @Test
        @DisplayName("D3: the measure-unit guard's probe answers from the ledger (S7)")
        void measureUnitProbeAnswersFromTheLedger() throws Exception {
            // A variant nobody wrote to has no history, so a unit change would still be allowed on it.
            // (The unit itself is declared — V55 makes it required — which is a different fact.)
            assertThat(hasLedgerHistory(sharedVariantId)).isFalse();

            writeStock(bareVariantId, storeAId, "3");

            assertThat(hasLedgerHistory(bareVariantId)).isTrue();

            // Back to zero: the balance is drained, but the movements remain, so the unit still cannot be
            // swapped. Nothing reads a quantity above zero any more — the history is the answer.
            writeStock(bareVariantId, storeAId, "0");

            assertThat(storePositionOf(storeAId, bareVariantId)).isEqualByComparingTo("0");
            assertThat(hasLedgerHistory(bareVariantId)).isTrue();
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void writeStock(UUID variantId, UUID storeId, String stock) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("stock", new BigDecimal(stock));

        mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
                .andExpect(status().isOk());
    }

    private void writePrice(UUID variantId, UUID storeId, String listPrice) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("listPrice", new BigDecimal(listPrice));

        mockMvc.perform(put(STORE_STOCK_URL, variantId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(editorJwt()))
                .andExpect(status().isOk())
                // The response reports the listing's price, so a client that writes a price and reads it
                // back is not told the price it just set is absent.
                .andExpect(jsonPath("$.listPrice").value(Double.parseDouble(listPrice)));
    }

    /**
     * The listing of one pair, read under this suite's tenant: {@code variant_listing} is armed on
     * {@code company_id} (V58), so an unscoped read answers no row — and an {@code isEmpty()} on such a
     * read would pass without proving anything.
     */
    private Optional<VariantListing> listingOf(UUID variantId, UUID storeId) {
        return TenantFixtures.inTenant(
                companyId, () -> variantListingRepository.findByProductVariantIdAndCompanyStoreId(variantId, storeId));
    }

    /**
     * The listing's price, or {@code null} when the store has no listing — which is the state the response
     * reports for a variant it does not sell.
     */
    private BigDecimal listingPriceOf(UUID variantId, UUID storeId) {
        return listingOf(variantId, storeId).map(VariantListing::getListPrice).orElse(null);
    }

    /**
     * The editor's JWT: {@code lc-admin}, the username the ledger records as {@code created_by}, and the
     * company.
     *
     * <p>{@code /api/variants/{variantId}/stores/{storeId}} and the variant list name no company in their
     * paths, so the request's tenant comes from the token's claims (T5b) and a token carrying none states
     * none: the variant and the store are then invisible and every write answers 404. The claim is the
     * fixture's, not the assertion's — this suite is about the balance invariant, and an administrator is
     * admitted for its company either way.</p>
     */
    private RequestPostProcessor editorJwt() {
        return jwt().jwt(builder ->
                        builder.claim("preferred_username", "stock-editor").claim("company_id", companyId.toString()))
                .authorities(ROLE_LC_ADMIN);
    }

    /**
     * What the measure-unit guard asks the ledger (S7): the question {@link StockLedgerWriter#hasMovements}
     * answers, asked under this suite's tenant because {@code stock_movement} is armed.
     */
    private boolean hasLedgerHistory(UUID variantId) {
        return TenantFixtures.inTenant(companyId, () -> stockLedgerWriter.hasMovements(variantId));
    }

    /**
     * One row of {@code stock_movement}, as the fields a booking case asserts.
     *
     * <p>Since T3c the ledger is the only record of a movement: {@code inventory_movements} is no longer written,
     * so a case that wants to say what was booked reads here. The projections below are the only balances.</p>
     */
    private record LedgerRow(
            String movementType,
            String sourceType,
            UUID sourceId,
            BigDecimal quantity,
            UUID companyStoreId,
            UUID storeLocationId,
            String actor) {}

    private List<LedgerRow> ledgerRowsOf(UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT movement_type, source_type, source_id, quantity, company_store_id,
                               store_location_id, actor
                          FROM stock_movement
                         WHERE product_variant_id = ?
                         ORDER BY occurred_at, id
                        """,
                        (rs, row) -> new LedgerRow(
                                rs.getString("movement_type"),
                                rs.getString("source_type"),
                                rs.getObject("source_id", UUID.class),
                                rs.getBigDecimal("quantity"),
                                rs.getObject("company_store_id", UUID.class),
                                rs.getObject("store_location_id", UUID.class),
                                rs.getString("actor")),
                        variantId));
    }

    /**
     * The store's sellable aggregate, from the ledger's projection.
     *
     * <p>It used to read {@code product_variant_store_stock.stock}. Since T3c nothing maintains that column, so
     * reading it would make every assertion about an edit agree with a frozen number — the silent agreement this
     * slice exists to remove. {@code stock_position} is derived from the movements by trigger, which is what makes
     * it the answer.</p>
     */
    private BigDecimal storePositionOf(UUID storeId, UUID variantId) {
        return positionRowsOf(storeId, variantId).stream()
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("no stock_position row for variant " + variantId + " in store " + storeId));
    }

    /**
     * The {@code stock_position} rows of one key, read under this suite's tenant.
     *
     * <p>An empty list is a state and not an error: the projection only holds a key the ledger moved, so "no
     * movement" and "no row" are the same fact — which is what a case that asserts nothing was written reads.</p>
     */
    private List<BigDecimal> positionRowsOf(UUID storeId, UUID variantId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id"
                                + " = ?",
                        BigDecimal.class,
                        storeId,
                        variantId));
    }

    /** One location's balance, from the projection the trigger maintains. A location with no row holds none. */
    private BigDecimal locationStockOf(UUID variantId, UUID storeLocationId) {
        var quantities = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id"
                                + " = ?",
                        BigDecimal.class,
                        storeLocationId,
                        variantId));
        return quantities.stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * The W3-D5 invariant as the ledger states it since T3c: the store's projection equals the sum of the
     * location balances the store owns.
     *
     * <p>Both sides used to be the two legacy columns — {@code product_variant_store_stock.stock} against
     * {@code SUM(product_variant_locations.stock)} — and nothing maintains either any more, so comparing them
     * would compare two frozen numbers and call it agreement. {@code stock_position} and {@code stock_balance} are
     * derived from {@code stock_movement} by the V61 trigger, and the locations are resolved through
     * {@code store_locations.company_store_id} so a balance row of another store cannot be summed in.</p>
     */
    private void assertInvariant(UUID variantId, UUID storeId) {
        var locationSum = TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(b.quantity), 0)
                      FROM stock_balance b
                      JOIN store_locations l ON l.id = b.store_location_id
                     WHERE b.product_variant_id = ?
                       AND l.company_store_id = ?
                    """, BigDecimal.class, variantId, storeId));
        assertThat(storePositionOf(storeId, variantId))
                .as("stock_position must equal SUM(stock_balance) over the locations the store owns")
                .isEqualByComparingTo(locationSum);
    }

    /**
     * A real {@code measure_units} row: {@code V3} seeds "Pieza" as {@code H87}. The unit is required
     * since ADR-0006 decision 3 ({@code V55}), and a random uuid would be refused by the foreign key —
     * which would make the fixture fail for a reason that says nothing about the rule under test.
     */
    private UUID pieceUnitId() {
        return measureUnitRepository.findBySatCode("H87").orElseThrow().getId();
    }

    private UUID createVariant(String variantName) {
        var saved = TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(productId)
                        .companyId(companyId)
                        .measureUnitId(pieceUnitId())
                        .barCode("VS-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName(variantName)
                        .enabled(true)
                        .build()));
        createdVariantIds.add(saved.getId());
        return saved.getId();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone chain plus two stores in
     * the same zone. Idempotent because the Testcontainers database is shared per JVM.
     */
    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = TenantFixtures.findOrCreate(
                companyRepository, "VARIANT-STOCK-WRITE-KEY", "Variant Stock Write Test Company", "VSWT010101ABC");
        companyId = company.getId();

        // One boundary states the company for the whole tree: each node, store and location is a row on
        // (or referencing) a policy-armed table, so the find-or-create reads and the INSERTs need it.
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

            var storeA = findOrCreateStore(zone, "Variant Stock Write Store A");
            var storeB = findOrCreateStore(zone, "Variant Stock Write Store B");
            storeAId = storeA.getId();
            storeBId = storeB.getId();
            locationA = findOrCreateLocation(storeA, "VSLA", "Variant Stock Write Sales A", 1)
                    .getId();
            locationB = findOrCreateLocation(storeB, "VSLB", "Variant Stock Write Sales B", 1)
                    .getId();
        });
    }

    private CompanyStore findOrCreateStore(CompanyZone zone, String storeName) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(candidate -> storeName.equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(storeName)
                        .enabled(true)
                        .build()));
    }

    /** Find-or-create the store's area &rarr; zone &rarr; leaf location used as its sales location. */
    private StoreLocation findOrCreateLocation(
            CompanyStore store, String locationCode, String locationName, int displayOrder) {
        var area = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId()).stream()
                .filter(candidate -> "VSW".equals(candidate.getAreaCode()))
                .findFirst()
                .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                        .companyStore(store)
                        .areaCode("VSW")
                        .areaName("Variant Stock Write Warehouse")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));
        var zone = storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                .filter(candidate -> "VSZ".equals(candidate.getZoneCode()))
                .findFirst()
                .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                        .storeArea(area)
                        .zoneCode("VSZ")
                        .zoneName("Variant Stock Write Aisle")
                        .displayOrder(1)
                        .enabled(true)
                        .build()));
        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId()).stream()
                .filter(candidate -> locationCode.equals(candidate.getLocationCode()))
                .findFirst()
                .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                        .storeZone(zone)
                        .locationCode(locationCode)
                        .locationName(locationName)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    private void seedInventorySettings(UUID storeId, UUID salesLocationId) {
        if (storeInventorySettingsRepository.findById(storeId).isEmpty()) {
            storeInventorySettingsRepository.save(StoreInventorySettings.builder()
                    .companyStoreId(storeId)
                    .receivingLocationId(salesLocationId)
                    .salesLocationId(salesLocationId)
                    .build());
        }
    }
}
