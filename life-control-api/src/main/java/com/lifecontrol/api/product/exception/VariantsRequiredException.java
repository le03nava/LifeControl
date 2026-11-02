package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.FieldViolationException;

/**
 * Thrown when a creation request declares no variant.
 *
 * <p>ADR-0006 decision 2 makes the variant list part of the creation instead of a step after it — "a
 * product always has at least one variant, and creating one is one transaction" — and invariant C1 states
 * the same fact as a rule. The schema forces the reading: {@code product_variants.variant_name} is
 * {@code NOT NULL} and nothing may invent one, so a product nobody declared a variant for is an incomplete
 * creation, not a catalog entry.</p>
 *
 * <p>Subclass of {@link FieldViolationException}: the 400 carries {@code errors: {variants: <message>}}
 * next to the same sentence in {@code message}, so the client marks the variant block instead of reading the
 * prose. The key is the <b>list</b> and not a row, because a request that declares no variant has no row to
 * point at. It is thrown before any row is written, so no product the next read has to report as broken is
 * left behind.</p>
 */
public class VariantsRequiredException extends FieldViolationException {

    public VariantsRequiredException() {
        super(
                "variants",
                "variants is required: a product is created with at least one variant — SIMPLE means exactly "
                        + "one and MULTI declares its family in the same request (ADR-0006 decision 2)");
    }
}
