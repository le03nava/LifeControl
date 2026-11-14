package com.lifecontrol.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verification of {@code V100__accounting_postings.sql} — S11, S12, S13, S14 and S15 of
 * {@code odd/tasks/derived-accounting.md} (T6, T7, T8) — against real PostgreSQL, because every rule of this
 * slice is a property of the database: a trigger that fires on an append-only fact, a deferred trigger that
 * has to see the allocations a transaction commits, and a policy that decides whether a rule's lines are
 * visible at all. A service cannot demonstrate any of them.
 *
 * <p><b>Which role every case runs as, and why that is the point.</b> The pool logs in as
 * {@code lifecontrol_app} (record T6b-4), so every statement below — the fixtures, the fact and the read-back —
 * runs as the role the policies apply to, inside one test transaction that states the tenant with
 * {@link TenantFixtures#stateTenantWithinTheOpenTransaction}. That is what makes the poster's refusal
 * meaningful rather than decorative: {@code posting_rule} and {@code posting_rule_line} are
 * {@code ENABLE}+{@code FORCE}d, so the poster resolves its rule's lines <b>under the transaction's own
 * scope</b> or answers zero rows. A suite that wrote these rows as a superuser would prove the arithmetic and
 * nothing about the production path.</p>
 *
 * <p><b>How a deferred rule is observed without committing.</b> The cost posting hangs on a
 * {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger, so it judges the allocation set a transaction
 * commits, not each row it wrote. Every case that expects an entry calls {@link #settle()}
 * ({@code SET CONSTRAINTS ALL IMMEDIATE}, the same pass the commit would run, fired by hand), and every case
 * that expects an entry also gets the two {@code V97} balance triggers in that same pass — which is what makes
 * "the entry balances" an assertion about the schema instead of an assumption about the poster. Nothing is
 * committed: the class is {@code @Transactional} and the shared container must not keep an append-only entry
 * that no later suite asked for.</p>
 *
 * <p><b>What each clause of the spec observes, and how.</b> S11 (revenue is anchored on the transition, dated
 * and denominated by the document) by {@link #revenuePostsFromTheCompletionTransitionInTheDocumentsCurrency()};
 * its negative controls by {@link #aTransitionToAnotherStateOrOfAnotherTypePostsNothing()}. S12 (a collection
 * is a payment row, a refund is its mirror) by {@link #aChargePostsTwoLines()} and
 * {@link #aRefundPostsTheMirrorImageAndNeverANegativeAmount()}. S13 (the cost is the allocation sum) by
 * {@link #theAllocatedCostPostsOnceAsTheSumOfEveryAllocation()} and
 * {@link #aCostThatArrivesAgainWithADifferentSumIsRefusedNamingBothAmounts()}; S15 (no cost is not a cost of
 * zero) by {@link #aChargedSaleWithNoAllocationPostsNoCostEntryAtAll()}. S14 (the analytic axis) by
 * {@link #theCostCenterIsFilledOnlyWhenTheStoresCenterIsUnambiguous()}. The refusal that is not a silent no-op
 * is {@link #aCompanyWithoutItsRevenueRuleIsRefusedByName()}.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Accounting posting integration tests (V100)")
class AccountingPostingIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The only currency the product names today, and the one every fixture company operates in. */
    private static final String MXN = "MXN";

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

    /**
     * The document sequence this suite states, one value per document it writes: `uq_sales_orders_doc_identity`
     * is unique over `(company_id, doc_type, seq_period, seq_value)` and a fixture that reused one number for
     * every order would be refused by the schema rather than by the rule under test.
     */
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private JdbcTemplate jdbc;

    /** The tenant every case works on, created with its own id as the stated scope. */
    private UUID company;

    // ──────────────────────────────────────
    // S11 — revenue posts from the completion transition, on the document's date
    // ──────────────────────────────────────

    @Test
    @DisplayName("S11: the completion transition posts one entry dated on the document's order_date, with the "
            + "receivable, the revenue and the tax as three lines that balance")
    void revenuePostsFromTheCompletionTransitionInTheDocumentsCurrency() {
        var store = openBook("S11");
        var order = salesOrder(store.id(), "2026-10-05 15:30:00", "100", "16", "116");
        // The transition is recorded nearly six weeks after the document's date on purpose: a late completion
        // that took its date from `occurred_at` would report October's revenue in November, in another period.
        var transition = transition(order, "PENDING", "COMPLETED", "2026-11-20 09:00:00");

        settle();

        var entries = entries("REVENUE");
        assertThat(entries)
                .as("S11: the anchor is the append-only transition, and one completion posts one entry")
                .hasSize(1);
        var entry = entries.get(0);
        assertThat(entry.entryDate())
                .as("S11: the entry carries the document's business date — `sales_orders.order_date` — and not "
                        + "the moment the transition was recorded (2026-11-20)")
                .isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(entry.sourceType()).isEqualTo("STATUS_TRANSITION");
        assertThat(entry.sourceId())
                .as("the source is the transition row, which is what a replay and a reversal name")
                .isEqualTo(transition);
        assertThat(entry.currency()).isEqualTo(MXN);
        assertThat(entry.actor()).isEqualTo("suite:actor");

        var lines = lines(entry.id());
        assertThat(lines)
                .as("S11: revenue, IVA trasladado and the receivable are three parts")
                .hasSize(3);
        assertLine(line(lines, "1201"), "1201", "116", "0");
        assertLine(line(lines, "4101"), "4101", "0", "100");
        assertLine(line(lines, "2101"), "2101", "0", "16");

        assertThat(total(lines, "debit"))
                .as("TOTAL = REVENUE + TAX, so the entry balances by construction and not by a rounding line")
                .isEqualByComparingTo("116");
        assertThat(total(lines, "credit")).isEqualByComparingTo("116");
    }

    @Test
    @DisplayName("S11: a transition to another state, or a transition of another document type, posts nothing")
    void aTransitionToAnotherStateOrOfAnotherTypePostsNothing() {
        var store = openBook("S11-NEG");
        var cancelled = salesOrder(store.id(), "2026-10-06 10:00:00", "100", "16", "116");
        transition(cancelled, "PENDING", "CANCELLED", "2026-10-06 11:00:00");

        // A purchase order's state machine has no COMPLETED, so this row is the one shape that isolates the
        // trigger's `document_type` condition from its `to_status` condition: the transition is legal for
        // `status_transition` itself and would be refused later by the deferred tie to the document, which is
        // why this case settles nothing — its subject is the poster's WHEN clause, and the rollback at the end
        // of the test drops the row either way.
        var purchaseOrder = purchaseOrder(store.id());
        recordCreation(purchaseOrder, "PURCHASE_ORDER", "CLOSED", "2026-10-07 08:00:00");
        jdbc.update("""
                INSERT INTO status_transition
                    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at)
                VALUES (?, 'PURCHASE_ORDER', ?, 'CLOSED', 'COMPLETED', 'suite:actor', ?::timestamptz)
                """, company, purchaseOrder, "2026-10-07 09:00:00");

        assertThat(entries("REVENUE"))
                .as("a cancellation is not revenue, and a purchase order is not a sale: neither writes an entry")
                .isEmpty();
    }

    @Test
    @DisplayName("S11/S10: a company whose revenue rule was deleted is refused by name instead of posting nothing")
    void aCompanyWithoutItsRevenueRuleIsRefusedByName() {
        var store = openBook("S11-MISSING");
        var order = salesOrder(store.id(), "2026-10-08 10:00:00", "100", "16", "116");
        // The policy hides an absent line set exactly as well as it hides another tenant's, which is why the
        // poster cannot tell "nothing to post" from "the rule is gone" and has to refuse instead of returning.
        jdbc.update("""
                DELETE FROM posting_rule_line l
                 USING posting_rule r
                 WHERE l.rule_id = r.id AND r.company_id = ? AND r.posting = 'REVENUE'
                """, company);
        jdbc.update("DELETE FROM posting_rule WHERE company_id = ? AND posting = 'REVENUE'", company);

        var thrown = refusal(() -> transition(order, "PENDING", "COMPLETED", "2026-10-08 11:00:00"));
        assertThat(rootCauseMessage(thrown))
                .as("the refusal names the company, the source type and the posting: an operator has to be able to "
                        + "tell which rule is missing without reading the function")
                .contains(company.toString())
                .contains("STATUS_TRANSITION")
                .contains("REVENUE")
                .contains("posts nothing");

        assertThat(entries("REVENUE"))
                .as("the refused statement stored no entry: a missing rule cannot leave half a book behind")
                .isEmpty();
        assertThat(countEntries()).as("and nothing else was written either").isZero();
    }

    @Test
    @DisplayName("S11: a document with no tax posts no zero line, and a document worth nothing posts no entry")
    void aZeroPartIsNotALine() {
        var store = openBook("S11-ZERO");
        var exempt = salesOrder(store.id(), "2026-10-08 12:00:00", "100", "0", "100");
        transition(exempt, "PENDING", "COMPLETED", "2026-10-08 12:30:00");
        // A document whose every part is zero has nothing to record: writing an entry all of whose lines are
        // zero is refused by `ck_account_entry_line_positive`, and dropping the lines would leave an entry with
        // none, which A2 refuses at commit. The rule's presence was asserted before the amounts were read, so
        // this silence cannot be a missing rule wearing a zero.
        //
        // Both documents are written before the pass below, because `SET CONSTRAINTS ALL IMMEDIATE` holds for the
        // rest of the transaction: a second order written afterwards would be judged by `V68`'s deferred tie at
        // the end of its own `INSERT`, before its creation row exists.
        var empty = salesOrder(store.id(), "2026-10-08 13:00:00", "0", "0", "0");
        transition(empty, "PENDING", "COMPLETED", "2026-10-08 13:30:00");

        settle();

        var exemptEntry = only(entries("REVENUE"));
        assertThat(lines(exemptEntry.id()))
                .as("`TAX = 0` is a stated value and not a missing part, so the entry is still two lines and the "
                        + "tax account is simply not named: `ck_account_entry_line_positive` refuses a zero line, "
                        + "and a line worth nothing moves nothing (TOTAL = REVENUE + TAX still holds)")
                .hasSize(2);
        assertLine(line(lines(exemptEntry.id()), "1201"), "1201", "100", "0");
        assertLine(line(lines(exemptEntry.id()), "4101"), "4101", "0", "100");

        assertThat(entries("REVENUE"))
                .as("one entry, for the exempt sale; the zero document added none")
                .hasSize(1);
    }

    // ──────────────────────────────────────
    // S12 — a collection posts from a payment row, and a refund is its mirror
    // ──────────────────────────────────────

    @Test
    @DisplayName("S12: a CHARGE posts cash against the receivable, on the day the money was taken")
    void aChargePostsTwoLines() {
        var store = openBook("S12");
        var order = salesOrder(store.id(), "2026-10-09 10:00:00", "100", "16", "116");
        transition(order, "PENDING", "COMPLETED", "2026-10-09 10:05:00");
        // Paid three days after the document, so the two dates cannot be confused for one another.
        var payment = payment(order, "CHARGE", "116", "2026-10-12 18:45:00");

        settle();

        var entry = only(entries("COLLECTION"));
        assertThat(entry.entryDate())
                .as("S12: the collection belongs to the day the money arrived, not to the document's date")
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(entry.sourceType()).isEqualTo("SALE_PAYMENT");
        assertThat(entry.sourceId()).isEqualTo(payment);
        assertThat(entry.currency())
                .as("`sale_payment` carries no currency column, so it comes from the order it pays")
                .isEqualTo(MXN);

        var lines = lines(entry.id());
        assertThat(lines).hasSize(2);
        assertLine(line(lines, "1101"), "1101", "116", "0");
        assertLine(line(lines, "1201"), "1201", "0", "116");
    }

    @Test
    @DisplayName("S12: a REFUND swaps the sides of the charge, and no line carries a negative amount")
    void aRefundPostsTheMirrorImageAndNeverANegativeAmount() {
        var store = openBook("S12-REFUND");
        var order = salesOrder(store.id(), "2026-10-09 10:00:00", "100", "16", "116");
        transition(order, "PENDING", "COMPLETED", "2026-10-09 10:05:00");
        var refund = payment(order, "REFUND", "40", "2026-10-14 12:00:00");

        settle();

        var entry = only(entries("COLLECTION"));
        assertThat(entry.sourceId()).isEqualTo(refund);
        var lines = lines(entry.id());
        assertThat(lines).hasSize(2);
        // The mirror, and the flip is on the side and never on the sign: `ck_account_entry_line_non_negative`
        // and `ck_account_entry_line_positive` refuse a negative amount, so a `-40` debit would not be a refund,
        // it would be a refused row.
        assertLine(line(lines, "1201"), "1201", "40", "0");
        assertLine(line(lines, "1101"), "1101", "0", "40");
        assertThat(lines)
                .as("every amount on the book is money: neither side is negative, on either column")
                .allSatisfy(posted -> {
                    assertThat(posted.debit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                    assertThat(posted.credit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                });
    }

    // ──────────────────────────────────────
    // S13 — cost of sales posts from the allocation sum, once
    // ──────────────────────────────────────

    @Test
    @DisplayName("S13: one sale movement posts one cost entry worth the sum of its allocations, at commit")
    void theAllocatedCostPostsOnceAsTheSumOfEveryAllocation() {
        var store = openBook("S13");
        var variant = variant();
        receipt(store.locationId(), variant, "3", "10", "2026-10-01 09:00:00");
        receipt(store.locationId(), variant, "4", "12", "2026-10-02 09:00:00");
        // Five units over a three and a four consume two inbound rows under the default FIFO policy, so the
        // trigger fires twice at commit and the entry it writes must still be one.
        var movement = saleMovement(store.locationId(), variant, "5", "2026-10-15 17:00:00");
        assertThat(allocate(movement))
                .as("two allocated rows, one per consumed receipt")
                .isEqualTo(2);

        settleCost();

        var entry = only(entries("COST_OF_SALES"));
        assertThat(entry.sourceType()).isEqualTo("STOCK_ALLOCATION");
        assertThat(entry.sourceId())
                .as("S13/L13(d): the source is the outbound movement, which is the row the allocation sum "
                        + "describes, and not the allocation row the trigger happened to fire for")
                .isEqualTo(movement);
        assertThat(entry.entryDate())
                .as("S13: the cost belongs to the day the goods left")
                .isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(entry.currency())
                .as("every sale row this tree books carries no currency of its own — `StockLedgerWriter.bookSale` "
                        + "writes NULL, which `ck_movement_inbound_cost` allows on an outbound row — so the book "
                        + "denominates it in the company's operating currency, the one ADR-0002 decision 2 makes "
                        + "the only currency a document can be in")
                .isEqualTo(MXN);
        assertThat(entry.actor()).isEqualTo("suite:actor");

        var lines = lines(entry.id());
        assertThat(lines).hasSize(2);
        // 3 × 10 + 2 × 12 = 54, which is the sum over the allocations as committed and not one allocation's own
        // amount: a poster that read the row that fired would write 30 and the second firing would write 24.
        assertLine(line(lines, "5101"), "5101", "54", "0");
        assertLine(line(lines, "1301"), "1301", "0", "54");
        assertThat(entries("COST_OF_SALES"))
                .as("the second firing recomputed the same sum and wrote nothing: a replay of the same fact is "
                        + "the same fact (S6's key plus the poster's own comparison)")
                .hasSize(1);
    }

    @Test
    @DisplayName("S13: a cost that arrives again with a different sum is refused, naming both amounts")
    void aCostThatArrivesAgainWithADifferentSumIsRefusedNamingBothAmounts() {
        var store = openBook("S13-CONFLICT");
        var variant = variant();
        var firstReceipt = receipt(store.locationId(), variant, "10", "10", "2026-10-01 09:00:00");
        var secondReceipt = receipt(store.locationId(), variant, "10", "12", "2026-10-02 09:00:00");
        var movement = saleMovement(store.locationId(), variant, "4", "2026-10-15 17:00:00");

        // The first allocation settles on its own, which is how the entry gets written before the second
        // allocation exists — the deferred trigger runs while the fact is still growing.
        insertAllocation(movement, firstReceipt, "4", "10");
        settleCost();
        assertThat(lines(only(entries("COST_OF_SALES")).id()))
                .as("the first allocation posted 40")
                .anySatisfy(posted -> assertThat(posted.debit()).isEqualByComparingTo("40"));

        var thrown = refusal(() -> insertAllocation(movement, secondReceipt, "2", "12"));
        assertThat(rootCauseMessage(thrown))
                .as("the conflict is not a no-op: the stored entry says 40 and the allocations now say 64, and a "
                        + "poster that swallowed this would leave the cost of sales short forever")
                .contains("40.0000")
                .contains("64.0000")
                .contains(movement.toString());

        assertThat(entries("COST_OF_SALES")).hasSize(1);
        var stored = lines(only(entries("COST_OF_SALES")).id());
        assertLine(line(stored, "5101"), "5101", "40", "0");
        assertLine(line(stored, "1301"), "1301", "0", "40");
    }

    @Test
    @DisplayName("S15: a charged sale whose stock was never allocated has no cost entry at all")
    void aChargedSaleWithNoAllocationPostsNoCostEntryAtAll() {
        var store = openBook("S15");
        var order = salesOrder(store.id(), "2026-10-16 10:00:00", "100", "16", "116");
        transition(order, "PENDING", "COMPLETED", "2026-10-16 10:05:00");
        payment(order, "CHARGE", "116", "2026-10-16 10:06:00");

        settle();

        assertThat(entries("REVENUE")).as("the sale and the money are facts").hasSize(1);
        assertThat(entries("COLLECTION")).hasSize(1);
        assertThat(entries("COST_OF_SALES"))
                .as("S15: with no allocation the cost is unknown, and unknown is the absence of an entry — a zero "
                        + "would be a claim that the goods cost nothing, and it must not be inferred from today's "
                        + "`product_variants.cost_price` either")
                .isEmpty();
    }

    // ──────────────────────────────────────
    // S14 — the analytic axis, pre-filled only when it is unambiguous
    // ──────────────────────────────────────

    @Test
    @DisplayName("S14: the cost center is filled when the store has exactly one, and left null with zero or two")
    void theCostCenterIsFilledOnlyWhenTheStoresCenterIsUnambiguous() {
        var bare = openBook("S14-NONE");
        var single = newStore("S14-ONE");
        var ambiguous = newStore("S14-TWO");
        var firstDepartment = department("OPS");
        var secondDepartment = department("ADM");
        var operationCenter = costCenter(single.id(), firstDepartment, "CC-ONE");
        costCenter(ambiguous.id(), firstDepartment, "CC-TWO-A");
        costCenter(ambiguous.id(), secondDepartment, "CC-TWO-B");

        var bareEntry = collectionOn(bare, "2026-10-17 10:00:00");
        var singleEntry = collectionOn(single, "2026-10-17 11:00:00");
        var ambiguousEntry = collectionOn(ambiguous, "2026-10-17 12:00:00");
        settle();

        assertThat(lines(bareEntry))
                .as("S14: a store with no center leaves the axis null instead of guessing one")
                .allSatisfy(posted -> assertThat(posted.costCenterId()).isNull());
        assertThat(lines(singleEntry))
                .as("S14: the store the fact carries resolves to exactly one center, so every line of the entry "
                        + "states it — the axis is pre-filled from the store, which is a fact, and not chosen here")
                .hasSize(2)
                .allSatisfy(posted -> assertThat(posted.costCenterId()).isEqualTo(operationCenter));
        assertThat(lines(ambiguousEntry))
                .as("S14: two centers on one store are two answers to one question, and the poster does not pick "
                        + "one — a silent guess would split a report with nothing raising (ADR decision 10)")
                .allSatisfy(posted -> assertThat(posted.costCenterId()).isNull());
    }

    // ──────────────────────────────────────
    // fixtures: one tenant, its store tree, its chart and its store's cost centers
    // ──────────────────────────────────────

    /** One store of the tenant, with the location its ledger rows travel through. */
    private record Store(UUID id, UUID locationId) {}

    /** One posted line, as the suite reads it back: the account by code, never by id. */
    private record Posted(String accountCode, BigDecimal debit, BigDecimal credit, UUID costCenterId) {}

    /** One entry header, as the suite reads it back. */
    private record Entry(
            UUID id,
            LocalDate entryDate,
            String sourceType,
            UUID sourceId,
            String posting,
            String currency,
            String actor) {}

    /**
     * A company with its hierarchy, one store and the whole seeded chart, and that company stated as the
     * tenant of the open transaction.
     *
     * <p>The scope goes first because every statement below — and every trigger they fire — runs as
     * {@code lifecontrol_app} and meets the policy {@code V52} armed, exactly as production's per-request
     * boundary does.</p>
     */
    private Store openBook(String tag) {
        company = UUID.randomUUID();
        TenantFixtures.stateTenantWithinTheOpenTransaction(company, jdbc);
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES (?, ?, ?, ?, ?)",
                company,
                "V100-" + tag + "-" + company.toString().substring(0, 8),
                "V100 Company " + tag,
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
                VALUES (?, ?, ?, ?, 'V100 Region')
                """, region, company, companyCountry, region.toString().substring(0, 6));
        var zone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)
                VALUES (?, ?, ?, ?, 'V100 Zone')
                """, zone, company, region, zone.toString().substring(0, 6));
    }

    /**
     * One store of the current company, with the area → zone → location chain a movement's key is built from.
     * A second store is a second scope for the analytic axis, not a second tenant.
     */
    private Store newStore(String tag) {
        var store = UUID.randomUUID();
        var companyZone = jdbc.queryForObject("SELECT id FROM company_zones WHERE company_id = ?", UUID.class, company);
        jdbc.update(
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES (?, ?, ?, ?, true)",
                store,
                company,
                companyZone,
                "V100 Store " + tag);
        var area = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled)
                VALUES (?, ?, ?, 'V100 Area', true)
                """, area, store, area.toString().substring(0, 6));
        var zone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)
                VALUES (?, ?, ?, ?, 'V100 Store zone', true)
                """, zone, area, store, zone.toString().substring(0, 6));
        var location = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code, location_name, enabled)
                VALUES (?, ?, ?, ?, 'V100 Location', true)
                """, location, zone, store, location.toString().substring(0, 6));
        return new Store(store, location);
    }

    /** The eight accounts and the three rules with their seven lines, stated the way {@code V98}/{@code V99} seed them. */
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
        return jdbc.queryForObject("""
                INSERT INTO posting_rule (company_id, source_type, posting) VALUES (?, ?, ?) RETURNING id
                """, UUID.class, company, sourceType, posting);
    }

    private void line(UUID ruleId, String side, String selector, String accountCode) {
        var inserted = jdbc.update("""
                INSERT INTO posting_rule_line (company_id, rule_id, account_id, side, amount_selector)
                SELECT ?, ?, a.id, ?, ? FROM account a WHERE a.company_id = ? AND a.code = ?
                """, company, ruleId, side, selector, company, accountCode);
        assertThat(inserted).as("the chart declares account %s", accountCode).isEqualTo(1);
    }

    private UUID department(String code) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, company_id, department_code, department_name)
                VALUES (?, ?, ?, ?)
                """, id, company, "V100-" + code, "V100 Department " + code);
        return id;
    }

    private UUID costCenter(UUID storeId, UUID departmentId, String code) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cost_center (id, company_id, code, name, company_store_id, department_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, company, code, "V100 Center " + code, storeId, departmentId);
        return id;
    }

    // ──────────────────────────────────────
    // fixtures: the three facts, written through SQL the way their writers do
    // ──────────────────────────────────────

    /**
     * One sales order, in {@code PENDING} and with the creation transition its own history requires, stating
     * the three money parts {@code ck_order_total} adds up.
     */
    private UUID salesOrder(UUID storeId, String orderDate, String subtotal, String tax, String total) {
        var id = UUID.randomUUID();
        var customer = customer();
        var number = "V100-" + id.toString().substring(0, 8);
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
                MXN,
                subtotal,
                tax,
                total);
        recordCreation(id, "SALES_ORDER", "PENDING", orderDate);
        return id;
    }

    private UUID customer() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO customers (id, name, sales_channel) VALUES (?, ?, 'STORE')",
                id,
                "V100 Customer " + id.toString().substring(0, 8));
        return id;
    }

    /**
     * One {@code status_transition} row, and the document's own column moved to match it.
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
                "V100-" + suffix,
                "V100 Product",
                company);
        var measureUnit = jdbc.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
        var variant = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)
                VALUES (?, ?, ?, 'unica', ?, ?)
                """, variant, product, "V100-BC-" + suffix, measureUnit, company);
        return variant;
    }

    /** An inbound row: it adds quantity, so it states both what it cost and in which currency. */
    private UUID receipt(UUID locationId, UUID variantId, String quantity, String unitCost, String occurredAt) {
        return movement(locationId, variantId, "RECEIPT", quantity, unitCost, MXN, occurredAt);
    }

    /**
     * A sale outbound row, in the shape {@code StockLedgerWriter.bookSale} writes it: no cost and no currency of
     * its own, because its cost lives in {@code stock_allocation} and the company states the currency.
     */
    private UUID saleMovement(UUID locationId, UUID variantId, String quantity, String occurredAt) {
        return movement(locationId, variantId, "SALE", quantity, null, null, occurredAt);
    }

    private UUID movement(
            UUID locationId,
            UUID variantId,
            String movementType,
            String quantity,
            String unitCost,
            String currency,
            String occurredAt) {
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
                UUID.randomUUID());
        return id;
    }

    private UUID storeOf(UUID locationId) {
        return jdbc.queryForObject("SELECT company_store_id FROM store_locations WHERE id = ?", UUID.class, locationId);
    }

    /** The allocation writer, called the way the sale path calls it. */
    private int allocate(UUID outMovementId) {
        return jdbc.queryForObject("SELECT allocate_stock_movement(?)", Integer.class, outMovementId);
    }

    /**
     * One allocation written by hand, which is how a second batch arrives: the writer is idempotent and refuses
     * a movement that already has allocations, so the only way to observe a re-post with a different sum is to
     * state the row the writer would have written.
     */
    private void insertAllocation(UUID outMovementId, UUID inMovementId, String quantity, String unitCost) {
        jdbc.update("""
                INSERT INTO stock_allocation (company_id, out_movement_id, in_movement_id, quantity, unit_cost)
                VALUES (?, ?, ?, ?::numeric, ?::numeric)
                """, company, outMovementId, inMovementId, quantity, unitCost);
    }

    // ──────────────────────────────────────
    // reads
    // ──────────────────────────────────────

    /**
     * Runs the deferred constraint triggers now, which is the pass the commit would run — and, in the same
     * pass, the two {@code V97} triggers that refuse an entry with one line or with two unequal sides.
     */
    private void settle() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    /**
     * The same pass for the cost posting, in two steps and in this order.
     *
     * <p>The cost trigger writes an entry <b>from inside</b> a deferred trigger, and the two `V97` balance
     * triggers are deferred too. Switching <b>all</b> of them to immediate first makes the balance check of the
     * entry fire at the end of the very statement that inserted its header — before the poster has written its
     * lines — which is a property of {@code SET CONSTRAINTS}, not of a commit: at {@code COMMIT} the events
     * queue, so the balance check is queued by the poster and runs after its own lines. Firing the cost
     * trigger first and the balance triggers second reproduces that order by hand.</p>
     */
    private void settleCost() {
        jdbc.execute("SET CONSTRAINTS trg_stock_allocation_post_cost IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    /**
     * Runs a statement the book is expected to refuse, and rolls back to a savepoint so the transaction — and
     * the assertions after the refusal — survive.
     *
     * <p>PostgreSQL aborts the whole transaction block on any error, so a case that asserts "the refusal stored
     * nothing" cannot simply catch and continue. The savepoint is the boundary the assertion needs: what the
     * refused statement wrote is undone, and everything written before it is still there to be read.</p>
     */
    private Throwable refusal(Runnable statement) {
        jdbc.execute("SAVEPOINT expected_refusal");
        try {
            statement.run();
        } catch (DataAccessException refused) {
            jdbc.execute("ROLLBACK TO SAVEPOINT expected_refusal");
            jdbc.execute("RELEASE SAVEPOINT expected_refusal");
            return refused;
        }
        jdbc.execute("RELEASE SAVEPOINT expected_refusal");
        throw new AssertionError("the book accepted a statement it was expected to refuse");
    }

    private int countEntries() {
        return jdbc.queryForObject("SELECT count(*) FROM account_entry WHERE company_id = ?", Integer.class, company);
    }

    private List<Entry> entries(String posting) {
        return jdbc.query(
                """
                SELECT e.id, e.entry_date, e.source_type, e.source_id, e.posting, e.actor,
                       (SELECT min(l.currency)
                          FROM account_entry_line l
                         WHERE l.company_id = e.company_id AND l.entry_id = e.id) AS currency
                  FROM account_entry e
                 WHERE e.company_id = ? AND e.posting = ?
                 ORDER BY e.created_at, e.id
                """,
                (result, index) -> new Entry(
                        result.getObject("id", UUID.class),
                        result.getDate("entry_date").toLocalDate(),
                        result.getString("source_type"),
                        result.getObject("source_id", UUID.class),
                        result.getString("posting"),
                        result.getString("currency").trim(),
                        result.getString("actor")),
                company,
                posting);
    }

    private Entry only(List<Entry> entries) {
        assertThat(entries).as("exactly one entry").hasSize(1);
        return entries.get(0);
    }

    private List<Posted> lines(UUID entryId) {
        return jdbc.query(
                """
                SELECT a.code, l.debit, l.credit, l.cost_center_id
                  FROM account_entry_line l
                  JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
                 WHERE l.company_id = ? AND l.entry_id = ?
                 ORDER BY a.code
                """,
                (result, index) -> new Posted(
                        result.getString("code"),
                        result.getBigDecimal("debit"),
                        result.getBigDecimal("credit"),
                        result.getObject("cost_center_id", UUID.class)),
                company,
                entryId);
    }

    private Posted line(List<Posted> lines, String accountCode) {
        var matching = lines.stream()
                .filter(posted -> posted.accountCode().equals(accountCode))
                .toList();
        assertThat(matching).as("one line against account %s", accountCode).hasSize(1);
        return matching.get(0);
    }

    private BigDecimal total(List<Posted> lines, String side) {
        return lines.stream()
                .map(posted -> "debit".equals(side) ? posted.debit() : posted.credit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertLine(Posted posted, String accountCode, String debit, String credit) {
        assertThat(posted.accountCode()).isEqualTo(accountCode);
        assertThat(posted.debit()).as("%s debit", accountCode).isEqualByComparingTo(debit);
        assertThat(posted.credit()).as("%s credit", accountCode).isEqualByComparingTo(credit);
    }

    /** Completes a sale against one store and answers the entry its collection produced. */
    private UUID collectionOn(Store store, String paidAt) {
        var order = salesOrder(store.id(), "2026-10-16 10:00:00", "100", "16", "116");
        transition(order, "PENDING", "COMPLETED", "2026-10-16 10:05:00");
        var payment = payment(order, "CHARGE", "116", paidAt);
        // By source, not by date: the three stores of the axis case are charged the same day on purpose, and a
        // read by date would answer the same entry three times.
        return jdbc.queryForObject("""
                SELECT id FROM account_entry
                 WHERE company_id = ? AND source_type = 'SALE_PAYMENT' AND source_id = ? AND posting = 'COLLECTION'
                """, UUID.class, company, payment);
    }

    /** One purchase order, the shape the fixture states: no money parts, because nothing reads them here. */
    private UUID purchaseOrder(UUID storeId) {
        var supplier = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO suppliers (id, company_id, supplier_name, rfc) VALUES (?, ?, ?, ?)",
                supplier,
                company,
                "V100 Supplier",
                "PS" + supplier.toString().replace("-", "").substring(0, 10).toUpperCase());
        var id = UUID.randomUUID();
        var number = "V100-PO-" + id.toString().substring(0, 8);
        var method =
                jdbc.queryForObject("SELECT id FROM payment_methods ORDER BY payment_method_name LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, order_number, supplier_id, company_store_id, payment_method_id, status, company_id,
                     doc_type, seq_period, seq_value, doc_number)
                VALUES (?, ?, ?, ?, ?, 'CLOSED', ?, 'PURCHASE_ORDER', '202610', ?, ?)
                """, id, number, supplier, storeId, method, company, SEQ.incrementAndGet(), number);
        return id;
    }

    /** The driver's own sentence, at the bottom of the Spring exception chain. */
    private static String rootCauseMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
