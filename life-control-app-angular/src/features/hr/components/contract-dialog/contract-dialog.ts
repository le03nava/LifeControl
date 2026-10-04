import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
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
import { MatSelectModule } from '@angular/material/select';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { ContractService } from '../../data/contract.service';
import {
  Contract,
  ContractRequest,
  ContractType,
  Position,
  PositionSalaryBand,
  SeniorityLevel,
  contractTypeLabel,
} from '../../models/contract.models';
import { Employee } from '../../models/employee.models';
import { isContractCurrent } from '../contract-history/contract-history';

/** The five legal forms of `D13`, in the order the select offers them. */
export const CONTRACT_TYPES: readonly ContractType[] = [
  'PERMANENT',
  'FIXED_TERM',
  'TEMPORARY',
  'INTERNSHIP',
  'CONTRACTOR',
];

const CATALOGUE_ERROR_MESSAGE =
  'No se pudieron cargar los puestos o los niveles. Cerrá el diálogo y volvé a abrirlo.';

/** `YYYY-MM-DD`, the wire shape of every date this dialog handles. */
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Whether `value` is a real calendar day in the wire's `YYYY-MM-DD` form.
 *
 * The round-trip through UTC catches an impossible day a plain pattern would let
 * through (`2026-02-31`): the normalized date no longer matches the input.
 */
export function isIsoDate(value: string): boolean {
  if (!ISO_DATE_PATTERN.test(value)) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

/**
 * The **inclusive** end date that closes the previous contract when a new one
 * starts on `startDate` (T13 + D14): the day before the new start date.
 *
 * The API's `endDate` is the last day covered, so "closes the day before the new
 * start" is exactly `startDate - 1`, not `startDate`. This is the **only** date
 * arithmetic in this unit: no date that came from the API is ever shifted for
 * display, and the conversion the column needs stays on the server. The
 * computation is UTC-based so a DST transition can never drop or repeat a day.
 */
export function closingDateFor(startDate: string): string {
  const [year, month, day] = startDate.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() - 1);
  const monthPart = `${date.getUTCMonth() + 1}`.padStart(2, '0');
  const dayPart = `${date.getUTCDate()}`.padStart(2, '0');
  return `${date.getUTCFullYear()}-${monthPart}-${dayPart}`;
}

/**
 * The pair's **enabled** salary band, or `null` when there is none.
 *
 * The bands arrive scoped to one position, so only the level and the enabled
 * flag are matched here. A disabled band is treated as absent: showing it would
 * make a retired policy look authoritative.
 */
export function enabledBandFor(
  bands: readonly PositionSalaryBand[],
  seniorityLevelId: string,
): PositionSalaryBand | null {
  return bands.find((band) => band.enabled && band.seniorityLevelId === seniorityLevelId) ?? null;
}

/** Whether `salary` falls outside the band's `[minimum, maximum]` range (inclusive ends). */
export function isSalaryOutsideBand(salary: number, band: PositionSalaryBand): boolean {
  return salary < band.minimumSalary || salary > band.maximumSalary;
}

/**
 * Mirror of the server's `ContractService.validateDates` on the create form:
 * an optional `endDate` may not precede the `startDate` (`D14`).
 *
 * A declared mirror, so the operator sees the rule before a round trip; when the
 * server still rejects — an overlap, a Terminated employee, an unknown type — its
 * own message is what the banner surfaces.
 */
export function datesInOrder(control: AbstractControl): ValidationErrors | null {
  const startDate = control.get('startDate')?.value as string | undefined;
  const endDate = control.get('endDate')?.value as string | undefined;
  if (!startDate || !endDate) {
    return null;
  }
  return endDate < startDate ? { endBeforeStart: true } : null;
}

/** Everything the dialog renders; it performs the catalog reads itself. */
export interface ContractDialogData {
  /** `create` opens a new contract; `close` ends the employee's current one. */
  readonly mode: 'create' | 'close';
  readonly companyId: string;
  readonly employee: Employee;
  /** The employee's loaded history, the source of the current contract (`T11`/`T28`). */
  readonly contracts: readonly Contract[];
}

