package com.lifecontrol.api.provisioning.dto;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of a company's <b>decision queue</b>: a task waiting for a human, with the employee it
 * belongs to (unit W5d, slice B, record T94).
 *
 * <p>It is deliberately <b>narrower</b> than {@link AccessProvisioningTaskView}, which is the task as
 * the employee's own section reads it. A queue row has to answer three questions and no more: who is
 * waiting (the employee, by id, number and name, so the row can be acted on and navigated from), which
 * intent is waiting ({@code kind}) and in which state ({@code status}), and who asked for it and when
 * ({@code requestedBy}/{@code requestedByName}/{@code requestedAt}). The section's view carries
 * {@code attempts}, {@code maxAttempts}, {@code lastError}, {@code nextAttemptAt}, {@code decidedBy},
 * {@code decidedAt} and {@code enabled} because an operator monitors one task's whole life there; a
 * decision queue that carried them would render a job monitor and pay for it on every row.</p>
 *
 * <p><b>The frozen reviewed set is deliberately not here.</b> A decision renders against the set its
 * task froze (record T92), and the dialog that decides reads it from the employee's own overview —
 * the same read the Access section performs — so the queue stays a list of what is pending and the
 * payload does not carry a role set per row (record T95).</p>
 *
 * <p><b>The status travels even though the queue's only status is {@code APPROVAL_PENDING}</b>: the
 * queue is defined by the read that composes it, and a payload that leaned on that definition would
 * have to change the day the queue widens. The field makes the row self-describing at the cost of one
 * enum name.</p>
 *
 * <p><b>The employee name travels as its three parts and not as a composed string.</b> This
 * application's only composition rule for a person's display name lives on the client
 * ({@code employee-list}'s own {@code fullName}), and the employee payload already ships the parts
 * ({@code firstName}, {@code paternalLastName}, {@code maternalLastName}). Sending a
 * {@code fullName} here would be a <b>second</b> rule for the same string — the drift class this
 * record keeps closing.</p>
 *
 * @param taskId the task the decision is about, which {@code approve}/{@code reject} need to name
 * @param kind the task's immutable reference (record T3)
 * @param status the task's life-cycle status, which for this queue is {@code APPROVAL_PENDING}
 * @param requestedBy who asked for the task, as the row records them — the JWT {@code sub} (T73), the
 *     same value the self-approval rule compares (O3)
 * @param requestedByName the requester's display name as the identity provider answers it, or
 *     {@code null} when it cannot be resolved (record T93, gap G42)
 * @param requestedAt when it was asked for
 * @param employeeId the employee the task belongs to, so the row can open their Access section
 * @param employeeNumber the employee's operator-typed number (decision D5)
 * @param employeeFirstName the employee's first name
 * @param employeePaternalLastName the employee's paternal last name
 * @param employeeMaternalLastName the employee's maternal last name; {@code null} when not recorded
 */
public record AccessProvisioningQueueEntry(
        UUID taskId,
        AccessProvisioningTaskKind kind,
        AccessProvisioningTaskStatus status,
        String requestedBy,
        String requestedByName,
        LocalDateTime requestedAt,
        UUID employeeId,
        String employeeNumber,
        String employeeFirstName,
        String employeePaternalLastName,
        String employeeMaternalLastName) {}
