package com.lifecontrol.api.accounting.catalog;

import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Seeds {@link StarterAccountingCatalog} into a company as soon as it is created, so a company created after
 * {@code V98__accounting_starter_catalog.sql} does not arrive without a book (S9 of
 * {@code odd/tasks/derived-accounting.md}).
 *
 * <p>{@code BEFORE_COMMIT} and not {@code AFTER_COMMIT}, and that is the whole of S10's second half: the book
 * is not a convenience seed, it is the company's book, so "a company exists" has to imply "its accounts and
 * its posting rules exist". Joining the creation transaction is what makes that implication true — a failure
 * rolls the company back instead of leaving a tenant that cannot post — and it is the shape the maintainer
 * chose after the
 * {@code AFTER_COMMIT} version was measured to log-and-swallow, which left a company with no book and no
 * failing test. The product-category listener this class otherwise mirrors still uses {@code AFTER_COMMIT}
 * deliberately: a missing category is not a short book.</p>
 *
 * <p>The failure is no longer caught. The event fires before the commit, so letting the exception propagate is
 * what rolls the creation back, and an operator reads one failure for the company they were creating instead
 * of discovering an empty book months later. A missing posting produces no error at read time — the books
 * simply come out short — so there is nothing downstream that would report it.</p>
 *
 * <p>{@code fallbackExecution = true} because a {@code BEFORE_COMMIT} listener is skipped entirely when the
 * event is published with no transaction active, and "no transaction" is not the same as "no company was
 * created". {@code CompanyService.createCompany} is transactional today, which is exactly why a future path
 * that is not — an operator script, a bulk import, a requeued job — would otherwise create tenants whose book
 * is silently missing while every test stays green. Running the (idempotent) seed is always better than
 * skipping it.</p>
 */
@Component
public class StarterAccountingCatalogListener {

    private static final Logger logger = LoggerFactory.getLogger(StarterAccountingCatalogListener.class);

    private final StarterAccountingCatalogApplier applier;

    public StarterAccountingCatalogListener(StarterAccountingCatalogApplier applier) {
        this.applier = applier;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onCompanyCreated(CompanyCreatedEvent event) {
        var inserted = applier.applyTo(event.getId());
        logger.info(
                "Starter accounting catalog applied to company: id={}, rows inserted={}, accounts={}, postingRules={}",
                event.getId(),
                inserted,
                StarterAccountingCatalog.accounts().size(),
                StarterAccountingCatalog.postingRules().size());
    }
}
