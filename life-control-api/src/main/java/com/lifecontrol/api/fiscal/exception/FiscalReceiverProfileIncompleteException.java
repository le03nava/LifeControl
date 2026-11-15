package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.List;
import java.util.UUID;

/**
 * The receiver of a sale cannot be stated on a document: the sale names no customer, the customer's fiscal
 * profile is incomplete, or it is complete and has no RFC ({@code S2} and {@code F6} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L8}(e)–(f) of the same record).
 *
 * <p><b>Why the refusal names the missing facts and not only the customer.</b> The profile is all-or-nothing at
 * write time ({@code L8}(e)), so an incomplete one is the state only a row written before {@code V107} — or by a
 * writer that bypassed the API — can be in, and the operator's next step is editing exactly those facts. The
 * message lists them by their column names, the shape {@code CustomerRequest}'s validator already uses.</p>
 *
 * <p><b>Why the RFC is part of the list and not of {@code Customer#fiscalProfileComplete()}.</b> S2 keeps
 * {@code rfc} optional in the profile ("when present, is validated for shape") and {@code V109} makes
 * {@code receptor_rfc} <b>required</b> on the document, so a complete profile without an RFC is a customer a
 * sale cannot be invoiced for. Neither half alone is enough, which is why the writer asks both and this refusal
 * is the answer to either.</p>
 */
public class FiscalReceiverProfileIncompleteException extends ConflictException {

    public FiscalReceiverProfileIncompleteException(UUID salesOrderId, String reason) {
        super("Sales order " + salesOrderId + " cannot be invoiced: " + reason + ". A fiscal document states the "
                + "receiver's legal name, regime, fiscal postal code, use of the voucher and RFC as they stand, "
                + "and the receiver's profile is required at issue and never at the till (S2).");
    }

    /** The message for a customer whose profile is missing some of the five facts a document states. */
    public static FiscalReceiverProfileIncompleteException ofMissingFacts(UUID salesOrderId, List<String> missing) {
        return new FiscalReceiverProfileIncompleteException(
                salesOrderId, "customers." + String.join(", customers.", missing) + " is not declared");
    }
}
