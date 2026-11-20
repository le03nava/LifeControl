package com.lifecontrol.api.fiscal.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks PostgreSQL for the tenants that have work, through the {@code SECURITY DEFINER} function the roles script
 * owns ({@code S3}, {@code T3} of {@code odd/tasks/fiscal-cancellation-cadence.md}).
 *
 * <p><b>The shape follows {@code AccessProvisioningTaskTenantResolutionImpl}, on purpose.</b> That class is the tree's
 * first escape from a forced policy — one fixed query, one uuid back, {@code EXECUTE} for the application role and
 * for nobody else — and a second escape that invented its own shape would be a second thing to review. What differs
 * is only the question: this one answers <em>which</em> tenants, so the function returns {@code SETOF uuid} and the
 * pass loops.</p>
 *
 * <p><b>A database without the function fails loudly, and that is the point.</b> The function is created by
 * {@code docker/postgres-init/lifecontrol-roles.sql} and applied by {@code ./docker/scripts/db-roles.sh <env>} on
 * every deploy. A database in the other state — a restored cluster whose Flyway history is complete, so nothing
 * re-checks the roles — must not read zero rows: "no tenant has work" and "the roles were never applied" would print
 * the same green pass, which is exactly the silent emptiness ADR-0005 names. So the catalog is asked first and the
 * absence raises with the command that fixes it. The probe is not cached: it is one catalog lookup on the same
 * connection the read uses, and a suite that drops the function inside its own transaction must be able to observe
 * that state.</p>
 *
 * <p><b>Why there is no fallback for other engines.</b> The provisioning resolver carries one because the default test
 * profile runs H2 and its worker has a second derivation that is legal there. This pass has none, and the asymmetry is
 * the decision: it is a scheduled job that runs only where the roles exist, its unit tests drive it through a fake
 * {@link FiscalCancellationTenantResolver}, and a plain-engine fallback would be a second enumeration path that no
 * deployment ever executes — a door opened for a room nobody enters.</p>
 */
@Component
public class FiscalCancellationTenantResolverImpl implements FiscalCancellationTenantResolver {

    /**
     * The resolver the roles script owns: one fixed query, one row per tenant with work.
     *
     * <p>The function is a set-returning one, so it is read in the {@code FROM} clause with a column alias of its own —
     * {@code select company_id from f()} would name a column the function's signature never declared, and PostgreSQL
     * refuses it as a grammar error rather than guessing ({@code T6} measured it: five cases, one message).</p>
     */
    static final String RESOLVER =
            "select tenant.company_id from public.fiscal_cancellation_pending_tenants() as tenant(company_id)";

    /** The catalog question that separates "the roles were applied" from "they were not". */
    private static final String RESOLVER_DEFINED =
            "select to_regprocedure('public.fiscal_cancellation_pending_tenants()') is not null";

    private static final Logger logger = LoggerFactory.getLogger(FiscalCancellationTenantResolverImpl.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * <p>The read is {@code readOnly} because it only ever selects, and it runs with whatever scope the calling
     * thread carries — deliberately none: the function is {@code SECURITY DEFINER}, so it answers from its owner's
     * rights and the policy does not apply to it.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<UUID> findCompaniesWithPendingCancellations() {
        if (!resolverExists()) {
            throw new IllegalStateException("fiscal_cancellation_pending_tenants() does not exist, so this database "
                    + "was never given the ADR-0005 roles the cancellation pass needs. Run "
                    + "docker/scripts/db-roles.sh <env> (docker/postgres-init/lifecontrol-roles.sql, step 6b): without "
                    + "it the pass enumerates no tenant at all and reports an idle system while cancellations wait.");
        }

        List<?> rows = entityManager.createNativeQuery(RESOLVER).getResultList();
        List<UUID> companies = rows.stream()
                .filter(Objects::nonNull)
                .map(FiscalCancellationTenantResolverImpl::asCompanyId)
                .distinct()
                .toList();
        logger.debug("Fiscal cancellation pass enumerated {} tenant(s) with pending work", companies.size());
        return companies;
    }

    /** Whether the roles script has been applied to this database. Asked on every call, never cached. */
    private boolean resolverExists() {
        return Boolean.TRUE.equals(
                entityManager.createNativeQuery(RESOLVER_DEFINED).getSingleResult());
    }

    /** The driver may hand back a {@code UUID} or its text rendering; both name the same company. */
    private static UUID asCompanyId(Object row) {
        if (row instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(row.toString());
    }
}
