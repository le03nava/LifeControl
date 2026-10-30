package com.lifecontrol.api.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.service.CompanyService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating a company states the tenant its own row will carry (ADR-0005's 2026-10-30 amendment; record
 * L16 of {@code odd/tasks/tenant-isolation-rls.md}).
 *
 * <p><b>Why creating needs an explicit scope at all.</b> {@code POST /api/companies} names no company in
 * its path, so there is no operation tenant to install, and a token with no {@code company_id} can never
 * satisfy a policy. The operation does know the value, though: {@code companies} is isolated by its own
 * {@code id}, so the row's tenant <b>is</b> its identity. The service therefore generates the id, states
 * it before the transaction that inserts the row, and the policy's {@code WITH CHECK} passes on a row
 * whose tenant is its own id. And the id has to be generated <b>before</b> the transaction: the boundary
 * hook reads the scope holder in {@code doBegin}, which is before the body of a {@code @Transactional}
 * method runs, so a scope stated inside one arrives one boundary too late.</p>
 *
 * <p><b>Two cases, because the claim has two halves and only one of them can be observed per stack.</b>
 * The policy half needs the policy armed, and a policy armed outside a transaction would leak into the
 * 60-odd suites sharing this container — so it runs on <b>one connection inside a transaction it rolls
 * back</b>, as {@code lifecontrol_app}: the row the creating tenant writes is readable by that tenant and
 * by nobody else, and a write naming another tenant is refused. The Java half — that the id exists before
 * the transaction and that the scope names it — runs through the real service, and cannot arm a policy for
 * the reason above: it observes the scope at the insert and the row the committed insert produced.</p>
 */
