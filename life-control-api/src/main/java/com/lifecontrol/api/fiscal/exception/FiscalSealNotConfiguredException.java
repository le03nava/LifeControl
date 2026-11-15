package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * Nothing seals a document in this deployment, so this attempt cannot stamp
 * (decision {@code L18}(d) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why it is the seal's own refusal and not the provider's.</b> {@link FiscalStampProviderNotConfiguredException}
 * answers "there is no authority to call"; this one answers "there is nothing that can sign what the authority
 * would be sent". They are two halves of one capability and each names the property a deployment is missing, so an
 * operator reading a log line knows whether to configure the PAC or the CSD. Folding them into one message would
 * have said "no provider" for a deployment whose provider is configured and whose certificate is not.</p>
 *
 * <p><b>Why it is raised where the provider is, before the network call, and as an
 * {@code IllegalStateException}.</b> A document that cannot be sealed is never sent, so the refusal belongs before
 * the call and not inside a response handler; and nothing about the <b>request</b> is wrong, the <b>instance</b>
 * is not configured to stamp — the same family {@link FiscalStampProviderNotConfiguredException} uses for the same
 * reason.</p>
 */
public class FiscalSealNotConfiguredException extends IllegalStateException {

    public FiscalSealNotConfiguredException(UUID documentId) {
        super("Fiscal document " + documentId + " cannot be stamped: no CfdiSealer is registered, so nothing can "
                + "sign the document before it is sent. The sealing pipeline and its configuration are T7b's "
                + "(app.fiscal.csd.certificado, app.fiscal.csd.llave and app.fiscal.csd.password, registered only "
                + "while app.fiscal.provider.enabled is true); until a sealer is present every attempt is refused "
                + "here, after the draft exists and before anything is sent.");
    }
}
