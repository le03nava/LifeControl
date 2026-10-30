package com.lifecontrol.api.common.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * ADR-0005's startup refusal: the application <b>does not start</b> when a table carrying a {@code
 * company_id} is missing from the {@code tenant_table} list.
 *
 * <p><b>What it protects.</b> A table with a {@code company_id} and no policy is not a table with a weaker
 * policy — it is a table every tenant reads in full, with no query and no log line saying so. That is the
 * failure mode the ADR exists to remove, and it is exactly the failure the compiler cannot see: the
 * sixteenth tenant table arrives in a migration somebody wrote at the end of a long day, and nothing else
 * in the tree would notice until a customer noticed first. So this is a refusal and not a warning: a log
 * line would leave the deployment serving every tenant's rows while looking healthy, and the only moment
 * the omission is still cheap to fix is before the process accepts a request.</p>
 *
 * <p><b>The assertion already exists in SQL, and this class is only the wiring.</b> {@code
 * V52__tenant_isolation_machinery.sql} defines {@code unprotected_tenant_tables()}: every table in {@code
 * public} with an effective {@code company_id} column that has no row in {@code tenant_table}. There is no
 * second definition of "unlisted" in Java — a second definition is a second thing to forget. Since {@code
 * V53} armed the sixteen tables the query returns the empty set on this tree, which is why this check lands
 * last (record L9 and L21 of {@code odd/tasks/tenant-isolation-rls.md}) and why its own suite has to
 * create an unlisted table to observe a refusal at all.</p>
 *
 * <p><b>Why {@link #afterPropertiesSet} orders after Flyway, and why that ordering is the whole
 * requirement.</b> This bean takes the JPA {@link EntityManagerFactory} as a constructor argument and never
 * uses it: the dependency <i>is</i> the ordering. Spring Boot makes {@code entityManagerFactory} depend on
 * database initialization ({@code FlywayMigrationInitializer}), so requiring it here means Spring cannot
 * create this bean before the migrations of the very schema it is asserting. The two wrong answers a
 * differently placed check would produce are both silent: a check that ran before Flyway would refuse every
 * <b>fresh</b> deployment — the schema is empty, so every table it looks for is missing — and would pass a
 * <b>broken</b> one on its next restart, because by then the tables exist and {@code to_regprocedure}
 * answers {@code null} for a question about a database that has not yet been migrated. Failing during
 * context refresh, and not from a {@code SpringApplication.run} runner, is the second half of the same
 * choice: the refusal happens before the servlet container is started, so no request is ever served by a
 * process that has not passed this check.</p>
 *
 * <p><b>It is a no-op where the assertion query does not exist, and the database is asked, not the
 * dialect.</b> {@code application-test.properties} makes H2 the default test stack with Flyway disabled,
 * and sixty-odd suites boot there; H2 has no {@code unprotected_tenant_tables()} and no policies, so an
 * unconditional call would fail every one of those contexts with "function not found" — a build that
 * breaks instead of a policy that fails. The question is asked once, through the pooled {@link DataSource},
 * and cached for the life of the bean: the same shape {@link
 * TenantScopeJpaTransactionManager#databaseHasScopeFunctions()} uses one class over, so the two components
 * cannot disagree about which stack they are on. The probe measures two things, and the second is the
 * precise one: the product name (H2's parser does not know {@code to_regprocedure}, so on H2 the precise
 * question cannot even be asked), then {@code to_regprocedure('public.unprotected_tenant_tables()')} — read
 * rather than a call that fails, so a PostgreSQL database without {@code V52} is answered "no assertion
 * query" instead of being reported as broken.</p>
 *
 * <p><b>The refusal reports the set, never a count.</b> "One or more tables are unprotected" sends the
 * operator to {@code psql} to look up names this process already read, and a name is the only form of the
 * fact that can be acted on: it is the argument of the {@code apply_tenant_isolation} call the fix
 * needs.</p>
 */
@Component
public class TenantIsolationStartupCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TenantIsolationStartupCheck.class);

    /**
     * The precise question, and the reason it is a question about the catalog and not a call to the
     * function: a probe that calls what it is probing cannot tell "the function is absent" from "the
     * function failed".
     */
    private static final String ASSERTION_QUERY_PROBE =
            "select to_regprocedure('public.unprotected_tenant_tables()') is not null";

    /** The assertion itself, exactly the query {@code V52} ships as its documented consumer. */
    private static final String UNPROTECTED_TABLES =
            "select unprotected_tenant_tables::text as table_name from unprotected_tenant_tables()";

    private final DataSource dataSource;

    /**
     * {@code null} until the first question is asked. A boxed field rather than a primitive for the same
     * reason its sibling gives: "not decided yet" and "decided no" are different facts, and only the first
     * one costs a round trip.
     */
    private volatile Boolean assertionApplies;

    /**
     * @param entityManagerFactory required and deliberately never stored: depending on the JPA factory is
     *     what makes Spring create this bean after the schema the migrations build (see the class javadoc),
     *     and holding it would suggest this class reads through JPA, which it must not — the assertion is
     *     plain SQL on a connection the caller owns
     * @param dataSource the pooled {@link DataSource} the probe is asked of and the connection the startup
     *     assertion runs on
     */
    public TenantIsolationStartupCheck(EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        Objects.requireNonNull(entityManagerFactory, "the EntityManagerFactory is what orders this check after Flyway");
        this.dataSource = Objects.requireNonNull(dataSource, "the pooled DataSource the assertion runs on");
    }

    /** Runs the assertion once, on a pooled connection, and ends the startup when it finds a table. */
    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            assertNoUnprotectedTenantTable(connection);
        } catch (SQLException cannotAsk) {
            throw new IllegalStateException(
                    "ADR-0005 cannot assert that every company_id table carries its policy: the database did not "
                            + "answer unprotected_tenant_tables(), so there is no way to know whether this "
                            + "deployment would serve every tenant's rows.",
                    cannotAsk);
        }
    }

    /**
     * Refuses every unlisted {@code company_id} table visible on {@code connection}, or returns.
     *
     * <p>Takes the connection instead of opening one because the assertion is about <b>this</b> session's
     * view of the catalog: uncommitted DDL is invisible to any other connection, and that is the only way a
     * suite can observe the refusal on a tree that has none. In production the caller is {@link
     * #afterPropertiesSet}, which passes a pooled connection.</p>
     *
     * @throws IllegalStateException naming every unprotected table, aggregated, when the assertion query
     *     returns at least one row
     */
    public void assertNoUnprotectedTenantTable(Connection connection) throws SQLException {
        if (!appliesToThisDatabase()) {
            log.debug("ADR-0005: this database has no unprotected_tenant_tables(), so the startup assertion does "
                    + "not apply");
            return;
        }
        List<String> unprotected = unprotectedTenantTables(connection);
        if (!unprotected.isEmpty()) {
            throw new IllegalStateException(refusal(unprotected));
        }
        log.info("ADR-0005: every company_id table in schema public is under row-level security");
    }

    /**
     * Whether this database has the assertion query at all, decided once per bean and cached.
     *
     * <p>Public because it is the fact the startup path observed and a suite has to be able to state it:
     * "the guard did not fail" is also what a guard that never ran produces, so a passing PostgreSQL suite
     * asserts this {@code true} — which is only true if the check ran after {@code V52} — and an H2 suite
     * asserts it {@code false}.</p>
     */
    public boolean appliesToThisDatabase() {
        Boolean decided = assertionApplies;
        if (decided == null) {
            decided = hasAssertionQuery();
            assertionApplies = decided;
        }
        return decided;
    }

    private boolean hasAssertionQuery() {
        try (Connection connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                // The precise question cannot even be parsed here (H2 has no to_regprocedure), and this is
                // not a policy question anyway: without PostgreSQL there are no policies to be missing.
                return false;
            }
            try (var statement = connection.prepareStatement(ASSERTION_QUERY_PROBE);
                    ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException cannotAsk) {
            throw new IllegalStateException(
                    "ADR-0005 cannot decide whether this database has unprotected_tenant_tables(): the "
                            + "database product could not be read, so there is no way to know whether the "
                            + "assertion applies and the startup check cannot be skipped safely.",
                    cannotAsk);
        }
    }

    private static List<String> unprotectedTenantTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var statement = connection.prepareStatement(UNPROTECTED_TABLES);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static String refusal(List<String> unprotected) {
        String names =
                unprotected.stream().map(table -> "  - " + table).collect(Collectors.joining(System.lineSeparator()));
        return """
                ADR-0005 refuses to start: %d table(s) in schema public carry a company_id column and have no \
                entry in tenant_table, so PostgreSQL filters none of them and every tenant reads every row:%n\
                %s%n\
                Fix it in a migration, and not by editing this message: call apply_tenant_isolation once per \
                table above (SELECT apply_tenant_isolation('%s');), which enables and forces row-level \
                security, creates the table's p_tenant_<table> policy and records it in tenant_table. If a \
                table is deliberately not a tenant table, record that decision in tenant_table and in \
                odd/tasks/tenant-isolation-rls.md instead. Nothing here is a warning: the application does \
                not start on a table it cannot isolate.%n\
                """.formatted(unprotected.size(), names, unprotected.get(0));
    }
}
