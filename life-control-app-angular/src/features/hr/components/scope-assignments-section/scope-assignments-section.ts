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
 * The **Alcance** of one employee: the company it derives from, and the country / region / zone /
 * store assignments it is bounded by, one row each.
 *
 * It is the **one** assignment surface of the detail screen: what used to be a `Tiendas` tab and an
 * `Alcance` tab is this component, because a store is a level of the same tree and two tabs made two
 * answers to one question.
 *
 * The company is rendered as the **derived root** and never as a row: `company_id` is required,
 * exactly one, and comes from the employee, so there is no company assignment to write and no
 * control to offer. Rendering it is what makes "the tree starts at the company" and "nothing was
 * assigned yet" stop looking the same — which is why the root is drawn above the table **and** in
 * the empty state.
 *
 * A **presentational** component in the shape the access section shares: it takes the rows, the
 * section's own loading and error state, and whether the caller may write, and it emits the three
 * intents the page acts on (`retry`, `assign`, `closeRequested`). It owns no HTTP access, no dialog
 * and no write: the page opens the assign dialog and the shared `ConfirmDialog`, performs the write
 * and reloads. An `HttpClient` or `MatDialog` in here would be the coupling that split exists to
 * prevent.
 *
 * Each row shows **which level** it assigns, the assigned node's name, its covered range (an
 * open-ended row says so instead of inventing an end date), the **derived** company/country/region/
 * zone chain the token will carry, and the row's status. The chain is the record's own reason for
 * the section: the operator should see what they are granting. A level the chain cannot resolve
 * stays in place as an em dash, because the chain's meaning is positional.
 *
 * The section also says, once and in words, what the table means: **the assignment bounds the
 * tenancy, the role is what enables access**. A scoped role with no assignment sees nothing, and
 * `lc-company` needs no assignment at all — so a reader who takes the table for a permission list
 * would read it exactly backwards.
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

  /**
   * The name of the company the employee belongs to, rendered as the derived root of the tree.
   *
   * Required, and nullable, because the value is the page's (it resolves the company for its own
   * selector): a root with no name is a company that has not been resolved yet, never a company that
   * does not exist.
   */
  readonly companyName = input.required<string | null>();

  /** The section's own read state; the page resolves it from its resource. */
  readonly loading = input(false);
  readonly error = input<string | null>(null);

  /**
   * Whether the caller may write. Decided by the page on `EMPLOYEE_WRITE_ROLES`, the pair the
   * endpoints themselves require.
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
