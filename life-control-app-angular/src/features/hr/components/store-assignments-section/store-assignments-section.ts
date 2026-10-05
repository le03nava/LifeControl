import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import {
  StoreAssignment,
  assignmentRangeLabel,
  assignmentStatus,
  assignmentStatusLabel,
  derivedChainLabel,
} from '../../models/store-assignment.models';

/**
 * One row's sections of the **Tiendas asignadas** history.
 *
 * A **presentational** component in `contract-history`'s shape: it takes the rows, the section's own
 * loading and error state, and whether the caller may write, and it emits the three intents the page
 * acts on (`retry`, `assign`, `close`). It owns no HTTP access, no dialog and no write: the page
 * opens the assign dialog and the shared `ConfirmDialog`, performs the write and reloads — the
 * `D71` shape the contract surface already uses ("the page opens, the dialog writes, the page
 * reloads"). A `HttpClient` or `MatDialog` in here would be the coupling that split exists to
 * prevent.
 *
 * Each row shows the store, its covered range (an open-ended row says so instead of inventing an end
 * date), the **derived** company/country/region/zone chain the token will carry (`T14`, and the
 * record's own reason for the section: the operator should see what they are granting), and the
 * row's status. The chain resolves **without** `T12`'s enabled filter, because the operator must see
 * the tree as it is, including that a disabled store grants nothing.
 *
 * The close action is offered only for a row this surface means to close: an already-closed row
 * renders no control, because closing it is a guaranteed 409. A **soft-deleted** row renders none
 * either, and that is a deliberate conservative choice of this screen rather than an API rule —
 * `EmployeeStoreAssignmentService.closeAssignment` gates on `valid_to != null` alone and never reads
 * `enabled`, so the endpoint would accept closing a soft-deleted row that is still open. The assign
 * action stays available either way, because a second store is a new assignment (`D1`).
 */
@Component({
  selector: 'app-store-assignments-section',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatTableModule],
  templateUrl: './store-assignments-section.html',
  styleUrl: './store-assignments-section.scss',
})
export class StoreAssignmentsSection {
  /** The employee's assignment history, as the API orders it (newest first). */
  readonly assignments = input<StoreAssignment[]>([]);

  /** The section's own read state; the page resolves it from its resource. */
  readonly loading = input(false);
  readonly error = input<string | null>(null);

  /**
   * Whether the caller may write. Decided by the page on `EMPLOYEE_WRITE_ROLES`, the pair the
   * endpoints themselves require (`T17`).
   */
  readonly canWrite = input(false);

  /** Re-runs the assignments read that failed. */
  readonly retry = output<void>();

  /** Asks the page for the assign flow. */
  readonly assign = output<void>();

  /** A row whose close the operator asked for; the page confirms it and writes. */
  readonly closeRequested = output<StoreAssignment>();

  readonly displayedColumns = ['companyStoreName', 'validity', 'scope', 'status', 'actions'];

  readonly statusLabel = assignmentStatusLabel;

  /** Exposed for the chip's modifier class, so the style and the label read one predicate. */
  readonly assignmentStatus = assignmentStatus;

  readonly rangeLabel = assignmentRangeLabel;

  readonly chainLabel = derivedChainLabel;

  /**
   * Whether this surface offers to close the row.
   *
   * An open row is offered; an already-closed one is not, because closing it is a 409. A
   * **soft-deleted** row is likewise not offered, but that is this screen's conservative choice and
   * not a rule of the endpoint: `closeAssignment` gates on `valid_to != null` only and never reads
   * `enabled`, so it would accept closing a soft-deleted row that is still open. This surface does
   * not ask it to.
   */
  isClosable(assignment: StoreAssignment): boolean {
    return assignmentStatus(assignment) === 'open';
  }

  onAssign(): void {
    this.assign.emit();
  }

  onClose(assignment: StoreAssignment): void {
    this.closeRequested.emit(assignment);
  }
}
