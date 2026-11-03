package com.lifecontrol.api.common.state;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of {@code status_transition}: every state change of a document appends one row here, and
 * nothing else in the application inserts into that table (ADR-0004 decisions 4–6).
 *
 * <p><b>Why plain SQL and no JPA entity.</b> The table is append-only — {@code V67} refuses {@code UPDATE}
 * and {@code DELETE} with a trigger <em>and</em> revokes both privileges from {@code lifecontrol_app} — and
 * nothing ever loads a row back by id, changes one, or navigates from a document to its history in the
 * domain model. An entity would exist only to be mapped, would need a synthetic id generation strategy for
 * a {@code UUID} the database already defaults, and would invite exactly the {@code merge} the table
 * refuses. One {@code INSERT} is the whole contract.</p>
 *
 * <p><b>{@code seq} and {@code occurred_at} are the database's.</b> Neither is a parameter: {@code seq} is
 * a {@code GENERATED ALWAYS AS IDENTITY} column whose only job is to give "the latest transition" one
 * answer even inside a single transaction where every row shares the same {@code now()}, and
 * {@code occurred_at} defaults to {@code now()} so a writer cannot claim an instant it did not observe.</p>
 *
 * <p><b>Why {@link Propagation#MANDATORY}.</b> ADR-0004 decision 5 ties the two writes together at
 * <b>commit</b>: a document whose state moved without its row, and a row whose state the document does not
 * hold, are both refused by deferred constraint triggers. That only means something if the document write
 * and this append are in <b>one</b> transaction — the order inside it is deliberately free, the boundary is
 * not. A caller with no transaction gets an {@code IllegalTransactionStateException} at the call instead of
 * a committed row that the database will judge on its own. The same shape
 * {@code DocumentSequenceAllocator} states for the same reason.</p>
 *
 * <p><b>The actor is the caller, and a caller with no identity is refused.</b> The row's whole purpose is
 * the {@code actor} column, so it comes from the request's {@code sub} claim and falls back to
 * {@code preferred_username} when a token carries no subject. When neither exists this throws instead of
 * writing a placeholder: {@code 'unknown:migration'} is the migration's literal for history it did not
 * observe (ADR-0004 migration step 6), and a live write may not claim it.</p>
 *
 * @see DocumentStates#requireTransition the guard that decides whether a move is legal, called before the
 *     write so a forbidden pair never reaches the table
 */
@Component
public class StatusTransitionRecorder {

    /**
     * One row, and no column the database owns. {@code from_status} is bound as {@code null} for a creation
     * row — that {@code NULL} is what {@code uq_status_transition_creation} makes unique per document.
     */
    private static final String INSERT_SQL = """
            INSERT INTO status_transition
                (company_id, document_type, document_id, from_status, to_status, actor, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    private final CurrentUserContext currentUserContext;

    public StatusTransitionRecorder(JdbcTemplate jdbcTemplate, CurrentUserContext currentUserContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Appends the row that creates a document: {@code from_status} is {@code null} and {@code to_status} is
     * the state the document is born in.
     *
     * @param subject the family, which is {@code status_transition.document_type}
     * @param companyId the tenant that owns the document
     * @param documentId the document the row is about
     * @param initialState the state the document is created in; its constant name is stored
     * @throws IllegalArgumentException when the state belongs to another family, or a parameter is missing
     * @throws IllegalStateException when the request carries no actor at all
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <S extends Enum<S> & DocumentState<S>> void recordCreation(
            StatusSubject subject, UUID companyId, UUID documentId, S initialState) {
        requireSubject(subject);
        requireCompanyAndDocument(companyId, documentId, subject);
        if (initialState == null) {
            throw new IllegalArgumentException("A creation row for " + subject + " " + documentId
                    + " needs the state the document is created in, and none was given: to_status is NOT NULL");
        }
        requireSameFamily(subject, initialState);
        append(companyId, subject, documentId, null, initialState.name(), null);
    }

    /**
     * Appends the row of one move, after refusing a move the family does not declare.
     *
     * <p><b>The guard runs before the write, not after.</b> ADR-0004 invariant T2 wants a forbidden
     * transition answered as a {@code 409} that <b>leaves the row untouched</b>; validating here is what
     * makes "writes nothing" true rather than a claim about a transaction the caller may still roll back.
     * The database cannot reach the Java enum, so this call is the only place the pair is judged.</p>
     *
     * @param subject the family, which is {@code status_transition.document_type}
     * @param companyId the tenant that owns the document
     * @param documentId the document the row is about
     * @param from the state the document is leaving
     * @param to the state it is entering; must be one of {@code from}'s declared successors
     * @param reason free text, or {@code null} when the caller has none
     * @throws InvalidStatusTransitionException when the family does not declare the pair
     * @throws IllegalArgumentException when a state belongs to another family, or a parameter is missing
     * @throws IllegalStateException when the request carries no actor at all
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <S extends Enum<S> & DocumentState<S>> void recordMove(
            StatusSubject subject, UUID companyId, UUID documentId, S from, S to, String reason) {
        requireSubject(subject);
        requireCompanyAndDocument(companyId, documentId, subject);
        // Null-tolerant on purpose: `requireTransition` answers a missing end with the 409 the guard
        // documents, and a family check here would turn that into a 400 for the same request.
        requireSameFamily(subject, from);
        requireSameFamily(subject, to);

        DocumentStates.requireTransition(from, to);

        append(companyId, subject, documentId, from.name(), to.name(), reason);
    }

    /**
     * Appends the row of a move whose target the family reaches through <b>more than one</b> declared move.
     *
     * <p><b>When this is the right method, and when it is not.</b> A requested move names one target and
     * {@link #recordMove} is the only entry point for it: a pair outside the current state's successor set is a
     * {@code 409} and nothing is written. This method exists for the one shape the domain really has and the
     * closed successor set cannot express: a receipt <em>derives</em> a line's state from what physically
     * arrived, and a line that was never marked {@code IN_TRANSIT} still becomes {@code PARTIAL_RECEIVED} — one
     * fact, one row, recorded with the ends the document now holds. That is why the reachability question
     * ({@link DocumentStates#isReachable}) is asked here instead of the one-hop question, and why the caller must
     * have asked it <b>before</b> touching the document: this method still refuses a missing end and a pair of
     * states from another family, but it cannot refuse a pair the family admits through the graph.</p>
     *
     * <p>Everything else is {@link #recordMove}'s contract: the family is {@code document_type}, the actor comes
     * from the request, the caller's transaction is required, and a pair with the same state at both ends is not
     * a move at all — a document that did not change state has nothing to record.</p>
     *
     * @param subject the family, which is {@code status_transition.document_type}
     * @param companyId the tenant that owns the document
     * @param documentId the document the row is about
     * @param from the state the document held
     * @param to the state it now holds; must be reachable from {@code from} in its family
     * @param reason free text saying what derived the state, or {@code null} when the caller has none
     * @throws InvalidStatusTransitionException when the document did not move, or the target is not reachable
     * @throws IllegalArgumentException when a state belongs to another family, or a parameter is missing
     * @throws IllegalStateException when the request carries no actor at all
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <S extends Enum<S> & DocumentState<S>> void recordReachableMove(
            StatusSubject subject, UUID companyId, UUID documentId, S from, S to, String reason) {
        requireSubject(subject);
        requireCompanyAndDocument(companyId, documentId, subject);
        requireSameFamily(subject, from);
        requireSameFamily(subject, to);

        // The same 409 `recordMove` answers, asked over the graph: a target no path reaches is refused, and a
        // pair with equal ends is refused there too, because no state declares itself a successor.
        DocumentStates.requireReachable(from, to);

        // Reachable is not the same as moved: the graph is reflexive, so an unchanged state would otherwise be
        // written as a row whose two ends are equal — which `ck_transition_moves` refuses at the database.
        if (from == to) {
            return;
        }

        append(companyId, subject, documentId, from.name(), to.name(), reason);
    }

    /**
     * The single statement, with the actor resolved first so a request with no identity writes nothing.
     *
     * @param fromStatus the constant being left, or {@code null} for the row that creates the document
     */
    private void append(
            UUID companyId, StatusSubject subject, UUID documentId, String fromStatus, String toStatus, String reason) {
        var actor = requireActor(subject, documentId);
        jdbcTemplate.update(INSERT_SQL, companyId, subject.name(), documentId, fromStatus, toStatus, actor, reason);
    }

    /**
     * The request's {@code sub}, else its {@code preferred_username}, else a refusal that names what it
     * would have written.
     */
    private String requireActor(StatusSubject subject, UUID documentId) {
        var subjectClaim = currentUserContext.getUserId();
        if (subjectClaim != null && !subjectClaim.isBlank()) {
            return subjectClaim;
        }
        var username = currentUserContext.getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }
        throw new IllegalStateException("A status_transition row for " + subject + " " + documentId
                + " would have no actor: the request carries neither the `sub` nor the `preferred_username` "
                + "claim. 'unknown' is the migration's literal for history it did not observe, so a live write "
                + "cannot claim it (ADR-0004 migration step 6)");
    }

    private static void requireSubject(StatusSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("A status_transition row names a document family, and none was given");
        }
    }

    private static void requireCompanyAndDocument(UUID companyId, UUID documentId, StatusSubject subject) {
        if (companyId == null) {
            throw new IllegalArgumentException(
                    "A status_transition row belongs to a tenant, and none was given for " + subject);
        }
        if (documentId == null) {
            throw new IllegalArgumentException(
                    "A status_transition row belongs to a document, and none was given for " + subject);
        }
    }

    /**
     * Refuses a state of another family, which would otherwise store a {@code document_type} the row's own
     * enum disagrees with. The database catches the same mistake one layer down — decision 6's trigger finds
     * no document of that type under that id — but a caller that mixed two families would get a message
     * about a missing document instead of about its own mistake.
     */
    private static void requireSameFamily(StatusSubject subject, DocumentState<?> state) {
        if (state != null && state.subject() != subject) {
            throw new IllegalArgumentException(
                    "State " + ((Enum<?>) state).name() + " belongs to " + state.subject() + " and not to " + subject);
        }
    }
}
