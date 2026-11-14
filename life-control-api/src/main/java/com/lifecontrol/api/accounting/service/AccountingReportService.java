package com.lifecontrol.api.accounting.service;

import com.lifecontrol.api.accounting.dto.GrossMarginDocumentResponse;
import com.lifecontrol.api.accounting.dto.GrossMarginDocumentsPage;
import com.lifecontrol.api.accounting.dto.GrossMarginStoreResponse;
import com.lifecontrol.api.accounting.dto.GrossMarginStoresResponse;
import com.lifecontrol.api.accounting.dto.SalesJournalEntryResponse;
import com.lifecontrol.api.accounting.dto.SalesJournalLineResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceRowResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceTotalResponse;
import com.lifecontrol.api.accounting.exception.InvalidAccountingRangeException;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three read models of {@code S18}: the sales journal, the trial balance and the gross margin, by document
 * and by store.
 *
 * <h2>Why native SQL and not a model</h2>
 *
 * <p>Accounting has <b>no JPA entity and does not get one</b>. The book is a projection written by triggers
 * ({@code V100}), the entries are append-only ({@code V97}) and every report here is a question no entity could
 * help with: a group by account and currency, a sum over an allocation's movement, a join from a cost entry
 * back to the document it belongs to. {@code JdbcTemplate} states exactly the query, and a mapping layer would
 * add a second vocabulary for {@code debit} and {@code credit} without answering anything.</p>
 *
 * <h2>The scope, and why every public method is transactional</h2>
 *
 * <p>Row-level security is installed into the database session by
 * {@code TenantScopeJpaTransactionManager#doBegin}, and a {@code JdbcTemplate} statement inside that transaction
 * reuses the bound connection and therefore inherits the scope. A statement <b>outside</b> a transaction borrows
 * another pooled connection and reads with <b>no</b> scope, which is {@code ADR-0008} decision 9's failure
 * measured: a report read that way comes back empty and looks clean. Every public method of this class is
 * {@code @Transactional(readOnly = true)} for that reason, and it is not an optimisation.</p>
 *
 * <h2>The four rules every report here obeys</h2>
 *
 * <ol>
 *   <li><b>The range is inclusive business dates.</b> {@code from} and {@code to} are compared against
 *       {@code account_entry.entry_date} — the date the fact carries, never the instant the row was written —
 *       an absent bound is an open end, and {@code to < from} is a 400 that names both values instead of
 *       printing an empty book.
 *   <li><b>Currency is part of every key and is never summed across it.</b> {@code companies.currency} is
 *       editable, so one tenant's book can hold MXN before a change and USD after it, and there is no exchange
 *       rate ({@code ADR-0002}). The trial balance groups by {@code (currency, account)} and the store margin by
 *       {@code (currency, store)}; the per-document margin carries each document's own currency.
 *   <li><b>Unknown is never zero.</b> A document whose revenue posted and whose cost never did reports a null
 *       cost and a null margin, and is counted — {@code S15}. A store bucket holding any such document reports
 *       its margin as null with the count.
 *   <li><b>An empty result is a legitimate report.</b> A range with no entry is a 200 with no rows and never a
 *       404: the company exists, its book simply has nothing between those dates.
 * </ol>
 *
 * <h2>The margin needs no replay, and the ADR's replay limit is about a different table</h2>
 *
 * <p>{@code ADR-0008} states one limit that looks like it applies here and does not: <em>"a valuation as of a
 * date is a replay"</em>. That belongs to {@code stock_valuation}, which is a current projection with no date
 * dimension. {@code account_entry} freezes the cost at the allocation it was derived from and is append-only, so
 * a margin over a past range is <b>what happened</b> and not what would happen today. Nobody should add a replay
 * to this class later: there is nothing to replay, and a second computation of the same fact is a second answer
 * that can disagree with the book.</p>
 *
 * <h2>The trial balance is born by range; the period appears with T11</h2>
 *
 * <p>{@code S18} says "trial balance by period", and {@code accounting_period} is T11 of
 * {@code odd/tasks/derived-accounting.md}. This class therefore <b>reads and writes no period</b> — the same
 * refusal {@code V100} asserts of the poster — and the closed-month grouping arrives with B5. A range answers
 * the question a month would today.</p>
 *
 * <h2>Where a cost entry's document comes from, measured rather than assumed</h2>
 *
 * <p>The per-document margin has to attribute a cost to a sales document, and the cost entry does not name one.
 * Measured in this tree: the poster writes the cost with {@code source_type = 'STOCK_ALLOCATION'} and
 * {@code source_id =} the <b>outbound stock movement</b>
 * ({@code V100__accounting_postings.sql:432-435}); the only writer of a sale movement states
 * {@code source_type = 'SALES_ORDER_ITEM'} and {@code source_id =} the <b>sales line id</b>, never the order's
 * ({@code SalesOrderService.java:96}, {@code :1197}, {@code StockLedgerWriter.java:23-49}). So the chain is
 * entry &rarr; movement &rarr; line &rarr; order, and a document's cost is the sum over <b>every</b> movement of
 * every one of its lines: a line books one movement per location and per write, and {@code L13(d)} keeps the
 * entry single per movement.</p>
 *
 * <h2>Two readings that are decisions, stated so they are not rediscovered</h2>
 *
 * <p><b>The cost is the cost entry's debit side, and the revenue is the credit side of its REVENUE-kind
 * accounts.</b> A {@code COST_OF_SALES} entry debits the cost of sales and credits inventory, so its debit total
 * <em>is</em> the cost it booked, and reading it that way needs no assumption about which codes a tenant's chart
 * uses. A {@code REVENUE} entry debits the receivable and credits both the revenue and the tax collected, so
 * "the revenue" is the part that landed on a {@code REVENUE}-kind account — the chart's own declaration, and the
 * reason {@code account.kind} exists. Summing the whole entry would answer zero, which is what A1 guarantees.</p>
 *
 * <p><b>A document's cost is read over every date, while the document set comes from the range.</b> The revenue
 * entry carries the document's date, so the range selects documents by when they earned. The cost entry carries
 * the day the goods left, which can be a day or a period later. Filtering the cost by the same range would
 * report a margin nobody earned — a document that earned in September and cost in October is not two
 * half-documents — so the join takes the document's whole cost.</p>
 *
 * <p><b>And the two margin reports are two statements, not one derived from the other.</b> The per-document
 * report is <b>paged</b> (a month of a busy tenant is thousands of documents) and the store report is one
 * {@code GROUP BY (currency, store)} statement over the whole range, so neither can be folded out of the other:
 * folding a page gives a store total that describes one page. They agree because they read the same facts with
 * the same predicates inside one read-only transaction — the revenue half and the cost half are the same two
 * CTEs, one copy each, composed into both statements. They would <b>diverge</b> if one of them changed a
 * predicate the other kept (a cost filtered by the entry's own date, say, or a document dropped by the store
 * join) or if a posting committed between the two calls: each statement takes its own snapshot under
 * {@code READ COMMITTED}, so a caller that needs one cut of the book reads the store report alone, which is
 * internally a single statement.</p>
 */
@Service
public class AccountingReportService {

    /**
     * The bound an absent {@code from} becomes. A sentinel and not a {@code NULL} parameter, because an open end
     * is a comparison the query can make: {@code entry_date >= '0001-01-01'} matches every row the book can
     * hold, while a null parameter needs a cast the driver has to infer and a predicate the planner cannot use.
     */
    private static final LocalDate OPEN_START = LocalDate.of(1, 1, 1);

    /** The bound an absent {@code to} becomes, for the same reason. */
    private static final LocalDate OPEN_END = LocalDate.of(9999, 12, 31);

    /**
     * One page of entries with their lines, in one statement.
     *
     * <p>The {@code page} CTE is the page and the join is its lines, which is what keeps the statement count at
     * two — a count and this — instead of one per row. The order is the report's own: {@code (entry_date, id)}
     * makes the page deterministic, and without it a page boundary over entries of the same day could repeat or
     * skip a row.</p>
     */
    private static final String JOURNAL = """
            WITH page AS (
                SELECT e.id, e.entry_date, e.source_type, e.source_id, e.posting, e.actor
                  FROM account_entry e
                 WHERE e.company_id = ? AND e.entry_date >= ? AND e.entry_date <= ?
                 ORDER BY e.entry_date, e.id
                 LIMIT ? OFFSET ?
            )
            SELECT p.id, p.entry_date, p.source_type, p.source_id, p.posting, p.actor,
                   a.code, a.name, l.debit, l.credit, btrim(l.currency) AS currency
              FROM page p
              JOIN account_entry_line l ON l.company_id = ? AND l.entry_id = p.id
              JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
             ORDER BY p.entry_date, p.id, a.code, l.id
            """;

    /** How many entries the range holds, so the page states a total a reader can page against. */
    private static final String JOURNAL_COUNT = """
            SELECT count(*)
              FROM account_entry e
             WHERE e.company_id = ? AND e.entry_date >= ? AND e.entry_date <= ?
            """;

    /**
     * One row per {@code (currency, account)} with activity in the range.
     *
     * <p>{@code sum(l.debit)} and {@code sum(l.credit)} and not a signed net: a trial balance is two columns, and
     * folding them into one would make a credit read as a negative debit in every consumer. The two sides of one
     * entry are equal by {@code V97}'s deferred trigger, so the currency's totals are equal too — which is the
     * property {@link #TRIAL_BALANCE_TOTALS} makes checkable instead of trusting.</p>
     */
    private static final String TRIAL_BALANCE = """
            SELECT a.code, a.name, a.kind, btrim(l.currency) AS currency,
                   sum(l.debit) AS debit, sum(l.credit) AS credit
              FROM account_entry_line l
              JOIN account_entry e ON e.company_id = l.company_id AND e.id = l.entry_id
              JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
             WHERE l.company_id = ? AND e.entry_date >= ? AND e.entry_date <= ?
             GROUP BY a.code, a.name, a.kind, l.currency
             ORDER BY l.currency, a.code
            """;

    /** The two sides of the balance per currency, over the same lines, so the report verifies itself. */
    private static final String TRIAL_BALANCE_TOTALS = """
            SELECT btrim(l.currency) AS currency, sum(l.debit) AS debit, sum(l.credit) AS credit
              FROM account_entry_line l
              JOIN account_entry e ON e.company_id = l.company_id AND e.id = l.entry_id
             WHERE l.company_id = ? AND e.entry_date >= ? AND e.entry_date <= ?
             GROUP BY l.currency
             ORDER BY l.currency
            """;

    /**
     * The two halves every margin statement is made of, written <b>once</b>.
     *
     * <p>The {@code revenue} half reads the {@code REVENUE} posting's lines on {@code REVENUE}-kind accounts and
     * uses the transition to find the document; the {@code cost} half reads the {@code COST_OF_SALES} posting's
     * debit side on the movement, then the movement's own source. Three statements compose this text — the paged
     * documents, the range-scoped counts and the store buckets — and one copy of each half is what keeps them
     * from drifting apart: two copies would be two places for a predicate to be edited alone.</p>
     */
    private static final String MARGIN_FACTS = """
            WITH revenue AS (
                SELECT t.document_id AS document_id, btrim(l.currency) AS currency,
                       sum(l.credit - l.debit) AS revenue
                  FROM account_entry e
                  JOIN status_transition t ON t.company_id = e.company_id AND t.id = e.source_id
                  JOIN account_entry_line l ON l.company_id = e.company_id AND l.entry_id = e.id
                  JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.source_type = 'STATUS_TRANSITION'
                   AND e.posting = 'REVENUE'
                   AND t.document_type = 'SALES_ORDER'
                   AND a.kind = 'REVENUE'
                   AND e.entry_date >= ? AND e.entry_date <= ?
                 GROUP BY t.document_id, l.currency
            ),
            cost AS (
                SELECT i.sales_order_id AS document_id, sum(l.debit) AS cost
                  FROM account_entry e
                  JOIN account_entry_line l ON l.company_id = e.company_id AND l.entry_id = e.id
                  JOIN stock_movement m ON m.company_id = e.company_id AND m.id = e.source_id
                  JOIN sales_order_items i ON i.id = m.source_id
                  JOIN sales_orders o ON o.company_id = e.company_id AND o.id = i.sales_order_id
                 WHERE e.company_id = ?
                   AND e.source_type = 'STOCK_ALLOCATION'
                   AND e.posting = 'COST_OF_SALES'
                   AND m.source_type = 'SALES_ORDER_ITEM'
                 GROUP BY i.sales_order_id
            )
            """;

    /**
     * One page of the documents that earned in the range, with the cost joined through the ledger.
     *
     * <p>The order is {@code (order_date, order_number, document id)}: the document a page boundary could repeat
     * or skip is the one whose order number and date tie with another, so the id is the tie-break and the three
     * together are unique. Ordering by the id alone would be deterministic too, but it would page a report by an
     * order nobody reading it can see.</p>
     */
    private static final String DOCUMENT_MARGIN = MARGIN_FACTS + """
            SELECT r.document_id, o.order_number, o.order_date::date AS order_date, o.company_store_id,
                   s.store_name, r.currency, r.revenue, c.cost
              FROM revenue r
              JOIN sales_orders o ON o.company_id = ? AND o.id = r.document_id
              LEFT JOIN company_stores s ON s.company_id = o.company_id AND s.id = o.company_store_id
              LEFT JOIN cost c ON c.document_id = r.document_id
             ORDER BY o.order_date, o.order_number, r.document_id
             LIMIT ? OFFSET ?
            """;

    /**
     * How many documents the range holds and how many of them have an unknown cost — <b>over the whole range, not
     * over a page</b>, which is the only honest scope for a count a paged response states.
     *
     * <p>One statement, not two: both numbers come from the same snapshot of the same CTEs, so a reader cannot
     * see a total from one instant beside a count from another. {@code count(DISTINCT r.document_id)} because the
     * revenue half is grouped by currency as well as by document: a document that earned in two currencies is one
     * document and would otherwise be counted twice.</p>
     */
    private static final String DOCUMENT_MARGIN_COUNTS = MARGIN_FACTS + """
            SELECT count(DISTINCT r.document_id) AS documents,
                   count(DISTINCT r.document_id) FILTER (WHERE c.cost IS NULL) AS unknown_cost_documents
              FROM revenue r
              LEFT JOIN cost c ON c.document_id = r.document_id
            """;

    /**
     * The store buckets, in one statement.
     *
     * <p>{@code GROUP BY (currency, store)} over the whole range and never a fold of the paged document rows: a
     * page is not the whole set, so summing one would print a store total that describes twenty documents. The
     * unknown-cost rule is carried by the group: {@code sum(p.cost)} sums the known parts, and the count of nulls
     * beside it is what tells the caller that the sum is not a total.</p>
     */
    private static final String STORE_MARGIN = MARGIN_FACTS + """
            , per_document AS (
                SELECT r.document_id, r.currency, o.company_store_id, s.store_name, r.revenue, c.cost
                  FROM revenue r
                  JOIN sales_orders o ON o.company_id = ? AND o.id = r.document_id
                  LEFT JOIN company_stores s ON s.company_id = o.company_id AND s.id = o.company_store_id
                  LEFT JOIN cost c ON c.document_id = r.document_id
            )
            SELECT p.currency, p.company_store_id, p.store_name,
                   sum(p.revenue) AS revenue,
                   sum(p.cost) AS cost,
                   count(*) AS documents,
                   count(*) FILTER (WHERE p.cost IS NULL) AS unknown_cost_documents
              FROM per_document p
             GROUP BY p.currency, p.company_store_id, p.store_name
             ORDER BY p.store_name, p.currency, p.company_store_id
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserContext currentUserContext;

    public AccountingReportService(JdbcTemplate jdbc, CurrentUserContext currentUserContext) {
        this.jdbc = jdbc;
        this.currentUserContext = currentUserContext;
    }

    /**
     * The sales journal of a range: every entry with its lines, oldest first.
     *
     * <p><b>The page carries the report's own order and never the caller's.</b> The request's page number and
     * size are honoured and its {@code sort} is not: the order is {@code (entry_date, id)} because a page of a
     * report that grows with every fact has to be stable, and the response states that order instead of echoing
     * a sort it ignored.</p>
     *
     * @param companyId the company whose book is read; another company's is a 403
     * @param from the first business date of the range, inclusive, or {@code null} for an open start
     * @param to the last business date of the range, inclusive, or {@code null} for an open end
     * @param pageable the page requested
     * @return the page of entries, each with the lines that balance it
     */
    @Transactional(readOnly = true)
    public Page<SalesJournalEntryResponse> salesJournal(
            UUID companyId, LocalDate from, LocalDate to, Pageable pageable) {
        currentUserContext.verifyCompanyAccess(companyId);
        var range = range(from, to);
        var page = pageable.isUnpaged()
                ? PageRequest.of(0, 20)
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        var total = jdbc.queryForObject(JOURNAL_COUNT, Long.class, companyId, range.from(), range.to());
        var content = jdbc.query(
                JOURNAL,
                JOURNAL_EXTRACTOR,
                companyId,
                range.from(),
                range.to(),
                page.getPageSize(),
                page.getOffset(),
                companyId);
        return new PageImpl<>(content, page, total == null ? 0 : total);
    }

    /**
     * The trial balance of a range: one row per {@code (currency, account)} with activity, and one total per
     * currency.
     *
     * @param companyId the company whose book is read; another company's is a 403
     * @param from the first business date of the range, inclusive, or {@code null} for an open start
     * @param to the last business date of the range, inclusive, or {@code null} for an open end
     * @return the rows and the two sides of each currency's total
     */
    @Transactional(readOnly = true)
    public TrialBalanceResponse trialBalance(UUID companyId, LocalDate from, LocalDate to) {
        currentUserContext.verifyCompanyAccess(companyId);
        var range = range(from, to);

        var rows = jdbc.query(
                TRIAL_BALANCE,
                (result, index) -> {
                    var debit = result.getBigDecimal("debit");
                    var credit = result.getBigDecimal("credit");
                    return new TrialBalanceRowResponse(
                            result.getString("code"),
                            result.getString("name"),
                            result.getString("kind"),
                            result.getString("currency"),
                            debit,
                            credit,
                            debit.subtract(credit));
                },
                companyId,
                range.from(),
                range.to());

        var totals = jdbc.query(
                TRIAL_BALANCE_TOTALS,
                (result, index) -> new TrialBalanceTotalResponse(
                        result.getString("currency"), result.getBigDecimal("debit"), result.getBigDecimal("credit")),
                companyId,
                range.from(),
                range.to());

        return new TrialBalanceResponse(rows, totals);
    }

    /**
     * Gross margin by document over a range: one page of documents, each with what it earned against the cost the
     * ledger froze for it.
     *
     * <p><b>Paged, because the document list grows with every fact.</b> The page carries the report's own order
     * and never the caller's {@code sort} — {@code (order_date, order_number, id)} is what keeps a page boundary
     * from repeating or skipping a row — so the response states that order instead of echoing a sort it
     * ignored.</p>
     *
     * <p><b>And the unknown-cost count is range-scoped, not page-scoped.</b> It comes from
     * {@link #DOCUMENT_MARGIN_COUNTS}, its own single statement over the whole range, so the number a client reads
     * is a property of the report rather than of the twenty rows in hand. That statement also yields the
     * {@code total} the page states — the same range, the same CTEs, one snapshot for the pair — while the rows
     * themselves are a second statement: under {@code READ COMMITTED} a posting committed between the two can
     * leave a page holding one row more or less than its own total, which is why the count the report publishes
     * and the rows it prints are consistent with each other and not with a later call.</p>
     *
     * @param companyId the company whose book is read; another company's is a 403
     * @param from the first business date of the range, inclusive, or {@code null} for an open start
     * @param to the last business date of the range, inclusive, or {@code null} for an open end
     * @param pageable the page requested
     * @return the page of document rows, with the count of documents whose cost is unknown
     */
    @Transactional(readOnly = true)
    public Page<GrossMarginDocumentResponse> grossMarginByDocument(
            UUID companyId, LocalDate from, LocalDate to, Pageable pageable) {
        currentUserContext.verifyCompanyAccess(companyId);
        var range = range(from, to);
        var page = pageable.isUnpaged()
                ? PageRequest.of(0, 20)
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        var counts = jdbc.queryForObject(
                DOCUMENT_MARGIN_COUNTS,
                (result, index) ->
                        new MarginCounts(result.getLong("documents"), result.getLong("unknown_cost_documents")),
                companyId,
                range.from(),
                range.to(),
                companyId);
        var content = jdbc.query(
                DOCUMENT_MARGIN,
                DOCUMENT_MARGIN_ROW_MAPPER,
                companyId,
                range.from(),
                range.to(),
                companyId,
                companyId,
                page.getPageSize(),
                page.getOffset());

        var documents = counts == null ? 0 : counts.documents();
        var unknown = counts == null ? 0 : counts.unknownCostDocuments();
        return new GrossMarginDocumentsPage(content, page, documents, unknown);
    }

    /**
     * Gross margin by store over a range: one bucket per {@code (currency, store)} that traded in the range.
     *
     * <p>Its own {@code GROUP BY}, over the whole range — never a fold of the paged document report, which would
     * state a store total that describes one page. A bucket holding a document whose cost never posted reports
     * {@code cost} and {@code margin} as {@code null} beside the count of documents that caused it, so the
     * missing cost is never totalled as zero ({@code S15}).</p>
     *
     * @param companyId the company whose book is read; another company's is a 403
     * @param from the first business date of the range, inclusive, or {@code null} for an open start
     * @param to the last business date of the range, inclusive, or {@code null} for an open end
     * @return one bucket per store and currency that traded in the range
     */
    @Transactional(readOnly = true)
    public GrossMarginStoresResponse grossMarginByStore(UUID companyId, LocalDate from, LocalDate to) {
        currentUserContext.verifyCompanyAccess(companyId);
        var range = range(from, to);
        var stores = jdbc.query(
                STORE_MARGIN,
                (result, index) -> {
                    var revenue = result.getBigDecimal("revenue");
                    var unknown = result.getInt("unknown_cost_documents");
                    // An unknown cost takes the whole bucket with it (S15): the revenue is summed because it is
                    // known for every document, but a cost total over a missing part is not a total.
                    var cost = unknown > 0 ? null : result.getBigDecimal("cost");
                    return new GrossMarginStoreResponse(
                            result.getObject("company_store_id", UUID.class),
                            result.getString("store_name"),
                            result.getString("currency"),
                            revenue,
                            cost,
                            cost == null ? null : revenue.subtract(cost),
                            result.getInt("documents"),
                            unknown);
                },
                companyId,
                range.from(),
                range.to(),
                companyId,
                companyId);
        return new GrossMarginStoresResponse(stores);
    }

    /**
     * The two numbers the paged margin response states about the range: how many documents it holds and how many
     * of them have no cost. Both come from one statement, so the pair is one snapshot.
     */
    private record MarginCounts(long documents, long unknownCostDocuments) {}

    /** One document row of the margin, from the paged statement. */
    private static final RowMapper<GrossMarginDocumentResponse> DOCUMENT_MARGIN_ROW_MAPPER = (result, index) -> {
        var revenue = result.getBigDecimal("revenue");
        var cost = result.getBigDecimal("cost");
        return new GrossMarginDocumentResponse(
                result.getObject("document_id", UUID.class),
                result.getString("order_number"),
                result.getDate("order_date").toLocalDate(),
                result.getObject("company_store_id", UUID.class),
                result.getString("store_name"),
                result.getString("currency"),
                revenue,
                cost,
                cost == null ? null : revenue.subtract(cost));
    };

    /**
     * Validates the range and replaces its open ends with the sentinels the queries compare against.
     *
     * @throws InvalidAccountingRangeException when the end is before the start, naming both dates
     */
    private static Range range(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidAccountingRangeException(from, to);
        }
        return new Range(from == null ? OPEN_START : from, to == null ? OPEN_END : to);
    }

    /** The two bounds a query compares against, each one present. */
    private record Range(LocalDate from, LocalDate to) {}

    /**
     * Folds the journal's rows into entries: the statement returns one row per line, and an entry's currency is
     * read from its first line because the poster denominates every line of one entry in the fact's currency.
     */
    private static final ResultSetExtractor<List<SalesJournalEntryResponse>> JOURNAL_EXTRACTOR = resultSet -> {
        Map<UUID, JournalEntry> entries = new LinkedHashMap<>();
        while (resultSet.next()) {
            var id = resultSet.getObject("id", UUID.class);
            var entry = entries.get(id);
            if (entry == null) {
                entry = new JournalEntry(
                        id,
                        resultSet.getDate("entry_date").toLocalDate(),
                        resultSet.getString("source_type"),
                        resultSet.getObject("source_id", UUID.class),
                        resultSet.getString("posting"),
                        resultSet.getString("actor"),
                        resultSet.getString("currency"));
                entries.put(id, entry);
            }
            entry.lines.add(new SalesJournalLineResponse(
                    resultSet.getString("code"),
                    resultSet.getString("name"),
                    resultSet.getBigDecimal("debit"),
                    resultSet.getBigDecimal("credit")));
        }
        return entries.values().stream().map(JournalEntry::toResponse).toList();
    };

    /** One entry while its lines are being collected. */
    private static final class JournalEntry {

        private final UUID id;
        private final LocalDate entryDate;
        private final String sourceType;
        private final UUID sourceId;
        private final String posting;
        private final String actor;
        private final String currency;
        private final List<SalesJournalLineResponse> lines = new ArrayList<>();

        private JournalEntry(
                UUID id,
                LocalDate entryDate,
                String sourceType,
                UUID sourceId,
                String posting,
                String actor,
                String currency) {
            this.id = id;
            this.entryDate = entryDate;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.posting = posting;
            this.actor = actor;
            this.currency = currency;
        }

        private SalesJournalEntryResponse toResponse() {
            return new SalesJournalEntryResponse(
                    id, entryDate, sourceType, sourceId, posting, actor, currency, List.copyOf(lines));
        }
    }
}
