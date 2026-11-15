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

/**
 * One concept of a fiscal document: a sale line as it stood when the document was written, and never a view of
 * the sale line as it stands now ({@code S9} of {@code odd/tasks/cfdi-fiscal-document.md}, ADR-0007 decision 4,
 * {@code V109__fiscal_document_snapshot.sql}).
 *
 * <p><b>Why every fact is a column and not a join.</b> «Clave, unit, description, quantity, unit value,
 * discount, tax object, base, tax, factor and rate are copied onto {@code fiscal_document_line} at stamp time
 * … Nothing is read from the live catalogs when a document is re-issued, reprinted or cancelled, for the reason
 * {@code V81} froze the rate on the line: a catalog is today's answer to a question asked last year.» A
 * re-print that joined {@code products} would restate a fiscal document the day somebody edits a name.</p>
 *
 * <p><b>Why {@code sales_order_item_id} is required.</b> {@code V109}'s comparison holds every line to the sale
 * line it names — quantity, unit value, discount, base and tax — so a line that names none is a line nobody
 * could compare. It is also what refuses a line of another sale at commit.</p>
 *
 * <p><b>Why the entity is insert-only in practice.</b> {@code V108} revokes {@code UPDATE} and {@code DELETE}
 * from the pool and a trigger refuses both for the owner, so no setter here can amend a written line; the
 * setters exist because one writer builds the row and nothing else does.</p>
 */
@Entity
@Table(name = "fiscal_document_line")
public class FiscalDocumentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "fiscal_document_id", nullable = false)
    private UUID fiscalDocumentId;

    /** The sale line this concept states, required: {@code V109}'s comparison is made through it. */
    @Column(name = "sales_order_item_id", nullable = false)
    private UUID salesOrderItemId;

    /** The product's fiscal key, {@code products.sat_code} as it stood at stamp (S4, {@code V104}). */
    @Column(name = "clave_prod_serv", length = 20, nullable = false)
    private String claveProdServ;

    /** The unit's key, {@code measure_units.sat_code} as it stood at stamp. */
    @Column(name = "clave_unidad", length = 5, nullable = false)
    private String claveUnidad;

    /**
     * What the line states the customer bought, rendered once from the sale line's own name snapshots.
     *
     * <p>{@code V81} backfilled those snapshots and <b>nothing has written them since</b> — a sale written today
     * carries {@code null} in both, which is a line with no snapshot and not a line with a wrong one. So the
     * description is composed from the snapshots when they exist and from the catalogs when they do not: a
     * document cannot state an empty description, and the alternative would be to refuse every sale made after
     * {@code V81}. {@code FiscalDocumentSnapshotWriter} states the rule where it renders it.</p>
     */
    @Column(name = "description", length = 1000, nullable = false)
    private String description;

    /** Six decimals, because the authority accepts fractions and the sale line carries four. */
    @Column(name = "quantity", precision = 19, scale = 6, nullable = false)
    private BigDecimal quantity;

    /** The unit value before discount: the sale line's {@code list_price} at stamp. */
    @Column(name = "unit_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal unitValue;

    @Column(name = "discount", precision = 19, scale = 4, nullable = false)
    private BigDecimal discount;

    /** The authority's {@code c_ObjetoImp}: {@code 01} not subject, {@code 02} subject and broken out. */
    @Column(name = "tax_object", length = 2, nullable = false)
    private String taxObject;

    /** The base the tax is stated on: the sale line's derived {@code subtotal}. */
    @Column(name = "base", precision = 19, scale = 4, nullable = false)
    private BigDecimal base;

    /** The tree's own code ({@code NA}, {@code IVA}, {@code TASA_0}, {@code EXENTO}), twenty wide (L9(e)). */
    @Column(name = "tax_code", length = 20, nullable = false)
    private String taxCode;

    /** {@code Tasa}, {@code Cuota} or {@code Exento}: the authority's factor, frozen from {@code V103}, and null exactly when the concept is not subject to the tax ({@code V110}). */
    @Column(name = "tipo_factor", length = 10, nullable = true)
    private String tipoFactor;

    /** The rate or the quota; {@code null} on an exempt line, because a zero would be a different statement. */
    @Column(name = "tasa_o_cuota", precision = 19, scale = 6)
    private BigDecimal tasaOCuota;

    @Column(name = "tax_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxAmount;

    /** The schema's {@code now()}, read-only for the reason {@link FiscalDocument#getCreatedAt()} states. */
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
    public FiscalDocumentLine() {}

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getFiscalDocumentId() {
        return fiscalDocumentId;
    }

    public UUID getSalesOrderItemId() {
        return salesOrderItemId;
    }

    public String getClaveProdServ() {
        return claveProdServ;
    }

    public String getClaveUnidad() {
        return claveUnidad;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitValue() {
        return unitValue;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public String getTaxObject() {
        return taxObject;
    }

    public BigDecimal getBase() {
        return base;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public String getTipoFactor() {
        return tipoFactor;
    }

    public BigDecimal getTasaOCuota() {
        return tasaOCuota;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public void setFiscalDocumentId(UUID fiscalDocumentId) {
        this.fiscalDocumentId = fiscalDocumentId;
    }

    public void setSalesOrderItemId(UUID salesOrderItemId) {
        this.salesOrderItemId = salesOrderItemId;
    }

    public void setClaveProdServ(String claveProdServ) {
        this.claveProdServ = claveProdServ;
    }

    public void setClaveUnidad(String claveUnidad) {
        this.claveUnidad = claveUnidad;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public void setUnitValue(BigDecimal unitValue) {
        this.unitValue = unitValue;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public void setTaxObject(String taxObject) {
        this.taxObject = taxObject;
    }

    public void setBase(BigDecimal base) {
        this.base = base;
    }

    public void setTaxCode(String taxCode) {
        this.taxCode = taxCode;
    }

    public void setTipoFactor(String tipoFactor) {
        this.tipoFactor = tipoFactor;
    }

    public void setTasaOCuota(BigDecimal tasaOCuota) {
        this.tasaOCuota = tasaOCuota;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }
}
