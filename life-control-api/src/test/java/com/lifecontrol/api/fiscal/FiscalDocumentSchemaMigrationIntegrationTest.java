package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.SaleFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code V108__fiscal_document.sql}: the fiscal document's own row set — the three tables, their policies,
 * the append-only pair, the state machine and the two unique indexes (S5, S6 and S7 of
 * {@code odd/tasks/cfdi-fiscal-document.md}; T4, decisions L9(a)–L9(d) and L9(j)).
 *
 * <p><b>Why a scratch database.</b> What is observed here is what the migration does to a database that does
 * not have it: the tables do not exist at the pre-unit head, so a case that wants to prove a refusal has to
 * write the row on the schema this file creates and nowhere else. The chain runs to {@code 107} first — the
 * head A1 leaves — and then to head, so the two halves are the before and the after and not the tree's
 * current shape read twice.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on every table it arms, so the policy applies to the table's <b>owner</b> — the role
 * delivery migrates with. The container's own user is a superuser and bypasses every policy, so a migration
 * asserted under it would report success while delivery's {@code NOSUPERUSER} owner matched nothing. That is
 * also why the fixtures and the refusal probes below run as the container's superuser: row security is
 * bypassed there, so the only refusal a statement can get is the constraint or the trigger under test, and not
 * the policy refusing a write for having no tenant scope.</p>
 *
 * <p><b>What this suite builds now that {@code V109} is the head, and what it still does not.</b> {@code
 * fiscal_document_line} requires a real {@code sales_order_item}, and building one means reproducing the whole
 * fixture chain (company, store, product, variant, order, item) — which is {@link SaleFixtures}, shared with
 * {@code FiscalDocumentSnapshotMigrationIntegrationTest} because both slices need it and one copy of a chain
 * that has to satisfy two schemas is the copy that can be right. Two facts moved the sale chain into this
 * suite: {@code V109} made the document's frozen identity, its place of issue, its currency and its money
 * parts {@code NOT NULL}, so every row below states them; and it added a constraint trigger that judges a
 * {@code STAMPED} row at commit against the sale its {@code source_id} names, so a stamped fixture naming a
 * random id would be refused for a reason that has nothing to do with the rule its case pins. The line's own
 * append-only half is still pinned structurally — trigger present, enabled, on the right function — plus the
 * privilege refusal, which needs no row at all, because no case here needs a {@code fiscal_document_line}:
 * {@code V109}'s judgement walks the document's own lines, so a stamped document without lines still exercises
 * the document's half of it, and the lines' comparison is the sibling suite's subject. The artefact's half
 * <b>is</b> exercised with a real row, because an artefact needs only a document and a company.</p>
 *
 * <p>No Spring context: this suite is JDBC against a scratch database, like the sibling schema suites.</p>
 */
