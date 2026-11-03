package com.lifecontrol.api.common.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ADR-0004 decisions 2 and 3, measured exhaustively: for every family, the declared edges are the ones
 * {@link DocumentStates#canTransition} accepts and <b>every other pair of constants is refused</b>.
 *
 * <p><b>Why the expected edges are written here and not read from the enum.</b> A test that asks the
 * enum what it declares and then asserts the enum declares it is a tautology. The table below is the
 * second, independent statement of the machine — copied from the four private maps the services carried
 * before this delivery — so a change to a successor set fails here and a reviewer can see both copies.
 * Every pair is checked, not just the declared edges: a state that silently accepts an undeclared
 * target is exactly the defect the closed set exists to remove.</p>
 */
@DisplayName("Document state machines (ADR-0004)")
class DocumentStatesTest {

    @Nested
    @DisplayName("sales order")
    class SalesOrder {

        private final Map<SalesOrderState, Set<SalesOrderState>> expected = new EnumMap<>(SalesOrderState.class);

        SalesOrder() {
            expected.put(SalesOrderState.DRAFT, EnumSet.of(SalesOrderState.ACTIVE, SalesOrderState.CANCELLED));
            expected.put(SalesOrderState.ACTIVE, EnumSet.of(SalesOrderState.PENDING, SalesOrderState.CANCELLED));
            expected.put(SalesOrderState.PENDING, EnumSet.of(SalesOrderState.COMPLETED, SalesOrderState.CANCELLED));
            expected.put(SalesOrderState.COMPLETED, EnumSet.noneOf(SalesOrderState.class));
            expected.put(SalesOrderState.CANCELLED, EnumSet.noneOf(SalesOrderState.class));
        }

        @Test
        @DisplayName("every declared edge is accepted and every other pair is refused")
        void everyPair() {
            assertEdgesAgree(SalesOrderState.values(), expected);
        }

        @Test
        @DisplayName("a completed order cannot be reopened")
        void completedIsTerminal() {
            assertThat(DocumentStates.canTransition(SalesOrderState.COMPLETED, SalesOrderState.ACTIVE))
                    .isFalse();
            assertThat(DocumentStates.isTerminal(SalesOrderState.COMPLETED)).isTrue();
        }
    }

    @Nested
    @DisplayName("sales order item")
    class SalesOrderItem {

        private final Map<SalesOrderItemState, Set<SalesOrderItemState>> expected =
                new EnumMap<>(SalesOrderItemState.class);

        SalesOrderItem() {
            expected.put(
                    SalesOrderItemState.PENDING, EnumSet.of(SalesOrderItemState.ADDED, SalesOrderItemState.CANCELLED));
            expected.put(SalesOrderItemState.ADDED, EnumSet.noneOf(SalesOrderItemState.class));
            expected.put(SalesOrderItemState.CANCELLED, EnumSet.noneOf(SalesOrderItemState.class));
        }

        @Test
        @DisplayName("every declared edge is accepted and every other pair is refused")
        void everyPair() {
            assertEdgesAgree(SalesOrderItemState.values(), expected);
        }

        @Test
        @DisplayName("a line that booked its deduction cannot be cancelled")
        void addedCannotBeCancelled() {
            assertThat(DocumentStates.canTransition(SalesOrderItemState.ADDED, SalesOrderItemState.CANCELLED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("purchase order")
    class PurchaseOrder {

        private final Map<PurchaseOrderState, Set<PurchaseOrderState>> expected =
                new EnumMap<>(PurchaseOrderState.class);

        PurchaseOrder() {
            expected.put(PurchaseOrderState.DRAFT, EnumSet.of(PurchaseOrderState.SENT, PurchaseOrderState.REJECTED));
            expected.put(PurchaseOrderState.SENT, EnumSet.of(PurchaseOrderState.ACCEPTED, PurchaseOrderState.REJECTED));
            expected.put(
                    PurchaseOrderState.ACCEPTED,
                    EnumSet.of(PurchaseOrderState.IN_TRANSIT, PurchaseOrderState.REJECTED));
            expected.put(
                    PurchaseOrderState.IN_TRANSIT,
                    EnumSet.of(PurchaseOrderState.RECEIVED, PurchaseOrderState.REJECTED));
            expected.put(
                    PurchaseOrderState.RECEIVED, EnumSet.of(PurchaseOrderState.BILLED, PurchaseOrderState.REJECTED));
            expected.put(PurchaseOrderState.BILLED, EnumSet.of(PurchaseOrderState.CLOSED, PurchaseOrderState.REJECTED));
            expected.put(PurchaseOrderState.CLOSED, EnumSet.noneOf(PurchaseOrderState.class));
            expected.put(PurchaseOrderState.REJECTED, EnumSet.noneOf(PurchaseOrderState.class));
        }

        @Test
        @DisplayName("every declared edge is accepted and every other pair is refused")
        void everyPair() {
            assertEdgesAgree(PurchaseOrderState.values(), expected);
        }

        @Test
        @DisplayName("a rejected order cannot be sent again")
        void rejectedIsTerminal() {
            assertThat(DocumentStates.canTransition(PurchaseOrderState.REJECTED, PurchaseOrderState.SENT))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("purchase order detail")
    class PurchaseOrderDetail {

        private final Map<PurchaseOrderDetailState, Set<PurchaseOrderDetailState>> expected =
                new EnumMap<>(PurchaseOrderDetailState.class);

        PurchaseOrderDetail() {
            expected.put(
                    PurchaseOrderDetailState.PENDING,
                    EnumSet.of(PurchaseOrderDetailState.IN_PROCESS, PurchaseOrderDetailState.CANCELLED));
            expected.put(
                    PurchaseOrderDetailState.IN_PROCESS,
                    EnumSet.of(
                            PurchaseOrderDetailState.IN_TRANSIT,
                            PurchaseOrderDetailState.REJECTED,
                            PurchaseOrderDetailState.CANCELLED));
            expected.put(
                    PurchaseOrderDetailState.IN_TRANSIT,
                    EnumSet.of(
                            PurchaseOrderDetailState.PARTIAL_RECEIVED,
                            PurchaseOrderDetailState.REJECTED,
                            PurchaseOrderDetailState.CANCELLED));
            expected.put(
                    PurchaseOrderDetailState.PARTIAL_RECEIVED,
                    EnumSet.of(PurchaseOrderDetailState.RECEIVED, PurchaseOrderDetailState.REJECTED));
            expected.put(PurchaseOrderDetailState.RECEIVED, EnumSet.noneOf(PurchaseOrderDetailState.class));
            expected.put(PurchaseOrderDetailState.REJECTED, EnumSet.noneOf(PurchaseOrderDetailState.class));
            expected.put(PurchaseOrderDetailState.CANCELLED, EnumSet.noneOf(PurchaseOrderDetailState.class));
        }

        @Test
        @DisplayName("every declared edge is accepted and every other pair is refused")
        void everyPair() {
            assertEdgesAgree(PurchaseOrderDetailState.values(), expected);
        }

        @Test
        @DisplayName("a received line cannot be cancelled, because its stock already arrived")
        void receivedCannotBeCancelled() {
            assertThat(DocumentStates.canTransition(
                            PurchaseOrderDetailState.RECEIVED, PurchaseOrderDetailState.CANCELLED))
                    .isFalse();
        }

        @Test
        @DisplayName("the detail machine is not the item machine: it has no ADDED and admits IN_PROCESS")
        void isNotTheSalesItemMachine() {
            assertThat(PurchaseOrderDetailState.valueOf("PENDING")).isNotNull();
            assertThat(EnumSet.allOf(PurchaseOrderDetailState.class))
                    .contains(PurchaseOrderDetailState.IN_PROCESS, PurchaseOrderDetailState.PARTIAL_RECEIVED)
                    .hasSize(7);
        }
    }

    @Nested
    @DisplayName("goods receipt")
    class GoodsReceipt {

        @Test
        @DisplayName("its one state is terminal and admits nothing")
        void registeredIsTerminal() {
            assertThat(GoodsReceiptState.values()).containsExactly(GoodsReceiptState.REGISTERED);
            assertThat(GoodsReceiptState.REGISTERED.successors()).isEmpty();
            assertThat(DocumentStates.isTerminal(GoodsReceiptState.REGISTERED)).isTrue();
        }
    }

    @Nested
    @DisplayName("the guard")
    class Guard {

        @Test
        @DisplayName("a missing state on either side is a refusal, never a null pointer")
        void nullsAreRefused() {
            assertThat(DocumentStates.canTransition(null, SalesOrderState.ACTIVE))
                    .isFalse();
            assertThat(DocumentStates.canTransition(SalesOrderState.DRAFT, null))
                    .isFalse();
            assertThat(DocumentStates.canTransition(null, null)).isFalse();
            assertThat(DocumentStates.isTerminal(null)).isTrue();
            assertThat(DocumentStates.successorsOf(null)).isEmpty();
        }

        @Test
        @DisplayName("the refusal is a 409 that names both ends of the move")
        void requireTransitionNamesBothEnds() {
            assertThatThrownBy(
                            () -> DocumentStates.requireTransition(SalesOrderState.COMPLETED, SalesOrderState.ACTIVE))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: COMPLETED → ACTIVE");
            assertThatThrownBy(() -> DocumentStates.requireTransition(SalesOrderState.DRAFT, null))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessage("Invalid status transition: DRAFT → missing");
        }

        @Test
        @DisplayName("the five families map one to one onto the five subjects")
        void oneFamilyPerSubject() {
            var subjects = java.util.List.of(
                            SalesOrderState.values(),
                            SalesOrderItemState.values(),
                            PurchaseOrderState.values(),
                            PurchaseOrderDetailState.values(),
                            GoodsReceiptState.values())
                    .stream()
                    .map(constants -> constants[0].subject())
                    .collect(Collectors.toList());

            assertThat(subjects)
                    .containsExactlyInAnyOrder(StatusSubject.values())
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no state declares itself as its own successor")
        void noSelfLoops() {
            assertThat(SalesOrderState.values())
                    .allSatisfy(s -> assertThat(s.successors()).doesNotContain(s));
            assertThat(SalesOrderItemState.values())
                    .allSatisfy(s -> assertThat(s.successors()).doesNotContain(s));
            assertThat(PurchaseOrderState.values())
                    .allSatisfy(s -> assertThat(s.successors()).doesNotContain(s));
            assertThat(PurchaseOrderDetailState.values())
                    .allSatisfy(s -> assertThat(s.successors()).doesNotContain(s));
            assertThat(GoodsReceiptState.values())
                    .allSatisfy(s -> assertThat(s.successors()).doesNotContain(s));
        }

        @Test
        @DisplayName("a family's successor set can never name another family's state")
        void successorSetsStayInsideTheirFamily() {
            assertThat(SalesOrderState.values())
                    .allSatisfy(
                            s -> assertThat(s.successors()).allMatch(t -> t.subject() == StatusSubject.SALES_ORDER));
            assertThat(PurchaseOrderDetailState.values())
                    .allSatisfy(s -> assertThat(s.successors())
                            .allMatch(t -> t.subject() == StatusSubject.PURCHASE_ORDER_DETAIL));
        }
    }

    /**
     * Asserts the guard's answer for every ordered pair of the family: an edge the table declares is
     * accepted, and a pair it does not declare is refused. Both directions, so a successor set that grew
     * or shrank without the table changing cannot pass.
     */
    private static <S extends Enum<S> & DocumentState<S>> void assertEdgesAgree(
            S[] constants, Map<S, Set<S>> declared) {
        assertThat(declared.keySet())
                .as("the table must name every constant of the family")
                .containsExactlyInAnyOrder(constants);

        var mismatches = new LinkedHashMap<>();
        for (S from : constants) {
            for (S to : constants) {
                var declaredEdge = declared.get(from).contains(to);
                if (DocumentStates.canTransition(from, to) != declaredEdge) {
                    mismatches.put(from + " -> " + to, declaredEdge);
                }
            }
        }

        assertThat(mismatches)
                .as("every pair must agree with the declared table; the value is what the table says")
                .isEmpty();

        for (S state : constants) {
            assertThat(DocumentStates.isTerminal(state))
                    .as("terminality of %s", state)
                    .isEqualTo(declared.get(state).isEmpty());
        }
    }
}
