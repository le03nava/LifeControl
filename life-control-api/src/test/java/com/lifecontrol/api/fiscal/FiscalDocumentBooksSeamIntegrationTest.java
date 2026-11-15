package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seam with the books: a fiscal document never creates revenue ({@code S14} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L15}(e) of the same record, and
 * {@code docs/adr/0008-accounting-is-a-projection.md}).
 *
 * <p><b>Why the assertion is the entry set and not the poster.</b> {@code S14} is a claim about what the book
 * says after a stamp, so the case reads {@code account_entry} and {@code account_entry_line} directly — through
 * JDBC, as this tree's accounting suites do — records them, stamps the sale's fiscal document with the fake
 * provider, and asserts the set is <b>identical</b>: no entry added, no amount moved, no date restated. A case
 * that inspected the poster's code would prove what it was written to do; this one proves what the rows say.</p>
 *
 * <p><b>Why the projection has to be made to run before the stamp.</b> Revenue posts from the sale's own
 * completion transition ({@code V100}'s {@code trg_status_transition_post_revenue}), a deferred trigger, so the
 * fixture's transition is judged by {@link #settle()} — the same {@code SET CONSTRAINTS ALL IMMEDIATE} pass a
 * commit would run. Without it the "before" set would be empty and an entry the stamp wrote would look like the
 * projection's.</p>
 *
 * <p><b>Why the negative case is a sale that is never invoiced.</b> {@code S14} has a second half: a sale no
 * document names is still a book entry, because the fiscal document is not where revenue comes from. The case
 * asserts exactly the projection's entry and no row of the book that cites a document, which is what "the books
 * describe what happened commercially" means when the alternative — a fiscal document that moves them — would
 * have to be visible as an entry the stamp wrote.</p>
 */
@SpringBootTest
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("The fiscal document never creates revenue (S14)")
class FiscalDocumentBooksSeamIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt the stamped case states, so its document is the one its attempt wrote. */
    private static final String ATTEMPT = "books-seam-2026-11-15-01";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void resetTheDouble() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S14/L15(e): stamping a sale's fiscal document leaves every entry and every amount of the book "
            + "exactly as the accounting projection posted them")
    void stampingADocumentMovesNothingInTheBook() {
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        settle();

        var before = postedEntriesOf(seed.company(), seed.sale());
        assertThat(before)
                .as("the projection posted the sale's revenue before any document existed")
                .isNotEmpty();

        var stamp = stamper.stamp(seed.sale(), ATTEMPT);
        settle();

        assertThat(stamp.status()).isEqualTo("STAMPED");
        assertThat(postedEntriesOf(seed.company(), seed.sale()))
                .as("S14: the document is not where revenue comes from, so the stamp adds no entry, moves no "
                        + "amount and restates no date — the sale was already booked")
                .isEqualTo(before);
        assertThat(entryCount(seed.company()))
                .as("and no other entry of the company appeared either, whatever its posting")
                .isEqualTo(before.stream().map(Posted::entryId).distinct().count());
    }

    @Test
    @DisplayName("S14: a completed sale that is never invoiced keeps exactly the entries the projection posted, "
            + "and none of them cites a document")
    void aSaleThatIsNeverInvoicedKeepsTheProjectionsEntries() {
        // Its own company and its own settled book, because the tenant scope of this transaction is the one
        // statement that cannot describe two companies at once: a case that seeded a second sale after settling
        // the first would have that sale judged under the wrong tenant.
        var neverInvoiced = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        settle();

        var posted = postedEntriesOf(neverInvoiced.company(), neverInvoiced.sale());
        assertThat(posted)
                .as("one revenue entry, with the receivable, the revenue and the tax as its three parts")
                .hasSize(3);
        assertThat(posted.stream().map(Posted::posting).distinct().toList()).containsExactly("REVENUE");
        assertThat(documentCount(neverInvoiced.company()))
                .as("the sale was never invoiced, which is the premise of the case")
                .isZero();
        assertThat(entryCount(neverInvoiced.company()))
                .as("the book of a sale nobody invoiced is the projection's and nothing else")
                .isEqualTo(1);
        assertThat(entriesCitingADocument(neverInvoiced.company()))
                .as("S14: no entry names a fiscal document as its source, so the books cannot have been moved "
                        + "by one")
                .isZero();
    }

    // ──────────────────────────────────────
    // the reads
    // ──────────────────────────────────────

    /** One posted line as the suite reads it back: the entry, its posting, the account by code and the amount. */
    private record Posted(UUID entryId, String posting, String accountCode, String debit, String credit) {}

    /**
     * Every posted line the sale's book carries, oldest first, fingerprinted as one comparable list.
     *
     * <p>The revenue entry anchors on the completion <b>transition</b> and not on the sale — that is {@code V100}
     * S11 — so the join goes through {@code status_transition}, which is the row the entry names. Reading the
     * entry by the sale instead would answer zero rows and make "nothing moved" true for the wrong reason.</p>
     */
    private List<Posted> postedEntriesOf(UUID companyId, UUID saleId) {
        return jdbc.query(
                """
                SELECT e.id, e.posting, a.code, l.debit, l.credit
                  FROM account_entry e
                  JOIN status_transition t
                    ON t.company_id = e.company_id AND t.id = e.source_id
                   AND e.source_type = 'STATUS_TRANSITION'
                  JOIN account_entry_line l
                    ON l.company_id = e.company_id AND l.entry_id = e.id
                  JOIN account a
                    ON a.company_id = l.company_id AND a.id = l.account_id
                 WHERE t.company_id = ? AND t.document_type = 'SALES_ORDER' AND t.document_id = ?
                 ORDER BY e.id, a.code
                """,
                (result, index) -> new Posted(
                        result.getObject("id", UUID.class),
                        result.getString("posting"),
                        result.getString("code"),
                        result.getBigDecimal("debit").toPlainString(),
                        result.getBigDecimal("credit").toPlainString()),
                companyId,
                saleId);
    }

    private long entryCount(UUID companyId) {
        return jdbc.queryForObject("SELECT count(*) FROM account_entry WHERE company_id = ?", Long.class, companyId);
    }

    private long documentCount(UUID companyId) {
        return jdbc.queryForObject("SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, companyId);
    }

    private long entriesCitingADocument(UUID companyId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM account_entry e
                  JOIN fiscal_document d
                    ON d.company_id = e.company_id AND d.id = e.source_id
                 WHERE e.company_id = ?
                """, Long.class, companyId);
    }

    /**
     * Runs the deferred constraint triggers now, which is the pass the commit would run.
     *
     * <p>Two of them matter here: {@code V100}'s revenue poster, which is what makes the "before" set exist, and
     * {@code V109}'s comparison of a stamped document with its sale, which a case that stamps has to face in the
     * open instead of at a rollback that never happens.</p>
     */
    private void settle() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
}
