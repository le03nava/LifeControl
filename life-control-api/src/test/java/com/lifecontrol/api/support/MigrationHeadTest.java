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
 */
@DisplayName("Latest migration version resolution")
class MigrationHeadTest {

    private static final String ORDER_FIXTURE = "classpath*:migration-order-fixture/V*__*.sql";

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
}
