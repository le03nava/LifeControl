package com.lifecontrol.api.common.state;

import java.util.Set;

/**
 * One document family's closed state set, and the only declaration of its legal moves (ADR-0004
 * decisions 1–3).
 *
 * <p>Each family is a Java enum whose constants are exactly the values the table's {@code CHECK}
 * admits. The successors are declared in the enum's own {@code switch} over {@code this}, which is what
 * makes adding a constant a compile error until its row is written: an exhaustive switch over an enum
 * has no {@code default}, so a new state cannot be added and silently end up with no outgoing
 * transition.</p>
 *
 * <p><b>Why the successor set is not a constructor argument.</b> {@code DRAFT(Set.of(ACTIVE))} inside
 * the constant list is an illegal forward reference to another constant of the same enum, and the
 * workarounds (a static map filled after the constants, or reflexive lookups by name) put the set one
 * hop away from the constants it describes. A {@code switch} in {@code successors()} keeps the
 * declaration next to the enum and lets the compiler check it.</p>
 *
 * <p>The guard that decides is {@link DocumentStates#canTransition}; this interface only states the two
 * facts a family answers — which subject it is, and what may follow what.</p>
 *
 * @param <S> the family's own enum, so a successor set can never mix two families
 */
public interface DocumentState<S extends Enum<S> & DocumentState<S>> {

    /** The family this state belongs to: the {@code status_transition.document_type} of its rows. */
    StatusSubject subject();

    /**
     * The states reachable from this one in a single move, and nothing else. An empty set is a terminal
     * state: the document is final and no path may move it again.
     */
    Set<S> successors();
}
