package com.lifecontrol.api.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>The suite asserts the <b>CONTRACT</b> the two migrations land: {@code V41} gave {@code suppliers} a
 * nullable owner column and stopped there, because a {@code NOT NULL} column cannot arrive before the
 * write surface knows where the company comes from (the reason {@code V39} states in its own header);
 * {@code V42} is what turns that into a database rule — the column is mandatory, V1's global RFC key is
 * replaced by the per-company one, and the rows neither rule can describe are gone.</p>
 *
 * <p>The per-company RFC case is the one that could not be proved while the expand step was the head: the
 * service refused a duplicate RFC <b>inside</b> the company, but the global key refused a second
 * company's identical RFC at the same time, so inside one transaction the two refusals were
 * indistinguishable. With V42 the database accepts the cross-company pair and refuses the same-company
 * pair by name, which is the rule the catalog actually states.</p>
 */
@SpringBootTest
@DisplayName("Supplier Schema Integration Tests")
class SupplierSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_A_KEY = "SUPPLIER-SCHEMA-A";
    private static final String COMPANY_B_KEY = "SUPPLIER-SCHEMA-B";
    private static final String COMPANY_A_RFC = "SSCA010101AB1";
    private static final String COMPANY_B_RFC = "SSCB010101AB2";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyAId;
    private UUID companyBId;

    private final List<UUID> insertedSuppliers = new ArrayList<>();
    private final List<UUID> insertedCompanies = new ArrayList<>();

    @BeforeEach
    void insertCompanies() {
        companyAId = insertCompany(COMPANY_A_KEY, "Supplier Schema Company A", COMPANY_A_RFC);
        companyBId = insertCompany(COMPANY_B_KEY, "Supplier Schema Company B", COMPANY_B_RFC);
    }

    @AfterEach
    void removeInsertedRows() {
        // Suppliers reference companies, so the child goes first.
        for (var id : insertedSuppliers) {
            jdbcTemplate.update("DELETE FROM suppliers WHERE id = ?", id);
        }
        insertedSuppliers.clear();
        for (var id : insertedCompanies) {
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id);
        }
        insertedCompanies.clear();
    }

    @Test
    @DisplayName("applies every migration on the classpath and leaves no pending migration")
    void flywayHeadIsTheLatestMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    // ──────────────────────────────────────
    // V42 — the contract
    // ──────────────────────────────────────

    @Test
    @DisplayName("V42 makes suppliers.company_id mandatory")
    void companyIdIsMandatory() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'suppliers' AND column_name = 'company_id'
                """, String.class);

        assertThat(nullable)
                .as("the owner is a fact of the row, not an optional annotation")
                .isEqualTo("NO");
    }

    @Test
    @DisplayName("V42 replaces V1's global RFC key with the per-company one")
    void globalRfcKeyIsGone() {
        var globalKey = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'suppliers_rfc_key'
                """, Integer.class);

        assertThat(globalKey)
                .as("a globally unique RFC is what stopped two businesses from both registering one")
                .isZero();
    }

    @Test
    @DisplayName("uq_suppliers_company_rfc is UNIQUE over exactly (company_id, rfc)")
    void perCompanyRfcKeyCoversCompanyAndRfc() {
        var kind = jdbcTemplate.queryForObject("""
                SELECT constraint_type FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_rfc'
                """, String.class);
        var columns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                 AND tc.table_name = kcu.table_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'suppliers'
                  AND tc.constraint_name = 'uq_suppliers_company_rfc'
                ORDER BY kcu.ordinal_position
                """, String.class);

        assertThat(kind).as("uq_suppliers_company_rfc is a UNIQUE constraint").isEqualTo("UNIQUE");
        assertThat(columns)
                .as("the key covers the company first and the RFC inside it, in that order")
                .containsExactly("company_id", "rfc");
    }

    @Test
    @DisplayName("a supplier with no company is refused and leaves nothing stored")
    void supplierWithoutCompanyIsRefused() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc)
                VALUES (?, 'Proveedor', 'SCHEMA-NOC-1')
                """, id)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, id))
                .as("the refused insert stored nothing")
                .isZero();
    }

    @Test
    @DisplayName("the same RFC in two companies is accepted: the key is per company, not global")
    void sameRfcInDifferentCompaniesIsAccepted() {
        var rfc = "SCHEMA-SHARED";
        var inCompanyA = insertSupplier(companyAId, rfc);
        var inCompanyB = insertSupplier(companyBId, rfc);

        var stored = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM suppliers WHERE id IN (?, ?)
                """, Integer.class, inCompanyA, inCompanyB);

        assertThat(stored).as("both businesses registered the same RFC").isEqualTo(2);
    }

    @Test
    @DisplayName("the same RFC twice inside one company is refused by uq_suppliers_company_rfc")
    void sameRfcTwiceInOneCompanyIsRefused() {
        var rfc = "SCHEMA-DUP-1";
        var accepted = insertSupplier(companyAId, rfc);
        var refusedId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', ?, ?)
                """, refusedId, rfc, companyAId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_suppliers_company_rfc");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, refusedId))
                .as("the refused row stored nothing")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id = ?", Integer.class, accepted))
                .as("the accepted row is the only one holding this RFC in this company")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("fk_suppliers_company survives the contract and rejects a company that does not exist")
    void unknownCompanyIsRejectedByTheForeignKey() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', 'SCHEMA-FK-1', ?)
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_suppliers_company");
    }

    @Test
    @DisplayName("uq_suppliers_company_id survives the contract: it is the next migration's FK target")
    void companyIdUniqueTargetSurvivesTheContract() {
        var uniqueConstraints = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'suppliers'
                  AND constraint_name = 'uq_suppliers_company_id'
                  AND constraint_type = 'UNIQUE'
                """, Integer.class);

        assertThat(uniqueConstraints).as("uq_suppliers_company_id exists").isEqualTo(1);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO companies (id, company_key, company_name, rfc, enabled)
                VALUES (?, ?, ?, ?, true)
                """, id, key, name, rfc);
        insertedCompanies.add(id);
        return id;
    }

    private UUID insertSupplier(UUID companyId, String rfc) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO suppliers (id, supplier_name, rfc, company_id)
                VALUES (?, 'Proveedor', ?, ?)
                """, id, rfc, companyId);
        insertedSuppliers.add(id);
        return id;
    }
}
