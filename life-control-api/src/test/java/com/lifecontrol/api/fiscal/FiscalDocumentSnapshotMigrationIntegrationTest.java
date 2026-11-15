package com.lifecontrol.api.fiscal;

import static com.lifecontrol.api.support.SaleFixtures.CURRENCY;
import static com.lifecontrol.api.support.SaleFixtures.FIRST_LINE_TAX;
import static com.lifecontrol.api.support.SaleFixtures.SALE_DISCOUNT;
import static com.lifecontrol.api.support.SaleFixtures.SALE_SUBTOTAL;
import static com.lifecontrol.api.support.SaleFixtures.SALE_TAX_TOTAL;
import static com.lifecontrol.api.support.SaleFixtures.SALE_TOTAL;
import static com.lifecontrol.api.support.SaleFixtures.SECOND_LINE_TAX;
import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.SaleFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code V109__fiscal_document_snapshot.sql}: what the fiscal document states, and the judgement that keeps
 * it honest against the sale it derives from (S8 and S9 of {@code odd/tasks/cfdi-fiscal-document.md}; T5,
 * decisions L9(e)–L9(h)).
 *
 * <p><b>Why a scratch database, and why this suite builds real sales.</b> The subject of the file is a
 * comparison between <b>two documents</b>: the fiscal one and the sale it names. A fixture that fabricated
 * the sale's amounts would be comparing the document with the constants this suite just wrote, which is the
 * one thing the comparison must not be able to do. So every case below seeds a company, a store, a product,
 * a variant, a sale with two lines and their history rows, and reads the sale's own numbers back — the same
 * chain {@code PurchaseOrderMoneyMigrationIntegrationTest} builds for the same reason, and it now lives in
 * {@link SaleFixtures} because the sibling schema suite needs it too: since {@code V109} a stamped row is
 * judged at commit against the sale its {@code source_id} names, so a stamped fixture names a real sale
 * wherever it is written. The chain runs to {@code 108} first — the head T4 leaves, where the document's
 * columns do not exist — and then to head, so the two halves are the before and the after.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}.</b> {@code V52} calls {@code ENABLE} <b>and
 * {@code FORCE}</b> on every table it arms, so the policy applies to the table's <b>owner</b> — the role
 * delivery migrates with. The container's own user is a superuser and bypasses row security, so a migration
 * asserted under it would report success while delivery's {@code NOSUPERUSER} owner matched nothing. That is
 * also why the fixtures and the refusals below are written as that superuser: row security is bypassed there,
 * so the only refusal a statement can get is the constraint or the trigger under test, and never the policy
 * refusing a write for having no tenant scope.</p>
 *
 * <p><b>Everything is judged at {@code COMMIT}.</b> {@code trg_fiscal_document_snapshot_matches_sale} is a
 * {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger, and the sale's own history is checked by
 * {@code V68}'s deferred pair, so a case that expects a refusal gets it from {@code commit()} and not from
 * the statement it wrote. {@link #refusalOf} therefore commits, and the accepted cases are the same helper
 * returning an empty message — which also means every acceptance below is an acceptance of the whole
 * transaction, deferred checks included.</p>
 *
 * <p>No Spring context: this suite is JDBC against a scratch database, like the sibling schema suites.</p>
 */
@DisplayName("Fiscal document snapshot (V109)")
class FiscalDocumentSnapshotMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: T4 created the three tables and nothing states what they hold. */
    private static final String PRE_UNIT_HEAD = "108";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    // The currency, the sale's four money parts and its two lines' derived amounts are imported from
    // `SaleFixtures` (see the static imports above): the sale a stamped document is compared with is built
    // once, and a suite that restated its amounts here would be comparing the document against its own literals.

    private String databaseName;
    private String jdbcUrl;

    /**
     * The one sale fixture, shared with {@code FiscalDocumentSchemaMigrationIntegrationTest} (see
     * {@link SaleFixtures}).
     */
    private SaleFixtures fixtures;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_fiscal_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + OWNER);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
        fixtures = new SaleFixtures(this::executeAccepted, this::read);
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

    // --- S8: the document's facts are the sale's, within one minor unit ---

    @Test
    @DisplayName("a stamped document whose lines and totals are the sale's is accepted at commit")
    void acceptsAStampedDocumentThatStatesItsSale() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V109");
        var document = UUID.randomUUID();

        assertThat(refusalOf(stampedDocumentWithLines(document, sale, SALE_TOTAL)))
                .as("S8: the document's four parts and every line's five amounts are the sale's, so the "
                        + "judgement at commit has nothing to report")
                .isEmpty();

        assertThat(count("SELECT count(*) FROM fiscal_document WHERE id = '" + document + "' AND status = "
                        + "'STAMPED'"))
                .as("and the document is really there: an accepted commit is not an empty transaction")
                .isEqualTo(1);
        assertThat(read("SELECT subtotal::text || '/' || total_amount::text FROM fiscal_document WHERE id = '"
                        + document + "'"))
                .as("the numbers it states are the ones it was given, not ones a trigger re-derived: the "
                        + "document is a snapshot and nothing in this file writes its money")
                .isEqualTo(SALE_SUBTOTAL + "/" + SALE_TOTAL);
    }

    @Test
    @DisplayName("a line whose tax differs from the sale line's by more than one minor unit is refused naming "
            + "the line, and one differing by exactly one minor unit is accepted")
    void refusesALineOutsideTheMinorUnitAndAcceptsTheBoundary() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V109");
        var refusedDocument = UUID.randomUUID();
        var refusedLine = UUID.randomUUID();
        // The sale line states 30.4000; this document line states 30.4200, two minor units of MXN apart.
        var refused = refusalOf(stampedDocumentWithFirstLineTax(refusedDocument, refusedLine, sale, "30.4200"));

        assertThat(refused)
                .as("S8: one minor unit is the whole tolerance, so two is a document that contradicts the "
                        + "sale it derives from")
                .contains("fiscal_document_line")
                .contains(refusedLine.toString())
                .contains(sale.firstLine().toString())
                .contains("30.4200")
                .contains(FIRST_LINE_TAX);
        assertThat(count("SELECT count(*) FROM fiscal_document WHERE source_id = '" + sale.sale() + "'"))
                .as("and the refusal leaves no stamped document behind: the deferred judgement aborts the "
                        + "whole transaction, which is why a failed stamp is a draft and never a half-written "
                        + "document")
                .isZero();

        // The boundary, from the accepting side, on the same sale: the refusal above rolled the transaction
        // back, so the sale still has no document competing with this one. 30.4100 is exactly 0.01 — one
        // minor unit of MXN — away from the sale's 30.4000.
        assertThat(refusalOf(stampedDocumentWithFirstLineTax(UUID.randomUUID(), UUID.randomUUID(), sale, "30.4100")))
                .as("one minor unit is inside the tolerance S8 grants, and the sale's rounding has no column "
                        + "on the authority's document (L9(g)) — a comparison that refused this would refuse "
                        + "every invoice of a sale with a rounding adjustment")
                .isEmpty();
    }

    @Test
    @DisplayName("a document whose total differs from the sale's by more than one minor unit is refused, and "
            + "one within that unit is accepted")
    void refusesADocumentTotalOutsideTheMinorUnitAndAcceptsTheBoundary() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V109");
        var refusedDocument = UUID.randomUUID();
        // The four parts still satisfy ck_fiscal_document_total and the other three still equal the sale's:
        // withholding two minor units moves the total by exactly two, which is the comparison this case is
        // about and nothing else.
        var refused = refusalOf(stampedDocumentWithWithheld(refusedDocument, sale, "0.0200"));

        assertThat(refused)
                .as("the document's total is the sum of its parts, and those parts are the sale's — a total "
                        + "that is not is a document stating money no other row of this tree agrees with")
                .contains("total_amount")
                .contains(sale.sale().toString());
        assertThat(count("SELECT count(*) FROM fiscal_document WHERE source_id = '" + sale.sale() + "'"))
                .as("and nothing was stored: the judgement runs at commit, before any row survives it")
                .isZero();

        assertThat(refusalOf(stampedDocumentWithWithheld(UUID.randomUUID(), sale, "0.0100")))
                .as("one minor unit of withholding is one minor unit of total, which is inside the tolerance")
                .isEmpty();
    }

    @Test
    @DisplayName("a stamped document naming a sale that does not exist is refused, because the pair carries "
            + "no foreign key and the stamp is where it is checked")
    void refusesADocumentThatNamesASaleNobodyCanRead() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var company = fixtures.seedCompany("V109-orphan");
        var orphanSale = UUID.randomUUID();
        var document = "INSERT INTO fiscal_document (id, company_id, source_type, source_id, status, uuid_fiscal,"
                + " stamped_at, issued_by, receptor_rfc, receptor_name, receptor_regimen, receptor_zip_code,"
                + " receptor_uso_cfdi, emisor_rfc, emisor_name, emisor_regimen, tipo_comprobante, metodo_pago,"
                + " forma_pago, exportacion, lugar_expedicion, currency, subtotal, discount_total,"
                + " tax_transferred, tax_withheld, total_amount) VALUES ('" + UUID.randomUUID() + "', '" + company
                + "', 'SALES_ORDER', '" + orphanSale + "', 'STAMPED', '" + UUID.randomUUID() + "', now(), 'V109',"
                + " 'REC010101AB1', 'V109 Receiver', '601', '06700', 'G03', 'V109010101AB1', 'V109 Issuer', '601',"
                + " 'I', 'PUE', '03', '01', '06700', '" + CURRENCY + "', 100.0000, 0.0000, 0.0000, 0.0000,"
                + " 100.0000)";

        assertThat(refusalOf(document))
                .as("`source_id` carries no foreign key by design (V108): the pair is polymorphic, so the pointer "
                        + "is checked where it is read, and a stamp is the last moment it can be")
                .contains("no such sale exists")
                .contains(orphanSale.toString());
    }

    @Test
    @DisplayName("a document line naming another sale's line is refused even when its amounts agree with it")
    void refusesALineOfAnotherSaleEvenWhenTheAmountsAgree() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var tree = fixtures.seedTree("V109");
        var first = fixtures.seedSale(tree, 1);
        var second = fixtures.seedSale(tree, 2);
        var document = UUID.randomUUID();
        var crossLine = UUID.randomUUID();

        // Both sales state the same numbers, so every amount below agrees with the line it names: the only
        // thing wrong is that the line belongs to the other sale, which neither pointer can say on its own.
        assertThat(refusalOf(
                        stampedDocument(document, first, SALE_TOTAL),
                        firstLineForItem(crossLine, tree.company(), document, second.firstLine(), FIRST_LINE_TAX),
                        secondLineStatement(UUID.randomUUID(), document, first, SECOND_LINE_TAX)))
                .as("S8 compares two documents, so a line of a third one is a comparison that means nothing: the "
                        + "document names the sale and the line names the sale line, and the pair is only "
                        + "complete inside the judgement")
                .contains(crossLine.toString())
                .contains(second.firstLine().toString())
                .contains(first.sale().toString())
                .contains(second.sale().toString());
    }

    @Test
    @DisplayName("a DRAFT document states whatever it likes, because the judgement reads only a STAMPED row")
    void acceptsADraftThatStatesNothingTheSaleDoes() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        var sale = fixtures.seedSale("V109");
        var draft = UUID.randomUUID();

        assertThat(refusalOf(draftDocument(draft, sale.company(), sale.sale(), "1.0000")))
                .as("the WHEN clause names the stamped status: a draft is the attempt, and an attempt is not "
                        + "a document — a judgement that read one would refuse the very state L9(b) keeps "
                        + "mutable for a failed stamp")
                .isEmpty();
        assertThat(read("SELECT status || '/' || subtotal::text FROM fiscal_document WHERE id = '" + draft + "'"))
                .as("and the draft is stored exactly as it was written, with no sale to compare it against")
                .isEqualTo("DRAFT/1.0000");
    }

    // --- L9(h): one judgement at commit, not one per row ---

    @Test
    @DisplayName("the judgement is one deferred constraint trigger, so a stamp writes the document and its "
            + "lines in either order and the sale itself may be written after them")
    void judgesTheDocumentOnceAtCommitAndNotPerRow() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(read("SELECT tgdeferrable::text || '/' || tginitdeferred::text || '/' ||"
                        + " (tgconstraint <> 0)::text || '/' || c.relname || '/' || p.proname"
                        + " FROM pg_trigger t"
                        + " JOIN pg_class c ON c.oid = t.tgrelid"
                        + " JOIN pg_proc p ON p.oid = t.tgfoid"
                        + " WHERE t.tgname = 'trg_fiscal_document_snapshot_matches_sale' AND NOT t.tgisinternal"))
                .as("deferrable, initially deferred, a constraint trigger, on the document table and calling "
                        + "the comparison: a plain AFTER trigger under the same name would judge each row as it "
                        + "is written and refuse a stamp that has not finished writing itself")
                .isEqualTo("true/true/true/fiscal_document/fiscal_document_snapshot_matches_sale");

        // Half one: the sale does not exist yet when the stamped document and its lines are written. An
        // immediate judgement would find no sale and raise at the first statement; the deferred one reads the
        // committed transaction, where the sale is there.
        var tree = fixtures.seedTree("V109");
        var sale = UUID.randomUUID();
        var firstLine = UUID.randomUUID();
        var secondLine = UUID.randomUUID();
        var document = UUID.randomUUID();
        var statements = new ArrayList<>(List.of(stampedDocument(
                document,
                new SaleFixtures.Sale(tree.company(), tree.store(), sale, firstLine, secondLine),
                SALE_TOTAL)));
        statements.addAll(fixtures.saleStatements(tree, sale, firstLine, secondLine, 1));

        assertThat(refusalOf(statements.toArray(String[]::new)))
                .as("the document first and the sale after it, in one transaction: only a judgement taken at "
                        + "commit can see both")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM fiscal_document WHERE id = '" + document + "'"))
                .as("and the whole transaction committed")
                .isEqualTo(1);

        // Half two: the sale exists, and the document moves through the one transition the state machine has
        // — draft, then lines, then stamped — inside a single transaction.
        var saleTwo = fixtures.seedSale("V109");
        var draft = UUID.randomUUID();
        var draftStatements = new ArrayList<String>();
        draftStatements.add(documentInsert(
                draft,
                saleTwo.company(),
                saleTwo.sale(),
                "DRAFT",
                SALE_SUBTOTAL,
                SALE_DISCOUNT,
                SALE_TAX_TOTAL,
                "0.0000",
                SALE_TOTAL));
        draftStatements.add(firstLineStatement(UUID.randomUUID(), draft, saleTwo, FIRST_LINE_TAX));
        draftStatements.add(secondLineStatement(UUID.randomUUID(), draft, saleTwo, SECOND_LINE_TAX));
        draftStatements.add("UPDATE fiscal_document SET status = 'STAMPED', uuid_fiscal = '" + UUID.randomUUID()
                + "', stamped_at = now() WHERE id = '" + draft + "'");

        assertThat(refusalOf(draftStatements.toArray(String[]::new)))
                .as("the lines cannot precede the document — sales_order_item_id is NOT NULL and the pair is "
                        + "keyed (V108) — so «either order» is the two reachable ones: a stamped row before its "
                        + "lines, and a draft stamped after them")
                .isEmpty();
        assertThat(read("SELECT status FROM fiscal_document WHERE id = '" + draft + "'"))
                .isEqualTo("STAMPED");
    }

    // --- the vocabularies, read where they live ---

    @Test
    @DisplayName("the closed vocabularies are named CHECKs, the total is its own four parts, and no CHECK pins "
            + "the catalogs this tree cannot read")
    void statesItsClosedVocabulariesAndPinsNoCatalog() throws Exception {
        migrateTo(PRE_UNIT_HEAD);
        migrateToHead();

        assertThat(names("SELECT string_agg(conname, ',' ORDER BY conname) FROM pg_constraint"
                        + " WHERE conrelid = 'fiscal_document'::regclass AND contype = 'c'"))
                .as("the closed set of the document's value checks. exportacion still has none, because the "
                        + "authority's c_Exportacion value set is stated nowhere in this tree: decision 11 "
                        + "refuses to pin a catalog from memory. cancellation_motive is the one that changed "
                        + "hands — V112 pins it, because c_MotivoCancelacion was read from the SAT's "
                        + "cancelación.xsd and from the vendor's catalogue before it was written (L20(e)) — and "
                        + "it appears in this list for exactly that reason: a vocabulary that arrives has to be "
                        + "a deliberate entry here, and so does one that leaves")
                .isEqualTo("ck_fiscal_document_cancellation,ck_fiscal_document_cancellation_motive,"
                        + "ck_fiscal_document_metodo_pago,"
                        + "ck_fiscal_document_parts_not_negative,ck_fiscal_document_source_type,"
                        + "ck_fiscal_document_stamped_at,ck_fiscal_document_status,"
                        + "ck_fiscal_document_tipo_comprobante,ck_fiscal_document_total,"
                        + "ck_fiscal_document_uuid_matches_status");
        assertThat(read("SELECT string_agg(m[1], ',' ORDER BY ord)"
                        + " FROM pg_constraint c, LATERAL regexp_matches(pg_get_constraintdef(c.oid),"
                        + " '''([0-9]{2})''', 'g') WITH ORDINALITY AS r(m, ord)"
                        + " WHERE c.conrelid = 'fiscal_document'::regclass"
                        + " AND c.conname = 'ck_fiscal_document_cancellation_motive'"))
                .as("and the two copies of that catalog agree: the seven-line enum in "
                        + "FiscalCancellationMotive and the CHECK that every writer bypassing the API is judged "
                        + "by. Two mirrors free to drift is the failure this pair exists to make impossible, and "
                        + "it is the same coupling ck_companies_regimen_fiscal and its enum carry")
                .isEqualTo(String.join(",", FiscalCancellationMotive.codes()));
        assertThat(names("SELECT string_agg(conname, ',' ORDER BY conname) FROM pg_constraint"
                        + " WHERE conrelid = 'fiscal_document_line'::regclass AND contype = 'c'"))
                .as("and the line's: tax_object is absent for the same reason exportacion is, so the two "
                        + "catalog-shaped columns the authority names are the two this file leaves open. The "
                        + "coupling constraint V110 adds is not a vocabulary — it is what keeps a not-subject "
                        + "line from borrowing a factor the authority never states — and it appears here for "
                        + "the same reason the vocabularies do: a constraint that changes the shape of a line "
                        + "has to be a deliberate entry in this list")
                .isEqualTo("ck_fiscal_document_line_factor_matches_object," + "ck_fiscal_document_line_tipo_factor");

        assertThat(names("SELECT string_agg(m[1], ',' ORDER BY m[1]) FROM pg_constraint k,"
                        + " regexp_matches(pg_get_constraintdef(k.oid), '''([A-Za-z_]+)''', 'g') AS m"
                        + " WHERE k.conname = 'ck_fiscal_document_line_tipo_factor'"
                        + " AND k.conrelid = 'fiscal_document_line'::regclass"))
                .as("the three factors V103 itself just mapped, and no fourth: a factor the tree cannot "
                        + "resolve is a document line no stamp can state")
                .isEqualTo("Cuota,Exento,Tasa");
        assertThat(names("SELECT string_agg(m[1], ',' ORDER BY m[1]) FROM pg_constraint k,"
                        + " regexp_matches(pg_get_constraintdef(k.oid), '''([A-Za-z_]+)''', 'g') AS m"
                        + " WHERE k.conname = 'ck_fiscal_document_tipo_comprobante'"
                        + " AND k.conrelid = 'fiscal_document'::regclass"))
                .as("income is the only type this tree issues until a note exists — a note is a document type "
                        + "of its own, and this is where the vocabulary would have to move")
                .isEqualTo("I");
        assertThat(names("SELECT string_agg(m[1], ',' ORDER BY m[1]) FROM pg_constraint k,"
                        + " regexp_matches(pg_get_constraintdef(k.oid), '''([A-Za-z_]+)''', 'g') AS m"
                        + " WHERE k.conname = 'ck_fiscal_document_metodo_pago'"
                        + " AND k.conrelid = 'fiscal_document'::regclass"))
                .as("both methods are admitted, and PUE is the only one a sale can satisfy today: S13/T9 is "
                        + "what refuses PPD, as a validation and not as a column that cannot hold it")
                .isEqualTo("PPD,PUE");

        // ck_fiscal_document_total, read from its definition with the parentheses and the whitespace taken
        // out: a CHECK under the right name stating another arithmetic passes every reader that only looks
        // for the name.
        assertThat(names("SELECT regexp_replace(pg_get_constraintdef(oid), '[()]', '', 'g')"
                        + " FROM pg_constraint WHERE conname = 'ck_fiscal_document_total'"
                        + " AND conrelid = 'fiscal_document'::regclass"))
                .as("the authority's document totals the sum of its parts, and the parts are exactly these "
                        + "five — the equality every row of the document is held to")
                .isEqualTo("CHECK total_amount = subtotal - discount_total + tax_transferred - tax_withheld");

        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = 'fiscal_document' AND column_name IN ('rounding_adjustment',"
                        + " 'tax_total')"))
                .as("no rounding_adjustment, and no reuse of the sale's tax_total (L9(g)): the sale's rounding "
                        + "is a number the authority's schema does not accept, and the gap it leaves is "
                        + "exactly the one minor unit the comparison tolerates")
                .isZero();
    }

    // --- the sale fixture, shared with the sibling schema suite ---

    // The company/store/product/variant/sale chain, its two lines and their history rows live in
    // `com.lifecontrol.api.support.SaleFixtures`, and both fiscal migration suites use that one copy: V109's
    // judgement compares a stamped document with a real sale, so a fixture that fabricated the sale's amounts
    // would be comparing the document against the constants the suite just wrote. The cases above reach it
    // through `fixtures`, seeded in `createScratchDatabase`.

    // --- the document's statements ---

    /** The accepted stamp: the document stating the sale's numbers, and both of its lines. */
    private String[] stampedDocumentWithLines(UUID document, SaleFixtures.Sale sale, String total) {
        return new String[] {
            stampedDocument(document, sale, total),
            firstLineStatement(UUID.randomUUID(), document, sale, FIRST_LINE_TAX),
            secondLineStatement(UUID.randomUUID(), document, sale, SECOND_LINE_TAX)
        };
    }

    /** A stamped document whose first line states {@code taxAmount}, and whose second line is the sale's. */
    private String[] stampedDocumentWithFirstLineTax(
            UUID document, UUID line, SaleFixtures.Sale sale, String taxAmount) {
        return new String[] {
            stampedDocument(document, sale, SALE_TOTAL),
            firstLineStatement(line, document, sale, taxAmount),
            secondLineStatement(UUID.randomUUID(), document, sale, SECOND_LINE_TAX)
        };
    }

    /**
     * A stamped document whose total moves by withholding, so the other three parts still equal the sale's and
     * {@code ck_fiscal_document_total} still holds: withholding is the only term the sale has no counterpart
     * for (its {@code tax_total} is the document's transferred tax, and there is no withheld total on a sale).
     */
    private String[] stampedDocumentWithWithheld(UUID document, SaleFixtures.Sale sale, String withheld) {
        var total = new java.math.BigDecimal(SALE_TOTAL).subtract(new java.math.BigDecimal(withheld));
        return new String[] {
            documentInsert(
                    document,
                    sale.company(),
                    sale.sale(),
                    "STAMPED",
                    SALE_SUBTOTAL,
                    SALE_DISCOUNT,
                    SALE_TAX_TOTAL,
                    withheld,
                    total.toPlainString()),
            firstLineStatement(UUID.randomUUID(), document, sale, FIRST_LINE_TAX),
            secondLineStatement(UUID.randomUUID(), document, sale, SECOND_LINE_TAX)
        };
    }

    /** A stamped document stating the sale's four parts, with no lines of its own. */
    private String stampedDocument(UUID document, SaleFixtures.Sale sale, String total) {
        return documentInsert(
                document,
                sale.company(),
                sale.sale(),
                "STAMPED",
                SALE_SUBTOTAL,
                SALE_DISCOUNT,
                SALE_TAX_TOTAL,
                "0.0000",
                total);
    }

    /** A draft, whose whole subject is that it states numbers no sale agrees with. */
    private String draftDocument(UUID document, UUID company, UUID sale, String total) {
        return documentInsert(document, company, sale, "DRAFT", total, "0.0000", "0.0000", "0.0000", total);
    }

    /**
     * One {@code fiscal_document} row with every column {@code V109} makes required. The fiscal identities are
     * literals of the fixture: what a stamp freezes is the shape of this row, and the comparison reads none of
     * them.
     */
    private String documentInsert(
            UUID document,
            UUID company,
            UUID sale,
            String status,
            String subtotal,
            String discount,
            String taxTransferred,
            String taxWithheld,
            String total) {
        var stamped = "STAMPED".equals(status);
        return "INSERT INTO fiscal_document (id, company_id, source_type, source_id, status, uuid_fiscal,"
                + " stamped_at, issued_by, receptor_rfc, receptor_name, receptor_regimen, receptor_zip_code,"
                + " receptor_uso_cfdi, emisor_rfc, emisor_name, emisor_regimen, tipo_comprobante, metodo_pago,"
                + " forma_pago, exportacion, lugar_expedicion, currency, subtotal, discount_total,"
                + " tax_transferred, tax_withheld, total_amount) VALUES ('" + document + "', '" + company
                + "', 'SALES_ORDER', '" + sale + "', '" + status + "', "
                + (stamped ? "'" + UUID.randomUUID() + "', now()" : "NULL, NULL")
                + ", 'V109', 'REC010101AB1', 'V109 Receiver SA de CV', '601', '06700', 'G03', 'V109010101AB1',"
                + " 'V109 Issuer SA de CV', '601', 'I', 'PUE', '03', '01', '06700', '" + CURRENCY + "', "
                + subtotal + ", " + discount + ", " + taxTransferred + ", " + taxWithheld + ", " + total + ")";
    }

    /** The document's first line, naming {@code saleItem} rather than the sale's own first line. */
    private String firstLineForItem(UUID line, UUID company, UUID document, UUID saleItem, String taxAmount) {
        return lineInsert(line, company, document, saleItem, "2.000000", "100.0000", "10.0000", "200.0000", taxAmount);
    }

    /** The document's first line, stating the sale line it names as the sale line states itself. */
    private String firstLineStatement(UUID line, UUID document, SaleFixtures.Sale sale, String taxAmount) {
        return lineInsert(
                line,
                sale.company(),
                document,
                sale.firstLine(),
                "2.000000",
                "100.0000",
                "10.0000",
                "200.0000",
                taxAmount);
    }

    /** The document's second line — 3 x 50.50 with a 1.50 discount, and 24.0000 of tax. */
    private String secondLineStatement(UUID line, UUID document, SaleFixtures.Sale sale, String taxAmount) {
        return lineInsert(
                line,
                sale.company(),
                document,
                sale.secondLine(),
                "3.000000",
                "50.5000",
                "1.5000",
                "151.5000",
                taxAmount);
    }

    /**
     * One {@code fiscal_document_line} row with every column {@code V109} makes required: the snapshot S9
     * describes, which nothing reads at print time and the comparison reads against the sale line.
     */
    private String lineInsert(
            UUID line,
            UUID company,
            UUID document,
            UUID saleLine,
            String quantity,
            String unitValue,
            String discount,
            String base,
            String taxAmount) {
        return "INSERT INTO fiscal_document_line (id, company_id, fiscal_document_id, sales_order_item_id,"
                + " clave_prod_serv, clave_unidad, description, quantity, unit_value, discount, base, tax_object,"
                + " tax_code, tipo_factor, tasa_o_cuota, tax_amount) VALUES ('" + line + "', '" + company + "', '"
                + document + "', '" + saleLine + "', '01010101', 'H87', 'V109 concept', " + quantity + ", "
                + unitValue + ", " + discount + ", " + base + ", '02', 'IVA', 'Tasa', 0.160000, " + taxAmount + ")";
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

    // --- writes and reads ---

    /** Runs the statements in one transaction and commits; a refusal is the exception the caller expects. */
    private void executeAccepted(String... statements) throws SQLException {
        try (Connection connection = connect()) {
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
     * Runs the statements in one transaction and returns the message of the refusal, or an empty string when
     * the commit was accepted.
     *
     * <p>The commit is inside the {@code try} on purpose: the judgement under test is deferred, so the refusal
     * arrives at {@code COMMIT} and not at the statement that wrote the row. A helper that returned at the last
     * statement would report every case below as accepted.</p>
     */
    private String refusalOf(String... statements) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (var sql : statements) {
                        statement.execute(sql);
                    }
                }
                connection.commit();
                return "";
            } catch (SQLException refused) {
                connection.rollback();
                return refused.getMessage() == null ? refused.getSQLState() : refused.getMessage();
            }
        }
    }

    /** One read as the container's superuser: what the schema holds, with no policy in the way. */
    private String read(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * A one-row read whose {@code NULL} is an answer: a constraint definition or an aggregate over nothing is
     * read for its absence as often as for its text.
     */
    private String names(String sql) throws SQLException {
        return read(sql);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
