package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T5a of {@code odd/tasks/inventory-ledger.md}: the four writers of {@link InventoryService} book the ledger
 * <b>beside</b> the legacy update, in the same transaction.
 *
 * <p><b>What this suite is the witness of.</b> The slice's whole purpose is that one transition is written
 * twice for a while, and that the two copies can be seen to agree. So every case drives a public writer of
 * {@link InventoryService} — the same entry point {@code GoodsReceiptService}, {@code SalesOrderService} and
 * {@code ProductVariantService} call — and then asserts, for the same {@code (store, variant)}:</p>
 * <pre>{@code
 * stock_position.quantity = product_variant_store_stock.stock
 * stock_balance.quantity  = product_variant_locations.stock   (per location)
 * }</pre>
 * <p>plus the movement the writer booked: its type, its quantity, its cost and currency, and — for an
 * outbound row — the allocations the engine recorded for it (invariant I6). Nothing asserts on a trigger or a
 * function body: the ledger's own engine is T3's suite, and this one does not restate its cases.</p>
 *
 * <p><b>The tenant scope is stated, never assumed.</b> The pool logs in as {@code lifecontrol_app}, and
 * {@code V60} armed all five ledger tables on {@code tenant_table}, so every statement the ledger runs meets
 * ADR-0005's policy. A case therefore calls the writer inside {@code TenantFixtures.inTenant(...)} — exactly
 * as {@code InventoryIntegrationTest} does — and one case proves the other direction: with no scope stated,
 * the ledger refuses the row instead of writing nothing and reporting success.</p>
 *
 * <p><b>Two collision cases, which is what the {@code source_id} decision is judged by.</b> {@code
 * uq_movement_source} is {@code (source_type, source_id, movement_type)}, and the naive source identity — the
 * document's or the line's id — cannot satisfy it in two shapes this tree really writes: a sale line that
 * spans locations books one row per location, a line sold again after its reversal books a second {@code SALE}
 * under the same reference, and a receipt hands the writer the document id once per line. Cases 5 and 6 pin
 * the first two; the third is pinned by 6. See {@link StockLedgerWriter}'s javadoc for where each {@code
 * source_id} comes from.</p>
 */
