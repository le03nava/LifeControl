package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderTransportException;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A provider failure leaves <b>no row behind</b> on the stamp path: not the draft the attempt wrote before it called
 * the authority, and not an artefact — {@code S9} of {@code odd/tasks/fiscal-provider-failure-status.md}.
 *
 * <p><b>Why this suite is not {@code @Transactional}, and this is the trap it exists to avoid.</b> Every fiscal HTTP
 * suite in this tree is {@code @Transactional}, and each of the three entry points is a single
 * {@code @Transactional} service method ({@code FiscalDocumentStamper#stamp},
 * {@code FiscalDocumentCancellationService#cancel}, {@code FiscalCancellationReconciliationService#reconcile}). Inside
 * a transactional test the request <b>joins the test's transaction</b>, so the rollback {@code S9} is about never
 * happens at the boundary under test: the test's own rollback would discard the row afterwards, and the assertion
 * would either see the draft the failing request left or pass for a reason that has nothing to do with the
 * production transaction. Neither is evidence. Without the annotation the request owns its transaction, the unchecked
 * family leaves it, and the container rolls it back <b>for real</b> — which is the fact being measured.</p>
 *
 * <p><b>What it costs, and why that is the honest price.</b> The fixture's rows commit, so this suite has to remove
 * them itself ({@link #deleteTheCompanyItCreated()}) and states the deletion leaf-first, by company, exactly as
 * {@code TaxRateSaleAcceptanceTest} does. Two things cannot be deleted through the application role and are
 * deliberately not attempted: {@code fiscal_document}/{@code fiscal_document_line}/{@code fiscal_document_artifact}
 * refuse {@code DELETE} by grant and by trigger ({@code V108}), which is the invariant this suite asserts is never
 * needed — the case leaves none — and {@code status_transition} is append-only since {@code V67} and blocks nothing.
 * A teardown that fails therefore reports a database the case did not leave clean, which is what a failed assertion
 * is.</p>
 *
 * <p><b>Why the read is scoped and why the sale is read first.</b> {@code fiscal_document} is armed and
 * {@code FORCE}d, so an unscoped count answers <b>zero</b> whatever the table holds — an assertion that would pass
 * over the very row it is looking for. Every read below runs inside a transaction that states the company
 * ({@link TenantFixtures#inTenant}), and the sale's own count is read the same way as the positive control: if the
 * scope were inert, the sale would be invisible too and the case would fail instead of passing quietly. The sale is
 * also compared with itself before the request: a document is the sale's, so a request that touched the sale while
 * leaving no document would be a different defect, and the family's rollback is what keeps both untouched.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("A fiscal provider failure leaves no row behind (S9)")
class FiscalProviderFailureLeavesNoRowIntegrationTest extends AbstractPostgresIntegrationTest {

    /** {@code D2}: the one sentence the failing request answers with. */
    private static final String MESSAGE = "The fiscal authority failed to answer this request";

    private static final String ISSUE_URL = "/api/fiscal-documents";

    private static final String ATTEMPT = "provider-failure-no-row-2026-11-20-01";

    private static final String ENDPOINT = "https://pac.example.test/servicio/timbrado";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    /**
     * The context's own transaction manager, used for <b>one</b> transaction: the seed's.
     *
     * <p>{@code FiscalDocumentSeed} states its tenant with {@code set_tenant_scope} — {@code SET LOCAL} — inside the
     * transaction that is already open, so a seed outside a boundary would state nothing and the policy would refuse
     * its first insert. This suite has no test transaction to lend it, so it opens one, which is also what makes the
     * fixture's rows commit: the rollback the case asserts is then the request's.</p>
     */
    @Autowired
    private PlatformTransactionManager transactions;

    /** The stamping port, replaced so a case can make the authority fail; its default answer is the shared double. */
    @MockitoBean
    private FiscalStampProvider stampProvider;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithItsRowsCommitted() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();

        var seeded = new AtomicReference<FiscalDocumentSeed>();
        new TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        seeded.set(new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile()));
        seed = seeded.get();

        doAnswer(call -> FiscalStampProviderTestConfiguration.PROVIDER.stamp(call.getArgument(0)))
                .when(stampProvider)
                .stamp(any());
    }

    @AfterEach
    void deleteTheCompanyItCreated() {
        var company = seed == null ? null : seed.company();
        try {
            if (company == null) {
                // The fixture never stated its tenant: nothing is scoped to this suite to remove.
                return;
            }
            // The document tree cannot be deleted through this role and the case leaves none; the book and the
            // fixture's own rows can, and the company cannot go before them.
            TenantFixtures.deleteBook(company);
            TenantFixtures.inTenant(
                    company, () -> deleteTheFixtureTree().forEach(statement -> jdbc.update(statement, company)));
        } finally {
            TenantScope.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("S9: a provider failure after the draft was written leaves no document, no artefact and an "
            + "untouched sale")
    void aProviderFailureLeavesNoRowBehind() throws Exception {
        var saleBeforeTheRequest = sale();
        doThrow(unreachable()).when(stampProvider).stamp(any());

        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"salesOrderId\":\"" + seed.sale() + "\",\"attemptKey\":\"" + ATTEMPT + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(MESSAGE));

        // The positive control first: the scoped reads below do see the rows this company owns, so a zero is a
        // zero and not an inert scope.
        assertThat(countScopedToTheCompany("sales_orders"))
                .as("the scope this suite reads with is the one the policies filter by")
                .isEqualTo(1);
        assertThat(countScopedToTheCompany("fiscal_document"))
                .as("S9: the draft the stamp writes before it calls the authority is rolled back with the family")
                .isZero();
        assertThat(countScopedToTheCompany("fiscal_document_line"))
                .as("S9: and so are the lines the same draft wrote")
                .isZero();
        assertThat(countScopedToTheCompany("fiscal_document_artifact"))
                .as("S9: nothing was stamped, so there is no artefact to keep")
                .isZero();
        assertThat(sale())
                .as("S9: the sale is the document's source and is not this request's to write")
                .isEqualTo(saleBeforeTheRequest);
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    /** How many rows of one tenant table this company owns, read inside a transaction that states the company. */
    private long countScopedToTheCompany(String table) {
        return TenantFixtures.inTenant(
                seed.company(),
                () -> jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE company_id = ?", Long.class, seed.company()));
    }

    /** The seeded sale, whole, read the same way: every column of it is what the failing request must not move. */
    private Map<String, Object> sale() {
        return TenantFixtures.inTenant(
                seed.company(), () -> jdbc.queryForMap("SELECT * FROM sales_orders WHERE id = ?", seed.sale()));
    }

    /**
     * The fixture's own rows, leaf-first, every statement scoped by {@code company_id}.
     *
     * <p>{@code fiscal_document}, its lines and its artefact are absent on purpose: the app role holds no
     * {@code DELETE} on them and a trigger refuses it, which is the same invariant the case asserts. If a case ever
     * leaves one, the company's own delete fails on the foreign key and the teardown reports it.</p>
     */
    private static List<String> deleteTheFixtureTree() {
        var ordersOfThisCompany = "SELECT id FROM sales_orders WHERE company_id = ?";
        var countriesOfThisCompany = "SELECT id FROM company_countries WHERE company_id = ?";
        var regionsOfThisCompany =
                "SELECT id FROM company_regions WHERE company_country_id IN (" + countriesOfThisCompany + ")";
        return List.of(
                "DELETE FROM sales_order_items WHERE sales_order_id IN (" + ordersOfThisCompany + ")",
                "DELETE FROM sales_orders WHERE company_id = ?",
                "DELETE FROM customers WHERE company_id = ?",
                "DELETE FROM product_variants WHERE company_id = ?",
                "DELETE FROM products WHERE company_id = ?",
                "DELETE FROM company_stores WHERE company_id = ?",
                "DELETE FROM company_zones WHERE company_region_id IN (" + regionsOfThisCompany + ")",
                "DELETE FROM company_regions WHERE company_country_id IN (" + countriesOfThisCompany + ")",
                "DELETE FROM company_countries WHERE company_id = ?",
                "DELETE FROM companies WHERE id = ?");
    }

    /** The family's first shape: the connection was never answered. */
    private static FiscalStampProviderException unreachable() {
        return FiscalStampProviderTransportException.unreachable(
                ENDPOINT, Duration.ofSeconds(20), 4_000, new SocketTimeoutException("connect timed out"));
    }

    /** The caller's token, with the whole store chain the sale's store belongs to. */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject("suite:fiscal-provider-failure-no-row")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
