import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { startWith } from 'rxjs';
import {
  AccessProvisioningOverview,
  AccessProvisioningRoleDiff,
  AccessProvisioningTaskView,
} from '../../models/access-provisioning.models';

/**
 * Everything the review dialog renders.
 *
 * The **overview is the data** (record T95): the dialog is fed by the very same per-employee read
 * the Access section consumes, so the queue's rows never have to carry role names. The caller's own
 * `sub` rides along to hide `Aprobar` on their own request (**O3**), and the employee identity is
 * supplied by the page — `AccessProvisioningTaskView` carries no employee reference and the
 * overview is employee-scoped, so the number and the name come from the page that loaded them.
 */
export interface AccessReviewDialogData {
  /** The employee's access overview; its `openTask` and `frozenRoleDiff` are what is reviewed. */
  readonly overview: AccessProvisioningOverview;
  /** The caller's own Keycloak `sub`, or `null` when the token carries none. */
  readonly currentUserId: string | null;
  /** The employee the decision is about, from the page. */
  readonly employeeNumber: string;
  /** The employee's full name, from the page. */
  readonly employeeName: string;
}

/**
 * What the dialog closes with.
 *
 * A bare dismissal (Esc, backdrop, `Cancelar`) closes with `null`, which the page reads as "no
 * write". The dialog performs **no HTTP call**: it is a decision collector, exactly like the sibling
 * contract dialog, and the page owns the `approve`/`reject` call (record T95).
 */
export type AccessReviewDialogResult =
  | { readonly decision: 'approve' }
  | { readonly decision: 'reject'; readonly reason: string }
  | null;

/**
 * The shared **Revisar solicitud** dialog: the frozen set under review (record T92), the requester
 * (record T93) and the two decisions (record T95).
 *
 * It renders the `frozenRoleDiff` the task froze for review, and when that is `null` it **says so**
 * — an empty set and "no frozen set" are indistinguishable otherwise. `Aprobar` is **hidden** (not
 * merely disabled) when the open task's `requestedBy` is the caller's own `sub`, because the API
 * refuses self-approval with a 409 (**O3**) and this application does not offer a control whose call
 * the API would refuse. `Rechazar` needs a non-blank reason, because the server's only blank check
 * answers 409 as well, so the confirm control is disabled until the reason has non-whitespace
 * content and the value is trimmed before it leaves the dialog.
 */
@Component({
  selector: 'app-access-review-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
  ],
  templateUrl: './access-review-dialog.html',
  styleUrl: './access-review-dialog.scss',
})
export class AccessReviewDialog {
  private readonly dialogRef =
    inject<MatDialogRef<AccessReviewDialog, AccessReviewDialogResult>>(MatDialogRef);

  readonly data = inject<AccessReviewDialogData>(MAT_DIALOG_DATA);

  /** The reason of a rejection; required non-blank for the `Rechazar` control to be enabled. */
  readonly reason = new FormControl('', { nonNullable: true });

  private readonly reasonValue = toSignal(
    this.reason.valueChanges.pipe(startWith(this.reason.value)),
    { initialValue: '' },
  );

  /** The task under review, or `null` when the overview has none open. */
  readonly openTask = computed<AccessProvisioningTaskView | null>(
    () => this.data.overview.openTask,
  );

  /** The set the task froze for review, or `null` when it froze nothing (record T92). */
  readonly frozen = computed<AccessProvisioningRoleDiff | null>(
    () => this.data.overview.frozenRoleDiff,
  );

  /** The requester's display name when resolvable, or their actor id otherwise (record T93/G42). */
  readonly requesterName = computed<string>(
    () => this.openTask()?.requestedByName ?? this.openTask()?.requestedBy ?? '—',
  );

  /**
   * Whether the open task was requested by the caller themselves (**O3**).
   *
   * An unknown caller (`null`) is never the requester: the dialog cannot prove a self-request
   * without the `sub`, and the API stays the authority on the rule.
   */
  readonly isOwnRequest = computed<boolean>(() => {
    const requestedBy = this.openTask()?.requestedBy ?? null;
    const caller = this.data.currentUserId;
    return requestedBy !== null && caller !== null && requestedBy === caller;
  });

  /** Whether the reason has non-whitespace content; the confirm control stays disabled until it does. */
  readonly canReject = computed<boolean>(() => this.reasonValue().trim().length > 0);

  /** The roles of one set, or an em dash when the set is empty. */
  roleList(roles: string[]): string {
    return roles.length > 0 ? roles.join(', ') : '—';
  }

  approve(): void {
    this.dialogRef.close({ decision: 'approve' });
  }

  reject(): void {
    // The server's only blank check answers 409, so a blank reason never leaves the dialog.
    if (!this.canReject()) {
      return;
    }
    this.dialogRef.close({ decision: 'reject', reason: this.reason.value.trim() });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }
}
