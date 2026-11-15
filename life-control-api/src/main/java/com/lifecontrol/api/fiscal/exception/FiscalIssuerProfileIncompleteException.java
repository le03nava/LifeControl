package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.List;
import java.util.UUID;

/**
 * The company has not declared its fiscal regime or its place of issue, so it cannot issue anything
 * ({@code S1} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L6}(a)–(b) and {@code L9}(f) of the
 * same record).
 *
 * <p><b>Why the issuer's two facts are required here and nowhere else.</b> S1 makes them "required to issue and
 * neither required to sell": the columns are nullable by design, a cash sale works without them, and this is the
 * call that asks. {@code V102} states the same pair and its {@code CHECK}s.</p>
 */
public class FiscalIssuerProfileIncompleteException extends ConflictException {

    public FiscalIssuerProfileIncompleteException(UUID companyId, List<String> missing) {
        super("Company " + companyId + " cannot issue a fiscal document: companies."
                + String.join(", companies.", missing)
                + " is not declared. The issuer's regime and its place of issue are required to issue and never "
                + "to sell (S1).");
    }
}
