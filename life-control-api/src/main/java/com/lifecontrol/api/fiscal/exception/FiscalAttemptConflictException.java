package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Two writers of one attempt key of one sale raced: the loser's insert was refused by
 * {@code uq_fiscal_document_attempt} ({@code V110}) before it could read the winner's row.
 *
 * <p><b>Why this exists at all, when a replay is supposed to return the existing document.</b> The writer reads
 * its key first and answers with the document it finds ({@code L12(h)}), so a sequential replay never reaches
 * here. Two concurrent attempts are the case the read cannot see: both find nothing, both insert, and the
 * schema refuses the second — which is the half of the idempotency a service guard cannot provide (L12(h): «a
 * guard is a statement a second writer can skip, and a replay is exactly the case where two writers race»).</p>
 *
 * <p><b>Why it is a named refusal and not the driver's exception.</b> A
 * {@code DataIntegrityViolationException} names a constraint and no fact, and a caller cannot tell "your attempt
 * lost a race to another attempt of the same sale" from "the schema is broken". This is that sentence, and the
 * caller's next move is the same one a replay makes: read the document the key now names.</p>
 */
public class FiscalAttemptConflictException extends ConflictException {

    public FiscalAttemptConflictException(UUID salesOrderId, String attemptKey) {
        super("Attempt '" + attemptKey + "' of sale " + salesOrderId + " is already recorded: another writer "
                + "committed the same attempt while this one was reading. Two attempts of one key are one "
                + "document (S11), so nothing was written twice — read the document the key now names.");
    }
}
