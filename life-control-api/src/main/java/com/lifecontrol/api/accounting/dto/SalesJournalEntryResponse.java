package com.lifecontrol.api.accounting.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One entry of the sales journal with its lines — the row {@code S18} names, and the one a drawer report
 * cites: when the fact happened, which fact it is, which posting it belongs to, who wrote it and in which
 * currency.
 *
 * <p><b>{@code sourceType} plus {@code sourceId} are the entry's identity and not decoration.</b> The book posts
 * once per {@code (company, source type, source id, posting)} ({@code V97}'s
 * {@code uq_account_entry_company_source_posting}), so that triple is what a reader needs to find the fact the
 * entry was derived from and what a reversal names (S17). It is deliberately not resolved into a document
 * number here: the source of a revenue entry is a transition, the source of a collection is a payment row and
 * the source of a cost is a stock movement, so a single "document" column would be a different fact per posting.
 *
 * <p>{@code currency} is the entry's own, from its lines: every line of one entry carries the currency the
 * poster read from the fact, and the book never translates between currencies (ADR-0002).
 */
public record SalesJournalEntryResponse(
        UUID id,
        LocalDate entryDate,
        String sourceType,
        UUID sourceId,
        String posting,
        String actor,
        String currency,
        List<SalesJournalLineResponse> lines) {}
