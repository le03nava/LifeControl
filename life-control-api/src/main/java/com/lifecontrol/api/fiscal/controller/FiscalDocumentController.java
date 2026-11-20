package com.lifecontrol.api.fiscal.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.fiscal.dto.FiscalCancellationReconciliation;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentCancellation;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentCancellationRequest;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentIssueRequest;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentRead;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentStamp;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationMotiveUnknownException;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalSealNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderNotConfiguredException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.service.FiscalCancellationReconciliationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentReadService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentReprintService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The fiscal document's five operations: issuing one from a completed sale ({@code #927}), reading the one a sale
 * produced ({@code #947}), the reprint of one stamped document ({@code S10}) and the cancellation that ends it
 * ({@code S7}), the last two of {@code odd/tasks/cfdi-fiscal-document.md} and its decisions {@code L15}(a)–(c) and
 * {@code L20}(a)–(g), and the reconciliation that resolves a submitted cancellation
 * ({@code odd/tasks/fiscal-cancellation-reconciliation.md}, {@code T8}).
 *
 * <p><b>Why two reads and two writes, with nothing else beside them.</b> {@code S10} is one rule — the artefact
 * returns the bytes that were stamped — and {@code S7} is one act — a cancellation is a new row naming the cancelled
 * document and its motive. The reconciling pass is the second write and it writes the <b>same row</b> the submission
 * wrote, once the authority has decided ({@code odd/tasks/fiscal-cancellation-reconciliation.md}); it adds no surface
 * beside the document it resolves. The read by sale is what makes both reachable: a document's id was answered only to
 * the caller who issued it, so a caller who never issued it could name neither the reprint nor the cancellation
 * ({@code odd/tasks/fiscal-document-read.md}). What that adds is exactly one read by sale, and there is still no list
 * across sales, no substitution, no re-issue and no drafting surface here: those are screens and slices of their own,
 * and a read that built a document instead of returning it would be the failure {@code L15}(a) names.</p>
 *
 * <p><b>Why the paths name no company.</b> The document's id is its identity, so the resource is addressed the way
 * {@code /api/customers} is: {@code TenantScopeFilter} states the caller's single {@code company_id} claim as the
 * operation's tenant before this method runs, and both services read that scope. A path carrying a company the row
 * does not belong to would be a second statement of a fact the policy already owns.</p>
 *
 * <p><b>Why the roles are {@code lc-admin} and {@code lc-sales} on both.</b> The same pair that reads and edits a
 * sale, because a reprint and a cancellation are what the sale produced and no fiscal role exists to grant — the gate
 * {@code SalesOrderController} already uses for the document a sale issues. The reconciling operation is the one
 * exception, and it is narrower on purpose: it is an administrative act on a state the submission left, not something
 * the sale screen does, so it is {@code lc-admin} alone.</p>
 *
 * <p><b>Why the bytes leave as an attachment.</b> {@code L15}(b): {@code application/xml} with an attachment
 * disposition, asserted byte-identical against what the provider returned. Rendering a third-party fiscal document
 * inline in a browser is an injection surface this tree does not need to open, and {@code text/plain} would invite
 * someone to "fix" the document by hand — which for a stamped document is the one edit that cannot be undone.</p>
 */
@RestController
@RequestMapping("/api/fiscal-documents")
@Tag(
        name = "Fiscal Documents",
        description = "The stamped fiscal documents of a sale: the one a sale produced, their XML, byte for byte, "
                + "and their cancellation")
public class FiscalDocumentController {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentController.class);

    /**
     * The one sentence a caller receives when the authority failed to answer ({@code D2} of
     * {@code odd/tasks/fiscal-provider-failure-status.md}).
     *
     * <p><b>Why it is a constant and not the exception's message.</b> The family's own messages state the endpoint, the
     * timeout, the HTTP status the receiver answered and, on a defect, the fragment of the answer that could not be
     * read. Every one of those is an operator's fact and a stranger's reconnaissance: a caller needs to know that the
     * authority it depends on did not answer, and the detail travels through the correlation id the envelope already
     * returns, which the log pairs with it.</p>
     */
    private static final String FISCAL_PROVIDER_FAILURE_MESSAGE = "The fiscal authority failed to answer this request";

    private final FiscalDocumentReprintService reprintService;
    private final FiscalDocumentCancellationService cancellationService;
    private final FiscalCancellationReconciliationService reconciliationService;
    private final FiscalDocumentReadService readService;
    private final FiscalDocumentStamper stamper;

    public FiscalDocumentController(
            FiscalDocumentReprintService reprintService,
            FiscalDocumentCancellationService cancellationService,
            FiscalCancellationReconciliationService reconciliationService,
            FiscalDocumentReadService readService,
            FiscalDocumentStamper stamper) {
        this.reprintService = reprintService;
        this.cancellationService = cancellationService;
        this.reconciliationService = reconciliationService;
        this.readService = readService;
        this.stamper = stamper;
    }

    /**
     * Issues one completed sale's fiscal document: the draft, the seal and the authority's stamp, in one call.
     *
     * <p><b>Why this endpoint exists at all, stated as the defect it closes.</b> {@code FiscalDocumentStamper} has
     * been the only way a document becomes {@code STAMPED} since {@code T6}, and nothing in {@code src/main} called
     * it: the reprint and the cancellation could both only answer 404 in a deployed system, because no stamped row
     * could ever exist. A capability whose services exist and whose suites are green is still unreachable until
     * something calls it — the same lesson {@code L17} recorded for the gateway route and the audit process, one
     * layer up.</p>
     *
     * <p><b>Why the two success statuses differ.</b> A first stamp <b>created</b> a row and is {@code 201 Created}.
     * A replay under the same attempt key created nothing, called nobody and answered the document that already
     * existed, which is {@code 200 OK} and not a second creation. The body says which happened
     * ({@code stampedByThisAttempt}), so a caller never has to infer it from the status — the same pair
     * {@code FiscalDocumentDraft.written()} and {@code FiscalDocumentCancellation.wrote()} already state one state
     * earlier.</p>
     *
     * <p><b>What the answer carries and why the XML is in it.</b> {@link FiscalDocumentStamp} is the record
     * {@code T6} designed as "what a stamp attempt answers": the identity, the authority's UUID, when it was
     * stamped, and the bytes the provider returned. The bytes travel here for the reason that record states — the
     * attempt already holds the provider's answer at the moment it keeps it — and a caller that wants them
     * independently of this response still has {@code GET /{id}/xml}, which reads the stored artefact. Neither is a
     * second source of truth: the table is insert-only, so both return the same bytes.</p>
     *
     * @param request the sale to invoice and the attempt key that names the stamp
     * @return {@code 201} with the document this call stamped, or {@code 200} with the one its attempt already had
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Issue a completed sale's fiscal document",
            description = "Creates the document from the sale's frozen snapshot, seals it, asks the authority to "
                    + "stamp it and keeps the XML it returns. The call is idempotent by attempt key: replaying a key "
                    + "that already stamped answers the same document with 200 and calls nobody, while a first stamp "
                    + "answers 201. A sale that is not completed, a sale of another company and an absent sale are "
                    + "each refused by name; a sale that already has a live stamped document under another attempt is "
                    + "a 409, because the authority cannot hold two UUIDs for one sale and neither would be the "
                    + "original. An absent or blank attempt key is a 400 naming the field, and a deployment without a "
                    + "provider or without a sealer is a 503. An authority that failed to answer — unreachable, a non-2xx, "
                    + "or an answer that cannot be read — is a 502 carrying one constant sentence and the correlation id "
                    + "of the log line that holds the cause.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "This call stamped the document and kept the artefact"),
        @ApiResponse(
                responseCode = "200",
                description = "The attempt already had a stamped document; nobody was called"),
        @ApiResponse(responseCode = "400", description = "The attempt key is absent or blank"),
        @ApiResponse(responseCode = "403", description = "The caller holds neither lc-admin nor lc-sales"),
        @ApiResponse(responseCode = "404", description = "No such sale in this company"),
        @ApiResponse(
                responseCode = "409",
                description = "The sale is not completed, already has a live stamped document under another "
                        + "attempt, or a profile or tax mapping it needs is incomplete or missing"),
        @ApiResponse(responseCode = "503", description = "No stamping provider or no sealer is configured"),
        @ApiResponse(
                responseCode = "502",
                description = "The authority failed to answer: unreachable, a non-2xx, or an unreadable answer")
    })
    public ResponseEntity<FiscalDocumentStamp> issue(@Valid @RequestBody FiscalDocumentIssueRequest request) {
        var stamp = stamper.stamp(request.salesOrderId(), request.attemptKey());

        return stamp.stampedByThisAttempt()
                ? ResponseEntity.status(HttpStatus.CREATED).body(stamp)
                : ResponseEntity.ok(stamp);
    }

    /**
     * Reads the fiscal document a sale produced, by the sale alone.
     *
     * <p><b>Why this endpoint exists, stated as the defect it closes.</b> {@code POST /api/fiscal-documents} answers
     * the {@code documentId} to the caller who issued it, {@code SalesOrderResponse} carries no fiscal pointer, and
     * {@code FiscalDocumentDraft} is returned by no read — so a document could be issued and then lost, while the
     * reprint and the cancellation waited for an id nobody could produce. The sale is the one id every caller
     * already holds, which is why it is the address.</p>
     *
     * <p><b>Why {@code 200}, {@code 204} and {@code 404} are three different answers.</b> {@code 200} states the
     * document the sale produced. {@code 204} states that no document was <b>stamped</b> — a sale nothing was ever
     * attempted on, one with only drafts, or one whose only attempts were refused — because "not yet invoiced" is a
     * state a client renders and not a failure ({@code S2}): the app toasts every failed request, so a {@code 404}
     * here would raise a red banner over a normal sale. {@code 404} is the sale itself: it does not exist, or it
     * belongs to another company, and the two are the same answer so a foreign id cannot be told from an absent one
     * ({@code S5}).</p>
     *
     * <p><b>Why a cancelled document is a {@code 200}.</b> {@code V108}'s append-only trigger never updates the
     * stamped row — a cancellation <b>inserts</b> a copy naming it ({@code S3}) — so the sale still has its document
     * and the copy is reported in the cancellation block. {@code 204} here would contradict the authority.</p>
     *
     * <p><b>Why the XML is not in the answer.</b> {@code S4}: {@code GET /{id}/xml} remains the only carrier of the
     * stamped bytes, and a read that carried them would be a second carrier of the one artefact the tree promises is
     * byte-for-byte the authority's.</p>
     *
     * <p><b>Why the path names no company.</b> As the other two do: {@code TenantScopeFilter} states the caller's
     * single {@code company_id} claim as the operation's tenant before this method runs, and the service reads that
     * scope. A path carrying a company the sale does not belong to would be a second statement of a fact the policy
     * already owns.</p>
     *
     * @param salesOrderId the sale whose document is read, which every caller already holds
     * @return {@code 200} with the document the sale produced, or {@code 204} with no body when nothing was stamped
     */
    @GetMapping("/by-sale/{salesOrderId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Read the fiscal document a sale produced",
            description = "Returns the stamped document of this sale — its identity, the authority's UUID, when it "
                    + "was stamped and the folio it states — plus the cancellation block when one names it. A sale "
                    + "with no stamped document answers 204 with no body: never invoiced is a state, and a draft or a "
                    + "refused attempt is not a document. A cancelled document still answers 200 with its stamped "
                    + "row, because the cancellation is a row beside it and never an edit of it. An absent sale and "
                    + "another company's sale both answer 404, the same answer. The XML is not part of this response: "
                    + "the reprint is its only carrier.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The document the sale produced, and its cancellation when one exists"),
        @ApiResponse(
                responseCode = "204",
                description = "This sale has no stamped document: not yet invoiced is a state, not a failure"),
        @ApiResponse(responseCode = "400", description = "The sale id in the path is not a UUID"),
        @ApiResponse(responseCode = "403", description = "The caller holds neither lc-admin nor lc-sales"),
        @ApiResponse(
                responseCode = "404",
                description = "No such sale: an absent id and another company's sale are the same answer")
    })
    public ResponseEntity<FiscalDocumentRead> readBySale(@PathVariable UUID salesOrderId) {
        return readService
                .readBySale(salesOrderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.<FiscalDocumentRead>noContent().build());
    }

    /**
     * Serves the XML a stamp kept, exactly as the provider returned it.
     *
     * @param id the document to reprint, as the caller reads it from the stamp
     * @return the stored bytes as an XML attachment named after the document's folio, or its id when it states
     *     none
     */
    @GetMapping("/{id}/xml")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Reprint a stamped fiscal document's XML",
            description = "Returns the bytes the stamping provider returned for this document, unmodified, as an "
                    + "XML attachment named after its folio or its id. The document is never rebuilt: a "
                    + "regenerated document cannot be proven to be the original. A draft, a rejected attempt, a "
                    + "document of another company and a document that is not a sale's are all 404, because none "
                    + "of them has bytes that were stamped.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The stored XML, byte for byte"),
        @ApiResponse(responseCode = "403", description = "The caller holds neither lc-admin nor lc-sales"),
        @ApiResponse(
                responseCode = "404",
                description =
                        "No artefact in this company: a draft, a refused attempt or another company's " + "document")
    })
    public ResponseEntity<byte[]> reprintXml(@PathVariable UUID id) {
        var reprint = reprintService.reprintXml(id);

        // ContentDisposition and not a hand-built header: the folio is free text (V108 makes it an optional
        // local convention of up to forty characters), and a header is not a place to interpolate a value
        // nobody has validated — the builder escapes it and states the charset the name is encoded in.
        var disposition = ContentDisposition.attachment()
                .filename(reprint.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(reprint.xml().length)
                .body(reprint.xml());
    }

    /**
     * Cancels one stamped document at the authority and records the answer.
     *
     * <p>The two statuses are the authority's two decided outcomes: a granted cancellation wrote a row and is
     * {@code 201 Created}, and a request the receiver has not answered yet wrote <b>nothing</b> and is
     * {@code 202 Accepted} — HTTP's own word for "submitted, not decided" ({@code L20}(d)). The {@code statusUUID} in
     * the body is the authority's code either way, so an operator can quote it.</p>
     *
     * @param id the stamped document to cancel
     * @param request the authority's motive and, when the motive replaces the document, the substituting folio
     * @return {@code 201} with the row that was written, or {@code 202} with the authority's code
     */
    @PostMapping("/{id}/cancellation")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Cancel a stamped fiscal document",
            description = "Asks the authority to cancel this document and, when it grants the cancellation, writes "
                    + "the cancellation row that names it, with the motive and the substituting folio when the motive "
                    + "replaces it. The stamped document is never modified: a cancellation is a new row (S7), so its "
                    + "XML still reprints byte for byte. A document that is already cancelled is a 409 and the "
                    + "authority is not called twice; a draft, an absent document and another company's are all 404; "
                    + "a motive that is not one of the authority's four, a motive 01 without a substituting folio and "
                    + "a folio that names no stamped document of this company are all 400. A request the receiver has "
                    + "not answered yet is a 202 and writes nothing, because a submitted cancellation is not a "
                    + "cancelled document. An authority that failed to answer is the same 502 the stamp answers, with "
                    + "the same constant sentence.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "The authority cancelled it and the row was written"),
        @ApiResponse(
                responseCode = "202",
                description = "The authority received the request and has not decided: no row was written"),
        @ApiResponse(
                responseCode = "400",
                description = "An unknown motive, a motive 01 without a substituting folio, or a folio that names no "
                        + "stamped document of this company"),
        @ApiResponse(responseCode = "403", description = "The caller holds neither lc-admin nor lc-sales"),
        @ApiResponse(
                responseCode = "404",
                description = "No stamped document in this company: a draft, an absent one or another company's"),
        @ApiResponse(
                responseCode = "409",
                description = "The document is already cancelled, or the authority refused the cancellation"),
        @ApiResponse(responseCode = "503", description = "No cancellation provider or sealer is configured"),
        @ApiResponse(
                responseCode = "502",
                description = "The authority failed to answer: unreachable, a non-2xx, or an unreadable answer")
    })
    public ResponseEntity<FiscalDocumentCancellation> cancel(
            @PathVariable UUID id, @Valid @RequestBody FiscalDocumentCancellationRequest request) {
        var cancellation = cancellationService.cancel(id, motiveOf(request.motive()), request.substitutingFolio());

        return cancellation.wrote()
                ? ResponseEntity.status(HttpStatus.CREATED).body(cancellation)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(cancellation);
    }

    /**
     * Resolves a submitted cancellation into the answer the authority gave it.
     *
     * <p><b>Why this endpoint exists, stated as the defect it closes.</b> {@code T3} made the reconciling service and
     * its adapter, and nothing in {@code src/main} called it: a {@code CANCELLATION_PENDING} row — the one the
     * submission writes when the authority has not decided — could only be resolved by a human querying the PAC, which
     * is exactly what {@code S1} says must not be necessary. That is the same lesson {@code L17} recorded for the
     * stamping endpoint one capability earlier: a green suite and a registered adapter are not a reachable capability
     * until something calls them.</p>
     *
     * <p><b>Why it is an endpoint and not a scheduler.</b> {@code L5} decided it with the maintainer on 2026-11-19:
     * {@code fiscal_document} is armed and forced, so no platform thread can enumerate the companies that hold a
     * pending cancellation, and the tree's only precedent for a privileged cross-tenant read is a
     * {@code SECURITY DEFINER} function this unit does not open. The pass therefore runs inside a request's tenant
     * scope, and the service's logic is already scheduling-free, so a scheduler is a later, purely additive unit.</p>
     *
     * <p><b>Why every answered pass is a {@code 200}.</b> An acceptance, a settled rejection, a still-pending answer
     * and a refused registration are four <b>answers</b>, and the body says which one this pass read:
     * {@code documentStatus} is where the row stands after it and {@code settled} whether anything is left to ask. A
     * status that encoded the outcome would make a caller parse a nullable body to learn a fact the body already
     * states, and a rejection — finished by its stamp ({@code L2}(d)) — would have to borrow the status of a failure
     * it is not. {@code S1}'s idempotence falls out of the same shape: a second call over a settled row answers the
     * row and asks the authority nothing.</p>
     *
     * <p><b>What the two refusals are.</b> A document with no submitted cancellation at all is the service's own
     * {@code 404} — answering "nothing to do" would report a settled state nobody reached — and a deployment with no
     * reconciling adapter is the {@code 503} the handler below already gives the other two absences of this same
     * configuration. Neither writes a row.</p>
     *
     * @param id the stamped document whose submitted cancellation is reconciled
     * @return {@code 200} with what the row says after the pass, what the authority said and whether it is finished
     */
    @PostMapping("/{id}/cancellation/reconciliation")
    @PreAuthorize("hasRole('" + ADMIN + "')")
    @Operation(
            summary = "Reconcile a submitted cancellation with the authority's answer",
            description = "Asks the authority about the cancellation submitted for this document and settles the "
                    + "pending row with what it answered: an acceptance moves it to CANCELLED, a rejection settles it "
                    + "where it stands with the authority's code preserved, and a still-pending answer or a refused "
                    + "registration writes nothing and says so. Every answered pass is a 200 with the row's state and "
                    + "a settled flag, because all four are answers; running it again over a settled row answers the "
                    + "row and asks the authority nothing. A document whose cancellation was never submitted is a 404, "
                    + "and a deployment without the reconciling adapter is a 503. An authority that failed to answer is "
                    + "a 502 with the same constant sentence the other two endpoints answer. It takes no request body: "
                    + "what to ask is the row's, not the caller's.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "What the authority answered and where the cancellation row stands after the pass"),
        @ApiResponse(responseCode = "400", description = "The document id in the path is not a UUID"),
        @ApiResponse(responseCode = "403", description = "The caller does not hold lc-admin"),
        @ApiResponse(responseCode = "404", description = "This document has no submitted cancellation in this company"),
        @ApiResponse(responseCode = "503", description = "No reconciling provider is configured"),
        @ApiResponse(
                responseCode = "502",
                description = "The authority failed to answer: unreachable, a non-2xx, or an unreadable answer")
    })
    public ResponseEntity<FiscalCancellationReconciliation> reconcileCancellation(@PathVariable UUID id) {
        return ResponseEntity.ok(reconciliationService.reconcile(id));
    }

    /**
     * The authority's motive for the code the request stated, or the 400 that names the field.
     *
     * <p>The code is the wire's vocabulary and the enum is this tree's ({@code L20}(e)); the boundary is where the
     * two meet, so the parse is here and not inside the request record — a record that threw a domain refusal while
     * Jackson was still binding it would turn a client's typo into a deserialisation failure.</p>
     */
    private static FiscalCancellationMotive motiveOf(String code) {
        return FiscalCancellationMotive.fromCode(code)
                .orElseThrow(() -> new FiscalCancellationMotiveUnknownException(code));
    }

    /**
     * Answers a deployment that cannot stamp or cancel with <b>503</b> instead of the 500 the advice's fallback
     * would give.
     *
     * <p><b>Why the three refusals share one handler.</b> They are one fact at three capabilities — this deployment
     * has no provider, no sealer, or no cancellation provider — and all three extend {@link IllegalStateException},
     * which the shared advice answers with a 500 because "misconfigured" and "broken" are not the same answer to an
     * operator. Listing them together keeps the 503 in one place instead of three methods that would drift.</p>
     *
     * <p><b>Why the mapping is here and not in {@code GlobalExceptionHandler}.</b> That advice is shared by every
     * controller and belongs to the slice that owns it, and it ends with a handler for {@link Exception} — which
     * resolves before {@code ResponseStatusExceptionResolver}, so annotating the refusal with {@code @ResponseStatus}
     * would map to nothing and answer 500. A handler on this controller is the smallest surface that states this
     * endpoint's one configuration answer, and it serves the tree's own envelope rather than a second shape.</p>
     */
    @ExceptionHandler({
        FiscalStampProviderNotConfiguredException.class,
        FiscalSealNotConfiguredException.class,
        FiscalCancellationNotConfiguredException.class
    })
    public ResponseEntity<GlobalExceptionHandler.ErrorResponse> handleNotConfigured(RuntimeException refusal) {
        logger.error("Fiscal capability refused: this deployment is not configured", refusal);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new GlobalExceptionHandler.ErrorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        refusal.getMessage(),
                        currentPath(),
                        LocalDateTime.now(),
                        MDC.get("traceId")));
    }

    /**
     * Answers an authority that failed to answer with <b>502 Bad Gateway</b> instead of the 500 the advice's fallback
     * would give ({@code S1}, {@code S2} and {@code S3} of {@code odd/tasks/fiscal-provider-failure-status.md}).
     *
     * <p><b>Why 502, and why not the two codes that look closer.</b> {@code D1}: {@code 503} already answers two
     * different facts in this tree — the "not configured" refusal of the three handlers above, and
     * {@code IdentityProviderConnectionException} in the shared advice — and the whole point of this answer is to be
     * distinguishable from "this deployment cannot invoice", so it cannot reuse that code. {@code 504} is excluded for
     * a narrower reason: the tree does not separate a timeout from any other transport failure, since
     * {@code FiscalStampProviderTransportException.unreachable} and {@code …answeredWithStatus} are one family, so
     * {@code 504} would name a distinction no code makes. Both subclasses mean the same thing to a caller — an upstream
     * server did not answer us in a usable way — which is what {@code 502} means.</p>
     *
     * <p><b>Why the body is one constant sentence.</b> {@code D2}: the family's own messages carry the endpoint, the
     * configured timeout, the HTTP status the receiver answered and, on a defect, the fragment of the answer that could
     * not be read. None of that belongs in a response — a caller cannot act on it and it describes this tree's
     * deployment to whoever asked — so the cause goes to the log together with the path the response names, and the
     * operator reaches it through the correlation id the envelope already returns.</p>
     *
     * <p><b>Why the mapping is here and not in {@code GlobalExceptionHandler} ({@code L4}).</b> That advice is the class
     * every endpoint of the module goes through, and its {@link Exception} fallback is the behaviour {@code S7} protects:
     * a fiscal arm there would change where one family lands for surfaces no other controller asked for. A handler on
     * this controller resolves before the advice, so the three endpoints of this document get the {@code 502} while every
     * other exception keeps the answer it has today — the same reason the not-configured trio lives here. It also keeps
     * the blast radius to the three paths {@code D3} names: {@code FiscalCancellationPass} reaches the same ports from a
     * scheduled thread, where no exception handler is involved and nothing about this method changes.</p>
     *
     * @param failure the port's failure, logged whole and never quoted in the body
     * @return {@code 502} with the constant sentence, the request path and the correlation id
     */
    @ExceptionHandler(FiscalStampProviderException.class)
    public ResponseEntity<GlobalExceptionHandler.ErrorResponse> handleProviderFailure(
            FiscalStampProviderException failure) {
        var path = currentPath();
        // The path is in the log line because it is what the response carries: an operator holding a caller's body and
        // its correlation id can name the endpoint without asking for anything else (S1).
        logger.error("Fiscal provider failed to answer the request: path={}", path, failure);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new GlobalExceptionHandler.ErrorResponse(
                        HttpStatus.BAD_GATEWAY.value(),
                        FISCAL_PROVIDER_FAILURE_MESSAGE,
                        path,
                        LocalDateTime.now(),
                        MDC.get("traceId")));
    }

    /** The path the response names, or {@code null} outside a request — the advice's own rule for the envelope. */
    private static String currentPath() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRequestURI()
                : null;
    }
}
