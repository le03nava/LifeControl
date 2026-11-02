package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.FieldViolationException;

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
 * <p>Subclass of {@link FieldViolationException}: the 400 carries
 * {@code errors: {variantMode: <message>}} next to the same sentence in {@code message}. The key is the
 * <b>mode</b> and not the list, because the contradiction is between the two statements and the mode is the
 * control the caller can change to make them agree.</p>
 *
 * <p>It is thrown before the product or any variant is written, so a request refused this way leaves no row
 * to clean up. A count of zero is a different refusal — {@link VariantsRequiredException} — because
 * what it violates is C1, which holds for both modes.</p>
 */
public class VariantModeMismatchException extends FieldViolationException {

    public VariantModeMismatchException(String variantMode, int declaredVariants) {
        super(
                "variantMode",
                "variantMode " + variantMode + " requires exactly one variant, but the request declares "
                        + declaredVariants
                        + ": a product is created with at least one variant, and SIMPLE means exactly "
                        + "one (ADR-0006 decision 2, invariant C2)");
    }
}
