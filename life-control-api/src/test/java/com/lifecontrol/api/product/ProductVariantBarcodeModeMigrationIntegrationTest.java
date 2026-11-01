package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification of {@code V56__variant_barcode_optional.sql}, the schema half of ADR-0006 decision 4:
 * {@code bar_code} stops being required, its tenant key becomes the partial {@code uq_variant_bar_code},
 * and {@code trg_variant_matches_mode} is what makes the <b>product's mode</b> decide both what a variant
 * may be and whether it needs a barcode (invariants C2, C3 and C5).
 *
 * <p><b>Why a scratch database.</b> The shape this file asserts is the state <b>before</b> the change:
 * {@code bar_code} was {@code NOT NULL} from {@code V14} on and its key was the full
 * {@code uq_product_variants_company_bar_code} from {@code V40}, so the shared test database — which boots
 * straight to head — can only show the destination. Here the database is held at {@code V55} (the head
 * this unit starts from), a catalogue is seeded the way that schema could hold it, and the chain then runs
 * to head: the relaxation, the replaced key and the trigger are all observed against real PostgreSQL.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser, the way the
 * older scratch-database suites do it. {@code V52__tenant_isolation_machinery.sql:152-153} calls
 * {@code ENABLE} <b>and {@code FORCE}</b> on every table it arms, {@code V53__tenant_isolation_policies.sql:122-123}
 * arms {@code products} and {@code product_variants}, and {@code FORCE} is what makes a policy apply to the
 * table's <b>owner</b> — the role Flyway runs as in delivery. A superuser bypasses the policies whatever
 * they say, so a trigger asserted under it would report reading the mode while delivery's
 * {@code NOSUPERUSER} owner read no product at all. The last case below pins the other half of the
 * premise: an insert on a connection that states <b>no</b> tenant scope is <b>refused</b>, because the
 * trigger reads under the writer's own scope and a product it cannot see is a product whose mode it
 * cannot check. That is deliberate — stating the scope from the row inside the trigger would make the
 * policy's {@code WITH CHECK}, evaluated afterwards, compare the row to its own claim.</p>
 */
