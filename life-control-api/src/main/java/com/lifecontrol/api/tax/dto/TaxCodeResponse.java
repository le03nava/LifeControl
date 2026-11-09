package com.lifecontrol.api.tax.dto;

/**
 * One row of the platform tax-code catalog, as {@code GET /api/tax-codes} serves it.
 *
 * <p><b>Why exactly these three fields.</b> They are the three columns of {@code tax_code}
 * ({@code V82:23-26}) and nothing else: the catalog has no timestamps and no generated id, because the code
 * is the identity — {@code tax_rate.tax_code} references it and a document snapshots it. {@code label} is
 * presentation only: the string a form prints and a reviewed migration may rewrite, never what a caller
 * filters by. {@code enabled} is served rather than filtered away silently so a retired code stays visible
 * under {@code ?includeDisabled=true} with its own flag, instead of disappearing from a shape that could
 * not say why.</p>
 *
 * <p><b>Why the shape is flat.</b> It is the same shape {@code CurrencyResponse} has — one row, one
 * object, no envelope and no grouping by "needs a rate" versus "zero by definition" (D-a of
 * {@code odd/tasks/product-tax-code.md}): which codes resolve to zero without a rate is a rule of the
 * resolver, and a client that needed it would be re-deriving a server decision from a field this catalog
 * does not have.</p>
 */
public record TaxCodeResponse(String code, String label, Boolean enabled) {}
