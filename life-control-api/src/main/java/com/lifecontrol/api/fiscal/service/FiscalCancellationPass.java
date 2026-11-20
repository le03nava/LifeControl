package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalCancellationTenantResolver;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Settles every submitted cancellation the authority has answered, without an operator per document — {@code T5} of
 * {@code odd/tasks/fiscal-cancellation-cadence.md}, whose requirement is {@code S1} of issue {@code #994}.
 *
 * <p><b>What this class is not.</b> It does not talk to the authority, does not write a status and does not know what
 * an acceptance is. Both of those are {@link FiscalCancellationReconciliationService}, which the endpoint already
 * drives; this pass is the second caller of the same settlement, on a cadence instead of on a click. A pass with its
 * own settlement logic would be a second place the four answers of {@code S5} could drift.</p>
 *
 * <p><b>Why the tenant loop is this class's whole job.</b> The pass is the only thing in the tree that works on more
 * than one tenant, and {@code TenantScope} is a thread-confined value with one rule: whoever states it clears it. So
 * the loop states one company at a time through {@link TenantScope#with}, runs everything that touches a policied row
 * inside that scope — the candidate read and every settlement — and lets the guard restore what was there before.
 * Nothing here holds a scope across tenants, and nothing enumerates rows across tenants: the tenant list comes from
 * {@link FiscalCancellationTenantResolver}, which answers company ids and nothing else ({@code S3}).</p>
 *
 * <p><b>Why the candidate read filters and the settlement does not have to.</b> The read asks for rows still waiting
 * — {@code CANCELLATION_PENDING} with no settle stamp — so a document settled by an earlier pass is not offered twice
 * and a rejection, which keeps the state and takes the stamp, has already left the set. That is {@code S4} and
 * {@code S5} held structurally rather than by a check; the settlement's own short-circuit is the second belt, and the
 * suite asserts both.</p>
 *
 * <p><b>Why a failure is contained per document and abandoned per tenant.</b> One authority that cannot be read is one
 * document's problem and must not stop the other tenants; a tenant whose candidate read fails is a tenant this pass
 * could not work, and it is counted and logged so the next pass retries it. What is <b>not</b> contained is the pass
 * itself: an enumeration failure escapes to the scheduler, where Spring logs it and keeps the schedule alive, because
 * catching it would make a dead query look exactly like an idle system — the failure mode the whole unit removes.</p>
 */
@Service
public class FiscalCancellationPass {

    private static final Logger logger = LoggerFactory.getLogger(FiscalCancellationPass.class);

    private final FiscalCancellationTenantResolver tenantResolver;
    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalCancellationReconciliationService reconciliationService;
    private final ObjectProvider<FiscalCancellationReconciliationPort> reconciliationPort;

    public FiscalCancellationPass(
            FiscalCancellationTenantResolver tenantResolver,
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalCancellationReconciliationService reconciliationService,
            ObjectProvider<FiscalCancellationReconciliationPort> reconciliationPort) {
        this.tenantResolver = tenantResolver;
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.reconciliationService = reconciliationService;
        this.reconciliationPort = reconciliationPort;
    }

    /**
     * Runs one pass: enumerate the tenants with work, then settle what each one is waiting on.
     *
     * <p><b>Why an absent reconciler is a skip and not a failure.</b> The reconciling port is the provider bean, and a
     * deployment that enabled the pass without one would fail identically for every document it touched. Reporting
     * that as N document failures, every interval, would bury the one fact worth reading — <em>this deployment cannot
     * settle anything</em> — under a count that looks like an authority problem. So it is stated once, per pass, and
     * the pass does nothing else.</p>
     *
     * @return what the pass did, for the log line and for the suite that asserts it
     */
    public Report runPass() {
        if (reconciliationPort.getIfAvailable() == null) {
            logger.info("Fiscal cancellation pass skipped: no reconciling port is configured in this deployment, so "
                    + "a submitted cancellation cannot be settled by anybody");
            return Report.nothingToDo();
        }

        List<UUID> companies = tenantResolver.findCompaniesWithPendingCancellations();

        int documents = 0;
        int settled = 0;
        int waiting = 0;
        int failed = 0;
        int abandonedTenants = 0;

        for (UUID companyId : companies) {
            try {
                TenantReport tenant = TenantScope.with(companyId, () -> settleTenant(companyId));
                documents += tenant.documents();
                settled += tenant.settled();
                waiting += tenant.waiting();
                failed += tenant.failed();
            } catch (RuntimeException failure) {
                // A tenant this pass could not work is not a reason to abandon the others: the next pass retries it,
                // and the count is what makes an operator able to see that it keeps happening.
                abandonedTenants++;
                logger.warn(
                        "Fiscal cancellation pass left company {} for the next pass: {}",
                        companyId,
                        failure.toString(),
                        failure);
            }
        }

        var report = new Report(false, companies.size(), documents, settled, waiting, failed, abandonedTenants);
        logger.info(
                "Fiscal cancellation pass: companies={}, documents={}, settled={}, waiting={}, failed={}, "
                        + "abandonedTenants={}",
                report.companies(),
                report.documents(),
                report.settled(),
                report.waiting(),
                report.failed(),
                report.abandonedTenants());
        return report;
    }

    /**
     * One tenant's slice of the pass: read its candidates, settle each one, count what happened.
     *
     * <p>Called inside {@link TenantScope#with}, so every read and every settlement transaction below opens with that
     * company installed as {@code app.company_id}. The pass never calls this method outside the wrapper.</p>
     */
    private TenantReport settleTenant(UUID companyId) {
        List<FiscalDocument> candidates =
                fiscalDocumentRepository.findByCompanyIdAndStatusAndCancellationSettledAtIsNull(
                        companyId, FiscalDocument.STATUS_CANCELLATION_PENDING);

        int settled = 0;
        int waiting = 0;
        int failed = 0;

        for (FiscalDocument candidate : candidates) {
            try {
                var answer = reconciliationService.reconcileAsSystem(companyId, candidate.getCancelsFiscalDocumentId());
                if (answer.settled()) {
                    settled++;
                } else {
                    waiting++;
                }
            } catch (RuntimeException failure) {
                // One document's authority answer is that document's fact. It stays a candidate — nothing was
                // written — and the next pass reads it again, which is the honest state for "we could not ask".
                failed++;
                logger.warn(
                        "Fiscal cancellation pass could not settle cancellation {} of company {}: {}",
                        candidate.getId(),
                        companyId,
                        failure.toString(),
                        failure);
            }
        }

        logger.debug(
                "Fiscal cancellation pass worked company {}: documents={}, settled={}, waiting={}, failed={}",
                companyId,
                candidates.size(),
                settled,
                waiting,
                failed);
        return new TenantReport(candidates.size(), settled, waiting, failed);
    }

    /**
     * What one pass did, counted by outcome.
     *
     * @param skipped whether the pass ran at all, which is false only when no reconciling port exists
     * @param companies the tenants the resolver named, worked or abandoned
     * @param documents the candidates read across those tenants
     * @param settled how many of them finished with an answer (accepted or rejected)
     * @param waiting how many moved nothing because the authority had not decided
     * @param failed how many could not be settled for a reason of their own, and stay candidates
     * @param abandonedTenants how many tenants the pass could not work at all
     */
    public record Report(
            boolean skipped, int companies, int documents, int settled, int waiting, int failed, int abandonedTenants) {

        /** The pass found nothing it could do, because this deployment has no reconciling port. */
        static Report nothingToDo() {
            return new Report(true, 0, 0, 0, 0, 0, 0);
        }
    }

    /** One tenant's counts, so the loop above adds numbers instead of guessing them. */
    private record TenantReport(int documents, int settled, int waiting, int failed) {}
}
