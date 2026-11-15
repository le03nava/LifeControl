package com.lifecontrol.api.fiscal.repository;

import com.lifecontrol.api.fiscal.model.FiscalDocumentArtifact;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The stored artefacts — the XML a stamp kept, one per document ({@code S10}, invariant F8).
 *
 * <p><b>Why the finder carries the company.</b> Same reason as the document's: {@code fiscal_document_artifact}
 * is armed since {@code V108}, and a caller that states the tenant in the query gets a refusal it can name
 * instead of a row whose visibility depends on the connection.</p>
 */
@Repository
public interface FiscalDocumentArtifactRepository extends JpaRepository<FiscalDocumentArtifact, UUID> {

    /** The artefact of one document of one company, which is the reprint's whole read. */
    Optional<FiscalDocumentArtifact> findByCompanyIdAndFiscalDocumentId(UUID companyId, UUID fiscalDocumentId);
}
