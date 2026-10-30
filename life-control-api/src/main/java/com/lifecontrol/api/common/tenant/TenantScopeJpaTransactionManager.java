package com.lifecontrol.api.common.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * The boundary hook of ADR-0005: every transaction this manager begins states the tenant scope of the
 * operation that opened it, <b>inside</b> that transaction and <b>before</b> its first query.
 *
 * <p><b>Why here and not in a filter</b> (record L13 of {@code odd/tasks/tenant-isolation-rls.md}). The
 * ADR's item 1 leaves the component open and states the requirement: the value must be set with
 * {@code set_config(..., true)}, which is {@code SET LOCAL}, and {@code SET LOCAL} outside a transaction
 * is not local at all. A servlet filter owns no transaction, so it can satisfy that only by owning the
 * request's transaction — which would change every transaction semantic in the tree — or by issuing
 * {@code SET LOCAL} on some other connection, which is a no-op dressed as enforcement. Installing it at
 * {@code doBegin} keeps transaction semantics exactly as they are and puts the statement on the
 * connection the queries will use.</p>
 *
 * <p><b>And why per boundary and not per request.</b> {@code ProfileLocationResolver.resolve} is
 * {@code @Transactional(propagation = NOT_SUPPORTED)} and calls a read-only service, so a scope installed
 * once per request disappears exactly there. This manager re-installs it on the inner transaction,
 * because the holder is thread-confined and still carries the request's scope. That case is pinned by
 * {@code TenantScopeBoundaryIntegrationTest}.</p>
 *
 * <p><b>H2 is a no-op, and the decision is taken once.</b> The default test profile runs H2 with Flyway
 * disabled and {@code ddl-auto=create-drop}, and the great majority of the suite runs there;
 * {@code set_tenant_scope} does not exist in H2, so an unconditional call would fail <b>every</b>
 * transaction of that suite with "function not found". H2 also has no policies to satisfy: the behaviour
 * that matters is measured on the PostgreSQL path, where it is measured for real. The question is asked of
 * the database once, through the pooled {@code DataSource}, and cached, so it costs nothing per transaction
 * (trap 1 of the T4 brief).</p>
 *
 * <p><b>An absent value costs no round trip.</b> The holder is nullable in both halves: a request with no
 * company segment states only the subject, and a platform thread with a company states no subject. The
 * missing half is left alone rather than set to {@code NULL} — a pooled connection that once set
 * {@code app.company_id} carries the empty string for the rest of its life, and {@code nullif(...,
 * '')} in the policy already filters every row with it (measured in record L10/L11, and trap 3 of the T4
 * brief). Setting it explicitly would be a query per transaction that changes no predicate.</p>
 */
public class TenantScopeJpaTransactionManager extends JpaTransactionManager {

    /** Both statements call the functions V52 owns: nothing here writes {@code set_config} by hand. */
    private static final String SET_TENANT_SCOPE = "select set_tenant_scope(cast(:company as uuid))";

    private static final String SET_USER_SCOPE = "select set_user_scope(cast(:subject as text))";

    /**
     * {@code null} until the first transaction. A boxed field rather than a primitive on purpose: "not
     * decided yet" and "decided false" are different facts, and only the first one costs a lookup.
     */
    private volatile Boolean installsScope;

    private final DataSource dataSource;

    public TenantScopeJpaTransactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        super(entityManagerFactory);
        this.dataSource = dataSource;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        var scope = TenantScope.current().orElse(null);
        // Decided BEFORE super.doBegin on purpose: the dialect probe may open its own connection, and a
        // failure there must happen while nothing is bound to this thread yet — a throw after the
        // EntityManager was bound leaves the holder behind and the next transaction on this thread fails
        // with "already value bound for key".
        var install = scope != null && installsScope();
        super.doBegin(transaction, definition);
        if (install) {
            install(scope);
        }
    }

    /**
     * Issues the scope statements through the EntityManager this transaction bound.
     *
     * <p>{@link EntityManagerFactoryUtils#getTransactionalEntityManager(EntityManagerFactory)} and not a
     * new {@code EntityManager}: the whole point is to run the statement on the connection the
     * transaction's queries will use, and a second EntityManager would get a second connection — a
     * {@code SET LOCAL} on somebody else's session, which is ADR decision 1's forbidden third shape.
     * {@code super.doBegin} has already created and bound that holder, which is why this runs after it.
     * </p>
     *
     * <p>{@code getSingleResult()} rather than {@code executeUpdate()}: both functions return
     * {@code void}, so PostgreSQL answers one row with a NULL column, and that is a query result and not
     * an update count.</p>
     */
    private void install(TenantScope.Scope scope) {
        EntityManager entityManager =
                EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory());
        if (entityManager == null) {
            // Cannot happen after super.doBegin bound the holder; returning is still the safe answer,
            // because a transaction whose scope could not be installed must read no tenant row rather
            // than fail with a different exception than the policy's zero rows.
            return;
        }
        if (scope.companyId() != null) {
            entityManager
                    .createNativeQuery(SET_TENANT_SCOPE)
                    .setParameter("company", scope.companyId())
                    .getSingleResult();
        }
        if (scope.subject() != null) {
            entityManager
                    .createNativeQuery(SET_USER_SCOPE)
                    .setParameter("subject", scope.subject())
                    .getSingleResult();
        }
    }

    /**
     * Whether this database has the scope functions at all, decided once per factory.
     *
     * <p>Asked of the <b>database</b>, through the pooled {@link DataSource}, and not of Hibernate's
     * dialect: the connection is opened once and returned immediately, it says exactly what T2's
     * migration ran against, and it costs nothing at all after the first transaction. Reading the dialect
     * from the {@code SessionFactory} was the first attempt and is recorded here because it failed for a
     * reason worth keeping: {@code EntityManagerFactory.unwrap(SessionFactory.class)} in this stack
     * returns a JDK proxy that implements the interface but not Hibernate's
     * {@code SessionFactoryImplementor}, and the cast to the SPI that owns {@code getJdbcServices()} blows
     * up inside {@code doBegin}.</p>
     *
     * <p><b>H2 and PostgreSQL are the two answers that matter</b>: H2 is the default test profile
     * ({@code jdbc:h2:mem:testdb}, Flyway disabled, {@code create-drop}) and has no
     * {@code set_tenant_scope} and no policies, so an unconditional call would fail every transaction of
     * the great majority of the suite. The behaviour that matters is measured on the PostgreSQL path (trap
     * 1 of the T4 brief).</p>
     */
    private boolean installsScope() {
        var decided = installsScope;
        if (decided == null) {
            decided = databaseHasScopeFunctions();
            installsScope = decided;
        }
        return decided;
    }

    private boolean databaseHasScopeFunctions() {
        try (var connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException cannotReadTheProduct) {
            throw new IllegalStateException(
                    "The tenant scope cannot be installed for a transaction: the database product could not be "
                            + "read, so there is no way to know whether set_tenant_scope exists.",
                    cannotReadTheProduct);
        }
    }
}
