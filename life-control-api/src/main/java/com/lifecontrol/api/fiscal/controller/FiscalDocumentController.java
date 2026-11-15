package com.lifecontrol.api.fiscal.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentCancellation;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentCancellationRequest;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationMotiveUnknownException;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotConfiguredException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentReprintService;
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
 * The fiscal document's two operations: the reprint of one stamped document ({@code S10}) and the cancellation that
 * ends it ({@code S7}), both of {@code odd/tasks/cfdi-fiscal-document.md} and its decisions {@code L15}(a)–(c) and
 * {@code L20}(a)–(g).
 *
 * <p><b>Why the reprint is a read and the cancellation a write, with nothing else beside them.</b> {@code S10} is
 * one rule — the artefact returns the bytes that were stamped — and {@code S7} is one act — a cancellation is a new
 * row naming the cancelled document and its motive. There is deliberately no list, no detail view and no re-issue
 * here: those are screens and slices of their own, and a read that built a document instead of returning it would be
 * the failure {@code L15}(a) names.</p>
 *
 * <p><b>Why the paths name no company.</b> The document's id is its identity, so the resource is addressed the way
 * {@code /api/customers} is: {@code TenantScopeFilter} states the caller's single {@code company_id} claim as the
 * operation's tenant before this method runs, and both services read that scope. A path carrying a company the row
 * does not belong to would be a second statement of a fact the policy already owns.</p>
 *
 * <p><b>Why the roles are {@code lc-admin} and {@code lc-sales} on both.</b> The same pair that reads and edits a
 * sale, because a reprint and a cancellation are what the sale produced and no fiscal role exists to grant — the gate
 * {@code SalesOrderController} already uses for the document a sale issues.</p>
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
        description = "The stamped fiscal documents of a sale: their XML, byte for byte, and " + "their cancellation")
public class FiscalDocumentController {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentController.class);

    private final FiscalDocumentReprintService reprintService;
    private final FiscalDocumentCancellationService cancellationService;

    public FiscalDocumentController(
            FiscalDocumentReprintService reprintService, FiscalDocumentCancellationService cancellationService) {
        this.reprintService = reprintService;
        this.cancellationService = cancellationService;
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
                    + "cancelled document.")
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
        @ApiResponse(responseCode = "503", description = "No cancellation provider or sealer is configured")
    })
    public ResponseEntity<FiscalDocumentCancellation> cancel(
            @PathVariable UUID id, @Valid @RequestBody FiscalDocumentCancellationRequest request) {
        var cancellation = cancellationService.cancel(id, motiveOf(request.motive()), request.substitutingFolio());

        return cancellation.wrote()
                ? ResponseEntity.status(HttpStatus.CREATED).body(cancellation)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(cancellation);
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
     * Answers a deployment that cannot cancel with <b>503</b> instead of the 500 the advice's fallback would give.
     *
     * <p><b>Why the mapping is here and not in {@code GlobalExceptionHandler}.</b> That advice is shared by every
     * controller and belongs to the slice that owns it, and it ends with a handler for {@link Exception} — which
     * resolves before {@code ResponseStatusExceptionResolver}, so annotating the refusal with {@code @ResponseStatus}
     * would map to nothing and answer 500. A handler on this controller is the smallest surface that states this
     * endpoint's one configuration answer, and it serves the tree's own envelope rather than a second shape.</p>
     */
    @ExceptionHandler(FiscalCancellationNotConfiguredException.class)
    public ResponseEntity<GlobalExceptionHandler.ErrorResponse> handleNotConfigured(
            FiscalCancellationNotConfiguredException refusal) {
        logger.error("Fiscal cancellation refused: this deployment is not configured", refusal);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new GlobalExceptionHandler.ErrorResponse(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        refusal.getMessage(),
                        currentPath(),
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
