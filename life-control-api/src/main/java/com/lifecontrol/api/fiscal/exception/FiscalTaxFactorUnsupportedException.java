package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * The line's tax code is mapped to a factor this slice cannot state: {@code Cuota}.
 *
 * <p><b>Why {@code Cuota} is refused and not written as a rate.</b> A quota is an amount per unit of measure and
 * the tree holds no column for one — {@code rate} is what {@code tax_rate} states and what
 * {@code sales_order_items.tax_rate} snapshots. Writing the rate under a {@code Cuota} factor would state a
 * different fact to the authority, and a stamped document is the one thing this unit can never recompute.
 * Nothing maps to {@code Cuota} today ({@code V103} maps {@code IVA}/{@code TASA_0} to {@code Tasa} and
 * {@code EXENTO} to {@code Exento}), so this branch is the refusal that keeps a future mapping honest instead
 * of silently wrong.</p>
 */
public class FiscalTaxFactorUnsupportedException extends ConflictException {

    public FiscalTaxFactorUnsupportedException(UUID salesOrderItemId, String taxCode, String factor) {
        super("Sale line " + salesOrderItemId + " carries tax code '" + taxCode + "', which is mapped to the "
                + "factor '" + factor + "', and this tree states a rate and no quota. A quota stated as a rate is "
                + "a different fiscal fact, so the document refuses instead of guessing (S9, L12(f)).");
    }
}
