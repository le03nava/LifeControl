package com.lifecontrol.api.product.exception;

/**
 * Thrown when a creation request declares no variant.
 *
 * <p>ADR-0006 decision 2 makes the variant list part of the creation instead of a step after it — "a
 * product always has at least one variant, and creating one is one transaction" — and invariant C1 states
 * the same fact as a rule. The schema forces the reading: {@code product_variants.variant_name} is
 * {@code NOT NULL} and nothing may invent one, so a product nobody declared a variant for is an incomplete
 * creation, not a catalog entry.</p>
 *
 * <p>It is a 400 that <b>names the field</b>, thrown before any row is written, so the caller receives
 * {@code variants is required} instead of a product that the next read has to report as broken. Thin
 * subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} already maps that class
 * to 400 and needs no method of its own for this type.</p>
 */
public class VariantsRequiredException extends IllegalArgumentException {

    public VariantsRequiredException() {
        super("variants is required: a product is created with at least one variant — SIMPLE means exactly "
                + "one and MULTI declares its family in the same request (ADR-0006 decision 2)");
    }
}
