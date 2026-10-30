package com.lifecontrol.api.common.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant scope of the operation running on the current thread: the company whose rows the caller may
 * see, and the subject whose {@code user_preferences} rows are theirs.
 *
 * <p><b>This is the single source of truth for ADR-0005's scope.</b> It carries the two facts a
 * transaction has to state before its first query — {@code app.company_id} and {@code app.user_id} — and
 * it exists as a thread-confined holder because three very different callers have to reach it without
 * becoming each other's dependencies:</p>
 *
 * <ul>
 *   <li>{@link TenantScopeFilter} <b>sets</b> it once per request, after authentication has decided who
 *       the caller is and before any controller runs.</li>
 *   <li>{@link TenantScopeJpaTransactionManager} <b>reads</b> it at every transaction boundary and
 *       installs it there. That indirection is the whole design: the scope is installed inside the
 *       transaction and before its first query, which a servlet filter cannot do because it owns no
 *       transaction (record L13 of {@code odd/tasks/tenant-isolation-rls.md}).</li>
 *   <li>The non-request threads — today the access-provisioning worker — <b>state their own</b> scope
 *       through {@link #with(UUID, Supplier)}, one tenant at a time, because no filter ever runs for
 *       them.</li>
 * </ul>
 *
 * <p><b>Why a holder instead of a bean.</b> A bean would force every one of those callers to inject it,
 * and two of them cannot: the transaction manager is built by a bean factory method that
 * {@code spring-boot-jpa} also defines (the holder would have to be threaded through that signature, and
 * through the constructor of a class whose unit suite this slice may not edit), and the filter is
 * constructed by the security chain. The static-holder shape is the one the tree already trusts for
 * exactly this kind of request-scoped fact — {@code SecurityContextHolder} is the same pattern — and the
 * risk it carries (a value surviving on a reused thread) is bounded by the one rule below.</p>
 *
 * <p><b>The rule that makes it safe: whoever sets it clears it, in a {@code finally}.</b> The filter
 * clears in its {@code finally}; {@link #with(UUID, Supplier)} restores whatever was there before in its
 * own. A thread that serves a second request therefore starts with no scope rather than with the previous
 * tenant's, which is a fail-closed direction: a transaction with no scope reads <b>no</b> tenant row, not
 * another tenant's.</p>
 *
 * <p><b>An absent scope is a refusal, never a wider scope.</b> Both components are nullable on purpose.
 * A request whose path names no company (health, discovery, the Keycloak callbacks, or a catalog endpoint
 * that resolves its company from a claim) carries the subject alone; the transaction manager then leaves
 * {@code app.company_id} as it is, which — on a pooled connection that once set it — is the empty string
 * {@code nullif(..., '')} already filters every row with. Setting it to {@code NULL} explicitly would cost
 * a round trip per transaction and change nothing, which is why it is not done (trap 3 of the T4
 * brief).</p>
 */
public final class TenantScope {

    /**
     * The scope of the operation on this thread. {@code remove()} rather than {@code set(null)} so a
     * reused thread also loses the map entry, and not only the value.
     */
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantScope() {}

    /**
     * The two facts ADR-0005 needs from one operation.
     *
     * @param companyId the company whose rows this operation may read and write, or {@code null} when the
     *     operation names none
     * @param subject the {@code sub} of the authenticated caller, or {@code null} for a platform thread
     *     that has no caller
     */
    public record Scope(UUID companyId, String subject) {}

    /** States the scope of the operation that starts here, replacing any scope already on the thread. */
    public static void set(UUID companyId, String subject) {
        CURRENT.set(new Scope(companyId, subject));
    }

    /** Ends the scope of the operation that started it. Safe to call twice, and safe when unset. */
    public static void clear() {
        CURRENT.remove();
    }

    /** The scope of the current operation, or empty when this thread carries none. */
    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Runs {@code work} with {@code companyId} as the scope's company and no subject, restoring whatever
     * scope the thread carried before it — which is a plain clear when it carried none.
     *
     * <p>For the paths that have no request: a scheduled process knows the tenant of the row it is working
     * on and nothing else, so it states that tenant here and every transaction {@code work} opens installs
     * it. Restoring instead of unconditionally clearing is what makes a nested call harmless: the outer
     * scope (a request's, say) survives the inner one.</p>
     *
     * <p>{@code companyId} may be {@code null}, and then the work runs with no company scope — the
     * fail-closed case, and deliberately not an exception: the caller's own "not found" branch is the
     * honest outcome, and throwing here would turn a missing tenant into a different failure.</p>
     *
     * @param companyId the tenant of the work, or {@code null} when it could not be determined
     * @param work the work to run inside that scope
     * @return whatever {@code work} returns
     */
    public static <T> T with(UUID companyId, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(new Scope(companyId, null));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
