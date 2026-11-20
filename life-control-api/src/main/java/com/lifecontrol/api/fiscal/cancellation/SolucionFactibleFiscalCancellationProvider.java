package com.lifecontrol.api.fiscal.cancellation;

import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.CANCELACION;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.SOAP_ENVELOPE;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.STATUS;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.answerOf;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.children;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.elapsedMillis;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.parse;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.quoted;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.statusOf;
import static com.lifecontrol.api.fiscal.cancellation.SolucionFactibleSoapTransport.text;

import com.lifecontrol.api.config.fiscal.SolucionFactibleProperties;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderDefectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * Solución Factible's {@code enviarSolicitudCancelacion} operation, behind {@link FiscalCancellationProvider}
 * ({@code T8c}, decisions {@code L20}(a) to {@code L20}(h) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this operation and not the four siblings that also cancel.</b> The vendor's service carries four, and
 * they split on who signs: {@code cancelar}, {@code cancelarBase64} and {@code cancelarPorNotaCredito} take
 * {@code derCertCSD}, {@code derKeyCSD} and {@code contrasenaCSD}, so the private key travels to the PAC on every
 * call; {@code cancelarViaCSDAlmacenado} takes none because the PAC holds the key, which is the custody posture
 * {@code L18}(a) refused. {@code enviarSolicitudCancelacion} takes {@code usuario}, {@code password} and one
 * base64 document, and the vendor's page says of it that the document <i>«ya vendra firmado»</i>
 * ({@code L20}(a)). So the seal is made here with the CSD this tree already loads for {@code T7b}, and the key
 * never leaves the process — the cancellation half reuses the sealing capability instead of inverting the posture
 * that chose the provider.</p>
 *
 * <p><b>Why one hand-written envelope and one hand-read answer, again.</b> The same trade {@code L18}(g) made for
 * {@code timbrarBase64}: one fixed envelope over {@link HttpClient}, parsed with a hardened
 * {@link DocumentBuilderFactory}, adds no dependency and keeps the suite a test of the vendor's contract rather
 * than of a marshaller. The limit is stated there and it holds here: this class understands <b>one</b> operation
 * and <b>one</b> answer.</p>
 *
 * <p><b>Why the answer is read by local name, and why its wrapper is not what the WSDL sketches.</b> The vendor's
 * two published answers are the authority for the wire form ({@code L20}(h)): there is <b>no</b> {@code return}
 * wrapper, the per-document results are serialized as {@code CFDIResultadoCancelacion} elements <b>directly</b>
 * under {@code EnviarSolicitudCancelacionResponse} rather than inside a {@code resultados} array, {@code UUID} is
 * spelled in capitals, and the published bodies carry <b>no SOAP envelope at all</b>. So the parser looks for the
 * body's answer element by local name, accepts it bare or inside a {@code soapenv:Body}, and reads its children by
 * local name too: the vendor decides which of its elements are qualified, and a parser that insisted on the WSDL's
 * namespace would refuse the vendor's own example.</p>
 *
 * <p><b>Why the third level decides and the first two do not.</b> The vendor numbers three levels and only the last
 * one is a verdict on the document ({@code L20}(c)): process {@code status} says whether the <b>call</b> worked,
 * the result's {@code status} says how the vendor processed the request, and {@code statusUUID} is the SAT's word
 * about the document. The vendor's own <i>correct</i> answer is process {@code 200}, result {@code 200} and
 * {@code statusUUID} {@code 205} — «el SAT no ha procesado la recepción de este comprobante» — so a mapper that
 * stopped at the result's {@code 200} would record a cancellation the authority explicitly did not make. Only
 * {@code 201} and {@code 202} are cancelled; {@code 211} is {@link FiscalCancellationResult.InProcess}, which writes
 * nothing ({@code L20}(d)); every other code is a {@link FiscalCancellationResult.Rejected} carrying it
 * untranslated.</p>
 *
 * <p><b>What is refused instead of mapped.</b> A process {@code 200} whose result states <b>no</b>
 * {@code statusUUID} is a provider defect — the authority stated no verdict on the document, so neither a
 * cancellation nor a rejection may be invented ({@code L20}(c)). An answer that names a <b>different</b> UUID than
 * the document this call is about is a defect too: it is an answer about somebody else's document, and keeping it
 * as this one's cancellation is the substitution {@code requireSameUuid} refuses on the stamping side. A SOAP
 * {@code Fault} is a defect and never a rejection: it says the call broke, not that the document was judged. And a
 * process status other than {@code 200} carries both codes when a result states a {@code statusUUID} — the request
 * level catalogue ({@code 1801}–{@code 1804}) arrives at the process level, and the operator fixes the account and
 * the request from the two sentences together.</p>
 *
 * <p><b>Why a defect is raised as {@link FiscalStampProviderDefectException} and a broken call as
 * {@link FiscalStampProviderTransportException}.</b> Both are the stamping half's types because both are the same
 * two facts one capability over: nothing was decided and the provider failed (a defect), or nothing was decided and
 * the call never got a reading (transport). The family's <b>names</b> say "stamp", which reads one letter wrong
 * here; a cancellation-specific copy would be a second hierarchy for the same two facts, and renaming the family is
 * a change in {@code fiscal/exception/} — outside this unit. The types are reused deliberately and the naming debt
 * is reported rather than paid twice.</p>
 *
 * <p><b>What this adapter does not do.</b> It does not build or seal the request ({@code T8b}), it does not write
 * the cancelled row or decide the HTTP answer ({@code T8d}), it does not retry, and it does not poll: {@code 211}
 * is answered as what it is, and the acceptance poll that will turn it into a verdict is {@code L20}(i)'s own
 * unit.</p>
 */
public final class SolucionFactibleFiscalCancellationProvider implements FiscalCancellationProvider {

    private static final Logger logger = LoggerFactory.getLogger(SolucionFactibleFiscalCancellationProvider.class);

    /** SOAP 1.1 carries the operation in the {@code Action} header, quoted as the HTTP binding requires. */
    private static final String SOAP_ACTION = "\"urn:enviarSolicitudCancelacion\"";

    /** The process level's "the call worked"; it is not a verdict on the document ({@code L20}(c)). */
    private static final int STATUS_OK = 200;

    /**
     * The only two {@code statusUUID} codes that mean <b>cancelled</b>: {@code 201} (<i>se ha cancelado con
     * éxito</i>) and {@code 202} (<i>el CFDI ya se había cancelado previamente</i>). A repeated cancellation is a
     * cancelled document — the second request changed nothing, and the state is the one the row records.
     */
    private static final Set<String> CANCELLED_CODES = Set.of("201", "202");

    /** <i>La cancelación está en proceso</i>: received and undecided, so nothing is written ({@code L20}(d)). */
    private static final String IN_PROCESS_CODE = "211";

    private static final String RESPONSE = "EnviarSolicitudCancelacionResponse";
    private static final String RESULT = "CFDIResultadoCancelacion";
    private static final String MENSAJE = "mensaje";
    private static final String STATUS_UUID = "statusUUID";
    private static final String UUID_ELEMENT = "UUID";

    /** The one copy of the vendor's endpoint, credentials and answer reading, shared with the reconciling adapter. */
    private final SolucionFactibleSoapTransport transport;

    /**
     * Binds the adapter to one endpoint and one account through the shared transport, which validates both before the
     * first call.
     *
     * <p>This adapter deliberately reuses {@code app.fiscal.provider.solucion-factible.*} whole — the same vendor, the
     * same service URL and the same account cancel what they stamp — so no second properties record and no second
     * secret exist to drift from this one. The validation, and the reason it happens at the boot, moved to
     * {@link SolucionFactibleSoapTransport} when the reconciling adapter started sending to the same host: one copy of
     * the rule instead of two.</p>
     *
     * @param properties the bound {@code app.fiscal.provider.solucion-factible} values
     * @throws IllegalStateException when the endpoint is absent or is not an absolute {@code http(s)} URL
     */
    public SolucionFactibleFiscalCancellationProvider(SolucionFactibleProperties properties) {
        this.transport = new SolucionFactibleSoapTransport(properties);
    }

    @Override
    public FiscalCancellationResult cancel(FiscalCancellationRequest request) {
        Objects.requireNonNull(request, "a cancellation carries the sealed request to send");
        var startedAt = System.nanoTime();
        var body = transport.send(SOAP_ACTION, envelopeFor(request), startedAt);
        var answer = map(body, request.uuid());
        logger.info(
                "Solución Factible cancellation call: endpoint={}, elapsedMillis={}, answer={}",
                transport.endpoint(),
                elapsedMillis(startedAt),
                answer.getClass().getSimpleName());
        return answer;
    }

    // ─── the call ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The one fixed envelope: SOAP 1.1, document style, three children in the WSDL's order.
     *
     * <p>The document travels as the base64 of the bytes the sealer produced — the vendor verifies the signature over
     * those bytes, so this string is the only copy of what was sent and re-rendering it here would break the seal.
     * The credentials are escaped because a password is data and not markup. There is no fourth child: unlike
     * {@code timbrarBase64} this operation has no {@code ZIP} flag, and it carries <b>one</b> document, because a
     * cancelled document is decided one at a time.</p>
     */
    private String envelopeFor(FiscalCancellationRequest request) {
        var document = Base64.getEncoder().encodeToString(request.requestXml().getBytes(StandardCharsets.UTF_8));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENVELOPE + "\" xmlns:timb=\"" + CANCELACION + "\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<timb:enviarSolicitudCancelacion>"
                + transport.credentialsAs("timb")
                + "<timb:solicitudCancelacion>" + document + "</timb:solicitudCancelacion>"
                + "</timb:enviarSolicitudCancelacion>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    // ─── the answer ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Maps the answer by the two levels that are a call and the one that is a verdict.
     *
     * <p>The order is the rule. A process status other than {@code 200} means the <b>call</b> failed, and the codes
     * travel as a rejection — with the result's {@code statusUUID} beside the process code when the answer states
     * one, because then the answer also judged the document and the operator needs both sentences. A process
     * {@code 200} hands the document to {@link #verdictOf}, which reads only the third level: the result's own
     * {@code status} is deliberately <b>not</b> the mapping key, since the vendor's correct answer states {@code 200}
     * there and {@code 205} below it ({@code L20}(c)).</p>
     */
    private FiscalCancellationResult map(String body, UUID requested) {
        var answer = answerOf(parse(body, "the SOAP answer"), RESPONSE);
        var processStatus = statusOf(answer, "the answer");
        var processMessage = text(answer, MENSAJE);
        var results = children(answer, RESULT);

        if (processStatus != STATUS_OK) {
            return new FiscalCancellationResult.Rejected(issuesOf(processStatus, processMessage, results));
        }
        if (results.isEmpty()) {
            throw FiscalStampProviderDefectException.unreadable("the answer states process status " + processStatus
                    + " (" + quoted(processMessage) + ") and carries no " + RESULT + " element, so there is no "
                    + "per-document verdict to report: a call that decided nothing is neither a cancellation nor a "
                    + "rejection (L20(c))");
        }
        return verdictOf(results.get(0), requested);
    }

    private static List<FiscalCancellationResult.Issue> issuesOf(
            int processStatus, String processMessage, List<Element> results) {
        List<FiscalCancellationResult.Issue> issues = new ArrayList<>();
        issues.add(new FiscalCancellationResult.Issue(String.valueOf(processStatus), processMessage));
        if (!results.isEmpty()) {
            var statusUuid = text(results.get(0), STATUS_UUID);
            if (statusUuid != null && !statusUuid.isBlank()) {
                issues.add(new FiscalCancellationResult.Issue(statusUuid, text(results.get(0), MENSAJE)));
            }
        }
        return issues;
    }

    /**
     * The third level, which is the only thing the authority says about the document.
     *
     * <p>A result with no {@code statusUUID} is a defect and not a {@code Rejected}: the answer stated no verdict, so
     * a rejection would put a code in the operator's list that the authority never wrote, and a cancellation would
     * record one it never made ({@code L20}(c)). The result's own {@code status} and {@code mensaje} are quoted in
     * the refusal, because they are the only facts the answer did carry.</p>
     */
    private static FiscalCancellationResult verdictOf(Element result, UUID requested) {
        var statusUuid = text(result, STATUS_UUID);
        if (statusUuid == null || statusUuid.isBlank()) {
            throw FiscalStampProviderDefectException.unreadable("the first " + RESULT + " states status "
                    + quoted(text(result, STATUS)) + " (" + quoted(text(result, MENSAJE)) + ") and no " + STATUS_UUID
                    + ", and " + STATUS_UUID + " is the SAT's verdict on the document: with the call at process "
                    + "status " + STATUS_OK + " the answer states no outcome at all, so reporting one would invent a "
                    + "cancellation or a rejection the authority never made (L20(c))");
        }
        requireSameUuid(text(result, UUID_ELEMENT), requested);
        var message = text(result, MENSAJE);
        if (CANCELLED_CODES.contains(statusUuid)) {
            return new FiscalCancellationResult.Cancelled(requested, statusUuid, message);
        }
        if (IN_PROCESS_CODE.equals(statusUuid)) {
            return new FiscalCancellationResult.InProcess(requested, statusUuid, message);
        }
        return new FiscalCancellationResult.Rejected(List.of(new FiscalCancellationResult.Issue(statusUuid, message)));
    }

    /**
     * An answer that names another UUID than the document asked about is a defect.
     *
     * <p>The same rule {@code requireSameUuid} applies on the stamping side, one level out: there the document and
     * the answer had to agree, here the answer and the <b>request</b> do. A provider — or a proxy in front of one —
     * that answered about a different folio would otherwise be kept as this document's cancellation, and a
     * cancellation row is a witness no later query can correct.</p>
     *
     * <p><b>What it deliberately tolerates.</b> An answer that states <b>no</b> {@code UUID} at all is left alone:
     * the vendor's published error answer carries neither a {@code statusUUID} nor a {@code UUID}, and the one-way
     * rule the sources state is about a <i>disagreement</i>, not about the field's presence ({@code L20}(f) reads
     * the same asymmetry for {@code FolioSustitucion}). The case this rule exists for states a different value and
     * is refused.</p>
     */
    private static void requireSameUuid(String stated, UUID requested) {
        if (stated == null || stated.isBlank()) {
            return;
        }
        UUID statedUuid;
        try {
            statedUuid = UUID.fromString(stated.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw FiscalStampProviderDefectException.unreadable(
                    "the answer states UUID '" + stated + "', which is not a UUID", notAUuid);
        }
        if (!statedUuid.equals(requested)) {
            throw FiscalStampProviderDefectException.unreadable("the answer states UUID " + stated
                    + " while this call is about " + requested + ", so it answers about another document: keeping it "
                    + "would record a cancellation of a folio the request never named");
        }
    }

    // ─── reading the answer ──────────────────────────────────────────────────────────────────────────
}
