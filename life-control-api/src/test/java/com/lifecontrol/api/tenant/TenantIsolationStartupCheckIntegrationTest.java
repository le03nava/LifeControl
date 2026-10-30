package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.tenant.TenantIsolationStartupCheck;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The two answers ADR-0005's startup refusal gives on PostgreSQL: it passes on this tree, and it refuses a
 * table nobody listed — naming it.
 *
 * <p><b>Why the passing half needs a case at all.</b> The check runs during context refresh, so every
 * PostgreSQL suite in this JVM is implicitly a case of the guard passing: had it refused anything, none of
 * them would start. That is a strong but <i>negative</i> observation — the absence of a failure is also
 * what a check that never ran produces. So this suite asserts the bean's own verdict, and the assertion it
 * makes is only true if the check ran <b>after</b> {@code V52}: {@code to_regprocedure} answers {@code
 * null} on a schema that has no {@code unprotected_tenant_tables()}, and the probe's answer is cached for
 * the life of the bean, so the {@code true} below is the value the startup path itself computed. A hook
 * that ran before Flyway would have cached {@code false} and this case would say so.</p>
 *
 * <p><b>Why the refusing half can only be observed here.</b> The tree returns nothing — {@code V53} armed
 * all sixteen tables — so there is no unlisted {@code company_id} table to refuse. The case therefore
 * creates one, and it creates it <b>inside a transaction it rolls back</b>: the container is shared with
 * sixty-odd suites, and a table left behind would fail them. That forces the second observation too: the
 * check has to run on <b>that</b> connection, because a fresh pooled one cannot see an uncommitted DDL
 * statement. The same case reads the assertion query first as the control, so a refusal that never saw the
 * table cannot be mistaken for a table that was never there.</p>
 */
@SpringBootTest
@DisplayName("The tenant isolation startup refusal on PostgreSQL")
class TenantIsolationStartupCheckIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Created and rolled back per run: the rollback is what keeps the shared container clean. */
    private static final String UNLISTED_A = "t3_unlisted_company_id";

    private static final String UNLISTED_B = "t3_also_unlisted_company_id";

    private static final String ASSERTION_QUERY =
            "SELECT unprotected_tenant_tables::text AS table_name FROM unprotected_tenant_tables()";

    @Autowired
    private TenantIsolationStartupCheck check;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the check ran after V52 and this tree refuses nothing")
    void theCheckRanAfterTheMigrationsAndThisTreeRefusesNothing() {
        assertThat(check.appliesToThisDatabase())
                .as("the probe's answer, computed by the startup path itself and cached since: `true` means "
                        + "`to_regprocedure('public.unprotected_tenant_tables()')` found the function, which "
                        + "only exists once V52 has run")
                .isTrue();

        assertThatCode(check::afterPropertiesSet)
                .as("and running it again on a pooled connection refuses nothing: V53 armed the sixteen "
                        + "tables, which is why this case is the only one that has to be written down and "
                        + "every sibling PostgreSQL suite is an implicit copy of it")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unlisted company_id table is refused and the message names it — the case that would "
            + "have caught the six readers a slice too late")
    void anUnlistedCompanyIdTableIsRefusedAndTheMessageNamesIt() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                exec(connection, "CREATE TABLE " + UNLISTED_A + " (id uuid PRIMARY KEY, company_id uuid NOT NULL)");
                exec(connection, "CREATE TABLE " + UNLISTED_B + " (id uuid PRIMARY KEY, company_id uuid NOT NULL)");

                assertThat(unprotectedTables(connection))
                        .as("the control: the assertion query sees exactly these two tables on this very "
                                + "connection, so the refusal below is about them and not about a table that "
                                + "was never created")
                        .containsExactlyInAnyOrder(UNLISTED_A, UNLISTED_B);

                assertThatThrownBy(() -> check.assertNoUnprotectedTenantTable(connection))
                        .as("a table with company_id and no policy is readable by every tenant, so the "
                                + "application does not start: a log line would leave it serving them")
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(UNLISTED_A)
                        .hasMessageContaining(UNLISTED_B)
                        .as("and it says what to do, because the operator is the one who can do it")
                        .hasMessageContaining("apply_tenant_isolation");
            } finally {
                connection.rollback();
            }

            assertThat(unprotectedTables(connection))
                    .as("rolled back, so the container is left as this suite found it and no sibling suite "
                            + "starts in front of an unlisted table")
                    .isEmpty();
            assertThatCode(() -> check.assertNoUnprotectedTenantTable(connection))
                    .as("and the same check on the same connection now passes: the refusal was the table")
                    .doesNotThrowAnyException();
        }
    }

    private static List<String> unprotectedTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var statement = connection.prepareStatement(ASSERTION_QUERY);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        return tables;
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