/**
 * What the opener must react to.
 *
 * `created` and `closed` each carry the written contract, and the page reloads
 * the history on either; `null` means closed without a write (cancel or a bare
 * Material dismissal). A failure keeps the dialog open and is reported in its own
 * banner, so it never reaches the page as a result.
 */
export type ContractDialogResult =
  | { readonly outcome: 'created'; readonly contract: Contract }
  | { readonly outcome: 'closed'; readonly contract: Contract }
  | null;

/**
 * Contract write surface of the employee detail page, opened by the page through
 * `MatDialog` (the scheduling domain's `D71` shape: the page opens, the dialog
 * writes, the page reloads).
 *
 * **Create mode** offers the six create fields and mirrors the server's own
 * validation (`@NotNull`/`@NotBlank` required fields, `endDate >= startDate`).
 * It reads the position and seniority-level catalogues once, and the **band of
 * the chosen position × level** next to the salary field: a salary outside the
 * band is **warned about and never blocked** (`T14`). A failed band read, or a
 * pair with no enabled band, leaves the form usable and invents no warning.
 *
 * **Effect before saving** (`T13`, `D14`): when the entered start date falls inside
 * the current contract's coverage window, the dialog states that creating this one
 * closes it, naming the inclusive date the server will store — the day **before** the
 * new start date, from the single exported {@link closingDateFor} helper. A start date
 * outside that window gets no dated promise, only an informational notice that the
 * server decides which contract closes.
 *
 * There is deliberately **no local mirror of `T13`**. The predecessor the server closes
 * is chosen by a finder over rows this form does not load (it may be a historical row),
 * and `ContractService` only rejects when that selected predecessor's own start equals
 * the new start (`ContractService.java:275-279`). A local rejection could therefore
 * refuse a payload the server accepts — the exact invariant every other mirror here
 * keeps. The server stays the authority, and its `400` is still surfaced through the
 * server-message path (`serverMessage`).
 *
 * **Close mode** offers the optional inclusive end date of the current contract
 * and mirrors the server's `endDate >= startDate` rule. An empty date is sent as
 * no body, which the API reads as "close today".
 *
 * The dialog injects no route and no page state: the employee, the loaded
 * contracts and the company all arrive through {@link ContractDialogData}.
 */
