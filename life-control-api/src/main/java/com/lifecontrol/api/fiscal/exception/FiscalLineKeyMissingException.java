package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * A concept of the document cannot state its authority key, because the product it sells or the unit it is
 * measured in has none ({@code S4} and {@code S9} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this is a refusal and not a fallback.</b> «The concept is the one element a stamp cannot omit, and
 * it is the weakest fiscal column in the tree» (S4). A product with no {@code sat_code} is a product nobody
 * classified — {@code V104} deliberately leaves the column nullable for exactly that state — and inventing a
 * key would put a made-up code on a document the authority witnesses. The refusal names the column and the row,
 * which is the difference between "fix these five articles" and "the stamp failed somewhere".</p>
 *
 * <p><b>Why a blank key is treated as a missing one.</b> {@code measure_units.sat_code} is {@code NOT NULL} and
 * {@code VARCHAR(5)}, so an empty string is the only way a unit reaches this refusal; "blank is not a key" is
 * the same rule the profile predicate states for its four facts.</p>
 */
public class FiscalLineKeyMissingException extends ConflictException {

    public FiscalLineKeyMissingException(UUID salesOrderItemId, String column, UUID holderId) {
        super("The fiscal document line of sale line " + salesOrderItemId + " cannot state "
                + column + ", because the row it comes from (" + holderId + ") has none. The concept's product key "
                + "and its unit key are required on every line (S4, S9), and neither is guessed.");
    }
}
