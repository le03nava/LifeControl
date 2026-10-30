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
 *   <li>{@code UsersAdminService#createUser} states the <b>subject alone</b> through
 *       {@link #withSubject(String, Supplier)}: the Keycloak user is created inside that call, so the
 *       subject its {@code user_preferences} row carries did not exist when the transaction boundary
 *       was entered. It is the one caller of the subject half that is not the request filter.</li>
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
 * clears in its {@code finally}; {@link #with(UUID, Supplier)} and {@link #withSubject(String, Supplier)}
 * restore whatever was there before in their own, through the same private helper. A thread that serves a
 * second request therefore starts with no scope rather than with the previous tenant's, which is a
 * fail-closed direction: a transaction with no scope reads <b>no</b> tenant row, not another tenant's.</p>
 *
 * <p><b>An absent scope is a refusal, never a wider scope.</b> Both halves of {@link Scope} are nullable
 * on purpose, and each one has a caller that states it alone. A request whose path names no company
 * (health, discovery, the Keycloak callbacks, a catalog endpoint, or a tenant-agnostic path that resolves
 * its own claims) carries the subject alone; a platform thread that works on one task carries the company
 * alone. The transaction manager then leaves the setting it has no value for as it is, which — on a pooled
 * connection that once set it — is the empty string {@code nullif(..., '')} already filters every row
 * with. Setting it to {@code NULL} explicitly would cost a round trip per transaction and change nothing,
 * which is why it is not done (trap 3 of the T4 brief).</p>
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
     * <p>The access-provisioning worker states this scope once per task and runs the <b>whole</b> task
     * inside it — not only the employee read — because the gate, the role convergence, the membership
     * derivation and the account update read {@code employees}, {@code departments} and {@code companies}
     * as well, and a wrap around one read would leave those reading zero rows once ADR-0005 arms
     * them.</p>
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
        return runWith(new Scope(companyId, null), work);
    }

    /**
     * Runs {@code work} with {@code subject} as the scope's subject and no company, restoring whatever
     * scope the thread carried before it — which is a plain clear when it carried none.
     *
     * <p>For the one write whose subject does not exist yet when the transaction begins: creating a
     * Keycloak user creates the {@code sub} it is about, so nothing upstream could have stated it, and the
     * {@code user_preferences} row that insert writes is isolated by {@code keycloak_user_id =
     * app.user_id} (ADR-0005's {@code subject} kind). The caller states the id it just received, and the
     * repository's own transaction is what installs it before the insert.</p>
     *
     * <p>The save/restore is the same one {@link #with(UUID, Supplier)} uses, deliberately: two copies of
     * "restore what was there, or remove the entry" would be two places to get the reused-thread rule
     * wrong. {@code subject} may be {@code null}, exactly as the company half may, and for the same
     * reason — an absent fact installs nothing and the policy filters every row.</p>
     *
     * @param subject the {@code sub} the work writes for, or {@code null} when it could not be determined
     * @param work the work to run inside that scope
     * @return whatever {@code work} returns
     */
    public static <T> T withSubject(String subject, Supplier<T> work) {
        return runWith(new Scope(null, subject), work);
    }

    /**
     * The one save/restore every scoped call shares: state {@code scope} for the duration of {@code work}
     * and put back exactly what the thread carried before, in a {@code finally}. Restoring instead of
     * unconditionally clearing is what makes a nested call harmless — the outer scope survives the inner
     * one — and removing the entry when there was none is what keeps a reused thread from inheriting a
     * value it never stated.
     */
    private static <T> T runWith(Scope scope, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(scope);
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
