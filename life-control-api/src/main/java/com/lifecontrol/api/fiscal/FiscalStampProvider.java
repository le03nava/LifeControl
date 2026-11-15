package com.lifecontrol.api.fiscal;

/**
 * The outbound boundary of stamping: a built CFDI goes out, and the authority's answer comes back
 * ({@code S10}, {@code S11} and decisions {@code L12}(a) and {@code L12}(g) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why it is named {@code *Provider} and not {@code *Port} or {@code *Adapter}.</b> The tree has exactly
 * one outbound boundary and it is {@code IdentityProvider}, with {@code KeycloakIdentityProvider} behind it;
 * {@code Port} and {@code Adapter} appear nowhere in {@code src/main} ({@code L12}(a)). ADR-0007's word stays a
 * concept and the code follows the tree.</p>
 *
 * <p><b>Why the interface says nothing about any vendor.</b> It takes this tree's own request record
 * ({@link FiscalStampRequest}) and answers with this tree's own sealed result ({@link FiscalStampResult}): no
 * HTTP client, no DTO of a PAC, no credentials and no certificate in the signature. That is what lets the
 * builder and the attempt ship before a vendor is chosen ({@code T7} registers
 * {@code <Vendor>FiscalStampProvider} behind the conditional the worker's scheduler already uses), and what
 * keeps a vendor's vocabulary out of the domain: a code the provider answers is a {@code String} the operator
 * reads, never a constant this tree has to keep up to date.</p>
 *
 * <p><b>What an implementation may not do.</b> It may not stamp twice: idempotency belongs to the schema and
 * the attempt ({@code L12}(h)), so the caller is what guarantees one call per attempt. And it may not drop the
 * provider's own XML from a stamped answer: {@code Stamped#xml()} is the artefact ({@code S10}), and a
 * document re-rendered by this tree after stamping is not the one the authority witnessed.</p>
 */
public interface FiscalStampProvider {

    /**
     * Sends one built document to the authority and returns what it answered.
     *
     * @param request the document to stamp and the issuer's RFC
     * @return a stamped document with the authority's fields and XML, or a rejection with the provider's codes
     */
    FiscalStampResult stamp(FiscalStampRequest request);
}
