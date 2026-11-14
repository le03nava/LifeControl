package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
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
import com.lifecontrol.api.inventory.model.StockValuation;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StockValuationRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.model.ProductVariantStoreStock;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The average the inventory reports, and the sale-side cost it must not move (S5 and S3 of
 * {@code odd/tasks/purchase-cost-into-inventory.md}; issue #853).
 *
 * <p><b>The average is the ledger's projection, not a column of this unit's.</b> {@code V96} gave a receipt the
 * purchase price to book and to snapshot, and {@code stock_valuation.avg_unit_cost} — the projection {@code V61}
 * derives from the inbound movements and their allocations — is where the weighted moving average of what was
 * paid already lived. These cases pin that: after two receipts at two prices the projection holds
 * {@code (10 × 10 + 10 × 20) / 20 = 15}, and the repository this tree added reads exactly that number.</p>
 *
 * <p><b>And they pin what the unit did NOT change (S3).</b> The outbound path is untouched: a sale's cost is its
 * {@code stock_allocation.unit_cost} rows, resolved by {@code companies.costing_method} — the consumed inbound
 * row's own cost under {@code FIFO}, the moving average in force under {@code AVERAGE} — exactly as ADR-0001's
 * {@code ## Costing and valuation} states. Both policies are asserted below, so a change that started booking the
 * average on every outbound row would fail here instead of on a margin report.</p>
 *
 * <p>Every quantity enters through the public writers ({@link StockLedgerEngine#applyReceipt} with a stated
 * purchase price, then {@link StockLedgerEngine#applySaleDeduction}), never by inserting a movement: a fixture that
 * wrote {@code stock_movement} by hand would be authoring the ledger the engine exists to book.</p>
 */
@SpringBootTest
@DisplayName("Stock valuation averages and the sale cost they do not move")
class StockValuationAverageIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SALE_SOURCE = "SALES_ORDER_ITEM";

    private static final String COMPANY_KEY = "STOCK-VALUATION-KEY";
    private static final String COMPANY_RFC = "SVAV050505AB1";

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
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductVariantStoreStockRepository productVariantStoreStockRepository;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private MeasureUnitRepository measureUnitRepository;

    @Autowired
    private StockValuationRepository stockValuationRepository;

    @Autowired
    private StockLedgerEngine stockLedgerEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The seed every company this suite creates receives, bound around each case. */
    @Autowired
    private StarterAccountingCatalogApplier starterCatalog;

    private UUID companyId;
    private UUID storeId;
    private UUID locationId;
    private UUID variantId;
    private UUID productId;

    @BeforeEach
    void seedTheStoreAndItsVariant() {
        // The company is created through `CompanyRepository` by `seedStoreHierarchy()`, which publishes no
        // `CompanyCreatedEvent`, and every case here books a sale that consumes stock: since V100 the cost of
        // that movement refuses to post without the revenue and cost rules, so the fixture states the book.
        TenantFixtures.bindStarterCatalog(starterCatalog);

        seedStoreHierarchy();

        var product = TenantFixtures.inTenant(
                companyId,
                () -> productRepository.save(Product.builder()
                        .sku("VALUATION-" + UUID.randomUUID().toString().substring(0, 8))
                        .name("Stock Valuation Product")
                        .company(companyRepository.findById(companyId).orElseThrow())
                        .enabled(true)
                        .build()));
        productId = product.getId();

        var variant = TenantFixtures.inTenant(
                companyId,
                () -> productVariantRepository.save(ProductVariant.builder()
                        .productId(productId)
                        .companyId(companyId)
                        .measureUnitId(measureUnitRepository
                                .findBySatCode("H87")
                                .orElseThrow()
                                .getId())
                        .barCode("SV-BAR-" + UUID.randomUUID().toString().substring(0, 12))
                        .variantName("Valuation variant")
                        .enabled(true)
                        .build()));
        variantId = variant.getId();

        // The per-store row a receipt requires: it states the declared cost, which is the fallback a caller
        // without a purchase price books — never the number the cases below assert on.
        TenantFixtures.inTenant(
                companyId,
                () -> productVariantStoreStockRepository.save(ProductVariantStoreStock.builder()
                        .productVariantId(variantId)
                        .companyStoreId(storeId)
                        .costPrice(new BigDecimal("9.75"))
                        .build()));

        // Both locations are the one shelf: these cases are about cost, not about the split of a sale.
        TenantFixtures.inTenant(
                companyId,
                () -> storeInventorySettingsRepository.save(StoreInventorySettings.builder()
                        .companyStoreId(storeId)
                        .receivingLocationId(locationId)
                        .salesLocationId(locationId)
                        .build()));
    }

    @AfterEach
    void removeTheLedgerAndTheFixture() {
        // First, so a teardown that fails half-way cannot leave the next class seeding its companies.
        TenantFixtures.unbindStarterCatalog();
        if (companyId == null) {
            return;
        }
        // `stock_movement` is append-only and references the variant and the location with no cascade, so the
        // ledger rows go first or the deletes below fail on fk_movement_variant.
        deleteStockLedger(jdbcTemplate, companyId);
        TenantFixtures.inTenant(companyId, () -> {
            storeInventorySettingsRepository.deleteAll();
            productVariantStoreStockRepository.deleteAll();
            if (variantId != null) {
                productVariantRepository.deleteById(variantId);
            }
            if (productId != null) {
                productRepository.deleteById(productId);
            }
        });
    }

    // ──────────────────────────────────────
    // the average: the ledger's number, fed by the price the receipt states
    // ──────────────────────────────────────

    @Test
    @DisplayName("the average is the weighted moving average of the prices the receipts paid")
    void theAverageIsTheWeightedMovingAverageOfTheReceiptPrices() {
        receive("10", "10.00");

        assertThat(projectedAverage())
                .as("the first receipt makes the average the price it paid, which is what V96's inbound cost feeds")
                .isEqualByComparingTo("10.00");
        assertThat(valuation().map(StockValuation::getAvgUnitCost))
                .as("and the read this tree added answers the projection's own number")
                .hasValueSatisfying(average -> assertThat(average).isEqualByComparingTo("10.00"));

        receive("10", "20.00");

        assertThat(projectedAverage())
                .as("(10 × 10.00 + 10 × 20.00) / 20 = 15.0000, at four decimals, derived from the movements")
                .isEqualByComparingTo("15.0000");
        assertThat(valuation().map(StockValuation::getTotalValue))
                .as("and the value on hand is the sum of what both receipts paid")
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("300.0000"));
    }

    @Test
    @DisplayName("no movement means no average at all, which is not a zero")
    void noMovementMeansNoAverage() {
        assertThat(stockValuationRepository.findByCompanyStoreIdAndProductVariantId(storeId, variantId))
                .as("the projection has a row only when the ledger moved the key, so a store that never received "
                        + "this variant has no cost to report — and the response field is null, not 0.00")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // S3 — the sale's cost is the policy's, and this unit did not move it
    // ──────────────────────────────────────

    @Test
    @DisplayName("FIFO: a sale books the consumed row's cost, not the average")
    void fifoBooksTheConsumedRowsCost() {
        receive("10", "10.00");
        receive("10", "20.00");

        var sale = sell("5");

        assertThat(allocationCostsOf(sale))
                .as("FIFO consumes oldest-first, so these units cost what the first receipt paid — 10.00 — while "
                        + "the average of the stock was 15.00: the two numbers are different facts and this unit "
                        + "does not make the sale book the second one (ADR-0001, `## Costing and valuation`)")
                .isNotEmpty()
                .allSatisfy(cost -> assertThat(cost).isEqualByComparingTo("10.0000"));
    }

    @Test
    @DisplayName("AVERAGE: a sale books the moving average in force")
    void averageBooksTheMovingAverageInForce() {
        stateCostingMethod("AVERAGE");
        receive("10", "10.00");
        receive("10", "20.00");

        var sale = sell("5");

        assertThat(allocationCostsOf(sale))
                .as("under AVERAGE every allocation carries the average at the instant of consumption, which is "
                        + "the projection's own number — the mechanism ADR-0001 already had, untouched here")
                .isNotEmpty()
                .allSatisfy(cost -> assertThat(cost).isEqualByComparingTo("15.0000"));
        assertThat(projectedAverage())
                .as("consuming at the average leaves the average where it was")
                .isEqualByComparingTo("15.0000");
    }

    // ──────────────────────────────────────
    // helpers: the writers
    // ──────────────────────────────────────

    /** A receipt of {@code quantity} units at a stated purchase price, through the public writer. */
    private void receive(String quantity, String purchaseUnitCost) {
        TenantFixtures.inTenant(
                companyId,
                () -> stockLedgerEngine.applyReceipt(
                        variantId,
                        storeId,
                        locationId,
                        new BigDecimal(quantity),
                        new BigDecimal(purchaseUnitCost),
                        StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                        UUID.randomUUID(),
                        "receiver"));
    }

    /** A sale of {@code quantity} units, answering the line identity it books under. */
    private UUID sell(String quantity) {
        var referenceId = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> stockLedgerEngine.applySaleDeduction(
                        variantId, storeId, new BigDecimal(quantity), SALE_SOURCE, referenceId, "seller"));
        return referenceId;
    }

    // ──────────────────────────────────────
    // helpers: the projection and the allocation the sale froze
    // ──────────────────────────────────────

    private java.util.Optional<StockValuation> valuation() {
        return TenantFixtures.inTenant(
                companyId, () -> stockValuationRepository.findByCompanyStoreIdAndProductVariantId(storeId, variantId));
    }

    private BigDecimal projectedAverage() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT avg_unit_cost FROM stock_valuation WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variantId));
    }

    /** What the sale froze per consumed inbound row: the number a margin report reads. */
    private List<BigDecimal> allocationCostsOf(UUID saleReferenceId) {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForList("""
                        SELECT a.unit_cost
                          FROM stock_allocation a
                          JOIN stock_movement o ON o.id = a.out_movement_id
                         WHERE o.source_type = ? AND o.source_id = ?
                         ORDER BY a.created_at, a.in_movement_id
                        """, BigDecimal.class, SALE_SOURCE, saleReferenceId));
    }

    private void stateCostingMethod(String costingMethod) {
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE companies SET costing_method = ? WHERE id = ?", costingMethod, companyId));
    }

    // ──────────────────────────────────────
    // helpers: the store tree the movements hang from
    // ──────────────────────────────────────

    private void seedStoreHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company =
                TenantFixtures.findOrCreate(companyRepository, COMPANY_KEY, "Stock Valuation Company", COMPANY_RFC);
        companyId = company.getId();

        TenantFixtures.inTenant(companyId, () -> {
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
                                    .regionCode("SVA")
                                    .regionName("Stock Valuation Region")
                                    .enabled(true)
                                    .build()));

            var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                    .findFirst()
                    .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                            .companyRegion(region)
                            .zoneCode("SVA")
                            .zoneName("Stock Valuation Zone")
                            .enabled(true)
                            .build()));

            var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                    .filter(candidate -> "Stock Valuation Store".equals(candidate.getStoreName()))
                    .findFirst()
                    .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                            .companyZone(zone)
                            .storeName("Stock Valuation Store")
                            .enabled(true)
                            .build()));
            storeId = store.getId();

            var area = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .filter(candidate -> "SVA-AREA".equals(candidate.getAreaCode()))
                    .findFirst()
                    .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                            .companyStore(store)
                            .areaCode("SVA-AREA")
                            .areaName("Stock Valuation Area")
                            .displayOrder(1)
                            .enabled(true)
                            .build()));

            var storeZone =
                    storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                            .filter(candidate -> "SVA-ZONE".equals(candidate.getZoneCode()))
                            .findFirst()
                            .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                                    .storeArea(area)
                                    .zoneCode("SVA-ZONE")
                                    .zoneName("Stock Valuation Store Zone")
                                    .displayOrder(1)
                                    .enabled(true)
                                    .build()));

            locationId =
                    storeLocationRepository
                            .findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZone.getId())
                            .stream()
                            .filter(candidate -> "SVA-01".equals(candidate.getLocationCode()))
                            .findFirst()
                            .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                                    .storeZone(storeZone)
                                    .locationCode("SVA-01")
                                    .locationName("Stock Valuation Shelf")
                                    .displayOrder(1)
                                    .enabled(true)
                                    .build()))
                            .getId();
        });
    }
}
