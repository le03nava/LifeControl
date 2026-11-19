package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationRejectedException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentAlreadyCancelledException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentNotStampedException;
import com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioRequiredException;
import com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioUnknownException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentReprintService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentSnapshotWriter;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cancellation service and the row it writes: {@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, under the
 * contract decisions {@code L20}(c)–(g) read from the vendor's own pages ({@code T8d}).
 *
 * <p><b>Why this is a {@code @SpringBootTest} against real PostgreSQL.</b> Three of the facts under test exist only
 * there. A cancellation row is a <b>new</b> row against a stamped one that {@code V108} refuses to update, so "the
 * stamped row is untouched" is the schema's answer and not this suite's. {@code uq_fiscal_document_cancels} is what
 * states that a document is cancelled once. And {@code replaces_fiscal_document_id} is a composite foreign key to
 * {@code (company_id, id)}, so "the folio resolves to a document of our own" is a pointer only the database can
 * confirm.</p>
 *
 * <p><b>Why the provider is a double and what it proves.</b> The vendor's adapter is {@code T8c}; what this slice owes
 * is which of the three outcomes writes a row. {@link InMemoryFiscalCancellationProvider} answers each on demand and
 * counts every call, so "the authority was never asked" is an assertion rather than an argument, and
 * {@link FiscalCancellationProviderTestConfiguration.InMemoryCfdiCancellationSealer} records the bytes the service
 * asked to have signed, which is what makes the seal a function of the seal-less rendering observable without a
 * certificate.</p>
 *
 * <p><b>Why the row is read with {@code JdbcTemplate}.</b> The row set is the fiscal evidence an auditor and a reprint
 * read, and the entity is what the service means to have written; every case asserts both, and the row is read under
 * the same pooled role and the same tenant scope the application uses.</p>
 *
 * <p><b>What the fourth state changed here ({@code S2} and {@code S3} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}).</b> A submitted cancellation used to write nothing, because
 * {@code ck_fiscal_document_status} had no state for it; {@code V113} adds {@code CANCELLATION_PENDING} and the
 * submission now writes that row. The 202 body does not move ({@code L2}(g)), so these cases count rows and provider
 * calls rather than reading the answer alone ({@code S8}): one pending row for a {@code 211}, one {@code CANCELLED}
 * row for a {@code 201}, none of either for a refusal, and no second submission once the pointer exists.</p>
 */
