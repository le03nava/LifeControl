package com.lifecontrol.api.fiscal.repository;

import com.lifecontrol.api.fiscal.model.FiscalDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The fiscal documents of one tenant.
 *
 * <p><b>Why every finder names the company.</b> {@code fiscal_document} is armed with {@code ENABLE} and
 * {@code FORCE} since {@code V108}, so the policy already filters what a transaction may see — but the tenant
 * is then a property of the connection and not of the call. Naming it states the same fact in the query: a
 * document of another company is simply not there, which is the answer a caller reads as a refusal instead of
 * a row whose absence depends on which pooled connection served it.</p>
 *
 * <p><b>What is deliberately not here.</b> No finder by id alone and none over a nullable {@code source_id}:
 * the document is addressed by the sale it derives from plus its attempt, which is the pair
 * {@code uq_fiscal_document_attempt} ({@code V110}) makes unique. A stamp transition, a cancellation and a
 * reprint are the later slices' reads and will state their own.</p>
 */
@Repository
public interface FiscalDocumentRepository extends JpaRepository<FiscalDocument, UUID> {

    /**
     * The document one attempt of one sale wrote, if it wrote one.
     *
     * <p>This is the replay lookup of {@code S11}, and the index is the constraint's own btree: the attempt key
     * is unique per {@code (company_id, source_type, source_id)} by the schema, so this answers at most one row
     * even when two writers raced — the loser reads the winner's document and writes nothing
     * ({@code L12(h)}).</p>
     */
    Optional<FiscalDocument> findByCompanyIdAndSourceTypeAndSourceIdAndAttemptKey(
            UUID companyId, String sourceType, UUID sourceId, String attemptKey);

    /**
     * Every document one sale has, oldest first — the shape {@code S6} describes: a cancelled document and its
     * replacement are two rows, and the order is what tells a reader which one came first.
     */
    List<FiscalDocument> findByCompanyIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(
            UUID companyId, String sourceType, UUID sourceId);
}
