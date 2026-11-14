package com.lifecontrol.api.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The gross margin of one sales document: what it earned, what it cost, and the difference.
 *
 * <p><b>{@code cost} and {@code margin} are nullable, and that is the point (S15).</b> A document whose revenue
 * posted and whose {@code COST_OF_SALES} never did — a sale charged before the ledger filled, or one whose
 * stock never moved — has an <em>unknown</em> cost. Reporting it as {@code 0} would claim the goods were free,
 * and inferring it from today's {@code product_variants.cost_price} would silently rewrite a past margin; both
 * are the failure {@code S15} names. So the cost is {@code null}, the margin with it, and the report counts the
 * document so the absence is visible rather than arithmetic.</p>
 *
 * <p>{@code currency} is the document's own, read from the entry's lines. A report that mixed currencies would
 * need an exchange rate, and ADR-0002 left those out on purpose: two documents in two currencies are two rows
 * and never one total.</p>
 */
public record GrossMarginDocumentResponse(
        UUID documentId,
        String orderNumber,
        LocalDate orderDate,
        UUID storeId,
        String storeName,
        String currency,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal margin) {}
