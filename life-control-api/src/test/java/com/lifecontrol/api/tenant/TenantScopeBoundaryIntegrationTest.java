package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The boundary hook of ADR-0005: a transaction states the holder's scope, a policy then filters by it, and
 * a suspended transaction does not lose it.
 *
 * <p><b>What is real here and what is not.</b> Real: PostgreSQL, Flyway's schema, the production
 * transaction manager, and a policy created by {@code apply_tenant_isolation} — exactly the call T5 will
 * make on the fifteen real tables. Not real: the table the policy is applied to is a throwaway probe, so
 * that arming a policy costs the other 63 suites in this shared container nothing. That is the one way to
 * prove the mechanism now instead of waiting for T5: the policy, the scope function and the boundary hook
 * are the same three artifacts either way.</p>
 *
 * <p><b>Why the app role appears in a test.</b> The container's own user is a superuser, and a superuser
 * bypasses every policy — the same trap T1 removed from production (record L9). So each scoped read
 * switches with {@code set_config('role', 'lifecontrol_app', true)} inside <b>its own</b> transaction:
 * {@code LOCAL} means the role dies with the commit and never leaks onto the pooled connection the next
 * suite gets, which a plain {@code SET ROLE} would not do.</p>
 *
 * <p><b>The case that carries the design</b> is
 * {@link #theScopeIsInstalledAgainAfterANotSupportedSuspension()}: {@code ProfileLocationResolver.resolve}
 * is {@code @Transactional(propagation = NOT_SUPPORTED)} and reads a service, so a scope installed once per
 * request would disappear exactly there. The outer transaction below begins <b>before</b> the caller states
 * its scope — so it provably carries none — and the inner read still sees the tenant, because the hook runs
 * at every boundary and not once per request.</p>
 */
@SpringBootTest
@DisplayName("Tenant scope at the transaction boundary")
class TenantScopeBoundaryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000001a");
    private static final UUID COMPANY_B = UUID.fromString("0b000000-0000-4000-8000-00000000001b");
    private static final UUID ROW_A = UUID.fromString("0a000000-0000-4000-8000-0000000000a1");
    private static final UUID ROW_B = UUID.fromString("0b000000-0000-4000-8000-0000000000b1");
    private static final String SUBJECT = "kc-subject-boundary";

    /** The role the pool connects as in delivery (ADR decision 3): the one policies apply to. */
    private static final String APPLICATION_ROLE = "lifecontrol_app";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SuspendedScopeProbe suspendedScopeProbe;

    /** One throwaway table per test, armed with a real tenant policy and taken away below. */
    private String probe;

    @BeforeEach
    void armAProbeTableWithOneRowPerCompany() {
        probe = "probe_scope_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.execute("CREATE TABLE " + probe + " (id uuid PRIMARY KEY, company_id uuid NOT NULL)");
        jdbcTemplate.update(
                "INSERT INTO " + probe + " (id, company_id) VALUES (?, ?), (?, ?)", ROW_A, COMPANY_A, ROW_B, COMPANY_B);
        jdbcTemplate.execute("SELECT apply_tenant_isolation('" + probe + "'::regclass)");
    }

    @AfterEach
    void dropTheProbeTable() {
        // The list row first: once the table is gone `'name'::regclass` no longer resolves, and a row left
        // in `tenant_table` would make the sibling suite's "the list is empty right after the migration"
        // false for the next reader of this shared container.
        jdbcTemplate.update("DELETE FROM tenant_table WHERE table_name = to_regclass(cast(? as text))", probe);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + probe);
    }

    @Test
    @DisplayName("the transaction boundary installs the holder's company and subject")
    void theBoundaryInstallsTheHoldersScope() {
        TenantScope.set(COMPANY_A, SUBJECT);
        try {
            var installed = inNewTransaction(() -> List.of(setting("app.company_id"), setting("app.user_id")));

            assertThat(installed)
                    .as("both settings the policy reads are installed inside the transaction, before its "
                            + "first query — ADR decision 1, and the reason the hook is at doBegin")
                    .containsExactly(COMPANY_A.toString(), SUBJECT);
        } finally {
            TenantScope.clear();
        }
    }

    @Test
    @DisplayName("a transaction with no scope installs nothing, so the policy filters every row")
    void aTransactionWithoutAScopeReadsNoTenantRow() {
        var observation = inNewTransaction(() -> new Observation(totalRowsAsOwner(), visibleRowIds()));

        assertThat(observation.ownerVisibleRows())
                .as("the control: both companies' rows exist, and this count runs as the superuser, which "
                        + "bypasses the policy")
                .isEqualTo(2L);
        assertThat(observation.applicationVisibleRows())
                .as("no scope is the fail-closed case: zero rows, and no exception either — the pooled "
                        + "connection's empty string is what nullif in the policy is for")
                .isEmpty();
    }

    @Test
    @DisplayName("a scoped transaction reads only the caller's rows")
    void aScopedTransactionReadsOnlyTheHoldersRows() {
        TenantScope.set(COMPANY_A, null);
        try {
            var observation = inNewTransaction(() -> new Observation(totalRowsAsOwner(), visibleRowIds()));

            assertThat(observation.ownerVisibleRows()).isEqualTo(2L);
            assertThat(observation.applicationVisibleRows())
                    .as("the company the boundary installed is the only tenant this transaction may see")
                    .containsExactly(ROW_A.toString());
        } finally {
            TenantScope.clear();
        }
    }

    @Test
    @DisplayName("the scope is installed again after a NOT_SUPPORTED suspension")
    void theScopeIsInstalledAgainAfterANotSupportedSuspension() {
        var observation = inNewTransaction(() -> {
            // The outer transaction begins before the caller states anything, so it provably carries no
            // scope — which is what makes the inner read proof of the boundary hook and not of the outer
            // transaction.
            var outerCompany = setting("app.company_id");

            TenantScope.set(COMPANY_A, null);
            try {
                return new Suspension(outerCompany, suspendedScopeProbe.readVisibleRows(probe));
            } finally {
                TenantScope.clear();
            }
        });

        assertThat(observation.outerCompany())
                .as("the suspended transaction had no scope, so nothing was inherited from it")
                .isNullOrEmpty();
        assertThat(observation.suspendedVisibleRows())
                .as("the inner transaction installed the scope itself: this is the case a per-request "
                        + "transaction would fail, and the reason the hook is at the boundary")
                .containsExactly(ROW_A.toString());
    }

    // ──────────────────────────────────────
    // the transaction under test
    // ──────────────────────────────────────

    private <T> T inNewTransaction(Supplier<T> work) {
        var template = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW and not REQUIRED: the boundary hook is only called for a transaction this manager
        // actually begins, so a participating transaction would prove nothing.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    /**
     * The EntityManager the transaction bound — the same one the hook issued the scope through, and the
     * only handle that is guaranteed to be on the transaction's connection. A {@code JdbcTemplate} here
     * would take a second connection from the pool and read another session's settings.
     */
    private EntityManager boundEntityManager() {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        assertThat(entityManager)
                .as("this read must run inside the transaction under test")
                .isNotNull();
        return entityManager;
    }

    private String setting(String name) {
        return (String) boundEntityManager()
                .createNativeQuery("select current_setting(cast(:name as text), true)")
                .setParameter("name", name)
                .getSingleResult();
    }

    private long totalRowsAsOwner() {
        return ((Number) boundEntityManager()
                        .createNativeQuery("select count(*) from " + probe)
                        .getSingleResult())
                .longValue();
    }

    /** The ids visible as the application role, after switching to it for the rest of the transaction. */
    private List<String> visibleRowIds() {
        return ScopedRowsReader.visibleRowIds(boundEntityManager(), probe);
    }

    private record Observation(long ownerVisibleRows, List<String> applicationVisibleRows) {}

    private record Suspension(String outerCompany, List<String> suspendedVisibleRows) {}

    // ──────────────────────────────────────
    // the suspension fixture: a NOT_SUPPORTED method that reads through a read-only transaction
    // ──────────────────────────────────────

    @TestConfiguration
    static class SuspensionProbeConfiguration {

        @Bean
        ScopedRowsReader scopedRowsReader(EntityManagerFactory entityManagerFactory) {
            return new ScopedRowsReader(entityManagerFactory);
        }

        @Bean
        SuspendedScopeProbe suspendedScopeProbe(ScopedRowsReader scopedRowsReader) {
            return new SuspendedScopeProbe(scopedRowsReader);
        }
    }

    /**
     * Mirrors {@code ProfileLocationResolver.resolve}: a {@code NOT_SUPPORTED} method whose body reads
     * through a transactional service. Both beans are ordinary beans so Spring's transaction advice is
     * what runs — inside the test's own call graph, not as a mock of it.
     */
    static class SuspendedScopeProbe {

        private final ScopedRowsReader reader;

        SuspendedScopeProbe(ScopedRowsReader reader) {
            this.reader = reader;
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public List<String> readVisibleRows(String table) {
            return reader.visibleRowIds(table);
        }
    }

    /** The read-only service of the pair: its own transaction is the boundary the scope must appear on. */
    static class ScopedRowsReader {

        private final EntityManagerFactory entityManagerFactory;

        ScopedRowsReader(EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Transactional(readOnly = true)
        public List<String> visibleRowIds(String table) {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            if (entityManager == null) {
                throw new IllegalStateException("the read ran outside a transaction, so there is no boundary to hook");
            }
            return visibleRowIds(entityManager, table);
        }

        static List<String> visibleRowIds(EntityManager entityManager, String table) {
            // LOCAL: the role dies with this transaction. A plain SET ROLE would follow the pooled
            // connection into the next suite, which is the residue record L11(b) warns about.
            entityManager
                    .createNativeQuery("select set_config('role', '" + APPLICATION_ROLE + "', true)")
                    .getSingleResult();
            @SuppressWarnings("unchecked")
            var ids = (List<String>) entityManager
                    .createNativeQuery("select id::text from " + table + " order by id")
                    .getResultList();
            return ids;
        }
    }
}