@SpringBootTest
@DisplayName("Inventory ledger booking integration tests (T5a)")
class InventoryLedgerBookingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "LEDGER-BOOKING-A";
    private static final String COMPANY_RFC = "LED030303EF3";

    /** The two source discriminators the callers pass; the ledger mirrors the rows, not the documents. */
    private static final String GOODS_RECEIPT = "GOODS_RECEIPT";

    private static final String SALES_ORDER_ITEM = "SALES_ORDER_ITEM";

    /** What the store records for the variant as its cost, which is what an inbound row must state. */
    private static final String RECORDED_COST = "12.50";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private StockLedgerWriter stockLedgerWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID companyId;

    private UUID storeId;

    /** The store's configured sales location: a deduction drains it first and spills over from there. */
    private UUID salesLocationId;

    private UUID secondaryLocationId;

    private UUID variantId;

    /** A second variant, only used by the case where one receipt document carries two lines. */
    private UUID secondVariantId;

    private final List<InsertedRow> insertedVariants = new ArrayList<>();
    private final List<InsertedRow> insertedProducts = new ArrayList<>();
    private final List<InsertedRow> insertedLocations = new ArrayList<>();
    private final List<InsertedRow> insertedZones = new ArrayList<>();
    private final List<InsertedRow> insertedAreas = new ArrayList<>();
    private final List<InsertedRow> insertedStores = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyZones = new ArrayList<>();
    private final List<InsertedRow> insertedRegions = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyCountries = new ArrayList<>();

    @BeforeEach
    void insertTheTreeEveryCaseHangsFrom() {
        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyId = TenantFixtures.insertCompany(
                jdbcTemplate, COMPANY_KEY, "Ledger Booking Company", COMPANY_RFC, TenantFixtures.SEED_CURRENCY);

        var companyCountryId = insertCompanyCountry(countryId);
        var regionId = insertRegion(companyCountryId);
        var companyZoneId = insertCompanyZone(regionId);
        storeId = insertStore(companyZoneId);
        salesLocationId = insertLocation(insertZone(insertArea(), storeId), storeId);
        secondaryLocationId = insertLocation(insertZone(insertArea(), storeId), storeId);

        insertSettings();

        variantId = insertVariant("BOOKING");
        secondVariantId = insertVariant("BOOKING2");
    }

    @AfterEach
    void removeInsertedRows() {
        deleteTheLedger();
        deleteTheLegacyBalances();
        for (var row : insertedVariants) {
            deleteRow(row, "DELETE FROM product_variants WHERE id = ?");
        }
        insertedVariants.clear();
        for (var row : insertedProducts) {
            deleteRow(row, "DELETE FROM products WHERE id = ?");
        }
        insertedProducts.clear();
        for (var row : insertedLocations) {
            deleteRow(row, "DELETE FROM store_locations WHERE id = ?");
        }
        insertedLocations.clear();
        for (var row : insertedZones) {
            deleteRow(row, "DELETE FROM store_zones WHERE id = ?");
        }
        insertedZones.clear();
        for (var row : insertedAreas) {
            deleteRow(row, "DELETE FROM store_areas WHERE id = ?");
        }
        insertedAreas.clear();
        for (var row : insertedStores) {
            deleteRow(row, "DELETE FROM company_stores WHERE id = ?");
        }
        insertedStores.clear();
        for (var row : insertedCompanyZones) {
            deleteRow(row, "DELETE FROM company_zones WHERE id = ?");
        }
        insertedCompanyZones.clear();
        for (var row : insertedRegions) {
            deleteRow(row, "DELETE FROM company_regions WHERE id = ?");
        }
        insertedRegions.clear();
        for (var row : insertedCompanyCountries) {
            deleteRow(row, "DELETE FROM company_countries WHERE id = ?");
        }
        insertedCompanyCountries.clear();
        deleteRow(new InsertedRow(companyId, companyId), "DELETE FROM companies WHERE id = ?");
    }

    // ──────────────────────────────────────
    // writer 1 — a receipt books one RECEIPT row, and the projections agree
    // ──────────────────────────────────────

    @Test
    @DisplayName("a receipt books one RECEIPT row with its cost and currency, and the projections agree")
    void aReceiptBooksTheLedgerAndTheProjectionsAgree() {
        var referenceId = UUID.randomUUID();

        applyReceipt(variantId, salesLocationId, "10", referenceId);

        var legacyMovementId = legacyMovementsOf(variantId, "RECEIPT").getFirst();
        var booked = ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT);
        assertThat(booked)
                .as("ADR-0001 decision 1 (:48-50): every change of quantity is one stock_movement row, so a "
                        + "receipt of one line books exactly one")
                .hasSize(1);
        var receipt = booked.getFirst();
        assertThat(receipt.quantity())
                .as("the same quantity the legacy row recorded")
                .isEqualByComparingTo("10");
        assertThat(receipt.unitCost())
                .as("ADR-0001 decision 4 (:59-61): an inbound row records what was paid, and the cost this "
                        + "deployment records for the variant in the store is what the receipt books")
                .isEqualByComparingTo(RECORDED_COST);
        assertThat(receipt.currency())
                .as("and the unit it is denominated in is the company's operating currency (ADR-0002)")
                .isEqualTo(TenantFixtures.SEED_CURRENCY);
        assertThat(receipt.storeId()).isEqualTo(storeId);
        assertThat(receipt.locationId()).isEqualTo(salesLocationId);
        assertThat(receipt.reversesMovementId())
                .as("a receipt is not a reversal")
                .isNull();
        assertThat(receipt.sourceType())
                .as("during the dual-write window a movement's source is the legacy row it mirrors")
                .isEqualTo(StockLedgerWriter.MIRRORED_SOURCE_TYPE);
        assertThat(receipt.sourceId())
                .as("one fact, one row: the ledger row and the legacy row mirror each other one to one")
                .isEqualTo(legacyMovementId);

        assertTheLedgerAgreesWithTheLegacyBalances("10", "10", "10");
    }

    // ──────────────────────────────────────
    // writer 2 — a sale books one SALE row per location it drains, each with its allocations
    // ──────────────────────────────────────

    @Test
    @DisplayName("a sale books one SALE row per location it drains, allocates each, and the projections agree")
    void aSaleBooksOneRowPerLocationAndAllocatesEachOfThem() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        applyReceipt(variantId, secondaryLocationId, "6", UUID.randomUUID());

        var referenceId = UUID.randomUUID();
        sell(variantId, "14", referenceId);

        var sales = ledgerMovementsOf(variantId, StockLedgerWriter.SALE);
        assertThat(sales)
                .as("the legacy engine writes one SALE row per location it consumes, and the ledger books the "
                        + "same fact: the priority location drains its 10 first, the remainder spills to the "
                        + "second location")
                .hasSize(2);
        var atSalesLocation = sales.stream()
                .filter(sale -> sale.locationId().equals(salesLocationId))
                .findFirst()
                .orElseThrow();
        var atSecondary = sales.stream()
                .filter(sale -> sale.locationId().equals(secondaryLocationId))
                .findFirst()
                .orElseThrow();
        assertThat(atSalesLocation.quantity()).isEqualByComparingTo("10");
        assertThat(atSecondary.quantity()).isEqualByComparingTo("4");

        assertThat(allocatedQuantity(atSalesLocation.id()))
                .as("ADR-0001 invariant I6 (:185): SUM(allocation.quantity) per outbound row equals its "
                        + "quantity, so the consumption is recorded rather than recomputed")
                .isEqualByComparingTo("10");
        assertThat(allocatedQuantity(atSecondary.id())).isEqualByComparingTo("4");
        assertThat(allocatedCosts(atSalesLocation.id()))
                .as("FIFO consumes oldest-first, and the cost of what it consumed is frozen on the allocation")
                .hasSize(1);
        assertThat(allocatedCosts(atSalesLocation.id()).getFirst()).isEqualByComparingTo(RECORDED_COST);
        assertThat(allocatedCosts(atSecondary.id())).hasSize(1);
        assertThat(allocatedCosts(atSecondary.id()).getFirst()).isEqualByComparingTo(RECORDED_COST);
        assertThat(atSalesLocation.sourceId())
                .as("both rows mirror their own legacy row, which is why one line that spans locations does not "
                        + "collide on uq_movement_source")
                .isNotEqualTo(atSecondary.sourceId());

        assertThat(unallocatedOutboundMovements())
                .as("no outbound row of this company is left without its allocations")
                .isEmpty();
        assertTheLedgerAgreesWithTheLegacyBalances("0", "2", "2");
    }

    // ──────────────────────────────────────
    // writer 3 — a reversal names the row it reverses
    // ──────────────────────────────────────

    @Test
    @DisplayName("a reversal books a SALE_REVERSAL that names the sale it reverses, and the projections agree")
    void aSaleReversalNamesTheSaleItReverses() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        var referenceId = UUID.randomUUID();
        sell(variantId, "4", referenceId);
        var sale = ledgerMovementsOf(variantId, StockLedgerWriter.SALE).getFirst();

        reverse(referenceId);

        var reversals = ledgerMovementsOf(variantId, StockLedgerWriter.SALE_REVERSAL);
        assertThat(reversals)
                .as("ADR-0001 decision 1: a reversal is a new row that names the row it reverses, never an edit")
                .hasSize(1);
        var reversal = reversals.getFirst();
        assertThat(reversal.reversesMovementId())
                .as("V61's shape rules are satisfied by construction: the exact row, the same store and "
                        + "variant, the same quantity")
                .isEqualTo(sale.id());
        assertThat(reversal.quantity()).isEqualByComparingTo("4");
        assertThat(reversal.locationId()).isEqualTo(salesLocationId);
        assertThat(allocatedQuantity(reversal.id()))
                .as("a reversal consumes nothing: it names a row, and the rows it frees are the ones that row "
                        + "consumed")
                .isEqualByComparingTo("0");
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("and the reversed sale is untouched — the ledger is append-only")
                .hasSize(1);

        assertTheLedgerAgreesWithTheLegacyBalances("10", "10", "10");
    }

    // ──────────────────────────────────────
    // writer 4 — the manual per-store edit, in both directions
    // ──────────────────────────────────────

    @Test
    @DisplayName("a manual edit books ADJUSTMENT_IN when it adds and ADJUSTMENT_OUT when it subtracts")
    void aManualEditBooksTheAdjustmentThatExplainsIt() {
        setStock(variantId, "10");

        var increase = ledgerMovementsOf(variantId, StockLedgerWriter.ADJUSTMENT_IN);
        assertThat(increase)
                .as("W3-D7's type carries the direction: an increase writes ADJUSTMENT_IN")
                .hasSize(1);
        assertThat(increase.getFirst().quantity()).isEqualByComparingTo("10");
        assertThat(increase.getFirst().unitCost())
                .as("an ADJUSTMENT_IN adds quantity, so ck_movement_inbound_cost makes it state its cost; the "
                        + "operator's cost is the one this store records for the variant")
                .isEqualByComparingTo(RECORDED_COST);
        assertThat(increase.getFirst().currency()).isEqualTo(TenantFixtures.SEED_CURRENCY);

        setStock(variantId, "4");

        var decrease = ledgerMovementsOf(variantId, StockLedgerWriter.ADJUSTMENT_OUT);
        assertThat(decrease)
                .as("a decrease is ADJUSTMENT_OUT, and it is allocated like a sale so no location goes negative")
                .hasSize(1);
        assertThat(decrease.getFirst().quantity()).isEqualByComparingTo("6");
        assertThat(allocatedQuantity(decrease.getFirst().id()))
                .as("an outbound row without its allocations violates I6")
                .isEqualByComparingTo("6");
        assertThat(unallocatedOutboundMovements()).isEmpty();

        assertTheLedgerAgreesWithTheLegacyBalances("4", "4", "4");
    }

    // ──────────────────────────────────────
    // the cleanup the other suites depend on
    // ──────────────────────────────────────

    @Test
    @DisplayName("the shared ledger cleanup empties the company's rows, so it cannot be masking a defect")
    void theSharedCleanupEmptiesTheLedgerForTheCompany() {
        applyReceipt(variantId, salesLocationId, "6", UUID.randomUUID());

        assertThat(countLedgerMovements())
                .as("the state the cleanup has to remove, so the case cannot pass vacuously over an empty " + "ledger")
                .isEqualTo(1);
        assertThat(countAllocations()).isZero();
        assertThat(positionOf(variantId)).isEqualByComparingTo("6");

        deleteStockLedger(jdbcTemplate, companyId);

        assertThat(countLedgerMovements())
                .as("every suite's teardown calls this helper and then deletes the variant, the location and "
                        + "the store those rows name: a row left here fails the NEXT suite on "
                        + "fk_movement_variant or fk_movement_location, which is a failure nobody can read")
                .isZero();
        assertThat(countAllocations()).isZero();
        assertThat(countProjections())
                .as("and the three projections go with the ledger they were derived from, so nothing is left "
                        + "describing stock no movement explains")
                .isZero();
        assertThat(countLegacyMovements())
                .as("the helper owns the stock ledger only: the legacy ledger is the suites' own business")
                .isEqualTo(1);

        // and the same helper is what this class's @AfterEach runs, so the teardown is the thing just proven
    }

    // ──────────────────────────────────────
    // the two collisions the source identity has to survive
    // ──────────────────────────────────────

    @Test
    @DisplayName("the same line sold again after its reversal books a second SALE row instead of colliding")
    void theSameLineSoldAgainAfterItsReversalBooksASecondSale() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        var referenceId = UUID.randomUUID();

        sell(variantId, "4", referenceId);
        reverse(referenceId);
        sell(variantId, "4", referenceId);

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("SalesOrderService raises a line's quantity by selling the difference and lowers it by "
                        + "reversing and re-selling, always under the same SALES_ORDER_ITEM reference "
                        + "(SalesOrderService:989, :991-992). The ledger is append-only, so the first SALE row "
                        + "survives; making the line id the source would refuse the second one")
                .hasSize(2);
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE_REVERSAL))
                .hasSize(1);
        assertThat(unallocatedOutboundMovements()).isEmpty();

        assertTheLedgerAgreesWithTheLegacyBalances("6", "6", "6");
    }

    @Test
    @DisplayName("one receipt document carrying two lines books both RECEIPT rows instead of colliding")
    void aReceiptWithTwoLinesOfOneDocumentBooksBoth() {
        var receiptDocumentId = UUID.randomUUID();

        applyReceipt(variantId, salesLocationId, "3", receiptDocumentId);
        applyReceipt(secondVariantId, salesLocationId, "5", receiptDocumentId);

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT))
                .as("GoodsReceiptService:269 passes the goods_receipts row once per line, so a document id as "
                        + "the source would refuse the second line of every multi-line receipt")
                .hasSize(1);
        assertThat(ledgerMovementsOf(secondVariantId, StockLedgerWriter.RECEIPT))
                .hasSize(1);
        assertThat(positionOf(secondVariantId)).isEqualByComparingTo("5");
        assertTheLocationAgrees(secondVariantId, salesLocationId, "5", "the sales location");
        assertThat(verifyStockProjections()).isEmpty();
    }

    // ──────────────────────────────────────
    // the tenant scope ADR-0005 requires, in both directions
    // ──────────────────────────────────────

    @Test
    @DisplayName("without the transaction's tenant scope the ledger refuses the row instead of writing nothing")
    void theLedgerRefusesToWriteWithoutTheTenantScope() {
        var booking = new StockLedgerWriter.Booking(
                companyId,
                storeId,
                salesLocationId,
                variantId,
                "receiver",
                StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                UUID.randomUUID());

        assertThatThrownBy(() -> stockLedgerWriter.bookReceipt(
                        booking,
                        new BigDecimal("5"),
                        new BigDecimal(RECORDED_COST),
                        TenantFixtures.SEED_CURRENCY,
                        null))
                .as("first gate: a movement outside the transaction that moves the legacy balance would let "
                        + "the two copies disagree after a rollback, so the writer refuses to book one")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside the transaction");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> stockLedgerWriter.bookReceipt(
                                booking,
                                new BigDecimal("5"),
                                new BigDecimal(RECORDED_COST),
                                TenantFixtures.SEED_CURRENCY,
                                null)))
                .as("second gate, and the one ADR-0005 owns: the five ledger tables are armed on tenant_table "
                        + "(V60) and FORCED, so an insert from a transaction that states no app.company_id is "
                        + "refused by the policy. The failure is the loud one — a silently skipped row would let "
                        + "the legacy balance move with no ledger row to explain it")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("row-level security policy"));

        assertThat(countLedgerMovements()).as("and the refusal stored nothing").isZero();

        assertThatThrownBy(() -> inventoryService.applyReceipt(
                        variantId,
                        storeId,
                        salesLocationId,
                        new BigDecimal("5"),
                        GOODS_RECEIPT,
                        UUID.randomUUID(),
                        "receiver"))
                .as("the same fact seen through the public writer: without the scope the store it derives the "
                        + "tenant from is not visible, so nothing is booked and nothing is moved")
                .isInstanceOf(CompanyStoreNotFoundException.class);
        assertThat(storeStockOf(variantId)).isEqualByComparingTo("0");
        assertThat(countLegacyMovements()).isZero();
        assertThat(countLedgerMovements()).isZero();
    }

    // ──────────────────────────────────────
    // helpers: the public writers, called the way the callers call them
    // ──────────────────────────────────────

    private void applyReceipt(UUID variant, UUID location, String quantity, UUID referenceId) {
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applyReceipt(
                        variant, storeId, location, new BigDecimal(quantity), GOODS_RECEIPT, referenceId, "receiver"));
    }

    private void sell(UUID variant, String quantity, UUID referenceId) {
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applySaleDeduction(
                        variant, storeId, new BigDecimal(quantity), SALES_ORDER_ITEM, referenceId, "seller"));
    }

    private void reverse(UUID referenceId) {
        TenantFixtures.inTenant(
                companyId, () -> inventoryService.applySaleReversal(SALES_ORDER_ITEM, referenceId, "seller"));
    }

    private void setStock(UUID variant, String absoluteStock) {
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applyStockAdjustment(
                        variant, storeId, new BigDecimal(absoluteStock), "operator"));
    }

    // ──────────────────────────────────────
    // helpers: the agreement property, and the ledger's own checks
    // ──────────────────────────────────────

    /**
     * The property this slice exists for: after a writer ran, the ledger's two projections are the legacy
     * balances, for the store and for each of its locations.
     */
    private void assertTheLedgerAgreesWithTheLegacyBalances(
            String salesLocationStock, String secondaryLocationStock, String storeStock) {
        assertThat(storeStockOf(variantId))
                .as("the legacy aggregate the callers and the screens still read")
                .isEqualByComparingTo(storeStock);
        assertThat(positionOf(variantId))
                .as("ADR-0001 decision 2 (:51-55): stock_position is derived from the ledger, so it has to be "
                        + "the number product_variant_store_stock.stock holds — the two copies of one fact")
                .isEqualByComparingTo(storeStock);

        assertTheLocationAgrees(variantId, salesLocationId, salesLocationStock, "the sales location");
        assertTheLocationAgrees(variantId, secondaryLocationId, secondaryLocationStock, "the second location");

        assertThat(verifyStockProjections())
                .as("invariant I2, over all three projections of this company: nothing the writers booked "
                        + "disagrees with a full replay of the ledger")
                .isEmpty();
        assertThat(unallocatedOutboundMovements())
                .as("invariant I6: every outbound row the writers booked is fully allocated")
                .isEmpty();
    }

    /** The ledger rows of one type, oldest first, as the fields the agreement is about. */
    private List<LedgerMovement> ledgerMovementsOf(UUID variant, String movementType) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT id, movement_type, quantity, unit_cost, currency, company_store_id, store_location_id,
                               source_type, source_id, reverses_movement_id
                          FROM stock_movement
                         WHERE product_variant_id = ? AND movement_type = ?
                         ORDER BY occurred_at, id
                        """,
                        (rs, rowNum) -> new LedgerMovement(
                                rs.getObject("id", UUID.class),
                                rs.getString("movement_type"),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("unit_cost"),
                                rs.getString("currency"),
                                rs.getObject("company_store_id", UUID.class),
                                rs.getObject("store_location_id", UUID.class),
                                rs.getString("source_type"),
                                rs.getObject("source_id", UUID.class),
                                rs.getObject("reverses_movement_id", UUID.class)),
                        variant,
                        movementType));
    }

    /** The ids of the legacy ledger rows of one type, which is the identity a ledger row mirrors. */
    private List<UUID> legacyMovementsOf(UUID variant, String movementType) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT id FROM inventory_movements WHERE product_variant_id = ? AND movement_type = ? "
                                + "ORDER BY occurred_at, id",
                        (rs, rowNum) -> rs.getObject("id", UUID.class),
                        variant,
                        movementType));
    }

    private BigDecimal allocatedQuantity(UUID outMovementId) {
        var allocated = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(quantity), 0) FROM stock_allocation WHERE out_movement_id = ?",
                        BigDecimal.class,
                        outMovementId));
        return allocated == null ? BigDecimal.ZERO : allocated;
    }

    private List<BigDecimal> allocatedCosts(UUID outMovementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT unit_cost FROM stock_allocation WHERE out_movement_id = ? ORDER BY created_at",
                        (rs, rowNum) -> rs.getBigDecimal("unit_cost"),
                        outMovementId));
    }

    private List<Unallocated> unallocatedOutboundMovements() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT out_movement_id, quantity, allocated FROM unallocated_stock_movements(?)",
                        (rs, rowNum) -> new Unallocated(
                                rs.getObject("out_movement_id", UUID.class),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("allocated")),
                        companyId));
    }

    private List<String> verifyStockProjections() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT projection FROM verify_stock_projections(?)",
                        (rs, rowNum) -> rs.getString("projection"),
                        companyId));
    }

    // ──────────────────────────────────────
    // helpers: the balances the two copies of the fact live in
    // ──────────────────────────────────────

    private BigDecimal storeStockOf(UUID variant) {
        return jdbcTemplate.queryForObject(
                "SELECT stock FROM product_variant_store_stock WHERE product_variant_id = ? AND company_store_id = ?",
                BigDecimal.class,
                variant,
                storeId);
    }

    /**
     * The agreement, per location: the legacy balance row and the derived {@code stock_balance} are the same
     * number — or both absent, which is the same fact said twice, since neither table gets a row for a
     * location nothing ever moved at.
     */
    private void assertTheLocationAgrees(UUID variant, UUID location, String expected, String where) {
        var legacy = locationStockOf(variant, location);
        var ledger = balanceOf(variant, location);
        if (legacy.isEmpty()) {
            assertThat(ledger)
                    .as(
                            "no legacy balance row and no ledger balance row at %s: the two copies agree that "
                                    + "nothing happened there",
                            where)
                    .isEmpty();
            return;
        }
        assertThat(legacy.get()).as("the legacy balance at %s", where).isEqualByComparingTo(expected);
        assertThat(ledger)
                .as("and stock_balance, derived from the same movements, is the same number at %s", where)
                .isPresent();
        assertThat(ledger.get()).isEqualByComparingTo(expected);
    }

    private Optional<BigDecimal> locationStockOf(UUID variant, UUID location) {
        return jdbcTemplate
                .query(
                        "SELECT stock FROM product_variant_locations WHERE product_variant_id = ? AND store_location_id = ?",
                        (rs, rowNum) -> rs.getBigDecimal("stock"),
                        variant,
                        location)
                .stream()
                .findFirst();
    }

    private BigDecimal positionOf(UUID variant) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variant));
    }

    private Optional<BigDecimal> balanceOf(UUID variant, UUID location) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate
                        .query(
                                "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                                (rs, rowNum) -> rs.getBigDecimal("quantity"),
                                location,
                                variant)
                        .stream()
                        .findFirst());
    }

    private int countLegacyMovements() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_movements", Integer.class);
    }

    private int countAllocations() {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_allocation", Integer.class));
    }

    private int countProjections() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM stock_balance) + (SELECT COUNT(*) FROM stock_position)"
                                + " + (SELECT COUNT(*) FROM stock_valuation)",
                        Integer.class));
    }

    private int countLedgerMovements() {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movement", Integer.class));
    }

    /** The driver's own sentence, at the bottom of the Spring exception chain. */
    private static String rootCauseMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    // ──────────────────────────────────────
    // helpers: cleanup
    // ──────────────────────────────────────

    /**
     * Deletes the ledger rows this suite wrote through the shared helper, which is itself under test in
     * {@link #theSharedCleanupEmptiesTheLedgerForTheCompany()}: a teardown that quietly stopped deleting
     * would leave rows that block the next suite's own cleanup, and nothing would say so.
     */
    private void deleteTheLedger() {
        if (companyId == null) {
            return;
        }
        deleteStockLedger(jdbcTemplate, companyId);
    }

    private void deleteTheLegacyBalances() {
        if (companyId == null) {
            return;
        }
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update("DELETE FROM inventory_movements WHERE company_store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM product_variant_locations");
            jdbcTemplate.update("DELETE FROM product_variant_store_stock WHERE company_store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store_inventory_settings WHERE company_store_id = ?", storeId);
        });
    }

    private record InsertedRow(UUID companyId, UUID id) {}

    private void deleteRow(InsertedRow row, String sql) {
        TenantFixtures.inTenant(row.companyId(), () -> jdbcTemplate.update(sql, row.id()));
    }

    // ──────────────────────────────────────
    // helpers: what a case reads back
    // ──────────────────────────────────────

    /** One ledger row, with every field the agreement and the movement's shape are judged on. */
    private record LedgerMovement(
            UUID id,
            String movementType,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency,
            UUID storeId,
            UUID locationId,
            String sourceType,
            UUID sourceId,
            UUID reversesMovementId) {}

    /** One I6 violation: an outbound row and what its allocations sum to. */
    private record Unallocated(UUID outMovementId, BigDecimal quantity, BigDecimal allocated) {}

    // ──────────────────────────────────────
    // helpers: the tree every case hangs from
    // ──────────────────────────────────────

    private UUID insertCompanyCountry(UUID countryId) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                        id,
                        companyId,
                        countryId));
        insertedCompanyCountries.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertRegion(UUID companyCountryId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                        INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name,
                                                     enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, companyCountryId, companyId, suffix, "Booking region " + suffix));
        insertedRegions.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertCompanyZone(UUID regionId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, regionId, companyId, suffix, "Booking zone " + suffix));
        insertedCompanyZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertStore(UUID companyZoneId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled) "
                                + "VALUES (?, ?, ?, ?, true)",
                        id,
                        companyZoneId,
                        companyId,
                        "Booking store " + suffix));
        insertedStores.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertArea() {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) "
                                + "VALUES (?, ?, ?, ?, true)",
                        id,
                        storeId,
                        suffix,
                        "Booking area " + suffix));
        insertedAreas.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertZone(UUID areaId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, areaId, store, suffix, "Booking store zone " + suffix));
        insertedZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertLocation(UUID storeZoneId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                        INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code,
                                                     location_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, storeZoneId, store, suffix, "Booking location " + suffix));
        insertedLocations.add(new InsertedRow(companyId, id));
        return id;
    }

    private void insertSettings() {
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_inventory_settings (company_store_id, receiving_location_id,
                                                              sales_location_id)
                        VALUES (?, ?, ?)
                        """, storeId, salesLocationId, salesLocationId));
    }

    /**
     * One product, one variant, and the per-store stock row the legacy engine locks first. The row starts at
     * zero and at the cost this deployment records, so every unit in the ledger entered through a writer —
     * which is what makes the agreement this suite asserts an observation instead of a fixture.
     */
    private UUID insertVariant(String suffix) {
        var productId = UUID.randomUUID();
        var variantId = UUID.randomUUID();
        var sku = "BOOKING-" + suffix + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        var measureUnitId =
                jdbcTemplate.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update(
                    "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                    productId,
                    sku,
                    "Booking product " + sku,
                    companyId);
            jdbcTemplate.update("""
                    INSERT INTO product_variants (id, company_id, product_id, variant_name, measure_unit_id)
                    VALUES (?, ?, ?, 'unica', ?)
                    """, variantId, companyId, productId, measureUnitId);
            jdbcTemplate.update("""
                    INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, stock, cost_price)
                    VALUES (?, ?, 0, ?)
                    """, variantId, storeId, new BigDecimal(RECORDED_COST));
        });
        insertedProducts.add(new InsertedRow(companyId, productId));
        insertedVariants.add(new InsertedRow(companyId, variantId));
        return variantId;
    }
}