@DisplayName("Fiscal document schema (V108)")
class FiscalDocumentSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: A1 is complete and no fiscal document exists anywhere. */
    private static final String PRE_UNIT_HEAD = "107";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    /** The role the pool logs in as, and the one the grants below are written for. */
    private static final String APP_ROLE = "lifecontrol_app";

    /** The three tables this file creates, in the order the migration creates them. */
    private static final String[] TABLES = {"fiscal_document", "fiscal_document_line", "fiscal_document_artifact"};

    private String databaseName;

    private String jdbcUrl;

    /**
     * The one sale fixture, shared with {@code FiscalDocumentSnapshotMigrationIntegrationTest}: since
     * {@code V109} a {@code STAMPED} row is judged at commit against the sale its {@code source_id} names, so a
     * case that needs a stamped row needs a real sale and not an id.
     */
    private SaleFixtures fixtures;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_fiscal_document_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + OWNER);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
        fixtures = new SaleFixtures(this::executeInOneTransaction, this::read);
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (Exception e) {
            throw new IllegalStateException("could not drop scratch database " + databaseName, e);
        } finally {
            databaseName = null;
        }
    }

    // --- tenancy: the three tables are armed in the file that creates them ---

    @Test
    @DisplayName("each of the three tables is armed: ENABLE, FORCE, exactly one p_tenant_ policy and a "
            + "tenant/company_id row")
    void eachOfTheThreeTablesIsArmed() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        for (var table : TABLES) {
            assertThat(read("SELECT relrowsecurity::text || '/' || relforcerowsecurity::text FROM pg_class "
                            + "WHERE relname = '" + table + "'"))
                    .as(table + ": ENABLE turns the policy on and FORCE stops the table's owner — Flyway's role "
                            + "— from being exempt (ADR-0005)")
                    .isEqualTo("true/true");

            assertThat(count("SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = '" + table
                            + "' AND policyname = 'p_tenant_" + table + "'"))
                    .as(table + ": exactly one policy, the name apply_tenant_isolation derives, so no second "
                            + "predicate nobody wrote is ANDed in")
                    .isEqualTo(1);

            assertThat(read("SELECT policy_kind || ':' || key_column FROM tenant_table " + "WHERE table_name = '"
                            + table + "'::regclass"))
                    .as(table + ": the registry row the startup assertion reads, and the row the policy was "
                            + "generated from — a wrong kind or key filters the wrong thing in silence")
                    .isEqualTo("tenant:company_id");
        }
    }

    @Test
    @DisplayName("unprotected_tenant_tables() names none of the three, so the startup assertion still passes")
    void theStartupAssertionDoesNotNameAnyOfTheThree() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(read("SELECT coalesce(string_agg(t, ',' ORDER BY t), '') FROM "
                        + "unnest(ARRAY['fiscal_document', 'fiscal_document_line', 'fiscal_document_artifact']) AS t "
                        + "WHERE to_regclass('public.' || t) IS NULL"))
                .as("the control: the three tables exist, so the empty answer below is the policy's and not an "
                        + "absent table's — without it this case would pass on a database that has no fiscal "
                        + "document at all")
                .isEmpty();
        assertThat(read("SELECT coalesce(string_agg(t::text, ',' ORDER BY t::text), '') "
                        + "FROM unprotected_tenant_tables() t WHERE t::text LIKE 'fiscal_document%'"))
                .as("V52's query is what TenantIsolationStartupCheck refuses to boot on: a table carrying "
                        + "company_id with no policy would be a database that never starts, in every environment "
                        + "at once (L9(j))")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM unprotected_tenant_tables()"))
                .as("and the whole answer is empty, which is the state V105 left: this file adds no table to "
                        + "the list")
                .isZero();
    }

    // --- F1: one live stamped document per sale ---

    @Test
    @DisplayName("a second STAMPED document for the same sale is refused, two DRAFTs are accepted, and a "
            + "CANCELLED row beside the stamped one is accepted")
    void refusesASecondStampedDocumentForTheSameSale() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V108-F1");
        var stamped = insertStamped(sale);

        assertThat(refusalOf(stampedRow(UUID.randomUUID(), sale)))
                .as("F1: at most one live stamped document per sale. A second stamp is the document that must "
                        + "not exist — the authority would hold two UUIDs for one sale and neither would be "
                        + "the original")
                .contains("uq_fiscal_document_one_stamped");

        var firstDraft = insertDraft(sale.company(), sale.sale());
        var secondDraft = insertDraft(sale.company(), sale.sale());
        assertThat(read("SELECT count(*) FROM fiscal_document WHERE source_id = '" + sale.sale() + "' AND status = "
                        + "'DRAFT'"))
                .as("the index is partial, so a retry is a draft: " + firstDraft + " and " + secondDraft
                        + " coexist and neither competes with the stamped row")
                .isEqualTo("2");

        assertThat(refusalOf(cancellationRow(sale.company(), stamped, "'01'")))
                .as("the cancellation row itself is accepted")
                .isEmpty();
        assertThat(read("SELECT count(*) FROM fiscal_document WHERE source_id = '" + sale.sale() + "'"))
                .as("S6: a cancelled document and its original are two rows and the pair is legal — a full "
                        + "unique key instead of the partial index would have refused exactly this")
                .isEqualTo("3");
    }

    // --- F3: the authority's UUID ---

    @Test
    @DisplayName("a STAMPED row without a uuid_fiscal is refused, a DRAFT carrying one is refused, and the same "
            + "uuid_fiscal twice is refused")
    void refusesAStampedRowWithoutAUuidAndADraftCarryingOne() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V108-F3");

        assertThat(refusalOf(documentRow(
                        UUID.randomUUID(), sale.company(), sale.sale(), "STAMPED", "NULL", "now()", "NULL", "NULL")))
                .as("F3: a stamped document states the UUID the authority gave it, and the CHECK is an equality "
                        + "in both directions so the absent half is refused too")
                .contains("ck_fiscal_document_uuid_matches_status");

        assertThat(refusalOf(documentRow(
                        UUID.randomUUID(),
                        sale.company(),
                        sale.sale(),
                        "DRAFT",
                        "'" + UUID.randomUUID() + "'",
                        "NULL",
                        "NULL",
                        "NULL")))
                .as("a draft carrying a UUID is a stamp that did not happen: the provider answered and the "
                        + "state did not move, which is the shape a partial failure leaves behind")
                .contains("ck_fiscal_document_uuid_matches_status");

        var uuidFiscal = UUID.randomUUID();
        insertStamped(sale, uuidFiscal);
        var otherSale = fixtures.seedSale("V108-F3-OTHER");
        assertThat(refusalOf(stampedRow(UUID.randomUUID(), otherSale, uuidFiscal)))
                .as("F3, second half: the UUID is the authority's global identity, so two rows cannot hold it — "
                        + "a different sale is not a different document")
                .contains("uq_fiscal_document_uuid_fiscal");
    }

    // --- F2: the append-only pair ---

    @Test
    @DisplayName("UPDATE and DELETE of a STAMPED row raise, UPDATE of a DRAFT is accepted, DELETE of a DRAFT "
            + "raises, and both operations on an artefact row raise")
    void refusesAWriteOnAWitnessedDocumentAndAcceptsTheTransitionOutOfDraft() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V108-F2");
        var stamped = insertStamped(sale);
        var draft = insertDraft(sale.company(), UUID.randomUUID());

        assertThat(refusalOf("UPDATE fiscal_document SET serie = 'A' WHERE id = '" + stamped + "'"))
                .as("F2: a stamped document's facts cannot be updated — the trigger is the half that refuses "
                        + "the owner and the superuser, which no grant can")
                .contains("fiscal_document")
                .contains("STAMPED");
        assertThat(refusalOf("DELETE FROM fiscal_document WHERE id = '" + stamped + "'"))
                .as("and it cannot be deleted: the authority keeps the original, and this tree keeps its copy")
                .contains("DELETE");
        assertThat(refusalOf("DELETE FROM fiscal_document WHERE id = '" + draft + "'"))
                .as("DELETE raises on any row, draft included — the state machine cancels, it never removes, "
                        + "which is why purging abandoned drafts is not in this slice")
                .contains("DELETE");

        assertThat(refusalOf("UPDATE fiscal_document SET serie = 'A' WHERE id = '" + draft + "'"))
                .as("the one legitimate update: the transition out of DRAFT, which the grant on this table "
                        + "exists for (L9(b))")
                .isEmpty();
        assertThat(read("SELECT serie FROM fiscal_document WHERE id = '" + draft + "'"))
                .as("and it really wrote the row")
                .isEqualTo("A");

        execute("INSERT INTO fiscal_document_artifact (fiscal_document_id, company_id, xml) VALUES ('" + stamped
                + "', '" + sale.company() + "', '<cfdi/>')");
        assertThat(refusalOf("UPDATE fiscal_document_artifact SET xml = '<other/>' WHERE fiscal_document_id = '"
                        + stamped + "'"))
                .as("F8: the artefact is the bytes that were stamped, so a reprint returns the original and "
                        + "never a regenerated document")
                .contains("fiscal_document_artifact");
        assertThat(refusalOf("DELETE FROM fiscal_document_artifact WHERE fiscal_document_id = '" + stamped + "'"))
                .as("and it is never removed, or a re-download three years later returns nothing")
                .contains("fiscal_document_artifact");
    }

    @Test
    @DisplayName(
            "the append-only trigger sits on the line and on the artefact, enabled, and one function " + "refuses both")
    void theAppendOnlyTriggerIsOnTheLineAndTheArtefact() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(read("SELECT string_agg(c.relname || ':' || t.tgname, ',' ORDER BY c.relname) "
                        + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                        + "WHERE NOT t.tgisinternal AND t.tgenabled <> 'D' "
                        + "AND t.tgfoid = 'fiscal_document_append_only'::regproc"))
                .as("one function, two tables: the line's half is pinned structurally because it requires a "
                        + "real sales_order_item and T5 is the slice that builds sale fixtures")
                .isEqualTo("fiscal_document_artifact:trg_fiscal_document_artifact_append_only,"
                        + "fiscal_document_line:trg_fiscal_document_line_append_only");
        assertThat(read("SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                        + "WHERE NOT t.tgisinternal AND c.relname = 'fiscal_document'"))
                .as("and the document's own guard is a second function, because its verdict is not the same: "
                        + "an UPDATE of a DRAFT is the transition, not a rewrite")
                .isEqualTo("trg_fiscal_document_guard");
    }

    // --- the grant: the pool may stamp and reprint, and may not rewrite ---

    @Test
    @DisplayName("lifecontrol_app cannot UPDATE a line nor DELETE a document, and can UPDATE a document")
    void theApplicationRoleCannotRewriteALineNorDeleteADocument() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var company = fixtures.seedCompany("V108-GRANT");
        var draft = insertDraft(company, UUID.randomUUID());

        assertThat(refusalAsApp("UPDATE fiscal_document_line SET company_id = company_id"))
                .as("V52:187 grants all four privileges on every new table, so the restrictive half has to be "
                        + "written — this is the statement that proves it was")
                .contains("permission denied");
        assertThat(refusalAsApp("DELETE FROM fiscal_document"))
                .as("a document is cancelled by a new row, never deleted")
                .contains("permission denied");

        assertThat(executeAsApp("UPDATE fiscal_document SET serie = 'P' WHERE id = '" + draft + "'", company))
                .as("and the transition path stays open: UPDATE on fiscal_document is granted on purpose, "
                        + "because the one legitimate update in this table is the way out of DRAFT (L9(b))")
                .isEqualTo(1);
        assertThat(read("SELECT serie FROM fiscal_document WHERE id = '" + draft + "'"))
                .as("the update really landed, so the grant is not merely present in the catalog")
                .isEqualTo("P");
    }

    // --- F4: a cancellation is a row, and it names a motive and a document of its own tenant ---

    @Test
    @DisplayName("a cancellation without a motive is refused, with motive '01' it is accepted, a second "
            + "cancellation is refused, and a pointer at another company's document is refused")
    void refusesACancellationWithoutMotiveAndASecondCancellationOfTheSameDocument() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V108-F4");
        var otherSale = fixtures.seedSale("V108-F4-OTHER");
        var company = sale.company();
        var stamped = insertStamped(sale);

        assertThat(refusalOf(documentRow(
                        UUID.randomUUID(),
                        company,
                        UUID.randomUUID(),
                        "CANCELLED",
                        "NULL",
                        "NULL",
                        "'" + stamped + "'",
                        "NULL")))
                .as("F4: a cancellation names why. A cancelled document with no motive is a row that says a "
                        + "document stopped existing and refuses to say what the authority was told")
                .contains("ck_fiscal_document_cancellation");

        assertThat(refusalOf(cancellationRow(company, stamped, "'01'")))
                .as("motive '01' is the authority's code for a cancellation that is replaced by another folio, "
                        + "and V112 closes the vocabulary: every code the SAT's cancelación.xsd enumerates is "
                        + "accepted")
                .isEmpty();

        var motiveSale = fixtures.seedSale("V108-F4-MOTIVE");
        var unsupported = insertStamped(motiveSale);
        assertThat(refusalOf(cancellationRow(motiveSale.company(), unsupported, "'05'")))
                .as("V112: a fifth motive is refused. Neither the SAT's cancelación.xsd (a required `Motivo` over "
                        + "`01`–`04`) nor the vendor's catalogue declares one, so accepting it here would be a "
                        + "code this tree invented — and a wrong closed vocabulary refuses a legal cancellation "
                        + "while admitting an illegal one")
                .contains("ck_fiscal_document_cancellation_motive");

        assertThat(refusalOf(cancellationRow(company, stamped, "'02'")))
                .as("and a document is cancelled once: two cancellation rows would be two contradictory "
                        + "statements about one UUID")
                .contains("uq_fiscal_document_cancels");

        var foreign = insertStamped(otherSale);
        assertThat(refusalOf(cancellationRow(company, foreign, "'01'")))
                .as("the pointer is composite over (company_id, id) because a referential check bypasses row "
                        + "security: an id-only key would let one tenant cancel another tenant's document and "
                        + "no policy could see it")
                .contains("fk_fiscal_document_cancels");
    }

    @Test
    @DisplayName("a DRAFT cannot change its company, its source_type or its source_id")
    void refusesAChangeOfIdentityOnADraft() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var company = fixtures.seedCompany("V108-IDENTITY");
        var other = fixtures.seedCompany("V108-IDENTITY-OTHER");
        var draft = insertDraft(company, UUID.randomUUID());

        assertThat(refusalOf("UPDATE fiscal_document SET company_id = '" + other + "' WHERE id = '" + draft + "'"))
                .as("a draft is mutable, its identity is not: moving a document to another tenant is not a "
                        + "transition")
                .contains("change of identity");
        assertThat(refusalOf("UPDATE fiscal_document SET source_id = '" + UUID.randomUUID() + "' WHERE id = '" + draft
                        + "'"))
                .as("nor to another sale: re-pointing it would leave the authority's UUID describing a "
                        + "different sale")
                .contains("change of identity");
        assertThat(refusalOf("UPDATE fiscal_document SET source_type = 'CREDIT_NOTE' WHERE id = '" + draft + "'"))
                .as("nor to another source kind, which is a value of the closed vocabulary and not a rewrite "
                        + "of the pointer")
                .contains("change of identity");
        assertThat(read("SELECT company_id::text || '/' || source_type || '/' || source_id::text "
                        + "FROM fiscal_document WHERE id = '" + draft + "'"))
                .as("and nothing moved: all three refusals left the row as it was")
                .isEqualTo(company + "/SALES_ORDER/"
                        + read("SELECT source_id FROM fiscal_document WHERE id = '" + draft + "'"));
    }

    // --- S12/L9(i): the vocabularies live in the schema, and they are read where they live ---

    @Test
    @DisplayName("ck_fiscal_document_status names exactly DRAFT, STAMPED and CANCELLED, and "
            + "ck_fiscal_document_source_type names SALES_ORDER")
    void statesItsClosedVocabulariesInNamedChecks() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(checkValues("ck_fiscal_document_status"))
                .as("L9(i): the enums arrive with A3's first reader, and the vocabulary is verified here, "
                        + "where it lives — an enum nothing reads would be exactly the unused code the audit in "
                        + "docs/ flags")
                .isEqualTo("CANCELLED,DRAFT,STAMPED");
        assertThat(checkValues("ck_fiscal_document_source_type"))
                .as("`source_type` names the table `source_id` points at, so an open vocabulary there would let "
                        + "a row name a table no writer of this tree loads")
                .isEqualTo("SALES_ORDER");
    }

    // --- migrations ---

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 0 and not 1: delivery baselines at 0 (#722), and a scratch database is empty anyway.
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    // --- fixtures: written as the superuser, so the only refusal is the constraint under test ---

    /**
     * Every column of a {@code fiscal_document} row this suite states: {@code V108}'s identity and state, and
     * the frozen facts, place of issue, currency and money parts {@code V109} made required.
     */
    private static final String DOCUMENT_COLUMNS = "id, company_id, source_type, source_id, status, uuid_fiscal,"
            + " stamped_at, issued_by, cancels_fiscal_document_id, cancellation_motive,"
            + " replaces_fiscal_document_id, receptor_rfc, receptor_name, receptor_regimen, receptor_zip_code,"
            + " receptor_uso_cfdi, emisor_rfc, emisor_name, emisor_regimen, tipo_comprobante, metodo_pago,"
            + " forma_pago, exportacion, lugar_expedicion, currency, subtotal, discount_total, tax_transferred,"
            + " tax_withheld, total_amount";

    /**
     * One {@code fiscal_document} row with every column {@code V108} and {@code V109} make required.
     *
     * <p><b>The frozen identity is a literal of the fixture.</b> No rule in this suite reads the receiver or the
     * issuer: {@code V109} made those eight columns, the place of issue and the currency {@code NOT NULL} so
     * that a document cannot state nothing about the parties it names, and a row that omitted any of them would
     * make every case below fail on the same {@code NOT NULL} instead of on the rule it pins.</p>
     *
     * <p><b>The money is the fixture sale's on a stamped row and zero elsewhere, and that is not a shortcut.</b>
     * A {@code STAMPED} row is judged at commit against the sale its {@code source_id} names
     * ({@code trg_fiscal_document_snapshot_matches_sale}, {@code V109}), so a stamped fixture stating numbers no
     * sale agrees with would be refused for a reason that has nothing to do with the rule under test. A
     * {@code DRAFT}, a {@code CANCELLED} row and the rows refused for their shape never reach that judgement —
     * the trigger's {@code WHEN} names the stamped status — and zero is a state {@code ck_fiscal_document_total}
     * accepts, which is what a draft that states nothing yet is.</p>
     */
    private String documentRow(
            UUID document,
            UUID company,
            UUID sourceId,
            String status,
            String uuidFiscal,
            String stampedAt,
            String cancels,
            String motive) {
        var money = "STAMPED".equals(status)
                ? SaleFixtures.SALE_SUBTOTAL + ", " + SaleFixtures.SALE_DISCOUNT + ", " + SaleFixtures.SALE_TAX_TOTAL
                        + ", 0.0000, " + SaleFixtures.SALE_TOTAL
                : "0.0000, 0.0000, 0.0000, 0.0000, 0.0000";
        return "INSERT INTO fiscal_document (" + DOCUMENT_COLUMNS + ") VALUES ('" + document + "', '" + company
                + "', 'SALES_ORDER', '" + sourceId + "', '" + status + "', " + uuidFiscal + ", " + stampedAt
                + ", 'V108', " + cancels + ", " + motive + ", NULL, 'REC010101AB1', 'V108 Receiver SA de CV',"
                + " '601', '06700', 'G03', 'V108010101AB1', 'V108 Issuer SA de CV', '601', 'I', 'PUE', '03',"
                + " '01', '06700', '" + SaleFixtures.CURRENCY + "', " + money + ")";
    }

    /** A stamped row for a real sale, stating the sale's numbers and a fresh UUID of its own. */
    private String stampedRow(UUID document, SaleFixtures.Sale sale) {
        return stampedRow(document, sale, UUID.randomUUID());
    }

    /** A stamped row whose {@code uuid_fiscal} the caller chooses, which is what the F3 case moves. */
    private String stampedRow(UUID document, SaleFixtures.Sale sale, UUID uuidFiscal) {
        return documentRow(
                document, sale.company(), sale.sale(), "STAMPED", "'" + uuidFiscal + "'", "now()", "NULL", "NULL");
    }

    /**
     * A draft, whose {@code source_id} may name no sale at all: the judgement reads only a stamped row, and a
     * draft naming a sale nobody wrote is a state the trigger deliberately does not look at.
     */
    private String draftRow(UUID document, UUID company, UUID sourceId) {
        return documentRow(document, company, sourceId, "DRAFT", "NULL", "NULL", "NULL", "NULL");
    }

    /**
     * A cancellation row: a new document naming the one it cancels, the motive, and no UUID of its own — the
     * UUID the authority cancels belongs to the cancelled row (F4).
     */
    private String cancellationRow(UUID company, UUID cancels, String motive) {
        return documentRow(
                UUID.randomUUID(),
                company,
                UUID.randomUUID(),
                "CANCELLED",
                "NULL",
                "NULL",
                "'" + cancels + "'",
                motive);
    }

    /** Inserts a stamped row for a real sale and returns its id: a refusal is the case's own assertion. */
    private UUID insertStamped(SaleFixtures.Sale sale) throws SQLException {
        return insertStamped(sale, UUID.randomUUID());
    }

    private UUID insertStamped(SaleFixtures.Sale sale, UUID uuidFiscal) throws SQLException {
        var document = UUID.randomUUID();
        execute(stampedRow(document, sale, uuidFiscal));
        return document;
    }

    private UUID insertDraft(UUID company, UUID sourceId) throws SQLException {
        var document = UUID.randomUUID();
        execute(draftRow(document, company, sourceId));
        return document;
    }

    // --- the scratch database's own administration ---

    private String adminJdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    private String jdbcUrlFor(String name) {
        var base = POSTGRES.getJdbcUrl();
        var queryStart = base.indexOf('?');
        var withoutQuery = queryStart < 0 ? base : base.substring(0, queryStart);
        var suffix = queryStart < 0 ? "" : base.substring(queryStart);
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + name + suffix;
    }

    /** One statement as the container's superuser; a refusal is the exception the caller did not expect. */
    private void execute(String sql) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Runs the statements as <b>one</b> transaction, which is what the sale fixture needs: {@code V68}'s
     * {@code assert_document_transition_recorded} is {@code DEFERRABLE INITIALLY DEFERRED}, so a sale written in
     * autocommit and its creation row written after it is a commit refused for a reason that has nothing to do
     * with the case.
     */
    private void executeInOneTransaction(String... statements) throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (var sql : statements) {
                    statement.execute(sql);
                }
            }
            connection.commit();
        }
    }

    /**
     * Runs {@code sql} as the container's superuser and returns the refusal's message, or an empty string when
     * it was accepted.
     *
     * <p>The superuser and not the owner on purpose: row security is bypassed for a superuser, so the only
     * refusal a statement can get here is the constraint or the trigger under test. As the owner, {@code FORCE}
     * would have refused the write for having no tenant scope and the case would have passed for the wrong
     * reason.</p>
     *
     * <p>A {@code String} and not the {@code SQLException} itself: {@code SQLException} implements
     * {@code Iterable<Throwable>}, so {@code assertThat(exception)} resolves to both the {@code Iterable} and
     * the {@code Object} overload and does not compile.</p>
     */
    private String refusalOf(String sql) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            try {
                statement.execute(sql);
                return "";
            } catch (SQLException refused) {
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
        }
    }

    /** A one-row, one-column read as the superuser: what the schema holds, with no policy in the way. */
    private String read(String sql) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * The values a named {@code CHECK} admits, read from {@code pg_get_constraintdef} and not counted by name:
     * a constraint under the right name holding the wrong values passes every reader and refuses nothing
     * useful.
     */
    private String checkValues(String constraint) throws SQLException {
        return read("SELECT string_agg(m[1], ',' ORDER BY m[1]) FROM pg_constraint k, "
                + "regexp_matches(pg_get_constraintdef(k.oid), '''([A-Z_]+)''', 'g') AS m "
                + "WHERE k.conname = '" + constraint + "' AND k.conrelid = 'fiscal_document'::regclass");
    }

    /** Runs {@code sql} as the pool's role and returns the refusal's message; empty when accepted. */
    private String refusalAsApp(String sql) throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // SET ROLE and not a second login: the app role's password belongs to the container's fixture and
            // a superuser may assume any role, so the statement runs with exactly the app's privileges.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + APP_ROLE);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return "";
            } catch (SQLException refused) {
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
        }
    }

    /**
     * Runs {@code sql} as the pool's role inside {@code company}'s scope and returns the rows it touched.
     *
     * <p>The scope is set because the policy applies to the app role: without it the statement would match no
     * row and "the update was allowed" would be indistinguishable from "the update found nothing", which is
     * the silent half of a grant assertion.</p>
     */
    private int executeAsApp(String sql, UUID company) throws SQLException {
        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + APP_ROLE);
                statement.execute("SELECT set_config('app.company_id', '" + company + "', false)");
            }
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        }
    }
}
