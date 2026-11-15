package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * A deployment that can stamp but cannot cancel: the endpoint answers <b>503</b> and nothing is written
 * ({@code T8d} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this needs a context of its own.</b> The refusal is the absence of a bean, and no case can state an
 * absence inside a context that registers one. This suite therefore imports the <b>stamping</b> doubles and nothing
 * else, which is the shape of a deployment whose {@code app.fiscal.provider.enabled} is off: the document the caller
 * wants cancelled exists and is stamped, so the 404 branch is not what answers — the missing provider is.</p>
 *
 * <p><b>What the status proves.</b> {@code 503} is the tree's answer for "this instance does not have the capability",
 * and it is a decision taken at the endpoint on purpose: the advice's last handler answers {@link Exception} with a
 * 500, so an annotated refusal would never reach the resolver that would have mapped it. The message names the
 * interface, which is what an operator configures.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("A cancellation in a deployment with no provider (T8d)")
class FiscalDocumentCancellationNotConfiguredIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "cancel-absent-2026-11-16-01";

    private static final String URL = "/api/fiscal-documents/%s/cancellation";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    private UUID stamped;

    @BeforeEach
    void seedAStampedDocument() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamped = stamper.stamp(seed.sale(), ATTEMPT).documentId();
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("T8d: with no cancellation provider registered the endpoint answers 503, names the interface the "
            + "deployment is missing, and writes no row")
    void withNoProviderTheEndpointAnswers503() throws Exception {
        var before = jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company());

        var message = mockMvc.perform(post(URL.formatted(stamped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"02\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                                .jwt(builder -> builder.subject("suite:fiscal-cancellation")
                                        .claim("company_id", seed.company().toString()))))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()))
                .andExpect(jsonPath("$.status").value(HttpStatus.SERVICE_UNAVAILABLE.value()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(message)
                .as("the refusal names the interface an operator has to register")
                .contains("FiscalCancellationProvider");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company()))
                .as("a capability the deployment does not have writes nothing")
                .isEqualTo(before);
    }
}
