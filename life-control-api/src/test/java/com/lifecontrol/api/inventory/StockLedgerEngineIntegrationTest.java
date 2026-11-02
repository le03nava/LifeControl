package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Behavioural verification of {@code V61__stock_ledger_engine.sql}, the engine half of ADR-0001: the
 * projection trigger, the allocation writer, the full-replay rebuild and the checks that compare the two.
 *
 * <p><b>What is proven here, and through what.</b> Every case drives the ledger through its public
 * interface — {@code INSERT INTO stock_movement} for a fact, and the SQL functions
 * {@code allocate_stock_movement}, {@code rebuild_stock_projections}, {@code verify_stock_projections} and
 * {@code unallocated_stock_movements} — and then reads the projections ({@code stock_balance},
 * {@code stock_position}, {@code stock_valuation}) and {@code stock_allocation}, which are what a reader
 * in T5 will read. Nothing asserts on a trigger, a function body or a private rule: a case that rewrote a
 * projection by hand would prove a row can be inserted, not that a number is derived. The one exception is
 * deliberate and named: the {@code I2} case writes the wrong number on purpose, because a comparison only
 * ever run against a projection the same code just wrote cannot answer the question the invariant asks.</p>
 *
 * <p><b>The invariants this slice can prove</b> ({@code docs/adr/0001-inventory-ledger-single-source.md:180-187}):
 * {@code I2} (balance, position and valuation equal a full replay), {@code I6} (every outbound row is
 * fully allocated), {@code I8} (a deduction that would take the balance negative is refused and stores
 * nothing) and decision 3's "a reversal frees exactly the rows the original consumed", which is the risk
 * item that motivated this slice. {@code I1}, {@code I3}, {@code I4}, {@code I5} and {@code I7} are the
 * schema's and stay where T2 proved them; none of them is repeated here.</p>
 *
 * <p><b>Two roles, as in the schema suite.</b> The pool logs in as {@code lifecontrol_app}, the role
 * ADR-0005's policies apply to, so every fixture runs inside the tenant scope the ledger's armed tables
 * require — a trigger that writes a projection row runs as the calling role, so an unscoped insert would
 * be refused by the policy before it proved anything. The container's superuser appears only in the
 * {@code @AfterEach}, which has to lift the append-only triggers to delete the rows this suite wrote: the
 * same rule that refuses a caller refuses the cleanup, and {@code ALTER TABLE} is owner DDL.</p>
 *
 * <p><b>Deterministic order.</b> FIFO is "oldest first", so every fixture states {@code occurred_at}
 * explicitly instead of taking the column default: two rows written in one transaction share
 * {@code now()}, and a case about which row was consumed first would then be asserting on a tie-break by
 * random UUID. The ordering the writer uses is exactly {@code (occurred_at, id)}; these fixtures make the
 * first key strict and never depend on the second.</p>
 */
