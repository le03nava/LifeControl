package com.lifecontrol.api.fiscal.exception;

/**
 * The stamping provider itself failed: nothing was stamped, and nothing was refused either
 * ({@code T7a}, {@code L18} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this family exists at all, when the port already has a rejection.</b> A rejection is the authority's
 * <b>verdict on the document</b> — a value the caller inspects, one code at a time. Everything under this class
 * is the opposite: the call did not produce a verdict. A socket that closed, a gateway that answered
 * {@code 500}, a body that cannot be read, a {@code 200} that carries no result — none of them says the document
 * is wrong, so none of them may be reported as one ({@link com.lifecontrol.api.fiscal.FiscalStampResult.Rejected}
 * and {@link FiscalStampRejectedException} are that other thing). Two shapes, because the operator's next move
 * differs: a transport failure is retried, a defect is called in to the provider.</p>
 *
 * <p><b>Why it is unchecked.</b> The port's signature is
 * {@link com.lifecontrol.api.fiscal.FiscalStampProvider#stamp} and it declares no checked exception, so the
 * attempt's transaction is not asked to handle a vendor's failure at every call site; the branch the caller
 * <em>must</em> handle is the rejection, and that one is a value. This is the same split
 * {@code IdentityProviderException} gives the tree's only other outbound boundary.</p>
 */
public abstract class FiscalStampProviderException extends RuntimeException {

    protected FiscalStampProviderException(String message) {
        super(message);
    }

    protected FiscalStampProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
