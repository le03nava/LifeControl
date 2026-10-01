package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V19__hr_org_structure.sql} against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the HR org-structure schema
 * landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself. The
 * five tables must exist after the migration, and the named CHECK and UNIQUE constraints must reject
 * an invalid write and leave the table untouched. In particular
 * {@code ck_positions_not_self_reporting} is the record's only database-level cycle guarantee (the
 * deeper cycles are a service concern), so its rejection is asserted here explicitly.</p>
 */
@SpringBootTest
@DisplayName("HR Org Structure Schema Integration Tests")
class HrSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "HR-SCHEMA-KEY";
    private static final String COMPANY_RFC = "HRSC010101ABC";

    private static final List<String> HR_TABLES =
            List.of("departments", "seniority_levels", "positions", "position_salary_bands", "position_roles");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @BeforeEach
    void resetHrTables() {
        // Leaf-first: position_salary_bands and position_roles reference positions, and positions
        // references departments. The container is shared per JVM, so each method starts from an
        // empty HR state to keep the row-count assertions deterministic.
        jdbcTemplate.update("DELETE FROM position_salary_bands");
        jdbcTemplate.update("DELETE FROM position_roles");
        jdbcTemplate.update("DELETE FROM positions");
        jdbcTemplate.update("DELETE FROM departments");
        jdbcTemplate.update("DELETE FROM seniority_levels");
    }

    @Test
    @DisplayName("applies every migration through V19 and leaves no pending migration")
    void flywayHeadIsV19() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("creates the five HR org-structure tables")
    void allFiveTablesExist() {
        for (var table : HR_TABLES) {
            var count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class,
                    table);
            assertThat(count).as("table %s exists", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ck_seniority_levels_rank rejects rank 0 and leaves seniority_levels untouched")
    void rankZeroIsRejectedByCheckConstraint() {
        assertViolatesConstraint("ck_seniority_levels_rank", """
                INSERT INTO seniority_levels (id, level_code, level_name, rank)
                VALUES (?, 'L0', 'Invalid', 0)
                """, UUID.randomUUID());

        assertThat(count("seniority_levels")).isZero();
    }

    @Test
    @DisplayName("ck_position_salary_bands_range rejects maximum below minimum and leaves the table untouched")
    void invertedSalaryRangeIsRejectedByCheckConstraint() {
        var departmentId = insertDepartment("OPS", "Operaciones");
        var positionId = insertPosition(departmentId, "OP1", "Operador");
        var seniorityId = insertSeniorityLevel("JUN", "Junior", 1);

        assertViolatesConstraint("ck_position_salary_bands_range", """
                INSERT INTO position_salary_bands
                    (id, position_id, seniority_level_id, minimum_salary, maximum_salary)
                VALUES (?, ?, ?, 1000.00, 500.00)
                """, UUID.randomUUID(), positionId, seniorityId);

        assertThat(count("position_salary_bands")).isZero();
    }

    @Test
    @DisplayName("ck_position_salary_bands_non_negative rejects a negative minimum and leaves the table untouched")
    void negativeMinimumSalaryIsRejectedByCheckConstraint() {
        var departmentId = insertDepartment("OPS", "Operaciones");
        var positionId = insertPosition(departmentId, "OP1", "Operador");
        var seniorityId = insertSeniorityLevel("JUN", "Junior", 1);

        assertViolatesConstraint(
                "ck_position_salary_bands_non_negative", """
                INSERT INTO position_salary_bands
                    (id, position_id, seniority_level_id, minimum_salary, maximum_salary)
                VALUES (?, ?, ?, -1.00, 100.00)
                """, UUID.randomUUID(), positionId, seniorityId);

        assertThat(count("position_salary_bands")).isZero();
    }

    @Test
    @DisplayName("ck_positions_not_self_reporting rejects a position that reports to itself")
    void selfReportingPositionIsRejectedByCheckConstraint() {
        var departmentId = insertDepartment("OPS", "Operaciones");
        var positionId = UUID.randomUUID();

        assertViolatesConstraint("ck_positions_not_self_reporting", """
                INSERT INTO positions
                    (id, department_id, position_code, position_name, reports_to_position_id)
                VALUES (?, ?, 'SELF', 'Self Reporting', ?)
                """, positionId, departmentId, positionId);

        assertThat(count("positions")).isZero();
    }

    @Test
    @DisplayName("uq_departments_company_code rejects a duplicate department_code for one company")
    void duplicateDepartmentCodeIsRejectedByUniqueKey() {
        var companyId = hrCompanyId();
        insertDepartment(companyId, "OPS", "Operaciones");

        assertViolatesConstraint("uq_departments_company_code", """
                INSERT INTO departments (id, company_id, department_code, department_name)
                VALUES (?, ?, 'OPS', 'Operaciones Duplicadas')
                """, UUID.randomUUID(), companyId);

        assertThat(count("departments")).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_positions_department_code rejects a duplicate position_code within one department")
    void duplicatePositionCodeIsRejectedByUniqueKey() {
        var departmentId = insertDepartment("OPS", "Operaciones");
        insertPosition(departmentId, "OP1", "Operador");

        assertViolatesConstraint("uq_positions_department_code", """
                INSERT INTO positions (id, department_id, position_code, position_name)
                VALUES (?, ?, 'OP1', 'Operador Duplicado')
                """, UUID.randomUUID(), departmentId);

        assertThat(count("positions")).isEqualTo(1);
    }

    /**
     * Find-or-create the company row the company-scoped keys hang off, mirroring the idempotent
     * fixture style of {@code StoreAreaIntegrationTest}. Reuses the persisted entity path instead of
     * hand-writing an insert into {@code companies}.
     */
    private UUID hrCompanyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("HR Schema Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private UUID insertDepartment(String code, String name) {
        return insertDepartment(hrCompanyId(), code, name);
    }

    private UUID insertDepartment(UUID companyId, String code, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO departments (id, company_id, department_code, department_name)
                VALUES (?, ?, ?, ?)
                """, id, companyId, code, name);
        return id;
    }

    private UUID insertPosition(UUID departmentId, String code, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO positions (id, department_id, position_code, position_name)
                VALUES (?, ?, ?, ?)
                """, id, departmentId, code, name);
        return id;
    }

    private UUID insertSeniorityLevel(String code, String name, int rank) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO seniority_levels (id, level_code, level_name, rank)
                VALUES (?, ?, ?, ?)
                """, id, code, name, rank);
        return id;
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint. The most specific cause carries the {@code PSQLException} message.
     */
    private void assertViolatesConstraint(String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }
}
