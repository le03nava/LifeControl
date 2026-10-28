package com.lifecontrol.api.product.attribute;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V37 and V38 must be safe on a database that ALREADY holds products, variants and categories.
 *
 * <p>Every other schema suite migrates an empty database, which is the one case where an additive
 * migration cannot hurt anything. This one builds a second database at V36, fills it the way production
 * is actually filled — a company, a seeded branch, a product carrying the old free-form attributes, a
 * variant without them — and then migrates it to <b>V38</b>. It is the only place the three claims that
 * matter here are measured together: the existing rows survive, the new variant column arrives empty
 * rather than with a default, and replaying the seed script is a no-op.</p>
 *
 * <p>The target is V38 because that is the last migration whose premise this test describes, and the
 * two versions are pinned instead of derived: V39 (the catalog's company column) is still purely
 * additive, but V40 discards every row that cannot be attributed to a company — a separate, deliberate
 * decision of the product-catalog unit (its {@code TRUNCATE} is the contract that makes {@code NOT NULL}
 * possible, not an attribute upgrade). Measuring to head would therefore assert that rows survive a
 * migration that exists to remove them, and the assertion would have to be deleted to stay green. The
 * target is written here and cannot drift: a migration appended later does not enter this range.</p>
 */
@DisplayName("Product attribute upgrade on a populated database")
class ProductAttributeUpgradeOnPopulatedDbIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    @DisplayName("V37/V38 upgrade a populated V36 database, keep its rows and replay the seed as a no-op")
    void upgradeFromPopulatedV36() throws Exception {
        var adminUrl = POSTGRES.getJdbcUrl();
        var user = POSTGRES.getUsername();
        var password = POSTGRES.getPassword();
        var adminJdbc = new JdbcTemplate(new DriverManagerDataSource(adminUrl, user, password));
        adminJdbc.execute("DROP DATABASE IF EXISTS probe_nonempty");
        adminJdbc.execute("CREATE DATABASE probe_nonempty");
        var url = adminUrl.replaceAll("/[^/]+$", "/probe_nonempty");

        var toV36 = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("36"))
                .load();
        assertThat(toV36.migrate().migrationsExecuted).isEqualTo(36);

        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        var companyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, rfc, enabled) VALUES (?,?,?,?,true)",
                companyId,
                "PROBE-NONEMPTY",
                "Probe non-empty co",
                "PNEM010101AB1");
        var ferreteria = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_categories (id, company_id, parent_category_id, category_name, display_order, enabled)"
                        + " VALUES (?,?,NULL,'Ferretería',0,true)",
                ferreteria,
                companyId);
        var child = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_categories (id, company_id, parent_category_id, category_name, display_order, enabled)"
                        + " VALUES (?,?,?,'Tubos y Conexiones',0,true)",
                child,
                companyId,
                ferreteria);
        var productId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, sku, name, category_id, attributes, enabled) VALUES (?,?,?,?,?::jsonb,true)",
                productId,
                "PROBE-LEGACY-1",
                "Legacy product",
                child,
                "{\"Material Externo\": \"Piel Vacuno\"}");
        var variantId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, enabled) VALUES (?,?,?,?,true)",
                variantId,
                productId,
                "PROBE-LEGACY-BC-1",
                "26");

        var throughV38 = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("38"))
                .load();
        // Exactly the two versions this test is about, pinned where the test can see them: V39 is
        // additive and V40 discards rows (see the class javadoc), so the range stops here on purpose.
        assertThat(throughV38.migrate().migrations)
                .extracting(migration -> migration.version)
                .containsExactly("37", "38");

        // The pre-existing variant gains the column empty — not with a default, which would silently
        // claim a colour nobody entered.
        assertThat(jdbc.queryForObject("SELECT attributes FROM product_variants WHERE id = ?", Object.class, variantId))
                .isNull();
        // The old free-form key is left exactly as it was: a migration does not rewrite product data.
        assertThat(jdbc.queryForObject(
                        "SELECT attributes->>'Material Externo' FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("Piel Vacuno");
        assertThat(jdbc.queryForList("SELECT root_name FROM product_attribute_profile_roots"))
                .hasSize(11);

        var before = catalogCounts(jdbc);
        try (var connection = DataSourceUtils.getConnection(new DriverManagerDataSource(url, user, password))) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V38__product_attribute_starter_catalog.sql"));
        }
        assertThat(catalogCounts(jdbc))
                .as("replaying the seed must not add a row")
                .isEqualTo(before);
    }

    private String catalogCounts(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM product_attribute_profiles) || '/'"
                        + " || (SELECT COUNT(*) FROM product_attribute_definitions) || '/'"
                        + " || (SELECT COUNT(*) FROM product_attribute_values) || '/'"
                        + " || (SELECT COUNT(*) FROM product_attribute_profile_roots)",
                String.class);
    }
}
