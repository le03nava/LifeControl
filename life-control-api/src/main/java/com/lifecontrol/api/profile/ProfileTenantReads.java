package com.lifecontrol.api.profile;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.tenant.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a tenant-table read under <b>each</b> company the caller's token claims, one stated scope per
 * attempt, and answers with the first attempt that has something to say.
 *
 * <p><b>Why it exists.</b> {@code GET}/{@code PUT /api/profile} is on {@code TenantScopeFilter}'s
 * tenant-agnostic list, so the scope that path carries is {@code (company = null, subject = sub)} — a
 * statement about the caller, not about a company. Two of the reads the profile performs are armed by
 * ADR-0005 ({@code employees} and {@code company_stores}: {@code ENABLE} + {@code FORCE} row level
 * security on {@code company_id = current_setting('app.company_id')}), so with no company stated they
 * answered <b>zero rows</b> and the profile silently lost both the preselected chain and the store guard
 * (record of {@code odd/tasks/profile-tenant-scope.md}). The company the profile needs is not in the path
 * and not determinate from the claim — a token may carry several (S5) — so the read states one claim at a
 * time, which is the same resolution {@code /api/companies} performs and the reason that path is exempt
 * too.</p>
 *
 * <p><b>The order is the contract: scope first, transaction second, read third.</b>
 * {@code TenantScopeJpaTransactionManager#doBegin} reads the thread's {@link TenantScope} when the
 * transaction begins, so a boundary opened before the scope is stated would install the previous tenant
 * or none — and none is exactly the zero-row answer this class exists to end. The scope is therefore
 * stated <em>around</em> the boundary and not inside it, which is also why the boundary is a
 * {@link TransactionTemplate} and not {@code @Transactional} on the read: only the template can be opened
 * after the scope is stated.</p>
 *
 * <p><b>The caller's own transaction is suspended.</b> {@code NOT_SUPPORTED} on the entry point, for the
 * same reason {@link ProfileLocationResolver} carries it: a {@code RuntimeException} crossing a
 * <em>participating</em> boundary marks the caller's transaction rollback-only, and the profile read must
 * survive a store it cannot read. It is also what makes the attempt a real transaction of its own — the
 * only boundary that can install a scope the request did not state.</p>
 *
 * <p><b>The first present answer wins and nothing is merged.</b> A present {@code Optional} is the
 * answer for the company that produced it, including a present-but-empty list: that is "constrained to
 * exactly none" for that company, not "keep asking". A {@code null} returned by the callback is treated
 * as absent, because a template may hand back {@code null} and an unboxing the compiler cannot check is
 * not worth the risk.</p>
 *
 * <p><b>An attempt that answers nothing is rolled back, not committed.</b> Two things make that the only
 * shape that works. The read may <em>fail</em> inside the attempt — {@code ProfileLocationResolver} catches
 * the store read's not-found — and the read that threw is usually a participating {@code @Transactional}
 * one, which marks this attempt's transaction rollback-only on its way out. Committing such a status raises
 * {@code UnexpectedRollbackException} <b>after</b> the callback returned, i.e. outside every catch the
 * caller has, which would turn the degraded preselect S2 requires into a 500. Rolling the attempt back
 * explicitly ends it quietly, and the loop moves on to the next claimed company. The class is for reads
 * and only for reads, so an attempt that answered nothing has nothing to lose.</p>
 *
 * <p><b>No claimed company runs no read at all.</b> That is the fail-closed direction S3 requires: a
 * caller whose token claims nothing has nothing to derive these reads from, and a query under no scope
 * (or, worse, under whatever the previous tenant left on a reused thread) would answer an invented
 * question rather than a degraded one.</p>
 */
@Component
public class ProfileTenantReads {

    private final CurrentUserContext currentUserContext;
    private final TransactionTemplate transactionTemplate;

    public ProfileTenantReads(CurrentUserContext currentUserContext, PlatformTransactionManager transactionManager) {
        this.currentUserContext = currentUserContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs {@code read} under each claimed company, in sorted order for determinism, and returns the first
     * answer that is present.
     *
     * @param read the tenant-table read, executed inside the scoped transaction of one claimed company
     * @return the first present answer, or empty when no claimed company produced one — including the case
     *     where the token claims none, in which case {@code read} is never executed
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> Optional<T> underEachClaimedCompany(Supplier<Optional<T>> read) {
        for (UUID companyId : sortedClaimedCompanies()) {
            var answer = TenantScope.with(
                    companyId,
                    () -> transactionTemplate.execute(status -> {
                        var result = read.get();
                        if (result == null || result.isEmpty()) {
                            // Nothing to keep, and possibly a read inside this attempt that marked it
                            // rollback-only: end it here, quietly, instead of committing a doomed status.
                            status.setRollbackOnly();
                        }
                        return result;
                    }));
            if (answer != null && answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    /** The claimed companies as an ordered list: the iteration order of a {@code Set} is not a contract. */
    private List<UUID> sortedClaimedCompanies() {
        return currentUserContext.getCompanyIds().stream().sorted().toList();
    }
}
