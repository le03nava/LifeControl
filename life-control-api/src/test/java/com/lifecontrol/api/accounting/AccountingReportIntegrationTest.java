package com.lifecontrol.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.dto.GrossMarginDocumentResponse;
import com.lifecontrol.api.accounting.dto.GrossMarginDocumentsPage;
import com.lifecontrol.api.accounting.dto.GrossMarginStoreResponse;
import com.lifecontrol.api.accounting.dto.SalesJournalEntryResponse;
import com.lifecontrol.api.accounting.dto.SalesJournalLineResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceRowResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceTotalResponse;
import com.lifecontrol.api.accounting.exception.InvalidAccountingRangeException;
import com.lifecontrol.api.accounting.service.AccountingReportService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Verification of the three read models of {@code S18} — the sales journal, the trial balance and the gross
 * margin by document and by store (T9, T10 of {@code odd/tasks/derived-accounting.md}, decisions recorded as
 * {@code L15}) — against real PostgreSQL.
 *
 * <p><b>Why a database, and why this one.</b> The reports are plain SQL under the tenant's row-level security,
 * and every fact below is produced by the {@code V100} triggers rather than written by the fixture: the
 * journal's three postings, the cost entry's source and the two money sums all exist only because a real
 * completion transition, a real payment row and a real allocation fired them. A fixture that inserted entries
 * directly would prove the report's arithmetic and nothing about the rows it will read in production — and the
 * one thing that most needs proving here is exactly the join the cost entry does <b>not</b> state: the cost
 * names a stock movement, and the movement names a sales line.</p>
 *
 * <p><b>Which role, and why the scope is stated by hand.</b> The pool logs in as {@code lifecontrol_app}, so
 * every statement runs as the role the policies apply to. The suite is {@code @Transactional} and each case
 * states its tenant with {@link TenantFixtures#stateTenantWithinTheOpenTransaction}, because the reports are
 * {@code @Transactional(readOnly = true)} and join the open transaction: that join is what makes the read reuse
 * the connection whose session carries the scope. Nothing is committed — the two entry tables are append-only,
 * so a committed fixture would outlive the class and be read by sixty sibling suites that never asked for
 * it.</p>
 *
 * <p><b>What each clause of the spec observes.</b> {@code L15(b)}'s gate by
 * {@link #aCallerWhoseClaimDoesNotCarryTheCompanyIsDenied()}; {@code L15(d)}'s inclusive business dates by
 * {@link #theJournalStatesEveryEntryOfTheRangeAndOmitsWhatFallsOutsideIt()} and the refusal of an inverted
 * range by {@link #aRangeThatEndsBeforeItStartsIsRefusedNamingBothDates()}; {@code L15(c)}'s currency key by
 * {@link #theTrialBalanceGroupsByCurrencyAndBalancesInEachOne()}; {@code S15} by
 * {@link #aDocumentsCostThatNeverPostedIsUnknownAndCounted()} and
 * {@link #aStoreBucketWithOneUnknownDocumentReportsNoMargin()}; the margin itself by
 * {@link #aDocumentsMarginIsItsRevenueMinusTheCostTheLedgerAllocated()}; the paging of the per-document report
 * and the range scope of its unknown-cost count by
 * {@link #theDocumentMarginIsPagedAndTheUnknownCostCountIsRangeScoped()}; {@code S18}'s scope by
 * {@link #anotherCompanysEntriesNeverAppearInThisCompanysReport()} and
 * {@link #aReportReadWithNoTenantScopeReturnsNothing()}.</p>
 *
 * <p><b>The store bucket is its own statement and not a fold of the paged document rows</b>, so the case that
 * pins the unknown-cost rule of a bucket
 * ({@link #aStoreBucketWithOneUnknownDocumentReportsNoMargin()}) is the same case that pinned it when the
 * buckets were folded in Java: it passed unchanged across that rewrite, which is the evidence that the rule
 * survived the change rather than being restated for it.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Accounting report integration tests (S18)")
class AccountingReportIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The currency the fixtures' companies operate in until a case changes it. */
    private static final String MXN = "MXN";

    /** The currency of the one case that proves the reports never add two of them together. */
    private static final String USD = "USD";

    /** The eight accounts of the seeded chart, as {@code code|name|kind} in the order {@code V98} states them. */
    private static final List<String> CHART = List.of(
            "1101|Caja|ASSET",
            "1102|Bancos|ASSET",
            "1201|Cuentas por cobrar|ASSET",
            "1202|IVA acreditable|ASSET",
            "1301|Inventario|ASSET",
            "2101|IVA trasladado|LIABILITY",
            "4101|Ingresos por ventas|REVENUE",
            "5101|Costo de ventas|EXPENSE");

    /** The first and last day of the range every case works in unless it says otherwise. */
    private static final LocalDate OCTOBER_FIRST = LocalDate.of(2026, 10, 1);

    private static final LocalDate OCTOBER_LAST = LocalDate.of(2026, 10, 31);

    /**
     * The document sequence this suite states, one value per document: {@code uq_sales_orders_doc_identity} is
     * unique over {@code (company_id, doc_type, seq_period, seq_value)} and one number for every order would be
     * refused by the schema instead of by the rule under test.
     */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountingReportService reports;

    /** The tenant the case works on, and the one its transaction states while the fixtures are written. */
    private UUID company;

    @BeforeEach
    void bindARequestWithACaller() {
        // The service reads the request-scoped CurrentUserContext, so calling it outside a web request needs a
        // bound request for the scoped proxy to resolve — the shape `StatusTransitionRecorderIntegrationTest`
        // documents for the same reason.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        authenticateAsAdmin();
    }

    @AfterEach
    void unbindTheRequest() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────
    // T9 — the sales journal, over a range
    // ──────────────────────────────────────

    @Test
    @DisplayName("S18: the journal states the revenue, the collection and the cost of the range with their lines, "
            + "and omits the entries outside it")
    void theJournalStatesEveryEntryOfTheRangeAndOmitsWhatFallsOutsideIt() {
        var store = openBook("JOURNAL");
        var variant = variant();
        receipt(store.locationId(), variant, "3", "10", "2026-09-30 09:00:00");
        receipt(store.locationId(), variant, "4", "12", "2026-10-01 09:00:00");
        // Three facts, three dates, so the three entries are ordered by `entry_date` and not by a random id: the
        // document on the 5th, the money on the 7th, the goods on the 8th.
        var order = salesOrder(store.id(), "2026-10-05 15:30:00", "100", "16", "116", MXN);
        var item = salesOrderItem(order, variant, "5", "20", "2026-10-05 15:30:00");
        var movement = saleMovement(store.locationId(), variant, "5", "2026-10-08 17:00:00", item);
        allocate(movement);
        transition(order, "PENDING", "COMPLETED", "2026-10-05 15:35:00");
        payment(order, "CHARGE", "116", "2026-10-07 10:00:00");

        // A whole sale in September, charged and collected: three entries the October range must not print.
        var outside = salesOrder(store.id(), "2026-09-02 10:00:00", "50", "8", "58", MXN);
        transition(outside, "PENDING", "COMPLETED", "2026-09-02 10:05:00");
        payment(outside, "CHARGE", "58", "2026-09-02 10:10:00");

        settle();

        var page = reports.salesJournal(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20));

        assertThat(page.getTotalElements())
                .as("three entries inside October, and none of September's three")
                .isEqualTo(3);
        assertThat(page.getContent())
                .extracting(SalesJournalEntryResponse::posting)
                .as("ordered by (entry_date, id), which is the deterministic order the request states")
                .containsExactly("REVENUE", "COLLECTION", "COST_OF_SALES");
        assertThat(page.getContent())
                .extracting(SalesJournalEntryResponse::entryDate)
                .containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8));

        var revenue = page.getContent().get(0);
        assertThat(revenue.sourceType())
                .as("the entry names the append-only fact it was derived from, which is what a reversal names")
                .isEqualTo("STATUS_TRANSITION");
        assertThat(revenue.currency()).isEqualTo(MXN);
        assertThat(revenue.actor()).isEqualTo("suite:actor");
        assertThat(revenue.lines())
                .extracting(SalesJournalLineResponse::accountCode)
                .as("the receivable, the tax collected and the revenue, ordered by account code")
                .containsExactly("1201", "2101", "4101");
        assertLine(revenue.lines(), "1201", "Cuentas por cobrar", "116", "0");
        assertLine(revenue.lines(), "2101", "IVA trasladado", "0", "16");
        assertLine(revenue.lines(), "4101", "Ingresos por ventas", "0", "100");

        var collection = page.getContent().get(1);
        assertThat(collection.sourceType()).isEqualTo("SALE_PAYMENT");
        assertLine(collection.lines(), "1101", "Caja", "116", "0");
        assertLine(collection.lines(), "1201", "Cuentas por cobrar", "0", "116");

        var cost = page.getContent().get(2);
        assertThat(cost.sourceType())
                .as("the cost's source is the outbound movement, the row whose allocation sum it derived")
                .isEqualTo("STOCK_ALLOCATION");
        assertLine(cost.lines(), "5101", "Costo de ventas", "54", "0");
        assertLine(cost.lines(), "1301", "Inventario", "0", "54");
    }

    @Test
    @DisplayName("S18: both bounds are inclusive business dates, an absent bound is an open end, and the journal "
            + "is a page")
    void theRangeBoundsAreInclusiveAndEitherOneMayBeAbsent() {
        var store = openBook("BOUNDS");
        var first = salesOrder(store.id(), "2026-10-05 10:00:00", "100", "16", "116", MXN);
        transition(first, "PENDING", "COMPLETED", "2026-10-05 10:05:00");
        var second = salesOrder(store.id(), "2026-10-09 10:00:00", "100", "16", "116", MXN);
        transition(second, "PENDING", "COMPLETED", "2026-10-09 10:05:00");
        settle();

        var oneDay = reports.salesJournal(
                company, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), PageRequest.of(0, 20));
        assertThat(oneDay.getContent())
                .as("a range whose start and end are the same day includes that day on both ends")
                .hasSize(1);

        var openStart = reports.salesJournal(company, null, LocalDate.of(2026, 10, 5), PageRequest.of(0, 20));
        assertThat(openStart.getContent()).as("no `from` is an open start").hasSize(1);

        var openEnd = reports.salesJournal(company, LocalDate.of(2026, 10, 9), null, PageRequest.of(0, 20));
        assertThat(openEnd.getContent()).as("no `to` is an open end").hasSize(1);

        var everything = reports.salesJournal(company, null, null, PageRequest.of(0, 1));
        assertThat(everything.getTotalElements())
                .as("with neither bound the journal is the whole book, and it is still a page")
                .isEqualTo(2);
        assertThat(everything.getContent()).hasSize(1);
        assertThat(everything.getTotalPages()).isEqualTo(2);

        var secondPage = reports.salesJournal(company, null, null, PageRequest.of(1, 1));
        assertThat(secondPage.getContent())
                .as("the second page holds the other entry, and the order is the journal's own")
                .hasSize(1)
                .doesNotContainAnyElementsOf(everything.getContent());
    }

    // ──────────────────────────────────────
    // T9 — the trial balance
    // ──────────────────────────────────────

    @Test
    @DisplayName("S18: the trial balance groups by (currency, account) and each currency's two sides are equal")
    void theTrialBalanceGroupsByCurrencyAndBalancesInEachOne() {
        var store = openBook("TRIAL");
        var mxn = salesOrder(store.id(), "2026-10-05 10:00:00", "100", "16", "116", MXN);
        transition(mxn, "PENDING", "COMPLETED", "2026-10-05 10:05:00");
        payment(mxn, "CHARGE", "116", "2026-10-05 10:10:00");

        // The company changes its operating currency, which is editable, and its book keeps both: the whole
        // point of the currency being part of the key instead of a column of a total.
        jdbc.update(
                "INSERT INTO currency (code, minor_units, symbol, enabled) VALUES (?, 2, '$', true)"
                        + " ON CONFLICT (code) DO NOTHING",
                USD);
        jdbc.update("UPDATE companies SET currency = ? WHERE id = ?", USD, company);
        var usd = salesOrder(store.id(), "2026-10-06 10:00:00", "200", "0", "200", USD);
        transition(usd, "PENDING", "COMPLETED", "2026-10-06 10:05:00");
        settle();

        var balance = reports.trialBalance(company, OCTOBER_FIRST, OCTOBER_LAST);

        assertThat(balance.rows())
                .as("four MXN accounts and two USD ones, and not one row that mixes them")
                .hasSize(6);
        assertThat(balance.rows())
                .extracting(TrialBalanceRowResponse::currency)
                .containsOnly(MXN, MXN, MXN, MXN, USD, USD);
        assertThat(balance.totals())
                .extracting(TrialBalanceTotalResponse::currency)
                .as("one total per currency, in the order the rows state them")
                .containsExactly(MXN, USD);

        for (var total : balance.totals()) {
            assertThat(total.debit())
                    .as("every entry balances by construction (A1), so %s's two sides are equal", total.currency())
                    .isEqualByComparingTo(total.credit());
            assertThat(balance.rows().stream()
                            .filter(row -> row.currency().equals(total.currency()))
                            .map(TrialBalanceRowResponse::balance)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("and the same statement read row by row in %s sums to zero", total.currency())
                    .isEqualByComparingTo("0");
        }

        assertThat(balance.totals())
                .filteredOn(total -> total.currency().equals(MXN))
                .singleElement()
                .satisfies(total -> {
                    assertThat(total.debit()).isEqualByComparingTo("232");
                    assertThat(total.credit()).isEqualByComparingTo("232");
                });
        assertThat(balance.totals())
                .filteredOn(total -> total.currency().equals(USD))
                .singleElement()
                .satisfies(total -> {
                    assertThat(total.debit())
                            .as("a tax-exempt document posts its receivable and its revenue")
                            .isEqualByComparingTo("200");
                    assertThat(total.credit()).isEqualByComparingTo("200");
                });
        assertThat(balance.rows())
                .filteredOn(row ->
                        row.accountCode().equals("4101") && row.currency().equals(MXN))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.accountName()).isEqualTo("Ingresos por ventas");
                    assertThat(row.accountKind()).isEqualTo("REVENUE");
                    assertThat(row.debit()).isEqualByComparingTo("0");
                    assertThat(row.credit()).isEqualByComparingTo("100");
                    assertThat(row.balance()).isEqualByComparingTo("-100");
                });
    }

    @Test
    @DisplayName("S18: a range with no entry is an empty report and never a 404")
    void anEmptyRangeIsALegitimateEmptyReport() {
        openBook("EMPTY");

        assertThat(reports.salesJournal(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getContent())
                .isEmpty();
        assertThat(reports.trialBalance(company, OCTOBER_FIRST, OCTOBER_LAST).rows())
                .isEmpty();
        assertThat(reports.trialBalance(company, OCTOBER_FIRST, OCTOBER_LAST).totals())
                .as("no currency has anything to total, and that is not an error")
                .isEmpty();
        assertThat(reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getContent())
                .isEmpty();
        assertThat(reports.grossMarginByStore(company, OCTOBER_FIRST, OCTOBER_LAST)
                        .stores())
                .isEmpty();
    }

    // ──────────────────────────────────────
    // T10 — gross margin by document
    // ──────────────────────────────────────

    @Test
    @DisplayName("S15: a document's margin is its revenue minus the cost its allocations froze")
    void aDocumentsMarginIsItsRevenueMinusTheCostTheLedgerAllocated() {
        var store = openBook("MARGIN");
        var variant = variant();
        receipt(store.locationId(), variant, "3", "10", "2026-10-01 09:00:00");
        receipt(store.locationId(), variant, "4", "12", "2026-10-02 09:00:00");
        var order = salesOrder(store.id(), "2026-10-05 15:30:00", "100", "16", "116", MXN);
        var item = salesOrderItem(order, variant, "5", "20", "2026-10-05 15:30:00");
        // Five units over a three and a four consume two inbound rows, so the cost is 3×10 + 2×12 = 54 and it is
        // posted from the movement the line booked — not from the document, which states no cost at all.
        var movement = saleMovement(store.locationId(), variant, "5", "2026-10-08 17:00:00", item);
        allocate(movement);
        transition(order, "PENDING", "COMPLETED", "2026-10-05 15:35:00");
        settle();

        var margin = reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20));

        assertThat(margin.getContent()).hasSize(1);
        var document = margin.getContent().get(0);
        assertThat(document.documentId()).isEqualTo(order);
        assertThat(document.storeId()).isEqualTo(store.id());
        assertThat(document.currency()).isEqualTo(MXN);
        assertThat(document.revenue())
                .as("revenue is the document's 100 of revenue and not its 116 total: the tax collected is a "
                        + "liability and never revenue")
                .isEqualByComparingTo("100");
        assertThat(document.cost())
                .as("the cost the ledger froze in stock_allocation.unit_cost, summed over the allocations")
                .isEqualByComparingTo("54");
        assertThat(document.margin()).isEqualByComparingTo("46");
        assertThat(margin.getTotalElements()).isEqualTo(1);
        assertThat(unknownCostDocuments(margin)).isZero();
    }

    @Test
    @DisplayName("S18: the per-document margin is a page ordered by (order_date, order_number, id), and the "
            + "unknown-cost count is the range's and not the page's")
    void theDocumentMarginIsPagedAndTheUnknownCostCountIsRangeScoped() {
        var store = openBook("MARGIN-PAGE");
        // Three documents on two days, and the two of the same day are ordered by their number: a page boundary
        // that repeated or skipped a row would be visible as a duplicated or missing id.
        var first = salesOrder(store.id(), "2026-10-05 09:00:00", "10", "0", "10", MXN);
        transition(first, "PENDING", "COMPLETED", "2026-10-05 09:05:00");
        var second = salesOrder(store.id(), "2026-10-05 11:00:00", "20", "0", "20", MXN);
        transition(second, "PENDING", "COMPLETED", "2026-10-05 11:05:00");
        var third = salesOrder(store.id(), "2026-10-06 09:00:00", "30", "0", "30", MXN);
        transition(third, "PENDING", "COMPLETED", "2026-10-06 09:05:00");
        settle();

        var firstPage = reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 2));
        var secondPage = reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(1, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalElements())
                .as("the total is the range's, so the page states what it is a page of")
                .isEqualTo(3);
        assertThat(firstPage.getContent())
                .extracting(GrossMarginDocumentResponse::documentId)
                .doesNotContainAnyElementsOf(secondPage.getContent().stream()
                        .map(GrossMarginDocumentResponse::documentId)
                        .toList());
        assertThat(java.util.stream.Stream.concat(firstPage.getContent().stream(), secondPage.getContent().stream())
                        .map(GrossMarginDocumentResponse::documentId)
                        .toList())
                .as("paging across both pages visits every document exactly once, in the report's order")
                .containsExactly(first, second, third);
        assertThat(unknownCostDocuments(firstPage))
                .as("none of the three has a cost, and the count is over the range: a page-scoped count would "
                        + "have said two")
                .isEqualTo(3);
        assertThat(unknownCostDocuments(secondPage))
                .as("the same range-scoped value on every page, which is what makes it a property of the report")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("S15: a document whose cost never posted reports a null cost and a null margin, and is counted")
    void aDocumentsCostThatNeverPostedIsUnknownAndCounted() {
        var store = openBook("MARGIN-UNKNOWN");
        var order = salesOrder(store.id(), "2026-10-06 10:00:00", "100", "16", "116", MXN);
        transition(order, "PENDING", "COMPLETED", "2026-10-06 10:05:00");
        payment(order, "CHARGE", "116", "2026-10-06 10:10:00");
        settle();

        var margin = reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20));

        assertThat(margin.getContent()).hasSize(1);
        var document = margin.getContent().get(0);
        assertThat(document.revenue()).isEqualByComparingTo("100");
        assertThat(document.cost())
                .as("unknown is not zero: the goods were not free, and today's product_variants.cost_price "
                        + "would rewrite a past margin (S15)")
                .isNull();
        assertThat(document.margin()).isNull();
        assertThat(unknownCostDocuments(margin))
                .as("the document is counted, so the absence is visible instead of arithmetic")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // T10 — gross margin by store
    // ──────────────────────────────────────

    @Test
    @DisplayName("S15: a store bucket holding one unknown-cost document reports no margin, and states how many")
    void aStoreBucketWithOneUnknownDocumentReportsNoMargin() {
        var first = openBook("STORES");
        var second = newStore("STORES-2");

        // Store one: a costed document and one whose cost never posted.
        var costed = variant();
        receipt(first.locationId(), costed, "10", "10", "2026-10-01 09:00:00");
        var costedOrder = salesOrder(first.id(), "2026-10-05 10:00:00", "100", "16", "116", MXN);
        var costedItem = salesOrderItem(costedOrder, costed, "5", "20", "2026-10-05 10:00:00");
        allocate(saleMovement(first.locationId(), costed, "5", "2026-10-05 18:00:00", costedItem));
        transition(costedOrder, "PENDING", "COMPLETED", "2026-10-05 10:05:00");

        var unknownOrder = salesOrder(first.id(), "2026-10-06 10:00:00", "50", "0", "50", MXN);
        transition(unknownOrder, "PENDING", "COMPLETED", "2026-10-06 10:05:00");

        // Store two: one costed document, so the propagation is visibly a property of the bucket.
        var other = variant();
        receipt(second.locationId(), other, "10", "5", "2026-10-01 09:00:00");
        var otherOrder = salesOrder(second.id(), "2026-10-07 10:00:00", "80", "0", "80", MXN);
        var otherItem = salesOrderItem(otherOrder, other, "4", "20", "2026-10-07 10:00:00");
        allocate(saleMovement(second.locationId(), other, "4", "2026-10-07 18:00:00", otherItem));
        transition(otherOrder, "PENDING", "COMPLETED", "2026-10-07 10:05:00");

        settle();

        var stores = reports.grossMarginByStore(company, OCTOBER_FIRST, OCTOBER_LAST);

        assertThat(stores.stores())
                .extracting(GrossMarginStoreResponse::storeId)
                .as("one bucket per store that traded in the range")
                .containsExactlyInAnyOrder(first.id(), second.id());

        var mixed = store(stores.stores(), first.id());
        assertThat(mixed.currency()).isEqualTo(MXN);
        assertThat(mixed.revenue())
                .as("the revenue is known for every document, so it is summed even when a cost is not")
                .isEqualByComparingTo("150");
        assertThat(mixed.cost())
                .as("a total over a missing part is not a total")
                .isNull();
        assertThat(mixed.margin()).isNull();
        assertThat(mixed.documents()).isEqualTo(2);
        assertThat(mixed.unknownCostDocuments()).isEqualTo(1);

        var known = store(stores.stores(), second.id());
        assertThat(known.storeName()).isEqualTo("V9 Store STORES-2");
        assertThat(known.revenue()).isEqualByComparingTo("80");
        assertThat(known.cost()).isEqualByComparingTo("20");
        assertThat(known.margin()).isEqualByComparingTo("60");
        assertThat(known.documents()).isEqualTo(1);
        assertThat(known.unknownCostDocuments()).isZero();
    }

    // ──────────────────────────────────────
    // The range refusal
    // ──────────────────────────────────────

    @Test
    @DisplayName("S18: a range that ends before it starts is a 400 naming both dates, on all three reports")
    void aRangeThatEndsBeforeItStartsIsRefusedNamingBothDates() {
        openBook("RANGE");
        var from = LocalDate.of(2026, 11, 1);
        var to = LocalDate.of(2026, 10, 1);

        assertThatThrownBy(() -> reports.salesJournal(company, from, to, PageRequest.of(0, 20)))
                .isInstanceOf(InvalidAccountingRangeException.class)
                .hasMessageContaining("2026-11-01")
                .hasMessageContaining("2026-10-01");
        assertThatThrownBy(() -> reports.trialBalance(company, from, to))
                .isInstanceOf(InvalidAccountingRangeException.class)
                .hasMessageContaining("2026-11-01")
                .hasMessageContaining("2026-10-01");
        assertThatThrownBy(() -> reports.grossMarginByDocument(company, from, to, PageRequest.of(0, 20)))
                .isInstanceOf(InvalidAccountingRangeException.class)
                .hasMessageContaining("2026-11-01")
                .hasMessageContaining("2026-10-01");
        assertThatThrownBy(() -> reports.grossMarginByStore(company, from, to))
                .isInstanceOf(InvalidAccountingRangeException.class)
                .hasMessageContaining("2026-11-01")
                .hasMessageContaining("2026-10-01");
    }

    // ──────────────────────────────────────
    // The gate, and the tenant scope S18 exists for
    // ──────────────────────────────────────

    @Test
    @DisplayName("S18: a caller whose claim does not carry the company is denied")
    void aCallerWhoseClaimDoesNotCarryTheCompanyIsDenied() {
        openBook("DENIED");
        // A sales caller of another company: the claim names a company, just not this one.
        authenticate(UUID.randomUUID());

        assertThatThrownBy(() -> reports.salesJournal(company, null, null, PageRequest.of(0, 20)))
                .as("the path's company is verified against the caller's claim before anything is read")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("S18: a second company's entries never appear in the first company's report")
    void anotherCompanysEntriesNeverAppearInThisCompanysReport() {
        var mine = openBook("TENANT-MINE");
        var mineCompany = company;
        var mineCharged = chargeOneSale(mine);
        // Settled while this tenant's scope is stated: the deferred ties of `V68` judge a document under the
        // scope that wrote it, so a pending event left for later would be judged under the next tenant's.
        settle();
        deferEverything();

        var theirs = openBook("TENANT-THEIRS");
        var theirsCompany = company;
        var theirsCharged = chargeOneSale(theirs);

        // Back to the first tenant: the scope stated by the transaction is what the reports read under.
        TenantFixtures.stateTenantWithinTheOpenTransaction(mineCompany, jdbc);
        company = mineCompany;

        var journal = reports.salesJournal(mineCompany, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20));
        assertThat(journal.getContent())
                .as("only this company's revenue and collection, and none of the other tenant's")
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.sourceId())
                        .isNotIn(theirsCharged.orderId(), theirsCharged.transitionId(), theirsCharged.paymentId()));

        var balance = reports.trialBalance(mineCompany, OCTOBER_FIRST, OCTOBER_LAST);
        assertThat(balance.totals()).singleElement().satisfies(total -> {
            assertThat(total.debit()).isEqualByComparingTo("232");
            assertThat(total.credit()).isEqualByComparingTo("232");
        });

        var margin = reports.grossMarginByDocument(mineCompany, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20));
        assertThat(margin.getContent()).hasSize(1);
        assertThat(margin.getContent().get(0).documentId()).isEqualTo(mineCharged.orderId());

        // And the other direction: asking for the other company while the transaction states this one reads
        // nothing, because the policy — not the WHERE clause — is what bounds a report.
        assertThat(reports.salesJournal(theirsCompany, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getTotalElements())
                .as("a report for a company the transaction does not state reads zero rows")
                .isZero();
    }

    @Test
    @DisplayName("S18: a report read with no tenant scope returns nothing, which is the ADR's silent empty report")
    void aReportReadWithNoTenantScopeReturnsNothing() {
        var store = openBook("NO-SCOPE");
        chargeOneSale(store);
        settle();

        assertThat(reports.salesJournal(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getTotalElements())
                .as("with the scope stated, the entries are there")
                .isEqualTo(2);

        // Exactly the session state a statement outside a transaction gets: no `app.company_id`, so the policy
        // filters every row. ADR-0008 decision 9's "a report as the owner reads zero rows and looks clean" is
        // this measurement — which is why every public method here is transactional.
        jdbc.execute("SELECT set_config('app.company_id', '', true)");

        assertThat(reports.salesJournal(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getTotalElements())
                .as("no scope, no journal")
                .isZero();
        assertThat(reports.trialBalance(company, OCTOBER_FIRST, OCTOBER_LAST).rows())
                .as("no scope, no balance")
                .isEmpty();
        assertThat(reports.grossMarginByDocument(company, OCTOBER_FIRST, OCTOBER_LAST, PageRequest.of(0, 20))
                        .getContent())
                .as("no scope, no margin — and not a single false 'unknown cost' either")
                .isEmpty();
        assertThat(reports.grossMarginByStore(company, OCTOBER_FIRST, OCTOBER_LAST)
                        .stores())
                .isEmpty();

        TenantFixtures.stateTenantWithinTheOpenTransaction(company, jdbc);
    }

    // ──────────────────────────────────────
    // fixtures: identity
    // ──────────────────────────────────────

    /**
     * States a caller carrying {@code lc-admin}, which is the role that reads every company: the reports'
     * tenant filter is then the transaction's scope alone, which is what the scope cases have to observe.
     */
    private void authenticateAsAdmin() {
        authenticate(List.of(UUID.randomUUID()), "lc-admin");
    }

    /** States a caller carrying {@code lc-sales} for exactly the companies named in the claim. */
    private void authenticate(UUID companyId) {
        authenticate(List.of(companyId), "lc-sales");
    }

    /**
     * Installs a fresh request with a JWT authentication, because the request-scoped {@code CurrentUserContext}
     * caches the claims it read: a new identity needs a new request.
     */
    private void authenticate(List<UUID> companyIds, String role) {
        RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var identity = Jwt.withTokenValue("v100-reports-token")
                .header("alg", "none")
                .subject("suite:reports")
                .claim("preferred_username", "suite:reports")
                .claim("company_id", companyIds.stream().map(UUID::toString).toList())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(identity, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ──────────────────────────────────────
    // fixtures: one tenant, its store tree, its chart and its rules
    // ──────────────────────────────────────

    /** One store of the tenant, with the location its ledger rows travel through. */
    private record Store(UUID id, UUID locationId) {}

    /** The three facts one charged sale produced, so a case can name them without a second query. */
    private record ChargedSale(UUID orderId, UUID transitionId, UUID paymentId) {}

    private Store openBook(String tag) {
        company = UUID.randomUUID();
        TenantFixtures.stateTenantWithinTheOpenTransaction(company, jdbc);
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES (?, ?, ?, ?, ?)",
                company,
                "V9-" + tag + "-" + company.toString().substring(0, 8),
                "V9 Company " + tag,
                "PT" + company.toString().replace("-", "").substring(0, 10).toUpperCase(),
                MXN);
        seedHierarchy();
        chart();
        return newStore(tag);
    }

    /** The country → region → zone chain the store's own composite keys require, one row per level. */
    private void seedHierarchy() {
        jdbc.update("""
                INSERT INTO company_countries (id, company_id, country_id)
                SELECT gen_random_uuid(), ?, c.id FROM countries c WHERE c.country_code = 'MX'
                """, company);
        var companyCountry =
                jdbc.queryForObject("SELECT id FROM company_countries WHERE company_id = ?", UUID.class, company);
        var region = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name)
                VALUES (?, ?, ?, ?, 'V9 Region')
                """, region, company, companyCountry, region.toString().substring(0, 6));
        var zone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)
                VALUES (?, ?, ?, ?, 'V9 Zone')
                """, zone, company, region, zone.toString().substring(0, 6));
    }

    /** One store of the current company, with the area → zone → location chain the ledger rows are keyed by. */
    private Store newStore(String tag) {
        var store = UUID.randomUUID();
        var companyZone = jdbc.queryForObject("SELECT id FROM company_zones WHERE company_id = ?", UUID.class, company);
        jdbc.update(
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)"
                        + " VALUES (?, ?, ?, ?, true)",
                store,
                company,
                companyZone,
                "V9 Store " + tag);
        var area = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)
                VALUES (?, ?, ?, 'V9 Area', true)
                """, area, store, area.toString().substring(0, 6));
        var zone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, 'V9 Store zone', true)
                """, zone, area, store, zone.toString().substring(0, 6));
        var location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_locations
                    (id, store_zone_id, company_store_id, location_code, location_name, enabled)
                VALUES (?, ?, ?, ?, 'V9 Location', true)
                """, location, zone, store, location.toString().substring(0, 6));
        return new Store(store, location);
    }

    /** The eight accounts and the three rules with their seven lines, as {@code V98}/{@code V99} seed them. */
    private void chart() {
        for (var row : CHART) {
            var parts = row.split("\\|");
            jdbc.update(
                    "INSERT INTO account (company_id, code, name, kind) VALUES (?, ?, ?, ?)",
                    company,
                    parts[0],
                    parts[1],
                    parts[2]);
        }
        var revenue = rule("STATUS_TRANSITION", "REVENUE");
        line(revenue, "DEBIT", "TOTAL", "1201");
        line(revenue, "CREDIT", "REVENUE", "4101");
        line(revenue, "CREDIT", "TAX", "2101");
        var collection = rule("SALE_PAYMENT", "COLLECTION");
        line(collection, "DEBIT", "SOURCE_AMOUNT", "1101");
        line(collection, "CREDIT", "SOURCE_AMOUNT", "1201");
        var cost = rule("STOCK_ALLOCATION", "COST_OF_SALES");
        line(cost, "DEBIT", "ALLOCATED_COST", "5101");
        line(cost, "CREDIT", "ALLOCATED_COST", "1301");
    }

    private UUID rule(String sourceType, String posting) {
        return jdbc.queryForObject(
                "INSERT INTO posting_rule (company_id, source_type, posting) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                company,
                sourceType,
                posting);
    }

    private void line(UUID ruleId, String side, String selector, String accountCode) {
        var inserted = jdbc.update("""
                INSERT INTO posting_rule_line (company_id, rule_id, account_id, side, amount_selector)
                SELECT ?, ?, a.id, ?, ? FROM account a WHERE a.company_id = ? AND a.code = ?
                """, company, ruleId, side, selector, company, accountCode);
        assertThat(inserted).as("the chart declares account %s", accountCode).isEqualTo(1);
    }

    // ──────────────────────────────────────
    // fixtures: the facts, written the way their writers write them
    // ──────────────────────────────────────

    /** One sales order in {@code PENDING}, stating the three money parts {@code ck_order_total} adds up. */
    private UUID salesOrder(
            UUID storeId, String orderDate, String subtotal, String tax, String total, String currency) {
        var id = UUID.randomUUID();
        var customer = customer();
        // Ascending with the fixture's own counter, so a case can assert the report's (order_date, order_number)
        // order instead of an order the random id chose. The rows are rolled back with the test transaction, so
        // the number never has to be unique across the container.
        var number = "V9-" + String.format("%06d", SEQ.incrementAndGet());
        jdbc.update(
                """
                INSERT INTO sales_orders
                    (id, order_number, customer_id, company_store_id, order_date, status, company_id, doc_type,
                     seq_period, seq_value, doc_number, currency, subtotal, tax_total, total_amount)
                VALUES (?, ?, ?, ?, ?::timestamp, 'PENDING', ?, 'SALES_ORDER', '202610', ?, ?, ?, ?::numeric,
                        ?::numeric, ?::numeric)
                """,
                id,
                number,
                customer,
                storeId,
                orderDate,
                company,
                SEQ.incrementAndGet(),
                number,
                currency,
                subtotal,
                tax,
                total);
        recordCreation(id, "SALES_ORDER", "PENDING", orderDate);
        return id;
    }

    /**
     * One line of a sales order, in {@code PENDING}, with the creation row {@code V68}'s deferred tie requires
     * of every line.
     *
     * <p>The line is what gives the ledger row its identity: the only writer of a sale movement states
     * {@code source_type = 'SALES_ORDER_ITEM'} and {@code source_id =} the line id
     * ({@code SalesOrderService.java:96}, {@code :1197}), and the per-document margin reads that very column to
     * find which document a cost entry belongs to. A fixture that invented a random source id would exercise a
     * join production never produces.</p>
     */
    private UUID salesOrderItem(UUID orderId, UUID variantId, String quantity, String listPrice, String createdAt) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_order_items
                    (id, sales_order_id, product_variant_id, quantity, list_price, status)
                VALUES (?, ?, ?, ?::numeric, ?::numeric, 'PENDING')
                """, id, orderId, variantId, quantity, listPrice);
        recordCreation(id, "SALES_ORDER_ITEM", "PENDING", createdAt);
        return id;
    }

    private UUID customer() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO customers (id, name, sales_channel) VALUES (?, ?, 'STORE')",
                id,
                "V9 Customer " + id.toString().substring(0, 8));
        return id;
    }

    /**
     * One {@code status_transition} row and the document's own column moved to match it.
     *
     * @return the transition's id, which is the source the revenue entry names
     */
    private UUID transition(UUID orderId, String from, String to, String occurredAt) {
        var id = recordCreation(orderId, "SALES_ORDER", to, occurredAt, from);
        jdbc.update("UPDATE sales_orders SET status = ? WHERE id = ? AND company_id = ?", to, orderId, company);
        return id;
    }

    private UUID recordCreation(UUID documentId, String documentType, String status, String occurredAt) {
        return recordCreation(documentId, documentType, status, occurredAt, null);
    }

    /**
     * The row the recorder writes: {@code from_status} is null only for the creation row, which is what
     * {@code uq_status_transition_creation} allows exactly one of.
     */
    private UUID recordCreation(
            UUID documentId, String documentType, String status, String occurredAt, String fromStatus) {
        return jdbc.queryForObject("""
                INSERT INTO status_transition
                    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'suite:actor', ?::timestamptz)
                RETURNING id
                """, UUID.class, company, documentType, documentId, fromStatus, status, occurredAt);
    }

    /** One payment row; {@code actor} and {@code paid_at} are stated so the entry's date is an assertion. */
    private UUID payment(UUID orderId, String direction, String amount, String paidAt) {
        var method =
                jdbc.queryForObject("SELECT id FROM payment_methods ORDER BY payment_method_name LIMIT 1", UUID.class);
        return jdbc.queryForObject("""
                INSERT INTO sale_payment (company_id, sales_order_id, method_id, direction, amount, paid_at, actor)
                VALUES (?, ?, ?, ?, ?::numeric, ?::timestamptz, 'suite:actor')
                RETURNING id
                """, UUID.class, company, orderId, method, direction, amount, paidAt);
    }

    /** One product and one variant of it, owned by the company the ledger row will belong to. */
    private UUID variant() {
        var product = UUID.randomUUID();
        var suffix = product.toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                product,
                "V9-" + suffix,
                "V9 Product",
                company);
        var measureUnit = jdbc.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        var variant = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)
                VALUES (?, ?, ?, 'unica', ?, ?)
                """, variant, product, "V9-BC-" + suffix, measureUnit, company);
        return variant;
    }

    /** An inbound row: it adds quantity, so it states both what it cost and in which currency. */
    private UUID receipt(UUID locationId, UUID variantId, String quantity, String unitCost, String occurredAt) {
        return movement(locationId, variantId, "RECEIPT", quantity, unitCost, MXN, occurredAt, UUID.randomUUID());
    }

    /**
     * A sale outbound row in the shape {@code StockLedgerWriter.bookSale} writes it: no cost and no currency of
     * its own, and its source is the sales line.
     */
    private UUID saleMovement(
            UUID locationId, UUID variantId, String quantity, String occurredAt, UUID salesOrderItemId) {
        return movement(locationId, variantId, "SALE", quantity, null, null, occurredAt, salesOrderItemId);
    }

    private UUID movement(
            UUID locationId,
            UUID variantId,
            String movementType,
            String quantity,
            String unitCost,
            String currency,
            String occurredAt,
            UUID sourceId) {
        var id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO stock_movement
                    (id, company_id, company_store_id, store_location_id, product_variant_id, movement_type,
                     quantity, unit_cost, currency, occurred_at, actor, source_type, source_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?::timestamptz, 'suite:actor',
                        'SALES_ORDER_ITEM', ?)
                """,
                id,
                company,
                storeOf(locationId),
                locationId,
                variantId,
                movementType,
                quantity,
                unitCost,
                currency,
                occurredAt,
                sourceId);
        return id;
    }

    private UUID storeOf(UUID locationId) {
        return jdbc.queryForObject("SELECT company_store_id FROM store_locations WHERE id = ?", UUID.class, locationId);
    }

    /** The allocation writer, called the way the sale path calls it. */
    private int allocate(UUID outMovementId) {
        return jdbc.queryForObject("SELECT allocate_stock_movement(?)", Integer.class, outMovementId);
    }

    // ──────────────────────────────────────
    // fixtures: one charged sale
    // ──────────────────────────────────────

    /**
     * Completes one sale and takes its money, which posts the two entries the scope and journal cases read.
     */
    private ChargedSale chargeOneSale(Store store) {
        var order = salesOrder(store.id(), "2026-10-05 10:00:00", "100", "16", "116", MXN);
        var transition = transition(order, "PENDING", "COMPLETED", "2026-10-05 10:05:00");
        var payment = payment(order, "CHARGE", "116", "2026-10-05 10:10:00");
        return new ChargedSale(order, transition, payment);
    }

    // ──────────────────────────────────────
    // fixtures: the deferred pass
    // ──────────────────────────────────────

    /**
     * Runs the deferred constraint triggers now, which is the pass the commit would run: the cost entry only
     * exists after it, and so does the balance check that proves the entry is whole.
     *
     * <p>The cost trigger goes first and the balance triggers second, in two steps and in this order: the cost
     * trigger writes an entry from inside a deferred trigger, and switching <b>all</b> of them to immediate at
     * once makes the balance check fire at the end of the statement that inserted the header, before the poster
     * has written its lines. That is a property of {@code SET CONSTRAINTS}, not of a commit — at {@code COMMIT}
     * the events queue — and firing them in this order reproduces the commit's order by hand.</p>
     */
    private void settle() {
        jdbc.execute("SET CONSTRAINTS trg_stock_allocation_post_cost IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    /**
     * Puts the deferral back after a {@link #settle()}, so the next tenant's fixtures can be written in their
     * natural order again: an entry's header is inserted before its lines, and a document before its history.
     */
    private void deferEverything() {
        jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
    }

    // ──────────────────────────────────────
    // assertions
    // ──────────────────────────────────────

    private static void assertLine(
            List<SalesJournalLineResponse> lines, String code, String name, String debit, String credit) {
        assertThat(lines)
                .filteredOn(line -> line.accountCode().equals(code))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.accountName()).isEqualTo(name);
                    assertThat(line.debit()).as("%s debit", code).isEqualByComparingTo(debit);
                    assertThat(line.credit()).as("%s credit", code).isEqualByComparingTo(credit);
                });
    }

    private static GrossMarginStoreResponse store(List<GrossMarginStoreResponse> stores, UUID storeId) {
        var matching = stores.stream()
                .filter(candidate -> candidate.storeId().equals(storeId))
                .toList();
        assertThat(matching).as("one bucket for store %s", storeId).hasSize(1);
        return matching.get(0);
    }

    /**
     * The range-scoped unknown-cost count the paged margin response carries. Asserted through the type the
     * endpoint serializes, so a page that lost the count fails here rather than in a client.
     */
    private static long unknownCostDocuments(Page<GrossMarginDocumentResponse> page) {
        assertThat(page)
                .as("the per-document margin response is the page that states the range's unknown-cost count")
                .isInstanceOf(GrossMarginDocumentsPage.class);
        return ((GrossMarginDocumentsPage) page).getUnknownCostDocuments();
    }
}
