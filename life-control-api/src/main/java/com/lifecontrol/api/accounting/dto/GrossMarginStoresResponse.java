package com.lifecontrol.api.accounting.dto;

import java.util.List;

/**
 * Gross margin by store over a date range: one bucket per {@code (currency, store)} that has documents in the
 * range.
 *
 * <p><b>One statement, one group by.</b> The buckets are computed by their own SQL — {@code GROUP BY (currency,
 * store)} over the whole range, revenue and cost together — and not by folding the per-document rows: that report
 * is now paged, and folding a page gives a store total that silently describes one page. The two reports are
 * therefore two statements over the same range in one read-only transaction, and they agree because they read the
 * same facts with the same predicates, not because one is derived from the other. They would diverge if one of
 * them changed a predicate the other kept (a cost filtered by the entry's date, say) or if a posting committed
 * between the two calls — each statement takes its own snapshot under {@code READ COMMITTED}, so a caller that
 * needs one cut of the book reads the store report alone, which is internally one statement.</p>
 *
 * <p>A store with no document in the range has no bucket instead of a zero one: printing every store of the
 * company would state a margin of nothing for a store that did not trade, which is the same confusion between
 * "zero" and "unknown" one level up.</p>
 */
public record GrossMarginStoresResponse(List<GrossMarginStoreResponse> stores) {}
