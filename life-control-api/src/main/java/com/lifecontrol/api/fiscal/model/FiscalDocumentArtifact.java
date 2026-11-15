package com.lifecontrol.api.fiscal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The bytes that were stamped: one row per fiscal document, keeping the XML byte for byte ({@code S10} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, ADR-0007 decision 3, invariant F8,
 * {@code V108__fiscal_document.sql}).
 *
 * <p><b>Why this slice maps it and does not write it.</b> A reprint reads the stored XML and never regenerates
 * it — a regenerated document cannot be proven to be the original — so the row is written by the stamp
 * ({@code T6}'s second pull request) and read by the reprint path ({@code T8}). It is mapped here because the
 * three tables of the document are one schema and a reader that had to invent a second mapping for the third
 * would be the first place the two could disagree.</p>
 *
 * <p><b>Why the primary key is the document.</b> Two artefacts for one document would be two candidate
 * originals and no way to say which one the authority witnessed (L9(d)). A replacement document is its own
 * row, and gets its own artefact.</p>
 *
 * <p><b>Why there is no setter.</b> {@code V108} revokes {@code UPDATE} from the pool and a trigger refuses it
 * for the owner as well: the XML a reprint returns has to be the bytes the authority witnessed, so the row is
 * insert-once by construction. The entity is deliberately read-only here; the writer of the stamp states the
 * fields through its own insert.</p>
 */
@Entity
@Table(name = "fiscal_document_artifact")
public class FiscalDocumentArtifact {

    /** The document, and the identity of the artefact: one per document, deliberately. */
    @Id
    @Column(name = "fiscal_document_id", nullable = false)
    private UUID fiscalDocumentId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** The stamped XML, kept byte for byte. {@code TEXT}, because a CFDI is not a short string. */
    @Column(name = "xml", nullable = false, columnDefinition = "TEXT")
    private String xml;

    // `pdf` (BYTEA) stays unmapped, and it is the one column of the three tables that does. The authority holds
    // the XML and the PDF is a rendering of it that a reprint may or may not need; nothing in this slice or the
    // next reads it, and a `byte[]` over `BYTEA` is a mapping this tree verifies nowhere. The column stays in
    // the schema and the slice that renders a PDF is where its mapping is decided — the same reason
    // `SalesOrderItem` leaves `attributes_snapshot` unmapped rather than mapping a payload nothing owns.

    /** The schema's {@code now()}, read-only: the row records when it was kept, not what it claims. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Default constructor for JPA. */
    protected FiscalDocumentArtifact() {}

    /**
     * The artefact one stamp keeps: the document, its tenant and the bytes the authority returned.
     *
     * <p><b>Why a constructor and not setters.</b> This row is inserted once and never touched again
     * ({@code V108} revokes {@code UPDATE} and a trigger refuses it for the owner as well), so the three values
     * are stated together at the one moment the row exists. A setter would be a second write this table refuses
     * at runtime and that a later reader would find in the entity's surface and try anyway.</p>
     *
     * <p>{@code created_at} is not a parameter: the schema's {@code now()} is when the artefact was kept, and an
     * application clock would be a second authority for a fact the row already records.</p>
     *
     * @param fiscalDocumentId the document this artefact belongs to, which is also its primary key
     * @param companyId the tenant
     * @param xml the bytes the provider returned, kept byte for byte ({@code S10})
     */
    public FiscalDocumentArtifact(UUID fiscalDocumentId, UUID companyId, String xml) {
        this.fiscalDocumentId = fiscalDocumentId;
        this.companyId = companyId;
        this.xml = xml;
    }

    public UUID getFiscalDocumentId() {
        return fiscalDocumentId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getXml() {
        return xml;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
