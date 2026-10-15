import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
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
 * - **No control the API would refuse** (record O3/T95). `apply` and `deactivate` are hidden while a
 *   task is open (both answer 409), `retryRequest` only exists for a `FAILED` task, `resend` only for
 *   a linked account, and the decision control is hidden when the open task's `requestedBy` is the
 *   caller's own `sub`.
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