@SpringBootTest
@DisplayName("Creating a company states its own id as the tenant")
class CompanyTenantScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID COMPANY_A = UUID.fromString("0a000000-0000-4000-8000-00000000008a");
    private static final UUID COMPANY_B = UUID.fromString("0b000000-0000-4000-8000-00000000008b");
    private static final UUID COMPANY_C = UUID.fromString("0c000000-0000-4000-8000-00000000008c");

    /** The role the pool connects as in delivery: the one policies apply to (ADR decision 3). */
    private static final String APPLICATION_ROLE = "lifecontrol_app";

    private static final String CREATED_KEY = "TENANT-SCOPE-CREATE";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyService companyService;

    /**
     * The bean the service drives, spied so the case reads the scope the transaction <b>boundary</b> saw.
     *
     * <p>Spying the template and not the repository: what this case asserts is that the id exists before
     * the transaction begins and that it is the scope installed there, and {@code execute} is the last
     * moment at which that is observable from outside the service.</p>
     */
    @MockitoSpyBean
    private TransactionTemplate transactionTemplate;

    /** The scope the transaction boundary had when the service opened the transaction of the insert. */
    private final AtomicReference<TenantScope.Scope> scopeAtTheTransaction = new AtomicReference<>();

    /** One connection per case at most, opened with {@link #beginTransaction()} and closed below. */
    private Connection transaction;

    /** The row this suite committed, so the shared container does not accumulate tenants. */
    private UUID createdCompanyId;

    @AfterEach
    void cleanUp() throws SQLException {
        TenantScope.clear();
        if (transaction != null) {
            try {
                transaction.rollback();
                transaction.setAutoCommit(true);
            } finally {
                transaction.close();
                transaction = null;
            }
        }
        if (createdCompanyId != null) {
            // Leaf-first: the company's creation seeds the starter product catalog through
            // `CompanyCreatedEvent`, and `product_categories.company_id` is a foreign key. The shared
            // container must not accumulate a tenant whose categories other suites would count around.
            jdbcTemplate.update(
                    "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?",
                    createdCompanyId);
            jdbcTemplate.update(
                    "UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", createdCompanyId);
            jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", createdCompanyId);
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", createdCompanyId);
            createdCompanyId = null;
        }
    }

    @Test
    @DisplayName("the row the creating tenant writes is readable by it and by no other tenant")
    void theCreatedCompanyIsAReadableOnlyByItsOwnTenant() throws SQLException {
        var connection = beginTransaction();
        // The policy T5 will apply to the real table, and the key the ADR singles out: `companies` is
        // isolated by `id`, not by a `company_id` column it does not have.
        exec(connection, "SELECT apply_tenant_isolation('companies', 'tenant', 'id')");

        exec(connection, "SET LOCAL ROLE " + APPLICATION_ROLE);

        // The creator's transaction: the scope is the id the row will carry.
        exec(connection, "SELECT set_tenant_scope(?::uuid)", COMPANY_A);
        exec(
                connection,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES (?::uuid, ?, ?, ?)",
                COMPANY_A,
                "TENANT-SCOPE-A",
                "Tenant Scope A",
                "TSCO010101AB");

        assertThat(ids(connection, "SELECT id FROM companies WHERE id = ?::uuid", COMPANY_A))
                .as("the creating tenant reads the row it just wrote: its own id is the row's tenant, "
                        + "which is why the create path can state a scope at all")
                .containsExactly(COMPANY_A.toString());

        exec(connection, "SELECT set_tenant_scope(?::uuid)", COMPANY_B);
        assertThat(ids(connection, "SELECT id FROM companies WHERE id = ?::uuid", COMPANY_A))
                .as("and nobody else reads it: another tenant's scope finds no row, without a WHERE clause "
                        + "naming a company — that is the isolation, not the authorization check")
                .isEmpty();

        // Write side, still as the other tenant: a row whose id is not this transaction's tenant is
        // refused by the same predicate as WITH CHECK.
        var refusal = refusalInSavepoint(
                connection,
                "INSERT INTO companies (id, company_key, company_name, rfc) VALUES ('%s', 'TENANT-SCOPE-C', 'Tenant Scope C', 'TSCO010101CC')"
                        .formatted(COMPANY_C));

        assertThat(refusal.getSQLState())
                .as("SQLSTATE 42501: a cross-tenant insert is refused by the policy and not merely hidden")
                .isEqualTo("42501");
        assertThat(ids(connection, "SELECT id FROM companies WHERE id = ?::uuid", COMPANY_C))
                .as("and the refused row stored nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("the service generates the id before the transaction and states it as the scope")
    void theServiceStatesTheIdItsRowWillCarry() {
        doAnswer(invocation -> {
                    scopeAtTheTransaction.set(TenantScope.current().orElse(null));
                    return invocation.callRealMethod();
                })
                .when(transactionTemplate)
                .execute(any(TransactionCallback.class));

        var created = companyService.createCompany(new CompanyRequest(
                CREATED_KEY,
                "Tenant Scope Create",
                2,
                "Tenant Scope Create SA",
                "TSCE010101ABC",
                null,
                null,
                null,
                true,
                null));
        createdCompanyId = created.id();

        assertThat(scopeAtTheTransaction.get())
                .as("the scope of the transaction that inserted the row names the row's own id: a value "
                        + "generated at persist time would arrive after `doBegin` and the WITH CHECK would "
                        + "refuse the insert once `companies` is armed")
                .isEqualTo(new TenantScope.Scope(created.id(), null));
        assertThat(countOfTheCreatedRow())
                .as("and the row really carries that id — the entity accepts the value it is given instead "
                        + "of generating a second random one at persist time")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    private Connection beginTransaction() throws SQLException {
        transaction = dataSource.getConnection();
        transaction.setAutoCommit(false);
        return transaction;
    }

    private int countOfTheCreatedRow() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM companies WHERE id = ?", Integer.class, createdCompanyId);
    }

    private static List<String> ids(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static void exec(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }

    /**
     * Runs {@code sql} under a savepoint and returns the refusal it produced, rolling back to the
     * savepoint so the caller can keep reading the same transaction: PostgreSQL aborts the whole
     * transaction on an error, and what a refused write did <b>not</b> store is half the assertion.
     */
    private static SQLException refusalInSavepoint(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT before_refusal");
            SQLException refusal;
            try {
                statement.execute(sql);
                throw new AssertionError("the write was accepted and should have been refused: " + sql);
            } catch (SQLException refused) {
                refusal = refused;
            }
            statement.execute("ROLLBACK TO SAVEPOINT before_refusal");
            return refusal;
        }
    }
}
