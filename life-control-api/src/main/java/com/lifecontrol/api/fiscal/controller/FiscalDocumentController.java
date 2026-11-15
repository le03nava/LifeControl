package com.lifecontrol.api.fiscal.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.fiscal.service.FiscalDocumentReprintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fiscal document's read path: the reprint of one stamped document ({@code S10} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L15}(a)–(c) of the same record).
 *
 * <p><b>Why this is the whole endpoint.</b> {@code S10} is one rule — the artefact returns the bytes that were
 * stamped — and one rule needs one path. There is deliberately no list, no detail view and no re-issue here:
 * those are screens and slices of their own, and a read that built a document instead of returning it would be
 * the failure {@code L15}(a) names.</p>
 *
 * <p><b>Why the path names no company.</b> The document's id is its identity and the artefact's primary key, so
 * the resource is addressed the way {@code /api/customers} is: {@code TenantScopeFilter} states the caller's
 * single {@code company_id} claim as the operation's tenant before this method runs, and
 * {@link FiscalDocumentReprintService} reads that scope. A path carrying a company the row does not belong to
 * would be a second statement of a fact the policy already owns.</p>
 *
 * <p><b>Why the roles are {@code lc-admin} and {@code lc-sales}.</b> The same pair that reads a sale, because a
 * reprint is a read of what the sale produced, and no fiscal role exists to grant — the gate
 * {@code SalesOrderController} already uses for the document a sale issues.</p>
 *
 * <p><b>Why the bytes leave as an attachment.</b> {@code L15}(b): {@code application/xml} with an attachment
 * disposition, asserted byte-identical against what the provider returned. Rendering a third-party fiscal
 * document inline in a browser is an injection surface this tree does not need to open, and {@code text/plain}
 * would invite someone to "fix" the document by hand — which for a stamped document is the one edit that cannot
 * be undone.</p>
 */
@RestController
@RequestMapping("/api/fiscal-documents")
@Tag(name = "Fiscal Documents", description = "The stamped fiscal documents of a sale: their XML, byte for byte")
public class FiscalDocumentController {

    private final FiscalDocumentReprintService reprintService;

    public FiscalDocumentController(FiscalDocumentReprintService reprintService) {
        this.reprintService = reprintService;
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
}
