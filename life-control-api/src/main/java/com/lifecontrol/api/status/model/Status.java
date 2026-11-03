package com.lifecontrol.api.status.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "statuses", uniqueConstraints = @UniqueConstraint(columnNames = {"status_type_id", "status_name"}))
public class Status extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "status_name", length = 100, nullable = false)
    private String statusName;

    /**
     * The domain constant this row names — {@code DRAFT}, {@code IN_TRANSIT}, … — for the five families
     * ADR-0004 types ({@code V66} step 4, decision 7).
     *
     * <p><b>Nullable, and read by the domain, never by a human.</b> {@code statuses} is one catalog for seven
     * families and ADR-0004 puts two of them out of scope by decision, so nine rows carry no code and no
     * column-level {@code NOT NULL} can be true here; the migration asserts the property for the five
     * in-scope families instead. The label stays what a screen renders, and this column is what a state
     * machine reads, which is why renaming a label stopped being a behavior change.</p>
     *
     * <p><b>There is deliberately no setter.</b> A code is the enum constant a family's migration maps its
     * rows to ({@code V66} step 4); nothing in the API creates or edits one, so {@code StatusService} can
     * persist a row without a code and cannot mint one by accident. A row created at runtime therefore has
     * no code, and every reader that needs a state refuses it explicitly instead of falling back to a
     * label — the fail-closed shape {@code SalesOrderStatusCatalog} states.</p>
     */
    @Column(name = "code", length = 24)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_type_id", nullable = false)
    private StatusType statusType;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Status() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getStatusName() {
        return statusName;
    }

    public String getCode() {
        return code;
    }

    public StatusType getStatusType() {
        return statusType;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }

    public void setStatusType(StatusType statusType) {
        this.statusType = statusType;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Status status = new Status();

        public Builder id(UUID id) {
            status.id = id;
            return this;
        }

        public Builder statusName(String statusName) {
            status.statusName = statusName;
            return this;
        }

        public Builder code(String code) {
            status.code = code;
            return this;
        }

        public Builder statusType(StatusType statusType) {
            status.statusType = statusType;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            status.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            status.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            status.setUpdatedAt(updatedAt);
            return this;
        }

        public Status build() {
            return status;
        }
    }
}
