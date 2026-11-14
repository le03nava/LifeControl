package com.lifecontrol.api.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The customer tenant boundary, observed at the three places it can fail: the HTTP read, the schema, and the
 * pointer a sale writes.
 *
 * <p><b>Why the requests go through the real chain.</b> {@code /api/customers} names no company, so the tenant
 * a read runs under is decided by {@code TenantScopeFilter} from the caller's single {@code company_id} claim —
 * faking that decision here would test the suite instead of the deployment. The tokens therefore carry the
 * claim and the chain states the scope exactly as it does in delivery.</p>
 *
 * <p><b>Why the fixtures are committed (and removed below).</b> These cases read through an HTTP request,
 * which borrows its own pooled connection: a row written inside a transaction this class rolls back would not
 * exist for the request. So the two companies and their customers are written through {@link TenantFixtures},
 * which states each company as its own tenant the way production does, and {@link #removeTheFixtures()} takes
 * them away in the order the foreign keys ask for.</p>
 *
 * <p><b>The schema cases are the point of slice A.</b> {@code V105} gives the table its tenant and its policy
 * and removes the walk-in placeholder; {@code V106} makes every pointer that can be composite composite. Both
 * are read back from {@code pg_catalog} here, because a migration that "ran" and a migration that produced the
 * structure are two different facts.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Customer tenant isolation")
class CustomerTenantIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The placeholder `V3` seeded and `V105` removes. */
    private static final UUID WALK_IN_PLACEHOLDER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SUBJECT = "kc-subject-customer-isolation";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Company A: the owner of the customer the other company must not see. */
    private UUID companyA;

    /** Company B: the caller that must not see it. */
    private UUID companyB;

    private UUID customerOfA;

    private UUID customerOfB;

    /** The one connection whose reads are not filtered, used for the questions a policy would answer with zero. */
    private Connection superuser;

    @BeforeEach
    void seedTwoCompaniesThatShareNothing() throws SQLException {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        companyA = TenantFixtures.insertCompany(
                jdbcTemplate,
                "CUSTOMER-ISO-A-" + suffix,
                "Customer Iso A",
                rfc("A" + suffix),
                TenantFixtures.SEED_CURRENCY);
        companyB = TenantFixtures.insertCompany(
                jdbcTemplate,
                "CUSTOMER-ISO-B-" + suffix,
                "Customer Iso B",
                rfc("B" + suffix),
                TenantFixtures.SEED_CURRENCY);

        customerOfA = TenantFixtures.insertCustomer(
                jdbcTemplate, companyA, UUID.randomUUID(), "Company A Customer " + suffix);
        customerOfB = TenantFixtures.insertCustomer(
                jdbcTemplate, companyB, UUID.randomUUID(), "Company B Customer " + suffix);

        superuser = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        superuser.setAutoCommit(false);
    }

    @AfterEach
    void removeTheFixtures() throws SQLException {
        try {
            if (superuser == null || companyA == null) {
                return;
            }
            superuser.rollback();
            superuser.setAutoCommit(true);
            try (var statement = superuser.createStatement()) {
                statement.execute("DELETE FROM customers WHERE company_id IN ('" + companyA + "', '" + companyB + "')");
                statement.execute("DELETE FROM companies WHERE id IN ('" + companyA + "', '" + companyB + "')");
            }
        } finally {
            if (superuser != null) {
                superuser.close();
                superuser = null;
            }
        }
    }

    // ──────────────────────────────────────
    // 1. S4: another company's customer is not in this company's list, and its id is a 404
    // ──────────────────────────────────────

    @Test
    @DisplayName("company B's list and search never name company A's customer")
    void theListAndTheSearchDoNotCrossTheBoundary() throws Exception {
        mockMvc.perform(get("/api/customers").with(token(companyB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(customerOfA.toString()))))
                .andExpect(jsonPath("$.content[*].name", not(hasItem(org.hamcrest.Matchers.startsWith("Company A")))))
                .andExpect(jsonPath("$.content[*].id", hasItem(customerOfB.toString())));

        mockMvc.perform(get("/api/customers").param("search", "Company A").with(token(companyB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("A's customer id is a 404 for B and a 200 for A")
    void anotherCompanysCustomerIsANotFound() throws Exception {
        mockMvc.perform(get("/api/customers/{id}", customerOfA).with(token(companyB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/customers/{id}", customerOfA).with(token(companyA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerOfA.toString()));
    }

    // ──────────────────────────────────────
    // 2. S1: the structure V105/V106 produced, read back from the catalog
    // ──────────────────────────────────────

    @Test
    @DisplayName("V105 left company_id required, the table armed and forced, and the unique key in place")
    void theTenantStructureIsWhatTheMigrationSaidItWouldBe() throws SQLException {
        assertThat(booleanOf("SELECT attnotnull FROM pg_attribute"
                        + " WHERE attrelid = 'customers'::regclass AND attname = 'company_id'"))
                .as("a tenant column that may be absent is a row no policy can place anywhere")
                .isTrue();

        assertThat(booleanOf("SELECT relrowsecurity FROM pg_class WHERE oid = 'customers'::regclass"))
                .as("ENABLE is what turns the policy on")
                .isTrue();
        assertThat(booleanOf("SELECT relforcerowsecurity FROM pg_class WHERE oid = 'customers'::regclass"))
                .as("FORCE is what stops the table's owner — Flyway — from being exempt from its own policy")
                .isTrue();

        assertThat(columnsOf("SELECT policyname || '|' || cmd FROM pg_policies"
                        + " WHERE schemaname = 'public' AND tablename = 'customers'"))
                .as("exactly one FOR ALL policy, from apply_tenant_isolation and from nothing else")
                .containsExactly("p_tenant_customers|ALL");

        assertThat(columnsOf("SELECT table_name::text || '|' || policy_kind || '|' || key_column"
                        + " FROM tenant_table WHERE table_name = 'customers'::regclass"))
                .as("the registration the startup check reads: listed and protected are one fact")
                .containsExactly("customers|tenant|company_id");

        assertThat(constraintColumns("uq_customers_company_id", "customers", "u"))
                .as("the key V106's composite pointer references")
                .containsExactly("company_id,id");

        assertThat(booleanOf("SELECT attnotnull FROM pg_attribute"
                        + " WHERE attrelid = 'sales_orders'::regclass AND attname = 'customer_id'"))
                .as("a counter sale states no receiver (S2)")
                .isFalse();

        assertThat(countOf("SELECT count(*) FROM customers WHERE id = '" + WALK_IN_PLACEHOLDER + "'"))
                .as("the fictitious walk-in customer is gone, and the read runs as the container's superuser — "
                        + "an unscoped read as the owner would answer zero rows under FORCE and report a pass "
                        + "for a row that is still there")
                .isZero();
    }

    @Test
    @DisplayName("the sale's pointer to a customer is (company_id, customer_id) -> (company_id, id), MATCH SIMPLE")
    void theSalesOrderPointerIsComposite() {
        assertThat(constraintColumns("sales_orders_customer_id_fkey", "sales_orders", "f"))
                .as("the local half names the tenant as well as the customer")
                .containsExactly("company_id,customer_id");

        assertThat(columnsOf("""
                        SELECT array_to_string(ARRAY(
                                   SELECT a.attname
                                     FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
                                     JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum
                                    ORDER BY k.ord), ',')
                               || '|' || c.confmatchtype::text
                               || '|' || c.confdeltype::text
                               || '|' || c.confupdtype::text
                          FROM pg_constraint c
                         WHERE c.contype = 'f'
                           AND c.conrelid = 'sales_orders'::regclass
                           AND c.conname = 'sales_orders_customer_id_fkey'
                        """))
                .as("the referenced half, and MATCH SIMPLE ('s'): a sale with customer_id NULL is not checked "
                        + "at all, which is what keeps a counter sale legal. The rules are the ones the id-only "
                        + "pointer had, so V106 copied them instead of choosing again")
                .containsExactly("company_id,id|s|a|a");
    }

    // ──────────────────────────────────────
    // 3. S5: the pointer is refused by the key, not by the policy
    // ──────────────────────────────────────

    @Test
    @DisplayName("a sale with no customer is accepted, and one naming another company's customer is refused")
    void theCompositeKeyAllowsNoCustomerAndRefusesAnotherCompanys() throws SQLException {
        var store = seedStoreOf(companyA);

        insertSale(store, null);

        var refusal = refusalInSavepoint(insertSaleStatement(store, customerOfB));

        assertThat(refusal.getSQLState())
                .as("a foreign key violation and not a policy refusal (42501): a referential check bypasses "
                        + "row-level security, which is exactly why the pointer itself has to carry the tenant")
                .isEqualTo("23503");
        assertThat(refusal.getMessage())
                .as("the message names the composite key, so an operator sees which pointer says no")
                .contains("sales_orders_customer_id_fkey");
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    private static RequestPostProcessor token(UUID claimedCompany) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject(SUBJECT).claim("company_id", claimedCompany.toString()));
    }

    private boolean booleanOf(String sql) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class));
    }

    private long countOf(String sql) throws SQLException {
        try (var statement = superuser.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private List<String> columnsOf(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * The columns one constraint covers, comma-joined in their stored order, so an assertion can name the whole
     * shape instead of counting columns.
     */
    private List<String> constraintColumns(String constraintName, String table, String type) {
        return jdbcTemplate.queryForList("""
                SELECT array_to_string(ARRAY(
                           SELECT a.attname
                             FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                            ORDER BY k.ord), ',')
                  FROM pg_constraint c
                 WHERE c.contype = ?
                   AND c.conrelid = ?::regclass
                   AND c.conname = ?
                """, String.class, type, table, constraintName);
    }

    private static String rfc(String suffix) {
        return ("RFC" + suffix).toUpperCase();
    }

    /** A store of one company, with the hierarchy the composite keys under it require. */
    private UUID seedStoreOf(UUID company) throws SQLException {
        var country = readUuid("SELECT id FROM countries WHERE country_code = 'MX'");
        var countryRow = UUID.randomUUID();
        var region = UUID.randomUUID();
        var zone = UUID.randomUUID();
        var store = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 6);

        try (var statement = superuser.createStatement()) {
            statement.execute("INSERT INTO company_countries (id, company_id, country_id) VALUES ('" + countryRow
                    + "', '" + company + "', '" + country + "')");
            statement.execute("INSERT INTO company_regions (id, company_id, company_country_id, region_code,"
                    + " region_name) VALUES ('" + region + "', '" + company + "', '" + countryRow + "', 'R" + suffix
                    + "', 'Iso Region')");
            statement.execute("INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)"
                    + " VALUES ('" + zone + "', '" + company + "', '" + region + "', 'Z" + suffix + "', 'Iso Zone')");
            statement.execute("INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)"
                    + " VALUES ('" + store + "', '" + company + "', '" + zone + "', 'Iso Store', true)");
        }
        return store;
    }

    private void insertSale(UUID store, UUID customer) throws SQLException {
        try (var statement = superuser.createStatement()) {
            statement.execute(insertSaleStatement(store, customer));
        }
    }

    /**
     * One sale of company A, at head, in the shape its writer leaves: the identity in parts and the three money
     * parts {@code ck_order_total} adds up. The customer is the parameter under test.
     */
    private String insertSaleStatement(UUID store, UUID customer) {
        var id = UUID.randomUUID();
        var number = "SO-CISO-" + id.toString().substring(0, 8);
        return "INSERT INTO sales_orders (id, order_number, customer_id, company_store_id, order_date, status,"
                + " company_id, doc_type, seq_period, seq_value, doc_number, currency, subtotal, tax_total,"
                + " total_amount) VALUES ('" + id + "', '" + number + "', "
                + (customer == null ? "NULL" : "'" + customer + "'") + ", '" + store + "', '2026-11-20 10:00:00',"
                + " 'DRAFT', '" + companyA + "', 'SALES_ORDER', '202611', " + nextSequence() + ", '" + number
                + "', 'MXN', 100.0000, 0.0000, 100.0000)";
    }

    /** A value nobody else in this case claims, so the document identity's unique key is not what refuses. */
    private long nextSequence() {
        return System.nanoTime() % 1000000 + 1;
    }

    private UUID readUuid(String sql) throws SQLException {
        try (var statement = superuser.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getObject(1, UUID.class);
        }
    }

    /**
     * Runs {@code sql} under a savepoint and returns the refusal it produced, rolling back to the savepoint so
     * the case can keep reading. PostgreSQL aborts the whole transaction on a refused statement, so without the
     * savepoint there would be nothing left to ask.
     */
    private SQLException refusalInSavepoint(String sql) throws SQLException {
        try (var statement = superuser.createStatement()) {
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
