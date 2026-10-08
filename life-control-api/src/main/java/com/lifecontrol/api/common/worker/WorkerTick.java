package com.lifecontrol.api.common.worker;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker's tick: the mechanical loop that drives one bounded batch of due task ids through a
 * per-id action, as a <b>plain class</b> (record T44, record T51).
 *
 * <p>This class is the reusable half of the worker. It is a plain class with a constructor —
 * <b>not</b> a {@code @Component}, <b>not</b> a {@code @Configuration}, <b>not</b> a bean — and it
 * deliberately does <b>nothing</b> beyond the loop: no {@code @Transactional} (the transaction
 * boundary stays on the domain side, which is record T44's split of mechanics from domain), no
 * Keycloak call, no notion of a task kind, no property read, and no clock. The due ids arrive
 * already selected and already sorted by the repository query.</p>
 *
 * <p>The batch bound arrives as an explicit constructor parameter rather than a property read,
 * because the value's consumer is the scheduler in the next unit (record T45, record T52): this
 * class must stay provable without a Spring context, so it is handed its {@code limit} and never
 * looks one up.</p>
 */
public final class WorkerTick {

    private static final Logger log = LoggerFactory.getLogger(WorkerTick.class);

    private final int limit;

    /**
     * @param limit the maximum number of due ids this tick hands to the action
     */
    public WorkerTick(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, but was " + limit);
        }
        this.limit = limit;
    }

    /**
     * Walks {@code dueIds} in order and invokes {@code action} for at most {@link #limit} of them,
     * containing each item's failure so one bad row cannot abort the batch.
     */
    public TickReport run(List<UUID> dueIds, Consumer<UUID> action) {
        var attempted = 0;
        var failed = 0;
        for (var id : dueIds) {
            if (attempted >= limit) {
                break;
            }
            attempted++;
            try {
                action.accept(id);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Worker tick action failed for id {}", id, ex);
            }
        }
        return new TickReport(attempted, failed);
    }

    /**
     * The outcome of one tick: {@code attempted} counts every id handed to the action (failures
     * included) and {@code failed} counts the ones that threw, so {@code failed <= attempted}.
     */
    public record TickReport(int attempted, int failed) {}
}
