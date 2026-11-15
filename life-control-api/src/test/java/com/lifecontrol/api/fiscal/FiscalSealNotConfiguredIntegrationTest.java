package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.exception.FiscalSealNotConfiguredException;
import com.lifecontrol.api.fiscal.service.FiscalDocumentStamper;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * A stamp with a provider and no sealer: the named refusal, and the fact that it happens before anything is sent
 * ({@code T7b}, decisions {@code L18}(d) and {@code L18}(f) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why this needs a context of its own.</b> The refusal is the absence of a bean, so no case can state it
 * inside a context that has one. This suite therefore registers the port <b>and nothing else</b> — the shape of a
 * deployment that turned {@code app.fiscal.provider.enabled} on without giving the sealer its CSD, which cannot
 * actually happen (both are gated by the same switch) and is exactly why the branch has to be pinned rather than
 * argued: the sealer is resolved through {@code ObjectProvider}, so the day one registration is split from the
 * other, the answer is this exception and not a {@code NullPointerException} inside a transaction that already
 * wrote a draft.</p>
 *
 * <p><b>What the case asserts besides the type.</b> That the authority was never asked, and that the row set says
 * what an attempt that could not run must leave: a {@code DRAFT} with no artefact. An exception thrown after the
 * network call would look identical to a caller and would have minted a timbre this tree cannot recompute.</p>
 */
@SpringBootTest
@Transactional
@Import(FiscalSealNotConfiguredIntegrationTest.ProviderWithoutASealer.class)
@DisplayName("A stamp with no sealer configured (T7b)")
class FiscalSealNotConfiguredIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ATTEMPT = "seal-absent-2026-11-15-01";

    @Autowired
    private FiscalDocumentStamper stamper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    private FiscalDocumentSeed seed;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        FiscalStampProviderTestConfiguration.PROVIDER.reset();
        seed = new FiscalDocumentSeed(jdbc, accountingCatalog).aCompletedSaleWithAFiscalProfile();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("T7b/L18(d): with no sealer registered the attempt is refused by name, the authority is never called "
            + "and the document stays a DRAFT with no artefact")
    void theAttemptIsRefusedByNameBeforeTheAuthorityIsCalled() {
        assertThatThrownBy(() -> stamper.stamp(seed.sale(), ATTEMPT))
                .isInstanceOf(FiscalSealNotConfiguredException.class)
                .hasMessageContaining("CfdiSealer")
                .hasMessageContaining("app.fiscal.csd");

        assertThat(FiscalStampProviderTestConfiguration.PROVIDER.invocations())
                .as("a document that cannot be sealed is never sent, so the authority has nothing to refuse")
                .isZero();

        Map<String, Object> document = jdbc.queryForMap(
                "SELECT status, uuid_fiscal, stamped_at FROM fiscal_document WHERE company_id = ?", seed.company());
        assertThat(document.get("status"))
                .as("the refusal leaves the draft the only mutable state, which is what a failed attempt leaves")
                .isEqualTo("DRAFT");
        assertThat(document.get("uuid_fiscal")).isNull();
        assertThat(document.get("stamped_at")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fiscal_document_artifact WHERE company_id = ?",
                        Long.class,
                        seed.company()))
                .as("and no artefact is written for a document nobody stamped")
                .isZero();
    }

    /** The deployment this case is about: the port answers, and nothing seals. */
    @TestConfiguration
    static class ProviderWithoutASealer {

        @Bean
        FiscalStampProvider fakeFiscalStampProvider() {
            return FiscalStampProviderTestConfiguration.PROVIDER;
        }
    }
}
