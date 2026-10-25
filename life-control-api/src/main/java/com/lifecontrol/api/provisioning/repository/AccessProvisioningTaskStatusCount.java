package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;

/**
 * One row of the queue's grouped read: how many tasks the table holds in one status.
 *
 * <p>Projected straight into this record through a JPQL constructor expression, following
 * {@code SchedulingCalendarSlotProjection}: the queue's depth is <b>one</b> aggregate read instead of
 * one count per status, and the caller still gets a typed value rather than an {@code Object[]} it has
 * to index positionally.</p>
 *
 * <p>A status with no rows is <b>absent</b> from the grouped read rather than present with
 * {@code 0} — {@code GROUP BY} cannot invent a group that does not exist. Whoever publishes these
 * numbers therefore has to treat "missing" as zero, and the gauge component that consumes this record
 * does exactly that.</p>
 *
 * @param status the status the count belongs to
 * @param count how many task rows carry it
 */
public record AccessProvisioningTaskStatusCount(AccessProvisioningTaskStatus status, long count) {}