@SpringBootTest
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Fiscal document cancellation (S7)")
class FiscalDocumentCancellationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt the stamped document every case starts from was written under. */
    private static final String ATTEMPT = "cancel-2026-11-16-01";

    /** A second attempt, for the draft a case needs to prove that a draft is not cancellable. */
    private static final String DRAFT_ATTEMPT = "cancel-2026-11-16-02";

    /** The attempt of the second sale of the same company, which substitutes the first document. */
    private static final String SUBSTITUTING_ATTEMPT = "cancel-2026-11-16-03";

    /**
     * The columns {@code V109} froze on the document, which a cancellation row has to copy rather than point at.
     *
     * <p>Listed rather than compared column by column so a column added to the snapshot is a decision taken here: a
     * copy that silently stopped copying one would otherwise pass every assertion below.</p>
     */
    private static final List<String> FROZEN_HEADER_COLUMNS = List.of(
            "receptor_rfc",
            "receptor_name",
            "receptor_regimen",
            "receptor_zip_code",
            "receptor_uso_cfdi",
            "emisor_rfc",
            "emisor_name",
            "emisor_regimen",
            "lugar_expedicion",
            "currency",
            "subtotal",
            "discount_total",
            "tax_transferred",
            "tax_withheld",
            "total_amount");

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private FiscalDocumentCancellationService cancellations;

    @Autowired
    private FiscalDocumentReprintService reprints;

    @Autowired
    private FiscalDocumentSnapshotWriter snapshotWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    private UUID stamped;

    @BeforeEach
    void seedAStampedDocument() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();

        // The stamp is not the subject here: the counters this suite reads belong to the cancelling port and its
        // sealer, so they are cleared once the document the cases cancel already exists.
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
        asCompany(seed.company());
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────
    // S7 — the row a cancellation writes
    // ──────────────────────────────────────

    @Test
    @DisplayName("S7: a granted cancellation writes exactly one CANCELLED row that names the cancelled document, "
            + "carries the motive, the authority's code and no settle stamp, and copies every frozen fact of the "
            + "source")
    void aGrantedCancellationWritesOneRowCopyingTheFrozenFacts() {
        var before = row(stamped);
        var stampedBytes =
                FiscalStampProviderTestConfiguration.PROVIDER.lastAnswerXml().getBytes(StandardCharsets.UTF_8);

        var answer = cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(answer.wrote()).isTrue();
        assertThat(answer.cancellationDocumentId()).isNotEqualTo(stamped);
        assertThat(answer.cancelledDocumentId()).isEqualTo(stamped);
        assertThat(answer.motive()).isEqualTo(FiscalCancellationMotive.ERRORS_WITHOUT_RELATION.code());
        assertThat(answer.status()).isEqualTo("CANCELLED");
        assertThat(answer.statusUuid()).isEqualTo(InMemoryFiscalCancellationProvider.CANCELLED_STATUS_UUID);

        var cancellation = row(answer.cancellationDocumentId());
        assertThat(cancellation.get("status")).isEqualTo("CANCELLED");
        assertThat(cancellation.get("cancels_fiscal_document_id")).isEqualTo(stamped);
        assertThat(cancellation.get("cancellation_motive")).isEqualTo("02");
        assertThat(cancellation.get("replaces_fiscal_document_id"))
                .as("a motive that replaces nothing states no substituting document")
                .isNull();
        assertThat(cancellation.get("uuid_fiscal"))
                .as("the UUID the authority cancels belongs to the cancelled row, not to this one")
                .isNull();
        assertThat(cancellation.get("stamped_at"))
                .as("the authority witnessed the cancelled document, not this row")
                .isNull();
        assertThat(cancellation.get("company_id")).isEqualTo(seed.company());
        assertThat(cancellation.get("cancellation_status_uuid"))
                .as("V113: the authority's own code is kept on the row, which is what a later reader quotes")
                .isEqualTo(InMemoryFiscalCancellationProvider.CANCELLED_STATUS_UUID);
        assertThat(cancellation.get("cancellation_settled_at"))
                .as("a granted cancellation is CANCELLED and done: settled is a timestamp, not a state, and it is "
                        + "what a rejection waits for (L2(d))")
                .isNull();
        assertThat(cancellation.get("source_type")).isEqualTo("SALES_ORDER");
        assertThat(cancellation.get("source_id"))
                .as("the two rows are one sale's history")
                .isEqualTo(seed.sale());

        for (var column : FROZEN_HEADER_COLUMNS) {
            assertThat(cancellation.get(column))
                    .as("%s is a frozen fact of the document, so the cancellation copies it (S9)", column)
                    .isEqualTo(before.get(column));
        }

        assertThat(lineCount(answer.cancellationDocumentId()))
                .as("the lines state what was invoiced; the cancellation states that it stopped existing")
                .isZero();

        assertThat(row(stamped))
                .as("the stamped row is byte-identical: a cancellation is a new row and never an edit (V108)")
                .isEqualTo(before);
        assertThat(reprints.reprintXml(stamped).xml())
                .as("and a cancelled document still reprints the very bytes that were stamped (S10)")
                .isEqualTo(stampedBytes);
    }

    @Test
    @DisplayName("S7/L20(c): the request is built from the frozen row and sealed before it is sent — the issuer is "
            + "the document's, and the signature is written into the request the authority receives")
    void theRequestIsBuiltFromTheFrozenRowAndSealed() {
        cancellations.cancel(stamped, FiscalCancellationMotive.OPERATION_NOT_CARRIED_OUT, null);

        var sealer = FiscalCancellationProviderTestConfiguration.SEALER;
        var provider = FiscalCancellationProviderTestConfiguration.PROVIDER;
        assertThat(sealer.invocations())
                .as("a cancellation asks for exactly one seal")
                .isEqualTo(1);
        assertThat(sealer.lastUnsignedXml())
                .as("the bytes a signature can be computed from state no signature yet")
                .isNotNull()
                .contains("<Cancelacion")
                .doesNotContain("<Signature");
        assertThat(provider.invocations()).isEqualTo(1);
        assertThat(provider.lastRequest().uuid())
                .as("the port states the UUID it is cancelling, so the answer can be compared with it")
                .isEqualTo(row(stamped).get("uuid_fiscal"));
        assertThat(provider.lastRequest().requestXml())
                .as("what travels is the sealed rendering, not the one that was signed")
                .isNotEqualTo(sealer.lastUnsignedXml())
                .contains(FiscalCancellationProviderTestConfiguration.InMemoryCfdiCancellationSealer.SIGNATURE)
                .contains("RfcEmisor=\"" + row(stamped).get("emisor_rfc") + "\"")
                .contains("Motivo=\"03\"");
    }

    // ──────────────────────────────────────
    // S2/S3 — a submitted cancellation is remembered
    // ──────────────────────────────────────

    @Test
    @DisplayName("S2/S3: a request the authority has not decided writes exactly one CANCELLATION_PENDING row "
            + "carrying the pointer, the motive, the authority's code and no settle stamp — and no CANCELLED row")
    void anInProcessAnswerWritesOnePendingRow() {
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        var before = documentCount();

        var answer = cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(answer.wrote())
                .as("L2(g): the 202 body does not change — an id and a state come together, or neither do")
                .isFalse();
        assertThat(answer.cancellationDocumentId()).isNull();
        assertThat(answer.status()).isNull();
        assertThat(answer.cancelledDocumentId()).isEqualTo(stamped);
        assertThat(answer.statusUuid()).isEqualTo(InMemoryFiscalCancellationProvider.IN_PROCESS_STATUS_UUID);
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("the authority was asked once and answered 211")
                .isEqualTo(1);

        assertThat(documentCount())
                .as("S3: the submission is remembered — one row more than before, and it is the pending one")
                .isEqualTo(before + 1);
        assertThat(countByStatus("CANCELLATION_PENDING")).isEqualTo(1);
        assertThat(countByStatus("CANCELLED"))
                .as("S2: a submitted request is not a cancellation, so nothing says CANCELLED")
                .isZero();

        var pending = pendingCancellationRow();
        assertThat(pending.get("status")).isEqualTo("CANCELLATION_PENDING");
        assertThat(pending.get("cancels_fiscal_document_id"))
                .as("L2(b): the pending row IS the cancellation row in waiting — the pointer is what makes a "
                        + "second submission impossible (uq_fiscal_document_cancels)")
                .isEqualTo(stamped);
        assertThat(pending.get("cancellation_motive")).isEqualTo("02");
        assertThat(pending.get("cancellation_status_uuid"))
                .as("211 is what the authority said, and the row states it")
                .isEqualTo(InMemoryFiscalCancellationProvider.IN_PROCESS_STATUS_UUID);
        assertThat(pending.get("cancellation_message"))
                .as("the authority's own sentence is kept, untranslated, for the operator")
                .isEqualTo("La cancelación está en proceso");
        assertThat(pending.get("cancellation_settled_at"))
                .as("L2(d): settled is the timestamp a reconciliation writes, and a submission has not settled")
                .isNull();
        assertThat(pending.get("uuid_fiscal"))
                .as("L2(c): the pending row carries no UUID of its own — the one the authority knows belongs to "
                        + "the cancelled document and is reached through the pointer")
                .isNull();
        assertThat(pending.get("stamped_at")).isNull();
        assertThat(row(stamped).get("status"))
                .as("and the document being cancelled stays STAMPED: it is what the sale still issues against")
                .isEqualTo("STAMPED");
    }

    @Test
    @DisplayName("S3/S8: 201 and 202 each write exactly one CANCELLED row carrying the authority's own code, and no "
            + "pending row — the terminal answer does not pass through the fourth state")
    void aGrantedCancellationWritesOneCancelledRowAndNoPendingRow() {
        var granted = cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(granted.statusUuid()).isEqualTo(InMemoryFiscalCancellationProvider.CANCELLED_STATUS_UUID);
        assertThat(countByStatus("CANCELLED"))
                .as("one row, and it is the cancelled one")
                .isEqualTo(1);
        assertThat(countByStatus("CANCELLATION_PENDING"))
                .as("a terminal answer goes straight to CANCELLED: nothing is left waiting for a reconciliation")
                .isZero();

        var cancellation = row(granted.cancellationDocumentId());
        assertThat(cancellation.get("cancellation_status_uuid"))
                .as("S3: the code the authority answered is stored, so the row says what it was told")
                .isEqualTo(InMemoryFiscalCancellationProvider.CANCELLED_STATUS_UUID);
        assertThat(cancellation.get("cancellation_settled_at"))
                .as("and it is not settled by a timestamp: settled is what a rejection keeps waiting on")
                .isNull();
        assertThat(cancellation.get("cancellation_message"))
                .as("201 and 202 are one outcome with two sentences, and the row keeps the one it received")
                .isEqualTo(InMemoryFiscalCancellationProvider.CANCELLED_MESSAGE);

        // 202 is the same outcome stated a second time: the authority says the document had already been cancelled.
        // It writes the same row, and the code it answered is the one the row keeps — which is the whole reason the
        // column is the authority's and not a constant.
        var secondSale = seed.anotherCompletedSale();
        // One UUID per document: uq_fiscal_document_uuid_fiscal is unique across the table, so the second stamped
        // document of this transaction needs its own.
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var second = stamper.stamp(secondSale, SUBSTITUTING_ATTEMPT).documentId();
        FiscalCancellationProviderTestConfiguration.PROVIDER.grantingUnder(
                InMemoryFiscalCancellationProvider.ALREADY_CANCELLED_STATUS_UUID,
                InMemoryFiscalCancellationProvider.ALREADY_CANCELLED_MESSAGE);

        var repeated = cancellations.cancel(second, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(repeated.statusUuid()).isEqualTo(InMemoryFiscalCancellationProvider.ALREADY_CANCELLED_STATUS_UUID);
        assertThat(countByStatus("CANCELLED"))
                .as("a second document, a second CANCELLED row: 202 is not a replay of 201")
                .isEqualTo(2);
        assertThat(countByStatus("CANCELLATION_PENDING"))
                .as("and still nothing waiting")
                .isZero();

        var alreadyCancelled = row(repeated.cancellationDocumentId());
        assertThat(alreadyCancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(alreadyCancelled.get("cancellation_status_uuid"))
                .as("the code is the authority's answer, never a constant this tree chose")
                .isEqualTo(InMemoryFiscalCancellationProvider.ALREADY_CANCELLED_STATUS_UUID);
        assertThat(alreadyCancelled.get("cancellation_settled_at"))
                .as("202 is finished by its state like 201: a granted cancellation is never settled by a timestamp")
                .isNull();
    }

    @Test
    @DisplayName("L20(c): a refusal writes nothing at all — no row, no pointer, and the document stays stamped")
    void aRejectionWritesNoRow() {
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.REJECTED);
        var before = documentCount();

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null))
                .isInstanceOfSatisfying(
                        FiscalCancellationRejectedException.class,
                        rejected -> assertThat(rejected.issues())
                                .as("the authority's codes are the operator's only way to fix the request")
                                .extracting(FiscalCancellationResult.Issue::code)
                                .containsExactly(InMemoryFiscalCancellationProvider.REJECTED_STATUS_UUID));

        assertThat(documentCount())
                .as("a refusal leaves the row set exactly as it found it")
                .isEqualTo(before);
        assertThat(countByStatus("CANCELLED"))
                .as("S8: zero rows of either cancellation state — a refusal is not remembered as attempted")
                .isZero();
        assertThat(countByStatus("CANCELLATION_PENDING")).isZero();
        assertThat(row(stamped).get("status")).isEqualTo("STAMPED");
    }

    // ──────────────────────────────────────
    // S2/L2(e) — the fourth state's guard
    // ──────────────────────────────────────

    @Test
    @DisplayName("S2/L2(e): a pending row may only stay pending or become CANCELLED — an UPDATE to STAMPED is "
            + "refused and the settled one is accepted")
    void aPendingRowCannotBeStampedAndCanBeSettled() {
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        var pending = (UUID) pendingCancellationRow().get("id");

        // A savepoint, because a refused statement aborts the transaction it ran in: the accepted transition
        // below has to be judged on the row the case reached, not on a transaction PostgreSQL already voided.
        jdbc.execute("SAVEPOINT pending_transition");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE fiscal_document SET status = 'STAMPED', uuid_fiscal = ?, stamped_at = now()"
                                + " WHERE id = ?",
                        UUID.randomUUID(),
                        pending))
                .as("L2(e): only a DRAFT has the transition out to STAMPED. A submitted cancellation that could "
                        + "be stamped would be a second stamp of a document the authority still holds")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CANCELLATION_PENDING");
        jdbc.execute("ROLLBACK TO SAVEPOINT pending_transition");
        assertThat(row(pending).get("status"))
                .as("and the refused transition changed nothing")
                .isEqualTo("CANCELLATION_PENDING");

        jdbc.update("UPDATE fiscal_document SET status = 'CANCELLED' WHERE id = ?", pending);

        assertThat(row(pending).get("status"))
                .as("the one transition the fourth state has: the reconciliation's, from pending to cancelled")
                .isEqualTo("CANCELLED");
    }

    // ──────────────────────────────────────
    // S7/L20(f) — the substituting folio
    // ──────────────────────────────────────

    @Test
    @DisplayName("S7/L20(f): motivo 01 without a substituting folio is refused before the authority is called")
    void motive01WithoutAFolioIsRefusedBeforeTheCall() {
        var before = documentCount();

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITH_RELATION, null))
                .isInstanceOf(FiscalSubstitutingFolioRequiredException.class);

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("a request the authority would refuse is never sent")
                .isZero();
        assertThat(FiscalCancellationProviderTestConfiguration.SEALER.invocations())
                .as("and nothing is even sealed")
                .isZero();
        assertThat(documentCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("S7: a folio that names no document of this company is a 400 naming the field, and the authority "
            + "is never called")
    void aFolioThatResolvesToNothingIsRefused() {
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITH_RELATION, unknown))
                .isInstanceOfSatisfying(
                        FiscalSubstitutingFolioUnknownException.class,
                        refusal -> assertThat(refusal.getField())
                                .as("a client marks the control from the field, not from the prose")
                                .isEqualTo(FiscalSubstitutingFolioUnknownException.FIELD))
                .hasMessageContaining(unknown.toString());

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    @Test
    @DisplayName("S7: a folio of another company is refused as a 400, because the pointer is a row of this tenant")
    void aFolioOfAnotherCompanyIsRefused() {
        var foreign = anotherCompanysStampedDocument();
        asCompany(seed.company());

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITH_RELATION, foreign))
                .isInstanceOf(FiscalSubstitutingFolioUnknownException.class);

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    @Test
    @DisplayName("S7: a folio that resolves to a stamped document of this company is written into "
            + "replaces_fiscal_document_id as a row id, which is what the column holds")
    void aResolvedFolioIsWrittenAsARowId() {
        var substitutingSale = seed.anotherCompletedSale();
        // The authority issues one UUID per document and uq_fiscal_document_uuid_fiscal is unique across the table,
        // so the second stamped document of this transaction needs its own.
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var substituting = stamper.stamp(substitutingSale, SUBSTITUTING_ATTEMPT).documentId();
        var substitutingUuid = row(substituting).get("uuid_fiscal");

        var answer = cancellations.cancel(
                stamped, FiscalCancellationMotive.ERRORS_WITH_RELATION, UUID.fromString(substitutingUuid.toString()));

        assertThat(row(answer.cancellationDocumentId()).get("replaces_fiscal_document_id"))
                .as("the column is a composite pointer to a row, so the folio resolves to one before the call")
                .isEqualTo(substituting);
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER
                        .lastRequest()
                        .requestXml())
                .contains("FolioSustitucion=\"" + substitutingUuid.toString().toUpperCase(Locale.ROOT) + "\"");
    }

    // ──────────────────────────────────────
    // the 404s and the 409
    // ──────────────────────────────────────

    @Test
    @DisplayName("S7: a draft, another company's document and an unknown id are all 404 — the reprint's answer, for "
            + "the reprint's reason")
    void nothingButAStampedDocumentOfThisCompanyCanBeCancelled() {
        var draft = snapshotWriter.createDraft(seed.sale(), DRAFT_ATTEMPT).id();
        var foreign = anotherCompanysStampedDocument();
        asCompany(seed.company());
        var unknown = UUID.randomUUID();

        for (var id : List.of(draft, foreign, unknown)) {
            assertThatThrownBy(() -> cancellations.cancel(id, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null))
                    .as("document %s has nothing to cancel in this company", id)
                    .isInstanceOf(FiscalDocumentNotStampedException.class);
        }

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("none of the three reached the authority")
                .isZero();
    }

    @Test
    @DisplayName("S7: a document that is already cancelled is a 409 and the authority is not asked twice")
    void anAlreadyCancelledDocumentIsAConflict() {
        var first = cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        var before = documentCount();

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null))
                .isInstanceOfSatisfying(
                        FiscalDocumentAlreadyCancelledException.class,
                        conflict -> assertThat(conflict.getMessage())
                                .as("the conflict names the row that already cancels it")
                                .contains(first.cancellationDocumentId().toString()));

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("the guard runs before the network call: uq_fiscal_document_cancels would answer after it")
                .isEqualTo(1);
        assertThat(documentCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("S7: a document whose cancellation is already submitted is a 409 and the authority is not asked "
            + "twice, because the pending row carries the unique pointer")
    void aDocumentWithAPendingCancellationCannotBeCancelledAgain() {
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);
        var pending = pendingCancellationRow();
        var before = documentCount();

        assertThatThrownBy(() -> cancellations.cancel(stamped, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null))
                .isInstanceOfSatisfying(
                        FiscalDocumentAlreadyCancelledException.class,
                        conflict -> assertThat(conflict.getMessage())
                                .as("the conflict names the blocking row and states what it says about this "
                                        + "document — S7's first clause is about a *pending* cancellation, so a "
                                        + "message that claimed the document was already cancelled would state "
                                        + "something false")
                                .contains(pending.get("id").toString())
                                .contains("CANCELLATION_PENDING")
                                .doesNotContain("is already cancelled by"));

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("S7: the guard reads the pointer uq_fiscal_document_cancels is unique on, so the second "
                        + "submission never reaches the authority")
                .isEqualTo(1);
        assertThat(documentCount()).isEqualTo(before);
        assertThat(countByStatus("CANCELLATION_PENDING")).isEqualTo(1);
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    /**
     * States one tenant for the three halves of the contract: the caller's own identity, the scope the service
     * reads and the setting the policy reads.
     *
     * <p>Each is needed and none implies the others: the JWT is what the store guard walks company &rarr; country
     * &rarr; region &rarr; zone &rarr; store, {@code TenantScope} is what a service states its reads with, and
     * {@code app.company_id} is what the row policies filter — a {@code @Transactional} suite opened its
     * transaction before any of its cases ran, so the last cannot be installed by a transaction boundary and has to
     * be stated inside the open one ({@code TenantFixtures} owns that rule). The identity is re-stated because the
     * cases that stamp a document of another company leave that company's caller in the context: restoring only the
     * tenant would hand the guard a caller of the wrong company, which is a refusal about the fixture and not about
     * the code under test.</p>
     */
    private void asCompany(UUID companyId) {
        seed.authenticateAs(seed.store());
        TenantScope.set(companyId, null);
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
    }

    /** Stamps a document of a company of its own, and answers its fiscal UUID. Leaves the tenant switched. */
    private UUID anotherCompanysStampedDocument() {
        var other = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var document = stamper.stamp(other.sale(), ATTEMPT).documentId();
        return UUID.fromString(row(document).get("uuid_fiscal").toString());
    }

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    private long documentCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company());
    }

    /** How many rows of one state this company holds, which is what a row-counting case asserts on (S8). */
    private long countByStatus(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = ?",
                Long.class,
                seed.company(),
                status);
    }

    /**
     * The one pending cancellation row of this company, read whole.
     *
     * <p>{@code queryForMap} and not a count: the case has to read the state, the pointer, the motive and the
     * three answer columns off the row the service wrote, and a count would let every one of them be wrong.</p>
     */
    private Map<String, Object> pendingCancellationRow() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND status = 'CANCELLATION_PENDING'",
                seed.company());
    }

    private long lineCount(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document_line WHERE fiscal_document_id = ?", Long.class, documentId);
    }
}
