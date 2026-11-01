package com.lifecontrol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The delivery half of the trap {@code application-test.properties} records: <b>a database created from
 * scratch is not empty when Flyway first runs</b>, and the baseline pair decides whether that costs a
 * migration or not.
 *
 * <p><b>Why a fresh delivery volume is pre-populated.</b>
 * {@code docker/postgres-init/lifecontrol-roles.sql} runs while the postgres image boots — before the API
 * exists — and it executes {@code CREATE EXTENSION IF NOT EXISTS btree_gist} (line 108, as
 * {@code lifecontrol_bootstrap}, which is why the extension can be created at all: {@code V21} finds it
 * already installed) and creates {@code task_employee_company(uuid)} (step 6). Both land in
 * {@code public}. Flyway reads a schema holding <b>any</b> object as a database that predates it, so with
 * {@code baseline-version=1} it records {@code V1__baseline_schema.sql} as applied <b>without running
 * it</b>, and the first migration that touches a V1 table dies with {@code relation "countries" does not
 * exist} — a symptom that names neither Flyway nor the baseline. Issue #722 is the measurement on a real
 * datadir: {@code flyway_schema_history} held exactly one row, {@code 1 | << Flyway Baseline >>}.
 *
 * <p><b>Why the properties are read instead of restated.</b> The value under test is the one delivery
 * ships, so the test loads {@code application.properties} from the classpath and hands those two keys to
 * Flyway. A test that wrote {@code baselineVersion("0")} would pin its own copy and stay green while
 * delivery drifted back — which is exactly how the defect survived: the suite was corrected, the delivered
 * pair that produced it was not.
 *
 * <p><b>Why a scratch database.</b> The suite's own container has already migrated to head, where the
 * baseline never triggers and every assertion below would be vacuously true. Each case creates and drops
 * its own database, like {@code StoreHierarchyCompanyBackfillMigrationIntegrationTest}, so a failed
 * assertion cannot leave a half-migrated database behind for the rest of the JVM.
 */
