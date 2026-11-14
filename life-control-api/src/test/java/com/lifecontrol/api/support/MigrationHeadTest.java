package com.lifecontrol.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * migration that adds no SQL.</p>
 *
 * <p><b>Why that fixture is written per test and not committed.</b> This repository ignores
 * {@code *.class} (build output), so a committed fixture {@code .class} is invisible to everyone who does
 * not happen to have it on disk: the case passed locally and failed in CI with the same two assertions —
 * a test that agrees with the author's working tree is exactly the failure mode it exists to prevent. The
 * bytes are irrelevant, the <b>name</b> is the fixture, so the two files are written into a temporary
 * directory whose {@code file:} location the helper is handed.</p>
 */
@DisplayName("Latest migration version resolution")
class MigrationHeadTest {

    private static final String ORDER_FIXTURE = "classpath*:migration-order-fixture/V*__*.sql";

    @TempDir
    Path javaHalfFixture;

    /** One SQL migration and one Java migration, side by side, in a directory that is never committed. */
    private void writeBothHalves() throws IOException {
        Files.writeString(javaHalfFixture.resolve("V9__sql_half.sql"), "-- fixture: the SQL half\n");
        // The content is never parsed — the version comes from the name — so an empty file is the honest
        // fixture for "a .class exists in this location".
        Files.write(javaHalfFixture.resolve("V11__java_half.class"), new byte[0]);
    }

    private String sqlHalf() {
        return javaHalfFixture.toUri() + "V*__*.sql";
    }

    private String javaHalf() {
        return javaHalfFixture.toUri() + "V*__*.class";
    }

    private String bothHalves() {
        return javaHalfFixture.toUri() + "V*__*.*";
    }

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
    void countsAJavaMigrationAsHead() throws IOException {
        writeBothHalves();

        // The same directory, three reads: the SQL half alone answers 9, the Java half alone answers 11, and
        // the directory answers 11. A scan of *.sql only is what reported 64 for a database at 65.
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(sqlHalf()))
                .isEqualTo("9");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(javaHalf()))
                .isEqualTo("11");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(bothHalves()))
                .isEqualTo("11");
    }

    @Test
    @DisplayName("takes the newest across all patterns, so the SQL and Java halves are one answer")
    void ordersAcrossPatterns() throws IOException {
        writeBothHalves();

        // Order of the patterns must not decide the answer, which is why the helper collects first and
        // compares second.
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(ORDER_FIXTURE, javaHalf()))
                .isEqualTo("11");
        assertThat(AbstractPostgresIntegrationTest.latestMigrationVersion(javaHalf(), ORDER_FIXTURE))
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
        // Compared as versions and never as strings, which is the same rule the helper itself follows. The
        // comparison below used to be `isGreaterThanOrEqualTo(String)`, and it was a latent defect from the day
        // it was written: `"100"` sorts <b>below</b> `"65"` lexicographically, so the tree's first three-digit
        // migration (`V100`) is the one that exposed it. `MigrationVersion` is the type Flyway orders by, it
        // reads `9`, `65`, `100` and `1.1` the way the chain does, and it needs no second parser here.
        assertThat(MigrationVersion.fromVersion(AbstractPostgresIntegrationTest.latestMigrationVersion()))
                .as("the real head, as a version against the newest .class migration's version")
                .isGreaterThanOrEqualTo(MigrationVersion.fromVersion(javaHalf));
    }
}
