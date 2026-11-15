package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentXmlReprint;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentArtifactNotFoundException;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.model.FiscalDocumentArtifact;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentArtifactRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reprint: the stored XML of one stamped document, returned byte for byte ({@code S10} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L15}(a)–(c) of the same record).
 *
 * <p><b>What this class deliberately does not do.</b> It does not call {@code CfdiXmlBuilder} — a rebuilt
 * document looks identical and is not the same bytes, and being the same bytes is the whole reason the artefact
 * exists ({@code L15}(a)) — and it reads no catalog: a reprint is one {@code SELECT} over two rows of the
 * document's own row set, which is what {@code S9} means by "nothing is read from the live catalogs when a
 * document is reprinted". The only transformation between the row and the response is the encoding of the
 * stored text into bytes.</p>
 *
 * <p><b>Why the tenant comes from the scope and not from the path.</b> The endpoint names no company —
 * {@code /api/fiscal-documents/{id}/xml} — so {@code TenantScopeFilter} states the caller's single
 * {@code company_id} claim as the operation's tenant before any controller runs, exactly as it does for
 * {@code /api/customers}. Both finders then name that company explicitly, which makes "not found" a fact of the
 * query and not a property of whichever pooled connection served it (the reason the repositories state the
 * company at all).</p>
 *
 * <p><b>Why a document of another company is a 404 and not a 403.</b> The policy makes the row invisible, so
 * "not found" is what the query answers; a 403 would need a second read outside the policy to learn that the
 * document exists, which is the leak the policy exists to close ({@code L15}(c)). The same answer covers a
 * {@code DRAFT} and a rejected attempt: neither has an artefact, because no bytes were witnessed.</p>
 */
@Service
public class FiscalDocumentReprintService {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentReprintService.class);

    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository;

    public FiscalDocumentReprintService(
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository) {
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.fiscalDocumentArtifactRepository = fiscalDocumentArtifactRepository;
    }

    /**
     * One stamped document's bytes, by the id the caller named.
     *
     * @param fiscalDocumentId the document to reprint, which is the artefact's primary key as well
     * @return the document's identity, the folio it states and the bytes that were stamped
     * @throws FiscalDocumentArtifactNotFoundException when the document is not visible in the caller's company,
     *     is not a sale's document, is not {@code STAMPED}, or a stamped one kept no artefact
     */
    @Transactional(readOnly = true)
    public FiscalDocumentXmlReprint reprintXml(UUID fiscalDocumentId) {
        Objects.requireNonNull(fiscalDocumentId, "a reprint names the document it is about");

        // A caller with no tenant sees no document: the 404 is the honest answer, and it is the same one a
        // document of another company receives, so the two cannot be told apart (L15(c)).
        var companyId = TenantScope.current()
                .map(TenantScope.Scope::companyId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new FiscalDocumentArtifactNotFoundException(fiscalDocumentId));

        // The status and the source kind are checked on the document before the artefact is asked for, so the
        // reader states S10's two rules itself: only a document the authority witnessed has bytes to reprint,
        // and only a sale's document is what this endpoint serves. The schema does not couple the artefact to
        // either (L9(d) reads the primary key, not a constraint), so relying on the writer's discipline would be
        // relying on the one thing a reader cannot see.
        var document = fiscalDocumentRepository
                .findByCompanyIdAndId(companyId, fiscalDocumentId)
                .filter(candidate -> FiscalDocument.STATUS_STAMPED.equals(candidate.getStatus()))
                .filter(candidate ->
                        FiscalDocumentSnapshotWriter.SOURCE_TYPE_SALES_ORDER.equals(candidate.getSourceType()))
                .orElseThrow(() -> new FiscalDocumentArtifactNotFoundException(fiscalDocumentId));

        var xml = fiscalDocumentArtifactRepository
                .findByCompanyIdAndFiscalDocumentId(companyId, document.getId())
                .map(FiscalDocumentArtifact::getXml)
                // The schema does not forbid a stamped document without an artefact — L9(d) reads the primary
                // key and not a constraint — so a stamped row that kept none is a defect the caller receives as
                // "nothing to reprint" instead of as a regenerated document that cannot be proven to be the
                // original (S10).
                .orElseThrow(() -> new FiscalDocumentArtifactNotFoundException(fiscalDocumentId));

        logger.info(
                "Fiscal document reprinted: document={}, company={}, uuid={}, characters={}",
                document.getId(),
                companyId,
                document.getUuidFiscal(),
                xml.length());

        return new FiscalDocumentXmlReprint(
                document.getId(), document.getFolio(), xml.getBytes(StandardCharsets.UTF_8));
    }
}
