package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * This deployment cannot cancel a document: no provider answers the cancelling port, or nothing seals the request
 * ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the absence is a named refusal and not a boot failure.</b> The provider is registered behind
 * {@code app.fiscal.provider.enabled} ({@code FiscalProviderConfig}), so an installation that does not invoice holds
 * no bean at all — and a required one would leave the whole API undeployable for a capability nobody configured. The
 * service therefore asks for the provider and the sealer when it cancels ({@code ObjectProvider}, exactly as
 * {@code FiscalDocumentStamper} does) and refuses <b>this request</b> by name, which is the answer an operator can
 * act on: configure a provider, or stop asking for cancellations.</p>
 *
 * <p><b>Why the two absences share one type.</b> They are one condition from the caller's side — "this instance
 * cannot cancel" — and they answer one status, so a second class would be a second {@code @ExceptionHandler} for the
 * same answer. The message is what says which half is missing, and it names the interface, because that is what the
 * configuration registers.</p>
 *
 * <p><b>Why it is an {@code IllegalStateException}.</b> Nothing about the request or the document conflicts with
 * anything: the server is not configured. That is the family {@code FiscalStampProviderNotConfiguredException} and
 * {@code FiscalSealNotConfiguredException} already use for the same condition one capability over, and it is what the
 * endpoint translates into a <b>503</b> — the status the tree answers a capability the deployment does not have.</p>
 */
public class FiscalCancellationNotConfiguredException extends IllegalStateException {

    private FiscalCancellationNotConfiguredException(String message) {
        super(message);
    }

    /**
     * Nothing implements the cancelling port in this context.
     *
     * @param fiscalDocumentId the document whose cancellation was refused, named so a batch caller knows which one
     * @return the refusal, naming the interface a deployment has to register
     */
    public static FiscalCancellationNotConfiguredException providerAbsent(UUID fiscalDocumentId) {
        return new FiscalCancellationNotConfiguredException("Fiscal document " + fiscalDocumentId
                + " cannot be cancelled: no FiscalCancellationProvider is registered, so there is no authority to ask."
                + " The vendor's adapter is registered behind app.fiscal.provider.enabled, and until one is present "
                + "every cancellation is refused here, before the request is built and before anything is written.");
    }

    /**
     * Something cancels but nothing can seal the request, which the authority would refuse unsigned.
     *
     * @param fiscalDocumentId the document whose cancellation was refused
     * @return the refusal, naming the sealing interface and the properties that configure it
     */
    public static FiscalCancellationNotConfiguredException sealerAbsent(UUID fiscalDocumentId) {
        return new FiscalCancellationNotConfiguredException("Fiscal document " + fiscalDocumentId
                + " cannot be cancelled: no CfdiCancellationSealer is registered, so the Anexo 20 request cannot be "
                + "signed and an unsigned request is one the authority refuses. The sealer is registered behind the "
                + "same switch as the provider (app.fiscal.csd.certificado, app.fiscal.csd.llave, "
                + "app.fiscal.csd.password), and neither the document nor the request is touched until it exists.");
    }
}
