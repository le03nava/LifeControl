package com.lifecontrol.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the two rules {@link AbstractPostgresIntegrationTest#latestMigrationVersion(String)} has to
 * hold, because seven schema suites now derive their Flyway-head assertion from it: a wrong answer
 * would make them all agree with the wrong value.
 *
 * <p>The ordering rule is the one that bites: {@code V10} is never newer than {@code V9} as a string,
 * so a comparison over the raw names silently reports the <i>oldest</i> of the two-digit migrations.
 * It is pinned against a fixture directory ({@code src/test/resources/migration-order-fixture}) whose
 * three files exist for nothing else, so this test never touches the real migration set and never has
 * to be bumped itself — the defect this whole change removes.</p>
 *
 * <p><b>And the second rule: a Java migration counts.</b> {@code V65__Document_identity_render} is the
 * tree's first migration of that kind (ADR-0003 renders the document number with the renderer the API
 * ships, so the writer and the history share one implementation), and the scan used to read {@code *.sql}
 * only — which reported {@code 64} for a database at {@code 65} and failed nine schema suites on the one
 * migration that adds no SQL. {@code migration-order-fixture-java} holds one SQL and one {@code .class}
 * file for exactly that question.</p>
 */
@DisplayName("Latest migration version resolution")
class MigrationHeadTest {

    private static final String ORDER_FIXTURE = "classpath*:migration-order-fixture/V*__*.sql";

    private static final String BOTH_HALVES_FIXTURE = "classpath*:migration-order-fixture-java/V*__*.*";

    private static final String JAVA_HALF_FIXTURE = "classpath*:migration-order-fixture-java/V*__*.class";

    @Test
    @DisplayName("orders by migration version, so V10 is newer than V9 and V2")
    void ordersVersionsNumerically() {
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(ORDER_FIXTURE))
                .isEqualTo("10");
    }

    @Test
    @DisplayName("fails loudly when the location carries no migration at all")
    void failsOnAnEmptyLocation() {
        assertThatThrownBy(() -> AbstractPostgresIntegrationTest.latestMigrationVersion(
                        "classpath*:migration-order-fixture-empty/V*__*.sql"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no migration matched");
    }

    @Test
    @DisplayName("reads a Java migration's head, so a .class file is not the one kind of migration it misses")
    void countsAJavaMigrationAsHead() {
        // The same directory, twice: the SQL half alone answers 9, and the answer for the directory is the
        // Java half's 11. A scan of *.sql only is what reported 64 for a database at 65.
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(
                        "classpath*:migration-order-fixture-java/V*__*.sql"))
                .isEqualTo("9");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(JAVA_HALF_FIXTURE))
                .isEqualTo("11");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(BOTH_HALVES_FIXTURE))
                .isEqualTo("11");
    }

    @Test
    @DisplayName("takes the newest across all patterns, so the SQL and Java halves are one answer")
    void ordersAcrossPatterns() {
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(ORDER_FIXTURE, JAVA_HALF_FIXTURE))
                .isEqualTo("11");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(JAVA_HALF_FIXTURE, ORDER_FIXTURE))
                .isEqualTo("11");
    }

    @Test
    @DisplayName("reports the real head as at least as new as the tree's newest Java migration")
    void realHeadAccountsForTheJavaHalf() {
        // The rule the fix exists for, without a literal: the zero-argument helper reads both halves of the
        // real directory, so it can never answer an older version than the newest .class migration on the
        // classpath. With a `*.sql`-only scan this was 64 against 65 and nine schema suites failed. If the
        // tree ever ships no Java migration again, delete this test with the pattern it names — the fixture
        // test above keeps the parse and the ordering pinned either way.
        var javaHalf = AbstractPostgresIntegrationTest.latestMigrationVersion("classpath*:db/migration/V*__*.class");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion()).isGreaterThanOrEqualTo(javaHalf);
    }
}
