package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing a document: {@code POST /api/fiscal-documents} turns a completed sale into a stamped document
 * (issue {@code #927}).
 *
 * <p><b>Why the case is the HTTP response and not the service.</b> {@code FiscalDocumentStamper} has been the only
 * way a document becomes {@code STAMPED} since {@code T6}, and its suite already proves what it does. What was
 * missing was a caller: nothing in {@code src/main} reached it, so the reprint and the cancellation could only
 * answer 404 in a deployed system. So every case here goes through the real chain — the filter that states the
 * tenant, the {@code @PreAuthorize} of the two roles and the controller — and asserts what a caller receives.</p>
 *
 * <p><b>Why the replay case is the important one.</b> {@code S11} is a statement about the authority, not about the
 * row: the same attempt must never stamp twice. The evidence is {@code stampedByThisAttempt} — the service sets it
 * from whether it called the provider — paired with a {@code 200} instead of a {@code 201}, so a caller can tell a
 * first stamp from a replay without inferring it from a side effect.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("Fiscal document issue (#927)")
class FiscalDocumentIssueHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ISSUE_URL = "/api/fiscal-documents";

    private static final String ATTEMPT = "issue-2026-11-16-01";

    private static final String SECOND_ATTEMPT = "issue-2026-11-16-02";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("#927: a completed sale is stamped and answers 201 with the authority's UUID and the bytes it "
            + "returned, and the row is STAMPED")
    void issuesADocumentAndAnswers201() throws Exception {
        var body = mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(seed.sale(), ATTEMPT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.salesOrderId").value(seed.sale().toString()))
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.attemptKey").value(ATTEMPT))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.uuidFiscal").isNotEmpty())
                .andExpect(jsonPath("$.stampedByThisAttempt").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var documentId = jdbc.queryForObject(
                "SELECT id FROM fiscal_document WHERE company_id = ? AND attempt_key = ?",
                UUID.class,
                seed.company(),
                ATTEMPT);

        assertThat(jdbc.queryForObject("SELECT status FROM fiscal_document WHERE id = ?", String.class, documentId))
                .as("the endpoint exposes the one transition T6 wrote, and it committed it")
                .isEqualTo("STAMPED");
        assertThat(jdbc.queryForObject(
                        "SELECT xml FROM fiscal_document_artifact WHERE fiscal_document_id = ?",
                        String.class,
                        documentId))
                .as("the artefact the row kept is the provider's answer and not the document that was sent, "
                        + "which is what S10 makes a reprint the authority's copy rather than a regeneration")
                .isNotNull()
                .isNotEqualTo(FiscalStampProviderTestConfiguration.PROVIDER
                        .lastRequest()
                        .xml())
                .isEqualTo(FiscalStampProviderTestConfiguration.PROVIDER.lastAnswerXml());
        assertThat(body)
                .as("and the response states the same bytes it kept, so a caller need not come back for them")
                .contains("\"xml\"");
    }

    @Test
    @DisplayName("#927/S11: replaying the same attempt answers 200 with the same document, and nobody is called "
            + "a second time")
    void replayingTheSameAttemptDoesNotStampTwice() throws Exception {
        var first = stamper.stamp(seed.sale(), ATTEMPT);

        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(seed.sale(), ATTEMPT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(first.documentId().toString()))
                .andExpect(jsonPath("$.uuidFiscal").value(first.uuidFiscal().toString()))
                .andExpect(jsonPath("$.stampedByThisAttempt").value(false));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND source_id = ?",
                        Long.class,
                        seed.company(),
                        seed.sale()))
                .as("one sale, one document: the replay wrote nothing")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("#927/S11: an absent or blank attempt key is a 400 that writes nothing, because a stamp without "
            + "one is the second stamp of the day")
    void ablankAttemptKeyIs400() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(seed.sale(), "  ")))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company()))
                .as("and the refusal ran before the first write")
                .isZero();
    }

    @Test
    @DisplayName("#927: a request that names no sale is a 400 from the binding layer, and nothing is stamped")
    void aRequestWithoutASaleIs400() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptKey\":\"" + ATTEMPT + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company()))
                .isZero();
    }

    @Test
    @DisplayName("#927: an unknown sale is a 404 and never a 500, because the refusal is the port's own family")
    void anUnknownSaleIs404() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(UUID.randomUUID(), ATTEMPT)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#927/S6: a sale that already has a live stamped document under another attempt is a 409, and "
            + "the authority is not asked twice")
    void aSecondAttemptForAStampedSaleIs409() throws Exception {
        var first = stamper.stamp(seed.sale(), ATTEMPT);

        mockMvc.perform(post(ISSUE_URL)
                        .with(token(seed.company()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(seed.sale(), SECOND_ATTEMPT)))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = 'STAMPED'",
                        Long.class,
                        seed.company()))
                .as("one live stamped document per sale, and the second attempt wrote nothing")
                .isEqualTo(1L);
        assertThat(first.uuidFiscal()).isNotNull();
    }

    @Test
    @DisplayName("#927: the endpoint is gated to lc-admin and lc-sales, so a caller with neither is refused and "
            + "nothing is stamped")
    void aCallerWithoutEitherRoleIsRefused() throws Exception {
        mockMvc.perform(post(ISSUE_URL)
                        .with(jwt().jwt(builder -> builder.subject("suite:no-role")
                                .claim("company_id", seed.company().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(seed.sale(), ATTEMPT)))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company()))
                .isZero();
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private static String payload(UUID salesOrderId, String attemptKey) {
        return "{\"salesOrderId\":\"" + salesOrderId + "\",\"attemptKey\":\"" + attemptKey + "\"}";
    }

    /**
     * The caller's token: the tenant in the one claim a company-less path reads, the store chain the sale belongs
     * to, and the narrower of the two roles the endpoint admits, so the case proves {@code lc-sales} is enough on
     * its own.
     *
     * <p><b>Why the chain and not only the company.</b> Issuing authorizes the caller for the store of the sale it
     * names, walking company &rarr; country &rarr; region &rarr; zone &rarr; store, so a token that states only the
     * company never says which store it may stamp in and is refused before anything is written.</p>
     */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject("suite:fiscal-issue")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
