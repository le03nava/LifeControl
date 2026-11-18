package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * The store axis of the fiscal surface: {@code S1}–{@code S8} of
 * {@code odd/tasks/fiscal-document-cross-store-authz.md} (issue {@code #967}).
 *
 * <p><b>What this class proves.</b> Two stores of <b>one</b> company, so the tenant policy admits every row to every
 * caller here and nothing but the store guard can refuse a request. Each of the four endpoints the issue names is
 * refused with {@code 403} when the sale behind the document belongs to the other store, and each refusal is paired
 * with the state a write would have moved: the read's document row, the reprint's bytes, the cancellation's row and
 * the stamped status the authority witnessed, the issue's absence of any new row. The two open directions stay open —
 * the store that owns the document still reads, reprints, cancels and issues it — and {@code lc-admin} keeps the
 * unscoped access the short-circuit already grants it.</p>
 *
 * <p><b>Why every case goes through the real chain.</b> These are statements about what a caller receives: the status,
 * the body, and whether an authority was called. So each performs the request through {@code TenantScopeFilter}, the
 * {@code @PreAuthorize} of the two sale roles and the controller, rather than calling the service and asserting an
 * exception type — an exception the handler maps to something else than {@code 403} would pass the second shape and
 * fail the caller.</p>
 *
 * <p><b>Why the other store's document is stamped under that store's identity.</b> Stamping a store-B sale from a
 * store-A caller is itself the refusal {@code S4} states, so a fixture that did it under the wrong identity would be
 * writing the bug into its own setup. {@link FiscalDocumentSeed#authenticateAs} states the chain of the store the
 * caller holds, and the fixture uses the store it just seeded.</p>
 *
 * <p><b>What it deliberately does not do.</b> It does not re-prove the tenant-level row level security, which its own
 * suites pin: both stores belong to one company on purpose, because a refusal that could come from either layer would
 * prove neither. And it does not re-prove the transitions, the sealing or the arithmetic of a cancellation — the
 * effects asserted here are only the ones a refused request would have moved.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({FiscalStampProviderTestConfiguration.class, FiscalCancellationProviderTestConfiguration.class})
@DisplayName("Cross-store authorization of the fiscal surface (S1-S8)")
class FiscalDocumentStoreScopeHttpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE_URL = "/api/fiscal-documents";

    /** The attempt that stamped the other store's document, which every refusal case addresses. */
    private static final String OTHER_STORES_ATTEMPT = "store-scope-2026-11-18-01";

    /** The attempt that stamps the caller's own sale, so {@code S8} has a document of its own to cancel. */
    private static final String OWN_ATTEMPT = "store-scope-2026-11-18-02";

    private static final String SUBJECT = "store-scope-2026-11-18";

    /** The authority's UUID for the other store's document. */
    private static final UUID OTHER_STORES_UUID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

    /** The authority's UUID for the caller's own document, so {@code S8} can name one and not the other. */
    private static final UUID OWN_UUID = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    /** The other store's stamped document, and the sale it came from. */
    private UUID otherDocument;

    private String otherUuidFiscal;

    /** A store-B sale with no attempt at all, for the case that pins "not yet invoiced" is still a state. */
    private UUID otherStoreSaleWithoutDocument;

    /** The caller's own stamped document, the one {@code S8} cancels while naming a foreign folio. */
    private UUID ownDocument;

    private String ownUuidFiscal;

    @BeforeEach
    void seedTwoStoresOfOneCompany() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        FiscalStampProviderTestConfiguration.SEALER.reset();
        FiscalCancellationProviderTestConfiguration.PROVIDER.reset();

        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
        var otherSale = seed.aSaleInAnotherStore();
        otherStoreSaleWithoutDocument = seed.aSaleInAnotherStore();

        // The other store's document is stamped by a caller that holds both stores: stamping it from store A alone is
        // the refusal S4 exists for, so it cannot be the setup, and CurrentUserContext caches the claim path of the
        // scope it resolved in, so one caller stating both stores is the only shape that stamps two stores in one
        // case. Two distinct authority UUIDs, because one UUID per document is the authority's rule and the S1/S2
        // cases read the uuid as the thing a 403 must not leak.
        seed.authenticateAs(seed.store(), seed.anotherStore());
        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(OTHER_STORES_UUID);
        var otherStamp = stamper.stamp(otherSale, OTHER_STORES_ATTEMPT);
        otherDocument = otherStamp.documentId();
        otherUuidFiscal = otherStamp.uuidFiscal().toString();

        FiscalStampProviderTestConfiguration.PROVIDER.stampingUnder(OWN_UUID);
        var ownStamp = stamper.stamp(seed.sale(), OWN_ATTEMPT);
        ownDocument = ownStamp.documentId();
        ownUuidFiscal = ownStamp.uuidFiscal().toString();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
        // The stamping double is a static singleton shared by every fiscal suite in the JVM: a mode this class set
        // and did not clear would make the next suite stamp two documents under one authority UUID.
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
    }

    // ──────────────────────────────────────
    // S1 — the read answers through the sale's store
    // ──────────────────────────────────────

    @Test
    @DisplayName("S1: another store's sale answers 403 and leaks neither the authority's UUID nor the folio")
    void readOfAnotherStoresSaleIsForbiddenAndLeaksNoFiscalIdentity() throws Exception {
        var body = mockMvc.perform(
                        get(BASE_URL + "/by-sale/{id}", saleOf(otherDocument)).with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The refusal is the answer; the identity of the document is the thing it must not say. The folio is a local
        // convention V108 leaves optional and this tree's stamping path never writes, so it is asserted when the row
        // states one and the authority's UUID is what always carries the leak.
        assertThat(body).doesNotContain(otherUuidFiscal).doesNotContain(otherDocument.toString());
        var folio = storedFolio(otherDocument);
        if (folio != null) {
            assertThat(body).doesNotContain(folio);
        }
    }

    @Test
    @DisplayName("S1: the store that owns the sale still reads its document, and the sale with no document is still "
            + "a 204 for the store that owns it")
    void theOwningStoreStillReadsAndAMissingDocumentIsStillNoContent() throws Exception {
        mockMvc.perform(get(BASE_URL + "/by-sale/{id}", saleOf(otherDocument)).with(tokenAt(seed.anotherStore())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuidFiscal").value(otherUuidFiscal));

        mockMvc.perform(get(BASE_URL + "/by-sale/{id}", otherStoreSaleWithoutDocument)
                        .with(tokenAt(seed.anotherStore())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // And the same sale answers the store that does not own it with the refusal, not with the 204: whether a
        // sale of another store was invoiced is not a state this caller may read (L10).
        mockMvc.perform(get(BASE_URL + "/by-sale/{id}", otherStoreSaleWithoutDocument)
                        .with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ──────────────────────────────────────
    // S2 — the reprint carries no bytes of another store
    // ──────────────────────────────────────

    @Test
    @DisplayName("S2: another store's document answers 403 from the reprint and returns no bytes")
    void reprintOfAnotherStoresDocumentIsForbiddenAndReturnsNoBytes() throws Exception {
        var body = mockMvc.perform(get(BASE_URL + "/{id}/xml", otherDocument).with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("<?xml").doesNotContain(otherUuidFiscal);
    }

    @Test
    @DisplayName("S2: the store that owns the document still reprints it, byte for byte")
    void theOwningStoreStillReprints() throws Exception {
        var stored = jdbc.queryForObject(
                "SELECT xml FROM fiscal_document_artifact WHERE fiscal_document_id = ?", String.class, otherDocument);

        mockMvc.perform(get(BASE_URL + "/{id}/xml", otherDocument).with(tokenAt(seed.anotherStore())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // ──────────────────────────────────────
    // S3 — a cancellation is a statement, and a refused one writes nothing
    // ──────────────────────────────────────

    @Test
    @DisplayName("S3: cancelling another store's document answers 403, writes no cancellation row and leaves the "
            + "stamped status exactly as it was")
    void cancellationOfAnotherStoresDocumentIsForbiddenAndWritesNothing() throws Exception {
        var before = storedStatus(otherDocument);

        mockMvc.perform(post(BASE_URL + "/{id}/cancellation", otherDocument)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"02\"}")
                        .with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(storedStatus(otherDocument)).isEqualTo(before);
        assertThat(cancellationRowsNaming(otherDocument)).isZero();
        // The authority is the half a local flag could not prove: a refusal that had already asked it would have
        // spent a request the receiver records.
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    // ──────────────────────────────────────
    // S4 — issuing stamps nothing for a sale of another store
    // ──────────────────────────────────────

    @Test
    @DisplayName("S4: issuing another store's sale answers 403 and stamps nothing")
    void issuingAnotherStoresSaleIsForbiddenAndStampsNothing() throws Exception {
        // The two setup stamps are the baseline: what this case asserts is that the refused request added none.
        var callsBefore = FiscalStampProviderTestConfiguration.PROVIDER.invocations();

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesOrderId\":\"" + otherStoreSaleWithoutDocument
                                + "\",\"attemptKey\":\"store-scope-issue-01\"}")
                        .with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(documentRowsFor(otherStoreSaleWithoutDocument)).isZero();
        assertThat(FiscalStampProviderTestConfiguration.PROVIDER.invocations()).isEqualTo(callsBefore);
    }

    // ──────────────────────────────────────
    // S7 — lc-admin keeps the unscoped access the short-circuit already grants it
    // ──────────────────────────────────────

    @Test
    @DisplayName("S7: lc-admin still reads the other store's document without stating a store claim")
    void adminKeepsItsUnscopedAccess() throws Exception {
        mockMvc.perform(get(BASE_URL + "/by-sale/{id}", saleOf(otherDocument)).with(adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuidFiscal").value(otherUuidFiscal));
    }

    @Test
    @DisplayName("S7/L7: a broader role keeps its wider scope, so an lc-company-region caller reads either store's "
            + "document without a store claim")
    void aBroaderRoleIsNotNarrowedToTheStore() throws Exception {
        // The guard asks for the broadest scope the caller holds, so a principal that also holds a company-region
        // role is verified up to the region and never down to the store. This is the second shape S7/L7 promises and
        // the one a deployment with mixed grants depends on.
        mockMvc.perform(get(BASE_URL + "/by-sale/{id}", saleOf(otherDocument))
                        .with(jwt().authorities(
                                        new SimpleGrantedAuthority("ROLE_lc-sales"),
                                        new SimpleGrantedAuthority("ROLE_lc-company-region"))
                                .jwt(builder -> builder.subject(SUBJECT)
                                        .claim("company_id", seed.company().toString())
                                        .claim(
                                                "company_country_id",
                                                seed.companyCountry().toString())
                                        .claim(
                                                "company_region_id",
                                                seed.region().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuidFiscal").value(otherUuidFiscal));
    }

    // ──────────────────────────────────────
    // S3 — the refusal precedes the state the document already has
    // ──────────────────────────────────────

    @Test
    @DisplayName("S3/S5: an already-cancelled document of another store answers 403 and never 409, because the store "
            + "is decided before the cancellation is looked for")
    void alreadyCancelledForeignDocumentIsForbiddenAndNotAConflict() throws Exception {
        // The owner cancels it first, so the row this caller must not learn about is the cancellation copy.
        mockMvc.perform(post(BASE_URL + "/{id}/cancellation", otherDocument)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"02\"}")
                        .with(tokenAt(seed.anotherStore())))
                .andExpect(status().isCreated());

        // Pre-fix this answered 409 — "already cancelled" — which told a caller of another store both that the
        // document exists and what state it is in. The guard runs before that read, so the answer is the same 403 a
        // stamped document of that store receives.
        mockMvc.perform(post(BASE_URL + "/{id}/cancellation", otherDocument)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"02\"}")
                        .with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ──────────────────────────────────────
    // S8 — the substituting folio is inside the rule, not beside it
    // ──────────────────────────────────────

    @Test
    @DisplayName("S8: cancelling the caller's own document while naming another store's folio answers 403 and "
            + "writes nothing")
    void cancellationNamingAnotherStoresFolioIsForbidden() throws Exception {
        mockMvc.perform(post(BASE_URL + "/{id}/cancellation", ownDocument)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"01\",\"substitutingFolio\":\"" + otherUuidFiscal + "\"}")
                        .with(tokenAt(seed.store())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(cancellationRowsNaming(ownDocument)).isZero();
        assertThat(storedStatus(ownDocument)).isEqualTo("STAMPED");
        assertThat(FiscalCancellationProviderTestConfiguration.PROVIDER.invocations())
                .isZero();
    }

    @Test
    @DisplayName("S8: the same request naming a folio of the caller's own store is still accepted, so the rule "
            + "refuses the store and not the motive")
    void cancellationNamingTheOwningStoresFolioStillWorks() throws Exception {
        mockMvc.perform(post(BASE_URL + "/{id}/cancellation", ownDocument)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motive\":\"01\",\"substitutingFolio\":\"" + ownUuidFiscal + "\"}")
                        .with(tokenAt(seed.store())))
                .andExpect(status().isCreated());

        assertThat(cancellationRowsNaming(ownDocument)).isEqualTo(1);
    }

    // ── the caller, and the rows the cases read back ──

    /**
     * A {@code lc-sales} caller whose claims state the real company &rarr; country &rarr; region &rarr; zone
     * &rarr; store path for one store of the seeded company.
     */
    private RequestPostProcessor tokenAt(UUID storeId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-sales"))
                .jwt(builder -> builder.subject(SUBJECT)
                        .claim("company_id", seed.company().toString())
                        .claim("company_country_id", seed.companyCountry().toString())
                        .claim("company_region_id", seed.region().toString())
                        .claim("company_zone_id", seed.zone().toString())
                        .claim("company_store_id", storeId.toString()));
    }

    /** The same realm principal holding {@code lc-admin}, which {@code verifyCompanyStoreAccess} exempts. */
    private RequestPostProcessor adminToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))
                .jwt(builder -> builder.subject(SUBJECT)
                        .claim("company_id", seed.company().toString()));
    }

    private UUID saleOf(UUID documentId) {
        return jdbc.queryForObject("SELECT source_id FROM fiscal_document WHERE id = ?", UUID.class, documentId);
    }

    private String storedStatus(UUID documentId) {
        return jdbc.queryForObject("SELECT status FROM fiscal_document WHERE id = ?", String.class, documentId);
    }

    private String storedFolio(UUID documentId) {
        return jdbc.queryForObject("SELECT folio FROM fiscal_document WHERE id = ?", String.class, documentId);
    }

    private int cancellationRowsNaming(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE cancels_fiscal_document_id = ?", Integer.class, documentId);
    }

    private int documentRowsFor(UUID saleId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE source_type = 'SALES_ORDER' AND source_id = ?",
                Integer.class,
                saleId);
    }
}
