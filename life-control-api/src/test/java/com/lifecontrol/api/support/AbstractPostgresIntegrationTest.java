package com.lifecontrol.api.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * to exist for those suites to prove anything. The script is idempotent, runs once per container before
 * Flyway, and is inert for every suite that never switches to either role with {@code SET ROLE}.
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

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
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
