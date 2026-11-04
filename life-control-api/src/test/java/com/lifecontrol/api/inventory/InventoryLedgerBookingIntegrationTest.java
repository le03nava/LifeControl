package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.service.InventoryService;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T5a of {@code odd/tasks/inventory-ledger.md}, as T3c leaves it: the writers of {@link InventoryService} book the
 * ledger, and the ledger is the only place stock is written.
 *
 * <p><b>What this suite is the witness of.</b> Every case drives a public writer of {@link InventoryService} — the
 * same entry point {@code GoodsReceiptService}, {@code SalesOrderService} and {@code ProductVariantService} call —
 * and then asserts the movement it booked (its type, its quantity, its cost and currency, and — for an outbound
 * row — the allocations the engine recorded for it, invariant I6) and the balances {@code V61}'s trigger derived
 * from it:</p>
 * <pre>{@code
 * stock_position.quantity = the movements booked for that (store, variant)
 * stock_balance.quantity  = the movements booked for that (location, variant)
 * }</pre>
 * <p>Nothing asserts on a trigger or a function body: the ledger's own engine is T3's suite, and this one does not
 * restate its cases. Nothing reads or writes the two legacy {@code stock} columns either: since
 * {@code odd/tasks/ledger-mirror-retirement.md} S4 the write path stopped maintaining them, so a fixture that
 * seeded them would construct a second truth that only a test could see, and an assertion that compared the ledger
 * to them would be comparing it to a frozen number.</p>
 *
 * <p><b>The tenant scope is stated, never assumed.</b> The pool logs in as {@code lifecontrol_app}, and
 * {@code V60} armed all five ledger tables on {@code tenant_table}, so every statement the ledger runs meets
 * ADR-0005's policy. A case therefore calls the writer inside {@code TenantFixtures.inTenant(...)} — exactly
 * as {@code InventoryIntegrationTest} does — and one case proves the other direction: with no scope stated,
 * the ledger refuses the row instead of writing nothing and reporting success.</p>
 *
 * <p><b>Two collision cases, which is what the {@code source_id} decision is judged by.</b> {@code
 * uq_movement_source} is {@code (source_type, source_id, movement_type, store_location_id, write_seq)} since
 * {@code V73}, and the naive source identity — the document's id, or the line's id alone — cannot satisfy it in
 * two shapes this tree really writes: a sale line that spans locations books one row per location, and a line
 * sold again after its reversal books a second {@code SALE} under the same reference. Cases 5 and 6 pin those
 * two, and case 6 states why a receipt still does <b>not</b> move to the line identity. See
 * {@link StockLedgerWriter}'s javadoc for where each {@code source_id} and {@code write_seq} comes from.</p>
 */
