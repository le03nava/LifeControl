package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.FiscalStampProviderTestConfiguration.InMemoryFiscalStampProvider;
import com.lifecontrol.api.fiscal.exception.FiscalStampRejectedException;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentArtifactRepository;
import com.lifecontrol.api.fiscal.service.FiscalDocumentSnapshotWriter;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reprint: {@code GET /api/fiscal-documents/{id}/xml} answers the bytes that were stamped, and nothing else
 * answers anything but a 404 ({@code S10} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions
 * {@code L15}(a)–(c) of the same record).
 *
 * <p><b>Why the assertion is the response and not the service.</b> {@code S10} is a statement about what a
 * caller receives: the XML the authority witnessed, byte for byte, as an attachment. So every case performs the
 * request through the real chain — the filter that states the tenant of a path that names none, the
 * {@code @PreAuthorize} of the two roles, and the controller — and the bytes are compared with what the provider
 * returned, never with a rebuilt document.</p>
 *
 * <p><b>Why the double is T6's and not a new one.</b> {@link InMemoryFiscalStampProvider} answers the request's
 * XML with the timbre <b>appended</b>, so "the reprint is the provider's rendering" and "the reprint is what the
 * builder rendered" are two different byte strings. A double that echoed the request would make the assertion
 * pass against either.</p>
 *
 * <p><b>Why the 404 cases are the ones that matter.</b> A reprint of a draft, of a refused attempt and of
 * another company's document are all {@code S10}'s negative half, and the last one is {@code L15}(c): the policy
 * hides the row, so "not found" is what the query <i>answers</i>; producing a 403 would need a second read
 * outside the policy, which is the leak the policy exists to close.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("Fiscal document reprint (S10)")
class FiscalDocumentXmlReprintHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt every case states, so a case's document is the one its attempt wrote. */
    private static final String ATTEMPT = "reprint-2026-11-15-01";

    /** A second attempt key, for the sale that already carries a draft. */
    private static final String SECOND_ATTEMPT = "reprint-2026-11-15-02";

    private static final String BASE_URL = "/api/fiscal-documents/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private FiscalDocumentSnapshotWriter writer;

    @Autowired
    private FiscalDocumentArtifactRepository artifactRepository;

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

    // ──────────────────────────────────────
    // S10 — the bytes that were stamped
    // ──────────────────────────────────────

    @Test
    @DisplayName("S10/L15(a)-(b): a stamped document reprints the very bytes the provider returned, as an XML "
            + "attachment named after the document's id when it states no folio")
    void aStampedDocumentReprintsTheProviderBytes() throws Exception {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);
        var expected =
                FiscalStampProviderTestConfiguration.PROVIDER.lastAnswerXml().getBytes(StandardCharsets.UTF_8);

        assertThat(expected)
                .as("the double's answer is provably not the request's XML")
                .isNotEqualTo(FiscalStampProviderTestConfiguration.PROVIDER
                        .lastRequest()
                        .xml()
                        .getBytes(StandardCharsets.UTF_8));

        var response = mockMvc.perform(
                        get(BASE_URL + stamp.documentId() + "/xml").with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                .andExpect(
                        header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(stamp.documentId() + ".xml")))
                .andReturn()
                .getResponse();

        assertThat(response.getContentType()).startsWith("application/xml");
        assertThat(response.getContentAsByteArray())
                .as("the artefact is the authority's copy, never a regeneration: a rebuilt document looks "
                        + "identical and is not the same bytes (L15(a))")
                .isEqualTo(expected);
        assertThat(artefactOf(stamp.documentId()).getBytes(StandardCharsets.UTF_8))
                .as("and what the response served is what the row keeps, byte for byte")
                .isEqualTo(response.getContentAsByteArray());
    }

    @Test
    @DisplayName("S10: a document that states a folio is served under it, so a human can tell two reprints apart")
    void aDocumentWithAFolioIsServedUnderIt() throws Exception {
        var draft = writer.createDraft(seed.sale(), ATTEMPT);
        // The folio is the schema's optional local convention (ADR-0007 decision 2) and no writer states one
        // yet, so the case states it the way the slice that prints it will: on the draft, before the stamp,
        // because V108 refuses every UPDATE of a stamped row.
        jdbc.update("UPDATE fiscal_document SET folio = ? WHERE id = ?", "F-0007", draft.id());
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);

        mockMvc.perform(get(BASE_URL + stamp.documentId() + "/xml").with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("F-0007.xml")));
    }

    // ──────────────────────────────────────
    // S10/L15(c) — the 404s
    // ──────────────────────────────────────

    @Test
    @DisplayName("S10/L15(c): a draft is a 404 — it has no artefact, and a reprint of one is a reprint of nothing")
    void aDraftIs404() throws Exception {
        var draft = writer.createDraft(seed.sale(), ATTEMPT);

        mockMvc.perform(get(BASE_URL + draft.id() + "/xml").with(token(seed.company())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S10/L15(c): a draft that somehow carries an artefact row is still a 404, because only a "
            + "stamped document has bytes the authority witnessed")
    void aDraftWithAnArtefactRowIsStill404() throws Exception {
        var draft = writer.createDraft(seed.sale(), ATTEMPT);
        // The schema does not couple the artefact to the status (L9(d) reads the primary key, not a constraint),
        // so the writer's discipline is not what the reader may rely on: the row is stated here to pin the
        // reader's own rule.
        jdbc.update(
                "INSERT INTO fiscal_document_artifact (fiscal_document_id, company_id, xml) VALUES (?, ?, ?)",
                draft.id(),
                seed.company(),
                "<cfdi:Comprobante/>");

        mockMvc.perform(get(BASE_URL + draft.id() + "/xml").with(token(seed.company())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S10/L15(c): a refused attempt leaves a draft, and a draft is a 404 — the authority returned no "
            + "bytes, so there is nothing to reprint")
    void aRefusedAttemptReprintsAs404() throws Exception {
        FiscalStampProviderTestConfiguration.PROVIDER.reject(new FiscalStampResult.Issue("CFDI40102", "RFC invalido"));

        assertThatThrownBy(() -> stamper.stamp(seed.sale(), ATTEMPT)).isInstanceOf(FiscalStampRejectedException.class);

        var document = jdbc.queryForObject(
                "SELECT id FROM fiscal_document WHERE company_id = ? AND attempt_key = ?",
                UUID.class,
                seed.company(),
                ATTEMPT);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document_artifact WHERE fiscal_document_id = ?",
                        Long.class,
                        document))
                .as("a rejection writes no artefact at all (S11)")
                .isZero();

        mockMvc.perform(get(BASE_URL + document + "/xml").with(token(seed.company())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S10/L15(c): a document of another company is a 404 and never a 403, because the policy hides "
            + "the row instead of admitting that it exists")
    void anotherCompanysDocumentIs404() throws Exception {
        var other = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        var foreign = stamper.stamp(other.sale(), ATTEMPT);

        // The scenario: the document exists, is stamped, and carries its artefact. Only the tenant differs.
        assertThat(artifactRepository.findByCompanyIdAndFiscalDocumentId(other.company(), foreign.documentId()))
                .isPresent();

        // Back to the caller's tenant, the way the request's own filter states it.
        TenantFixtures.stateTenantWithinTheOpenTransaction(seed.company(), jdbc);

        mockMvc.perform(get(BASE_URL + foreign.documentId() + "/xml").with(token(seed.company())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S10: the reprint is gated to lc-admin and lc-sales, so a caller with neither is refused")
    void aCallerWithoutEitherRoleIsRefused() throws Exception {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);

        mockMvc.perform(get(BASE_URL + stamp.documentId() + "/xml")
                        .with(jwt().jwt(builder -> builder.subject("suite:no-role")
                                .claim("company_id", seed.company().toString()))))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private String artefactOf(UUID documentId) {
        return artifactRepository
                .findByCompanyIdAndFiscalDocumentId(seed.company(), documentId)
                .map(artifact -> artifact.getXml())
                .orElse(null);
    }

    /**
     * The caller's token: the tenant in the one claim a company-less path reads, and the role the sale screens
     * already use. {@code lc-sales} and not {@code lc-admin} so the case proves the narrower of the two.
     */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject("suite:fiscal-reprint").claim("company_id", company.toString()));
    }
}
