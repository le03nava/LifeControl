package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.lifecontrol.api.accounting.PostingAnchor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the declared money-moving anchors — the code side of S10 of {@code odd/tasks/derived-accounting.md} —
 * literally, so shrinking the list is a red build instead of a silent hole.
 *
 * <p><b>Why a literal pin and not a structural check.</b> {@code AccountingCatalogStartupCheck} asserts that
 * every anchor this list declares has a rule and a table, and it reads its expectations from this very list.
 * That makes the assertion exactly as strong as the list is long: a slice that deletes {@code SALE_PAYMENT}
 * because its rule is not written yet would leave the startup check green and every collection posting
 * nowhere, and nothing else in the tree would say so. The list is therefore stated twice — once as the
 * constant in {@link PostingAnchor} and once here, in the form
 * {@code KeycloakClaimMapperCoverageTest} uses for the claims {@code ScopeLevel} reads: the code's own
 * vocabulary, spelled out, compared for equality.</p>
 *
 * <p><b>This starts no Spring context and touches no database.</b> The list is a static constant and the
 * question is which values it holds, so a context would only add time. The database half of S10 — that each
 * table really exists and that the closed vocabulary of {@code V98} names the same three anchors — is
 * measured by {@code AccountingCatalogStartupCheckIntegrationTest} beside this file.</p>
 */
@DisplayName("Declared posting anchors")
class PostingAnchorTest {

    @Test
    @DisplayName("declares exactly the three anchors ADR-0008 decision 3 posts from, each with its table and "
            + "its one posting")
    void declaresTheThreeAnchorsLiterally() {
        assertThat(PostingAnchor.anchors())
                .extracting(
                        PostingAnchor.Anchor::sourceType, PostingAnchor.Anchor::table, PostingAnchor.Anchor::posting)
                .as("the anchor, the append-only table it is read from, and the one posting it must be wired "
                        + "to: a fourth entry here is a money-moving source the startup check will demand a "
                        + "rule for, and a missing one is a money-moving source nothing asserts")
                .containsExactly(
                        tuple("STATUS_TRANSITION", "status_transition", "REVENUE"),
                        tuple("SALE_PAYMENT", "sale_payment", "COLLECTION"),
                        tuple("STOCK_ALLOCATION", "stock_allocation", "COST_OF_SALES"));
    }

    @Test
    @DisplayName("names each anchor and each table once, so no rule can satisfy two anchors at once")
    void namesEachAnchorAndEachTableOnce() {
        assertThat(PostingAnchor.anchors())
                .extracting(PostingAnchor.Anchor::sourceType)
                .as("ck_posting_rule_source_type is a closed set and uq_posting_rule_company_source_posting "
                        + "keys on source_type, so a repeated anchor could never be told apart from the first")
                .doesNotHaveDuplicates();

        assertThat(PostingAnchor.anchors())
                .extracting(PostingAnchor.Anchor::table)
                .as("two anchors read from one table is not a defect by itself, but it is not this book: the "
                        + "three anchors are three different append-only facts, and a copy-paste is the way "
                        + "this list would lose one of them")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("is a closed collection: a caller cannot add an anchor the startup check never saw")
    void isClosedAndUnmodifiable() {
        assertThatThrownBy(() -> PostingAnchor.anchors()
                        .add(new PostingAnchor.Anchor("INVENTORY_ADJUSTMENT", "stock_movement", "REVERSAL")))
                .as("an anchor added at runtime would be a rule requirement nothing can satisfy at boot, so "
                        + "the list is a constant and not a mutable registry")
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(PostingAnchor.anchors())
                .as("and no caller's failed attempt leaves the declared list short")
                .hasSize(3);
    }
}
