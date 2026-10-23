import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import {
  ScopeAssignment,
  scopeAssignmentLevelLabel,
  scopeAssignmentRangeLabel,
  scopeAssignmentStatus,
  scopeAssignmentStatusLabel,
  scopeDerivedChainLabel,
} from '../../models/scope-assignment.models';

/**
 * The **Alcance asignado** history of one employee: the per-level (country, region, zone) scope
 * assignments, one row each.
 *
 * A **presentational** component in `store-assignments-section`'s shape: it takes the rows, the
 * section's own loading and error state, and whether the caller may write, and it emits the three
 * intents the page acts on (`retry`, `assign`, `closeRequested`). It owns no HTTP access, no dialog
 * and no write: the page opens the assign dialog and the shared `ConfirmDialog`, performs the write
 * and reloads — the "the page opens, the dialog writes, the page reloads" shape the store section
 * documents. An `HttpClient` or `MatDialog` in here would be the coupling that split exists to
 * prevent.
 *
 * Each row shows **which level** it assigns, the assigned node's name, its covered range (an
 * open-ended row says so instead of inventing an end date), the **derived** company/country/region/
 * zone chain the token will carry, and the row's status. The chain is the record's own reason for
 * the section: the operator should see what they are granting. A level the chain cannot resolve
 * stays in place as an em dash, because the chain's meaning is positional.
 *
 * The whole history is rendered, closed and soft-deleted rows included, and each row says which of
 * the three it is: an assignment is the record of when someone stopped being scoped somewhere, so
 * hiding the closed rows would destroy the fact the table exists for.
 *
 * The close action is offered only for a row this surface means to close: an already-closed row
 * renders no control, because closing it is a guaranteed 409. A **soft-deleted** row renders none
 * either, and that is a deliberate conservative choice of this screen rather than an API rule. The
 * assign action stays available either way, because a second node is a new assignment.
 */
@Component({
  selector: 'app-scope-assignments-section',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatTableModule],
  templateUrl: './scope-assignments-section.html',
  styleUrl: './scope-assignments-section.scss',
})
export class ScopeAssignmentsSection {
  /** The employee's scope-assignment history, as the API orders it (newest first). */
  readonly assignments = input<ScopeAssignment[]>([]);

  /** The section's own read state; the page resolves it from its resource. */
  readonly loading = input(false);
  readonly error = input<string | null>(null);

  /**
   * Whether the caller may write. Decided by the page on `EMPLOYEE_WRITE_ROLES`, the pair the
   * endpoints themselves require — the same pair the store assignments use.
   */
  readonly canWrite = input(false);

  /** Re-runs the assignments read that failed. */
  readonly retry = output<void>();

  /** Asks the page for the assign flow. */
  readonly assign = output<void>();

  /** A row whose close the operator asked for; the page confirms it and writes. */
  readonly closeRequested = output<ScopeAssignment>();

  readonly displayedColumns = ['level', 'nodeName', 'validity', 'scope', 'status', 'actions'];

  readonly statusLabel = scopeAssignmentStatusLabel;

  /** Exposed for the chip's modifier class, so the style and the label read one predicate. */
  readonly assignmentStatus = scopeAssignmentStatus;

  readonly levelLabel = scopeAssignmentLevelLabel;

  readonly rangeLabel = scopeAssignmentRangeLabel;

  readonly chainLabel = scopeDerivedChainLabel;

  /**
   * Whether this surface offers to close the row.
   *
   * An open row is offered; an already-closed one is not, because closing it is a 409. A
   * **soft-deleted** row is likewise not offered, and that is this screen's conservative choice
   * rather than a rule of the endpoint.
   */
  isClosable(assignment: ScopeAssignment): boolean {
    return scopeAssignmentStatus(assignment) === 'open';
  }

  onAssign(): void {
    this.assign.emit();
  }

  onClose(assignment: ScopeAssignment): void {
    this.closeRequested.emit(assignment);
  }
}
