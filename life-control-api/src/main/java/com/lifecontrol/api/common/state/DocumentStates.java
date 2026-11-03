package com.lifecontrol.api.common.state;

import java.util.Set;

/**
 * The one guard that decides whether a document may move (ADR-0004 decision 2).
 *
 * <p>Every write path calls this and nothing else: the order-level put, the single-item edit, the
 * charge, the cancel, and the purchase-order promotions that are derived rather than requested. Before
 * ADR-0004 that decision lived in four private {@code Map<String, Set<String>>} tables and in the
 * comparisons next to them, which is why a state added to one path was refused by another.</p>
 *
 * <p><b>The database cannot reach the Java enum, so this is discipline with a named owner.</b> The
 * {@code CHECK} on the column refuses a value the enum does not have (ADR invariant T1); whether a
 * <em>move</em> is legal is decided here, and a path that skips it is a review finding rather than a
 * missing constraint. The failure is {@link InvalidStatusTransitionException}, a {@code 409}, and it
 * names both ends of the refused move.</p>
 */
public final class DocumentStates {

    private DocumentStates() {}

    /**
     * Whether {@code from} declares {@code to} as a successor.
     *
     * <p>{@code null} answers {@code false} on either side: a document with no state cannot move, and a
     * request with no target is not a move. Callers reach this case only through a missing value, which
     * is a {@code 409} and not a {@code NullPointerException} in the middle of a transaction.</p>
     */
    public static <S extends Enum<S> & DocumentState<S>> boolean canTransition(S from, S to) {
        return from != null && to != null && from.successors().contains(to);
    }

    /** The same question, refused with the two ends named. */
    public static <S extends Enum<S> & DocumentState<S>> void requireTransition(S from, S to) {
        if (!canTransition(from, to)) {
            throw new InvalidStatusTransitionException(
                    from == null ? "missing" : from.name(), to == null ? "missing" : to.name());
        }
    }

    /**
     * Whether the document is final: no successor exists, so no move is legal.
     *
     * <p>A state the family has no constant for is terminal by construction as well — a row whose
     * state the enum lacks cannot be moved, and the {@code CHECK} makes that row unreachable.</p>
     */
    public static <S extends Enum<S> & DocumentState<S>> boolean isTerminal(S state) {
        return state == null || state.successors().isEmpty();
    }

    /** The successors of {@code state}, or the empty set when there is no state to ask about. */
    public static <S extends Enum<S> & DocumentState<S>> Set<S> successorsOf(S state) {
        return state == null ? Set.of() : state.successors();
    }
}
