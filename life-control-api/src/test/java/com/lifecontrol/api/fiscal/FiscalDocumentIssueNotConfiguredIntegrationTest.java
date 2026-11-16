package com.lifecontrol.api.fiscal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * A deployment with the fiscal capability switched off answers <b>503</b>, and says which capability is missing
 * (issue {@code #927}).
 *
 * <p><b>Why the assertion is a status and not an exception.</b> {@code FiscalStampProviderNotConfiguredException}
 * and {@code FiscalSealNotConfiguredException} both extend {@link IllegalStateException}, which the shared advice
 * answers with a <b>500</b>. "This installation does not invoice" is not a server fault: it is the answer an
 * operator must be able to read, and the controller's own handler is what states it — the same shape the
 * cancellation endpoint states for the same fact. Without this case the two would be one refactor apart from
 * quietly becoming 500s again.</p>
 *
 * <p><b>Why this context imports nothing.</b> Every other fiscal suite substitutes the provider and the sealer with
 * doubles; here their absence <i>is</i> the scenario, so {@code FiscalStampProviderTestConfiguration} is
 * deliberately not imported and the {@code ObjectProvider} in the stamper finds nothing.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Fiscal document issue without a configured provider (#927)")
class FiscalDocumentIssueNotConfiguredIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ISSUE_URL = "/api/fiscal-documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("#927: with no provider configured the answer is 503 and names the capability, not a 500")
    void anUnconfiguredDeploymentAnswers503() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesOrderId\":\"" + seed.sale() + "\",\"attemptKey\":\"issue-unconfigured\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(containsString("FiscalStampProvider")));
    }

    @Test
    @DisplayName("#927: and nothing is stamped, so a stack that cannot invoice issues no document")
    void anUnconfiguredDeploymentStampsNothing() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesOrderId\":\"" + seed.sale() + "\",\"attemptKey\":\"issue-unconfigured\"}"))
                .andExpect(status().isServiceUnavailable());

        // The claim is about what the deployment issued, not about what the transaction holds mid-flight: the
        // draft is the writer's and it is written before the capability is resolved, so inside one test transaction
        // the row exists and the exception marks the transaction rollback-only. What must be true — here and in
        // production — is that no document was stamped and no artefact was kept.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = 'STAMPED'",
                        Long.class,
                        seed.company()))
                .as("nothing was issued, which is what makes the 503 recoverable: enable the capability and retry")
                .isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document_artifact a"
                                + " JOIN fiscal_document d ON d.id = a.fiscal_document_id WHERE d.company_id = ?",
                        Long.class,
                        seed.company()))
                .as("and no artefact exists in this tenant, because the provider was never reached")
                .isZero();
    }

    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder ->
                        builder.subject("suite:fiscal-issue-unconfigured").claim("company_id", company.toString()));
    }
}
