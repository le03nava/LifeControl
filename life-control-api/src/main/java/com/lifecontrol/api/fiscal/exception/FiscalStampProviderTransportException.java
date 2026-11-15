package com.lifecontrol.api.fiscal.exception;

import java.time.Duration;

/**
 * The stamping call never got an answer: the endpoint refused the connection, the socket timed out, or the HTTP
 * answer was not a {@code 2xx} ({@code T7a}, decision {@code L18}(e) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this is not a rejection, and the distinction is the whole reason the type exists.</b> A rejection is
 * the authority's statement that the document is wrong, and an operator acts on it by editing the request. A
 * closed socket says nothing about the document — the same document sent five minutes later is stamped — so
 * reporting it as {@code Rejected} would put a vendor's outage into the operator's catalogue of things to fix and
 * would make a retry look like a correction. The attempt therefore leaves the draft exactly as it found it and
 * this exception travels; whether the retry is automatic is not this unit's decision.</p>
 *
 * <p><b>Why the message states the endpoint and the time.</b> Those are the two facts of an outage an operator
 * cannot get anywhere else: which host was called (a staging properties file pointed at production is a real
 * mistake) and how long it took or was allowed to take (the timeout is a property, so it can be wrong).
 * </p>
 */
public class FiscalStampProviderTransportException extends FiscalStampProviderException {

    private FiscalStampProviderTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The endpoint answered, and the answer was not a success status.
     *
     * @param endpoint the URL the call was sent to
     * @param timeout the configured timeout of the call
     * @param elapsedMillis how long the call took before that answer arrived
     * @param status the HTTP status the endpoint answered with
     * @return the refusal to raise
     */
    public static FiscalStampProviderTransportException answeredWithStatus(
            String endpoint, Duration timeout, long elapsedMillis, int status) {
        return new FiscalStampProviderTransportException(
                "Solución Factible's stamping endpoint " + endpoint + " answered HTTP " + status + " after "
                        + elapsedMillis + " ms, so nothing was stamped and the document was not refused: an HTTP "
                        + "status is a transport answer, never the authority's verdict on a document (L18). "
                        + boundedBy(timeout),
                null);
    }

    /**
     * The endpoint could not be reached at all, or the call was cut short.
     *
     * @param endpoint the URL the call was sent to
     * @param timeout the configured timeout of the call
     * @param elapsedMillis how long the call was attempted before it failed
     * @param failure what the transport reported, kept as the cause so the stack keeps the vendor's detail
     * @return the refusal to raise
     */
    public static FiscalStampProviderTransportException unreachable(
            String endpoint, Duration timeout, long elapsedMillis, Exception failure) {
        return new FiscalStampProviderTransportException(
                "Solución Factible's stamping endpoint " + endpoint + " could not be reached after " + elapsedMillis
                        + " ms (" + failure + "), so nothing was stamped and the document was not refused: a "
                        + "transport that failed is not the authority's verdict on a document (L18). "
                        + boundedBy(timeout),
                failure);
    }

    private static String boundedBy(Duration timeout) {
        return "The call was bounded by app.fiscal.provider.solucion-factible.timeout-seconds = " + timeout.toSeconds()
                + " s.";
    }
}
