package com.lifecontrol.api.product.exception;

/**
 * Thrown when the request's variant list contradicts the mode it declares: {@code SIMPLE} with more than
 * one variant.
 *
 * <p>ADR-0006 decision 2 says {@code SIMPLE} <b>means exactly one</b>, and invariant C2 is the same rule
 * stated by the schema — {@code trg_variant_matches_mode} (V56) refuses the second variant of a
 * {@code SIMPLE} product with {@code 'product % is SIMPLE and already has a variant'}. That message is the
 * half no writer can skip; this exception is the half a client can render, because it names the field
 * ({@code variantMode}) and the count the request declared instead of a trigger's SQLSTATE.</p>
 *
 * <p>It is thrown before the product or any variant is written, so a request refused this way leaves no row
 * to clean up. Thin subclass of {@link IllegalArgumentException}: the {@code GlobalExceptionHandler} already
 * maps that class to 400. A count of zero is a different refusal — {@link VariantsRequiredException} — because
 * what it violates is C1, which holds for both modes.</p>
 */
public class VariantModeMismatchException extends IllegalArgumentException {

    public VariantModeMismatchException(String variantMode, int declaredVariants) {
        super("variantMode " + variantMode + " requires exactly one variant, but the request declares "
                + declaredVariants + ": a product is created with at least one variant, and SIMPLE means exactly "
                + "one (ADR-0006 decision 2, invariant C2)");
    }
}
