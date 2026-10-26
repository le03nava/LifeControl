package com.lifecontrol.api.product.category.catalog;

import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Seeds {@link StarterProductCategoryCatalog} into a company as soon as it is created, so a company
 * created after {@code V35__product_category_starter_catalog.sql} does not start with an empty category
 * tree.
 *
 * <p>Mirrors {@link com.lifecontrol.api.company.listener.KeycloakGroupEventListener}, and for the same
 * reason: both are side effects of creating a company that live outside the company's own transaction.
 * The phase is {@code AFTER_COMMIT} because <b>a convenience seed must never roll back the creation of a
 * company</b>. Joining the company's transaction would make a catalog problem — a lock timeout, a
 * duplicate the guard missed — undo a company that was created correctly, and the operator would see a
 * failure for a feature they never asked for. Committing first means the worst case is a company without
 * a starter catalog, which is the state every company was in before this feature and which an operator
 * can repair by hand.</p>
 *
 * <p>The failure is logged at {@code ERROR} instead of the {@code WARN} the Keycloak listener uses,
 * because the consequences are not the same. A missing Keycloak group is repaired by the next
 * provisioning run and is invisible until an operator is invited. A missing starter catalog is visible
 * on the Categories screen immediately, is what a company expects to exist, and has no automated repair
 * — the log line is the only record that this company will never receive it. {@code ERROR} is what makes
 * that reachable by an alert instead of buried in a healthy-looking log.</p>
 *
 * <p>The exception is never rethrown: the event has already committed, so there is nothing left to
 * abort, and throwing here would only leave the publisher of the event with a failure it cannot act on.
 * Nothing is cached or retried either — the company id in the log is the input for a manual re-run of
 * {@link StarterProductCategoryApplier#applyTo(java.util.UUID)}, which is safe to repeat.</p>
 *
 * <p>{@code fallbackExecution = true} because an {@code AFTER_COMMIT} listener is skipped entirely when
 * the event is published with no transaction active, and “no transaction” is not the same as “no company
 * was created”. {@code CompanyService.createCompany} is transactional today, which is exactly why a
 * future path that is not — an operator script, a bulk import, a requeued job — would otherwise create
 * tenants whose catalog is silently missing while every test stays green. Running the (idempotent) seed
 * is always better than skipping it.</p>
 */
@Component
public class StarterProductCategoryListener {

    private static final Logger logger = LoggerFactory.getLogger(StarterProductCategoryListener.class);

    private final StarterProductCategoryApplier applier;

    public StarterProductCategoryListener(StarterProductCategoryApplier applier) {
        this.applier = applier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCompanyCreated(CompanyCreatedEvent event) {
        try {
            var inserted = applier.applyTo(event.getId());
            logger.info(
                    "Starter product catalog applied to company: id={}, categories inserted={}",
                    event.getId(),
                    inserted);
        } catch (RuntimeException e) {
            logger.error(
                    "company created WITHOUT the starter product catalog: id={}, error={}",
                    event.getId(),
                    e.getMessage(),
                    e);
        }
    }
}
