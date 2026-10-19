import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import {
  ACCESS_CLAIM_FIELDS,
  AccessProvisioningClaims,
  AccessProvisioningOverview,
  AccessProvisioningTaskView,
  accessTaskKindLabel,
  accessTaskStatusLabel,
} from '../../models/access-provisioning.models';

/** The one status name the request surface refuses, as `V20` seeds it and `EmployeeStatuses` pins it. */
const TERMINATED_STATUS_NAME = 'Terminated';

/**
 * Whether `statusName` is the `Terminated` row of the `EMPLOYEE_STATUS` family.
 *
 * Case-insensitive, mirroring `EmployeeStatuses.isTerminated` (record T84) and `employee-edit`'s own
 * mirror: the seeded value is compared case-insensitively on the server, so a differently-cased
 * catalogue name must not slip through.
 */
function isTerminatedStatus(statusName: string | null): boolean {
  return statusName !== null && statusName.toLowerCase() === TERMINATED_STATUS_NAME.toLowerCase();
}

/**
 * The **Acceso** section of the employee detail.
 *
 * A **presentational** component in `store-assignments-section`'s shape: it takes the overview, the
 * section's own loading and error state, whether the caller may write and the caller's own `sub`,
 * and it emits the intents the page acts on (`retry` for the failed read, `apply`, `deactivate`,
 * `retryRequest`, `resend` and `decisionRequested`). It owns no HTTP access and no dialog: the page
 * performs the writes and opens the shared review dialog (record T95). A `HttpClient` or `MatDialog`
 * in here would be the coupling that split exists to prevent.
 *
 * Two rules shape what it renders, and both come from the API it mirrors:
 *
 * - **The frozen set is a different fact from the live diff** (record T92). `roleDiff` is recomputed
 *   on every read, while `frozenRoleDiff` is what a human is being asked to decide. The section
 *   renders both, labels the frozen one as the set the reviewer decides on, and when it is `null` it
 *   **says so** rather than drawing an empty set — the two are indistinguishable otherwise.
 * - **No control the API would refuse** (records O3/T84/T85/T95). `apply` and `deactivate` are hidden
 *   while a task is open (both answer 409), `retryRequest` only exists for a `FAILED` task, `resend`
 *   only for a linked account, and the decision control is hidden when the open task's `requestedBy`
 *   is the caller's own `sub`. On top of that, `apply` and `resend` are also hidden — with the reason
 *   stated in Spanish — when the employee is `Terminated` (T84) or the company has no deliverable
 *   `emailDomain` (T68/T85): both refusals are named 409s, and the operator must see which path
 *   applies instead of discovering it from a bounced message.
 */
@Component({
  selector: 'app-access-section',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './access-section.html',
  styleUrl: './access-section.scss',
})
export class AccessSection {
  /** The employee's access overview, or `null` before the first successful read. */
  readonly overview = input<AccessProvisioningOverview | null>(null);

  /** The section's own read state; the page resolves it from its resource. */
  readonly loading = input(false);
  readonly error = input<string | null>(null);

  /**
   * Whether the caller may write. Decided by the page on `ACCESS_READ_ROLES`, the pair the endpoints
   * themselves require.
   */
  readonly canWrite = input(false);

  /**
   * The caller's own Keycloak `sub`, as `CurrentUser.sub` reads it, or `null` when the token carries
   * none. The page supplies it so the section stays free of the auth path; it exists to hide the
   * decision control on the caller's own request (O3).
   */
  readonly currentUserId = input<string | null>(null);

  /**
   * The employee's status name, as the employee read carries it (`Employee.statusName`), or `null`
   * while that read has not resolved. It exists for the **terminated gate** (record T84): the API
   * refuses `apply` and `resend` with a named 409 for a `Terminated` employee, so the section must
   * not offer either control. The page supplies it because the access overview carries no status.
   */
  readonly employeeStatusName = input<string | null>(null);

  /**
   * The selected company's `emailDomain` (`Company.emailDomain`), as the companies read carries it.
   * It exists for the **deliverable-domain gate** (record T68/T85): the API refuses `apply` and
   * `resend` with a named 409 when the company has no usable domain, because nobody can be onboarded
   * without one. `undefined` means the company is not resolved yet and must not fire the gate;
   * `null` or a blank value means the domain is missing. The page supplies it because the access
   * overview carries no domain.
   */
  readonly companyEmailDomain = input<string | null | undefined>(undefined);

