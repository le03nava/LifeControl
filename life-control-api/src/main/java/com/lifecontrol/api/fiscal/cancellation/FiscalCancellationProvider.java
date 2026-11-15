package com.lifecontrol.api.fiscal.cancellation;

/**
 * The outbound boundary of cancelling: a sealed Anexo 20 request goes out, and the authority's answer comes back
 * ({@code S7} and decisions {@code L20}(a), {@code L20}(c) and {@code L20}(d) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why it mirrors {@link com.lifecontrol.api.fiscal.FiscalStampProvider} and not an adapter of its own.</b>
 * The tree has one outbound boundary per capability and it is named {@code Provider} with a
 * {@code <Vendor>…Provider} behind it ({@code L12}(a)); "port" and "adapter" stay concepts. Cancellation is a second
 * capability, so it is a second interface with the same shape — the vendor is behind it, and nothing about a vendor
 * is in the signature: no HTTP client, no SOAP, no credentials, no vendor DTO.</p>
 *
 * <p><b>Why the method takes one already-sealed document and answers with a sealed three.</b> The request is
 * {@code T8b}'s product ({@code L20}(g)) and this boundary only sends it. The answer is
 * {@link FiscalCancellationResult}, whose three branches are the decision: a cancelled document is a row the caller
 * writes, a submission in process is a row it must <b>not</b> write ({@code L20}(d)), and a rejection is a value the
 * caller inspects one code at a time. A fourth outcome — the provider failed to answer — is not a value: it is
 * {@code FiscalStampProviderException}, because a socket that closed says nothing about the document.</p>
 *
 * <p><b>What an implementation may not do.</b> It may not build or seal the request (that is {@code T8b}, and a
 * re-signing adapter would send a document nobody kept), it may not write anything anywhere — this boundary has no
 * transaction — and it may not turn a submission in process or an unreadable answer into a cancellation, because a
 * cancellation row is a witness about a document and the authority is the only thing allowed to state it.</p>
 */
public interface FiscalCancellationProvider {

    /**
     * Asks the authority to cancel one document and returns what it answered.
     *
     * @param request the sealed cancellation request and the UUID of the document it is about
     * @return cancelled, in process, or refused with the authority's codes
     */
    FiscalCancellationResult cancel(FiscalCancellationRequest request);
}
