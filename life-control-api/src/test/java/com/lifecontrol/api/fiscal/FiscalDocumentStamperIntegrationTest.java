package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.FiscalStampProviderTestConfiguration.InMemoryFiscalStampProvider;
import com.lifecontrol.api.fiscal.exception.FiscalSaleAlreadyStampedException;
import com.lifecontrol.api.fiscal.exception.FiscalStampRejectedException;
import com.lifecontrol.api.fiscal.model.IssuerSeal;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentArtifactRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentLineRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stamp attempt: one sale, one attempt key, one call to the authority, and the artefact that keeps the
 * bytes it returned ({@code S10} and {@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions
 * {@code L12}(g)–(h) of the same record).
 *
 * <p><b>Why this is a {@code @SpringBootTest} against real PostgreSQL.</b> Three of the four facts under test
 * exist only there: {@code fiscal_document} is {@code ENABLE}+{@code FORCE}d so the write only lands under the
 * tenant scope the request installs; the transition out of {@code DRAFT} is the one {@code UPDATE} that
 * {@code V108}'s guard admits and any other is refused; and the artefact is insert-only by trigger, so "written
 * once" is the schema's answer and not this suite's. The deferred comparison of {@code V109} is the fourth: a
 * stamped document is judged against its sale at commit, and {@link #settle()} runs that judgement inside the
 * case instead of leaving it to a rollback.</p>
 *
 * <p><b>The provider is a double, and what it proves.</b> {@code T7} is the adapter; this slice has no vendor, so
 * the port is served by {@link InMemoryFiscalStampProvider}, which counts its calls and answers a document that
 * is <b>its own</b> rendering — the request's XML with a timbre appended — so "the artefact is the bytes the
 * authority returned" is a fact the suite can distinguish from "the artefact is what the builder rendered".</p>
 *
 * <p><b>The sealer is a double too, and it is what makes the seal a function of the bytes ({@code T7b}).</b>
 * {@link FiscalStampProviderTestConfiguration.InMemoryCfdiSealer} records the document it was asked to sign, so
 * "the sealer signs the seal-less rendering" and "the bytes that travel differ from it in {@code Sello} alone"
 * are two assertions about this suite's own evidence rather than about a signature nobody here can produce. The
 * signature itself is proven where it can be — {@code FiscalCsdSealerTest}, against a key pair generated at run
 * time.</p>
 *
 * <p><b>Why the cases read the table and not only the returned record.</b> The returned record is what the
 * attempt means to have done; the row set is the fiscal evidence a reprint and an auditor read. Every case below
 * therefore asserts both, and the row is read with the same pooled role the application uses.</p>
 */
@SpringBootTest
@Transactional
@Import(FiscalStampProviderTestConfiguration.class)
@DisplayName("Fiscal stamp attempt (S10, S11, T7b)")
class FiscalDocumentStamperIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt every case stamps under. */
    private static final String ATTEMPT = "stamp-2026-11-15-01";

    /** A second, separately keyed attempt of the same sale, for the one-stamped-document rule. */
    private static final String SECOND_ATTEMPT = "stamp-2026-11-15-02";

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private FiscalDocumentRepository documentRepository;

    @Autowired
    private FiscalDocumentLineRepository lineRepository;

    @Autowired
    private FiscalDocumentArtifactRepository artifactRepository;

    @Autowired
    private JdbcTemplate jdbc;

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
    @DisplayName("S10/S11: the attempt stamps once, keeps exactly the XML the provider returned, and moves the "
            + "document out of DRAFT with the authority's UUID and instant")
    void theAttemptStampsOnceAndKeepsTheBytesTheProviderReturned() {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);

        assertThat(stamp.stampedByThisAttempt())
                .as("this call is the one that stamped")
                .isTrue();
        assertThat(stamp.status()).isEqualTo("STAMPED");
        assertThat(stamp.uuidFiscal())
                .as("the authority's UUID, frozen on the row")
                .isEqualTo(InMemoryFiscalStampProvider.STAMPED_UUID);
        assertThat(stamp.stampedAt()).isEqualTo(InMemoryFiscalStampProvider.STAMPED_AT);

        var provider = FiscalStampProviderTestConfiguration.PROVIDER;
        assertThat(provider.invocations())
                .as("one attempt calls the authority once")
                .isEqualTo(1);
        assertThat(provider.lastRequest().emisorRfc())
                .as("the provider is addressed by the issuer the document froze")
                .isEqualTo(issuerRfc());
        assertThat(provider.lastRequest().xml())
                .as("what is sent is the builder's document: the timbre is the provider's to add")
                .doesNotContain("TimbreFiscalDigital");

        var document = row(stamp.documentId());
        assertThat(document.get("status")).isEqualTo("STAMPED");
        assertThat(document.get("uuid_fiscal")).isEqualTo(stamp.uuidFiscal());
        assertThat(document.get("stamped_at")).isNotNull();

        var artefact = artifactOf(stamp.documentId());
        assertThat(artefact)
                .as("a committed STAMPED document always has its XML")
                .isNotNull();
        assertThat(artefact)
                .as("the artefact is the provider's rendering byte for byte, never a regeneration (S10)")
                .isEqualTo(provider.lastAnswerXml());
        assertThat(artefactCount()).isEqualTo(1);

        // The author's own judgement: V109's deferred comparison runs here, and a document that disagreed with
        // its sale would raise at this line instead of passing at a rollback that never happens.
        settle();
    }

    // ──────────────────────────────────────
    // T7b — the seal is a function of the bytes this attempt just built
    // ──────────────────────────────────────

    @Test
    @DisplayName("T7b/L18(d): the sealer signs the seal-less rendering and the document that is sent differs "
            + "from it in Sello alone")
    void theSealerSignsTheSealLessRenderingAndTheSentDocumentDiffersFromItInSelloAlone() {
        stamper.stamp(seed.sale(), ATTEMPT);

        var sealer = FiscalStampProviderTestConfiguration.SEALER;
        var provider = FiscalStampProviderTestConfiguration.PROVIDER;
        assertThat(sealer.invocations()).as("a stamp asks for exactly one seal").isEqualTo(1);
        assertThat(sealer.lastUnsignedXml())
                .as("a Sello cannot be computed from a document that already states it, so what is signed is "
                        + "the rendering without one")
                .isNotNull()
                .doesNotContain("Sello=");
        assertThat(provider.lastRequest().xml())
                .as("the bytes that travel are the sealed rendering, not the one that was signed")
                .isNotEqualTo(sealer.lastUnsignedXml());

        var unsigned = XmlFingerprint.parse(sealer.lastUnsignedXml());
        var sealed = XmlFingerprint.parse(provider.lastRequest().xml());

        assertThat(sealed.getDocumentElement().getAttribute("Sello"))
                .as("and the seal it states is the one the sealer answered")
                .isEqualTo(FiscalStampProviderTestConfiguration.InMemoryCfdiSealer.SELLO);
        assertThat(unsigned.getDocumentElement().hasAttribute("Sello"))
                .as("the rendering a signature can be computed from states no seal at all")
                .isFalse();
        var unsignedAttributes = XmlFingerprint.attributesExceptSello(unsigned.getDocumentElement());
        assertThat(XmlFingerprint.attributesExceptSello(sealed.getDocumentElement()))
                .as("every other attribute of the root is identical, Fecha included: the two renderings come "
                        + "from one instant captured before the first of them")
                .isEqualTo(unsignedAttributes)
                .containsKey("Fecha");
        assertThat(XmlFingerprint.childrenOf(sealed.getDocumentElement()))
                .as("and every child, attribute for attribute: the seal is the only difference")
                .isEqualTo(XmlFingerprint.childrenOf(unsigned.getDocumentElement()));

        settle();
    }

    // ──────────────────────────────────────
    // S11 — idempotency
    // ──────────────────────────────────────

    @Test
    @DisplayName("S11/L12(h): a replay of the same attempt returns the stamp, calls the provider zero times and "
            + "writes no second artefact")
    void aReplayOfTheSameAttemptCallsTheProviderZeroTimes() {
        var first = stamper.stamp(seed.sale(), ATTEMPT);
        var provider = FiscalStampProviderTestConfiguration.PROVIDER;

        var replay = stamper.stamp(seed.sale(), ATTEMPT);

        // The same attempt with the padding the writer trims before it writes: the replay reads the written key,
        // so a padded one has to answer the same document instead of looking like a second attempt.
        var padded = stamper.stamp(seed.sale(), "  " + ATTEMPT + "  ");

        assertThat(padded.documentId()).isEqualTo(first.documentId());
        assertThat(provider.invocations())
                .as("the same attempt never stamps twice")
                .isEqualTo(1);
        assertThat(replay.stampedByThisAttempt()).isFalse();
        assertThat(replay.documentId()).isEqualTo(first.documentId());
        assertThat(replay.uuidFiscal()).isEqualTo(first.uuidFiscal());
        assertThat(replay.xml())
                .as("the replay answers with the stored artefact and regenerates nothing")
                .isEqualTo(first.xml());
        assertThat(artefactCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("S6/S11: a second, differently keyed attempt of an already stamped sale is refused before the "
            + "authority is called, so nothing is stamped twice")
    void aSecondAttemptOnAnAlreadyStampedSaleNeverReachesTheProvider() {
        var first = stamper.stamp(seed.sale(), ATTEMPT);
        var provider = FiscalStampProviderTestConfiguration.PROVIDER;

        assertThatThrownBy(() -> stamper.stamp(seed.sale(), SECOND_ATTEMPT))
                .isInstanceOf(FiscalSaleAlreadyStampedException.class)
                .hasMessageContaining(first.documentId().toString());

        assertThat(provider.invocations())
                .as("the guard runs before the network call: a stamp cannot be undone or recomputed")
                .isEqualTo(1);
        assertThat(documentCount())
                .as("and no second draft is written for a second that cannot be stamped")
                .isEqualTo(1);
        assertThat(artefactCount()).isEqualTo(1);
    }

    // ──────────────────────────────────────
    // S11 — the rejection
    // ──────────────────────────────────────

    @Test
    @DisplayName("S11/L12(g): a rejection leaves the document DRAFT with no artefact and surfaces the provider's "
            + "own codes")
    void aRejectionLeavesTheDocumentDraftWithNoArtefact() {
        var issues = List.of(
                new FiscalStampResult.Issue("CFDI40102", "El RFC del receptor no es valido"),
                new FiscalStampResult.Issue("CFDI40125", "El nombre del receptor no coincide"));
        FiscalStampProviderTestConfiguration.PROVIDER.reject(issues.toArray(FiscalStampResult.Issue[]::new));

        assertThatThrownBy(() -> stamper.stamp(seed.sale(), ATTEMPT))
                .isInstanceOfSatisfying(
                        FiscalStampRejectedException.class,
                        rejected -> assertThat(rejected.issues())
                                .as("the codes are the operator's only way to fix the request")
                                .containsExactlyElementsOf(issues));

        var document = singleRow();
        assertThat(document.get("status"))
                .as("a rejected attempt leaves a draft, not garbage")
                .isEqualTo("DRAFT");
        assertThat(document.get("uuid_fiscal")).isNull();
        assertThat(document.get("stamped_at")).isNull();
        assertThat(artefactCount()).as("and no artefact is written").isZero();
        assertThat(FiscalStampProviderTestConfiguration.PROVIDER.invocations())
                .as("the authority was asked exactly once")
                .isEqualTo(1);
    }

    // ──────────────────────────────────────
    // S9 — the frozen row wins over the catalog
    // ──────────────────────────────────────

    @Test
    @DisplayName("S9/S10: after the stamp, changing the catalog does not move the document that was stamped")
    void theFrozenRowWinsOverAChangedCatalog() {
        var stamp = stamper.stamp(seed.sale(), ATTEMPT);
        var artefactBefore = artifactOf(stamp.documentId());

        jdbc.update("UPDATE products SET sat_code = '02020202' WHERE id = ?", seed.product());

        var document = documentRepository.findById(stamp.documentId()).orElseThrow();
        var lines = new ArrayList<>(lineRepository.findByFiscalDocumentIdOrderByCreatedAtAsc(stamp.documentId()));
        var rebuilt = new CfdiXmlBuilder(2, Map.of("IVA", "002"))
                .build(
                        document,
                        lines,
                        new IssuerSeal(
                                FiscalStampProviderTestConfiguration.InMemoryCfdiSealer.CERTIFICATE.noCertificado(),
                                FiscalStampProviderTestConfiguration.InMemoryCfdiSealer.CERTIFICATE.certificado(),
                                FiscalStampProviderTestConfiguration.InMemoryCfdiSealer.SELLO),
                        LocalDateTime.of(2026, 11, 15, 10, 15, 30));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getClaveProdServ())
                .as("the concept states the key the row froze, never today's catalog")
                .isEqualTo(FiscalDocumentSeed.PRODUCT_KEY);
        assertThat(rebuilt)
                .as("and a rebuilt document states the same concept key")
                .contains("ClaveProdServ=\"" + FiscalDocumentSeed.PRODUCT_KEY + "\"")
                .doesNotContain("02020202");
        assertThat(artifactOf(stamp.documentId()))
                .as("the artefact is the bytes that were stamped, so nothing about it moves")
                .isEqualTo(artefactBefore);
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private String issuerRfc() {
        return jdbc.queryForObject("SELECT rfc FROM companies WHERE id = ?", String.class, seed.company());
    }

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    private Map<String, Object> singleRow() {
        var documents = jdbc.queryForList("SELECT * FROM fiscal_document WHERE company_id = ?", seed.company());
        assertThat(documents).as("exactly one document of this company").hasSize(1);
        return documents.get(0);
    }

    private long documentCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, seed.company());
    }

    private long artefactCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document_artifact WHERE company_id = ?", Long.class, seed.company());
    }

    private String artifactOf(UUID documentId) {
        return artifactRepository
                .findByCompanyIdAndFiscalDocumentId(seed.company(), documentId)
                .map(artifact -> artifact.getXml())
                .orElse(null);
    }

    /** Runs the deferred judgement a commit would run, so a stamped document is compared with its sale. */
    private void settle() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
}