@Component({
  selector: 'app-contract-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
    ErrorBanner,
  ],
  templateUrl: './contract-dialog.html',
  styleUrl: './contract-dialog.scss',
})
export class ContractDialog {
  private readonly dialogRef =
    inject<MatDialogRef<ContractDialog, ContractDialogResult>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly contractService = inject(ContractService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<ContractDialogData>(MAT_DIALOG_DATA);

  /** The five legal forms the select offers. */
  readonly contractTypes = CONTRACT_TYPES;

  /** The Spanish legal-form label; an unknown value falls back to the raw name. */
  readonly contractTypeLabel = contractTypeLabel;

  /**
   * The employee's current contract, resolved with the history's own predicate
   * so the dialog and the page can never disagree about which row is current.
   */
  readonly currentContract = computed<Contract | null>(
    () => this.data.contracts.find(isContractCurrent) ?? null,
  );

  /**
   * The create form. It mirrors the server's required fields and the
   * `endDate >= startDate` rule; it carries **no** local mirror of `T13`, because the
   * predecessor the server closes is chosen from rows this form never loads.
   */
  readonly createForm = this.fb.group(
    {
      positionId: ['', Validators.required],
      seniorityLevelId: ['', Validators.required],
      contractType: ['', Validators.required],
      monthlySalary: this.fb.control<number | null>(null, [Validators.required, Validators.min(0)]),
      startDate: ['', Validators.required],
      endDate: [''],
    },
    { validators: datesInOrder },
  );

  private readonly positionId = toSignal(this.createForm.controls.positionId.valueChanges, {
    initialValue: '',
  });
  private readonly seniorityLevelId = toSignal(
    this.createForm.controls.seniorityLevelId.valueChanges,
    { initialValue: '' },
  );
  private readonly monthlySalary = toSignal(this.createForm.controls.monthlySalary.valueChanges, {
    initialValue: null,
  });
  private readonly startDate = toSignal(this.createForm.controls.startDate.valueChanges, {
    initialValue: '',
  });

  /** The position catalogue; empty until it resolves. */
  readonly positions = signal<Position[]>([]);
  /** The global seniority-level catalogue; empty until it resolves. */
  readonly seniorityLevels = signal<SeniorityLevel[]>([]);
  /** A failed catalogue read; the form stays usable but names the failure. */
  readonly catalogueError = signal<string | null>(null);

  /** The salary bands of the last chosen position; empty while none is chosen. */
  private readonly bands = signal<PositionSalaryBand[]>([]);

  /** The enabled band of the chosen position × level, or `null`. */
  readonly selectedBand = computed(() => enabledBandFor(this.bands(), this.seniorityLevelId()));

  /** True when a typed salary falls outside the selected band. Warns, never blocks (`T14`). */
  readonly salaryOutOfBand = computed(() => {
    const band = this.selectedBand();
    const salary = this.monthlySalary();
    return band !== null && salary !== null && isSalaryOutsideBand(salary, band);
  });

  /** The band rendered next to the salary field, or `null` when there is none. */
  readonly bandLabel = computed(() => {
    const band = this.selectedBand();
    return band ? `Banda del puesto y nivel: ${band.minimumSalary} – ${band.maximumSalary}` : null;
  });

  /** The entered start date when it is a real `YYYY-MM-DD` day, or `null`. */
  private readonly enteredStartDate = computed(() => {
    const startDate = this.startDate();
    return startDate && isIsoDate(startDate) ? startDate : null;
  });

  /** The inclusive date the server will store for the current contract, or `null`. */
  readonly closingDate = computed(() => {
    const startDate = this.enteredStartDate();
    return startDate ? closingDateFor(startDate) : null;
  });

  /**
   * Whether the entered start date falls inside the current contract's coverage
   * window as the API defines it (`endDate` inclusive): strictly after its start
   * and not after its end (or the end is absent). This is exactly the case the
   * server's predecessor finder matches on the current contract (`T13`), so only
   * here is the dated closure notice true.
   */
  private readonly startInsideCurrentWindow = computed(() => {
    const current = this.currentContract();
    const startDate = this.enteredStartDate();
    if (!current || !startDate || startDate <= current.startDate) {
      return false;
    }
    return (
      current.endDate === null || current.endDate === undefined || startDate <= current.endDate
    );
  });

  /** The effect-before-saving notice, or `null` when it would not be true. */
  readonly closesCurrentNotice = computed(() => {
    if (!this.startInsideCurrentWindow()) {
      return null;
    }
    const closingDate = this.closingDate();
    return closingDate ? `Esto cierra el contrato vigente el ${closingDate}.` : null;
  });

  /**
   * An honest note when the entered start date falls outside the current contract's
   * coverage window on **either** side — not after its start, or after its inclusive
   * end — or `null`. It names no date: the server decides which contract closes, and a
   * date the API would not write must not be promised. There is no local rejection of
   * this case (`T13`), because the predecessor the server closes depends on rows this
   * form never loads.
   */
  readonly outsideCurrentNotice = computed(() => {
    const current = this.currentContract();
    const startDate = this.enteredStartDate();
    if (!current || !startDate || this.startInsideCurrentWindow()) {
      return null;
    }
    return 'La fecha de inicio queda fuera del contrato vigente: el servidor decide qué contrato se cierra.';
  });

  // --- Close mode ----------------------------------------------------------

  /**
   * Mirror of the server's close-date rule: a typed `endDate` may not precede the
   * contract's start date. An omitted date means "close today", which the server
   * resolves, and the page only offers the action for a contract already started.
   */
  private readonly closeDateOnOrAfterStart: ValidatorFn = (control: AbstractControl) => {
    const startDate = this.currentContract()?.startDate;
    const endDate = control.get('endDate')?.value as string | undefined;
    if (!startDate || !endDate) {
      return null;
    }
    return endDate < startDate ? { endBeforeStart: true } : null;
  };

  readonly closeForm = this.fb.group(
    { endDate: [''] },
    { validators: this.closeDateOnOrAfterStart },
  );

  // --- Shared state --------------------------------------------------------

  private readonly savingState = signal(false);
  /** True while a write is in flight; the buttons disable on it. */
  readonly saving = this.savingState.asReadonly();

  /** A failed write or catalogue read, shown in the banner; the dialog stays open. */
  readonly actionError = signal<string | null>(null);

  constructor() {
    if (this.data.mode === 'create') {
      this.loadCatalogues();
      this.loadBandsOnPositionChange();
    }
  }

  /** Issues the create write with the six fields of `ContractRequest`. */
  onCreate(): void {
    if (this.savingState()) {
      return;
    }
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const value = this.createForm.getRawValue();
    const request: ContractRequest = {
      positionId: value.positionId,
      seniorityLevelId: value.seniorityLevelId,
      contractType: value.contractType as ContractType,
      monthlySalary: value.monthlySalary as number,
      startDate: value.startDate,
      endDate: value.endDate ? value.endDate : null,
    };

    this.actionError.set(null);
    this.savingState.set(true);
    this.contractService
      .addContract(this.data.companyId, this.data.employee.id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (contract) => {
          this.savingState.set(false);
          this.dialogRef.close({ outcome: 'created', contract });
        },
        error: (err: HttpErrorResponse) => {
          this.savingState.set(false);
          this.actionError.set(this.serverMessage(err));
        },
      });
  }

