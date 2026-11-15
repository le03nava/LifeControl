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
 * through {@link #cancellationOf(FiscalDocument, FiscalCancellationMotive, java.util.UUID, String)} and never
 * touches the stamped one ({@code S7}). {@code status} is a {@code String} and not an enum because the
 * vocabulary lives in {@code ck_fiscal_document_status} ({@code L9}(i)).</p>
 *
 * <p><b>What the database refuses, and therefore what this mapping does not have to.</b> {@code V108} revokes
 * {@code DELETE} and guards {@code UPDATE} with a trigger, so a {@code STAMPED} or {@code CANCELLED} row
 * refuses every write and no setter here can rewrite history: the one legitimate update is the transition out
 * of {@code DRAFT}. The identity columns ({@code company_id}, {@code source_type}, {@code source_id}) are
 * refused on any row, which is why moving a document to another sale is not a setter anybody owns.</p>
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
     * @return a new row: {@code CANCELLED}, naming its source, with the source's frozen facts and no lines
     */
    public static FiscalDocument cancellationOf(
            FiscalDocument cancelled, FiscalCancellationMotive motive, UUID replacesFiscalDocumentId, String issuedBy) {
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
        cancellation.setStatus(STATUS_CANCELLED);
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

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
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