  /** Whether the employee holds the fixed `Terminated` status (record T84). */
  private readonly employeeTerminated = computed(() =>
    isTerminatedStatus(this.employeeStatusName()),
  );

  /**
   * Whether the company has a usable `emailDomain` (record T68/T85).
   *
   * `undefined` means the company is not resolved yet and must not fire the gate; `null` or a blank
   * value is a missing domain and does.
   */
  private readonly companyHasEmailDomain = computed(() => {
    const domain = this.companyEmailDomain();
    return domain === undefined || (domain !== null && domain.trim() !== '');
  });

  /**
   * Why `apply` and `resend` are not offered, or `null` when they are.
   *
   * The API refuses both by name (409) for a `Terminated` employee and for a company with no usable
   * domain, so the section **states which path applies** instead of leaving an empty gap where the
   * controls would have been (T68/T84/T85).
   */
  readonly accessBlockedReason = computed<string | null>(() => {
    if (this.employeeTerminated()) {
      return 'El empleado está dado de baja: no se puede solicitar acceso ni reenviar la invitación.';
    }
    if (!this.companyHasEmailDomain()) {
      return 'La empresa no tiene un dominio de correo configurado: sin un dominio entregable no se puede incorporar a nadie, así que no se puede solicitar acceso ni reenviar la invitación.';
    }
    return null;
  });

  /**
   * Whether the API reports the access-provisioning worker as **disabled** (record S3-D / `T5`).
   *
   * `false` is the only value that fires the notice: an absent `workerEnabled` (a backend older
   * than the additive field) means unknown, and asserting "the worker is off" from a field the
   * section never received would be the silent default this notice exists to surface.
   */
  readonly workerDisabled = computed(() => this.overview()?.workerEnabled === false);

  /** Re-runs the overview read that failed. */
  readonly retry = output<void>();

  /** Asks the page for the apply flow. */
  readonly apply = output<void>();

  /** Asks the page for the revocation flow. */
  readonly deactivate = output<void>();

  /** Asks the page to retry the failed open task. */
  readonly retryRequest = output<void>();

  /** Asks the page to re-send the activation invitation. */
  readonly resend = output<void>();

  /** A task awaiting a decision; the page opens the shared review dialog. */
  readonly decisionRequested = output<AccessProvisioningTaskView>();

  readonly claimFields = ACCESS_CLAIM_FIELDS;
  readonly statusLabel = accessTaskStatusLabel;
  readonly kindLabel = accessTaskKindLabel;

  /** The roles of one set, or an em dash when the set is empty. */
  roleList(roles: string[]): string {
    return roles.length > 0 ? roles.join(', ') : '—';
  }

  /** The values of one claim level, or an em dash when the level is not held. */
  claimValues(values: string[]): string {
    return values.length > 0 ? values.join(', ') : '—';
  }

  /** The claim list of one field, guarded against a payload that omits it. */
  claimValuesOf(claims: AccessProvisioningClaims, field: keyof AccessProvisioningClaims): string {
    return this.claimValues(claims[field] ?? []);
  }

  /** Whether the task is waiting for a human decision. */
  isAwaitingDecision(task: AccessProvisioningTaskView): boolean {
    return task.status === 'APPROVAL_PENDING';
  }

  /**
   * Whether the task was requested by the caller themselves (O3).
   *
   * An unknown caller (`null`) is never treated as the requester: the section cannot prove a
   * self-request without the `sub`, and the API stays the authority on the rule.
   */
  isSelfRequested(task: AccessProvisioningTaskView): boolean {
    const caller = this.currentUserId();
    return caller !== null && task.requestedBy === caller;
  }

  onApply(): void {
    this.apply.emit();
  }

  onDeactivate(): void {
    this.deactivate.emit();
  }

  onRetryRequest(): void {
    this.retryRequest.emit();
  }

  onResend(): void {
    this.resend.emit();
  }

  onDecide(task: AccessProvisioningTaskView): void {
    this.decisionRequested.emit(task);
  }
}
