package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.tenant.TenantIsolationStartupCheck;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The path that must not change: the default test stack boots even though it has no assertion query.
 *
 * <p><b>Why this needs its own case.</b> {@code application-test.properties} makes H2 the default stack
 * ({@code jdbc:h2:mem:testdb}, Flyway disabled, {@code ddl-auto=create-drop}) and sixty-odd suites boot
 * there. {@code unprotected_tenant_tables()} is a {@code V52} function that exists on PostgreSQL and
 * nowhere else, so a check that queried it unconditionally would fail <b>every</b> one of those contexts —
 * a build that breaks rather than a policy that fails. This case asserts the gate itself, not the colour
 * of the suite: the bean's own probe answers "not applicable", and invoking the check returns without
 * issuing the assertion query.</p>
 *
 * <p><b>And why the probe asks two questions instead of one.</b> H2 cannot answer the precise one — its
 * parser does not know {@code to_regprocedure}, so the probe would fail with a syntax error instead of
 * answering "no function" — which the second case pins. The product name is what the sibling
 * {@code TenantScopeJpaTransactionManager} already asks, and it is asked of the same pooled {@link
 * DataSource}, so the two components cannot disagree about which stack they are on.</p>
 */
@SpringBootTest
@DisplayName("The tenant isolation startup refusal on the H2 test stack")
class TenantIsolationStartupCheckH2Test {

    @Autowired
    private TenantIsolationStartupCheck check;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * The critical finding the native review admitted against the first version of the check, pinned.
     *
     * <p>{@code afterPropertiesSet} holds one pooled connection while it asks whether the assertion query
     * exists. The first version asked through a helper that opened its <b>own</b> connection, so on a
     * deployment whose pool is one connection ({@code maximum-pool-size=1}) the second
     * {@code getConnection()} waited for a permit the first one held: the application blocked until the pool
     * timeout and then failed on a schema that had no unprotected table at all. Counting the requests is
     * the honest observable: one request is what a pool of one can serve, and two is the deadlock.
     */
    @Test
    @DisplayName("the check asks the pool for one connection only, so a pool of one cannot deadlock")
    void theCheckAsksThePoolForOneConnectionOnly() {
        var requested = new AtomicInteger();
        var counting = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                requested.incrementAndGet();
                return dataSource.getConnection();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
        var checkUnderTest = new TenantIsolationStartupCheck(entityManagerFactory, counting);

        assertThatCode(checkUnderTest::afterPropertiesSet)
                .as("the probe must answer on the connection the check already holds: asking the pool for a "
                        + "second one blocks a maximum-pool-size=1 deployment until its timeout and then fails "
                        + "a startup that had nothing wrong")
                .doesNotThrowAnyException();
        assertThat(requested.get())
                .as("exactly one connection: the old shape requested two, and the second is the one that "
                        + "cannot be served while the first is open")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the probe answers \"not applicable\" on H2 and the check returns without querying")
    void theProbeAnswersNotApplicableOnH2() {
        assertThat(check.appliesToThisDatabase())
                .as("this is the answer the context's own startup check computed from the pooled DataSource; "
                        + "had it been true, this suite could not have started, because H2 has neither "
                        + "`set_tenant_scope` nor `unprotected_tenant_tables()`")
                .isFalse();

        assertThatCode(check::afterPropertiesSet)
                .as("so the guard is a no-op here: it returns before the assertion query, and the great "
                        + "majority of this module's suite keeps booting")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("H2 cannot answer the precise question, which is why the probe asks the product name first")
    void h2CannotAnswerThePreciseQuestion() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                        "select to_regprocedure('public.unprotected_tenant_tables()')", Object.class))
                .as("`to_regprocedure` is the precise question on PostgreSQL and an unknown function on H2: "
                        + "asking it unconditionally would fail every H2 context at startup")
                .isInstanceOf(DataAccessException.class)
                .hasMessageMatching("(?is).*to_regprocedure.*");
    }
}
