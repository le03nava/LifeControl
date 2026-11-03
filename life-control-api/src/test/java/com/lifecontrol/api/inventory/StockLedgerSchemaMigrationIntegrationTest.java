package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V60__stock_ledger_schema.sql}, the schema half of ADR-0001,
 * against real PostgreSQL with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the
 * migration landed exactly as the ADR declares it.
 *
 * <p><b>What this suite covers, and what it deliberately does not.</b> The ADR's invariants are eight
 * ({@code docs/adr/0001-inventory-ledger-single-source.md:180-187}), and only five of them are provable
 * against the schema alone: {@code I1} (no {@code UPDATE} or {@code DELETE} path into the ledger),
 * {@code I3} (a movement's store is its location's store), {@code I4} (at most one reversal per movement),
 * {@code I5} (one movement per source document line and direction) and {@code I7} (every inbound row names
 * a cost and a currency). {@code I2} and {@code I6} compare a projection against a replay and an allocation
 * against its outbound row, so they need the projection trigger, the rebuild function and the allocation
 * writer — T3/T4 of {@code odd/tasks/inventory-ledger.md}, not this slice. {@code I8} is the deduction
 * path refusing before it writes, which is also T3's. None of the three is faked here: a test that wrote
 * {@code stock_balance} by hand would prove a row can be inserted, not that the projection is derived.</p>
 *
 * <p><b>Two roles, and why.</b> Each case runs as <b>two</b> credentials, and the split is the rule rather
 * than a convenience. The pool logs in as {@code lifecontrol_app} (record T6b-4), so it is the role
 * ADR-0005's policies apply to — that is the credential this suite proves the ledger <i>reaches</i>:
 * {@code INSERT} and {@code SELECT} are granted ({@code V60}), and {@code UPDATE}/{@code DELETE} are not.
 * A privilege check happens before a trigger can fire, so the application role can never observe the
 * {@code BEFORE UPDATE OR DELETE} trigger at all — it is refused earlier. The trigger's own refusal is
 * therefore observed through {@link #CONTAINER_SUPERUSER}, the container's {@code lifecontrol} login, which
 * is a SUPERUSER: {@code AbstractPostgresIntegrationTest#containerSuperuserPool()} exists for exactly this
 * class of fact (a behaviour that is invisible to the application role by construction), and the
 * superuser is not the table's owner in any way this suite relies on — the trigger is a user trigger and
 * fires for every role that reaches it.</p>
 *
 * <p><b>Why the cleanup has to escape the very rule it tests, and how.</b> The two ledger tables are
 * append-only, so this suite cannot {@code DELETE} its own movements with the rule in place — the trigger
 * refuses the cleanup exactly as it refuses a caller. The {@code @AfterEach} therefore goes through
 * {@link com.lifecontrol.api.support.AbstractPostgresIntegrationTest#deleteStockLedger}, which suppresses
 * the trigger for <b>one session and one transaction</b> with
 * {@code SET LOCAL session_replication_role = replica} instead of changing the table's catalog entry: an
 * {@code ALTER TABLE … DISABLE TRIGGER USER} would switch the rule off for every other suite sharing this
 * container for as long as it lasted, and this suite is the one that asserts the rule holds.</p>
 *
 * <p><b>One nested class is not {@code V60}'s: {@code V63}.</b> {@code uq_movement_source} is the one index this
 * file declares that a later migration has to re-create, and {@link SourceIndexWidenedWithTheLocationTests}
 * lives here rather than in a suite of its own because the tree a movement needs — company, region, zone,
 * store, area, store zone, location, variant — is the fixture below. It asserts the two halves of {@code V63}:
 * one source line spanning two locations now books one movement per location, and the same source, direction
 * and location is still refused.</p>
 *
 * <p>Written the way its sibling {@code StoreLocationTreeStoreScopeSchemaMigrationIntegrationTest} is: one
 * case per rule, every refusal asserted <b>by the name of the constraint or trigger that refused it</b> —
 * which is what makes the assertion about the rule and not about "some error" — and every refusal also
 * asserting that nothing was stored.</p>
 */
@SpringBootTest
@DisplayName("Stock ledger schema integration tests (V60)")
class StockLedgerSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "LEDGER-A";
    private static final String COMPANY_RFC = "LED010101AB1";

    /**
     * The movement types {@code ck_movement_inbound_cost} requires a cost for
     * ({@code docs/adr/0001-inventory-ledger-single-source.md:110-112}).
     */
    private static final Set<String> INBOUND_TYPES = Set.of("RECEIPT", "ADJUSTMENT_IN", "TRANSFER_IN");

    /**
     * One inbound movement: the shape every row that adds quantity has to have, with its cost stated.
     */
    private static final String INSERT_INBOUND = """
            INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id, product_variant_id,
                                        movement_type, quantity, unit_cost, currency, source_type, source_id,
                                        reverses_movement_id, reason)
            VALUES (?, ?, ?, ?, ?, ?, 1.0000, 10.0000, 'MXN', 'PURCHASE_ORDER_LINE', ?, ?,
                    'ledger schema fixture')
            """;

    /**
     * The same insert for a row that removes quantity: the ADR gives an outbound row no cost of its own
     * ({@code :196-198}), the cost lives in {@code stock_allocation.unit_cost}.
     */
    private static final String INSERT_OUTBOUND = """
            INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id, product_variant_id,
                                        movement_type, quantity, source_type, source_id, reverses_movement_id,
                                        reason)
            VALUES (?, ?, ?, ?, ?, ?, 1.0000, 'PURCHASE_ORDER_LINE', ?, ?, 'ledger schema fixture')
            """;

    /**
     * The container's own login, which is a SUPERUSER — the only credential here that reaches the
     * append-only trigger, because {@code lifecontrol_app} is denied before the trigger can fire.
     */
    private static final DataSource CONTAINER_SUPERUSER = containerSuperuserPool();

    private static final JdbcTemplate UNRESTRICTED = new JdbcTemplate(CONTAINER_SUPERUSER);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID countryId;
    private UUID companyCountryId;

    /** The store a movement names. */
    private UUID storeId;

    /** A second store of the same company: the refusal of {@code I3} has to be about the store, not the tenant. */
    private UUID otherStoreId;

    private UUID locationId;
    private UUID otherLocationId;
    private UUID variantId;

    private final List<InsertedRow> insertedMovements = new ArrayList<>();
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
        // Seeded reference data, read rather than invented: no company country exists without one.
        countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        companyCountryId = insertCompanyCountry();
        var regionId = insertRegion();
        var companyZoneId = insertCompanyZone(regionId);

        storeId = insertStore(companyZoneId);
        otherStoreId = insertStore(companyZoneId);
        var areaId = insertArea(storeId);
        var otherAreaId = insertArea(otherStoreId);
        var storeZoneId = insertZone(areaId, storeId);
        var otherStoreZoneId = insertZone(otherAreaId, otherStoreId);
        locationId = insertLocation(storeZoneId, storeId);
        otherLocationId = insertLocation(otherStoreZoneId, otherStoreId);

        variantId = insertVariant();
    }

    @AfterEach
    void removeInsertedRows() {
        deleteMovements();
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
    // I1 — stock_movement has no UPDATE or DELETE path at all
    // ──────────────────────────────────────

    @Test
    @DisplayName("I1: a direct UPDATE of a movement raises, and the stored row is unchanged")
    void aDirectUpdateIsRefusedByTheAppendOnlyTrigger() {
        var movementId = insertMovement("RECEIPT", null, UUID.randomUUID());

        assertThatThrownBy(() -> UNRESTRICTED.update(
                        "UPDATE stock_movement SET reason = 'rewritten by hand' WHERE id = ?", movementId))
                .as("ADR-0001 decision 1 (:48-50): the row is append-only, and the BEFORE UPDATE trigger is "
                        + "the half of that rule which holds for every role that can reach the statement — "
                        + "the application role's refusal comes earlier and is the next case")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("stock_movement is append-only")
                .hasMessageContaining("UPDATE");

        assertThat(storedReason(movementId))
                .as("the refusal stored nothing: the row still carries what it was written with")
                .isEqualTo("ledger schema fixture");
        assertThat(countMovement(movementId)).as("and it is still there, once").isEqualTo(1);
    }

    @Test
    @DisplayName("I1: a direct DELETE of a movement raises, and the stored row survives")
    void aDirectDeleteIsRefusedByTheAppendOnlyTrigger() {
        var movementId = insertMovement("RECEIPT", null, UUID.randomUUID());

        assertThatThrownBy(() -> UNRESTRICTED.update("DELETE FROM stock_movement WHERE id = ?", movementId))
                .as("the same rule from the other direction: deleting the only record of a quantity change "
                        + "would leave a balance no ledger explains")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("stock_movement is append-only")
                .hasMessageContaining("DELETE");

        assertThat(countMovement(movementId))
                .as("the refusal stored nothing: the row is still exactly one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("I1: the application role holds INSERT and SELECT on both ledger tables and nothing else")
    void theApplicationRoleIsDeniedUpdateAndDeleteOnBothLedgerTables() {
        var movementId = insertMovement("RECEIPT", null, UUID.randomUUID());

        assertThat(List.of(
                        holds("stock_movement", "SELECT"),
                        holds("stock_movement", "INSERT"),
                        holds("stock_movement", "UPDATE"),
                        holds("stock_movement", "DELETE")))
                .as("ADR-0001 states the grant set verbatim (:173-174): the application role gets "
                        + "`INSERT, SELECT` on `stock_movement` and `stock_allocation` — so the UPDATE and "
                        + "DELETE that `V52`'s ALTER DEFAULT PRIVILEGES (:196-197) would have granted a new "
                        + "table are revoked, and the revocation is the second, independent half of the rule")
                .containsExactly(true, true, false, false);
        assertThat(List.of(
                        holds("stock_allocation", "SELECT"),
                        holds("stock_allocation", "INSERT"),
                        holds("stock_allocation", "UPDATE"),
                        holds("stock_allocation", "DELETE")))
                .as("the allocation is what I6 sums against the outbound row, so a DELETE there would look "
                        + "exactly like an allocation writer that never ran")
                .containsExactly(true, true, false, false);

        assertThatThrownBy(() ->
                        jdbcTemplate.update("UPDATE stock_movement SET reason = 'rewritten' WHERE id = ?", movementId))
                .as("and the grant is not a document: the role the pool logs in as is refused by PostgreSQL "
                        + "itself, before any trigger is reached")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown ->
                        assertThat(rootCauseMessage(thrown)).contains("permission denied for table stock_movement"));
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM stock_movement WHERE id = ?", movementId))
                .as("DELETE is denied for the same reason and by the same grant set")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown ->
                        assertThat(rootCauseMessage(thrown)).contains("permission denied for table stock_movement"));

        assertThat(storedReason(movementId)).as("both denials stored nothing").isEqualTo("ledger schema fixture");
        assertThat(countMovement(movementId)).as("and neither removed the row").isEqualTo(1);
    }

    // ──────────────────────────────────────
    // I3 — a movement's store is its location's store
    // ──────────────────────────────────────

    @Test
    @DisplayName("I3: a movement naming another store's location is refused by fk_movement_location")
    void aMovementNamingAnotherStoresLocationIsRefused() {
        // The control: the pair a movement is supposed to hold is accepted, so the refusal below is about
        // the pair and not about the movement being unwritable at all.
        var acceptedId = insertMovement("RECEIPT", null, UUID.randomUUID());
        assertThat(countMovement(acceptedId))
                .as("a movement naming its own store and a location of that store is stored")
                .isEqualTo(1);

        var refusedId = UUID.randomUUID();

        assertThatThrownBy(
                        () -> insertMovement(refusedId, storeId, otherLocationId, "RECEIPT", UUID.randomUUID(), null))
                .as("ADR invariant I3 (:182): the store and the location are one pair, and the composite key "
                        + "is what makes a disagreeing pair unwritable instead of a check at every call site. "
                        + "Both stores belong to the same company on purpose, so the refusal is about the "
                        + "store and not about the tenant")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_movement_location");

        assertThat(countMovement(refusedId)).as("the refusal stored nothing").isZero();
        assertThat(countMovements())
                .as("and the ledger holds only the movement of the control")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // I4 — at most one reversal per movement
    // ──────────────────────────────────────

    @Test
    @DisplayName("I4: a second reversal of the same movement is refused by uq_movement_one_reversal")
    void aSecondReversalOfTheSameMovementIsRefused() {
        var receiptId = insertMovement("RECEIPT", null, UUID.randomUUID());
        var firstReversalId = insertMovement("RECEIPT_REVERSAL", receiptId, UUID.randomUUID());
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> insertMovement(
                        refusedId, storeId, locationId, "RECEIPT_REVERSAL", UUID.randomUUID(), receiptId))
                .as("ADR invariant I4 (:183): the partial unique index is what keeps a reversal a single "
                        + "fact — the reversal names the row it reverses (:49-50), so two of them would "
                        + "restore the same quantity twice")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_movement_one_reversal");

        assertThat(countMovement(refusedId)).as("the refusal stored nothing").isZero();
        assertThat(countReversalsOf(receiptId))
                .as("the receipt carries exactly the one reversal it already had")
                .isEqualTo(1);
        assertThat(countMovement(firstReversalId))
                .as("and that first reversal is untouched")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // I5 — a source document line books one movement per direction
    // ──────────────────────────────────────

    @Test
    @DisplayName("I5: a retried source line is refused by uq_movement_source")
    void aRetriedSourceLineIsRefused() {
        var sourceId = UUID.randomUUID();
        var bookedId = insertMovement("RECEIPT", null, sourceId);
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> insertMovement(refusedId, storeId, locationId, "RECEIPT", sourceId, null))
                .as("ADR invariant I5 (:184): the partial unique index keys the source line and the "
                        + "direction, so a retried request cannot double-book the same receipt")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_movement_source");

        assertThat(countMovement(refusedId)).as("the refusal stored nothing").isZero();
        assertThat(countMovementsOfSource(sourceId))
                .as("the source line holds exactly the one movement it booked")
                .isEqualTo(1);
        assertThat(countMovement(bookedId))
                .as("and that movement is still the one that was booked")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // I7 — every inbound row names a cost and a currency
    // ──────────────────────────────────────

    @Test
    @DisplayName("I7: an inbound movement without a cost or without a currency is refused by ck_movement_inbound_cost")
    void anInboundMovementWithoutCostIsRefused() {
        var before = countMovements();
        var withoutCostId = UUID.randomUUID();
        var withoutCurrencyId = UUID.randomUUID();

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.update(
                                """
                        INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id,
                                                    product_variant_id, movement_type, quantity, unit_cost,
                                                    currency, source_type, source_id)
                        VALUES (?, ?, ?, ?, ?, 'RECEIPT', 1.0000, NULL, 'MXN', 'PURCHASE_ORDER_LINE', ?)
                        """, withoutCostId, companyId, storeId, locationId, variantId, UUID.randomUUID())))
                .as("ADR invariant I7 (:186) and `ck_movement_inbound_cost` (:109-112): cost travels inward, "
                        + "so a row that adds quantity states what it cost")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_movement_inbound_cost");
        assertThat(countMovement(withoutCostId))
                .as("the refusal stored nothing")
                .isZero();
        assertThat(countMovements()).as("and the ledger is exactly what it was").isEqualTo(before);

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.update(
                                """
                        INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id,
                                                    product_variant_id, movement_type, quantity, unit_cost,
                                                    currency, source_type, source_id)
                        VALUES (?, ?, ?, ?, ?, 'ADJUSTMENT_IN', 1.0000, 10.0000, NULL, 'PURCHASE_ORDER_LINE', ?)
                        """, withoutCurrencyId, companyId, storeId, locationId, variantId, UUID.randomUUID())))
                .as("the other half of the same rule: a cost with no unit is not money, which is the defect "
                        + "ADR correction 2 (:283-287) fixed for `stock_valuation`")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_movement_inbound_cost");
        assertThat(countMovement(withoutCurrencyId))
                .as("the refusal stored nothing")
                .isZero();
        assertThat(countMovements())
                .as("and the ledger is still exactly what it was")
                .isEqualTo(before);
    }

    // ──────────────────────────────────────
    // V63 — uq_movement_source gains the location, so one line can span two of them
    // ──────────────────────────────────────

    /**
     * {@code V63__stock_ledger_movement_source_location.sql} re-creates the one index {@code V60} declares that
     * a document line can outgrow: {@code uq_movement_source} is
     * {@code (source_type, source_id, movement_type) WHERE reverses_movement_id IS NULL}, and a line that draws
     * stock from two locations books one movement per location under one line's source — so the second row it
     * needs was refused.
     *
     * <p>Measured, not supposed: {@code InventoryService.applySaleDeduction} writes one legacy {@code SALE} row
     * per location it drains ({@code InventoryService:474-500}), a {@code stock_movement} names exactly one
     * {@code store_location_id} ({@code V60:120}), and {@code StockLedgerWriter}'s javadoc records the collision
     * as the first of the three shapes the index cannot satisfy. The legacy table had no such rule —
     * {@code inventory_movements} ({@code V10:30-38}) carries no unique key over its reference at all.</p>
     *
     * <p>Both cases are about the index and about nothing else, so both insert rows directly: a writer that
     * allocated or reversed would make the case's outcome depend on {@code V61} instead of on the key.</p>
     */
    @Nested
    @DisplayName("V63: uq_movement_source keys the location")
    class SourceIndexWidenedWithTheLocationTests {

        @Test
        @DisplayName("one source line spanning two locations books one movement per location")
        void oneSourceLineSpanningTwoLocationsBooksOneMovementPerLocation() {
            var secondLocationId = insertLocation(insertZone(insertArea(storeId), storeId), storeId);
            var sourceId = UUID.randomUUID();

            var atFirstLocation = bookAt(locationId, sourceId, "RECEIPT");
            var atSecondLocation = bookAt(secondLocationId, sourceId, "RECEIPT");

            assertThat(List.of(countMovement(atFirstLocation), countMovement(atSecondLocation)))
                    .as("ADR-0001 decision 1 (:48-50) makes one movement name one location, so a line that draws "
                            + "stock from two locations is two facts. The widened key is what lets them carry the "
                            + "same source: before V63 the second insert was refused by uq_movement_source, which "
                            + "is the whole reason this file exists")
                    .containsExactly(1, 1);
            assertThat(locationOf(atSecondLocation))
                    .as("the row that used to collide is the one at the second location")
                    .isEqualTo(secondLocationId);
            assertThat(countMovementsOfSource(sourceId))
                    .as("and the source line holds both of them, one per location")
                    .isEqualTo(2);
            assertThat(balanceAt(secondLocationId))
                    .as("the projection followed each row to its own location: stock_balance is keyed by "
                            + "(store_location_id, product_variant_id) and the trigger wrote both")
                    .isEqualByComparingTo("1.0000");
            assertThat(balanceAt(locationId)).isEqualByComparingTo("1.0000");
            assertThat(positionQuantity())
                    .as("and the store's position is the sum of what the two rows moved")
                    .isEqualByComparingTo("2.0000");
        }

        @Test
        @DisplayName("a retried booking of the same source, direction and location is still refused")
        void aRetryOfTheSameSourceDirectionAndLocationIsStillRefused() {
            var secondLocationId = insertLocation(insertZone(insertArea(storeId), storeId), storeId);
            var sourceId = UUID.randomUUID();
            bookAt(locationId, sourceId, "RECEIPT");
            bookAt(secondLocationId, sourceId, "RECEIPT");

            var retriedId = UUID.randomUUID();

            assertThatThrownBy(() -> insertMovement(retriedId, storeId, locationId, "RECEIPT", sourceId, null))
                    .as("I5 (:184) is one movement per source and direction <b>per location</b>, and the part "
                            + "that survives V63 is what makes a retried request a no-op instead of a second "
                            + "deduction: the location is in the key, so a retry at the location that already "
                            + "booked is refused")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_movement_source");

            assertThat(countMovement(retriedId))
                    .as("the refusal stored nothing")
                    .isZero();
            assertThat(countMovementsOfSource(sourceId))
                    .as("and the source line still holds exactly the two movements, one per location")
                    .isEqualTo(2);
            assertThat(balanceAt(locationId))
                    .as("the refused row moved no balance either: the trigger never saw it")
                    .isEqualByComparingTo("1.0000");
            assertThat(positionQuantity()).isEqualByComparingTo("2.0000");
        }

        /** One movement at {@code location} under {@code sourceId}, registered for cleanup. */
        private UUID bookAt(UUID location, UUID sourceId, String movementType) {
            var id = UUID.randomUUID();
            insertMovement(id, storeId, location, movementType, sourceId, null);
            insertedMovements.add(new InsertedRow(companyId, id));
            return id;
        }

        private UUID locationOf(UUID movementId) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT store_location_id FROM stock_movement WHERE id = ?", UUID.class, movementId));
        }

        /** The ledger's per-location projection, which is what a second row at a new location creates. */
        private BigDecimal balanceAt(UUID location) {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT quantity FROM stock_balance WHERE store_location_id = ? AND product_variant_id = ?",
                            BigDecimal.class,
                            location,
                            variantId));
        }

        private BigDecimal positionQuantity() {
            return TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT quantity FROM stock_position WHERE company_store_id = ? AND product_variant_id = ?",
                            BigDecimal.class,
                            storeId,
                            variantId));
        }
    }

    // ──────────────────────────────────────
    // V73 — uq_movement_source gains the write, so one line can book twice in one location
    // ──────────────────────────────────────

    /**
     * {@code V73__stock_ledger_per_write_identity.sql} widens the same index {@code V63} widened, and the file
     * checks itself. This class exists because a file that checks itself proves the check ran, not that an
     * independent reader agrees with it: the expected shape is read back out of the catalog here, by column name
     * and in order, so a key that merely *contains* the five names, or holds a sixth, or lost its predicate, is a
     * different key and fails.
     */
    @Nested
    @DisplayName("V73: uq_movement_source keys the write")
    class SourceIndexKeyedOnTheWriteTests {

        @Test
        @DisplayName("the key is unique on exactly the five columns, still partial on reverses_movement_id")
        void theKeyIsUniqueOnExactlyTheFiveColumnsStillPartialOnReversesMovementId() {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT a.attname FROM pg_index i
                      CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                     WHERE i.indexrelid = 'uq_movement_source'::regclass ORDER BY k.ord""", String.class))
                    .as("I5 (:184) is one movement per source, direction and location and write. Without the fifth "
                            + "column a line that books twice in one location is refused again; a sixth or a "
                            + "reordered column leaves the key refusing something other than the write")
                    .containsExactly("source_type", "source_id", "movement_type", "store_location_id", "write_seq");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT indrelid = 'stock_movement'::regclass AND indisunique
                      FROM pg_index WHERE indexrelid = 'uq_movement_source'::regclass""", Boolean.class))
                    .as("a key on another table keys nothing this ledger writes, and a non-unique index refuses "
                            + "nothing at all")
                    .isTrue();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT regexp_replace(lower(pg_get_expr(indpred, indrelid)), '[[:space:]()]', '', 'g')
                      FROM pg_index WHERE indexrelid = 'uq_movement_source'::regclass""", String.class))
                    .as("the predicate is what keeps a reversal from being counted against its own source: a lost "
                            + "predicate makes every reversal collide with the movement it corrects")
                    .isEqualTo("reverses_movement_idisnull");
        }
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    /**
     * Records one movement of {@code movementType} and registers it for cleanup.
     *
     * <p>The cost is stated for the three inbound types and left out for the outbound ones, because the
     * ADR states both: a row that adds quantity says what it cost ({@code :109-112}) and an outbound row
     * carries no cost of its own ({@code :196-198}).</p>
     */
    private UUID insertMovement(String movementType, UUID reversesMovementId, UUID sourceId) {
        var id = UUID.randomUUID();
        insertMovement(id, storeId, locationId, movementType, sourceId, reversesMovementId);
        insertedMovements.add(new InsertedRow(companyId, id));
        return id;
    }

    /** The one insert path, without registering the row: a case about a refusal calls it too. */
    private void insertMovement(
            UUID id, UUID store, UUID location, String movementType, UUID sourceId, UUID reversesMovementId) {
        if (INBOUND_TYPES.contains(movementType)) {
            TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.update(
                            INSERT_INBOUND,
                            id,
                            companyId,
                            store,
                            location,
                            variantId,
                            movementType,
                            sourceId,
                            reversesMovementId));
        } else {
            TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.update(
                            INSERT_OUTBOUND,
                            id,
                            companyId,
                            store,
                            location,
                            variantId,
                            movementType,
                            sourceId,
                            reversesMovementId));
        }
    }

    /**
     * Deletes the movements and projections this suite wrote, through the shared helper — the cleanup cannot
     * delete from an append-only table any other way, and this suite proves that rule rather than working
     * around it. The helper's session-scoped suppression (no catalog change) is stated where it lives.
     *
     * <p>It names this suite's company, so a row another suite writes is never touched, and it clears the
     * projections too: the trigger derives them from the movements, so a suite that cleaned only the ledger
     * would leave a projection that no longer replays.</p>
     */
    private void deleteMovements() {
        if (insertedMovements.isEmpty()) {
            return;
        }
        deleteStockLedger(jdbcTemplate, companyId);
        insertedMovements.clear();
    }

    /** Whether the application role holds {@code privilege} on {@code table}, read from PostgreSQL. */
    private boolean holds(String table, String privilege) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('lifecontrol_app', ?::regclass, ?)", Boolean.class, table, privilege));
    }

    /**
     * The driver's own sentence, at the bottom of the Spring exception chain.
     *
     * <p>Needed wherever the assertion is about what PostgreSQL said rather than about the wrapper: Spring's
     * {@code BadSqlGrammarException} message is its own summary of the statement, and the refusal it carries
     * is in the cause.</p>
     */
    private static String rootCauseMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * A row this class inserted, with the tenant whose policy guards it.
     *
     * <p>The tenant travels with the id because the cleanup has to state it: an unscoped {@code DELETE} on
     * an armed table matches no row, so a fixture that forgot it would leave every row behind for the next
     * class of the shared container to see.</p>
     */
    private record InsertedRow(UUID companyId, UUID id) {}

    /** Deletes one row inside the tenant that guards it. */
    private void deleteRow(InsertedRow row, String sql) {
        TenantFixtures.inTenant(row.companyId(), () -> jdbcTemplate.update(sql, row.id()));
    }

    /** The stored reason, read through the tenant that owns the row. */
    private String storedReason(UUID movementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT reason FROM stock_movement WHERE id = ?", String.class, movementId));
    }

    /** One movement, counted so a refusal can prove it stored nothing. */
    private int countMovement(UUID movementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE id = ?", Integer.class, movementId));
    }

    /** Every movement of this suite's tenant. */
    private int countMovements() {
        return TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_movement", Integer.class));
    }

    /** The reversals of one movement, counted. */
    private int countReversalsOf(UUID movementId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE reverses_movement_id = ?",
                        Integer.class,
                        movementId));
    }

    /** The movements one source document line booked. */
    private int countMovementsOfSource(UUID sourceId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stock_movement WHERE source_id = ?", Integer.class, sourceId));
    }

    private UUID insertCompany() {
        var id = TenantFixtures.insertCompany(
                jdbcTemplate, COMPANY_KEY, "Ledger Company", COMPANY_RFC, TenantFixtures.SEED_CURRENCY);
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
                INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, companyCountryId, companyId, suffix, "Ledger region " + suffix));
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
                """, id, regionId, companyId, suffix, "Ledger zone " + suffix));
        insertedCompanyZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertStore(UUID companyZoneId) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, companyZoneId, companyId, "Ledger store " + suffix));
        insertedStores.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertArea(UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, store, suffix, "Ledger area " + suffix));
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
                """, id, areaId, store, suffix, "Ledger zone " + suffix));
        insertedStoreZones.add(new InsertedRow(companyId, id));
        return id;
    }

    private UUID insertLocation(UUID storeZoneId, UUID store) {
        var id = UUID.randomUUID();
        var suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code, location_name,
                                             enabled)
                VALUES (?, ?, ?, ?, ?, true)
                """, id, storeZoneId, store, suffix, "Ledger location " + suffix));
        insertedLocations.add(new InsertedRow(companyId, id));
        return id;
    }

    /**
     * A product and one variant of it, which is what every movement names.
     *
     * <p>{@code measure_unit_id} is a real {@code measure_units} row — {@code V3} seeds "Pieza" as
     * {@code H87} and {@code V55} made the column required — and {@code bar_code} stays absent, which
     * {@code V56} allows for a {@code SIMPLE} article. Both avoid a refusal that would say nothing about
     * the ledger.</p>
     */
    private UUID insertVariant() {
        var productId = UUID.randomUUID();
        var sku = "LEDGER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                        productId,
                        sku,
                        "Ledger product " + sku,
                        companyId));
        insertedProducts.add(new InsertedRow(companyId, productId));

        var variantId = UUID.randomUUID();
        var measureUnitId =
                jdbcTemplate.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_variants (id, company_id, product_id, variant_name, measure_unit_id)
                VALUES (?, ?, ?, 'unica', ?)
                """, variantId, companyId, productId, measureUnitId));
        insertedVariants.add(new InsertedRow(companyId, variantId));
        return variantId;
    }
}
