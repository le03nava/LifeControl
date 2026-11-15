package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * The provider answered, and what it answered cannot be read as either a stamp or a rejection — so nothing is
 * reported ({@code T7a}, decision {@code L18}(c) and {@code L18}(e) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why a defect is refused instead of being mapped to the nearest value.</b> Every case here is a
 * contradiction between two halves of the same answer, and each one has a plausible-looking wrong reading:
 * a {@code 200} with no result would become "a stamp with nothing in it"; a document whose timbre states another
 * UUID than the result would become "the result's UUID with the document's seals"; a {@code 200} whose
 * {@code cfdiTimbrado} carries no timbre would become "a stamped document with two nulls". A stamp cannot be
 * recomputed — the UUID and the seals are the authority's — so a guessed one is a wrong fiscal document forever.
 * Refusing names what the provider did and costs one retry; guessing costs a revocation.</p>
 *
 * <p><b>Why it is not a rejection, though the authority did answer.</b> The process status may be {@code 200}
 * in every case here, and even when it is not, the answer carries no result to fix: the request is not what has
 * to change, the provider's answer is. That is the opposite of a rejection, whose whole purpose is to give the
 * operator a code to act on ({@link FiscalStampRejectedException}).</p>
 */
public class FiscalStampProviderDefectException extends FiscalStampProviderException {

    private FiscalStampProviderDefectException(String message) {
        super(message);
    }

    private FiscalStampProviderDefectException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A call the provider called successful carrying no {@code resultados} element.
     *
     * @param processStatus the call-level status the provider answered ({@code 200} for this case)
     * @param processMessage the call-level sentence, quoted so the provider's own words travel
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException successfulCallWithoutResult(
            int processStatus, String processMessage) {
        return new FiscalStampProviderDefectException("Solución Factible answered the stamp with process status "
                + processStatus + " (" + quoted(processMessage) + ") and no result, and a call that stamped nothing "
                + "is not a stamp: resultados is empty, so there is neither a document to keep nor a rejection code "
                + "to fix, and reporting either would invent what the authority never said (L18).");
    }

    /**
     * A call-level failure carrying no {@code resultados} element to explain it.
     *
     * @param processStatus the call-level status the provider answered ({@code 601}–{@code 605} are credentials)
     * @param processMessage the call-level sentence, which is the only explanation the answer carries
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException failedCallWithoutResult(int processStatus, String processMessage) {
        return new FiscalStampProviderDefectException("Solución Factible refused the call with process status "
                + processStatus + " (" + quoted(processMessage) + ") and no result, so the answer names a call-level "
                + "failure and no per-document reason: there is nothing to report as a stamp and no result code to "
                + "report as a rejection (L18).");
    }

    /**
     * The provider returned a document whose {@code cfdiTimbrado} states no {@code TimbreFiscalDigital}.
     *
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException noTimbre() {
        return new FiscalStampProviderDefectException("Solución Factible returned a cfdiTimbrado that states no "
                + "TimbreFiscalDigital in namespace http://www.sat.gob.mx/TimbreFiscalDigital, so the two facts the "
                + "port carries from the timbre — RfcProvCertif and SelloCFD — cannot be read from the document it "
                + "returned, and an unstamped document is not the one the answer describes (L18(c)).");
    }

    /**
     * The provider returned a document whose timbre UUID disagrees with the UUID of its own result.
     *
     * @param timbreUuid the UUID the returned document's timbre states
     * @param resultUuid the UUID the answer's first result states
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException timbreDisagrees(String timbreUuid, UUID resultUuid) {
        return new FiscalStampProviderDefectException("Solución Factible returned a document whose timbre states "
                + "UUID " + timbreUuid + " while its result names " + resultUuid + ", so the document and the answer "
                + "describe two different stamps and neither can be kept as the artefact (S10, L18(c)).");
    }

    /**
     * The answer could not be read at all: not well-formed XML, no SOAP body, no {@code return} element, no
     * status, a UUID that is not one, or an instant with no offset.
     *
     * @param detail what could not be read, in the terms of the document that was being read
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException unreadable(String detail) {
        return unreadable(detail, null);
    }

    /**
     * The same, with the parser's own exception kept as the cause.
     *
     * @param detail what could not be read
     * @param cause the parser's failure, so the log keeps the line and the column
     * @return the refusal to raise
     */
    public static FiscalStampProviderDefectException unreadable(String detail, Throwable cause) {
        return new FiscalStampProviderDefectException(
                "Solución Factible's answer cannot be read as the contract the WSDL states: " + detail
                        + ". Nothing was stamped and nothing was refused, because an answer this tree cannot parse "
                        + "is a provider defect and never the authority's verdict on a document (L18).",
                cause);
    }

    private static String quoted(String message) {
        return message == null || message.isBlank() ? "no message" : "\"" + message + "\"";
    }
}
