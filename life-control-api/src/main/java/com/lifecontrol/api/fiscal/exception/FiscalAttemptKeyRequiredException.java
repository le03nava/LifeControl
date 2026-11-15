package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * A snapshot attempt was asked for with no attempt key. A caller error, refused as a 400.
 *
 * <p><b>Why the key cannot be blank.</b> The key is what makes an attempt idempotent ({@code S11},
 * {@code L12}(h)): two calls of one attempt collide on it and the second writes nothing. An empty key would
 * make <b>every</b> attempt of a sale collide with every other one, which is the exact opposite of the rule —
 * and {@code fiscal_document.attempt_key} admits {@code NULL} for the rows that are not attempts at all, so a
 * blank string would be a value with no meaning wearing the shape of none.</p>
 */
public class FiscalAttemptKeyRequiredException extends IllegalArgumentException {

    public FiscalAttemptKeyRequiredException(UUID salesOrderId) {
        super("A fiscal snapshot attempt of sale " + salesOrderId + " needs an attempt key: it is what makes a "
                + "replayed attempt a no-op (S11), and a blank one would make every attempt of the sale collide "
                + "with every other.");
    }
}
