package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.FiscalCancellationProviderTestConfiguration.InMemoryFiscalCancellationProvider;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/fiscal-documents/{id}/cancellation}: the three answers a caller can receive, and the guard in
 * front of them ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, {@code T8d}).
 *
 * <p><b>Why the assertion is the response and not the service.</b> {@code L20}(d) is a statement about the
 * <b>status</b> a caller gets: a granted cancellation is {@code 201} and a document that was written, and a request
 * the authority has not decided is {@code 202} and a document that was <b>not</b> — and since {@code V113} the row
 * exists all the same, as a {@code CANCELLATION_PENDING} one the response deliberately does not echo
 * ({@code L2}(g)), so a case reads the state off the database beside the code off the body. Only a real request
 * through the chain — the filter that states the tenant of a path that names none, the {@code @PreAuthorize} of the
 * two roles, the binding of the body, and the controller — can prove which of the two a caller is looking at.</p>
 *
 * <p><b>Why the motive is a code in the body and an enum in the service.</b> The body states what the authority
 * spells ({@code "02"}), so a case that sent the enum's name would be testing this tree's vocabulary instead of the
 * one on the wire; and a code that is not one of the four is the 400 the contract asks to name the field.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Fiscal document cancellation endpoint (S7, T8d)")
class FiscalDocumentCancellationHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "cancel-http-2026-11-16-01";

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
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        FiscalCancellationProviderTestConfiguration.SEALER.reset();
    }

    @AfterEach
    void clearTheIdentity() {
        TenantScope.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S7: a granted cancellation is a 201 carrying the row it wrote, the cancelled document, the motive, "
            + "the state and the authority's code")
    void aGrantedCancellationIs201WithTheRow() throws Exception {
        var body = mockMvc.perform(
                        cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelledDocumentId").value(stamped.toString()))
                .andExpect(jsonPath("$.motive").value("02"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusUuid").value(InMemoryFiscalCancellationProvider.CANCELLED_STATUS_UUID))
                .andReturn();

        var cancellationDocumentId = idOf(body);
        assertThat(jdbc.queryForObject(
                        "SELECT cancels_fiscal_document_id FROM fiscal_document WHERE id = ?",
                        UUID.class,
                        cancellationDocumentId))
                .as("the id the response carries is the row that names the cancelled document")
                .isEqualTo(stamped);
    }

    @Test
    @DisplayName("S2/S3: a request the authority has not decided is a 202 with its code and exactly one "
            + "CANCELLATION_PENDING row, and no CANCELLED row")
    void anInProcessAnswerIs202WithOnePendingRow() throws Exception {
        FiscalCancellationProviderTestConfiguration.PROVIDER.answerWith(
                InMemoryFiscalCancellationProvider.Answer.IN_PROCESS);
        var before = cancellationCount();

        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancellationDocumentId").value(nullValue()))
                .andExpect(jsonPath("$.statusUuid").value(InMemoryFiscalCancellationProvider.IN_PROCESS_STATUS_UUID));

        assertThat(cancellationCount())
                .as("S3: the submission is remembered — one cancellation row more than before, and it is the "
                        + "pending one")
                .isEqualTo(before + 1);
        assertThat(statusCount("CANCELLATION_PENDING")).isEqualTo(1);
        assertThat(statusCount("CANCELLED"))
                .as("S2: a submitted request is not a cancellation, so the wire writes nothing that says CANCELLED")
                .isZero();

        var pending = pendingRow();
        assertThat(pending.get("cancels_fiscal_document_id")).isEqualTo(stamped);
        assertThat(pending.get("cancellation_status_uuid"))
                .as("and the row states the code the response echoed: 211, the authority's own (L2(g))")
                .isEqualTo(InMemoryFiscalCancellationProvider.IN_PROCESS_STATUS_UUID);
        assertThat(pending.get("cancellation_settled_at"))
                .as("L2(d): nothing has settled it — a submission waits for the reconciliation")
                .isNull();
    }

    @Test
    @DisplayName("S7/L20(f): motive 01 without a substituting folio is a 400 through the wire, and the authority is "
            + "never called")
    void motive01WithoutAFolioIs400() throws Exception {
        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"01\"}"))
                .andExpect(status().isBadRequest());

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    @Test
    @DisplayName("S7: a motive code that is not one of the authority's four is a 400 that names the field")
    void anUnknownMotiveIs400NamingTheField() throws Exception {
        var body = mockMvc.perform(
                        cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"05\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("a client marks the control from the field map, not from the prose")
                .contains("\"motive\"")
                .contains("05");
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    @Test
    @DisplayName("S7: the endpoint is gated to lc-admin and lc-sales, so a caller with neither is refused")
    void aCallerWithoutEitherRoleIsRefused() throws Exception {
        mockMvc.perform(cancellation(stamped)
                        .with(jwt().jwt(builder -> builder.subject("suite:no-role")
                                .claim("company_id", seed.company().toString())))
                        .content("{\"motive\":\"02\"}"))
                .andExpect(status().isForbidden());

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("a caller without the role never reaches the authority")
                .isZero();
    }

    @Test
    @DisplayName("S7: a document that is already cancelled is a 409 through the wire")
    void anAlreadyCancelledDocumentIs409() throws Exception {
        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(cancellation(stamped).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isConflict());

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .as("the second request was refused before the authority was called")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("S7: a document of another company is a 404 and never a 403, because the policy hides it")
    void anotherCompanysDocumentIs404() throws Exception {
        var other = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        // One UUID per document: uq_fiscal_document_uuid_fiscal is unique across the table, and this transaction
        // already stamped the caller's document.
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(UUID.randomUUID());
        var foreign = stamper.stamp(other.sale(), ATTEMPT).documentId();
        TenantFixtures.stateTenantWithinTheOpenTransaction(seed.company(), jdbc);

        mockMvc.perform(cancellation(foreign).with(token(seed.company())).content("{\"motive\":\"02\"}"))
                .andExpect(status().isNotFound());

        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private MockHttpServletRequestBuilder cancellation(UUID documentId) {
        return post(URL.formatted(documentId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
    }

    /** The id of the row the response wrote, read from the field the contract names. */
    private static UUID idOf(MvcResult result) throws Exception {
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.cancellationDocumentId");
        return UUID.fromString(id);
    }

    private long cancellationCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND cancels_fiscal_document_id IS NOT NULL",
                Long.class,
                seed.company());
    }

    /** How many rows of one state this company holds, which is what a row-counting case asserts on (S8). */
    private long statusCount(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND status = ?",
                Long.class,
                seed.company(),
                status);
    }

    /** The one pending cancellation row of this company, read whole: the state, the pointer and the answer columns. */
    private Map<String, Object> pendingRow() {
        return jdbc.queryForMap(
                "SELECT * FROM fiscal_document WHERE company_id = ? AND status = 'CANCELLATION_PENDING'",
                seed.company());
    }

    /**
     * The caller's token: the tenant in the one claim a company-less path reads, the store chain the document's
     * sale belongs to, and the role the sale screens already use. {@code lc-sales} and not {@code lc-admin} so the
     * case proves the narrower of the two.
     *
     * <p><b>Why the chain and not only the company.</b> Cancelling authorizes the caller for the store the document
     * came from, walking company &rarr; country &rarr; region &rarr; zone &rarr; store, so a token that states only
     * the company is refused before the authority is called — which would turn every case here into the same 403.
     * The foreign-document case keeps the caller's own chain with the caller's own company: the policy hides the
     * row, so it answers a 404 before any store is verified.</p>
     */
    private RequestPostProcessor token(UUID company) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject("suite:fiscal-cancellation")
                        .claim("company_id", company.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
