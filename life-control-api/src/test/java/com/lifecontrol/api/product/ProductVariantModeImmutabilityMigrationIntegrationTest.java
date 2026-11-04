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
 * Verification of {@code V76__product_variant_mode_immutable.sql}, the guard ADR-0006 decision 1 promised
 * ("set at creation, <b>immutable once its variant has stock movements</b>") and the ADR's migration step 6
 * deferred until ADR-0001's ledger existed — which {@code V60} created.
 *
 * <p><b>The defect this suite is written against.</b> Until this migration there is <b>no trigger on
 * {@code products} at all</b>, so {@code UPDATE products SET variant_mode = 'SIMPLE'} on a product with two
 * variants is reachable today, and it leaves a row that {@code V56}'s {@code assert_variant_matches_mode()}
 * forbids any write to create. The API never changes the mode ({@code ProductService.updateProduct} does not
 * read {@code variantMode}, and the update path posts neither {@code variantMode} nor {@code variants}), so
 * the only place this rule can live is the database — and the only way to observe it is a suite that talks to
 * PostgreSQL directly.</p>
 *
 * <p><b>Why a scratch database.</b> The shared test database boots straight to head, so it can only show the
 * destination: a database that already refuses. Here the database is held at {@code V75} (the head this unit
 * starts from, where the guard does not exist yet), the catalogue and the tree a {@code stock_movement} needs
 * are seeded the way that schema holds them, and the chain then runs to head — so every refusal below is
 * observed as a <i>change</i> in behaviour and not as a property of the tree it was born in.</p>
 *
 * <p><b>Why Flyway runs as {@code lifecontrol_owner}</b> and not as the container's superuser.
 * {@code V52__tenant_isolation_machinery.sql:152-153} calls {@code ENABLE} <b>and {@code FORCE}</b> on every
 * table it arms, {@code V53__tenant_isolation_policies.sql:108-114} arms {@code products},
 * {@code product_variants} and {@code companies}, and {@code V60} arms {@code stock_movement}.
 * {@code FORCE} is what makes a policy apply to the table's <b>owner</b> — the role Flyway runs as in
 * delivery — while a superuser bypasses every policy whatever it says. A guard asserted under the superuser
 * would therefore report a ledger it could always read, and delivery's {@code NOSUPERUSER} owner would see
 * none of it.</p>
 *
 * <p><b>What the suite pins, case by case.</b> The rule has two halves and both are asserted, because a guard
 * that refuses too much is as wrong as one that refuses nothing: cases 1–3 are the refusals (both directions,
 * and the {@code SIMPLE}-with-two-variants state #774 reports as reachable, which contradicts {@code V56}),
 * and cases 4–7 are the regressions — a product with no movements is still reclassifiable, a writer that
 * re-sends an unchanged value is not refused (the column being in the {@code SET} list fires the trigger even
 * when the value does not move), an {@code UPDATE} that does not name the column is untouched, and a writer
 * scoped to another company changes nothing at all, so the guard is not reachable from outside the tenant.
 * Case 8 pins the installation itself.</p>
 *
 * <p><b>What this suite deliberately does not cover.</b> {@code variant_listing} is not a freeze condition in
 * this slice: ADR-0006 migration step 6 (the pre-ledger formulation) named "a listing or non-zero stock", and
 * decision 1 — the accepted decision — names stock movements. Adding the listing later is additive; #774's
 * approval is decision 1's letter, and the migration's header records the omission so it is a decision and not
 * an oversight.</p>
 */
@DisplayName("Product variant mode immutability migration (V76)")
class ProductVariantModeImmutabilityMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The head this unit starts from: `products` carries the mode and has no trigger at all. */
    private static final String PRE_UNIT_HEAD = "75";

    /** The trigger this unit installs, named once so the assertions cannot drift from each other. */
    private static final String TRIGGER = "trg_product_variant_mode_immutable";

    /** The role Flyway runs as in delivery: it owns the tables and the policies still apply to it. */
    private static final String OWNER = "lifecontrol_owner";

    /** A literal on a container that lives for one JVM on a random loopback port, never a secret. */
    private static final String OWNER_PASSWORD = "lifecontrol_owner";

    private String databaseName;
    private String jdbcUrl;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        databaseName = "scratch_variant_mode_" + UUID.randomUUID().toString().replace("-", "");
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

    // --- the refusals: a product whose variants have moved stock is frozen ---

    @Test
    @DisplayName("MULTI → SIMPLE with a stock movement on its variant is refused, and the message names the product")
    void refusesMultiToSimpleOnceAVariantHoldsStock() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var shoe = insertProduct(company, "V76-MULTI", "A shoe", "MULTI");
        var variant = insertVariant(company, shoe, "7509999900001", "Talla 40");

        migrateToHead();

        insertReceipt(company, tree, variant);

        assertThatThrownBy(() -> setMode(company, shoe, "SIMPLE"))
                .as("ADR-0006 decision 1: the mode is immutable once its variant has stock movements")
                .hasStackTraceContaining("product " + shoe)
                .hasStackTraceContaining("from MULTI to SIMPLE")
                .hasStackTraceContaining("ADR-0006 decision 1");

        assertThat(storedMode(company, shoe))
                .as("the refusal stored nothing: the product is still the MULTI article the ledger was booked "
                        + "against")
                .isEqualTo("MULTI");
        assertThat(countMovements(company))
                .as("and the movement it was refused for is untouched — the ledger is append-only")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SIMPLE → MULTI with a stock movement on its variant is refused too, so the guard is symmetric")
    void refusesSimpleToMultiOnceAVariantHoldsStock() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var bread = insertProduct(company, "V76-SIMPLE", "A loaf of bread", "SIMPLE");
        var variant = insertVariant(company, bread, null, "Unica");

        migrateToHead();

        insertReceipt(company, tree, variant);

        assertThatThrownBy(() -> setMode(company, bread, "MULTI"))
                .as("the rule is about the mode, not about one direction of it: a reclassification either way "
                        + "reinterprets the movements already booked against the variants")
                .hasStackTraceContaining("product " + bread)
                .hasStackTraceContaining("from SIMPLE to MULTI");

        assertThat(storedMode(company, bread)).as("the refusal stored nothing").isEqualTo("SIMPLE");
        assertThat(countMovements(company)).isEqualTo(1);
    }

    @Test
    @DisplayName("SIMPLE with two variants whose stock moved is refused, the state V56 forbids any write to create")
    void refusesTheModeChangeOfASimpleProductThatAlreadyHasTwoVariantsAndStock() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var shirt = insertProduct(company, "V76-SIMPLE-TWO", "A shirt", "MULTI");
        var small = insertVariant(company, shirt, "7509999900011", "Chica");
        var large = insertVariant(company, shirt, "7509999900012", "Grande");

        migrateToHead();

        // The reachable state #774 reports. Decision 1 freezes the mode once its variant has MOVEMENTS, so a
        // product with two variants and an empty ledger is still reclassifiable — that is case 4's rule, and it
        // is what makes this row representable at all. From here V56 refuses every write that would create it:
        // `trg_variant_matches_mode` answers "product … is SIMPLE and already has a variant". Only a legacy row
        // (a product that predates V56) or this window reaches it.
        assertThat(setMode(company, shirt, "SIMPLE"))
                .as("the premise: with no movement yet, the reclassification is legal")
                .isEqualTo(1);
        assertThat(storedMode(company, shirt)).isEqualTo("SIMPLE");

        insertReceipt(company, tree, small);

        assertThatThrownBy(() -> setMode(company, shirt, "MULTI"))
                .as("the sharper half of #774: the ledger now names the variants of a SIMPLE article with two of "
                        + "them, and a mode change would reinterpret what that movement was booked against")
                .hasStackTraceContaining("product " + shirt)
                .hasStackTraceContaining("from SIMPLE to MULTI");

        assertThat(storedMode(company, shirt))
                .as("the refusal stored nothing, so the row the ledger was booked against is still readable")
                .isEqualTo("SIMPLE");
        assertThat(countVariants(company, shirt))
                .as("and neither variant was touched")
                .isEqualTo(2);
        assertThat(countMovements(company)).isEqualTo(1);
    }

    // --- the regressions: the guard must not refuse what nothing changed ---

    @Test
    @DisplayName("a product whose variants carry no movement can still be reclassified, in both directions")
    void stillAllowsReclassifyingAProductWithNoMovements() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        insertTree(company);
        var pants = insertProduct(company, "V76-NO-MOVEMENT", "A pair of pants", "MULTI");
        insertVariant(company, pants, "7509999900021", "32");
        insertVariant(company, pants, "7509999900022", "34");

        migrateToHead();

        assertThat(setMode(company, pants, "SIMPLE"))
                .as("decision 1's condition is the stock movement, not the variant count: an article whose "
                        + "ledger is empty can still be reclassified")
                .isEqualTo(1);
        assertThat(storedMode(company, pants)).isEqualTo("SIMPLE");

        assertThat(setMode(company, pants, "MULTI"))
                .as("and back, because the freeze is about a ledger that would be reinterpreted and this one is "
                        + "still empty")
                .isEqualTo(1);
        assertThat(storedMode(company, pants)).isEqualTo("MULTI");
        assertThat(countMovements(company)).isZero();
    }

    @Test
    @DisplayName("a writer that re-sends the same mode is not refused, because the value did not change")
    void doesNotRefuseAWriterThatReSendsTheSameMode() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var shoe = insertProduct(company, "V76-SAME-VALUE", "A shoe", "MULTI");
        var variant = insertVariant(company, shoe, "7509999900031", "Talla 41");

        migrateToHead();

        insertReceipt(company, tree, variant);

        // `UPDATE OF variant_mode` fires on a column that is in the SET list even when the value does not move,
        // so a writer that re-sends the whole row — which is what an ORM's dirty-checking update does when it
        // writes every mapped column — would be refused by a guard that only compared the row to the ledger.
        assertThat(execute(company, "UPDATE products SET variant_mode = variant_mode WHERE id = '" + shoe + "'"))
                .as("the value did not change, so nothing is reinterpreted")
                .isEqualTo(1);
        assertThat(setMode(company, shoe, "MULTI"))
                .as("and the same is true of the value spelled out: it is the same mode, written again")
                .isEqualTo(1);
        assertThat(storedMode(company, shoe)).isEqualTo("MULTI");
    }

    @Test
    @DisplayName("an UPDATE that does not name variant_mode is unaffected by the guard")
    void doesNotRefuseAnUpdateThatDoesNotNameTheMode() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var shoe = insertProduct(company, "V76-OTHER-COLUMN", "A shoe", "MULTI");
        var variant = insertVariant(company, shoe, "7509999900041", "Talla 42");

        migrateToHead();

        insertReceipt(company, tree, variant);

        assertThat(execute(company, "UPDATE products SET name = 'A renamed shoe' WHERE id = '" + shoe + "'"))
                .as("the trigger is `BEFORE UPDATE OF variant_mode`, so an ordinary edit of the article is not "
                        + "the question it asks — and a guard that refused it would make the product uneditable "
                        + "the moment it sold something")
                .isEqualTo(1);
        assertThat(readScoped(company, "SELECT name FROM products WHERE id = '" + shoe + "'"))
                .isEqualTo("A renamed shoe");
        assertThat(storedMode(company, shoe)).isEqualTo("MULTI");
    }

    @Test
    @DisplayName("a writer scoped to another company changes nothing, so the guard is not reachable from outside")
    void refusesNothingButChangesNothingFromAnotherTenant() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        var company = insertCompany();
        var tree = insertTree(company);
        var shoe = insertProduct(company, "V76-TENANT", "A shoe", "MULTI");
        var variant = insertVariant(company, shoe, "7509999900051", "Talla 43");
        var otherCompany = insertCompany();

        migrateToHead();

        insertReceipt(company, tree, variant);

        assertThat(setMode(otherCompany, shoe, "SIMPLE"))
                .as("the policy is the barrier here, not the trigger: a BEFORE UPDATE trigger only runs for a row "
                        + "the UPDATE could see, and another tenant's product is not one of them")
                .isZero();
        assertThat(readScoped(otherCompany, "SELECT count(*) FROM products WHERE id = '" + shoe + "'"))
                .as("the row is not merely unchangeable from that scope, it is invisible")
                .isEqualTo("0");
        assertThat(storedMode(company, shoe))
                .as("and its own tenant still reads the mode it was written with")
                .isEqualTo("MULTI");

        // The two facts that make the refusal above the policy's and not the guard's, measured instead of
        // assumed: all three tables are armed as tenant tables keyed on `company_id` (V53 and V60 §7), and all
        // three carry ENABLE *and FORCE*, so the policy applies to the owner Flyway runs as.
        assertThat(readUnscoped("SELECT string_agg(t.table_name::text || ':' || t.policy_kind || '/'"
                        + " || t.key_column, ',' ORDER BY t.table_name::text) FROM tenant_table t"
                        + " WHERE t.table_name IN ('products'::regclass, 'product_variants'::regclass,"
                        + " 'stock_movement'::regclass)"))
                .as("the guard reads three armed tables, so none of them can be read outside a tenant")
                .isEqualTo("product_variants:tenant/company_id,products:tenant/company_id,"
                        + "stock_movement:tenant/company_id");
        assertThat(readUnscoped("SELECT count(*) FROM pg_class c WHERE c.relkind = 'r'"
                        + " AND c.relname IN ('products', 'product_variants', 'stock_movement')"
                        + " AND c.relrowsecurity AND c.relforcerowsecurity"))
                .as("and FORCE is what makes them apply to the owner, which is the credential Flyway runs as")
                .isEqualTo("3");
    }

    // --- the installation itself ---

    @Test
    @DisplayName("the trigger is installed, enabled, and fires on updates of variant_mode after head")
    void installsTheTriggerAtHead() throws Exception {
        migrateTo(PRE_UNIT_HEAD);

        assertThat(triggerEnabled())
                .as("the premise: at V75 `products` has no guard at all")
                .isNull();

        migrateToHead();

        assertThat(triggerEnabled())
                .as("`O` is the ordinary enabled state: the trigger fires for the table's owner, its replicas and "
                        + "every session — a `D` here would be a guard that exists and is switched off")
                .isEqualTo("O");
        // 19 is BEFORE (2) | ROW (1) | UPDATE (16): a statement-level trigger would see no row, an AFTER one
        // would refuse a change it had already stored, and a trigger without the column list would ask its
        // question about every edit of an article that has sold something.
        assertThat(triggerFacts())
                .as("the trigger is BEFORE, FOR EACH ROW, and about UPDATE")
                .startsWith("19|")
                .contains("BEFORE UPDATE OF variant_mode ON")
                .contains("FOR EACH ROW EXECUTE FUNCTION refuse_variant_mode_change()");
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

    /** A company as {@code V57} requires it: it states its own operating currency. */
    private UUID insertCompany() throws SQLException {
        var id = UUID.randomUUID();
        execute(
                id,
                "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES ('" + id + "', 'V76-"
                        + id.toString().substring(0, 8) + "', 'V76 Company', 'V76"
                        + id.toString().replace("-", "").substring(0, 10).toUpperCase() + "', 'MXN')");
        return id;
    }

    /**
     * The store and the location {@code stock_movement}'s two composite foreign keys demand
     * ({@code fk_movement_store} and {@code fk_movement_location}, {@code V60:133-136}), in the shape
     * {@code StockLedgerSchemaMigrationIntegrationTest#insertTheTreeEveryCaseHangsFrom} casts them: country →
     * region → zone → store, then area → store zone → location, with the store restated on the location
     * because {@code V59} made it the pair the key is about.
     */
    private Tree insertTree(UUID company) throws SQLException {
        execute(
                company,
                "INSERT INTO company_countries (id, company_id, country_id)"
                        + " SELECT gen_random_uuid(), '" + company + "', c.id FROM countries c"
                        + " WHERE c.country_code = 'MX'"
                        + " ON CONFLICT (company_id, country_id) DO NOTHING");
        var companyCountry =
                readScoped(company, "SELECT id FROM company_countries WHERE company_id = '" + company + "'");

        var region = UUID.randomUUID();
        var regionCode = region.toString().substring(0, 6).toUpperCase();
        execute(
                company,
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name, enabled)"
                        + " VALUES ('" + region + "', '" + company + "', '" + companyCountry + "', '" + regionCode
                        + "', 'V76 Region', true)");

        var zone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name, enabled)"
                        + " VALUES ('" + zone + "', '" + company + "', '" + region + "', '"
                        + zone.toString().substring(0, 6).toUpperCase() + "', 'V76 Zone', true)");

        var store = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled) VALUES ('" + store
                        + "', '" + company + "', '" + zone + "', 'V76 Store', true)");

        var area = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_areas (id, company_store_id, area_code, area_name, enabled) VALUES ('" + area
                        + "', '" + store + "', '"
                        + area.toString().substring(0, 6).toUpperCase()
                        + "', 'V76 Area', true)");

        var storeZone = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_zones (id, store_area_id, company_store_id, zone_code, zone_name, enabled)"
                        + " VALUES ('" + storeZone + "', '" + area + "', '" + store + "', '"
                        + storeZone.toString().substring(0, 6).toUpperCase() + "', 'V76 Store Zone', true)");

        var location = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO store_locations (id, store_zone_id, company_store_id, location_code, location_name,"
                        + " enabled) VALUES ('" + location + "', '" + storeZone + "', '" + store + "', '"
                        + location.toString().substring(0, 6).toUpperCase() + "', 'V76 Location', true)");

        return new Tree(store, location);
    }

    /** A product with the mode stated explicitly, so each case controls the state it is about. */
    private UUID insertProduct(UUID company, String sku, String name, String variantMode) throws SQLException {
        var id = UUID.randomUUID();
        execute(
                company,
                "INSERT INTO products (id, sku, name, company_id, variant_mode) VALUES ('" + id + "', '" + sku + "', '"
                        + name + "', '" + company + "', '" + variantMode + "')");
        return id;
    }

    /** A variant; {@code barCode} is {@code null} only where decision 4 allows it, on a SIMPLE article. */
    private UUID insertVariant(UUID company, UUID product, String barCode, String variantName) throws SQLException {
        var id = UUID.randomUUID();
        var literal = barCode == null ? "NULL" : "'" + barCode + "'";
        execute(
                company,
                "INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name, measure_unit_id)"
                        + " VALUES ('" + id + "', '" + company + "', '" + product + "', " + literal + ", '"
                        + variantName + "', '" + seededUnitId() + "')");
        return id;
    }

    /**
     * One inbound movement, the shape {@code ck_movement_inbound_cost} requires: a row that adds quantity
     * states what it cost and in which currency. Inserted after head on purpose — nothing about the guard
     * changes what a movement is, and the ledger's own triggers run exactly as delivery's do.
     */
    private void insertReceipt(UUID company, Tree tree, UUID variant) throws SQLException {
        execute(company, """
                INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id, product_variant_id,
                                            movement_type, quantity, unit_cost, currency, source_type, source_id,
                                            reason)
                VALUES ('%s', '%s', '%s', '%s', '%s', 'RECEIPT', 1.0000, 10.0000, 'MXN', 'PURCHASE_ORDER_LINE',
                        '%s', 'variant mode immutability fixture')
                """.formatted(
                        UUID.randomUUID(), company, tree.storeId(), tree.locationId(), variant, UUID.randomUUID()));
    }

    /** The {@code measure_units} row {@code V3} seeds as "Pieza": read, never invented. */
    private UUID seededUnitId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT id FROM measure_units WHERE sat_code = 'H87'")) {
            resultSet.next();
            return resultSet.getObject(1, UUID.class);
        }
    }

    // --- writes and reads, every one of them under a stated tenant scope ---

    /** The mode change every case is about, written the way an operator's script would write it. */
    private int setMode(UUID scope, UUID product, String mode) throws SQLException {
        return execute(scope, "UPDATE products SET variant_mode = '" + mode + "' WHERE id = '" + product + "'");
    }

    /**
     * One statement on its own transaction, with {@code set_tenant_scope} stated first.
     *
     * <p>{@code set_tenant_scope} is {@code SET LOCAL} ({@code V52:60-63}), so the transaction has to stay open
     * while the statement runs: autocommit would end the transaction — and the scope with it — before the
     * statement it is there for. The scope is not a convenience either: {@code products},
     * {@code product_variants} and {@code stock_movement} are all {@code ENABLE}+{@code FORCE}d, so without it
     * a write is refused by the policy's {@code WITH CHECK} and a read matches no row.</p>
     *
     * <p>Returns the affected row count, which is what makes "changed nothing" measurable.</p>
     */
    private int execute(UUID scope, String sql) throws SQLException {
        try (Connection connection = scopedConnection(scope);
                Statement statement = connection.createStatement()) {
            var affected = statement.executeUpdate(sql);
            connection.commit();
            return affected;
        }
    }

    /** One row's first column, read under a stated scope: without it the policies answer with no row at all. */
    private String readScoped(UUID scope, String sql) throws SQLException {
        try (Connection connection = scopedConnection(scope);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private Connection scopedConnection(UUID scope) throws SQLException {
        var connection = DriverManager.getConnection(jdbcUrl, OWNER, OWNER_PASSWORD);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_tenant_scope('" + scope + "')");
        }
        return connection;
    }

    private String storedMode(UUID scope, UUID product) throws SQLException {
        return readScoped(scope, "SELECT variant_mode FROM products WHERE id = '" + product + "'");
    }

    private int countVariants(UUID scope, UUID product) throws SQLException {
        return Integer.parseInt(
                readScoped(scope, "SELECT count(*) FROM product_variants WHERE product_id = '" + product + "'"));
    }

    private int countMovements(UUID scope) throws SQLException {
        return Integer.parseInt(readScoped(scope, "SELECT count(*) FROM stock_movement"));
    }

    // --- the catalog, read unscoped: pg_class and pg_trigger carry no policy ---

    /** {@code O} enabled, {@code D} disabled, or {@code null} when no such trigger exists. */
    private String triggerEnabled() throws SQLException {
        return readUnscoped("SELECT t.tgenabled FROM pg_trigger t WHERE t.tgname = '" + TRIGGER
                + "' AND t.tgrelid = 'products'::regclass AND NOT t.tgisinternal");
    }

    /** The trigger's type bits and its own definition, in one read, so the two cannot come from two triggers. */
    private String triggerFacts() throws SQLException {
        return readUnscoped("SELECT t.tgtype || '|' || pg_get_triggerdef(t.oid) FROM pg_trigger t WHERE t.tgname = '"
                + TRIGGER + "' AND t.tgrelid = 'products'::regclass AND NOT t.tgisinternal");
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

    /** The two rows of the hierarchy a movement names. */
    private record Tree(UUID storeId, UUID locationId) {}
}