@DisplayName("Product variant barcode and mode trigger migration (V56)")
class ProductVariantBarcodeModeMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: `bar_code` is still required and no trigger exists. */
    private static final String PRE_UNIT_HEAD = "55";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_variant_barcode_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + OWNER);
        }
        jdbcUrl = jdbcUrlFor(databaseName);
    }

    @AfterEach
    void dropScratchDatabase() {
        if (databaseName == null) {
            return;
        }
        try (var connection =
                        DriverManager.getConnection(adminJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (Exception e) {
            throw new IllegalStateException("could not drop scratch database " + databaseName, e);
        } finally {
            databaseName = null;
        }
    }

    // --- cases ---

    @Test
    @DisplayName("bar_code stops being required, its key becomes partial, and the mode trigger is installed")
    void relaxesTheColumnAndKeysTheBarcodeOnlyWhereItExists() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        // The state this unit starts from, asserted so the assertions below are about the change.
        assertThat(nullable("product_variants", "bar_code")).isEqualTo("NO");
        assertThat(constraintNames()).contains("uq_product_variants_company_bar_code");
        assertThat(indexDefinition("uq_variant_bar_code")).isNull();

        migrateToHead();

        assertThat(nullable("product_variants", "bar_code"))
                .as("decision 4: a single-variant article has no barcode to give")
                .isEqualTo("YES");
        assertThat(constraintNames())
                .as("V40's key covered every row and therefore required one")
                .doesNotContain("uq_product_variants_company_bar_code");
        assertThat(indexDefinition("uq_variant_bar_code"))
                .as("the replacement is an index — a partial UNIQUE cannot be a constraint — and it is "
                        + "partial, so the rows with no barcode are not indexed and cannot collide")
                .isNotNull()
                .containsIgnoringCase("UNIQUE")
                .contains("(company_id, bar_code)")
                .contains("WHERE (bar_code IS NOT NULL)");
    }

    @Test
    @DisplayName("two barcode-less variants of SIMPLE products coexist in one tenant")
    void acceptsBarcodeLessVariantsOfSimpleProducts() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var bread = insertProduct(company, "V56-SIMPLE", "A loaf of bread", "SIMPLE");
        var cheese = insertProduct(company, "V56-SIMPLE-2", "A wheel of cheese", "SIMPLE");

        migrateToHead();

        insertVariant(company, bread, null, "Unica");
        insertVariant(company, cheese, null, "Entera");

        assertThat(countScoped(company, "SELECT count(*) FROM product_variants"))
                .as("both rows are stored: the partial key leaves the ones with no barcode alone")
                .isEqualTo(2);
        assertThat(countScoped(company, "SELECT count(*) FROM product_variants WHERE bar_code IS NULL"))
                .as("and neither was given an invented barcode to satisfy a key")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a barcode-less variant of a MULTI product is refused with the ADR's own message")
    void refusesBarcodeLessVariantOfMultiProduct() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var shoe = insertProduct(company, "V56-MULTI", "A shoe", "MULTI");

        migrateToHead();

        assertThatThrownBy(() -> insertVariant(company, shoe, null, "Talla 40"))
                // Verbatim: it is what an operator reads, and it names the product that has to change.
                .hasStackTraceContaining("bar_code is required for a variant of MULTI product " + shoe);

        assertThat(countScoped(company, "SELECT count(*) FROM product_variants"))
                .as("the refusal stores nothing")
                .isZero();
    }

    @Test
    @DisplayName("a second variant of a SIMPLE product is refused with the ADR's own message")
    void refusesSecondVariantOfSimpleProduct() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var bread = insertProduct(company, "V56-SIMPLE-ONE", "A loaf of bread", "SIMPLE");
        insertVariant(company, bread, "7509999900001", "Unica");

        migrateToHead();

        assertThatThrownBy(() -> insertVariant(company, bread, "7509999900002", "Otra"))
                .hasStackTraceContaining("product " + bread + " is SIMPLE and already has a variant");

        assertThat(countScoped(company, "SELECT count(*) FROM product_variants"))
                .as("the one variant that satisfied SIMPLE is still the only row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate non-null barcode in one tenant is refused by uq_variant_bar_code")
    void refusesDuplicateBarcodeInOneTenant() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var shoe = insertProduct(company, "V56-MULTI-DUP", "A shoe", "MULTI");
        insertVariant(company, shoe, "7501234567890", "Talla 40");

        migrateToHead();

        assertThatThrownBy(() -> insertVariant(company, shoe, "7501234567890", "Talla 41"))
                .as("invariant C5: the key is per tenant and covers exactly the rows that have a barcode")
                .hasStackTraceContaining("uq_variant_bar_code");
    }

    @Test
    @DisplayName("a write from a connection that states no tenant scope is refused, not validated blind")
    void aWriterWithNoScopeIsRefused() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var shoe = insertProduct(company, "V56-NO-SCOPE", "A shoe", "MULTI");

        migrateToHead();

        // No `set_config('app.company_id', …)` on this connection at all: this is the Flyway run and the
        // provisioning worker, the writers ADR-0005's FORCE makes blind. The product is unreadable from
        // here, so the mode is unknown — and the trigger refuses instead of validating nothing. It does
        // NOT state the scope itself: a BEFORE ROW trigger runs before the policy's WITH CHECK, so
        // stating the row's own company there would make that check compare the row to its own claim and
        // accept a cross-tenant insert. Asserted as a refusal with the product named, because the message
        // is what an operator will read.
        assertThatThrownBy(() -> executeUnscoped("INSERT INTO product_variants"
                        + " (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + company + "', '" + shoe + "', NULL,"
                        + " 'Talla 40', '" + seededUnitId() + "')"))
                .as("the product cannot be read under this writer's scope, so the rules are unknown and the "
                        + "row is refused")
                .hasStackTraceContaining("could not be read")
                .hasStackTraceContaining(shoe.toString());
    }

    // --- migrations ---

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                // 0 and not 1: delivery baselines at 0 (#722), and a scratch database is empty anyway.
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private void migrateToHead() {
        Flyway.configure()
                .dataSource(jdbcUrl, OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    // --- fixtures: every write states the tenant scope, because the policies apply to the owner ---

    private UUID insertCompany() throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('" + id + "', 'V56-"
                        + id.toString().substring(0, 8) + "', 'V56 Company', 'V5601010AB1')");
        return id;
    }

    /**
     * A product, seeded through the columns the pre-unit schema has: {@code variant_mode} exists from
     * {@code V54} on and is stated explicitly, so each case controls the mode it is about instead of
     * inheriting the column's default.
     */
    private UUID insertProduct(UUID company, String sku, String name, String variantMode) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id, variant_mode) VALUES ('" + id + "', '" + sku + "', '"
                        + name + "', '" + company + "', '" + variantMode + "')");
        return id;
    }

    /** A variant; {@code barCode} may be {@code null}, which is the state this migration makes legal. */
    private void insertVariant(UUID company, UUID product, String barCode, String variantName) throws SQLException {
        var literal = barCode == null ? "NULL" : "'" + barCode + "'";
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + company + "', '" + product + "', " + literal
                        + ", '" + variantName + "', '" + seededUnitId() + "')");
    }

    /**
     * The {@code measure_units} row {@code V3} seeds as "Pieza". The unit is required from {@code V55}
     * on, so a fixture has to declare a real one — and it is read, never invented: a random uuid would be
     * refused by the foreign key, which would make every case fail for a reason that says nothing about
     * the barcode rule.
     */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT id FROM measure_units WHERE sat_code = 'H87'")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        }
    }

    /**
     * One statement on one connection with the tenant scope set first.
     *
     * <p>The scope is not a convenience: {@code products} and {@code product_variants} are isolated by
     * {@code company_id}, so without it the write is refused by the policy's {@code WITH CHECK} — the
     * behaviour ADR-0005 wants, and the reason a scratch-database fixture cannot borrow a pool that
     * installs the scope for it.</p>
     */
    private void execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
                statement.execute(sql);
            }
        }
    }

    /** The same write with no scope stated: what Flyway and the provisioning worker look like. */
    private void executeUnscoped(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // --- reads ---

    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.company_id', '" + scope + "', false)");
            }
            try (Statement statement = connection.createStatement();
                    var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private int countScoped(UUID scope, String sql) throws SQLException {
        return Integer.parseInt(readScoped(scope, sql));
    }

    /** {@code YES}/{@code NO} for one column: {@code information_schema} is readable without a scope. */
    private String nullable(String table, String column) throws SQLException {
        return readUnscoped("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = '" + table + "' AND column_name = '" + column + "'");
    }

    private String indexDefinition(String indexName) throws SQLException {
        return readUnscoped(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = '" + indexName + "'");
    }

    /** The constraint names on the table under test, as PostgreSQL reports them. */
    private String constraintNames() throws SQLException {
        return readUnscoped("SELECT string_agg(constraint_name, ',' ORDER BY constraint_name)"
                + " FROM information_schema.table_constraints WHERE table_schema = 'public'"
                + " AND table_name = 'product_variants'");
    }

    private String readUnscoped(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    // --- the scratch database's own administration ---

    private String adminJdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    private String jdbcUrlFor(String name) {
        var base = POSTGRES.getJdbcUrl();
        var queryStart = base.indexOf('?');
        var withoutQuery = queryStart < 0 ? base : base.substring(0, queryStart);
        var suffix = queryStart < 0 ? "" : base.substring(queryStart);
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + name + suffix;
    }
}
