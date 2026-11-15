package com.lifecontrol.api.fiscal.repository;

import com.lifecontrol.api.fiscal.model.FiscalDocumentLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The concepts of a fiscal document.
 *
 * <p><b>Why there is no finder by id.</b> A line is never addressed on its own: it is read as part of the
 * document it belongs to, which is what {@code V108}'s composite pointer and the company column state. The two
 * reads below are the writer's (how many concepts an attempt wrote, in the order it wrote them) and the
 * builder's (the same list, to render the XML).</p>
 */
@Repository
public interface FiscalDocumentLineRepository extends JpaRepository<FiscalDocumentLine, UUID> {

    /** The document's concepts in the order they were written. */
    List<FiscalDocumentLine> findByFiscalDocumentIdOrderByCreatedAtAsc(UUID fiscalDocumentId);

    /** How many concepts a document states, which is what an idempotent replay reports. */
    long countByFiscalDocumentId(UUID fiscalDocumentId);
}
