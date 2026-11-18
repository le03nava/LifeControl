package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.service.FiscalDocumentCancellationService;
import com.lifecontrol.api.fiscal.service.FiscalDocumentSnapshotWriter;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading a sale's fiscal document: {@code GET /api/fiscal-documents/by-sale/{salesOrderId}} answers the document
 * that sale produced, and nothing else answers anything but a 204 or a 404 (issue {@code #947}, specs {@code S1}–{@code S9}
 * of {@code odd/tasks/fiscal-document-read.md}).
 *
 * <p><b>Why every case is the response and not the service.</b> Six of the nine specs are statements about what a caller
 * receives — a status, a body, a refusal by role — and the seventh is a statement about the tenant the policy states
 * before the service runs. So each case performs the request through the real chain: the filter that states the
 * caller's single {@code company_id} as the operation's tenant, the {@code @PreAuthorize} of the two sale roles, and
 * the controller.</p>
 *
 * <p><b>Why "not invoiced" is a 204 and not a 404.</b> {@code S2}: a sale that was never invoiced is a state the screen
 * renders — it is the state in which the screen offers the issue action — and the app toasts every failed request
 * ({@code shared/data/error-interceptor.ts:12-27}), so a 404 would raise a red banner over a normal sale. The 204 is
 * what makes "not yet" distinguishable from "gone".</p>
 *
 * <p><b>Why the cancelled case is the one that cannot be inferred.</b> {@code S3}: {@code V108}'s append-only trigger
 * refuses every {@code UPDATE} of a stamped row, so a cancellation <b>inserts</b> a copy naming it. A reader that
 * looked only for {@code status = 'CANCELLED'} would answer 204 to a sale the authority did cancel, and a reader that
 * reported the copy as the document would hand a reprint the wrong id. Both halves are asserted.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Reading a sale's fiscal document (S1-S9)")
class FiscalDocumentReadHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt the stamped cases state, so a case reads the document its own attempt wrote. */
    private static final String ATTEMPT = "read-2026-11-17-01";

    private static final String SUBJECT = "read-2026-11-17";

    private static final String BASE_URL = "/api/fiscal-documents/by-sale/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private FiscalDocumentSnapshotWriter writer;

    @Autowired
    private FiscalDocumentCancellationService cancellationService;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    @Autowired
    private EntityManager entityManager;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────
    // S2 — never invoiced is a state, not a failure
    // ──────────────────────────────────────

    @Test
    @DisplayName("S2: a sale that no attempt ever touched answers 204 with no body, so a client renders 'not yet' "
            + "instead of a failure")
    void aSaleWithoutAnyDocumentAnswersNoContent() throws Exception {
        mockMvc.perform(get(BASE_URL + seed.sale()).with(token(seed.company())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("S2: a draft is not a document — an attempt that never reached the authority answers 204, "
            + "because nothing was stamped")
    void aDraftIsNotADocument() throws Exception {
        writer.createDraft(seed.sale(), ATTEMPT);

        mockMvc.perform(get(BASE_URL + seed.sale()).with(token(seed.company()))).andExpect(status().isNoContent());
    }

    // ──────────────────────────────────────
    // S1 — the document a sale produced
    // ──────────────────────────────────────

    @Test
    @DisplayName("S1: a stamped document is readable by sale alone, and states its identity without the XML, "
            + "which the reprint carries")
    void aStampedDocumentIsReadableBySale() throws Exception {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);

        var response = mockMvc.perform(get(BASE_URL + seed.sale()).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(stamp.documentId().toString()))
                .andExpect(jsonPath("$.salesOrderId").value(seed.sale().toString()))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.uuidFiscal").value(stamp.uuidFiscal().toString()))
                .andExpect(jsonPath("$.stampedAt").exists())
                // S1 states the field even when the document states no folio: an absent property and a null one
                // are different answers to a caller that renders "no folio" instead of guessing one.
                .andExpect(jsonPath("$.folio").value(nullValue()))
                .andExpect(jsonPath("$.cancellation").doesNotExist())
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString())
                .as("S4: the bytes are the reprint's business, and a read that carried them would be a second "
                        + "carrier of the stamped XML")
                .doesNotContain("<")
                .doesNotContain("\"xml\"");
    }

    // ──────────────────────────────────────
    // S3 — a cancelled document is still the sale's document
    // ──────────────────────────────────────

    @Test
    @DisplayName("S3: a cancelled document still answers 200 with the stamped row and reports the copy that names "
            + "it — motive and date, never a status the tree cannot store")
    void aCancelledDocumentStillAnswersTheStampedRowWithItsCancellation() throws Exception {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);
        // The same transaction and the same Java-side scope as the stamp above: TenantFixtures.inTenant opens
        // PROPAGATION_REQUIRES_NEW, and a new transaction cannot see the row this test just stamped, so the
        // cancellation would answer "not stamped" for a reason that has nothing to do with S3.
        var cancellation = TenantScope.with(
                seed.company(),
                () -> cancellationService.cancel(
                        stamp.documentId(), FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null));

        // L10: the read has to load the row this transaction wrote, not the first-level-cached instance that
        // inserted it. created_at is the schema's own now() and its column is insertable = false, so only the
        // flushed row carries it — the service states the value and never refreshes it.
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get(BASE_URL + seed.sale()).with(token(seed.company())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(stamp.documentId().toString()))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.cancellation.cancellationDocumentId")
                        .value(cancellation.cancellationDocumentId().toString()))
                .andExpect(jsonPath("$.cancellation.motive").value("02"))
                .andExpect(jsonPath("$.cancellation.cancelledAt").exists())
                // L4 of the record: the authority's numeric code and message are not columns, so a stored answer
                // that promised them would have to invent them.
                .andExpect(jsonPath("$.cancellation.statusUuid").doesNotExist())
                .andExpect(jsonPath("$.cancellation.message").doesNotExist());
    }

    // ──────────────────────────────────────
    // S5 — absent and foreign answer the same
    // ──────────────────────────────────────

    @Test
    @DisplayName("S5: a sale id that names nothing answers 404 — distinct from the 204 of a sale with no document")
    void anAbsentSaleAnswersNotFound() throws Exception {
        var absent = UUID.randomUUID();

        mockMvc.perform(get(BASE_URL + absent).with(token(seed.company())))
                .andExpect(status().isNotFound())
                // Only a mapped refusal names the sale it refused: an unmapped path is a 404 too, so the status
                // alone would pass without the endpoint existing.
                .andExpect(jsonPath("$.message", containsString(absent.toString())));
    }

    @Test
    @DisplayName("S5/S7: another company's sale answers the same 404, because the policy hides the row instead of "
            + "the reader reporting what it cannot show")
    void anotherCompanysSaleAnswersNotFound() throws Exception {
        var foreign = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        stamper.stamp(foreign.sale(), ATTEMPT);

        assertThat(foreign.company()).isNotEqualTo(seed.company());

        mockMvc.perform(get(BASE_URL + foreign.sale()).with(token(seed.company())))
                .andExpect(status().isNotFound())
                // The same reason as the absent case: the body has to name the sale this read refused, not merely
                // be a 404 the framework would answer for any unmapped path.
                .andExpect(jsonPath("$.message", containsString(foreign.sale().toString())));
    }

    @Test
    @DisplayName("S5/S7: another company's sale with no document answers the same 404 as an absent one — the sale is "
            + "read before any document, so a foreign id is never answered 204")
    void anotherCompanysSaleWithoutADocumentAnswersNotFound() throws Exception {
        // No stamp, deliberately: this is the case that reads no document at all. An implementation that looked at
        // fiscal rows before the sale would find none here and answer 204 to a sale of another company, which is
        // exactly the S2/S5 boundary — "never invoiced" is a state of a sale this caller may see, not of one it may
        // not.
        var foreign = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();

        assertThat(foreign.company()).isNotEqualTo(seed.company());

        mockMvc.perform(get(BASE_URL + foreign.sale()).with(token(seed.company())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString(foreign.sale().toString())));
    }

    // ──────────────────────────────────────
    // S6 — the role gate and the path variable
    // ──────────────────────────────────────

    @Test
    @DisplayName("S6: a caller holding neither lc-admin nor lc-sales is refused, and reads nothing")
    void aCallerWithoutTheSaleRolesIsRefused() throws Exception {
        stamper.stamp(seed.sale(), ATTEMPT);

        mockMvc.perform(get(BASE_URL + seed.sale())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-hr"))
                                .jwt(builder -> builder.subject(SUBJECT)
                                        .claim("company_id", seed.company().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S6: a path variable that is not a UUID answers 400 naming the parameter, not a 500 and not a 404")
    void aMalformedSaleIdAnswersBadRequest() throws Exception {
        mockMvc.perform(get(BASE_URL + "not-a-uuid").with(token(seed.company())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'salesOrderId'"));
    }

    // ──────────────────────────────────────
    // helpers
    // ──────────────────────────────────────

    /**
     * The caller's token: the tenant in the one claim a company-less path reads, the store chain the sale behind
     * the document belongs to, and the role the sale screens already use. {@code lc-sales} and not
     * {@code lc-admin} so the case proves the narrower of the two.
     *
     * <p><b>Why the chain is the seed's even when the company claim is another company's.</b> The store guard walks
     * company &rarr; country &rarr; region &rarr; zone &rarr; store, so a token that states only the company is a
     * caller who never says which store it may touch, and the guard refuses it. The cases that pass a foreign
     * company do it to prove what the policy answers, and they never reach the guard: the row is hidden, so the
     * path answers 404 first.</p>
     */
    private RequestPostProcessor token(UUID claimedCompany) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject(SUBJECT)
                        .claim("company_id", claimedCompany.toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", seed.store().toString()));
    }
}
