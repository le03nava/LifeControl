package com.lifecontrol.api.common.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Verification of {@code V67__status_transition.sql} and of {@link StatusTransitionRecorder}: the append-only
 * history table, its grant set, decision 6's tenant check, the two uniqueness indexes, decision 5's side B
 * deferred trigger, and the recorder that is the table's only writer (ADR-0004 task T4; invariants T3 and T7).
 *
 * <p><b>Why the shared head container and not a scratch database.</b> Every fact here is about the head
 * schema as the application meets it: the table exists, the roles hold what {@code V67} says they hold, and a
 * trigger refuses a commit. None of it is a rewrite of pre-existing rows, which is the only thing a scratch
 * database held at an earlier version buys.</p>
 *
 * <p><b>Three credentials, and each case declares the one its premise needs.</b> The pool logs in as
 * {@code lifecontrol_app} (record T6b-4), so the scoped reads, the grant question and the recorder's own
 * writes run there — that is the role the rules apply to. Two facts are invisible to it: the append-only
 * <b>trigger</b>, because the revoked {@code UPDATE}/{@code DELETE} privilege is denied before any trigger is
 * reached, and the tenant trigger of decision 6, because the policy refuses the row first and would make
 * "refused" true for the wrong reason. Those two cases run on the container's own superuser login, which is
 * what makes the trigger the only thing that can refuse them; the class says so at each of them.</p>
 *
 * <p><b>What "nothing was stored" means here.</b> Counts are read on the superuser login, never through a
 * policy: a row hidden by a scope the case forgot to state would read as absent and turn a stored row into a
 * false pass. The one read that <em>must</em> go through the policy is the arming case, and it says so.</p>
 */
