package com.lifecontrol.api.fiscal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * <p><b>What is mapped writable and what is not.</b> The writer of this slice
 * ({@code FiscalDocumentSnapshotWriter}) states the identity, the two fiscal faces, the place of issue, the
 * money and the attempt key. Everything that belongs to the <b>later</b> slices is mapped read-only, and that
 * is a statement rather than a shortcut: {@code status} is a {@code String} because the enum arrives with
 * A3's builder (L9(i)); {@code tipo_comprobante}, {@code metodo_pago} and {@code exportacion} carry the
 * schema's defaults until a builder states them (S12, T9); the authority's UUID and stamp instant are written
 * by the transition out of {@code DRAFT}, which does not exist yet (L9(b)).</p>
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
