package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The path that must not change: the default H2 test stack boots even though it has no PostgreSQL catalog to
 * read, and the startup assertion is a no-op there.
 *
 * <p><b>Why this needs its own case.</b> {@code application-test.properties} makes H2 the default stack
 * ({@code jdbc:h2:mem:testdb}, Flyway disabled, {@code ddl-auto=create-drop}) and sixty-odd suites boot there.
 * S10's assertion asks PostgreSQL's catalog three questions — is the table there, what does the closed
 * vocabulary say, does {@code to_regclass} answer — that H2 cannot answer at all, so an unconditional check
 * would fail <b>every</b> one of those contexts: a build that breaks instead of a catalog that fails. The
 * suite therefore states the gate itself: the bean's own probe says "not applicable", and running the check
 * returns without asking the catalog.</p>
 *
 * <p><b>And why the probe asks two questions instead of one.</b> H2 cannot even parse the precise question —
 * its parser does not know {@code to_regprocedure} — so asking it unconditionally would fail with a syntax
 * error instead of answering "no function". The product name is asked first, exactly as
 * {@code TenantIsolationStartupCheck} asks it of the same pooled {@code DataSource}.</p>
 */
@SpringBootTest
@DisplayName("The accounting catalog startup assertion on the H2 test stack")
class AccountingCatalogStartupCheckH2Test {

    @Autowired
    private AccountingCatalogStartupCheck check;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the probe answers \"not applicable\" on H2 and the check returns without reading the catalog")
    void theProbeAnswersNotApplicableOnH2() {
        assertThat(check.appliesToThisDatabase())
                .as("this is the answer the context's own startup check computed from the pooled DataSource; "
                        + "had it been true, this suite could not have started, because H2 has no "
                        + "`to_regclass`, no `pg_constraint` and no posting_rule table to look at")
                .isFalse();

        assertThatCode(check::afterPropertiesSet)
                .as("so the assertion is a no-op here: it returns before the catalog query, and the great "
                        + "majority of this module's suite keeps booting")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("H2 cannot answer the precise question, which is why the probe asks the product name first")
    void h2CannotAnswerThePreciseQuestion() {
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                        "select to_regprocedure('pg_catalog.to_regclass(text)')", Boolean.class))
                .as("`to_regprocedure` is the precise question on PostgreSQL and an unknown function on H2: "
                        + "asking it unconditionally would fail every H2 context at startup")
                .isInstanceOf(DataAccessException.class)
                .hasMessageMatching("(?is).*to_regprocedure.*");
    }
}
