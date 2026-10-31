package com.lifecontrol.api.support;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need real PostgreSQL semantics.
 * <p>
 * Starts a single {@link PostgreSQLContainer} per JVM (singleton container) and
 * points Spring's datasource at it, enabling Flyway so the schema is built by the
 * exact same migrations used in production ({@code V1} to {@code V3}). This removes
 * the H2/PostgreSQL dialect divergences that made H2 an unreliable proxy for
 * production column types and constraints.
 * <p>
 * Test classes extending this base must still declare {@code @SpringBootTest}
 * (and {@code @AutoConfigureMockMvc} when needed). Pure slices ({@code @WebMvcTest}),
 * unit tests, and AOP/cache tests backed by mocked repositories stay on H2.
 * <p>
 * The container also creates the two roles ADR-0005 separates, from
 * {@code src/test/resources/test-container-roles.sql}: the migration that grants the application its
 * privileges names {@code lifecontrol_app}, and the container's own user ({@code lifecontrol}) is a
 * superuser — which always bypasses Row-Level Security — so the role the policies actually apply to has
 * to exist for those suites to prove anything. The script is idempotent and runs once per container
 * before Flyway. The <b>pool</b> logs in as {@code lifecontrol_app} — always, since record T6b-4 flipped
 * the default and deleted the switch — while <b>Flyway</b> keeps the container's superuser;
 * {@link #postgresProperties} states why the split exists.
 *
 * <p><b>The server takes 300 connections because the suite caches one pool per Spring context.</b> Each
 * distinct {@code @SpringBootTest} configuration keeps its own Hikari pool alive for the whole JVM and the
 * default pool size is 10, so sixty-odd classes already sit at PostgreSQL's default of 100 — which is why
 * the first test that needed a connection <i>outside</i> a pool failed with “sorry, too many clients
 * already” while 4239 others passed. Raising the ceiling is not hiding a leak: a leak would exhaust 300
 * just as well; it gives the suite room for the contexts it legitimately caches.</p>
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300")
            .withDatabaseName("lifecontrol_test")
            .withUsername("lifecontrol")
            .withPassword("lifecontrol")
            .withInitScript("test-container-roles.sql");

    static {
        POSTGRES.start();
    }

    /**
     * The role the pool logs in as: the one ADR-0005's policies apply to, and the reason this base exists.
     *
     * <p><b>Why the pool and Flyway are two credentials</b> (record T6b). Until T6b both were
     * {@code lifecontrol}, the container's SUPERUSER, and a superuser <b>always</b> bypasses Row-Level
     * Security whatever the policies say — so the sixteen armed tables were installed and enforced nothing
     * that any test could see. S5 of issue #696 asks for the suite to pass <i>under</i> the policies, which
     * is only measurable if the connections that run the queries are the connections the policies apply to.
     * The two halves have opposite requirements:</p>
     *
     * <ul>
     *   <li><b>Flyway cannot be the application role.</b> It owns nothing and has no DDL rights in
     *       delivery ({@code V52} grants it DML and nothing else), so {@code CREATE TABLE} would fail on
     *       the first migration. Migrations run as the owner, in the test container the superuser — the
     *       same split delivery uses ({@code DATABASE_USERNAME} for the pool, the owner for Flyway).</li>
     *   <li><b>The pool must not be the owner nor a superuser.</b> Both are exempt from RLS unless a policy
     *       is {@code FORCE}d on them, and the point of this slice is to stop being exempt: from here on a
     *       missing or wrong policy fails a test instead of hiding in production.</li>
     * </ul>
     *
     * <p>{@code spring.flyway.url}, {@code .user} and {@code .password} are Boot's own properties, so
     * Flyway takes its credential from them and not from {@code spring.datasource.*}. Passing the URL too
     * is redundant today (it equals the pool's) and deliberate: it states the separation in one place
     * instead of leaving it implied by a shared default, which is the shape delivery will need when the two
     * URLs diverge.</p>
     *
     * <p><b>The pool logs in as the application role unconditionally since record T6b-4.</b> T6b landed it
     * behind {@code TEST_POOL_APP_ROLE} because 545 of 4 240 tests failed when the role changed, and each of
     * the four slices needed the same suite run both ways to prove it moved the number (record L24). The last
     * slice made it the default and deleted the switch — environment variable included — so this class has
     * one credential to state and nothing to branch on: S5's acceptance is {@code ./gradlew cleanTest test}
     * with <b>no</b> environment variable set.</p>
     */
    private static final String APPLICATION_USERNAME = "lifecontrol_app";

    /** Not a secret: a literal for a container that lives for one JVM on a random loopback port. */
    private static final String APPLICATION_PASSWORD = "lifecontrol_app";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
        registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * A pool bound to the shared container that logs in as {@code lifecontrol}, the container's <b>own</b>
     * user — a SUPERUSER, so no policy applies to it.
     *
     * <p><b>Who may take it, and why it is not a second switch.</b> Only a suite whose cases observe
     * ADR-0005's <b>machinery</b>: the boundary hook on a transaction whose policy the suite arms itself
     * ({@code ALTER TABLE … ENABLE/FORCE ROW LEVEL SECURITY} requires the table's owner), a control read that
     * has to be unfiltered before the policy can be shown answering below it, and the resolver whose
     * {@code SECURITY DEFINER} owner is a superuser. Those facts are invisible to the application role by
     * construction, so such a suite declares the credential its premise needs instead of inheriting it — and
     * says in its own javadoc which role it runs as and why that role is the one that can observe the fact.
     * Every suite whose cases prove the <b>policy</b> keeps the default pool, which is the application role:
     * that is what S5 measures, and a suite taking this method as a shortcut would stop measuring it.</p>
     *
     * <p>The password is the container's, not a second literal: it is the throwaway credential
     * {@code withPassword} sets, on a container that listens on a random loopback port for one JVM.</p>
     */
    protected static DataSource containerSuperuserPool() {
        var dataSource = new HikariDataSource();
        dataSource.setPoolName("test-container-superuser-pool");
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /**
     * Hands the context's transaction manager to the fixtures, which open their raw writes on it (record
     * T6b-2).
     *
     * <p>A field on the base class and not a parameter per fixture entry point: sixty-five test classes call
     * {@code TenantFixtures}, and the manager is one fact for the whole run — the same one the application
     * uses, which is the only manager whose {@code doBegin} installs the tenant scope.</p>
     */
    @Autowired
    private PlatformTransactionManager tenantFixtureBoundary;

    /**
     * Re-states that binding before every test method.
     *
     * <p><b>Why per test and not once per context</b> (measured on T6b-2). The binding is a JVM-wide static
     * and Spring <b>caches</b> a context across classes, so an autowired method runs when a context is
     * created, not every time one is reused. A suite that clears the binding — {@code TenantFixturesTest}
     * does, so a Mockito suite does not leave a recording manager behind for the next class — therefore
     * unbound the fixtures of every later suite whose context Spring had already built, and those failed with
     * “No transaction manager is bound to TenantFixtures” depending on execution order. Re-binding here costs
     * one assignment per test and makes the fact true for the test that needs it instead of for the context
     * that happened to be created first.</p>
     *
     * <p>A {@code @BeforeEach} on the base class runs before every subclass's own, which is exactly the order
     * a fixture in a subclass's {@code @BeforeEach} needs.</p>
     */
    @BeforeEach
    void bindTenantFixtureBoundary() {
        // Guarded because two suites extend this class for its container and its helpers while booting no
        // Spring context at all (the two that migrate their own scratch database): they have no manager to
        // receive, and they bind their own in their setup. Binding null would erase that one.
        if (tenantFixtureBoundary != null) {
            TenantFixtures.bindTransactionManager(tenantFixtureBoundary);
        }
    }

    /**
     * Deletes every access-provisioning row, leaf-first, for the suites whose own cleanup is an
     * <b>unscoped</b> {@code DELETE FROM employees}.
     *
     * <p>Added with unit W5c&#39;s producer (record T90): {@code ContractService.createContract} and
     * {@code EmployeeService.updateEmployee} now write an {@code access_provisioning_tasks} row for an
     * organisational fact, and that table carries a foreign key to {@code employees}. A suite that wipes
     * the employee table without deleting the rows that reference those employees therefore fails on the
     * constraint — and it fails for every other suite's leftover rows too, not only its own, which is why
     * the two rows that are not referenced by an employee at all are deleted in the same call.</p>
     *
     * <p>The order is the one the {@code provisioning} suites already use: applied roles and reviewed
     * roles reference a task, and a task references an employee. It is deliberately <b>not</b> wired into
     * this class' lifecycle: a suite that cleans a scoped subset of its own must keep cleaning that
     * subset, and only a suite already deleting every employee is entitled to delete every intent.</p>
     */
    protected static void deleteAllAccessProvisioningRows(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM access_provisioning_applied_roles");
        jdbcTemplate.update("DELETE FROM access_provisioning_reviewed_roles");
        jdbcTemplate.update("DELETE FROM access_provisioning_tasks");
    }

    /**
     * Deletes every employee, leaf-first, for the suites whose own cleanup is an <b>unscoped</b>
     * {@code DELETE FROM employees}.
     *
     * <p>Two tables reference {@code employees(id)} and every one of them blocks that delete while a
     * row survives: {@code employee_contracts} (V21) and {@code employee_scope_assignments} (V26). The
     * container is started once per JVM, so a row another
     * suite left behind is enough to fail this suite's cleanup — issue #562, where a full run failed all
     * 14 tests of {@code EmployeeCrudIntegrationTest} with a {@code DataIntegrityViolationException}
     * while the class passed on its own.</p>
     *
     * <p>The order matters and lives here, not in the callers: children first, then the parent. Only a
     * suite already deleting every employee is entitled to call this, for the same reason the
     * access-provisioning helper states: a suite that cleans a scoped subset of its own must keep
     * cleaning that subset.</p>
     */
    protected static void deleteAllEmployees(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM employee_contracts");
        jdbcTemplate.update("DELETE FROM employee_scope_assignments");
        jdbcTemplate.update("DELETE FROM employees");
    }

    /** The classpath location of every versioned migration this API ships. */
    private static final String MIGRATION_LOCATION = "classpath*:db/migration/V*__*.sql";

    /**
     * Returns the newest versioned migration in the API's own {@code db/migration} directory.
     *
     * <p>Exists so a migration is added in one place. Seven suites asserted the Flyway head as a
     * literal ({@code "35"}), so every new migration meant editing seven assertions, and the ones
     * that were forgotten stayed green until the next run of that suite — the drift the record
     * {@code odd/tasks/product-category-sat-code.md} measured in its own comments ({@code V33} next
     * to an assertion of {@code 35}) is the same failure mode.</p>
     *
     * <p>The version is read from the <b>files the classpath actually carries</b>, deliberately not
     * from {@code flyway.info()}: comparing Flyway's own report against Flyway's own scan would be a
     * tautology. A migration file whose name Flyway does not recognize (a missing {@code __}, a
     * lower-case {@code v}) is present in the directory and absent from the report, so this source
     * catches it while the report alone cannot — which is strictly more than the literal checked.</p>
     *
     * @return the newest version, as Flyway renders it ({@code "35"})
     * @throws IllegalStateException when the location resolves to no migration at all, so a wrong
     *     location fails loudly instead of silently comparing against nothing
     */
    protected static String latestMigrationVersion() {
        return latestMigrationVersion(MIGRATION_LOCATION);
    }

    /**
     * Same as {@link #latestMigrationVersion()}, for an explicit location pattern. Package-visible so
     * the ordering rule can be pinned against a fixture directory instead of the real migrations.
     */
    protected static String latestMigrationVersion(String locationPattern) {
        var resolver = new PathMatchingResourcePatternResolver();
        List<MigrationVersion> versions = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(locationPattern)) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                int end = filename.indexOf("__");
                if (end > 1 && filename.startsWith("V")) {
                    // Flyway's own version type, so "V10" orders after "V9": a comparison over the raw
                    // names reports the oldest two-digit migration as the newest, which is the defect
                    // MigrationHeadTest pins.
                    versions.add(MigrationVersion.fromVersion(filename.substring(1, end)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the migrations at " + locationPattern, e);
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException("no migration matched " + locationPattern);
        }
        return versions.stream().max(Comparator.naturalOrder()).orElseThrow().getVersion();
    }
}