@DisplayName("Flyway delivery baseline for a database created from scratch")
class FlywayDeliveryBaselineIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASELINE_ON_MIGRATE = "spring.flyway.baseline-on-migrate";
    private static final String BASELINE_VERSION = "spring.flyway.baseline-version";

    private String databaseName;
    private String jdbcUrl;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_flyway_baseline_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = adminConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
        jdbcTemplate =
                new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
        // FORCE terminates a leftover session (PostgreSQL 13+), so a connection Flyway did not return
        // cannot turn this cleanup into a failure that hides the test's own result.
        try (var connection = adminConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (Exception e) {
            throw new IllegalStateException("could not drop scratch database " + databaseName, e);
        } finally {
            databaseName = null;
        }
    }

    @Test
    @DisplayName("a fresh volume applies V1 to head, and V1 is executed rather than recorded as a baseline")
    void freshDatabaseAppliesEveryMigrationFromTheFirstOne() {
        createInfrastructureObjectTheRolesScriptCreates();
        assertThat(flywayConsidersTheSchemaEmpty())
                .as("the premise of this case: the roles script's resolver makes `public` non-empty for"
                        + " Flyway, which is what triggers `baseline-on-migrate` in the first place")
                .isFalse();

        var flyway = deliveryFlyway();
        flyway.migrate();

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND type = 'SQL'
                        """, Integer.class))
                .as("V1 ran. A baseline row of type BASELINE instead is the defect: the table it creates"
                        + " would not exist, and V2 would have nothing to seed")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = 'public' AND table_name = 'countries'
                        """, Integer.class))
                .as("V1's own effect, observed instead of inferred from the history table")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM countries", Integer.class))
                .as("and V2 seeded it, so the chain ran on past the migration the baseline skipped")
                .isPositive();
        assertThat(currentVersion(flyway))
                .as("the schema does not stop at the first migration: it ends at the head")
                .isEqualTo(headVersion(flyway));
    }

    @Test
    @DisplayName("a database that already holds a full history is unaffected: nothing is applied twice")
    void databaseAlreadyAtHeadIsUntouched() {
        // What every environment that exists today was built with: a database that Flyway created on an
        // empty schema, where `baseline-on-migrate` never triggered and the whole chain ran.
        var previousDelivery = Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        previousDelivery.migrate();
        var historyBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class);

        var applied = deliveryFlyway().migrate();

        assertThat(applied.migrationsExecuted)
                .as("a database with a full history is a replay of nothing, whatever the baseline version")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class))
                .as("and it gains no row at all, so the baseline cannot renumber what it finds")
                .isEqualTo(historyBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM countries", Integer.class))
                .as("the rows the seed migrations wrote are still the rows that are there")
                .isPositive();
    }

    /** The two statements of {@code docker/postgres-init/lifecontrol-roles.sql} that land in `public`. */
    private void createInfrastructureObjectTheRolesScriptCreates() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION task_employee_company(task uuid) RETURNS uuid
                    LANGUAGE plpgsql STABLE SECURITY DEFINER
                    SET search_path = pg_catalog, public, pg_temp
                    AS $$
                BEGIN
                    RETURN NULL;
                END
                $$;
                """);
    }

    /**
     * Flyway 12's own emptiness predicate, copied from {@code PostgreSQLSchema#doEmpty()}: relations of
     * kind table/view/sequence/toast, types that are neither arrays nor composites, and functions — each
     * one counted only when it does <b>not</b> belong to an extension.
     *
     * <p>Copying it is the point of the premise: it is the query that decides whether a schema is treated
     * as pre-existing, and it states the two facts this case rests on — {@code btree_gist}'s functions are
     * extension-owned and therefore invisible here, while the roles script's resolver is not. If Flyway
     * changes the predicate, this assertion fails instead of the case silently proving nothing.</p>
     */
    private boolean flywayConsidersTheSchemaEmpty() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT c.oid FROM pg_catalog.pg_class c
                      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                      LEFT JOIN pg_catalog.pg_depend d ON d.objid = c.oid AND d.deptype = 'e'
                     WHERE n.nspname = ? AND d.objid IS NULL AND c.relkind IN ('r', 'v', 'S', 't')
                    UNION ALL
                    SELECT t.oid FROM pg_catalog.pg_type t
                      JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                      LEFT JOIN pg_catalog.pg_depend d ON d.objid = t.oid AND d.deptype = 'e'
                     WHERE n.nspname = ? AND d.objid IS NULL AND t.typcategory NOT IN ('A', 'C')
                    UNION ALL
                    SELECT p.oid FROM pg_catalog.pg_proc p
                      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                      LEFT JOIN pg_catalog.pg_depend d ON d.objid = p.oid AND d.deptype = 'e'
                     WHERE n.nspname = ? AND d.objid IS NULL
                )
                """, Boolean.class, "public", "public", "public"));
    }

    /** Flyway configured from the delivered properties, against this case's scratch database. */
    private Flyway deliveryFlyway() {
        var delivery = deliveryProperties();
        var baselineOnMigrate = delivery.getProperty(BASELINE_ON_MIGRATE);
        var baselineVersion = delivery.getProperty(BASELINE_VERSION);
        assertThat(baselineOnMigrate)
                .as(
                        "%s must be declared in application.properties: without it Flyway refuses a"
                                + " non-empty schema instead of initializing it",
                        BASELINE_ON_MIGRATE)
                .isNotNull();
        assertThat(baselineVersion)
                .as("%s must be declared in application.properties", BASELINE_VERSION)
                .isNotNull();
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(Boolean.parseBoolean(baselineOnMigrate))
                .baselineVersion(MigrationVersion.fromVersion(baselineVersion))
                .load();
    }

    private static Properties deliveryProperties() {
        var properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("application.properties is not on the test classpath", e);
        }
        return properties;
    }

    private static String currentVersion(Flyway flyway) {
        var current = flyway.info().current();
        return current == null || current.getVersion() == null
                ? null
                : current.getVersion().getVersion();
    }

    private static String headVersion(Flyway flyway) {
        return Arrays.stream(flyway.info().all())
                .filter(info -> info.getType() == CoreMigrationType.SQL)
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(MigrationVersion::getVersion)
                .orElseThrow(() -> new IllegalStateException("no versioned migration resolves from db/migration"));
    }

    private java.sql.Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The container's JDBC URL with the database name replaced by the scratch one. */
    private static String jdbcUrlFor(String name) {
        var base = POSTGRES.getJdbcUrl();
        var queryStart = base.indexOf('?');
        var withoutQuery = queryStart < 0 ? base : base.substring(0, queryStart);
        var suffix = queryStart < 0 ? "" : base.substring(queryStart);
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + name + suffix;
    }
}
