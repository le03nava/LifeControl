package com.lifecontrol.api.fiscal.cancellation;

import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.CANCELACION;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.SOAP_ENVELOPE;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.answerOf;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.elapsedMillis;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.parse;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.quoted;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.statusOf;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.text;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solución Factible's two <b>asynchronous</b> cancellation operations, behind
 * {@link FiscalCancellationReconciliationPort} ({@code T3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md};
 * operations {@code L3} of the same record, reading {@code L4}).
 *
 * <p><b>Why the asynchronous pair and not the synchronous one the tree already ships.</b> {@code enviarSolicitudCancelacion}
 * answers <b>now</b> and, for a document it already judged, cannot be asked again — a second call is a second request.
 * The vendor's asynchronous surface is the one that exists for this question, and it is two operations:
 * {@code enviarSolicitudCancelacionAsincrono}, which registers a signed request and answers
 * {@code CancelacionAsincronoResponse{status, mensaje}} where {@code 200} is «se registró» and carries the
 * transaction id <b>in the message</b> and no verdict at all; and {@code getStatusCancelacionAsincrona}, which answers
 * {@code StatusCancelacionResponse{status, mensaje, acuseSAT}} and whose {@code acuseSAT} is the vendor's only
 * statement that the document «fue cancelada correctamente». The vendor's third asynchronous operation,
 * {@code cancelarAsincrono}, is deliberately not used: it takes the issuer's private key per call, the custody posture
 * {@code L20} of {@code odd/tasks/cfdi-fiscal-document.md} refused.</p>
 *
 * <p><b>Why the reading is these codes and no others ({@code L4}).</b> An {@code acuseSAT} present is an
 * <b>acceptance</b> whatever code sits beside it, because the catalogue lists no {@code 202} on this surface and the
 * receipt is its own verdict. {@code 213} (the receiver rejected it) and {@code 204} (the document cannot be
 * cancelled) are <b>settled rejections</b> and their code is kept. {@code 211} and a bare {@code 200} are
 * <b>still pending</b>. {@code 702} says the authority holds <b>no transaction</b> for that UUID, which is what makes
 * the pass register the request. On the registration side {@code 200} and {@code 701} both mean the request is
 * registered — the second because an earlier pass already did it — and every other code ({@code 601}–{@code 605},
 * {@code 611}, {@code 620}, {@code 621}, {@code 633}, {@code 500}, {@code 501}, {@code 1801}–{@code 1803}) is a
 * refusal carrying the authority's own code.</p>
 *
 * <p><b>Why a code outside that list is a defect and not "still pending".</b> The four status answers are a closed
 * reading of the catalogue, and the one code the catalogue does name as <i>not</i> a verdict is the one the adapter
 * must not guess at: an account failure, a malformed answer or a code this tree has never seen says nothing about the
 * document, and reporting it as «still waiting» would leave an operator polling forever while the answer was in their
 * hands. It is raised as {@link FiscalStampProviderDefectException} — the tree's "the provider answered and the answer
 * cannot be read" — for the reason that class already states one capability over: nothing was decided, so neither a
 * cancellation nor a rejection may be invented. A SOAP {@code Fault} is the same fact and is refused the same way.</p>
 *
 * <p><b>Why the transport is shared and not copied.</b> This adapter sends two more operations to the same host, with
 * the same account, the same escaped credentials and the same local-name answer reading. {@link
 * SolucionFactibleSoapTransport} is one copy of those facts for both adapters, which is why
 * {@code SolucionFactibleFiscalCancellationProvider}'s behaviour did not change when its helpers moved there.</p>
 *
 * <p><b>What this adapter does not do.</b> It does not build or seal a request ({@link #register} sends one already
 * sealed, exactly as the cancelling port does), it does not write anything — it has no transaction — and it does not
 * decide whether a pending row moves: that is
 * {@code com.lifecontrol.api.fiscal.service.FiscalCancellationReconciliationService}. It also never sends
 * {@code emailNotifica} or {@code properties}: the first is the vendor's only notification channel and this tree does
 * not use it, and the second carries no value anyone configured.</p>
 */
public final class SolucionFactibleFiscalCancellationReconciler implements FiscalCancellationReconciliationPort {

    private static final Logger logger = LoggerFactory.getLogger(SolucionFactibleFiscalCancellationReconciler.class);

    /** SOAP 1.1 carries the operation in the {@code Action} header, quoted as the HTTP binding requires. */
    private static final String REGISTER_ACTION = "\"urn:enviarSolicitudCancelacionAsincrono\"";

    private static final String STATUS_ACTION = "\"urn:getStatusCancelacionAsincrona\"";

    private static final String REGISTRATION_RESPONSE = "CancelacionAsincronoResponse";

    private static final String STATUS_RESPONSE = "StatusCancelacionResponse";

    private static final String MENSAJE = "mensaje";

    private static final String ACUSE = "acuseSAT";

    /** «La solicitud de cancelación se registró exitosamente»: the authority holds the request. */
    private static final int REGISTERED = 200;

    /**
     * «Ya existe una transacción asíncrona para el UUID especificado»: an earlier pass registered it.
     *
     * <p>The same outcome as {@link #REGISTERED}, and the distinction is kept in the message rather than dropped,
     * because a second registration that silently looked like a first would hide a pass that already ran.</p>
     */
    private static final int ALREADY_REGISTERED = 701;

    /** The status code that says the transaction is registered and undecided, with no receipt beside it. */
    private static final int REGISTERED_UNDECIDED = 200;

    /** «La cancelación está en proceso». */
    private static final int IN_PROCESS = 211;

    /** The two codes that are the authority's verdict against the cancellation ({@code L4}). */
    private static final List<String> REJECTED_CODES = List.of("213", "204");

    /** «No existe una transacción con el UUID especificado»: the pass has to register the request again. */
    private static final int NO_TRANSACTION = 702;

    private final SolucionFactibleSoapTransport transport;

    /**
     * Binds the adapter to the vendor's endpoint and account, through the same transport the cancelling half uses.
     *
     * <p>No second properties record and no second secret: the vendor, the service URL and the account are the ones
     * {@code app.fiscal.provider.solucion-factible.*} already configures, and the transport validates them before the
     * first call so a mistake is a boot failure instead of a failed reconciliation.</p>
     *
     * @param properties the bound {@code app.fiscal.provider.solucion-factible} values
     * @throws IllegalStateException when the endpoint is absent or is not an absolute {@code http(s)} URL
     */
    public SolucionFactibleFiscalCancellationReconciler(SolucionFactibleProperties properties) {
        this.transport = new SolucionFactibleSoapTransport(properties);
    }

    @Override
    public Registration register(FiscalCancellationRequest request) {
        Objects.requireNonNull(request, "a registration carries the sealed request to send");
        var startedAt = System.nanoTime();
        var body = transport.send(REGISTER_ACTION, registrationEnvelope(request), startedAt);
        var answer = answerOf(parse(body, "the SOAP answer"), REGISTRATION_RESPONSE);
        var status = statusOf(answer, "the answer");
        var message = text(answer, MENSAJE);
        logger.info(
                "Solución Factible asynchronous registration: endpoint={}, elapsedMillis={}, status={}, registered={}",
                transport.endpoint(),
                elapsedMillis(startedAt),
                status,
                status == REGISTERED || status == ALREADY_REGISTERED);

        if (status == REGISTERED || status == ALREADY_REGISTERED) {
            if (message == null || message.isBlank()) {
                // The transaction id is the whole of what this answer says, and it travels in `mensaje`: an answer that
                // registered something without naming it is a defect, not a registration this tree can report.
                throw FiscalStampProviderDefectException.unreadable("the registration states status " + status
                        + " and no " + MENSAJE + ", and " + MENSAJE
                        + " is where the vendor delivers the transaction id: "
                        + "an answer that registered a transaction without naming it cannot be kept as a registration "
                        + "(L3)");
            }
            return new Registration.Registered(
                    message, status == ALREADY_REGISTERED ? alreadyRegistered(message) : message);
        }
        return new Registration.Refused(List.of(new FiscalCancellationResult.Issue(String.valueOf(status), message)));
    }

    @Override
    public Outcome status(UUID transactionId) {
        Objects.requireNonNull(transactionId, "a status call names the transaction it asks about");
        var startedAt = System.nanoTime();
        var body = transport.send(STATUS_ACTION, statusEnvelope(transactionId), startedAt);
        var answer = answerOf(parse(body, "the SOAP answer"), STATUS_RESPONSE);
        var status = statusOf(answer, "the answer");
        var message = text(answer, MENSAJE);
        var acuse = text(answer, ACUSE);
        var code = String.valueOf(status);
        var outcome = outcomeOf(code, status, acuse, message);
        logger.info(
                "Solución Factible asynchronous status: endpoint={}, elapsedMillis={}, status={}, outcome={}",
                transport.endpoint(),
                elapsedMillis(startedAt),
                status,
                outcome.getClass().getSimpleName());
        return outcome;
    }

    // ─── the calls ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The registration's envelope: SOAP 1.1, document style, three children in the WSDL's order.
     *
     * <p>The document travels as the base64 of the bytes the caller sealed — the vendor verifies the signature over
     * those bytes, so re-rendering it here would break the seal. {@code emailNotifica} and {@code properties} are
     * optional and not sent: the first is the vendor's only notification channel and this tree does not use it, and
     * the second carries nothing anyone configured.</p>
     */
    private String registrationEnvelope(FiscalCancellationRequest request) {
        var document = Base64.getEncoder().encodeToString(request.requestXml().getBytes(StandardCharsets.UTF_8));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENVELOPE + "\" xmlns:timb=\"" + CANCELACION + "\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<timb:enviarSolicitudCancelacionAsincrono>"
                + transport.credentialsAs("timb")
                + "<timb:solicitud>" + document + "</timb:solicitud>"
                + "</timb:enviarSolicitudCancelacionAsincrono>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    /**
     * The status envelope: the two credentials and the transaction the pass asks about.
     *
     * <p>The UUID is rendered in upper case because every published example of this vendor states a UUID that way
     * ({@code CfdiCancellationRequestBuilder} writes the document's {@code Folio/@UUID} in the same form), and the
     * vendor's own sentence is that this id is usually the UUID that was sent to be cancelled.</p>
     */
    private String statusEnvelope(UUID transactionId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENVELOPE + "\" xmlns:timb=\"" + CANCELACION + "\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<timb:getStatusCancelacionAsincrona>"
                + transport.credentialsAs("timb")
                + "<timb:transactionId>" + transactionId.toString().toUpperCase(Locale.ROOT) + "</timb:transactionId>"
                + "</timb:getStatusCancelacionAsincrona>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    // ─── the reading (L4) ────────────────────────────────────────────────────────────────────────────

    /**
     * The four answers {@code L4} settles, and a defect for anything else.
     *
     * <p>The receipt decides first: {@code acuseSAT} present is an acceptance, because the catalogue lists no
     * {@code 202} on this surface and the field is its only statement of «cancelada correctamente». Only then the code
     * is read, and only the codes the catalogue names as a verdict on the document are mapped — everything else is a
     * call that stated nothing about this document, which must not be reported as «still waiting» ({@code L4}).</p>
     */
    private static Outcome outcomeOf(String code, int status, String acuse, String message) {
        if (acuse != null && !acuse.isBlank()) {
            return new Outcome.Accepted(code, acuse, message);
        }
        if (REJECTED_CODES.contains(code)) {
            return new Outcome.Rejected(code, message);
        }
        if (status == NO_TRANSACTION) {
            return new Outcome.NoTransaction();
        }
        if (status == REGISTERED_UNDECIDED || status == IN_PROCESS) {
            return new Outcome.StillPending(code, message);
        }
        throw FiscalStampProviderDefectException.unreadable("the status answer states status " + status + " ("
                + quoted(message) + ") with no " + ACUSE + ", and that code is not one the vendor's catalogue settles "
                + "as a verdict on this document: 200 and 211 are pending, 213 and 204 are rejections and 702 means no "
                + "transaction, so reading anything else as «still waiting» would leave the operator polling for an "
                + "answer that is already in hand (L4)");
    }

    /** The `701` sentence, with the fact it carries kept: nothing was registered twice, and this pass is not the first. */
    private static String alreadyRegistered(String message) {
        return "701 — una transacción asíncrona ya existía para esta solicitud: " + message;
    }
}
