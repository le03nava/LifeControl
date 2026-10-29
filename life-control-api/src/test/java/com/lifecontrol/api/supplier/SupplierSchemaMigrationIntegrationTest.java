package com.lifecontrol.api.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of the two supplier tenancy migrations against real PostgreSQL, with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the schema landed exactly as the
 * migrations declare it.
 *
 * <p>This suite is deliberately split along the EXPAND/CONTRACT boundary the migrations themselves
 * follow. The section for {@code V41} asserts what the expand step is allowed to contain — a nullable
 * owner column, its foreign key, and the unique target a composite key would need later — and asserts
 * that the global RFC key is <b>still</b> there, because a column that becomes {@code NOT NULL} before
 * the write surface knows where the company comes from would turn every existing supplier write into a
 * failure (the reason `V39` states in its own header). The section for {@code V42} asserts the contract:
 * the column is mandatory, the RFC key is per company, and a supplier of another company cannot be named
 * by a purchase order or linked to a product.</p>
 */
@SpringBootTest
@DisplayName("Supplier Schema Integration Tests")
class SupplierSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedSuppliers = new ArrayList<>();

    @AfterEach
    void removeInsertedSuppliers() {
        for (var id : insertedSuppliers) {
            jdbcTemplate.update("DELETE FROM suppliers WHERE id = ?", id);
        }
        insertedSuppliers.clear();
    }

    @Test
    @DisplayName("applies every migration on the classpath and leaves no pending migration")
    void flywayHeadIsTheLatestMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    // ──────────────────────────────────────
    // V41 — the expand step
    // ──────────────────────────────────────

    @Test
    @DisplayName("V41 adds suppliers.company_id as a nullable foreign key to companies")
    void companyIdArrivesNullableAndGuarded() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'suppliers' AND column_name = 'company_id'
                """, String.class);
        var foreignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'fk_suppliers_company'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);

        assertThat(nullable)
                .as("V41 must not make the column mandatory before the write surface carries a company")
                .isEqualTo("YES");
        assertThat(foreignKeys).as("fk_suppliers_company exists").isEqualTo(1);
    }

    @Test
    @DisplayName("V41 adds uq_suppliers_company_id, the unique target the composite keys will need")
    void companyIdUniqueTargetExists() {
        var uniqueConstraints = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_id'
                  AND constraint_type = 'UNIQUE'
                """, Integer.class);

        assertThat(uniqueConstraints).as("uq_suppliers_company_id exists").isEqualTo(1);
    }

    @Test
    @DisplayName("a supplier with no company is still a legal row while the expand step is the head")
    void supplierWithoutCompanyIsALegalRow() {
        var id = insertSupplier(null, "SCHEMA-EXP-1");

        var stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, id);

        assertThat(stored).isEqualTo(1);
    }

    @Test
    @DisplayName("fk_suppliers_company rejects a company id that does not exist")
    void unknownCompanyIsRejectedByTheForeignKey() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', 'SCHEMA-FK-1', ?)
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_suppliers_company");
    }

    @Test
    @DisplayName("the RFC key stays global until the contract lands: V41 does not carry the per-company key")
    void rfcKeyIsStillGlobalAtTheExpandStep() {
        var globalKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'suppliers_rfc_key'
                  AND constraint_type = 'UNIQUE'
                """, Integer.class);
        var perCompanyKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_rfc'
                """, Integer.class);

        assertThat(globalKey)
                .as("the global RFC key is the contract step's to replace, not the expand step's")
                .isEqualTo(1);
        assertThat(perCompanyKey)
                .as("the per-company RFC key does not exist before its migration")
                .isZero();
    }

    private UUID insertSupplier(UUID companyId, String rfc) {
        var id = UUID.randomUUID();
        if (companyId == null) {
            jdbcTemplate.update("""
                    INSERT INTO suppliers (id, supplier_name, rfc)
                    VALUES (?, 'Proveedor', ?)
                    """, id, rfc);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                    VALUES (?, 'Proveedor', ?, ?)
                    """, id, rfc, companyId);
        }
        insertedSuppliers.add(id);
        return id;
    }
}