  /**
   * Issues the close write for the current contract.
   *
   * An empty date is sent as `undefined`, which `ContractService.closeContract`
   * turns into no body at all — the API's own "close today".
   */
  onClose(): void {
    if (this.savingState()) {
      return;
    }
    const contract = this.currentContract();
    if (!contract) {
      return;
    }
    if (this.closeForm.invalid) {
      this.closeForm.markAllAsTouched();
      return;
    }
    const endDate = this.closeForm.getRawValue().endDate;

    this.actionError.set(null);
    this.savingState.set(true);
    this.contractService
      .closeContract(this.data.companyId, this.data.employee.id, contract.id, endDate || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (closed) => {
          this.savingState.set(false);
          this.dialogRef.close({ outcome: 'closed', contract: closed });
        },
        error: (err: HttpErrorResponse) => {
          this.savingState.set(false);
          this.actionError.set(this.serverMessage(err));
        },
      });
  }

  /** Closes without a write; the page treats `null` and `undefined` alike. */
  cancel(): void {
    this.dialogRef.close(null);
  }

  private loadCatalogues(): void {
    this.contractService
      .getPositions(this.data.companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (positions) => this.positions.set(positions),
        error: () => this.catalogueError.set(CATALOGUE_ERROR_MESSAGE),
      });
    this.contractService
      .getSeniorityLevels()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (levels) => this.seniorityLevels.set(levels),
        error: () => this.catalogueError.set(CATALOGUE_ERROR_MESSAGE),
      });
  }

  /**
   * Reads the position's bands whenever the choice changes.
   *
   * `switchMap` drops a stale response when the operator picks again before the
   * first read lands. A failed read resolves to an empty band list, so the form
   * stays usable and no warning is invented (`T14`).
   */
  private loadBandsOnPositionChange(): void {
    this.createForm.controls.positionId.valueChanges
      .pipe(
        switchMap((positionId) =>
          positionId
            ? this.contractService.getSalaryBands(this.data.companyId, positionId).pipe(
                map((bands) => ({ bands })),
                catchError(() => of({ bands: [] as PositionSalaryBand[] })),
                // Clear the previous position's band on selection, so a warning can
                // never name a pair that is no longer chosen.
                startWith({ bands: [] as PositionSalaryBand[] }),
              )
            : of({ bands: [] as PositionSalaryBand[] }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ bands }) => this.bands.set(bands));
  }

  /**
   * The server's own message when the failure carried the API envelope, and the
   * shared copy otherwise. The server is the final authority on why it rejected a
   * write, so its message is surfaced instead of a generic one.
   */
  private serverMessage(error: HttpErrorResponse): string {
    const message = (error.error as { message?: unknown } | undefined)?.message;
    return typeof message === 'string' && message.trim() !== '' ? message : httpErrorMessage(error);
  }
}
