package com.lifecontrol.api.config.provisioning;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publishes the access-provisioning worker's queue as Micrometer gauges, so a stalled queue is a
 * number Prometheus already scrapes instead of a log line somebody has to grep.
 *
 * <p><b>The defect this closes (S11).</b> Before it, {@code grep -rl
 * 'MeterRegistry\|Counter\|Gauge' life-control-api/src/main/java} returned nothing: the one component
 * in this service that mutates another system unattended had no meter at all. Its only signal was
 * logging, so a task stuck in {@code PENDING}, a queue that stopped draining and a retry storm were
 * indistinguishable from an idle worker — the same shape of blindness as the {@code WORKER_ENABLED}
 * default that once left every provisioning request queued forever.</p>
 *
 * <p><b>What is published, and under which names.</b> Micrometer names are dotted and Prometheus
 * derives the exposition names from them, so the two views are one registration rather than two
 * spellings that can drift:</p>
 *
 * <ul>
 *   <li>{@code access.provisioning.tasks} with a {@code state} tag → {@code
 *       access_provisioning_tasks{state="PENDING"}} and one series per value of {@link
 *       AccessProvisioningTaskStatus}, so {@code sum by (state)} answers "how many are waiting".</li>
 *   <li>{@code access.provisioning.oldest.pending.age} with base unit {@code seconds} → {@code
 *       access_provisioning_oldest_pending_age_seconds}, the age of the oldest row still {@code
 *       PENDING}. This is the number that turns "the queue has items" into "the queue has been stuck
 *       for an hour"; it reads {@code 0} when nothing is pending.</li>
 * </ul>
 *
 * <p><b>One gauge per state the entity actually has.</b> The series are registered from {@link
 * AccessProvisioningTaskStatus#values()}, so a state added to the enum is published without a second
 * edit here, and no state that the column cannot hold is invented. A state with no rows is reported as
 * {@code 0} rather than omitted: {@code GROUP BY} cannot produce a group that does not exist, and a
 * missing series would make "no pending work" and "this status is not instrumented" look alike in a
 * query.</p>
 *
 * <p><b>Why the refresh is not gated by {@code app.provisioning.worker.enabled}.</b> The queue is a
 * table, not a thread: rows keep arriving whether or not a worker drains them, and the state most
 * worth alerting on is precisely the one where the worker is off. Gating the refresh on the switch
 * would publish {@code 0} for a queue that is in fact growing — a gauge that lies in exactly the
 * incident it exists to surface — so the refresh always runs and reports the real table. The switch
 * already announces itself once at start-up ({@link ProvisioningWorkerStateLogger}); the gauge reports
 * work, not configuration.</p>
 *
 * <p><b>Why a dedicated thread instead of {@code @Scheduled}.</b> Spring's scheduling is enabled by
 * {@link ProvisioningWorkerSchedulerConfig}, which is itself gated on the worker switch: with the
 * worker off there is no {@code @EnableScheduling}, no scheduler thread pool and no registered task in
 * the context, and that structural off-state is deliberate. Adding {@code @EnableScheduling} here to
 * host this timer would resurrect the always-ticking scheduler infrastructure that configuration
 * refuses — for a metric. The component therefore owns one daemon thread, started when the context is
 * ready (never during bean initialisation, when the schema may still be migrating) and shut down with
 * the context. The executor is single-threaded with a fixed delay, so a refresh slower than the
 * interval delays the next one instead of stacking a second query behind it.</p>
 *
 * <p><b>Why cached values instead of a scrape-time supplier.</b> Micrometer evaluates a gauge's
 * function on every scrape, so a supplier that queried the database would put two SQL reads inside
 * {@code GET /actuator/prometheus} — a slow or locked table would then stall the scrape itself. The
 * gauges instead read counters this component refreshes on its own timer, and the timer is aligned
 * with Prometheus' {@code scrape_interval: 15s}: refreshing faster would spend queries on samples
 * nobody collects, slower would publish a sample older than the scrape that carries it.</p>
 *
 * <p><b>What a failed refresh does, and what it refuses to do.</b> The failure is logged and the last
 * successfully measured values stay in place. Writing {@code 0} on failure would be the worst
 * available answer — it reads as "the queue is empty" precisely when the database is unreachable — and
 * letting the exception escape would be worse still: {@link
 * ScheduledExecutorService#scheduleWithFixedDelay} cancels every subsequent execution when the task
 * throws, so one transient failure would silently freeze the gauge forever, which is the failure mode
 * this class exists to remove.</p>
 *
 * <p><b>The age is measured from {@code requested_at}, and the threshold has to know that.</b> {@code
 * requested_at} is when the intent entered the queue, which is what "waiting" means to an operator;
 * {@code next_attempt_at} is a deadline the worker writes itself, so measuring from it would report a
 * healthy retry backoff as if it were the wait. The consequence is stated rather than hidden: a row
 * waiting out a retry can age this gauge up to {@code
 * app.provisioning.worker.max-delay-seconds} (3600 by default), so an alert threshold below that
 * ceiling would fire on a queue that is working correctly.</p>
 */
@Component
public class AccessProvisioningQueueMetrics {

    /** Micrometer name; Prometheus exposes it as {@code access_provisioning_tasks}. */
    static final String TASKS_METRIC = "access.provisioning.tasks";

    /** Micrometer name; the {@code seconds} base unit makes Prometheus expose {@code ..._age_seconds}. */
    static final String OLDEST_PENDING_AGE_METRIC = "access.provisioning.oldest.pending.age";

    /** The tag that makes the task count answerable per state with {@code sum by (state)}. */
    static final String STATE_TAG = "state";

    /**
     * The one status whose age is published. {@code PENDING} is the only status the worker claims
     * ({@code AccessProvisioningTaskRepository#findDueTaskIds}), so it is the only one whose age
     * measures how long the automatic flow has been stuck; {@code APPROVAL_PENDING} waits for a human
     * and the remaining statuses are terminal or transient within one pass.
     */
    static final AccessProvisioningTaskStatus AGE_STATUS = AccessProvisioningTaskStatus.PENDING;

    /** Matches {@code scrape_interval: 15s} in {@code docker/config/prometheus/prometheus.yml}. */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(AccessProvisioningQueueMetrics.class);

    private final AccessProvisioningTaskRepository taskRepository;

    /**
     * The published values, one cell per state, iterated in enum order so the registered series have a
     * deterministic order. Held here (and strongly referenced by each gauge) because Micrometer gauges
     * hold their state object weakly: a cell that nothing referenced would be collected and the gauge
     * would start reporting {@code NaN}.
     */
    private final Map<AccessProvisioningTaskStatus, AtomicLong> tasksByStatus =
            new EnumMap<>(AccessProvisioningTaskStatus.class);

    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "access-provisioning-queue-metrics");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Registers the gauges and starts their cells at zero; the first measurement arrives with the first
     * refresh, which runs as soon as the context is ready.
     *
     * @param taskRepository the worker's own repository — this component adds no second query surface
     *     beyond a count and a minimum timestamp
     * @param meterRegistry the registry Prometheus scrapes
     */
    public AccessProvisioningQueueMetrics(
            AccessProvisioningTaskRepository taskRepository, MeterRegistry meterRegistry) {
        this.taskRepository = taskRepository;
        for (var status : AccessProvisioningTaskStatus.values()) {
            var cell = new AtomicLong();
            tasksByStatus.put(status, cell);
            Gauge.builder(TASKS_METRIC, cell, AtomicLong::doubleValue)
                    .tag(STATE_TAG, status.name())
                    .description("Access-provisioning tasks currently in this status;"
                            + " sum by (state) is the depth of the queue")
                    .strongReference(true)
                    .register(meterRegistry);
        }
        Gauge.builder(OLDEST_PENDING_AGE_METRIC, oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .baseUnit("seconds")
                .description("Age of the oldest PENDING access-provisioning task, in seconds;"
                        + " zero when nothing is pending")
                .strongReference(true)
                .register(meterRegistry);
    }

    /**
     * Starts the refresher once the application is ready.
     *
     * <p>Bound to {@link ApplicationReadyEvent} rather than to a {@code @PostConstruct}: the first
     * refresh reads {@code access_provisioning_tasks}, and during bean initialisation the schema may
     * still be migrating. The initial delay is zero because a gauge that stays at its placeholder until
     * the first interval elapses would publish "empty queue" for one scrape after every restart.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startRefreshing() {
        refresher.scheduleWithFixedDelay(this::refreshQuietly, 0, REFRESH_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Stops the daemon thread with the context so a redeploy leaves nothing behind. */
    @PreDestroy
    public void stopRefreshing() {
        refresher.shutdownNow();
    }

    /**
     * Re-reads the queue and republishes it. Package-private because it is the seam the tests drive:
     * the behaviour worth asserting is what the gauges report after a refresh, not how the timer is
     * wired.
     *
     * <p>The two reads are separate on purpose. They are independent questions ("how many per state",
     * "how old is the oldest pending"), and a single combined query would have to be a grouped
     * aggregate with a {@code MIN} that reports nothing for every state except the one asked about.</p>
     */
    void refresh() {
        var counts = new EnumMap<AccessProvisioningTaskStatus, Long>(AccessProvisioningTaskStatus.class);
        for (var row : taskRepository.countTasksByStatus()) {
            counts.put(row.status(), row.count());
        }
        for (var entry : tasksByStatus.entrySet()) {
            entry.getValue().set(counts.getOrDefault(entry.getKey(), 0L));
        }

        var oldest = taskRepository.findOldestRequestedAtByStatus(AGE_STATUS);
        // Clamped at zero: requested_at is written by this service's own clock, so a negative age can
        // only mean the two clocks disagree, and a negative age is not a value Prometheus should hold.
        oldestPendingAgeSeconds.set(
                oldest == null
                        ? 0L
                        : Math.max(
                                0L,
                                Duration.between(oldest, LocalDateTime.now()).toSeconds()));
    }

    /**
     * Refreshes, and contains a failure instead of letting it cancel the schedule. Package-private for
     * the same reason as {@link #refresh()}: the contract worth asserting — a failed read keeps the
     * last measurement and does not kill the timer — is observable at the gauge, and this is the seam
     * that triggers it without waiting a full interval.
     *
     * <p>The values are left untouched on failure — see the class javadoc for why zero would be a
     * lie and why an escaping exception would silently stop every later refresh.</p>
     */
    void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not refresh the access-provisioning queue gauges; the published values are the"
                            + " last successful read, not a measurement of the current queue.",
                    failure);
        }
    }
}
