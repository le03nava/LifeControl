package com.lifecontrol.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.tenant.TenantScope;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one path that must not change: a transaction on the tree's default test stack starts normally even
 * while a tenant scope is stated.
 *
 * <p><b>Why this needs its own case.</b> {@code application-test.properties} makes H2 the default stack
 * ({@code jdbc:h2:mem:testdb}, Flyway disabled, {@code ddl-auto=create-drop}) and the great majority of
 * this module's suite runs there. H2 has no {@code set_tenant_scope} and no policies, so an installer that
 * issued the statement unconditionally would fail <b>every</b> transaction of that suite with "function
 * not found" — the trap that breaks the build rather than a policy. The gate is one cached decision, and
 * this case is what keeps it honest: the holder is <b>set</b> here on purpose. A test that ran with no
 * scope would pass for the wrong reason — the installer skips an absent scope anyway, so it would prove
 * nothing about the dialect gate.</p>
 *
 * <p>The scope's real behaviour is measured on the PostgreSQL path
 * ({@code TenantScopeBoundaryIntegrationTest}), because that is the only stack where a policy exists at
 * all. This case is the other half of the same decision: the dialect gate is not a switch that turned the
 * feature off everywhere.</p>
 */
@SpringBootTest
@DisplayName("Tenant scope on the H2 test stack")
class TenantScopeH2NoOpTest {

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000002a");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTheThread() {
        TenantScope.clear();
    }

    @Test
    @DisplayName("a transaction begins with a scope stated and issues no scope function")
    void aTransactionBeginsWithAScopeStated() {
        TenantScope.set(COMPANY_A, "kc-subject-h2");

        var active = new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the boundary hook ran: this is a real transaction on the H2 stack")
                    .isTrue();
            return jdbcTemplate.queryForObject("select count(*) from companies", Integer.class);
        });

        assertThat(active)
                .as("and it completed: an unconditional set_tenant_scope would have failed the begin with "
                        + "\"Function SET_TENANT_SCOPE not found\", because H2 has no such function")
                .isNotNull();
    }
}
