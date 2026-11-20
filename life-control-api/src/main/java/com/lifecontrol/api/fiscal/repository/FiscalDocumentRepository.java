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
 * <p><b>What is deliberately not here.</b> No finder by id <b>alone</b> and none over a nullable
 * {@code source_id}: the document is addressed by the sale it derives from plus its attempt, which is the pair
 * {@code uq_fiscal_document_attempt} ({@code V110}) makes unique. A stamp transition states its own read; the
 * cancellation's two are below, and the reprint is {@link #findByCompanyIdAndId(UUID, UUID)}, whose id came from
 * a caller and therefore states the tenant as well.
 */
@Repository
public interface FiscalDocumentRepository extends JpaRepository<FiscalDocument, UUID> {

    /**
     * One document of one company, by the id the caller named — the reprint's first read ({@code S10},
     * {@code L15}(c)).
     *
     * <p>It names the company for the reason every finder here does, and here it is also the whole negative
     * answer: a document of another company is simply not found, which is the 404 {@code L15}(c) asks for
     * instead of a 403 that would admit the row exists.</p>
     */
    Optional<FiscalDocument> findByCompanyIdAndId(UUID companyId, UUID id);

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

    /**
     * The document the authority stamped under one fiscal UUID — how a substituting folio resolves to a row
     * ({@code S7}, {@code L20}(f) of {@code odd/tasks/cfdi-fiscal-document.md)).
     *
     * <p><b>Why the folio is a UUID and not the string the request carries.</b>
     * {@code fiscal_document.replaces_fiscal_document_id} is a composite foreign key to
     * {@code fiscal_document (company_id, id)} ({@code V108}), so what a cancellation stores is a <b>row id</b> and
     * not a folio. The authority spells the substituting document's folio as its fiscal UUID, and
     * {@code uq_fiscal_document_uuid_fiscal} makes that unique, which is what lets one read turn the request's value
     * into the pointer the column can hold.</p>
     *
     * <p><b>Why it names the company.</b> The same reason every finder here does — the policy already filters, and
     * naming it states the fact in the query — and here it is also the rule: a folio of <b>another company</b> is
     * not a document this company may substitute with, so it does not resolve, and the refusal is a 400 instead of a
     * composite foreign key violation that names a constraint and no fact.</p>
     */
    Optional<FiscalDocument> findByCompanyIdAndUuidFiscal(UUID companyId, UUID uuidFiscal);

    /**
     * The cancellation of one document, if one was written — {@code S7}'s "a document is cancelled once".
     *
     * <p><b>Why it reads by the pointer and not by the state.</b> The pointer <b>is</b> the rule:
     * {@code uq_fiscal_document_cancels} ({@code V108}) makes {@code cancels_fiscal_document_id} unique, so this
     * answers at most one row and its presence <i>is</i> "already cancelled". Reading the cancelled row's own status
     * instead would answer the same question today and stop answering it the day a document could be cancelled in
     * more than one way.</p>
     *
     * <p><b>Why it names the company.</b> As every finder here does; the composite pointer it reads is
     * {@code (company_id, cancels_fiscal_document_id)} in the schema, so the pair is the key the query states.</p>
     */
    Optional<FiscalDocument> findByCompanyIdAndCancelsFiscalDocumentId(UUID companyId, UUID cancelsFiscalDocumentId);

    /**
     * The submitted cancellations of one company that no answer has settled yet — the candidate set of the scheduled
     * pass (issue {@code #994}).
     *
     * <p><b>Why it filters the settle stamp as well as the state.</b> A rejection keeps the row
     * {@code CANCELLATION_PENDING} and finishes it with {@code cancellation_settled_at} (see
     * {@link FiscalDocument#settleCancellationRejected}), so a filter on the state alone would make a rejected
     * cancellation a candidate forever and have every pass ask the authority again about an answer it already gave.
     * The two halves together are the set the pass may act on: submitted, and unanswered.</p>
     *
     * <p><b>Why it names the company</b> — as every finder here does. Here it is also what makes the pass safe: it
     * runs inside {@code TenantScope#with} for the company the privileged enumerator named, so the read is policed by
     * the same policy every request is, and the enumerator is the only thing that ever saw more than one tenant.</p>
     */
    List<FiscalDocument> findByCompanyIdAndStatusAndCancellationSettledAtIsNull(UUID companyId, String status);
}
