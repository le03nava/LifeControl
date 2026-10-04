import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { Contract, contractTypeLabel } from '../../models/contract.models';

/**
 * Today as the wire's local `YYYY-MM-DD`.
 *
 * `Contract.endDate` is an ISO date string, so the validity comparison is a
 * string comparison against this value — no `Date` parsing and no time-zone
 * arithmetic on the wire value. Local time is deliberate: "today" is the
 * operator's day, and the API's dates are calendar days, not instants.
 */
export function todayIso(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Whether a contract is **current**: `enabled`, already started and not already
 * ended.
 *
 * This is the record's `T11`/`T28` fact read through the API's **inclusive**
 * `endDate` (the last day covered, D14), so a contract whose `endDate` is today
 * is still current, and one whose `startDate` is today has already started. A
 * **future-dated** contract (`startDate > today`) is therefore *not* current —
 * the backend permits it (`ContractService.validateDates` only rejects
 * `endDate < startDate`) and it is neither current nor closed, which is why
 * {@link contractValidityLabel} gives it its own third state.
 *
 * `endDate === null` is *open-ended*, which is **not** the same fact as current,
 * but it is never an ended one either.
 *
 * Exported so the detail page's header resolves the current contract's position
 * with the very same predicate the chip uses, instead of a second truth.
 */
export function isContractCurrent(contract: Contract): boolean {
  return (
    contract.enabled &&
    contract.startDate <= todayIso() &&
    (contract.endDate === null || contract.endDate >= todayIso())
  );
}

/**
 * Whether a contract is **scheduled**: `enabled` but not started yet
 * (`startDate > today`).
 *
 * The third validity state the record's `T28` implies: a future-dated contract
 * covers no day yet, so it is neither `Vigente` nor `Cerrado`. A **disabled**
 * future contract is deliberately not scheduled — it will never start.
 */
export function isContractScheduled(contract: Contract): boolean {
  return contract.enabled && contract.startDate > todayIso();
}

/**
 * The Spanish validity label of a contract row: `Vigente` (current),
 * `Programado` (not started yet) or `Cerrado` (everything else).
 */
export function contractValidityLabel(contract: Contract): string {
  if (isContractCurrent(contract)) {
    return 'Vigente';
  }
  return isContractScheduled(contract) ? 'Programado' : 'Cerrado';
}

/**
 * Read-only contract history of one employee.
 *
 * A **presentational** component: it takes the contracts as an `input()`, renders
 * them and owns no HTTP access, no dialog and no write control. The page reads
 * the data and, in `W4b`, will be the one that opens the contract dialog and
 * reloads; a `HttpClient` or `MatDialog` in here would be the coupling this split
 * exists to prevent.
 *
 * The validity chip is derived from the shared validity predicates: a contract
 * that is `enabled` and already started but not ended is "Vigente", one that has
 * not started yet is "Programado", and everything else — including a disabled
 * contract with no end date — is "Cerrado".
 */
@Component({
  selector: 'app-contract-history',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatTableModule, CurrencyPipe],
  templateUrl: './contract-history.html',
  styleUrl: './contract-history.scss',
})
export class ContractHistory {
  /** The employee's contract history, as the API orders it (newest first). */
  readonly contracts = input<Contract[]>([]);

  readonly displayedColumns = [
    'positionName',
    'seniorityLevelName',
    'contractType',
    'monthlySalary',
    'startDate',
    'endDate',
    'validity',
  ];

  /** The Spanish legal-form label; unknown values fall back to the raw name. */
  readonly contractTypeLabel = contractTypeLabel;

  readonly isCurrent = isContractCurrent;

  readonly isScheduled = isContractScheduled;

  readonly validityLabel = contractValidityLabel;
}
