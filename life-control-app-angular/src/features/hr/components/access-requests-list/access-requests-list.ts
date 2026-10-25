import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import {
  AccessProvisioningQueueEntry,
  accessTaskKindLabel,
} from '../../models/access-provisioning.models';
import { composeEmployeeFullName } from './employee-name';

/**
 * The **Solicitudes** list: a company's pending access decisions, oldest first (record T94).
 *
 * A **presentational** component in `access-section`'s and `scope-assignments-section`'s shape: it
 * takes the rows and the section's own loading and error state and emits the two intents the page
 * acts on (`retry` for the failed read, `reviewRequested` for a row the operator wants to decide). It
 * owns no HTTP access and no dialog: the page fetches the employee's overview and opens the shared
 * `AccessReviewDialog` (record T95). A `HttpClient` or `MatDialog` in here would be the coupling that
 * split exists to prevent.
 *
 * A row carries the **employee it belongs to**, the task **kind**, who **requested** it and **when**.
 * The requester's name falls back to the actor id when Keycloak can no longer resolve it (record
 * T93/**G42**) rather than inventing an empty cell. The rows deliberately carry no role set: the
 * dialog that decides reads the frozen set from the employee's own overview (record T95).
 */
@Component({
  selector: 'app-access-requests-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatTableModule],
  templateUrl: './access-requests-list.html',
  styleUrl: './access-requests-list.scss',
})
export class AccessRequestsList {
  /** The company's waiting decisions, as the API orders them (oldest request first). */
  readonly entries = input<AccessProvisioningQueueEntry[]>([]);

  /** The section's own read state; the page resolves it from its resource. */
  readonly loading = input(false);
  readonly error = input<string | null>(null);

  /** Re-runs the queue read that failed. */
  readonly retry = output<void>();

  /** A row the operator asked to decide; the page fetches its overview and opens the dialog. */
  readonly reviewRequested = output<AccessProvisioningQueueEntry>();

  readonly displayedColumns = ['employee', 'kind', 'requester', 'requestedAt', 'actions'];

  readonly kindLabel = accessTaskKindLabel;

  /** The employee's full name, composed by the app's single rule (record T96). */
  employeeName(entry: AccessProvisioningQueueEntry): string {
    return composeEmployeeFullName({
      firstName: entry.employeeFirstName,
      paternalLastName: entry.employeePaternalLastName,
      maternalLastName: entry.employeeMaternalLastName,
    });
  }

  /**
   * Who requested the task: the resolved display name, or the actor id when the name is `null`
   * (record T93/**G42**), or an em dash when neither resolves.
   */
  requesterLabel(entry: AccessProvisioningQueueEntry): string {
    return entry.requestedByName ?? entry.requestedBy ?? '—';
  }

  onReview(entry: AccessProvisioningQueueEntry): void {
    this.reviewRequested.emit(entry);
  }
}