@SpringBootTest
@DisplayName("Inventory ledger booking integration tests (T5a)")
class InventoryLedgerBookingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "LEDGER-BOOKING-A";
    private static final String COMPANY_RFC = "LED030303EF3";

    /**
     * The sales-order line discriminator the callers pass. A receipt's own ({@code GOODS_RECEIPT_ITEM}) is stated by
     * {@link StockLedgerWriter#RECEIPT_LINE_SOURCE_TYPE}, because the writer is what declares it.
     */
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
    // writer 1 — a receipt books one RECEIPT row, and the projections follow it
    // ──────────────────────────────────────

    @Test
    @DisplayName("a receipt books one RECEIPT row with its cost and currency, and the projections agree")
    void aReceiptBooksTheLedgerAndTheProjectionsAgree() {
        var receiptLineId = UUID.randomUUID();

        applyReceipt(variantId, salesLocationId, "10", receiptLineId);

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
                .as("a receipt names its own line, not a mirrored row: the ledger's receipt identity no longer "
                        + "dies with inventory_movements (odd/tasks/ledger-mirror-retirement.md, S2)")
                .isEqualTo(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
        assertThat(receipt.sourceId())
                .as("one fact, one row: the source is the goods_receipt_items row this movement booked")
                .isEqualTo(receiptLineId);

        assertTheLedgerHolds("10", "0", "10");
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
        assertThat(atSalesLocation.sourceType())
                .as("a sale books the line's own identity (V73): the movement names the document line, so the "
                        + "ledger alone answers which line caused it")
                .isEqualTo(SALES_ORDER_ITEM);
        assertThat(atSalesLocation.sourceId())
                .as("the source is the line id, and both rows of the line name it")
                .isEqualTo(referenceId);
        assertThat(atSecondary.sourceId()).isEqualTo(referenceId);
        assertThat(atSalesLocation.locationId())
                .as("the two rows do not collide because the location is in the key (V63), not because their "
                        + "sources differ: one line that spans locations is one source and two facts")
                .isNotEqualTo(atSecondary.locationId());
        assertThat(atSalesLocation.writeSeq())
                .as("and both belong to the same write of that line")
                .isEqualTo(atSecondary.writeSeq());

        assertThat(unallocatedOutboundMovements())
                .as("no outbound row of this company is left without its allocations")
                .isEmpty();
        assertTheLedgerHolds("0", "2", "2");
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

        assertTheLedgerHolds("10", "0", "10");
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

        assertTheLedgerHolds("4", "0", "4");
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
                .as("and the receipt booked no legacy row at all: the five legacy writers stopped with T3c (S4), so "
                        + "the only ledger this helper has to own is stock_movement")
                .isZero();

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

        // The two writes of this line state their own numbers (V73): the column starts at 0 and a line's first bump
        // hands out 1, so a line sold, reversed and sold again carries 1 and 2. The fixture states them instead of
        // letting a default decide, because the number is the document's and the key is what this case is about.
        sell(variantId, "4", referenceId, 1L);
        reverse(referenceId);
        sell(variantId, "4", referenceId, 2L);

        var sales = ledgerMovementsOf(variantId, StockLedgerWriter.SALE);
        assertThat(sales)
                .as("SalesOrderService raises a line's quantity by selling the difference and lowers it by "
                        + "reversing and re-selling, always under the same SALES_ORDER_ITEM reference "
                        + "(SalesOrderService:961-981). The ledger is append-only, so the first SALE row survives; "
                        + "the fifth key column is what lets the second one exist under the same line")
                .hasSize(2);
        assertThat(sales)
                .extracting(LedgerMovement::sourceType)
                .as("both rows are the line's, which is the identity a sale books from V73 on")
                .containsOnly(SALES_ORDER_ITEM);
        assertThat(sales)
                .extracting(LedgerMovement::sourceId)
                .as("both name the same sales_order_items row")
                .containsOnly(referenceId);
        assertThat(sales)
                .extracting(LedgerMovement::writeSeq)
                .as("and they are two writes, not one: source, direction and location are identical, so the "
                        + "write is the only column that tells them apart")
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE_REVERSAL))
                .hasSize(1);
        assertThat(unallocatedOutboundMovements()).isEmpty();

        assertTheLedgerHolds("6", "0", "6");
    }

    @Test
    @DisplayName("one receipt document carrying two lines books both RECEIPT rows instead of colliding")
    void aReceiptWithTwoLinesOfOneDocumentBooksBoth() {
        // Two lines of ONE receipt document. The document id is deliberately absent from the call: the ledger
        // states the line, which is the identity that survives the mirror, and this case is here because the
        // document id is the one a reader would reach for first.
        var firstLineId = UUID.randomUUID();
        var secondLineId = UUID.randomUUID();

        applyReceipt(variantId, salesLocationId, "3", firstLineId);
        applyReceipt(secondVariantId, salesLocationId, "5", secondLineId);

        var firstLine = ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT).getFirst();
        var secondLine =
                ledgerMovementsOf(secondVariantId, StockLedgerWriter.RECEIPT).getFirst();
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT))
                .as("one document carrying two lines is two facts, and the ledger has to state both")
                .hasSize(1);
        assertThat(ledgerMovementsOf(secondVariantId, StockLedgerWriter.RECEIPT))
                .hasSize(1);
        assertThat(List.of(firstLine.sourceType(), secondLine.sourceType()))
                .as("the receipt now names its own line (S2 of odd/tasks/ledger-mirror-retirement.md): the "
                        + "document id cannot be the source, because uq_movement_source does not carry the "
                        + "variant and the second line of every multi-line receipt would collide with the first, "
                        + "and the mirrored row cannot be it either, because that row dies with inventory_movements")
                .containsOnly(StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE);
        assertThat(firstLine.sourceId())
                .as("and the source is the line that booked it, not the document both lines share")
                .isEqualTo(firstLineId);
        assertThat(secondLine.sourceId()).isEqualTo(secondLineId);
        assertThat(positionOf(secondVariantId)).isEqualByComparingTo("5");
        assertTheLedgerHoldsAt(secondVariantId, salesLocationId, "5", "the sales location");
        assertThat(verifyStockProjections()).isEmpty();
    }

    @Test
    @DisplayName("two lines of one receipt for one variant in one location book two rows, not one")
    void twoLinesOfOneReceiptForOneVariantInOneLocationBookTwoRows() {
        // The grain the new key has to hold: source is the LINE, so the same variant received twice on one
        // document, into one location, is two rows. Under a document id it would be one row and a refusal.
        applyReceipt(variantId, salesLocationId, "3", UUID.randomUUID());
        applyReceipt(variantId, salesLocationId, "5", UUID.randomUUID());

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT))
                .as("V60:120 makes one movement name one location, and I5 makes one movement per source line: two "
                        + "lines of one document at one location are two facts, and the key must not merge them")
                .hasSize(2);
        assertThat(positionOf(variantId))
                .as("and the projection followed both rows into the same position")
                .isEqualByComparingTo("8");
        assertTheLedgerHoldsAt(variantId, salesLocationId, "8", "the sales location");
        assertThat(verifyStockProjections()).isEmpty();
    }

    // ──────────────────────────────────────
    // the split reads the ledger's projection (T3) — its divergence-only case is gone
    // ──────────────────────────────────────

    /**
     * The rollout window this slice creates: an upgraded database whose frozen column still holds the last number
     * the old writer set. A sale must split by the projection, not by that number.
     *
     * <p><b>Why this case survives the deletion rule the others of its kind met.</b> Their state — two copies both
     * maintained, disagreeing — cannot exist any more. This one can: nothing in this tree writes the column, so a
     * deployment that upgrades keeps whatever it held, and a hand with a psql prompt can still set it. That is
     * exactly the window between T3c and T4's drop, and it is the case that proves the split's source moved rather
     * than that the column happened to read zero.</p>
     */
    @Test
    @DisplayName("a sale splits by the ledger's projection even when the frozen column holds another number")
    void aSaleSplitsByTheProjectionNotByTheStaleColumn() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE product_variant_locations SET stock = 999 "
                                + "WHERE product_variant_id = ? AND store_location_id = ?",
                        variantId,
                        salesLocationId));

        sell(variantId, "5", UUID.randomUUID());

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("the projection holds 10 at that location, so a sale of 5 has a shape even though the frozen "
                        + "column claims 999: the split's source is the ledger")
                .hasSize(1);
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE)
                        .getFirst()
                        .quantity())
                .isEqualByComparingTo("5");
        assertThat(positionOf(variantId)).as("and the position followed it").isEqualByComparingTo("5");
        assertThat(verifyStockProjections()).isEmpty();
    }

    // ──────────────────────────────────────
    // the mirror identity the deploy window still holds, and the key that still refuses a duplicate on it
    // ──────────────────────────────────────

    /**
     * A sale written <b>before</b> {@code V73} is still reversible, which is the half of the widening the deploy
     * window depends on.
     *
     * <p><b>The fixture is the state, not a shortcut.</b> A sale written before this slice carries
     * {@code source_type = 'INVENTORY_MOVEMENT'} and the id of the {@code inventory_movements} row beside it, so
     * {@code openSales('SALES_ORDER_ITEM', line)} answers nothing for it and the reversal has to read the mirror as
     * a <b>second</b> source ({@code InventoryService.openLedgerSalesBookedOnTheMirror}). Both parts of that state
     * that a reversal reads are therefore written by hand — the legacy row and the ledger movement that mirrors it
     * — because no writer in this tree produces the pair any more, which is exactly what "predates the deploy"
     * means. The two legacy balance columns are not seeded: since T3c nothing maintains them and no assertion here
     * reads them (S4).</p>
     *
     * <p><b>What it would take to make this pass for the wrong reason.</b> Nothing here reads a default: the mirror
     * row's identity and its {@code SINGLE_WRITE} are asserted before the reversal runs, so a case that accidentally
     * booked the line identity would fail instead of silently proving the other path.</p>
     */
    @Test
    @DisplayName("a sale booked on the pre-V73 mirror identity still reverses by its line reference")
    void aSaleBookedOnTheMirrorIdentityStillReverses() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        var referenceId = UUID.randomUUID();
        var legacySaleId = insertALegacySaleAndItsLedgerTwin(referenceId, "4");

        var mirrored = ledgerMovementsOf(variantId, StockLedgerWriter.SALE).getFirst();
        assertThat(mirrored.sourceType())
                .as("the identity this case is about: a sale booked before V73 names the legacy row it mirrors, "
                        + "not the document line")
                .isEqualTo(StockLedgerWriter.MIRRORED_SOURCE_TYPE);
        assertThat(mirrored.sourceId()).isEqualTo(legacySaleId);
        assertThat(mirrored.writeSeq())
                .as("a source with no counter states SINGLE_WRITE, which is what every pre-V73 row carries")
                .isEqualTo(StockLedgerWriter.SINGLE_WRITE);

        reverse(referenceId);

        var reversals = ledgerMovementsOf(variantId, StockLedgerWriter.SALE_REVERSAL);
        assertThat(reversals)
                .as("the reversal found the row the line reference no longer names: the per-reference read answers "
                        + "nothing for a pre-V73 sale, and the mirror answers for it")
                .hasSize(1);
        assertThat(reversals.getFirst().reversesMovementId())
                .as("and it names the exact row, which is what V61's shape rules demand")
                .isEqualTo(mirrored.id());
        assertThat(reversals.getFirst().quantity()).isEqualByComparingTo("4");
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("the reversed sale is untouched — the ledger is append-only")
                .hasSize(1);
        assertTheLedgerHolds("10", "0", "10");
    }

    /**
     * A reference whose ledger holds <b>both</b> identities reverses every row it can name, and both copies of the
     * fact end at the same numbers.
     *
     * <p><b>What changed, and why this case used to be the opposite one.</b> Until T2 the amount a reversal
     * credited was computed from {@code inventory_movements} and then compared, row by row, against the ledger's
     * open rows; a reference holding 4 under the mirror identity and 3 under the line identity had a legacy
     * remainder of 7 the ledger could only name 3 of, so it was refused. The refusal was never a property of the
     * ledger — it was an artefact of deriving the amount from a second table. From T2 the amount <b>is</b> the set
     * of open rows ({@code odd/tasks/ledger-mirror-retirement.md}, S3), the two reads are unioned by ledger
     * movement id, and each reversal names the row it gives back, so the whole reference is reversible in one go:
     * two reversal rows, 4 and 3, each satisfying {@code V61}'s rule that a reversal moves exactly the quantity of
     * the row it names.</p>
     */
    @Test
    @DisplayName("a reference holding both the mirror and the line identity reverses both, not half of it")
    void aReferenceHoldingBothIdentitiesReversesBothOfThem() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());
        var referenceId = UUID.randomUUID();
        insertALegacySaleAndItsLedgerTwin(referenceId, "4");
        sell(variantId, "3", referenceId, 1L);

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("the state this case is about: two SALE rows of one reference, one on each identity")
                .hasSize(2);

        reverse(referenceId);

        var reversals = ledgerMovementsOf(variantId, StockLedgerWriter.SALE_REVERSAL);
        assertThat(reversals)
                .as("one reversal per open row, because a reversal moves exactly the quantity of the row it names")
                .hasSize(2);
        assertThat(reversals)
                .extracting(LedgerMovement::quantity)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("4"), new BigDecimal("3"));
        assertThat(reversals)
                .as("and the reversal names the line beside the sale it corrects, so the ledger states one source "
                        + "for both halves of the reference")
                .allSatisfy(reversal -> assertThat(reversal.sourceId()).isEqualTo(referenceId));
        assertThat(positionOf(variantId))
                .as("the receipt put 10 in, the two sales took 7 out, and the reversal gives the 7 back")
                .isEqualByComparingTo("10");
        assertTheLedgerHolds("10", "0", "10");
    }

    /**
     * The key still refuses a second movement on one receipt line, which is the half of I5 the fifth column could
     * have destroyed.
     *
     * <p><b>What is at stake.</b> A receipt line, a manual edit and a reversal book a source that has no counter
     * and therefore state {@link StockLedgerWriter#SINGLE_WRITE}: the write column is a constant for them, so the
     * key refuses a second row exactly as it did before {@code V73}. The failure this case exists to refuse is the
     * one where the fifth column quietly becomes a discriminator — a writer that invents a number per booking, a
     * counter that is read from a clock — and every such source stops being unique at all: a replayed receipt would
     * book a second {@code RECEIPT} row and double the stock instead of being refused.</p>
     *
     * <p>The duplicate is attempted through the writer, with the same {@link StockLedgerWriter.Booking} the receipt
     * path built, so the statement under test is the one production runs.</p>
     */
    @Test
    @DisplayName("the key refuses a second movement on one receipt line instead of booking it")
    void theKeyRefusesASecondMovementOnOneReceiptLine() {
        var receiptLineId = UUID.randomUUID();
        applyReceipt(variantId, salesLocationId, "3", receiptLineId);
        var theSameFactAgain = new StockLedgerWriter.Booking(
                companyId,
                storeId,
                salesLocationId,
                variantId,
                "receiver",
                StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                receiptLineId,
                StockLedgerWriter.SINGLE_WRITE);

        assertThatThrownBy(() -> TenantFixtures.inTenant(companyId, () -> {
                    stockLedgerWriter.bookReceipt(
                            theSameFactAgain,
                            new BigDecimal("3"),
                            new BigDecimal(RECORDED_COST),
                            TenantFixtures.SEED_CURRENCY,
                            null);
                }))
                .as("the same source, direction and location under the same write is one key, so the second "
                        + "booking is refused by the database rather than booked as a second fact")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("uq_movement_source"));

        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.RECEIPT))
                .as("and the refusal stored nothing: the ledger still holds the one RECEIPT row")
                .hasSize(1);
        assertThat(positionOf(variantId)).as("so the projection did not double").isEqualByComparingTo("3");
        assertThat(balanceOf(variantId, salesLocationId))
                .as("nor did the balance at the location the row names")
                .hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo("3"));
    }

    // ──────────────────────────────────────
    // the position is the authority a sale is refused by
    // ──────────────────────────────────────

    /**
     * The refusal is the documented sale contract, and the number it refuses with is the ledger's position.
     *
     * <p><b>What this case is about now.</b> Until T3c the fixture had to create a state where the two copies of
     * the fact disagreed — the legacy copies raised above the sale and the position below it — because otherwise
     * no case could tell which gate refused. There is one copy now: the receipt books 10, the position says 10,
     * and a sale of 15 is refused by the position. That refusal is the socket the sale contract has always
     * documented (409), not the {@code DataAccessException} {@code V61}'s invariant I8 would raise from inside the
     * projection trigger — which is the 500 this gate exists to replace.</p>
     */
    @Test
    @DisplayName("a sale the position cannot cover answers the 409 contract, not the trigger's database error")
    void aSaleThePositionCannotCoverAnswersTheSaleContract() {
        applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());

        assertThatThrownBy(() -> sell(variantId, "15", UUID.randomUUID()))
                .as("the position is the authority a sale is refused by, and its refusal is the exception the "
                        + "sale contract has always documented — not a DataAccessException from the trigger")
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock for variant " + variantId)
                .hasMessageContaining("requested 15")
                .hasMessageContaining("available 10");

        assertThat(positionOf(variantId)).isEqualByComparingTo("10");
        assertThat(balanceOf(variantId, salesLocationId))
                .as("the refused deduction moved no balance either")
                .hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo("10"));
        assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                .as("the refusal stored no movement: a refused sale is not a fact")
                .isEmpty();
        assertThat(legacyMovementsOf(variantId, "SALE"))
                .as("and no legacy row either, because nothing writes that table any more (S4)")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // the position gate takes the projection row's own lock (T5c)
    // ──────────────────────────────────────

    /**
     * T5c: {@code applySaleDeduction}'s step 4b reads {@code stock_position} <b>under the row's lock</b>, so the
     * gate serializes on the row it decides about instead of relying on the store row locked earlier in the same
     * method — a row that no longer carries any stock at all (T3c).
     *
     * <p><b>Why the case has to be a race and not an assertion about a query.</b> A case that only asserts a
     * refusal cannot tell a locking read from a plain one. The case therefore holds the {@code stock_position} row
     * from a second transaction, after lowering it below what the sale needs, and observes what the deduction does
     * <b>while that lock is held</b>.</p>
     *
     * <p><b>What distinguishes the two behaviours, exactly.</b> The holder raises the row lock <em>before</em> the
     * deduction starts, so a locking read cannot return the pre-update quantity at all: it waits, and under
     * {@code READ COMMITTED} it then reads what the holder committed. A plain read does not wait — MVCC hands it
     * the last committed version, which is the quantity that still passes the gate — so the deduction proceeds,
     * books its movement, and is refused later by {@code V61}'s trigger as a {@code DataAccessException}. The
     * assertion below is therefore the exception <b>type and number</b>, and neither outcome can be reached by
     * timing: with the lock the gate always reads the committed {@code 8}, without it the gate always reads
     * {@code 10}.</p>
     */
    @Nested
    @DisplayName("the position gate serializes on the row it decides about")
    class PositionGateLockTests {

        /** The lock the concurrent transaction holds: the same row, and the same statement a gate would run. */
        private static final String LOCK_THE_POSITION_ROW = """
                SELECT quantity FROM stock_position
                 WHERE company_store_id = ? AND product_variant_id = ?
                   FOR UPDATE
                """;

        /**
         * Text matching the statements that wait on this row: the gate's {@code select … for update} (the code
         * under test) and the projection trigger's identical one (the gate's own second line, and what a plain
         * read would fall through to).
         */
        private static final String WAITING_ON_THE_POSITION_ROW = """
                SELECT count(*) FROM pg_stat_activity
                 WHERE pid <> pg_backend_pid()
                   AND datname = current_database()
                   AND usename = current_user
                   AND wait_event_type = 'Lock'
                """;

        /** What the other sessions of this test looked like, so a timeout says why instead of just failing. */
        private static final String OTHER_SESSIONS = """
                SELECT format('%s state=%s waiting=%s/%s query=%s', pid, state, wait_event_type, wait_event,
                              left(coalesce(query, '<hidden>'), 80))
                  FROM pg_stat_activity
                 WHERE pid <> pg_backend_pid()
                   AND datname = current_database()
                """;

        @Test
        @DisplayName("a deduction waits for the position row and refuses with the quantity committed under it")
        void aDeductionWaitsForThePositionRowAndRefusesWithTheQuantityCommittedUnderIt() throws Exception {
            // The receipt books 10, so the position passes for anything up to 10 and the holder lowers it to 8.
            applyReceipt(variantId, salesLocationId, "10", UUID.randomUUID());

            var theLockIsHeld = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // The concurrent transaction: take the row's lock, lower the position below what the sale needs,
                // and hold everything open until the deduction is observed waiting on that very row.
                var holder = CompletableFuture.runAsync(
                        () -> TenantFixtures.inTenant(companyId, () -> {
                            jdbcTemplate.queryForObject(LOCK_THE_POSITION_ROW, BigDecimal.class, storeId, variantId);
                            jdbcTemplate.update(
                                    "UPDATE stock_position SET quantity = ? "
                                            + "WHERE company_store_id = ? AND product_variant_id = ?",
                                    new BigDecimal("8"),
                                    storeId,
                                    variantId);
                            theLockIsHeld.countDown();
                            awaitAnotherTransactionWaitingOnThePositionRow();
                            return null;
                        }),
                        pool);

                assertThat(theLockIsHeld.await(20, TimeUnit.SECONDS))
                        .as("the concurrent transaction holds the stock_position row's lock before the sale starts")
                        .isTrue();

                var deduction = CompletableFuture.<RuntimeException>supplyAsync(
                        () -> {
                            try {
                                sell(variantId, "9", UUID.randomUUID());
                                return null;
                            } catch (RuntimeException refused) {
                                return refused;
                            }
                        },
                        pool);

                // The holder commits only once the deduction is waiting on the row, which is what makes the
                // quantity the gate reads the quantity committed under the lock.
                holder.get(60, TimeUnit.SECONDS);

                assertThat(deduction.get(60, TimeUnit.SECONDS))
                        .as("the gate serialized on the row it decides about: it waited for the concurrent "
                                + "transaction's lock and refused with the quantity that transaction committed "
                                + "(8), which is the documented sale contract. A plain read would not wait — MVCC "
                                + "would hand it the last committed 10, the gate would pass, and the refusal would "
                                + "come from V61's trigger as a database error instead")
                        .isInstanceOf(InsufficientStockException.class)
                        .hasMessageContaining("requested 9")
                        .hasMessageContaining("available 8");

                assertThat(positionOf(variantId))
                        .as("the refused deduction moved nothing: the position is still what the holder committed")
                        .isEqualByComparingTo("8");
                assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                        .as("a refused sale is not a fact, so the ledger holds no SALE row")
                        .isEmpty();
                assertThat(legacyMovementsOf(variantId, "SALE"))
                        .as("and no legacy row either: the gate refuses before the first write of the loop")
                        .isEmpty();
                assertThat(balanceOf(variantId, salesLocationId))
                        .as("the location's balance is still the 10 the receipt booked: the refused deduction "
                                + "moved nothing, and the only number the holder changed is the position")
                        .hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo("10"));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a key the ledger never moved is refused: a row that does not exist cannot be locked")
        void aKeyTheLedgerNeverMovedIsRefused() {
            // The ledger never moved this key: no movement, therefore no position row to lock. No fixture writes
            // that state by hand — the store's stock row exists (every store/variant pair has one) and the ledger
            // has booked nothing for it, which is what a variant nobody has received looks like.
            assertThat(countPositionRows())
                    .as("the ledger never moved this key, which is the state this case is about")
                    .isZero();

            assertThatThrownBy(() -> sell(variantId, "5", UUID.randomUUID()))
                    .as("a locking read cannot lock a row that does not exist, and the gate must not read that as "
                            + "permission: an empty position is used as zero and the sale is refused. Reading it "
                            + "optimistically — \"no row, therefore nothing to check\" — would authorize a "
                            + "deduction the ledger cannot explain")
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 5")
                    .hasMessageContaining("available 0");

            assertThat(ledgerMovementsOf(variantId, StockLedgerWriter.SALE))
                    .as("and it stored nothing: no movement, no allocation")
                    .isEmpty();
            assertThat(countAllocations()).isZero();
            assertThat(countPositionRows()).isZero();
            assertThat(countProjections())
                    .as("and nothing was projected, because nothing was booked")
                    .isZero();
        }

        /** How many position rows exist for the fixture's key: zero is a state, not an error. */
        private int countPositionRows() {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stock_position "
                                    + "WHERE company_store_id = ? AND product_variant_id = ?",
                            Integer.class,
                            storeId,
                            variantId));
        }

        /**
         * Blocks until another session is waiting for a lock on this row, or fails the case.
         *
         * <p>This is the barrier the case needs and the reason it is not a sleep: the holder must not commit
         * while the deduction could still be reading a snapshot, or a plain read could read the committed value
         * and the case would pass for the wrong reason. {@code pg_stat_activity} is the database's own report of
         * the wait, and both sessions belong to the pool's role, which is what makes another session's row
         * readable ({@code has_privs_of_role}).</p>
         */
        private void awaitAnotherTransactionWaitingOnThePositionRow() {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                var waiting = jdbcTemplate.queryForObject(WAITING_ON_THE_POSITION_ROW, Integer.class);
                if (waiting != null && waiting > 0) {
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the holder was interrupted while waiting", interrupted);
                }
            }
            throw new IllegalStateException("no other transaction was waiting on the stock_position row within 20 "
                    + "seconds, so the deduction never reached the row's lock — the gate is not taking it. Other "
                    + "sessions: " + jdbcTemplate.queryForList(OTHER_SESSIONS, String.class));
        }
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
                StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                UUID.randomUUID(),
                StockLedgerWriter.SINGLE_WRITE);

        assertThatThrownBy(() -> stockLedgerWriter.bookReceipt(
                        booking,
                        new BigDecimal("5"),
                        new BigDecimal(RECORDED_COST),
                        TenantFixtures.SEED_CURRENCY,
                        null))
                .as("first gate: a movement booked outside the transaction that states the fact would survive the "
                        + "rollback of that fact, so the writer refuses to book one")
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
                        + "refused by the policy. The failure is the loud one — a silently skipped row would report "
                        + "success while the stock the caller asked to record does not exist")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("row-level security policy"));

        assertThat(countLedgerMovements()).as("and the refusal stored nothing").isZero();

        assertThatThrownBy(() -> inventoryService.applyReceipt(
                        variantId,
                        storeId,
                        salesLocationId,
                        new BigDecimal("5"),
                        StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                        UUID.randomUUID(),
                        "receiver"))
                .as("the same fact seen through the public writer: without the scope the store it derives the "
                        + "tenant from is not visible, so nothing is booked and nothing is moved")
                .isInstanceOf(CompanyStoreNotFoundException.class);
        assertThat(countProjections())
                .as("and nothing was projected: no balance, no position, no valuation")
                .isZero();
        assertThat(countLegacyMovements()).isZero();
        assertThat(countLedgerMovements()).isZero();
    }

    // ──────────────────────────────────────
    // helpers: the public writers, called the way the callers call them
    // ──────────────────────────────────────

    /**
     * One receipt line of {@code variant} into {@code location}, booked under its own line identity.
     *
     * <p>The document id no longer travels through this call: the ledger's source is the line (S2 of
     * {@code odd/tasks/ledger-mirror-retirement.md}), and the document is what a multi-line receipt shares — the
     * shape whose inadequacy as a key this file pins.</p>
     */
    private void applyReceipt(UUID variant, UUID location, String quantity, UUID receiptLineId) {
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applyReceipt(
                        variant,
                        storeId,
                        location,
                        new BigDecimal(quantity),
                        StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE,
                        receiptLineId,
                        "receiver"));
    }

    private void sell(UUID variant, String quantity, UUID referenceId) {
        sell(variant, quantity, referenceId, StockLedgerWriter.SINGLE_WRITE);
    }

    /**
     * One sale of {@code variant} under {@code referenceId}, as that reference's {@code writeSeq}-th write.
     *
     * <p>The sequence is stated by the case rather than defaulted here for {@code StockLedgerWriter}'s own reason:
     * the number belongs to the document line, and a fixture that sells the same reference twice has to say which
     * write each movement is. {@code SINGLE_WRITE} is the honest answer for the cases that sell once.</p>
     */
    private void sell(UUID variant, String quantity, UUID referenceId, long writeSeq) {
        TenantFixtures.inTenant(
                companyId,
                () -> inventoryService.applySaleDeduction(
                        variant, storeId, new BigDecimal(quantity), SALES_ORDER_ITEM, referenceId, "seller", writeSeq));
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

    /**
     * A sale as the tree wrote it <b>before</b> {@code V73}, both parts that matter to a reversal: the
     * {@code inventory_movements} row it wrote, and the ledger movement that mirrors it by primary key.
     *
     * <p>The two legacy {@code stock} columns that row also moved are deliberately left alone: since T3c nothing
     * maintains them and no case in this file reads them, so seeding them would be constructing a second truth a
     * fixture would have to keep in step for no assertion's benefit.</p>
     *
     * <p>The columns are the ones {@code V10__inventory.sql} declares for {@code inventory_movements} —
     * {@code product_variant_id}, {@code company_store_id}, {@code store_location_id}, {@code movement_type},
     * {@code quantity}, {@code reference_type}, {@code reference_id}, {@code created_by} — with {@code id} and
     * {@code occurred_at} left to their defaults, which is what the legacy engine's own insert does.</p>
     *
     * <p>The ledger twin is booked through the production writer instead of by a hand-written {@code INSERT}: it is
     * the same statement, and the allocation the writer performs is what invariant I6 requires of an outbound row —
     * a hand-written row would be refused by {@code allocate_stock_movement} the moment the reversal ran.</p>
     *
     * @return the legacy row's id, which is the {@code source_id} a pre-V73 ledger row carries
     */
    private UUID insertALegacySaleAndItsLedgerTwin(UUID referenceId, String quantity) {
        var amount = new BigDecimal(quantity);
        var legacySaleId = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        """
                        INSERT INTO inventory_movements (product_variant_id, company_store_id, store_location_id,
                                                         movement_type, quantity, reference_type, reference_id,
                                                         created_by)
                        VALUES (?, ?, ?, 'SALE', ?, ?, ?, 'seller')
                        RETURNING id
                        """, UUID.class, variantId, storeId, salesLocationId, amount, SALES_ORDER_ITEM, referenceId));

        TenantFixtures.inTenant(
                companyId,
                () -> stockLedgerWriter.bookSale(
                        new StockLedgerWriter.Booking(
                                companyId,
                                storeId,
                                salesLocationId,
                                variantId,
                                "seller",
                                StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                                legacySaleId,
                                StockLedgerWriter.SINGLE_WRITE),
                        amount));
        return legacySaleId;
    }

    // ──────────────────────────────────────
    // helpers: the ledger's own state, and its invariants
    // ──────────────────────────────────────

    /**
     * The ledger's equation after a writer ran: the store's position and each location's balance are the numbers
     * the booked movements sum to, and the ledger's invariants hold over them.
     *
     * <p>Until T3c this method asserted an agreement with the two legacy columns the write path also maintained.
     * There is one copy of the fact now — {@code stock_movement}, and the projections {@code V61}'s trigger derives
     * from it — so the expected quantities are checked against {@code stock_position} and {@code stock_balance}
     * directly. No legacy balance is read, and none is written: that is the whole point of the slice.</p>
     */
    private void assertTheLedgerHolds(String salesLocationStock, String secondaryLocationStock, String storeStock) {
        assertThat(positionOf(variantId))
                .as("ADR-0001 decision 2 (:51-55): the store's position is what the movements booked for "
                        + "(store, variant) add up to, and it is the only per-store balance there is")
                .isEqualByComparingTo(storeStock);

        assertTheLedgerHoldsAt(variantId, salesLocationId, salesLocationStock, "the sales location");
        assertTheLedgerHoldsAt(variantId, secondaryLocationId, secondaryLocationStock, "the second location");

        assertThat(verifyStockProjections())
                .as("invariant I2, over all three projections of this company: nothing the writers booked "
                        + "disagrees with a full replay of the ledger")
                .isEmpty();
        assertThat(unallocatedOutboundMovements())
                .as("invariant I6: every outbound row the writers booked is fully allocated")
                .isEmpty();
    }

    /** The ledger rows of one type, oldest first, as the fields each case judges them on. */
    private List<LedgerMovement> ledgerMovementsOf(UUID variant, String movementType) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT id, movement_type, quantity, unit_cost, currency, company_store_id, store_location_id,
                               source_type, source_id, write_seq, reverses_movement_id
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
                                rs.getLong("write_seq"),
                                rs.getObject("reverses_movement_id", UUID.class)),
                        variant,
                        movementType));
    }

    /**
     * The ids of the legacy ledger rows of one type. Read only by the cases whose subject is what the pre-V73
     * fallback answers, and never to compare a balance: the legacy table is not a balance any more.
     */
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
    // helpers: the balances the ledger itself states
    // ──────────────────────────────────────

    /**
     * One location's balance as {@code stock_balance} states it, where an absent row means zero.
     *
     * <p>{@code V61}'s trigger writes a balance row for a location the first time a movement names it, so a
     * location nothing ever moved at has no row at all — the same fact as a row of zero, which is why "0" is a
     * valid expectation for either shape.</p>
     */
    private void assertTheLedgerHoldsAt(UUID variant, UUID location, String expected, String where) {
        var balance = balanceOf(variant, location);
        if (balance.isEmpty()) {
            assertThat(new BigDecimal(expected).signum())
                    .as(
                            "no stock_balance row at %s, which is the ledger saying nothing ever moved there, so "
                                    + "the expected quantity has to be zero",
                            where)
                    .isZero();
            return;
        }
        assertThat(balance.get())
                .as("stock_balance at %s, derived from the movements booked there", where)
                .isEqualByComparingTo(expected);
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

    /** One ledger row, with every field the balances and the movement's shape are judged on. */
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
            long writeSeq,
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
     * One product, one variant, and the per-store stock row every mover locks first. The row carries the cost this
     * deployment records for the variant and no stock: the column is left to its default, because since T3c nothing
     * maintains it and a fixture that seeded it would state a balance no movement explains. Every unit this suite
     * asserts on therefore entered through a writer.
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
            jdbcTemplate.update(
                    "INSERT INTO product_variant_store_stock (product_variant_id, company_store_id, cost_price) "
                            + "VALUES (?, ?, ?)",
                    variantId,
                    storeId,
                    new BigDecimal(RECORDED_COST));
        });
        insertedProducts.add(new InsertedRow(companyId, productId));
        insertedVariants.add(new InsertedRow(companyId, variantId));
        return variantId;
    }
}
