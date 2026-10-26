package com.lifecontrol.api.product.category.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes {@code V35__product_category_starter_catalog.sql} itself and reads what it wrote.
 *
 * <p>The rest of the suite cannot reach this half of the feature: Flyway runs V35 once, on a database
 * that has no companies yet, so the seed inserts nothing and every other test only ever exercises the
 * Java applier. That leaves the migration's actual data path — the temp table, the two joined inserts and
 * both idempotency guards — proven by nothing, which for a forward-only data migration is the half that
 * matters. This suite replays the script against a database where companies do exist.</p>
 *
 * <p>{@code @Transactional} is what keeps the replay harmless: the container is shared per JVM, the script
 * seeds <b>every</b> company in it, and rolling the test back leaves no row behind for another suite's
 * counts. Replaying a migration from a test is deliberate and narrow — the file is read from the classpath,
 * so the test pins the script that ships, not a copy of it.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Starter Product Category Migration Integration Tests")
class StarterProductCategoryMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V35__product_category_starter_catalog.sql";
    private static final String COMPANY_KEY = "STARTER-CATALOG-MIGRATION-KEY";
    private static final String COMPANY_RFC = "SCMG010101ABC";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Test
    @DisplayName("seeds the whole catalog for a company that exists when the migration runs")
    void seedsEveryExistingCompany() {
        var companyId = companyId();

        replayMigration();

        assertThat(countCategories(companyId))
                .as("39 enabled rows of the user's catalog plus 18 disabled proposals")
                .isEqualTo(57);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_categories WHERE company_id = ? AND enabled",
                        Integer.class,
                        companyId))
                .as("the proposals ship disabled, so they are not part of this count")
                .isEqualTo(39);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id IS NULL
                        """, Integer.class, companyId))
                .as("twelve enabled branches plus six proposals")
                .isEqualTo(18);

        var ferreteria = category(companyId, null, "Ferretería");
        assertThat(ferreteria.get("sat_code")).isEqualTo("27110000");
        assertThat(category(companyId, (UUID) ferreteria.get("id"), "Herramientas Manuales")
                        .get("sat_code"))
                .isEqualTo("27111600");
        assertThat(category(companyId, (UUID) ferreteria.get("id"), "Herramientas Eléctricas")
                        .get("sat_code"))
                .isEqualTo("27112700");

        var automotriz = category(companyId, null, "Automotriz");
        assertThat(automotriz.get("enabled"))
                .as("a proposal is created disabled")
                .isEqualTo(false);
        assertThat(automotriz.get("sat_code"))
                .as("and carries no invented fiscal key")
                .isNull();
    }

    @Test
    @DisplayName("replaying the migration inserts nothing: both guards are idempotent")
    void replayingTheMigrationIsANoOp() {
        // A successful second replay also proves the script cleans up after itself: a surviving
        // `starter_catalog` would abort the second `CREATE TEMPORARY TABLE`.
        var companyId = companyId();
        replayMigration();
        assertThat(countCategories(companyId)).isEqualTo(57);

        replayMigration();

        assertThat(countCategories(companyId))
                .as("a company that already has the catalog receives no duplicate")
                .isEqualTo(57);
    }

    @Test
    @DisplayName("a company that already has a root keeps it and receives only the missing children")
    void preExistingRootKeepsItsRowAndGainsItsChildren() {
        var companyId = companyId();
        var disabledRootId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name, enabled)
                VALUES (?, ?, 'ferretería', false)
                """, disabledRootId, companyId);

        replayMigration();

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id IS NULL AND lower(category_name) = 'ferretería'
                        """, Integer.class, companyId))
                .as("the pre-existing root is reused, not duplicated")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id = ?
                        """, Integer.class, companyId, disabledRootId))
                .as("and its two children hang from it")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id = ? AND enabled
                        """, Integer.class, companyId, disabledRootId))
                .as("a child of a disabled root is created disabled, never enabled under it")
                .isZero();
        assertThat(countCategories(companyId))
                .as("57 catalog rows, one of which is the root the company already had")
                .isEqualTo(57);
    }

    /**
     * Runs the shipped migration file on the test transaction's own connection, so the seed and the
     * rollback happen together. The connection belongs to the transaction, so it is released instead of
     * closed.
     */
    private void replayMigration() {
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION_RESOURCE));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * The suite's company, flushed before any raw JDBC runs: the script and the fixtures below read
     * {@code companies} through the same connection but not through the persistence context, and a
     * pending insert would make the migration's {@code JOIN companies} see no company at all.
     */
    private UUID companyId() {
        return companyRepository
                .findByCompanyKey(COMPANY_KEY)
                .orElseGet(() -> companyRepository.saveAndFlush(Company.builder()
                        .companyKey(COMPANY_KEY)
                        .companyName("Starter Catalog Migration Test Company")
                        .rfc(COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private int countCategories(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_categories WHERE company_id = ?", Integer.class, companyId);
    }

    /** One row as the database holds it, so an assertion cannot pass on a stale entity. */
    private java.util.Map<String, Object> category(UUID companyId, UUID parentId, String name) {
        var sql = parentId == null
                ? "SELECT * FROM product_categories WHERE company_id = ? AND parent_category_id IS NULL AND category_name = ?"
                : "SELECT * FROM product_categories WHERE company_id = ? AND parent_category_id = ? AND category_name = ?";
        return parentId == null
                ? jdbcTemplate.queryForMap(sql, companyId, name)
                : jdbcTemplate.queryForMap(sql, companyId, parentId, name);
    }
}
