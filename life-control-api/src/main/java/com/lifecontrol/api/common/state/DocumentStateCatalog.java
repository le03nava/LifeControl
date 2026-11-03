package com.lifecontrol.api.common.state;

import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place a request's {@code status_id} becomes a state, for every document family (ADR-0004
 * decision 7). The catalog stopped being the authority and became the label of a code, so the only thing a
 * request can name is the code's row, and the only thing a service may decide a move with is the constant
 * that code spells.
 *
 * <p><b>No name is compared, anywhere.</b> Renaming {@code Draft} in the catalog changes what the screen
 * says and nothing else (ADR-0004, {@code ## Consequences}); a comparison against {@code "Draft"} would
 * quietly turn that rename into a 409, which is the coupling decisions 1 and 7 exist to remove. The label is
 * read here for one purpose only: naming the row in a refusal, because the caller sent an id and has no
 * other way to know which row it was.</p>
 *
 * <p><b>The read is the model's, not a raw query.</b> {@link StatusRepository#findWithStatusTypeById} brings
 * the row and its family in one fetch, so this class owns the mapping and the refusal and nothing about how a
 * catalog row is loaded. That is also why it shares the caller's transaction: the row is read on the same
 * connection and inside the same snapshot as the write it guards.</p>
 *
 * <p><b>Four refusals, and each one names what it saw.</b> A row of another family is the client's mistake and
 * answers {@code 400}, the same way {@code StatusValidator} refused a status of the wrong type before this
 * slice; a row with no code is an out-of-scope family or a catalog row created at run time, and there is no
 * constant to move to; a code the family's enum does not declare is a state the compiler has never heard of;
 * and an id that names no row at all is the {@code 404} the catalog never had a row for. The middle two are
 * refused instead of guessed: moving a document to "whatever the label looked like" would be the string
 * comparison ADR-0004 removes. Every one of those refusals used to live in the sales module's own catalog,
 * and the purchase module would have copied them; one class and one message contract means the second family
 * cannot answer them differently.</p>
 *
 * <p><b>Why the family arrives as a {@code Class<S>} and not as a second argument to the constant.</b> A
 * single {@code stateOf(subject, statusId)} would have to answer one of five unrelated enum types, and Java
 * gives it one return type plus a cast at every call site — a cast that fails at run time in the one place
 * nobody wrote a test. The class token is both the return type and the check: {@code Enum.valueOf(family,
 * code)} is what makes "a code the enum does not declare" an answerable question instead of a string the
 * caller compares, and its {@code IllegalArgumentException} is what the refusal below reports.</p>
 */
@Component
public class DocumentStateCatalog {

    private final StatusRepository statusRepository;

    public DocumentStateCatalog(StatusRepository statusRepository) {
        this.statusRepository = statusRepository;
    }

    /**
     * The state a catalog row names, in the family's own enum.
     *
     * @param subject the family the caller is asking about, which is what {@code status_transition} stores and
     *     what {@code status_types.status_type_name} spells
     * @param statusId the catalog row the request named
     * @param family the family's enum, which is at once the return type and the value set the code is checked
     *     against
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when no id was given, the row belongs to another family, it carries no
     *     code, or it carries a code {@code family} does not declare
     */
    public <S extends Enum<S> & DocumentState<S>> S requireState(
            StatusSubject subject, UUID statusId, Class<S> family) {
        if (statusId == null) {
            throw new IllegalArgumentException("A status id is required to name a state of " + subject);
        }

        var status = statusRepository
                .findWithStatusTypeById(statusId)
                .orElseThrow(() -> new StatusNotFoundException(statusId));
        var catalogName = status.getStatusName();
        var typeName = status.getStatusType().getStatusTypeName();

        // The family first: a row of another family answers with its label in a message about the type,
        // which is the one thing the caller can fix ("the id I sent is a line's status").
        if (!subject.name().equalsIgnoreCase(typeName)) {
            throw new IllegalArgumentException("The provided status does not belong to type " + subject.name()
                    + ": catalog status '" + catalogName + "' (" + statusId + ") belongs to " + typeName);
        }

        var code = status.getCode();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Catalog status '" + catalogName + "' (" + statusId
                    + ") carries no code, and " + subject + " has no state a label can name. ADR-0004 decision 7"
                    + " makes the code the only thing the machine may read");
        }

        try {
            return Enum.valueOf(family, code);
        } catch (IllegalArgumentException notDeclared) {
            throw new IllegalArgumentException(
                    "Catalog status '" + catalogName + "' (" + statusId + ") carries code '" + code + "', which "
                            + family.getSimpleName() + " does not declare",
                    notDeclared);
        }
    }
}
