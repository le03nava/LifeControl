package com.lifecontrol.api.fiscal.repository;

import java.util.List;
import java.util.UUID;

/**
 * Names the companies that have a submitted cancellation nobody has settled yet — the one read the scheduled
 * cancellation pass cannot perform inside a tenant scope (issue {@code #994}, {@code S3}).
 *
 * <p><b>Why a resolver exists at all.</b> {@code fiscal_document} is armed with {@code ENABLE} <b>and</b>
 * {@code FORCE} since {@code V108:218}, so a connection carrying no {@code app.company_id} reads zero rows — and a
 * scheduler thread carries no scope, because the filter that states it only runs for a request. The pass therefore
 * cannot ask "what is waiting?" the way a caller would; it has to be <i>told</i> which tenants have work, and then
 * state each one's scope itself.</p>
 *
 * <p><b>Why the contract answers companies and not rows.</b> The narrowest door that makes the pass possible is the
 * set of tenants, and this type is where that narrowing is stated. Handing a scheduled thread the rows would give it
 * every tenant's documents — amounts, RFCs, fiscal UUIDs — in one result; handing it company ids gives it exactly
 * what it needs to ask a policied question per tenant, and nothing a policy would otherwise have hidden. The same
 * reasoning is written next to the function in {@code docker/postgres-init/lifecontrol-roles.sql} (step 6b), which is
 * the only place the bypass is granted.</p>
 *
 * <p><b>What this type is not.</b> It is not a repository: it answers one fixed query through a
 * {@code SECURITY DEFINER} function, and it must not grow a second one. Every other read the pass performs goes
 * through {@link FiscalDocumentRepository} inside a scope.</p>
 */
public interface FiscalCancellationTenantResolver {

    /**
     * The companies that have at least one submitted cancellation no answer has settled.
     *
     * <p>The candidate rule lives in the function, not here: a row counts while it is
     * {@code CANCELLATION_PENDING} and its settle stamp is empty, so a rejection — which keeps the state and
     * finishes the row with the stamp — has already left the set by the time this answers.</p>
     *
     * @return the tenant ids, each once; empty when nothing anywhere is waiting
     * @throws IllegalStateException when the database has no
     *     {@code fiscal_cancellation_pending_tenants()} function, which means the roles script was never applied to
     *     it — a broken deployment, never "no work to do"
     */
    List<UUID> findCompaniesWithPendingCancellations();
}
