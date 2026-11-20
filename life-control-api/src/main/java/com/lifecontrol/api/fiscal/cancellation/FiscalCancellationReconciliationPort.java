package com.lifecontrol.api.fiscal.cancellation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outbound boundary of <b>resolving a submitted cancellation</b>: one request is registered with the
 * authority's asynchronous operation, and the status of one registered transaction is read back
 * ({@code S4} and {@code S5} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, decisions {@code L3} and
 * {@code L4} of the same record).
 *
 * <p><b>Why a second port and not a fourth branch of {@link FiscalCancellationProvider}.</b> The synchronous
 * {@code enviarSolicitudCancelacion} that {@code #926} ships answers <b>now</b>, about <b>one</b> document, and its
 * reply is a verdict. These two operations answer a different question: the first registers a signed request and
 * says only «se registró» — its {@code 200} carries a transaction id and no verdict — and the second is asked later
 * about that transaction. A port whose {@code cancel} could sometimes return "registered" and sometimes a verdict
 * would force every caller to know which half it is talking to, which is exactly the conflation {@code L2}(d)
 * refuses one layer up. Two capabilities, two boundaries, and {@code SolucionFactibleFiscalCancellationReconciler}
 * behind this one.</p>
 *
 * <p><b>Why {@link Outcome} has four branches and not three.</b> The authority's catalogue distinguishes an answer
 * that <b>accepted</b> the cancellation ({@code acuseSAT} present — its only statement of «cancelada
 * correctamente»), two that <b>rejected</b> it ({@code 213}, the receiver; {@code 204}, the document cannot be
 * cancelled), one that is still <b>in process</b> ({@code 211}, and a bare {@code 200}), and one that says the
 * transaction is <b>not registered at all</b> ({@code 702}). The fourth is not a shade of pending: it is the fact
 * that makes the pass <i>register</i> the request, and a three-way answer would either lose it or turn it into
 * "still waiting" forever.</p>
 *
 * <p><b>What an implementation may not do.</b> It may not build or seal the request — {@link Registration} carries
 * one already sealed, exactly as {@link FiscalCancellationProvider#cancel(FiscalCancellationRequest)} does. It may
 * not write anything anywhere: it has no transaction, and the row that remembers the answer belongs to
 * {@code FiscalCancellationReconciliationService}. And it may not turn a code the catalogue does not settle into
 * "still pending", because a call that stated nothing must not read as "waiting": that case is a provider defect and
 * arrives as {@code FiscalStampProviderDefectException}, never as this interface's value.</p>
 */
public interface FiscalCancellationReconciliationPort {

    /**
     * Registers a signed cancellation request with the authority's asynchronous operation.
     *
     * <p>What this call answers is <b>not</b> a verdict on the document: the authority states that it holds the
     * request (or that an earlier pass already registered it), and the verdict is read later through
     * {@link #status(UUID)}.</p>
     *
     * @param request the sealed Anexo 20 request and the UUID of the document it is about, built and signed by the
     *     caller — the adapter sends it and never re-renders it
     * @return registered, with the transaction id the authority answered, or refused with the authority's codes
     */
    Registration register(FiscalCancellationRequest request);

    /**
     * Reads what the authority says about one registered transaction.
     *
     * @param transactionId the fiscal UUID the request was about — the vendor's own sentence is that the transaction
     *     id «generalmente será el mismo UUID que se envió a cancelar»
     * @return accepted, rejected, still pending, or not registered at all — never a verdict this tree invented
     */
    Outcome status(UUID transactionId);

    /**
     * What the registration call answered, in two shapes.
     *
     * <p>The codes are the vendor's: {@code 200} «la solicitud de cancelación se registró exitosamente» and
     * {@code 701} «ya existe una transacción asíncrona para el UUID especificado». Both mean the authority now holds
     * the request, which is why both are {@link Registered} — the second because an earlier pass already did the
     * work, and saying so in the message is what keeps that visible instead of silent.</p>
     */
    sealed interface Registration permits Registration.Registered, Registration.Refused {

        /**
         * The authority holds the request.
         *
         * @param transactionId the id it answered — its own sentence is that this is usually the UUID that was sent
         * @param message the authority's sentence, and on {@code 701} it also carries the fact that the transaction
         *     was already registered: the caller writes nothing either way, and the operator still has to be able to
         *     read which of the two happened
         */
        record Registered(String transactionId, String message) implements Registration {

            public Registered {
                Objects.requireNonNull(
                        transactionId, "a registered cancellation states the transaction the authority named");
                Objects.requireNonNull(message, "a registered cancellation keeps the authority's sentence");
            }
        }

        /**
         * The authority refused the registration, and nothing is registered.
         *
         * @param issues at least one provider code with its message — the operator's only way to fix the request or
         *     the account, in the authority's own vocabulary
         */
        record Refused(List<FiscalCancellationResult.Issue> issues) implements Registration {

            public Refused {
                issues = List.copyOf(issues);
                if (issues.isEmpty()) {
                    throw new IllegalArgumentException("A refused registration names at least one issue: the "
                            + "authority's codes are the only way the request is fixed, and a refusal that names none "
                            + "is a provider defect.");
                }
            }
        }
    }

    /**
     * What the status call answered, in the four shapes the vendor's catalogue settles.
     *
     * <p>{@code acuseSAT} is the discriminator of {@link Accepted}: the catalogue lists no {@code 202} on this
     * surface and the field is its only statement that the document was cancelled correctly ({@code L4}). So an
     * answer that carries one is an acceptance <b>whatever</b> code sits beside it, and an answer that carries none
     * can never be read as one.</p>
     */
    sealed interface Outcome permits Outcome.Accepted, Outcome.Rejected, Outcome.StillPending, Outcome.NoTransaction {

        /**
         * The authority cancelled the document and returned its receipt.
         *
         * @param statusUuid the answer's own code, kept as the authority stated it — {@code VARCHAR(4)} on the row
         * @param acuse the authority's receipt; it has no column in this slice, so it travels to the caller and is
         *     not stored
         * @param message the authority's sentence, untranslated
         */
        record Accepted(String statusUuid, String acuse, String message) implements Outcome {

            public Accepted {
                statusUuid = required(statusUuid, "an accepted cancellation states the authority's code");
                acuse = required(acuse, "an accepted cancellation carries the authority's acuse");
            }
        }

        /**
         * The authority refused the cancellation and the row is settled by that answer.
         *
         * @param statusUuid {@code 213} (the receiver rejected it) or {@code 204} (the document cannot be cancelled)
         * @param message the authority's sentence, untranslated
         */
        record Rejected(String statusUuid, String message) implements Outcome {

            public Rejected {
                statusUuid = required(statusUuid, "a rejected cancellation states the authority's code");
            }
        }

        /**
         * The authority has the request and has not answered yet: nothing moves, and nothing is written.
         *
         * @param statusUuid {@code 211} (in process) or {@code 200} with no {@code acuseSAT} beside it
         * @param message the authority's sentence, untranslated
         */
        record StillPending(String statusUuid, String message) implements Outcome {

            public StillPending {
                statusUuid = required(statusUuid, "a pending cancellation states the authority's code");
            }
        }

        /**
         * The authority holds no transaction for that UUID ({@code 702}), so the pass has to register it again.
         *
         * <p>No code travels: the answer's code is the fact, and a value that carried it would invite a caller to
         * store it on a row whose cancellation was never submitted.</p>
         */
        record NoTransaction() implements Outcome {}
    }

    private static String required(String value, String why) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(why + ", and it is absent: a verdict with no code is not one this "
                    + "tree can act on, and inventing a code would put it in the operator's catalogue.");
        }
        return value;
    }
}
