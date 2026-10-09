package com.lifecontrol.api.provisioning.dto;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One durable access-provisioning task as the Access section reads it: the open intent and every
 * historical one.
 *
 * <p>Every field is a fact the row already carries (record T9): the <b>kind</b> and <b>status</b> are
 * the task's reference and its state, {@code requestedBy}/{@code requestedAt} record who asked and
 * when, {@code decidedBy}/{@code decidedAt} who approved or refused, and {@code lastError} is the
 * visible reason a task stopped — a failure is visible and never silent (record T10), and a rejection
 * reuses the same column (record T19).</p>
 *
 * <p><b>{@code attempts} is rendered against {@code maxAttempts}, not alone.</b> A task that failed
 * once and a task that is out of attempts are otherwise indistinguishable — both are {@code FAILED}
 * and the only difference is the count — so the Access section has to show the count against the
 * configured ceiling or "exhausted" becomes invisible and T43's ceiling has no reader. The maximum is
 * supplied by the caller from {@code ProvisioningWorkerProperties.maxAttempts}; this record does not
 * reach for configuration of its own.</p>
 *
 * <p>{@code enabled} is exposed verbatim as {@link com.lifecontrol.api.provisioning.model.AccessProvisioningTask#getEnabled()}
 * exposes it. It is a declared-but-unconsumed column (record T48): the read surface shows what the row
 * holds and gives the flag no meaning.</p>
 *
 * <p>The {@code id} is carried because the read is the only place an operator sees the open task and
 * the sibling gate unit's {@code approve}/{@code reject} actions need to name it; a task view without
 * its id could not be acted on.</p>
 *
 * @param id the task's own id, so the gate can name it
 * @param kind the task's immutable reference (record T3)
 * @param status the task's life-cycle status (record T2)
 * @param attempts how many times the task has been claimed (record T18)
 * @param maxAttempts the configured ceiling those attempts are rendered against (record T43)
 * @param lastError the visible reason the task is not applied: a failure (T10) or a rejection (T19)
 * @param nextAttemptAt the backoff deadline while the task waits; {@code null} means due immediately
 * @param requestedBy who asked for the task
 * @param requestedAt when it was asked for
 * @param decidedBy who approved or refused it; {@code null} until a gate decides
 * @param decidedAt when the gate decided; {@code null} until then
 * @param enabled the row's declared-but-unconsumed flag (record T48)
 */
public record AccessProvisioningTaskView(
        UUID id,
        AccessProvisioningTaskKind kind,
        AccessProvisioningTaskStatus status,
        int attempts,
        int maxAttempts,
        String lastError,
        LocalDateTime nextAttemptAt,
        String requestedBy,
        LocalDateTime requestedAt,
        String decidedBy,
        LocalDateTime decidedAt,
        boolean enabled) {}