@SpringBootTest
@DisplayName("Stock ledger engine integration tests (V61)")
class StockLedgerEngineIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "LEDGER-ENGINE-A";
    private static final String COMPANY_RFC = "LED020202CD2";

    /** The source discriminators the fixtures use; there is no vocabulary behind them, only a shape. */
    private static final String SOURCE_RECEIPT = "GOODS_RECEIPT_LINE";

    private static final String SOURCE_SALE = "SALES_ORDER_LINE";
    private static final String SOURCE_ADJUSTMENT = "MANUAL_ADJUSTMENT";

    /** One movement, with its cost and its instant stated: the shape every fixture in this suite uses. */
    private static final String INSERT_MOVEMENT = """
            INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id, product_variant_id,
                                        movement_type, quantity, unit_cost, currency, occurred_at, source_type,
                                        source_id, reverses_movement_id, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?::timestamptz, ?, ?, ?, 'ledger engine fixture')
            """;

    /** The container's own login, which is a SUPERUSER and the only credential that can lift a trigger. */
    private static final DataSource CONTAINER_SUPERUSER = containerSuperuserPool();

    private static final JdbcTemplate UNRESTRICTED = new JdbcTemplate(CONTAINER_SUPERUSER);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID countryId;
    private UUID companyCountryId;

    /** The store every case works on, and the second one the reversal-shape rule needs. */
    private UUID storeId;

    private UUID otherStoreId;

    /** Two locations of {@link #storeId}: one balance per location and one position per store. */
    private UUID locationA;

    private UUID locationB;

    /** One location of {@link #otherStoreId}, for the refusal that is about the store. */
    private UUID otherLocation;

    private UUID variantId;

    private final List<InsertedRow> insertedVariants = new ArrayList<>();
    private final List<InsertedRow> insertedProducts = new ArrayList<>();
    private final List<InsertedRow> insertedLocations = new ArrayList<>();
    private final List<InsertedRow> insertedStoreZones = new ArrayList<>();
    private final List<InsertedRow> insertedAreas = new ArrayList<>();
    private final List<InsertedRow> insertedStores = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyZones = new ArrayList<>();
    private final List<InsertedRow> insertedRegions = new ArrayList<>();
    private final List<InsertedRow> insertedCompanyCountries = new ArrayList<>();
    private final List<InsertedRow> insertedCompanies = new ArrayList<>();

    @BeforeEach
    void insertTheTreeEveryCaseHangsFrom() {
        companyId = insertCompany();
        countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyCountryId = insertCompanyCountry();
        var regionId = insertRegion();
        var companyZoneId = insertCompanyZone(regionId);

        storeId = insertStore(companyZoneId);
        otherStoreId = insertStore(companyZoneId);
        locationA = insertLocation(insertZone(insertArea(storeId), storeId), storeId);
        locationB = insertLocation(insertZone(insertArea(storeId), storeId), storeId);
        otherLocation = insertLocation(insertZone(insertArea(otherStoreId), otherStoreId), otherStoreId);

        variantId = insertVariant();
    }

    @AfterEach
    void removeInsertedRows() {
        deleteTheLedger();
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
        for (var row : insertedStoreZones) {
            deleteRow(row, "DELETE FROM store_zones WHERE id = ?");
        }
        insertedStoreZones.clear();
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
        for (var row : insertedCompanies) {
            deleteRow(row, "DELETE FROM companies WHERE id = ?");
        }
        insertedCompanies.clear();
    }

    // ──────────────────────────────────────
    // decision 2 — the projection is maintained by the trigger, per location and per store
    // ──────────────────────────────────────

    @Test
    @DisplayName("the trigger keeps one balance per location and one position per store, and moves both")
    void theTriggerProjectsPerLocationAndPerStore() {
        receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        receipt(locationB, "5", "10", "2026-11-01T11:00:00Z");

        assertThat(balance(locationA))
                .as("ADR-0001 decision 2 (:51-55): the location balance is derived from the movement alone — "
                        + "nothing in this case wrote stock_balance by hand")
                .isEqualByComparingTo("10");
        assertThat(balance(locationB)).isEqualByComparingTo("5");
        assertThat(position())
                .as("and the store projection is the sum of its locations, which is the number a sale checks")
                .isEqualByComparingTo("15");

        var sale = outbound(locationA, "SALE", "4", "2026-11-01T12:00:00Z");
        allocate(sale);

        assertThat(balance(locationA))
                .as("an outbound subtracts from the location it names")
                .isEqualByComparingTo("6");
        assertThat(balance(locationB))
                .as("and leaves the other location of the same store alone")
                .isEqualByComparingTo("5");
        assertThat(position()).as("while the store projection follows both").isEqualByComparingTo("11");
    }

    // ──────────────────────────────────────
    // decision 4 — FIFO consumes the oldest inbound rows, at their own cost
    // ──────────────────────────────────────

    @Test
    @DisplayName("FIFO consumes the oldest inbound row first, at that row's cost")
    void fifoConsumesTheOldestInboundRowFirst() {
        var firstReceipt = receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        var secondReceipt = receipt(locationA, "10", "20", "2026-11-01T11:00:00Z");

        var sale = outbound(locationA, "SALE", "15", "2026-11-01T12:00:00Z");
        assertThat(allocate(sale))
                .as("one allocation row per consumed inbound row, so 15 units over a 10 and a 10 consume two "
                        + "of them")
                .isEqualTo(2);

        var allocations = allocationsOf(sale);
        assertThat(allocations)
                .as("ADR-0001 `## Costing and valuation` (:200-202): FIFO consumes inbound rows oldest-first, "
                        + "and the cost applied to the consumed units is the consumed row's own")
                .hasSize(2);
        assertAllocation(allocations.get(0), firstReceipt, "10", "10");
        assertAllocation(allocations.get(1), secondReceipt, "5", "20");

        assertThat(valuationQuantity())
                .as("what is left is 5 units, and its value is the 5 units of the second receipt")
                .isEqualByComparingTo("5");
        assertThat(valuationTotal()).isEqualByComparingTo("100");
        assertThat(valuationAverage())
                .as("the value of what is on hand, divided by what is on hand")
                .isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("AVERAGE applies the moving average in force at the instant of consumption")
    void averageAppliesTheMovingAverageInForce() {
        stateCostingMethod("AVERAGE");
        receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        receipt(locationA, "10", "20", "2026-11-01T11:00:00Z");

        assertThat(valuationAverage())
                .as("the average after 10 at 10 and 10 at 20 is 15, and the projection is where it is read")
                .isEqualByComparingTo("15");

        var sale = outbound(locationA, "SALE", "5", "2026-11-01T12:00:00Z");
        allocate(sale);

        assertThat(allocationsOf(sale))
                .as("under AVERAGE every allocation carries the average in force, not the row's own cost")
                .isNotEmpty()
                .allSatisfy(allocation -> assertThat(allocation.unitCost()).isEqualByComparingTo("15"));
        assertThat(valuationQuantity()).isEqualByComparingTo("15");
        assertThat(valuationTotal())
                .as("value leaves at the average: 5 of the 300 on hand at 15")
                .isEqualByComparingTo("225");
        assertThat(valuationAverage()).isEqualByComparingTo("15");
    }

    // ──────────────────────────────────────
    // I8 — a deduction that would go negative is refused, and stores nothing
    // ──────────────────────────────────────

    @Test
    @DisplayName("I8: a deduction larger than the balance is refused before anything is written")
    void aDeductionThatWouldGoNegativeIsRefused() {
        receipt(locationA, "2", "10", "2026-11-01T10:00:00Z");
        var overtaking = UUID.randomUUID();

        assertThatThrownBy(() -> insertMovement(
                        overtaking, storeId, locationA, "SALE", "5", null, null, "2026-11-01T11:00:00Z", SOURCE_SALE))
                .as("ADR-0001 invariant I8 (:187): balance can never be negative in a store with no setting "
                        + "allowing it, and this tree has no such setting — `store_inventory_settings` (V11:19-26) "
                        + "holds two locations, a version and two timestamps, and no flag. The conservative rule "
                        + "is therefore `>= 0` everywhere, refused by the projection, which is the one place "
                        + "every writer passes")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("I8"))
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("stock would go negative"));

        assertThat(countMovements())
                .as("the refusal stored nothing: no movement")
                .isEqualTo(1);
        assertThat(countMovement(overtaking)).isZero();
        assertThat(countAllocationsOfCompany()).as("no allocation").isZero();
        assertThat(balance(locationA))
                .as("and the projection is exactly what the receipt left")
                .isEqualByComparingTo("2");
        assertThat(position()).isEqualByComparingTo("2");

        var exact = outbound(locationA, "SALE", "2", "2026-11-01T11:00:00Z");
        allocate(exact);
        assertThat(position())
                .as("the boundary is legal: exactly the balance can be deducted, and takes it to zero")
                .isEqualByComparingTo("0");
    }

    // ──────────────────────────────────────
    // I6 — every outbound row is fully allocated
    // ──────────────────────────────────────

    @Test
    @DisplayName("I6: the allocation sums to the outbound row, and the check names a writer that did not run")
    void everyOutboundRowIsFullyAllocated() {
        receipt(locationA, "20", "10", "2026-11-01T10:00:00Z");
        var sale = outbound(locationA, "SALE", "5", "2026-11-01T11:00:00Z");
        allocate(sale);

        assertThat(unallocatedOutboundMovements())
                .as("the writer ran, so the allocation sums to the outbound row and the check returns nothing")
                .isEmpty();

        var neverAllocated = outbound(locationA, "ADJUSTMENT_OUT", "3", "2026-11-01T12:00:00Z");

        var unallocated = unallocatedOutboundMovements();
        assertThat(unallocated)
                .as("ADR-0001 invariant I6 (:185): one query, and a violation means an allocation writer is "
                        + "incomplete — indistinguishable from a correct no-op unless it is asked")
                .hasSize(1);
        assertThat(unallocated.get(0).outMovementId()).isEqualTo(neverAllocated);
        assertThat(unallocated.get(0).quantity()).isEqualByComparingTo("3");
        assertThat(unallocated.get(0).allocated()).isEqualByComparingTo("0");
    }

    // ──────────────────────────────────────
    // decision 3, and the risk item this slice exists for: a reversal frees the right rows
    // ──────────────────────────────────────

    @Test
    @DisplayName("a reversal restores the quantity, the value, and exactly the rows the original consumed")
    void aReversalFreesExactlyTheRowsItConsumed() {
        var firstReceipt = receipt(locationA, "6", "10", "2026-11-01T10:00:00Z");
        var secondReceipt = receipt(locationA, "4", "20", "2026-11-01T11:00:00Z");
        var sale = outbound(locationA, "SALE", "10", "2026-11-01T12:00:00Z");
        allocate(sale);
        assertThat(position()).as("the store is empty").isEqualByComparingTo("0");
        assertThat(valuationTotal()).isEqualByComparingTo("0");

        var reversal = reversal(locationA, "SALE_REVERSAL", "10", sale, "2026-11-01T13:00:00Z");

        assertThat(position())
                .as("a reversal is a new row that names the row it reverses (decision 1), so it adds back the "
                        + "quantity the sale took")
                .isEqualByComparingTo("10");
        assertThat(valuationTotal())
                .as("decision 4 (:59-61): a reversal restores value, not only quantity — 60 + 80 was consumed, "
                        + "so 140 comes back")
                .isEqualByComparingTo("140");
        assertThat(balance(locationA)).isEqualByComparingTo("10");
        assertThat(allocationsOf(reversal))
                .as("and the reversal consumed nothing itself: it names a row, it does not allocate")
                .isEmpty();

        var resale = outbound(locationA, "SALE", "6", "2026-11-01T14:00:00Z");
        allocate(resale);

        var resaleAllocations = allocationsOf(resale);
        assertThat(resaleAllocations)
                .as("ADR-0001 decision 3 (:56-58): the reversal frees exactly the rows the original consumed, "
                        + "so this sale takes the 6 units of the first receipt again — a writer that re-derived "
                        + "a remainder from a reference id would find nothing free here")
                .hasSize(1);
        assertAllocation(resaleAllocations.get(0), firstReceipt, "6", "10");
        var originalAllocations = allocationsOf(sale);
        assertThat(originalAllocations)
                .as("and the original's own allocations are untouched: they are the record of what it consumed")
                .hasSize(2);
        assertAllocation(originalAllocations.get(0), firstReceipt, "6", "10");
        assertAllocation(originalAllocations.get(1), secondReceipt, "4", "20");
        assertThat(valuationTotal())
                .as("what is left is the second receipt, at its cost")
                .isEqualByComparingTo("80");
        assertThat(valuationAverage()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("a reversal must be the exact inverse of the row it names")
    void aReversalMustBeAnExactInverse() {
        var receipt = receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");

        var inTheOtherStore = UUID.randomUUID();
        assertThatThrownBy(() -> insertMovement(
                        inTheOtherStore,
                        otherStoreId,
                        otherLocation,
                        "RECEIPT_REVERSAL",
                        "10",
                        null,
                        receipt,
                        "2026-11-01T11:00:00Z",
                        SOURCE_RECEIPT))
                .as("the value and the pool arithmetic are per (store, variant), so a reversal in another store "
                        + "would subtract value from a store that never held it")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown))
                        .contains("a reversal stays in the store and variant it reverses"));
        assertThat(countMovement(inTheOtherStore))
                .as("the refusal stored nothing")
                .isZero();

        var partial = UUID.randomUUID();
        assertThatThrownBy(() -> insertMovement(
                        partial,
                        storeId,
                        locationA,
                        "RECEIPT_REVERSAL",
                        "4",
                        null,
                        receipt,
                        "2026-11-01T11:00:00Z",
                        SOURCE_RECEIPT))
                .as("a partial reversal would free a fraction of the rows the original consumed, which is the "
                        + "arithmetic decision 3 exists to remove; a partial correction is a new movement")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown))
                        .contains("a reversal moves exactly the quantity of the row it reverses"));
        assertThat(countMovement(partial)).as("the refusal stored nothing").isZero();

        var reversal = reversal(locationA, "RECEIPT_REVERSAL", "10", receipt, "2026-11-01T11:00:00Z");
        var reversalOfReversal = UUID.randomUUID();
        assertThatThrownBy(() -> insertMovement(
                        reversalOfReversal,
                        storeId,
                        locationA,
                        "RECEIPT_REVERSAL",
                        "10",
                        null,
                        reversal,
                        "2026-11-01T12:00:00Z",
                        SOURCE_RECEIPT))
                .as("a reversal of a reversal would grow the recorded value with a term the replay cannot "
                        + "attribute, so a chain stops at one reversal")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(rootCauseMessage(thrown)).contains("a reversal of a reversal"));
        assertThat(countMovement(reversalOfReversal))
                .as("the refusal stored nothing")
                .isZero();
        assertThat(countMovements())
                .as("only the receipt and its one reversal are stored")
                .isEqualTo(2);
        assertThat(position())
                .as("and reversing the receipt takes the balance back to where it started")
                .isEqualByComparingTo("0");
    }

    // ──────────────────────────────────────
    // the writer's idempotency, which is what the pair key is for
    // ──────────────────────────────────────

    @Test
    @DisplayName("allocating the same outbound row twice writes nothing the second time")
    void allocatingTheSameOutboundRowTwiceIsANoOp() {
        receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        var sale = outbound(locationA, "SALE", "4", "2026-11-01T11:00:00Z");
        assertThat(allocate(sale)).isEqualTo(1);

        assertThat(allocate(sale))
                .as("`uq_allocation_pair` makes the quantity of an (outbound, inbound) pair final, so a second "
                        + "call is a no-op instead of a second subtraction of value")
                .isZero();
        assertThat(allocationsOf(sale)).hasSize(1);
        assertThat(valuationTotal())
                .as("the value was subtracted once, not twice")
                .isEqualByComparingTo("60");
        assertThat(unallocatedOutboundMovements()).isEmpty();
    }

    // ──────────────────────────────────────
    // I2 — the check names what disagrees, and the rebuild is what repairs it
    // ──────────────────────────────────────

    @Test
    @DisplayName("I2 (FIFO): the check names the disagreeing rows and the rebuild repairs them")
    void theRebuildRepairsTheProjectionsUnderFifo() {
        assertTheRebuildRepairsTheProjections("FIFO");
    }

    @Test
    @DisplayName("I2 (AVERAGE): the check names the disagreeing rows and the rebuild repairs them")
    void theRebuildRepairsTheProjectionsUnderAverage() {
        assertTheRebuildRepairsTheProjections("AVERAGE");
    }

    /**
     * The {@code I2} shape, for one costing method: a ledger with several movements and more than two
     * allocations, corrupted by hand, named by the check, repaired by the rebuild, clean afterwards.
     */
    private void assertTheRebuildRepairsTheProjections(String costingMethod) {
        stateCostingMethod(costingMethod);
        receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        receipt(locationB, "10", "20", "2026-11-01T11:00:00Z");
        var firstSale = outbound(locationB, "SALE", "5", "2026-11-01T12:00:00Z");
        allocate(firstSale);
        var secondSale = outbound(locationA, "SALE", "5", "2026-11-01T13:00:00Z");
        allocate(secondSale);
        var adjustment = outbound(locationA, "ADJUSTMENT_OUT", "2", "2026-11-01T14:00:00Z");
        allocate(adjustment);

        assertThat(verifyStockProjections())
                .as("before the corruption the three projections equal the replay, for both costing methods")
                .isEmpty();

        corruptTheProjections();

        var disagreements = verifyStockProjections();
        assertThat(disagreements)
                .as("ADR-0001 decision 2 (:51-55): a verification query compares projection against replay and "
                        + "fails loudly — it names the rows that disagree, one per projection here")
                .extracting(Disagreement::projection)
                .contains("stock_balance", "stock_position", "stock_valuation");
        assertThat(disagreements)
                .as("and every row it returns carries both numbers — an operator sees what is stored beside "
                        + "what the ledger says — and each of them really is a disagreement in at least one of "
                        + "the numbers it compares")
                .allSatisfy(disagreement -> assertThat(sameValue(
                                        disagreement.projectedQuantity(), disagreement.replayedQuantity())
                                && sameValue(disagreement.projectedTotalValue(), disagreement.replayedTotalValue()))
                        .as("row: %s", disagreement)
                        .isFalse());

        rebuildTheProjections();

        assertThat(verifyStockProjections())
                .as("the rebuild is what makes the projection disposable: replaying the ledger repairs every "
                        + "row the corruption touched")
                .isEmpty();
        assertThat(balance(locationA))
                .as("and the repaired balance is the replay's number (10 received − 5 sold − 2 adjusted), not "
                        + "the corrupted one")
                .isEqualByComparingTo("3");
        assertThat(balance(locationB)).isEqualByComparingTo("5");
        assertThat(position()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("the rebuild can be scoped to one key of one store, and repairs every location of it")
    void theRebuildCanBeScopedToOneKey() {
        receipt(locationA, "10", "10", "2026-11-01T10:00:00Z");
        receipt(locationB, "4", "10", "2026-11-01T11:00:00Z");
        corruptTheProjections();

        rebuildTheProjectionsFor(locationA);

        assertThat(verifyStockProjections())
                .as("the scope of a key is (company, store, variant), so the repair covers every location of "
                        + "that store and nothing of another")
                .isEmpty();
        assertThat(balance(locationA)).isEqualByComparingTo("10");
        assertThat(balance(locationB)).isEqualByComparingTo("4");
        assertThat(position()).isEqualByComparingTo("14");
    }

    // ──────────────────────────────────────
    // helpers: the ledger's public interface
    // ──────────────────────────────────────

    /** One inbound row at {@code location}, with its cost and its instant stated. */
    private UUID receipt(UUID location, String quantity, String unitCost, String occurredAt) {
        return record(location, "RECEIPT", quantity, unitCost, null, occurredAt, SOURCE_RECEIPT);
    }

    /** One non-reversal outbound row: the shape that must be followed by {@link #allocate(UUID)}. */
    private UUID outbound(UUID location, String movementType, String quantity, String occurredAt) {
        var source = "ADJUSTMENT_OUT".equals(movementType) ? SOURCE_ADJUSTMENT : SOURCE_SALE;
        return record(location, movementType, quantity, null, null, occurredAt, source);
    }

    /** A reversal row: it names what it reverses and carries no cost of its own. */
    private UUID reversal(
            UUID location, String movementType, String quantity, UUID reversesMovementId, String occurredAt) {
        return record(location, movementType, quantity, null, reversesMovementId, occurredAt, SOURCE_SALE);
    }

    private UUID record(
            UUID location,
            String movementType,
            String quantity,
            String unitCost,
            UUID reversesMovementId,
            String occurredAt,
            String sourceType) {
        var id = UUID.randomUUID();
        insertMovement(
                id,
                storeOf(location),
                location,
                movementType,
                quantity,
                unitCost,
                reversesMovementId,
                occurredAt,
                sourceType);
        return id;
    }

    /** The one insert path, also called directly by the cases that state the row they want refused. */
    private void insertMovement(
            UUID id,
            UUID store,
            UUID location,
            String movementType,
            String quantity,
            String unitCost,
            UUID reversesMovementId,
            String occurredAt,
            String sourceType) {
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        INSERT_MOVEMENT,
                        id,
                        companyId,
                        store,
                        location,
                        variantId,
                        movementType,
                        quantity,
                        unitCost,
                        unitCost == null ? null : TenantFixtures.SEED_CURRENCY,
                        occurredAt,
                        sourceType,
                        UUID.randomUUID(),
                        reversesMovementId));
    }

    /** The allocation writer, called the way T5 will call it. */
    private int allocate(UUID outMovementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject("SELECT allocate_stock_movement(?)", Integer.class, outMovementId));
    }

    /** The full-replay rebuild, scoped to this suite's company. */
    private void rebuildTheProjections() {
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject("SELECT rebuild_stock_projections(?)", Long.class, companyId));
    }

    /** The rebuild for the one key a location belongs to, which is the operator's narrow repair. */
    private void rebuildTheProjectionsFor(UUID location) {
        var store = storeOf(location);
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT rebuild_stock_projections(?, ?, ?)", Long.class, companyId, store, variantId));
    }

    /** The check every operator and this suite share: the rows where projection and replay disagree. */
    private List<Disagreement> verifyStockProjections() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT projection, projected_quantity, replayed_quantity, projected_total_value, "
                                + "replayed_total_value FROM verify_stock_projections(?) ORDER BY projection",
                        (rs, rowNum) -> new Disagreement(
                                rs.getString("projection"),
                                rs.getBigDecimal("projected_quantity"),
                                rs.getBigDecimal("replayed_quantity"),
                                rs.getBigDecimal("projected_total_value"),
                                rs.getBigDecimal("replayed_total_value")),
                        companyId));
    }

    /**
     * The {@code I6} query: one row per outbound movement whose recorded consumption does not sum to its
     * quantity.
     */
    private List<Unallocated> unallocatedOutboundMovements() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        "SELECT out_movement_id, quantity, allocated FROM unallocated_stock_movements(?) "
                                + "ORDER BY out_movement_id",
                        (rs, rowNum) -> new Unallocated(
                                rs.getObject("out_movement_id", UUID.class),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("allocated")),
                        companyId));
    }

    /** The allocations of one outbound row, oldest inbound row first, the way a report would read them. */
    private List<Allocation> allocationsOf(UUID outMovementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.query(
                        """
                        SELECT a.in_movement_id, a.quantity, a.unit_cost
                          FROM stock_allocation a
                          JOIN stock_movement m ON m.id = a.in_movement_id
                         WHERE a.out_movement_id = ?
                         ORDER BY m.occurred_at, m.id
                        """,
                        (rs, rowNum) -> new Allocation(
                                rs.getObject("in_movement_id", UUID.class),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("unit_cost")),
                        outMovementId));
    }

    private BigDecimal balance(UUID location) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        location,
                        variantId));
    }

    private BigDecimal position() {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variantId));
    }

    private BigDecimal valuationQuantity() {
        return valuationColumn("quantity");
    }

    private BigDecimal valuationTotal() {
        return valuationColumn("total_value");
    }

    private BigDecimal valuationAverage() {
        return valuationColumn("avg_unit_cost");
    }

    private BigDecimal valuationColumn(String column) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT " + column
                                + " FROM stock_valuation WHERE company_store_id = ? AND product_variant_id = ?",
                        BigDecimal.class,
                        storeId,
                        variantId));
    }

    /**
     * Writes the wrong number into all three projections, which is what a lost trigger leaves behind.
     *
     * <p>This is the only place in the suite that authors a projection row, and it exists to be caught: the
     * {@code I2} cases assert that the check names it before the rebuild erases it.</p>
     */
    private void corruptTheProjections() {
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update(
                    "UPDATE stock_balance SET quantity = quantity + 7 WHERE product_variant_id = ?", variantId);
            jdbcTemplate.update(
                    "UPDATE stock_position SET quantity = quantity + 7 WHERE product_variant_id = ?", variantId);
            jdbcTemplate.update(
                    "UPDATE stock_valuation SET total_value = total_value + 7 WHERE product_variant_id = ?", variantId);
        });
    }

    private void stateCostingMethod(String costingMethod) {
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE companies SET costing_method = ? WHERE id = ?", costingMethod, companyId));
    }

    /** The store a location belongs to, read from the tree the movement's own key is built from. */
    private UUID storeOf(UUID location) {
        return jdbcTemplate.queryForObject(
                "SELECT company_store_id FROM store_locations WHERE id = ?", UUID.class, location);
    }

    private int countMovements() {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movement", Integer.class));
    }

    private int countMovement(UUID movementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE id = ?", Integer.class, movementId));
    }

    private int countAllocationsOfCompany() {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_allocation", Integer.class));
    }

    /**
     * Deletes the rows this suite wrote, with the append-only triggers lifted for exactly the two statements
     * that need it and re-enabled in the {@code finally}.
     *
     * <p>The projections go too, in the same pass: they are the ledger's derived rows, so a suite that
     * cleaned only the ledger would leave a projection that no longer replays — the exact state {@code I2}
     * exists to report.</p>
     */
    private void deleteTheLedger() {
        if (companyId == null) {
            return;
        }
        UNRESTRICTED.execute("ALTER TABLE stock_allocation DISABLE TRIGGER USER");
        UNRESTRICTED.execute("ALTER TABLE stock_movement DISABLE TRIGGER USER");
        try {
            UNRESTRICTED.update("DELETE FROM stock_allocation WHERE company_id = ?", companyId);
            UNRESTRICTED.update("DELETE FROM stock_movement WHERE company_id = ?", companyId);
        } finally {
            UNRESTRICTED.execute("ALTER TABLE stock_movement ENABLE TRIGGER USER");
            UNRESTRICTED.execute("ALTER TABLE stock_allocation ENABLE TRIGGER USER");
        }
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update("DELETE FROM stock_balance WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM stock_position WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM stock_valuation WHERE company_id = ?", companyId);
        });
    }

    /** The driver's own sentence, at the bottom of the Spring exception chain. */
    private static String rootCauseMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * Asserts one recorded consumption, comparing the numbers <b>by value</b> rather than by scale: the
     * columns are {@code NUMERIC(19,4)} and the driver hands back {@code 10.0000}, which is not
     * {@code equals} to {@code 10} even though it is the same quantity.
     */
    private static void assertAllocation(Allocation allocation, UUID inMovementId, String quantity, String unitCost) {
        assertThat(allocation.inMovementId()).isEqualTo(inMovementId);
        assertThat(allocation.quantity()).isEqualByComparingTo(quantity);
        assertThat(allocation.unitCost()).isEqualByComparingTo(unitCost);
    }

    private record InsertedRow(UUID companyId, UUID id) {}

    private void deleteRow(InsertedRow row, String sql) {
        TenantFixtures.inTenant(row.companyId(), () -> jdbcTemplate.update(sql, row.id()));
    }

    /** One recorded consumption: how much of which inbound row, and at what cost. */
    private record Allocation(UUID inMovementId, BigDecimal quantity, BigDecimal unitCost) {}

    /** One {@code I6} violation: an outbound row and what its allocations sum to. */
    private record Unallocated(UUID outMovementId, BigDecimal quantity, BigDecimal allocated) {}

    /** One row where a projection and the replay disagree, with both numbers as they were read. */
    private record Disagreement(
            String projection,
            BigDecimal projectedQuantity,
            BigDecimal replayedQuantity,
            BigDecimal projectedTotalValue,
            BigDecimal replayedTotalValue) {}

    /** Compares two numbers by value, treating two {@code null}s as equal — the columns are nullable. */
    private static boolean sameValue(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    // ──────────────────────────────────────
    // helpers: the tree every case hangs from
    // ──────────────────────────────────────

    private UUID insertCompany() {
        var id = TenantFixtures.insertCompany(
                jdbcTemplate, COMPANY_KEY, "Ledger Engine Company", COMPANY_RFC, TenantFixtures.SEED_CURRENCY);
        insertedCompanies.add(new InsertedRow(id, id));
        return id;
    }

    private UUID insertCompanyCountry() {
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

    private UUID insertRegion() {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                        INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name,
                                                     enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, companyCountryId, companyId, suffix, "Engine region " + suffix));
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
                        """, id, regionId, companyId, suffix, "Engine zone " + suffix));
        insertedCompanyZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertStore(UUID companyZone) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                        VALUES (?, ?, ?, ?, true)
                        """, id, companyZone, companyId, "Engine store " + suffix));
        insertedStores.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertArea(UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)
                        VALUES (?, ?, ?, ?, true)
                        """, id, store, suffix, "Engine area " + suffix));
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
                        """, id, areaId, store, suffix, "Engine store zone " + suffix));
        insertedStoreZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertLocation(UUID storeZoneId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                        INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code,
                                                     location_name, enabled)
                        VALUES (?, ?, ?, ?, ?, true)
                        """, id, storeZoneId, store, suffix, "Engine location " + suffix));
        insertedLocations.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertVariant() {
        var productId = UUID.randomUUID();
        var sku = "ENGINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                        productId,
                        sku,
                        "Engine product " + sku,
                        companyId));
        insertedProducts.add(new InsertedRow(companyId, productId));

        var id = UUID.randomUUID();
        var measureUnitId =
                jdbcTemplate.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                        INSERT INTO product_variants (id, company_id, product_id, variant_name, measure_unit_id)
                        VALUES (?, ?, ?, 'unica', ?)
                        """, id, companyId, productId, measureUnitId));
        insertedVariants.add(new InsertedRow(companyId, id));
        return id;
    }
}
