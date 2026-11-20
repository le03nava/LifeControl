package com.lifecontrol.api.fiscal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One fiscal document: a row set of its own that derives from a sale by {@code (source_type, source_id)} and is
 * never a column of it (ADR-0007 decision 1, {@code V108__fiscal_document.sql}, {@code V109}, {@code V110}).
 *
 * <p><b>Why the entity exists here and the document does not exist as a sale column.</b> The sale is mutable
 * and the document is not: an order is editable until it reaches a terminal state, its lines are replaced
 * wholesale by the {@code PUT}, and a deleted order can be re-enabled. A fiscal document whose contents could
 * follow those edits would be a document that changes after the authority witnessed it, so it is a separate
 * row set written once from a snapshot — the reason {@link #getReceptorZipCode()} and
 * {@link #getEmisorRegimen()}, which are copies of live catalog columns, exist at all.</p>
 *
 * <p><b>What is mapped writable and what is not.</b> Three writers share this row set, and the mapping states
 * which columns each one owns. {@code FiscalDocumentSnapshotWriter} states the identity, the two fiscal faces,
 * the place of issue, the money and the attempt key of a {@code DRAFT};
 * {@code com.lifecontrol.api.fiscal.service.FiscalDocumentStamper} states the one transition out of it —
 * {@link #setStatus(String)}, {@link #setUuidFiscal(java.util.UUID)} and {@link #setStampedAt} — which is why
 * those three have setters and the header does not: {@code tipo_comprobante}, {@code metodo_pago},
 * {@code forma_pago} and {@code exportacion} are {@code insertable = false, updatable = false} because the
 * schema's defaults write them ({@code V109}), and no code states them until {@code T9};
 * {@code com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService} <b>inserts</b> a second row
 * through {@link #cancellationOf(FiscalDocument, FiscalCancellationMotive, java.util.UUID, String, String, String)}
 * — or, when the authority has the request and has not decided, through
 * {@link #pendingCancellationOf(FiscalDocument, FiscalCancellationMotive, java.util.UUID, String, String, String)} —
 * and never touches the stamped one ({@code S7}, {@code S2}). {@code status} is a {@code String} and not an enum
 * because the vocabulary lives in {@code ck_fiscal_document_status} ({@code L9}(i)).</p>
 *
 * <p><b>What the database refuses, and therefore what this mapping does not have to.</b> {@code V108} revokes
 * {@code DELETE} and guards {@code UPDATE} with a trigger, so a {@code STAMPED} or {@code CANCELLED} row
 * refuses every write and no setter here can rewrite history: the one legitimate update is the transition out
 * of {@code DRAFT}. A {@code CANCELLATION_PENDING} row has exactly one transition of its own — to
 * {@code CANCELLED}, written by the reconciliation — and the same guard refuses every other target
 * ({@code V113}, {@code L2}(e)). The identity columns ({@code company_id}, {@code source_type}, {@code source_id})
 * are refused on any row, which is why moving a document to another sale is not a setter anybody owns.</p>
 */
@Entity
@Table(name = "fiscal_document")
public class FiscalDocument {

    /** The only state this slice writes: the draft a stamp attempt leaves behind, mutable and unstamped. */
    public static final String STATUS_DRAFT = "DRAFT";

    /**
     * The state the stamp writes: the authority witnessed this document, and nothing but a cancellation can
     * follow it ({@code ck_fiscal_document_status}, {@code V108}).
     *
     * <p>Named here for the same reason {@link #STATUS_DRAFT} is: the transition out of {@code DRAFT} is one
     * comparison, and a literal in a second class is a second place the spelling can drift from the
     * constraint that enforces it.</p>
     */
    public static final String STATUS_STAMPED = "STAMPED";

    /**
     * The state of a cancellation row: a document the authority was asked to cancel and answered that it
     * cancelled ({@code S7}, {@code ck_fiscal_document_status}).
     *
     * <p>Named here for the same reason as the other two: the factory below writes it, the service compares it, and
     * a literal in a second class is a spelling free to drift from the constraint that enforces it.</p>
     */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * The state of a <b>submitted</b> cancellation: the authority holds the request and has not decided
     * ({@code S2} and {@code S3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, {@code V113}).
     *
     * <p><b>Why it is not {@code CANCELLED}.</b> A request the receiver has not answered is not a document the SAT
     * cancelled, and writing {@code CANCELLED} on it is the lie {@code L20}(d) refuses. This is the missing word
     * that let the submission be remembered instead of forgotten: the row keeps the pointer, the motive and the
     * authority's code, and a later pass resolves it into {@code CANCELLED} or into a settled rejection.</p>
     *
     * <p>Twenty characters, exactly the width {@code fiscal_document.status} declares — the longest value the
     * closed vocabulary can hold without retyping the column ({@code L2}(a)).</p>
     */
    public static final String STATUS_CANCELLATION_PENDING = "CANCELLATION_PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "source_type", length = 40, nullable = false)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /**
     * The state, as a {@code String} and not an enum.
     *
     * <p>{@code L9(i)} keeps the three enums with their first reader, which is A3's builder; a status enum now
     * would be a vocabulary nothing else reads. What makes it safe to defer is that the closed set lives in
     * {@code ck_fiscal_document_status} and is verified there, and this slice writes one value.</p>
     */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** The optional local convention the authority's schema does not require (ADR-0007 decision 2). */
    @Column(name = "serie", length = 25)
    private String serie;

    /** The folio, written because humans read it and never because a stamp requires it. */
    @Column(name = "folio", length = 40)
    private String folio;

    /** The authority's UUID: non-null exactly on a stamped row, which the schema enforces both ways. */
    @Column(name = "uuid_fiscal")
    private UUID uuidFiscal;

    @Column(name = "stamped_at")
    private OffsetDateTime stampedAt;

    @Column(name = "cancels_fiscal_document_id")
    private UUID cancelsFiscalDocumentId;

    @Column(name = "cancellation_motive", length = 2)
    private String cancellationMotive;

    @Column(name = "replaces_fiscal_document_id")
    private UUID replacesFiscalDocumentId;

    /**
     * The authority's code for the answer it gave this cancellation ({@code L2}(d), {@code V113}).
     *
     * <p>Four characters because the vendor's catalogue states four-digit codes ({@code 211}, {@code 204},
     * {@code 213}), and the value is the wire vocabulary an operator quotes back and never a translation. Non-null
     * exactly on a cancellation row, which {@code ck_fiscal_document_cancellation_answer} enforces.</p>
     */
    @Column(name = "cancellation_status_uuid", length = 4)
    private String cancellationStatusUuid;

    /** The authority's sentence for that code, kept untranslated. {@code TEXT}, because a sentence is not a name. */
    @Column(name = "cancellation_message", columnDefinition = "TEXT")
    private String cancellationMessage;

    /**
     * When a pass decided this cancellation was finished, or {@code null} while it is not.
     *
     * <p><b>Settled is this column and not a state</b> ({@code L2}(d)): a rejection keeps the row at
     * {@code CANCELLATION_PENDING} and is settled here, which is what lets a second pass skip it without asking the
     * authority again. Read-only in this slice — the pass that writes it is the reconciliation, and a setter here
     * would be a second way to state a fact the row records once.</p>
     */
    @Column(name = "cancellation_settled_at")
    private OffsetDateTime cancellationSettledAt;

    /** Who issued the document, as the tree records an actor elsewhere. Never a fiscal fact. */
    @Column(name = "issued_by", length = 255, nullable = false)
    private String issuedBy;

    /**
     * The attempt key that wrote this row ({@code S11}, {@code L12(h)}, {@code V110}).
     *
     * <p>Nullable, and the nullability is the rule: a cancellation and a replacement are rows of this table
     * that are not attempts. Two rows of one sale carrying the same key are refused by
     * {@code uq_fiscal_document_attempt}; two keyless rows are not, because PostgreSQL treats {@code NULL}s as
     * distinct.</p>
     */
    @Column(name = "attempt_key", length = 64)
    private String attemptKey;

    // ─── the receiver's frozen identity (S9, ADR-0007 decision 4) ─────────────────────

    @Column(name = "receptor_rfc", length = 13, nullable = false)
    private String receptorRfc;

    @Column(name = "receptor_name", length = 255, nullable = false)
    private String receptorName;

    @Column(name = "receptor_regimen", length = 3, nullable = false)
    private String receptorRegimen;

    @Column(name = "receptor_zip_code", length = 5, nullable = false)
    private String receptorZipCode;

    @Column(name = "receptor_uso_cfdi", length = 5, nullable = false)
    private String receptorUsoCfdi;

    // ─── the issuer's frozen identity (S9, the block's omission corrected by L9(f)) ────

    @Column(name = "emisor_rfc", length = 13, nullable = false)
    private String emisorRfc;

    @Column(name = "emisor_name", length = 255, nullable = false)
    private String emisorName;

    @Column(name = "emisor_regimen", length = 3, nullable = false)
    private String emisorRegimen;

    /** The authority's type of document: {@code I} today, and the schema's default writes it. */
    @Column(name = "tipo_comprobante", length = 1, nullable = false, insertable = false, updatable = false)
    private String tipoComprobante;

    /** {@code PUE} or {@code PPD}; T9 is what refuses the deferred one, and the default writes {@code PUE}. */
    @Column(name = "metodo_pago", length = 3, nullable = false, insertable = false, updatable = false)
    private String metodoPago;

    /** Conditional in the authority's schema, which is why the column admits no value at all. */
    @Column(name = "forma_pago", length = 2, insertable = false, updatable = false)
    private String formaPago;

    /** The export declaration, required on every document and defaulted by the schema until S12's slice. */
    @Column(name = "exportacion", length = 2, nullable = false, insertable = false, updatable = false)
    private String exportacion;

    /** The place of issue, frozen from {@code companies.fiscal_zip_code} (S1 + S9, L6(b)). */
    @Column(name = "lugar_expedicion", length = 5, nullable = false)
    private String lugarExpedicion;

    // ─── the money (S8, ADR-0007 decision 7) ──────────────────────────────────────────

    /** {@code CHAR(3)}, which a {@code String} maps as {@code varchar} unless the type is stated. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "subtotal", precision = 19, scale = 4, nullable = false)
    private BigDecimal subtotal;

    @Column(name = "discount_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal discountTotal;

    @Column(name = "tax_transferred", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxTransferred;

    @Column(name = "tax_withheld", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxWithheld;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    /**
     * When the draft was written, as the schema's own {@code now()}.
     *
     * <p>Read-only for the reason the tree states on every column the database defaults: Hibernate would send
     * the instance's field, and a clock in the application is a second authority for a fact the schema already
     * records. It is not {@code stamped_at}, which is when the authority witnessed the document.</p>
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Default constructor, for JPA and for the one writer of these rows.
     *
     * <p>Public rather than package-private because {@code FiscalDocumentSnapshotWriter} lives in
     * {@code fiscal.service} and builds both rows field by field: a factory per row shape would restate the
     * column list a third time (here, in the writer and in the schema) and the three would be free to drift.
     * What keeps a written row immutable is not this constructor but {@code V108}: {@code DELETE} is revoked and
     * {@code UPDATE} is guarded by a trigger, so a setter cannot amend history.</p>
     */
    public FiscalDocument() {}

    /**
     * The cancellation of {@code cancelled}: a <b>new row</b> that names the document it cancels, and never an
     * edit of it ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}).
     *
     * <p><b>This is the row for a cancellation the authority granted.</b> Its sibling
     * {@link #pendingCancellationOf(FiscalDocument, FiscalCancellationMotive, UUID, String, String, String)} builds the
     * same row for a submission the authority has not decided ({@code S2}, {@code V113}): both delegate to
     * {@link #cancellationRow(FiscalDocument, FiscalCancellationMotive, UUID, String, String)}, so the field list below
     * is stated once for the two states. What the paragraph about {@code uuid_fiscal} and {@code stamped_at} below says
     * holds for both, and for the reason it gives.</p>
     *
     * <p><b>Why it copies every frozen fact instead of pointing at the cancelled row.</b> {@code V109} made the
     * document's frozen header {@code NOT NULL} — the receiver's and the issuer's identity, the place of issue,
     * the currency and the four money parts — so a thin pointer would be a row the schema refuses. Copying them is
     * also what keeps {@code S9} true for the row a reader finds: the cancellation states the same receiver, the
     * same issuer and the same money as the document it cancels, as they were frozen, and reads no live catalog to
     * do it.</p>
     *
     * <p><b>Why it carries no {@code fiscal_document_line} rows.</b> The lines state <b>what was invoiced</b>; a
     * cancellation states that the invoice stopped existing. Copying them would double the line count of a table
     * that refuses {@code DELETE} ({@code V108}) to say the same thing twice, and the lines are reachable through
     * {@code cancels_fiscal_document_id} when someone needs to read them. The row it writes therefore has none, and
     * that absence is the rule rather than an omission.</p>
     *
     * <p><b>What it deliberately leaves {@code null}, and why the schema requires it.</b> {@code uuid_fiscal} and
     * {@code stamped_at} belong to the document the authority stamped —
     * {@code ck_fiscal_document_uuid_matches_status} and {@code ck_fiscal_document_stamped_at} are equalities, so a
     * {@code CANCELLED} row that carried either would be a row claiming the authority witnessed <b>it</b>. The same
     * equality is why a cancellation has no UUID of its own: the UUID the authority cancels is the cancelled row's.
     * {@code serie} and {@code folio} stay null as well, because the local folio convention names a document that
     * was issued and copying it would put one folio on two rows. {@code attempt_key} stays null because a
     * cancellation is not an attempt at stamping ({@code V110}).</p>
     *
     * <p><b>What it keeps from the source.</b> The tenant, the source type and the source id, so the row set stays
     * one sale's history ({@code S6}: a cancelled document and its replacement are two rows of one sale). The
     * expression of that is below, field by field, so a column added to the snapshot is a decision taken here and
     * not a value silently inherited.</p>
     *
     * @param cancelled the stamped document being cancelled; a row that is not {@code STAMPED} is refused, because
     *     nothing else has a witnessed document to cancel and the schema does not couple the pointer to the state
     * @param motive which of the authority's four motives the cancellation states
     * @param replacesFiscalDocumentId the row id of the document that substitutes this one, or {@code null} when the
     *     motive replaces nothing; it is an id and not a folio, because the column is a composite foreign key to
     *     {@code fiscal_document (company_id, id)}
     * @param issuedBy who asked for the cancellation, {@code issued_by}; not a fiscal fact and not the cancelled
     *     document's actor, because cancelling is a new act by a person
     * @param statusUuid the authority's code for the answer it gave — {@code 201} or {@code 202} on this row, because
     *     it is the row that says the authority cancelled; the code is stored as the authority stated it
     *     ({@code L2}(d))
     * @param message the authority's sentence for that code, untranslated, or {@code null} when it stated none
     * @return a new row: {@code CANCELLED}, naming its source, with the source's frozen facts and no lines
     */
    public static FiscalDocument cancellationOf(
            FiscalDocument cancelled,
            FiscalCancellationMotive motive,
            UUID replacesFiscalDocumentId,
            String issuedBy,
            String statusUuid,
            String message) {
        return answer(
                cancellationRow(cancelled, motive, replacesFiscalDocumentId, issuedBy, STATUS_CANCELLED),
                statusUuid,
                message);
    }

    /**
     * The <b>submitted</b> cancellation of {@code cancelled}: the same row, waiting for the authority to decide
     * ({@code S2} and {@code S3} of {@code odd/tasks/fiscal-cancellation-reconciliation.md}, {@code L2}(c)–(d)).
     *
     * <p><b>What it copies and what it changes.</b> Every field is the one
     * {@link #cancellationOf(FiscalDocument, FiscalCancellationMotive, UUID, String, String, String)} builds — the
     * tenant, the source pair, the pointer, the motive, the substituting row and the whole frozen snapshot — and the
     * two factories share that copy rather than repeating it, so a column added to the snapshot is a decision taken in
     * one place and not two that can drift. What differs is exactly the answer: the state is
     * {@link #STATUS_CANCELLATION_PENDING} instead of {@code CANCELLED}, {@code cancellation_status_uuid} is the code
     * the authority answered ({@code 211} in process), and {@code cancellation_settled_at} is {@code null} because a
     * submission has not settled.</p>
     *
     * <p><b>Why the row carries no {@code uuid_fiscal} and no {@code stamped_at}, and why that is the schema's rule</b>
     * ({@code L2}(c)): {@code ck_fiscal_document_uuid_matches_status} and {@code ck_fiscal_document_stamped_at} are
     * equalities and stay as {@code V108} states them, so a pending row claiming either would claim the authority
     * witnessed <b>it</b>. The UUID a later reconciliation asks about is the pointed document's, reached through
     * {@code cancels_fiscal_document_id}.</p>
     *
     * @param cancelled the stamped document whose cancellation was submitted; anything else is refused, for the
     *     reason the field-by-field argument above states
     * @param motive the motive the submission stated
     * @param replacesFiscalDocumentId the substituting document's row id, or {@code null}
     * @param issuedBy who asked for the cancellation
     * @param statusUuid the authority's code for the answer it gave the submission — {@code 211} when it is in
     *     process
     * @param message the authority's sentence for that code, untranslated, or {@code null}
     * @return a new row: {@code CANCELLATION_PENDING}, carrying the authority's code and no settle stamp
     */
    public static FiscalDocument pendingCancellationOf(
            FiscalDocument cancelled,
            FiscalCancellationMotive motive,
            UUID replacesFiscalDocumentId,
            String issuedBy,
            String statusUuid,
            String message) {
        return answer(
                cancellationRow(cancelled, motive, replacesFiscalDocumentId, issuedBy, STATUS_CANCELLATION_PENDING),
                statusUuid,
                message);
    }

    /**
     * The one copy of the field list both cancellation factories build, with the state left to the caller.
     *
     * <p>Separate and private on purpose: {@code CANCELLED} and {@code CANCELLATION_PENDING} are the same row before
     * and after the authority answers, so two copies of the sixteen assignments above would be two places a copied
     * column could be forgotten — and forgetting one is silent, because the schema only requires that the row be
     * complete, not that it agree with the source.</p>
     */
    private static FiscalDocument cancellationRow(
            FiscalDocument cancelled,
            FiscalCancellationMotive motive,
            UUID replacesFiscalDocumentId,
            String issuedBy,
            String status) {
        Objects.requireNonNull(cancelled, "a cancellation names the document it cancels");
        Objects.requireNonNull(motive, "a cancellation states the authority's motive");
        Objects.requireNonNull(issuedBy, "a cancellation states who asked for it");
        if (!STATUS_STAMPED.equals(cancelled.getStatus())) {
            throw new IllegalStateException("Fiscal document " + cancelled.getId() + " is " + cancelled.getStatus()
                    + " and a cancellation names a STAMPED document: a draft was never witnessed by the authority "
                    + "and nothing else has a document to cancel.");
        }

        var cancellation = new FiscalDocument();
        cancellation.setCompanyId(cancelled.getCompanyId());
        cancellation.setSourceType(cancelled.getSourceType());
        cancellation.setSourceId(cancelled.getSourceId());
        cancellation.setStatus(status);
        cancellation.setIssuedBy(issuedBy);

        // The pointers, and the three columns ck_fiscal_document_cancellation couples to them.
        cancellation.cancelsFiscalDocumentId = cancelled.getId();
        cancellation.cancellationMotive = motive.code();
        cancellation.replacesFiscalDocumentId = replacesFiscalDocumentId;

        // The two facts that belong to the stamped row and to nothing else (the schema states both as equalities).
        cancellation.uuidFiscal = null;
        cancellation.stampedAt = null;

        // The receiver's and the issuer's frozen identities, as the cancelled row states them (S9).
        cancellation.setReceptorRfc(cancelled.getReceptorRfc());
        cancellation.setReceptorName(cancelled.getReceptorName());
        cancellation.setReceptorRegimen(cancelled.getReceptorRegimen());
        cancellation.setReceptorZipCode(cancelled.getReceptorZipCode());
        cancellation.setReceptorUsoCfdi(cancelled.getReceptorUsoCfdi());
        cancellation.setEmisorRfc(cancelled.getEmisorRfc());
        cancellation.setEmisorName(cancelled.getEmisorName());
        cancellation.setEmisorRegimen(cancelled.getEmisorRegimen());
        cancellation.setLugarExpedicion(cancelled.getLugarExpedicion());

        // The money, copied so ck_fiscal_document_total keeps holding on a row that states the same document.
        cancellation.setCurrency(cancelled.getCurrency());
        cancellation.setSubtotal(cancelled.getSubtotal());
        cancellation.setDiscountTotal(cancelled.getDiscountTotal());
        cancellation.setTaxTransferred(cancelled.getTaxTransferred());
        cancellation.setTaxWithheld(cancelled.getTaxWithheld());
        cancellation.setTotalAmount(cancelled.getTotalAmount());
        return cancellation;
    }

    /**
     * The authority's verdict on a cancellation row: its code, its sentence, and a settle stamp of {@code null}.
     *
     * <p>The stamp is written by the pass that settles the row ({@code L2}(d)) and by nothing here; a factory that
     * filled it would be claiming a settlement the caller never observed. The code is required because
     * {@code ck_fiscal_document_cancellation_answer} couples the answer to the row and a cancellation row without a
     * code is a row that says something happened without saying what.</p>
     */
    private static FiscalDocument answer(FiscalDocument row, String statusUuid, String message) {
        Objects.requireNonNull(statusUuid, "a cancellation row states the authority's code");
        row.cancellationStatusUuid = statusUuid;
        row.cancellationMessage = message;
        row.cancellationSettledAt = null;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getStatus() {
        return status;
    }

    /** The optional local series, absent on a document that states none ({@code Serie} is optional per the schema). */
    public String getSerie() {
        return serie;
    }

    /** The optional local folio, absent on a document that states none ({@code Folio} is optional per the schema). */
    public String getFolio() {
        return folio;
    }

    /** The authority's UUID: non-null exactly on a stamped row, which {@code ck_fiscal_document_uuid_matches_status} enforces. */
    public UUID getUuidFiscal() {
        return uuidFiscal;
    }

    /** When the authority stamped it, non-null exactly on a stamped row. It is not {@link #getCreatedAt()}. */
    public OffsetDateTime getStampedAt() {
        return stampedAt;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public String getReceptorRfc() {
        return receptorRfc;
    }

    public String getReceptorName() {
        return receptorName;
    }

    public String getReceptorRegimen() {
        return receptorRegimen;
    }

    public String getReceptorZipCode() {
        return receptorZipCode;
    }

    public String getReceptorUsoCfdi() {
        return receptorUsoCfdi;
    }

    public String getEmisorRfc() {
        return emisorRfc;
    }

    public String getEmisorName() {
        return emisorName;
    }

    public String getEmisorRegimen() {
        return emisorRegimen;
    }

    public String getTipoComprobante() {
        return tipoComprobante;
    }

    public String getMetodoPago() {
        return metodoPago;
    }

    public String getFormaPago() {
        return formaPago;
    }

    public String getExportacion() {
        return exportacion;
    }

    public String getLugarExpedicion() {
        return lugarExpedicion;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTaxTransferred() {
        return taxTransferred;
    }

    public BigDecimal getTaxWithheld() {
        return taxWithheld;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * The authority's motive a cancellation row carries, and {@code null} on every row that is not one.
     *
     * <p><b>Why this getter exists and no setter beside it.</b> The write path is
     * {@link #cancellationOf(FiscalDocument, FiscalCancellationMotive, UUID, String, String, String)}, which states
     * the motive when it builds the row; a setter would be a second way to write a column the schema couples to
     * {@code status = 'CANCELLED'} and to the pointer it names ({@code ck_fiscal_document_cancellation}). What was
     * missing was a <b>reader</b>: the sale's read reports the motive of the cancellation that ended its document
     * ({@code S1} of {@code odd/tasks/fiscal-document-read.md}), and the value as stored is the authority's own code
     * — the wire vocabulary, which is what a caller quotes back.</p>
     */
    public String getCancellationMotive() {
        return cancellationMotive;
    }

    /**
     * The authority's code for the answer it gave this cancellation, and {@code null} on every row that is not one
     * ({@code L2}(d), {@code V113}).
     *
     * <p>Read-only like the motive beside it: the value is stated by the factory that builds the row, and the pass that
     * settles a pending row is the reconciliation's ({@code T3}). What was missing was a <b>reader</b> — the code is
     * the authority's own vocabulary, which is what an operator looks up.</p>
     */
    public String getCancellationStatusUuid() {
        return cancellationStatusUuid;
    }

    /** The authority's sentence for that code, untranslated, or {@code null}. */
    public String getCancellationMessage() {
        return cancellationMessage;
    }

    /**
     * The document this row cancels, and {@code null} on every row that is not a cancellation ({@code V108}).
     *
     * <p><b>Why this getter exists.</b> The pointer is what a reconciliation reads the row <b>by</b>
     * ({@code FiscalDocumentRepository#findByCompanyIdAndCancelsFiscalDocumentId}), and the document it names is
     * where the fiscal UUID the authority is asked about lives: a cancellation row carries no {@code uuid_fiscal} of
     * its own ({@code L2}(c)), so the UUID reached through this column is the only one there is.</p>
     */
    public UUID getCancelsFiscalDocumentId() {
        return cancelsFiscalDocumentId;
    }

    /**
     * The row of the document that substitutes this one, or {@code null}; a row id and not a folio, because the
     * column is a composite foreign key ({@code V108}).
     *
     * <p><b>Why a reconciliation needs to read it.</b> Registering the request again means rebuilding the Anexo 20
     * document from the row, and {@code FolioSustitucion} is a <b>folio</b> while this column is a row id: the pass
     * resolves one to the other exactly as the submission did ({@code L20}(f)).</p>
     */
    public UUID getReplacesFiscalDocumentId() {
        return replacesFiscalDocumentId;
    }

    /**
     * When a pass decided this cancellation was finished, or {@code null} when nothing has settled it.
     *
     * <p>{@code null} means no pass has settled this row yet, which covers a submission the authority acknowledged and
     * has not decided ({@code 211}) and an answer a pass read but did not settle (the codes {@code L4} of this record
     * moves nothing on). A rejection is the case the column exists for: the row stays {@code CANCELLATION_PENDING} and
     * this timestamp is what says a pass decided it. An acceptance keeps the row {@code CANCELLED} <b>and</b> carries
     * the stamp of the pass that read it, so "settled" is one predicate on both halves instead of a state a reader has
     * to translate — what {@link #settleCancellationAccepted} writes and {@link #getCancellationSettledAt()} answers.</p>
     */
    public OffsetDateTime getCancellationSettledAt() {
        return cancellationSettledAt;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    /**
     * Settles a <b>submitted</b> cancellation the authority accepted: the row becomes {@code CANCELLED}.
     *
     * <p><b>Why this is one method and not three setters.</b> The transition is one fact — the state, the authority's
     * code and the moment a pass read it — and {@code ck_fiscal_document_cancellation_answer} couples all three
     * columns. A caller free to set them one at a time could write a row that says «finished» without saying how, or
     * a code over a state the guard never let move. The pointer, the motive and the substituting row are deliberately
     * untouched: the submission stated them and this row still cancels the same document. {@code uuid_fiscal} and
     * {@code stamped_at} stay {@code null}, which is the equality {@code ck_fiscal_document_uuid_matches_status} and
     * {@code ck_fiscal_document_stamped_at} require of a cancellation row ({@code L2}(c)).</p>
     *
     * <p><b>What actually enforces the direction.</b> Not this method: {@code trg_fiscal_document_guard} refuses
     * every target but {@code CANCELLED} — and staying pending — for a {@code CANCELLATION_PENDING} row, so the arm
     * {@code V113} added is the authority and this is the writer that uses it ({@code L2}(e)).</p>
     *
     * @param statusUuid the authority's code for the answer that accepted the cancellation
     * @param message the authority's sentence for it, untranslated, or {@code null} when it stated none
     * @param settledAt when the pass read that answer — the moment a second pass reads to skip this row
     * @return this row, so the caller writes what it settled
     */
    public FiscalDocument settleCancellationAccepted(String statusUuid, String message, OffsetDateTime settledAt) {
        return settle(STATUS_CANCELLED, statusUuid, message, settledAt);
    }

    /**
     * Settles a <b>submitted</b> cancellation the authority rejected: the row stays {@code CANCELLATION_PENDING} and
     * is finished by the timestamp.
     *
     * <p><b>Why a rejection keeps the state.</b> The authority refused to cancel the document, so the document is
     * still stamped and the row that remembers the attempt is not a cancellation: promoting it would state a fact the
     * authority denied ({@code S5}, {@code L2}(d)). What finishes it is {@code cancellation_settled_at}, which is
     * also what lets a second pass skip it without asking the authority again ({@code S6}).</p>
     *
     * @param statusUuid the authority's code — {@code 213} or {@code 204} on this surface
     * @param message the authority's sentence for it, untranslated
     * @param settledAt when the pass read that answer — the moment a second pass reads to skip this row
     * @return this row, so the caller writes what it settled
     */
    public FiscalDocument settleCancellationRejected(String statusUuid, String message, OffsetDateTime settledAt) {
        return settle(STATUS_CANCELLATION_PENDING, statusUuid, message, settledAt);
    }

    /**
     * The three answer columns of a cancellation row, written together because they are one fact ({@code L2}(d)).
     *
     * <p>The state is the caller's, so the two public halves state their own target and share everything else: one of
     * them moves the row and the other does not, and the three fields are identical either way.</p>
     */
    private FiscalDocument settle(String status, String statusUuid, String message, OffsetDateTime settledAt) {
        this.status = status;
        this.cancellationStatusUuid = Objects.requireNonNull(
                statusUuid,
                "a settled cancellation states the authority's code: a timestamp with no verdict says something "
                        + "finished without saying how (ck_fiscal_document_cancellation_answer)");
        this.cancellationMessage = message;
        this.cancellationSettledAt =
                Objects.requireNonNull(settledAt, "a settled cancellation states the moment a pass read the answer");
        return this;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * The authority's UUID, written by the transition out of {@code DRAFT} and by nothing else.
     *
     * <p><b>Why the mapping is writable even though the columns look read-only.</b> Neither {@code uuid_fiscal}
     * nor {@code stamped_at} carries {@code insertable = false, updatable = false} — what was missing was the
     * setter, because no code of the earlier slices could transition a document. The stamp is that writer, and
     * the mapping only has to let one {@code UPDATE} through; what keeps a stamped row from being rewritten is
     * not this class but {@code V108}: {@code DELETE} is revoked and {@code trg_fiscal_document_guard} raises on
     * any {@code UPDATE} of a row that is already {@code STAMPED} or {@code CANCELLED}, so the privilege opens
     * the transition and nothing else.</p>
     */
    public void setUuidFiscal(UUID uuidFiscal) {
        this.uuidFiscal = uuidFiscal;
    }

    /** When the authority stamped it: written once, by the same transition, and never re-derived. */
    public void setStampedAt(OffsetDateTime stampedAt) {
        this.stampedAt = stampedAt;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }

    public void setAttemptKey(String attemptKey) {
        this.attemptKey = attemptKey;
    }

    public void setReceptorRfc(String receptorRfc) {
        this.receptorRfc = receptorRfc;
    }

    public void setReceptorName(String receptorName) {
        this.receptorName = receptorName;
    }

    public void setReceptorRegimen(String receptorRegimen) {
        this.receptorRegimen = receptorRegimen;
    }

    public void setReceptorZipCode(String receptorZipCode) {
        this.receptorZipCode = receptorZipCode;
    }

    public void setReceptorUsoCfdi(String receptorUsoCfdi) {
        this.receptorUsoCfdi = receptorUsoCfdi;
    }

    public void setEmisorRfc(String emisorRfc) {
        this.emisorRfc = emisorRfc;
    }

    public void setEmisorName(String emisorName) {
        this.emisorName = emisorName;
    }

    public void setEmisorRegimen(String emisorRegimen) {
        this.emisorRegimen = emisorRegimen;
    }

    public void setLugarExpedicion(String lugarExpedicion) {
        this.lugarExpedicion = lugarExpedicion;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public void setDiscountTotal(BigDecimal discountTotal) {
        this.discountTotal = discountTotal;
    }

    public void setTaxTransferred(BigDecimal taxTransferred) {
        this.taxTransferred = taxTransferred;
    }

    public void setTaxWithheld(BigDecimal taxWithheld) {
        this.taxWithheld = taxWithheld;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
}