@SpringBootTest
@DisplayName("Status transition history and its recorder (V67)")
class StatusTransitionRecorderIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The {@code sub} claim every fixture request carries, and the actor the rows must record. */
    private static final String SUBJECT = "v67-actor-sub";

    /** The {@code preferred_username} of the same token, used by the fallback case. */
    private static final String USERNAME = "v67-actor";

    /**
     * The container's own login, a SUPERUSER: it bypasses the policies, so a statement here is refused by the
     * triggers and by nothing else — and it is the only credential that can delete a row to clean up, because
     * the append-only trigger refuses the {@code DELETE} for every role that reaches it.
     */
    private static final DataSource CONTAINER_SUPERUSER = containerSuperuserPool();

    private static final JdbcTemplate UNRESTRICTED = new JdbcTemplate(CONTAINER_SUPERUSER);

    /** One row as a writer writes it: {@code seq} and {@code occurred_at} are the database's. */
    private static final String INSERT_ROW = """
            INSERT INTO status_transition
                (company_id, document_type, document_id, from_status, to_status, actor, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** The same insert with the instant stated, which is what makes "the same move at another instant" testable. */
    private static final String INSERT_ROW_AT = """
            INSERT INTO status_transition
                (company_id, document_type, document_id, from_status, to_status, actor, reason, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StatusTransitionRecorder recorder;

    /** The companies this class seeded, so the teardown can leave the shared container as it found it. */
    private final List<UUID> companies = new ArrayList<>();

    @BeforeEach
    void bindARequestWithAnActor() {
        // The recorder reads the request-scoped CurrentUserContext, so calling it outside a web request needs
        // a bound request (the scoped proxy has to resolve) and a JWT authentication. The same shape
        // `GoodsReceiptIntegrationTest` uses for the same reason.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        authenticate(Jwt.withTokenValue("v67-token")
                .header("alg", "none")
                .subject(SUBJECT)
                .claim("preferred_username", USERNAME)
                .build());
    }

    @AfterEach
    void removeEveryRowThisSuiteWrote() throws SQLException {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();

        if (companies.isEmpty()) {
            return;
        }
        try (var connection = CONTAINER_SUPERUSER.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // `SET LOCAL session_replication_role = replica` and not a disabled trigger: the append-only
                // trigger is the invariant this suite asserts, and turning it off would be a catalog change on
                // a table the whole container shares. This says the same thing about one session, inside one
                // transaction, and expires at COMMIT — the pattern `AbstractPostgresIntegrationTest` documents.
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                for (var company : companies) {
                    for (var sql : List.of(
                            "DELETE FROM status_transition WHERE company_id = ?",
                            "DELETE FROM sales_orders WHERE company_id = ?",
                            "DELETE FROM store_locations WHERE company_store_id IN"
                                    + " (SELECT id FROM company_stores WHERE company_id = ?)",
                            "DELETE FROM store_zones WHERE company_store_id IN"
                                    + " (SELECT id FROM company_stores WHERE company_id = ?)",
                            "DELETE FROM store_areas WHERE company_store_id IN"
                                    + " (SELECT id FROM company_stores WHERE company_id = ?)",
                            "DELETE FROM company_stores WHERE company_id = ?",
                            "DELETE FROM company_zones WHERE company_id = ?",
                            "DELETE FROM company_regions WHERE company_id = ?",
                            "DELETE FROM company_countries WHERE company_id = ?",
                            "DELETE FROM companies WHERE id = ?")) {
                        try (var prepared = connection.prepareStatement(sql)) {
                            prepared.setObject(1, company);
                            prepared.executeUpdate();
                        }
                    }
                }
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                throw new IllegalStateException("could not remove the rows this suite wrote", failed);
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            companies.clear();
        }
    }

    // ─── T3, first half: history is immutable ────────────────────────────────────────────────────────

    @Test
    @DisplayName("T3: UPDATE and DELETE on a stored row raise, and the row is still there unchanged")
    void refusesUpdateAndDeleteOnAStoredRow() {
        var company = seedCompany("append-only");
        var document = insertSalesOrder(company, 1, "Draft");

        assertThatThrownBy(() -> UNRESTRICTED.update(
                        "UPDATE status_transition SET reason = 'rewritten by hand' WHERE document_id = ?", document))
                .as("ADR-0004 decision 4: the table is append-only, and the BEFORE UPDATE trigger is the half "
                        + "of that rule which holds for every role that can reach the statement — the "
                        + "application role's denial comes earlier and is the next case. Run on the superuser "
                        + "login, which is the only credential that reaches the trigger at all")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("status_transition is append-only");

        assertThatThrownBy(() -> UNRESTRICTED.update("DELETE FROM status_transition WHERE document_id = ?", document))
                .as("the same rule from the other direction: deleting the only record of a move would leave a "
                        + "document no history explains")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("status_transition is append-only");

        var rows = rowsOf(document);
        assertThat(rows)
                .as("both refusals stored nothing: the row is still exactly one")
                .hasSize(1);
        assertThat(rows.get(0).reason())
                .as("and the UPDATE did not take: this creation row was written by the recorder, which writes "
                        + "no reason for a creation — a reason belongs to a move — so the 'rewritten by hand' the "
                        + "statement asked for is demonstrably absent")
                .isNull();
        assertThat(rows.get(0).toStatus()).isEqualTo("DRAFT");
    }

    // ─── T3, second half: the grant set is the rule, not a document ──────────────────────────────────

    @Test
    @DisplayName("T3: the application role holds INSERT and SELECT on status_transition and nothing else")
    void theApplicationRoleHoldsSelectAndInsertOnly() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .as("the premise, stated: the pool logs in as the role these grants are about, so the "
                        + "two-argument has_table_privilege below asks about the application itself")
                .isEqualTo("lifecontrol_app");

        assertThat(List.of(holds("SELECT"), holds("INSERT"), holds("UPDATE"), holds("DELETE")))
                .as("ADR-0004 decision 4 and its schema block: V52:190-197 granted SELECT, INSERT, UPDATE and "
                        + "DELETE to this role and recorded the same as a DEFAULT PRIVILEGE, so the new table "
                        + "arrives writable and REVOKE is the only statement that takes it away. The explicit "
                        + "GRANT after the REVOKE states the complete set the role is meant to hold")
                .containsExactly(true, true, false, false);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE status_transition SET reason = 'rewritten' WHERE document_type = 'SALES_ORDER'"))
                .as("the grant is not a comment: PostgreSQL refuses the statement itself, before any trigger")
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown ->
                        assertThat(rootCauseMessage(thrown)).contains("permission denied for table status_transition"));
    }

    // ─── T7: a transition names a document of its own tenant ─────────────────────────────────────────

    @Test
    @DisplayName("T7: a row naming another tenant's document is refused, and the message names it")
    void refusesATransitionForAnotherTenantsDocument() {
        var owner = seedCompany("tenant-owner");
        var other = seedCompany("tenant-other");
        var document = insertSalesOrder(owner, 1, "Draft");

        // A move row and not the creation row: the fixture already wrote the document's one creation row, and
        // `uq_status_transition_creation` allows exactly one — so the tenant rule is stated on the row shape
        // that can still be written. Inserted on the superuser login on purpose: the policy is out of the way,
        // so decision 6's trigger is the only thing that can refuse it, which is exactly the claim tested.
        assertThatThrownBy(() -> insertMoveRow(other, document, "DRAFT", "ACTIVE"))
                .as("ADR-0004 decision 6: without this trigger a wrong company_id is not rejected but HIDDEN, "
                        + "because ADR-0005's policy filters the row instead of failing the write")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not name a SALES_ORDER")
                .hasMessageContaining(document.toString());
        assertThat(rowsOf(document))
                .as("the refusal stored nothing: the document still has only the creation row its writer wrote")
                .hasSize(1);

        moveDocumentAndAppend(owner, document, "DRAFT", "ACTIVE", "2026-11-01T10:00:00Z");

        assertThat(rowsOf(document))
                .as("the same move with the company that owns the document is accepted, and it is the second "
                        + "row of a history that began with its creation row")
                .hasSize(2);
    }

    // ─── T3: one creation row, and one row per move ──────────────────────────────────────────────────

    @Test
    @DisplayName("T3: one creation row per document, one row per move and instant, and a real repeat passes")
    void recordsOneCreationRowAndOneMove() {
        var company = seedCompany("uniqueness");
        var document = insertSalesOrder(company, 1, "Draft");

        assertThatThrownBy(() -> insertCreationRow(company, document, "DRAFT", "second"))
                .as("`uq_status_transition_creation` is partial on `from_status IS NULL`, so a document has "
                        + "exactly one row with no predecessor")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_status_transition_creation");
        assertThat(rowsOf(document)).as("and the refused insert stored nothing").hasSize(1);

        assertThatThrownBy(() -> insertTwoIdenticalMovesInOneTransaction(company, document))
                .as("`uq_status_transition_once_per_move` covers occurred_at: two identical rows written in one "
                        + "transaction share now(), so the duplicate write is refused")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_status_transition_once_per_move");
        assertThat(rowsOf(document))
                .as("the aborted transaction stored nothing: still the creation row and nothing else")
                .hasSize(1);

        // A genuine A->B->A->B is three different facts at three different instants, which is why the index is
        // not a plain UNIQUE over the four columns.
        moveDocumentAndAppend(company, document, "DRAFT", "ACTIVE", "2026-11-01T10:00:00Z");
        moveDocumentAndAppend(company, document, "ACTIVE", "DRAFT", "2026-11-02T10:00:00Z");
        moveDocumentAndAppend(company, document, "DRAFT", "ACTIVE", "2026-11-03T10:00:00Z");

        assertThat(rowsOf(document))
                .as("the creation row plus the three committed moves")
                .hasSize(4);
        assertThat(rowsOf(document).stream()
                        .filter(row -> "DRAFT".equals(row.fromStatus()) && "ACTIVE".equals(row.toStatus()))
                        .count())
                .as("the same A->B twice at different instants is a different fact and is accepted")
                .isEqualTo(2);
    }

    // ─── decision 5, side B: a row cannot claim a state the document does not hold ───────────────────

    @Test
    @DisplayName("side B: a row whose to_status is not the document's state fails at COMMIT, not at insert")
    void refusesARowThatClaimsAStateTheDocumentDoesNotHold() throws SQLException {
        var company = seedCompany("side-b");
        var document = insertSalesOrder(company, 1, "Draft");

        try (var connection = CONTAINER_SUPERUSER.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(INSERT_ROW)) {
                    statement.setObject(1, company);
                    statement.setString(2, "SALES_ORDER");
                    statement.setObject(3, document);
                    statement.setString(4, "DRAFT");
                    statement.setString(5, "ACTIVE");
                    statement.setString(6, "side-b");
                    statement.setString(7, "claims a move that did not happen");
                    statement.executeUpdate();
                }

                // The insert above succeeded, and that is half of the case: the constraint is deferred, so the
                // row is legal when it is written and only the commit can judge it against the document.
                assertThatThrownBy(connection::commit)
                        .as("`trg_status_transition_matches_state` is DEFERRABLE INITIALLY DEFERRED, so the "
                                + "refusal lands at COMMIT — which is what makes the order of the two writes "
                                + "inside the transaction stop mattering")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("the latest row says ACTIVE but the document is DRAFT");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }

        assertThat(rowsOf(document))
                .as("the refused commit stored nothing: the document still has its one creation row")
                .hasSize(1);
    }

    // ─── the recorder ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the recorder refuses a forbidden pair and writes nothing, and stores the state and the actor")
    void theRecorderRefusesAForbiddenMoveAndStoresTheActor() {
        var company = seedCompany("recorder");
        var document = insertSalesOrder(company, 1, "Draft");

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        company,
                        () -> recorder.recordMove(
                                StatusSubject.SALES_ORDER,
                                company,
                                document,
                                SalesOrderState.DRAFT,
                                SalesOrderState.COMPLETED,
                                "not a declared edge")))
                .as("ADR-0004 decision 2 and invariant T2: a pair the family does not declare is a 409 that "
                        + "leaves the row untouched, so the guard runs BEFORE the insert")
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("COMPLETED");
        assertThat(rowsOf(document))
                .as("the refusal wrote nothing beyond the creation row the fixture's own transaction wrote, and "
                        + "no move row: a refused move is not a transition row")
                .hasSize(1);

        // The creation row under test is the one `insertSalesOrder` wrote through the recorder, in the same
        // transaction as the document: a document cannot commit without it, so the fixture owns it and this
        // case reads it back instead of writing a second one.
        var creation = rowsOf(document);
        assertThat(creation)
                .as("the creation row is the one with no predecessor")
                .hasSize(1);
        assertThat(creation.get(0).fromStatus())
                .as("`from_status` is NULL, which is what makes it the creation row")
                .isNull();
        assertThat(creation.get(0).toStatus())
                .as("`to_status` is the constant's name(), not the catalog's label")
                .isEqualTo("DRAFT");
        assertThat(creation.get(0).actor())
                .as("the actor is the request's `sub` claim, which is the question this table exists to answer")
                .isEqualTo(SUBJECT);

        TenantFixtures.inTenant(company, () -> {
            moveDocument(company, document, "ACTIVE");
            recorder.recordMove(
                    StatusSubject.SALES_ORDER,
                    company,
                    document,
                    SalesOrderState.DRAFT,
                    SalesOrderState.ACTIVE,
                    "charged");
        });

        var move = rowsOf(document).stream()
                .filter(row -> row.fromStatus() != null)
                .findFirst()
                .orElseThrow();
        assertThat(move.fromStatus()).isEqualTo("DRAFT");
        assertThat(move.toStatus()).isEqualTo("ACTIVE");
        assertThat(move.reason()).as("the free-text reason is stored as given").isEqualTo("charged");
        assertThat(move.actor())
                .as("a move records the same actor as a creation")
                .isEqualTo(SUBJECT);
        assertThat(move.occurredAt())
                .as("`occurred_at` is the database's, so a writer cannot claim an instant it did not observe")
                .isNotNull();
    }

    @Test
    @DisplayName("the actor falls back to the username, and a request with neither is refused")
    void theActorFallsBackToTheUsernameAndRefusesWhenThereIsNone() {
        var company = seedCompany("actor");
        var document = insertSalesOrder(company, 1, "Draft");
        var other = insertSalesOrder(company, 2, "Draft");

        // A new request, so the request-scoped context is a fresh instance: it caches the two claims.
        RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        authenticate(Jwt.withTokenValue("v67-token-no-sub")
                .header("alg", "none")
                .claim("preferred_username", USERNAME)
                .build());

        // A move, not a creation: every document already carries the one creation row it could not have been
        // written without, so the actor resolution of a live write is stated on the row shape that can still be
        // appended now. The rule belongs to the recorder and not to the row: `sub` when it exists,
        // `preferred_username` when it does not.
        TenantFixtures.inTenant(company, () -> {
            moveDocument(company, document, "ACTIVE");
            recorder.recordMove(
                    StatusSubject.SALES_ORDER,
                    company,
                    document,
                    SalesOrderState.DRAFT,
                    SalesOrderState.ACTIVE,
                    "actor");
        });

        assertThat(rowsOf(document).stream()
                        .filter(row -> row.fromStatus() != null)
                        .findFirst()
                        .orElseThrow())
                .as("a token with no `sub` falls back to `preferred_username` rather than refusing a write the "
                        + "request can identify")
                .extracting(HistoryRow::actor)
                .isEqualTo(USERNAME);

        RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> TenantFixtures.inTenant(
                        company,
                        () -> recorder.recordCreation(
                                StatusSubject.SALES_ORDER, company, other, SalesOrderState.DRAFT)))
                .as("with no identity at all the row would carry a placeholder, and 'unknown' is the "
                        + "migration's literal for history it did not observe — a live write may not claim it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("would have no actor")
                .hasMessageContaining("SALES_ORDER")
                .hasMessageContaining(other.toString());
        assertThat(rowsOf(other))
                .as("the refusal wrote nothing beyond the creation row the fixture's own transaction wrote: no "
                        + "second creation row, and no placeholder actor")
                .hasSize(1);
    }

    // ─── the arm: the new tenant table joins ADR-0005's list ─────────────────────────────────────────

    @Test
    @DisplayName("the arm: status_transition is armed, and a scoped read does not return another tenant's row")
    void theArmIsInPlaceAndThePolicyFiltersTheOtherTenant() {
        var first = seedCompany("armed-first");
        var second = seedCompany("armed-second");
        var firstDocument = insertSalesOrder(first, 1, "Draft");
        var secondDocument = insertSalesOrder(second, 1, "Draft");

        assertThat(jdbcTemplate.queryForList("SELECT t::text FROM unprotected_tenant_tables() t", String.class))
                .as("ADR-0004 migration step 8: `apply_tenant_isolation('status_transition')` is the last "
                        + "statement of V67, and the same query TenantIsolationStartupCheck runs at every boot "
                        + "must return no row — otherwise the application does not start at all")
                .isEmpty();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT table_name::text || ':' || policy_kind || ':' || key_column"
                                + " FROM tenant_table WHERE table_name = 'status_transition'::regclass",
                        String.class))
                .as("listed is one fact, armed is another: the kind and the key column ARE the predicate")
                .containsExactly("status_transition:tenant:company_id");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class"
                                + " WHERE oid = 'status_transition'::regclass",
                        Boolean.class))
                .as("ENABLE turns policies on and FORCE makes them apply to the owner as well")
                .isTrue();

        // Asked about this suite's two documents and not about the whole table: the container is shared, so a
        // `SELECT *` here would be an assertion about every other suite's history as well.
        var visibleToFirst = TenantFixtures.inTenant(
                first,
                () -> jdbcTemplate.queryForList(
                        "SELECT document_id::text FROM status_transition WHERE document_id IN (?, ?)",
                        String.class,
                        firstDocument,
                        secondDocument));
        assertThat(visibleToFirst)
                .as("a scoped read answers with its own tenant's history")
                .containsExactly(firstDocument.toString());
        assertThat(visibleToFirst)
                .as("and not the other tenant's row, which the policy filters instead of failing the read")
                .doesNotContain(secondDocument.toString());
    }

    // ─── fixtures: two companies, each with a store chain and a document ─────────────────────────────

    /**
     * A company with one store at the bottom of the hierarchy the composite keys require, plus the receiving
     * location a goods receipt would name — the shape {@code DocumentIdentityMigrationIntegrationTest} seeds,
     * written through the application role inside the tenant's own scope.
     *
     * <p>No supplier and no payment method: they exist for {@code purchase_orders} and this suite writes only
     * sales orders. A fixture that seeded them would be a reference nobody reads.</p>
     */
    private UUID seedCompany(String label) {
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var company = TenantFixtures.insertCompany(
                jdbcTemplate,
                "V67-" + label + "-" + suffix,
                "V67 Company " + label,
                ("V67" + suffix).toUpperCase(),
                TenantFixtures.SEED_CURRENCY);
        companies.add(company);

        var countryId = jdbcTemplate.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        var companyCountry = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                        companyCountry,
                        company,
                        countryId));

        var region = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_regions (id, company_country_id, company_id, region_code, region_name, enabled)"
                                + " VALUES (?, ?, ?, ?, ?, true)",
                        region,
                        companyCountry,
                        company,
                        suffix,
                        "V67 Region " + suffix));

        var companyZone = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_zones (id, company_region_id, company_id, zone_code, zone_name, enabled)"
                                + " VALUES (?, ?, ?, ?, ?, true)",
                        companyZone,
                        region,
                        company,
                        suffix,
                        "V67 Zone " + suffix));

        var store = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO company_stores (id, company_zone_id, company_id, store_name, enabled)"
                                + " VALUES (?, ?, ?, ?, true)",
                        store,
                        companyZone,
                        company,
                        "V67 Store " + suffix));

        var area = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)"
                                + " VALUES (?, ?, ?, ?, true)",
                        area,
                        store,
                        suffix,
                        "V67 Area " + suffix));

        var storeZone = UUID.randomUUID();
        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)"
                                + " VALUES (?, ?, ?, ?, ?, true)",
                        storeZone,
                        area,
                        store,
                        suffix,
                        "V67 Store Zone " + suffix));

        TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.update(
                        "INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code, location_name,"
                                + " enabled) VALUES (?, ?, ?, ?, ?, true)",
                        UUID.randomUUID(),
                        storeZone,
                        store,
                        "V67L" + suffix.substring(0, 5),
                        "V67 Receiving"));
        return company;
    }

    /**
     * One sales order, written in the shape {@code V65} leaves behind: an identity in parts (ADR-0003) and one
     * {@code status_id} from the catalog.
     *
     * <p>{@code status} is deliberately omitted: {@code V66}'s bridge derives it from {@code statuses.code},
     * which is how every writer today reaches the new {@code NOT NULL} while {@code status_id} still exists.</p>
     *
     * <p><b>The document and its creation row are written in ONE transaction, and that is not a
     * convenience.</b> {@code V68} arms ADR-0004 decision 5's side A for this table: a sales order that
     * commits with no {@code status_transition} creation row is refused at the commit itself, so a fixture
     * that inserted the document first and its row afterwards would be writing the two halves of one fact in
     * two transactions — exactly the shape the invariant forbids (ADR-0004 migration step 6 says the pair goes
     * in one transaction). The second half is written by the recorder, because it is the production writer.</p>
     */
    private UUID insertSalesOrder(UUID company, long seq, String catalogStatus) {
        var id = UUID.randomUUID();
        var number = "SO-202611-" + String.format("%05d", seq);
        var customer = customerId();
        // Scoped, because `company_stores` is armed: an unscoped read answers no row at all.
        var store = TenantFixtures.inTenant(
                company,
                () -> jdbcTemplate.queryForObject(
                        "SELECT id FROM company_stores WHERE company_id = ?", UUID.class, company));
        var catalog = statusId("SALES_ORDER", catalogStatus);
        var state = SalesOrderState.valueOf(catalogStatus.toUpperCase(Locale.ROOT));
        TenantFixtures.inTenant(company, () -> {
            jdbcTemplate.update(
                    "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date, status_id,"
                            + " company_id, doc_type, seq_period, seq_value, doc_number)"
                            + " VALUES (?, ?, ?, ?, '2026-11-05 10:00:00', ?, ?, 'SALES_ORDER', '202611', ?, ?)",
                    id,
                    number,
                    customer,
                    store,
                    catalog,
                    company,
                    seq,
                    number);
            recorder.recordCreation(StatusSubject.SALES_ORDER, company, id, state);
        });
        return id;
    }

    /** The one global reference a sales order names; created once, on the container's first use. */
    private UUID customerId() {
        var existing = jdbcTemplate.queryForList("SELECT id FROM customers LIMIT 1", UUID.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO customers (id, name, sales_channel) VALUES (?, 'V67 Customer', 'STORE')", id);
        return id;
    }

    /** The catalog row of one family and one label: {@code statuses} is not a tenant table. */
    private UUID statusId(String typeName, String statusName) {
        return jdbcTemplate.queryForObject(
                "SELECT s.id FROM statuses s JOIN status_types st ON st.id = s.status_type_id"
                        + " WHERE st.status_type_name = ? AND s.status_name = ?",
                UUID.class,
                typeName,
                statusName);
    }

    // ─── the writes the cases need ───────────────────────────────────────────────────────────────────

    /**
     * One creation row, on the superuser login: the cases that insert directly are asking what the
     * <b>triggers</b> do, and the policy would answer first.
     *
     * <p>Every document this suite writes arrives with its creation row already — {@code V68} refuses a
     * document that commits without one — so a direct creation row is only ever a <b>second</b> one, and the
     * case that writes it is asserting the unique index.</p>
     */
    private void insertCreationRow(UUID company, UUID document, String state, String reason) {
        UNRESTRICTED.update(INSERT_ROW, company, "SALES_ORDER", document, null, state, "fixture", reason);
    }

    /**
     * One move row on its own, on the superuser login: what a case needs when it is asking about the row's
     * tenant rather than about the pair's consistency — the document has not moved, so side B would refuse
     * this at commit, and the refusal under test (decision 6's {@code BEFORE INSERT}) happens first.
     */
    private void insertMoveRow(UUID company, UUID document, String from, String to) {
        UNRESTRICTED.update(INSERT_ROW, company, "SALES_ORDER", document, from, to, "fixture", "move");
    }

    /** Two identical moves in one transaction: one {@code now()}, so the second is the duplicate. */
    private void insertTwoIdenticalMovesInOneTransaction(UUID company, UUID document) throws SQLException {
        try (var connection = CONTAINER_SUPERUSER.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (var ignored : List.of(1, 2)) {
                    try (var statement = connection.prepareStatement(INSERT_ROW)) {
                        statement.setObject(1, company);
                        statement.setString(2, "SALES_ORDER");
                        statement.setObject(3, document);
                        statement.setString(4, "DRAFT");
                        statement.setString(5, "ACTIVE");
                        statement.setString(6, "fixture");
                        statement.setString(7, "duplicate write");
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * One real move: the document changes state and its row is appended, in <b>one</b> transaction — the shape
     * decision 5 makes mandatory, because the deferred trigger judges the pair at commit.
     */
    private void moveDocumentAndAppend(UUID company, UUID document, String from, String to, String instant) {
        TenantFixtures.inTenant(company, () -> {
            moveDocument(company, document, to);
            jdbcTemplate.update(INSERT_ROW_AT, company, "SALES_ORDER", document, from, to, "fixture", "move", instant);
        });
    }

    /**
     * Moves the document's typed state, and the catalog pointer beside it.
     *
     * <p>Both columns are written because {@code V66}'s bridge refuses a stored state that disagrees with the
     * catalog code while {@code status_id} still exists; passing only {@code status} would be refused by the
     * bridge, which is the bridge working, not an obstacle to work around.</p>
     */
    private void moveDocument(UUID company, UUID document, String constant) {
        jdbcTemplate.update(
                "UPDATE sales_orders SET status = ?, status_id = ? WHERE id = ?",
                constant,
                statusId("SALES_ORDER", titleCase(constant)),
                document);
    }

    /** {@code ACTIVE} is stored and {@code Active} is the label the catalog carries. */
    private static String titleCase(String constant) {
        return constant.substring(0, 1) + constant.substring(1).toLowerCase();
    }

    // ─── reads ───────────────────────────────────────────────────────────────────────────────────────

    /** Every history row of one document, read on the superuser login so no policy can hide one. */
    private List<HistoryRow> rowsOf(UUID document) {
        return UNRESTRICTED.query(
                "SELECT from_status, to_status, actor, reason, occurred_at::text FROM status_transition"
                        + " WHERE document_id = ? ORDER BY seq",
                (resultSet, index) -> new HistoryRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5)),
                document);
    }

    /** Whether the application role holds one privilege on the table, asked as that role. */
    private boolean holds(String privilege) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege('status_transition', ?)", Boolean.class, privilege));
    }

    /** The driver's own sentence, at the bottom of the Spring exception chain. */
    private static String rootCauseMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /** One row as the cases read it back. */
    private record HistoryRow(String fromStatus, String toStatus, String actor, String reason, String occurredAt) {}

    /** Binds one JWT as the request's authentication, with no authorities the recorder could depend on. */
    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("lc-admin"))));
    }
}
