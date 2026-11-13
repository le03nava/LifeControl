package com.lifecontrol.api.accounting;

import java.util.List;

/**
 * The money-moving anchors the book posts from, as a closed, ordered collection: each one names the append-only
 * table the fact is read from and the one {@code posting} its rule must carry.
 *
 * <p><b>What an anchor is, and why it is append-only.</b> ADR-0008 decision 3 derives the book from facts this
 * tree already writes and cannot rewrite: {@code status_transition} records the move into a terminal state,
 * {@code sale_payment} records money received, {@code stock_allocation} records the cost of the units a sale
 * consumed. The editable document — {@code sales_orders} — is never an anchor, because a document that can be
 * amended cannot be the source of an entry that cannot. These three tables are the input set of the whole book,
 * which is why they are declared here once instead of being discovered in three posting services.</p>
 *
 * <p><b>Why this list is the source of truth for S10's assertion.</b>
 * {@code AccountingCatalogStartupCheck} refuses to start when a declared anchor has no posting rule in
 * {@link com.lifecontrol.api.accounting.catalog.StarterAccountingCatalog}, when a rule names an account the
 * chart does not declare, when an anchor's table does not resolve in {@code pg_catalog}, or when the database's
 * closed {@code source_type} vocabulary and this list disagree. Every one of those clauses reads its
 * expectations from here, so the assertion is exactly as strong as this list is long: a slice that deleted an
 * entry because its rule was not written yet would leave the startup check green over a source that posts
 * nowhere. {@code PostingAnchorTest} pins the three entries literally so that deletion is a red build.</p>
 *
 * <p><b>And why the assertion reads no tenant's rows.</b> No runtime credential in this stack can read across
 * tenants — {@code lifecontrol_owner} is {@code NOSUPERUSER} without {@code BYPASSRLS} and a {@code FORCE}d
 * policy reaches it, {@code lifecontrol_app} is {@code NOBYPASSRLS}, and {@code lifecontrol_bootstrap} has no
 * password — so "does every tenant have its rule" answered at boot would return zero rows and pass while the
 * book was short. That is why the per-tenant half of S10 lives in the company creation transaction
 * ({@code StarterAccountingCatalogListener}) and only the <b>catalog</b> is asserted here.</p>
 *
 * <p><b>What this list does not say.</b> It does not say which accounts a rule moves; that is the rule's own
 * fact and it is declared in {@code StarterAccountingCatalog}. It does not say how a source row is identified
 * (a transition's id, a payment's id, an outbound movement's {@code source_id}); that belongs to the posting
 * code of B3. And it declares no {@code REVERSAL} anchor: a reversal mirrors the lines of the entry it names
 * and reads no new source table, so an entry for it here would demand a rule nothing reads.</p>
 */
public final class PostingAnchor {

    /**
     * One anchor: the {@code posting_rule.source_type} value, the append-only table the fact is read from, and
     * the one {@code posting} the rule for it must declare.
     *
     * <p>{@code sourceType} is the spelling {@code ck_posting_rule_source_type} closes (`V98`), not a name of
     * this class's choosing: the two are asserted equal at startup, so a value here that the constraint does
     * not admit refuses the boot instead of storing a rule nobody can read.</p>
     */
    public record Anchor(String sourceType, String table, String posting) {}

    private static final List<Anchor> ANCHORS = List.of(
            new Anchor("STATUS_TRANSITION", "status_transition", "REVENUE"),
            new Anchor("SALE_PAYMENT", "sale_payment", "COLLECTION"),
            new Anchor("STOCK_ALLOCATION", "stock_allocation", "COST_OF_SALES"));

    private PostingAnchor() {}

    /**
     * The three anchors, in the order a sale produces them: the document completes, the money arrives, the cost
     * is consumed. The list is immutable — an anchor added at runtime would be a rule requirement nothing could
     * satisfy at boot — and every accessor below reads this one constant.
     */
    public static List<Anchor> anchors() {
        return ANCHORS;
    }
}
