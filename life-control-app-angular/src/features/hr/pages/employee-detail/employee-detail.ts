import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { map } from 'rxjs/operators';
import { ConfirmDialog, ConfirmDialogData, ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { EmployeeService } from '../../data/employee.service';
import { ContractService } from '../../data/contract.service';
import { StoreAssignmentService } from '../../data/store-assignment.service';
import { employeeStatusLabel } from '../../data/employee-status';
import {
  ContractHistory,
  isContractCurrent,
} from '../../components/contract-history/contract-history';
import { StoreAssignmentsSection } from '../../components/store-assignments-section/store-assignments-section';
import { Contract } from '../../models/contract.models';
import { StoreAssignment } from '../../models/store-assignment.models';
import { Employee } from '../../models/employee.models';
import {
  ContractDialog,
  ContractDialogData,
  ContractDialogResult,
} from '../../components/contract-dialog/contract-dialog';
import {
  StoreAssignmentDialog,
  StoreAssignmentDialogData,
  StoreAssignmentDialogResult,
} from '../../components/store-assignment-dialog/store-assignment-dialog';
import { EMPLOYEE_WRITE_ROLES, hasAnyClientRole } from '@core/security/roles';

/**
 * Read-only detail screen of one employee: `/hr/employees/:id`.
 *
 * **The company is resolved exactly as `employee-list` resolves it** — an inline
 * `mat-select` fed by `CompanyService.getCompanies(0, 1000)`, keyed by
 * `company.id`, seeded from the `companyId` query parameter and written back to
 * the URL on change. That is the same mechanism, not a new one: the detail is
 * reached from a list row that already carries the company, and the employee read
 * is company-scoped (`GET …/companies/{companyId}/employees/{id}`), so the page
 * needs a company id before it can ask for anything. The selector is also what
 * makes a bare deep link (no `companyId`) recoverable instead of a dead end, and
 * no request is issued until a company is selected — the list's own rule.
 *
 * The page owns the three reads (`getEmployee` + `getContracts` + `getAssignments`)
 * **independently**, so a failure of one degrades only its half of the screen: the employee read
 * keeps the three states — loading, error and **not-found** (a 404 is told apart
 * from every other failure, because the employee may simply not exist in the
 * selected company) — while the contracts and store-assignment sections own their own error and
 * retry. Joining the reads would let a failed contracts call discard a successfully
 * read employee, which is the coupling this split removes.
 *
 * The header's **"Puesto actual"** is the `D15`/`G15` fact only this screen can
 * show: the position comes from the employee's **current contract**, which the
 * list response does not carry. It resolves through the same
 * `isContractCurrent` predicate the history's validity chip uses, so the header
 * and the table can never disagree.
 *
 * There are two contract write controls, both gated on `EMPLOYEE_WRITE_ROLES`: **"Nuevo
 * contrato"** opens the create mode of {@link ContractDialog}, and **"Cerrar
 * contrato vigente"** is offered only while the employee actually has a current
 * contract (`isContractCurrent`) and opens the dialog's close mode. The page does
 * not write: it opens the dialog (`D71`) and reloads the contracts read on a real outcome, which is
 * the read the header's "Puesto actual" also derives from. A **create** act can also carry a store
 * assignment (`D8`), so it reloads the assignments read too and reports a failed second call as the
 * partial outcome it is (`D9`) instead of hiding it.
 *
 * The store-assignment surface follows the same shape: {@link StoreAssignmentsSection} renders the
 * history with its derived chain and emits three intents, the page opens {@link StoreAssignmentDialog}
 * for **Asignar tienda** and the shared `ConfirmDialog` for **Cerrar**, then reloads only the
 * assignments read. Both write actions gate on the very same `EMPLOYEE_WRITE_ROLES` the endpoints
 * require (`T17`), so the UI cannot offer a control whose call the API would refuse.
 */
@Component({
  selector: 'app-employee-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    PageHeader,
    ErrorBanner,
    ContractHistory,
    StoreAssignmentsSection,
  ],
  templateUrl: './employee-detail.html',
  styleUrl: './employee-detail.scss',
})
export class EmployeeDetail {
  private readonly companyService = inject(CompanyService);
  private readonly employeeService = inject(EmployeeService);
  private readonly contractService = inject(ContractService);
  private readonly storeAssignmentService = inject(StoreAssignmentService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-employee` and `lc-admin` reach the contract write endpoints; every other
   * authenticated caller reads the detail but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(EMPLOYEE_WRITE_ROLES);

  /** The route's `:id`; the page reads exactly one employee. */
  readonly employeeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  /** The selector's options, the same read `employee-list` performs. */
  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Company selection: seeded from the URL and kept there, like the list. */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  /**
   * The employee read, re-run whenever the selected company changes. No company
   * (or no route id) means no request at all.
   */
  readonly employeeResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) => this.employeeService.getEmployee(params.companyId, params.employeeId),
  });

  /**
   * The contracts read, independent from the employee one: a failure here leaves
   * the employee card standing and is shown by the `Contratos` section alone.
   */
  readonly contractsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) => this.contractService.getContracts(params.companyId, params.employeeId),
  });

  /**
   * The store-assignment read, independent from the other two: a failure here leaves the employee
   * card and the contracts standing and is shown by the `Tiendas asignadas` section alone.
   *
   * The whole history is asked for (`includeDisabled`): a soft-deleted assignment is part of the
   * record, and the section distinguishes it from a closed one.
   */
  readonly assignmentsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) =>
      this.storeAssignmentService.getAssignments(params.companyId, params.employeeId, true),
  });

  /** The page-level loading gate follows the employee read; contracts load in its own section. */
  readonly loading = this.employeeResource.isLoading;

  /**
   * The loaded employee, or `null` while loading and on any failure.
   *
   * Guarded by `hasValue()` on purpose: reading `.value()` in an error state
   * throws (rxResource wraps a non-`Error` failure, and `HttpErrorResponse` is
   * one), so the error states below read `.error()` instead.
   */
  readonly employee = computed<Employee | null>(() =>
    this.employeeResource.hasValue() ? (this.employeeResource.value() ?? null) : null,
  );

  readonly contracts = computed<Contract[]>(() =>
    this.contractsResource.hasValue() ? (this.contractsResource.value() ?? []) : [],
  );

  /** The contracts section's loading flag; drives only that section's copy. */
  readonly contractsLoading = this.contractsResource.isLoading;

  readonly assignments = computed<StoreAssignment[]>(() =>
    this.assignmentsResource.hasValue() ? (this.assignmentsResource.value() ?? []) : [],
  );

  /** The assignments section's loading flag; drives only that section's copy. */
  readonly assignmentsLoading = this.assignmentsResource.isLoading;

  /**
   * A failed close write, as the service mapped it.
   *
   * A failed **assign** never reaches the page: the dialog stays open and reports it in its own
   * banner, so this is only ever the close route's message. A failed assignment inside the contract
   * activation act is a different fact and gets its own signal below.
   */
  readonly assignmentActionError = signal<string | null>(null);

  /**
   * The partial outcome of the contract activation act (`D9`): the contract was created and the
   * store was not assigned.
   *
   * Named as two facts, because that is what happened — a banner implying nothing was saved would be
   * false. The assign dialog of the `Tiendas asignadas` section is the retry path, so the message
   * points there; it clears once that retry succeeds (`onAssign`) or when a later activation act
   * starts from a clean slate.
   */
  readonly partialAssignmentError = signal<string | null>(null);

  /**
   * A 404 is its own state: the employee does not exist in the selected company
   * (or the caller cannot see it), which is not the same as a failed read and
   * must not offer a retry that can never succeed.
   */
  readonly notFound = computed(
    () => unwrapHttpError(this.employeeResource.error())?.status === 404,
  );

  /** The banner copy of a failure that is not a not-found; `null` otherwise. */
  readonly loadError = computed(() => {
    const error = this.employeeResource.error();
    if (!error || this.notFound()) {
      return null;
    }
    return httpErrorMessage(error);
  });

  /** The contracts section's own error copy; `null` while loading, on success or before selection. */
  readonly contractsError = computed(() => {
    const error = this.contractsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /** The assignments section's own error copy; `null` while loading, on success or before selection. */
  readonly assignmentsError = computed(() => {
    const error = this.assignmentsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /** The header's current contract, resolved with the history's own predicate. */
  readonly currentContract = computed<Contract | null>(
    () => this.contracts().find(isContractCurrent) ?? null,
  );

  /** The current position, or an em dash when the employee has no current contract. */
  readonly currentPositionName = computed(() => this.currentContract()?.positionName ?? '—');

  readonly subtitle = computed<string | undefined>(() => {
    const employee = this.employee();
    return employee ? this.fullName(employee) : undefined;
  });

  readonly statusLabel = computed(() => {
    const employee = this.employee();
    return employee ? employeeStatusLabel(employee.statusName) : '';
  });

  /** `firstName` plus both last names, tolerating the nullable maternal one. */
  fullName(employee: Employee): string {
    return [employee.firstName, employee.paternalLastName, employee.maternalLastName]
      .filter((part): part is string => !!part)
      .join(' ');
  }

  /** Keeps the selection in the URL, exactly as the list does. */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** Re-runs the employee read that failed. */
  reload(): void {
    this.employeeResource.reload();
  }

  /** Re-runs only the contracts read, the `Contratos` section's own retry. */
  retryContracts(): void {
    this.contractsResource.reload();
  }

  /** Re-runs only the assignments read, the `Tiendas asignadas` section's own retry. */
  retryAssignments(): void {
    this.assignmentsResource.reload();
  }

  /**
   * Opens the assign dialog for the selected company and employee (`T16`: the company comes in as an
   * input, so the dialog only walks the store tree).
   *
   * Guarded on the write role like the contract controls, so a reader cannot reach a writable surface
   * by a second route.
   */
  onAssign(): void {
    const companyId = this.selectedCompanyId();
    const employeeId = this.employeeId();
    if (!this.canWrite || !companyId || !employeeId) {
      return;
    }
    const data: StoreAssignmentDialogData = { companyId, employeeId };
    this.dialog
      .open<StoreAssignmentDialog, StoreAssignmentDialogData, StoreAssignmentDialogResult>(
        StoreAssignmentDialog,
        { data },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        // `undefined` is a bare Material dismissal and means the same as `null`: no write.
        if (result?.outcome === 'created') {
          // The retry landed: the partial message of a previous failed activation is stale now.
          this.partialAssignmentError.set(null);
          this.assignmentsResource.reload();
        }
      });
  }

  /**
   * Closes an assignment after the operator confirms it in the shared `ConfirmDialog`, then reloads
   * the assignments.
   *
   * The confirmation lives here, next to the write, exactly as `employee-list` and `department-list`
   * open it; the section renders the control and emits the row.
   */
  onCloseAssignment(assignment: StoreAssignment): void {
    const companyId = this.selectedCompanyId();
    const employeeId = this.employeeId();
    if (!this.canWrite || !companyId || !employeeId) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Cerrar asignación de tienda',
      message: `¿Confirmás que querés cerrar la asignación a "${assignment.companyStoreName}"? Se conserva en el historial y la tienda deja de contar para el alcance del empleado.`,
      confirmLabel: 'Cerrar asignación',
      destructive: true,
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed) {
          this.closeAssignment(companyId, employeeId, assignment);
        }
      });
  }

  private closeAssignment(
    companyId: string,
    employeeId: string,
    assignment: StoreAssignment,
  ): void {
    this.assignmentActionError.set(null);
    this.storeAssignmentService
      .closeAssignment(companyId, employeeId, assignment.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.assignmentsResource.reload(),
        // The service maps the status; its message is the one the operator can act on.
        error: () =>
          this.assignmentActionError.set(
            this.storeAssignmentService.error() ?? 'Error al cerrar la asignación',
          ),
      });
  }

  /**
   * Opens the create mode of the contract dialog.
   *
   * Guarded on the write role and on a loaded employee: a reader must not reach a
   * writable surface by a second route, and the dialog needs the employee and the
   * current contracts to resolve the effect-before-saving notice.
   */
  onNewContract(): void {
    const companyId = this.selectedCompanyId();
    const employee = this.employee();
    if (!this.canWrite || !companyId || !employee) {
      return;
    }
    this.openContractDialog('create', companyId, employee);
  }

  /**
   * Opens the close mode of the contract dialog for the current contract.
   *
   * The action is only rendered while `currentContract()` is non-null; this guard
   * is defence in depth, like the create one above.
   */
  onCloseContract(): void {
    const companyId = this.selectedCompanyId();
    const employee = this.employee();
    if (!this.canWrite || !companyId || !employee || !this.currentContract()) {
      return;
    }
    this.openContractDialog('close', companyId, employee);
  }

  private openContractDialog(
    mode: 'create' | 'close',
    companyId: string,
    employee: Employee,
  ): void {
    const data: ContractDialogData = {
      mode,
      companyId,
      employee,
      contracts: this.contracts(),
    };
    this.dialog
      .open<ContractDialog, ContractDialogData, ContractDialogResult>(ContractDialog, { data })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.onContractDialogClosed(result));
  }

  /**
   * Reacts to the dialog's close, reading the **two separate facts** a create act can carry (`D9`).
   *
   * `undefined` is treated as no result on purpose: Material can close the ref
   * itself (Esc or backdrop) and that close carries no result, so it means the same
   * thing as `null` — no write. The employee read is deliberately **not** reloaded:
   * the two reads stay independent (`T52`), and the header's "Puesto actual" is
   * derived from the contracts, which do reload.
   *
   * A `created` result always reloads the contracts. When the act also wrote an assignment, the
   * assignments read reloads with it; when the assignment failed, the page reloads that read too — a
   * write can succeed on the server and still fail on the wire — and tells the truth in
   * {@link partialAssignmentError} instead of implying that nothing was saved. A contract-only result
   * (the pre-existing empty-picker act, `D8`) reloads the contracts and nothing else.
   */
  private onContractDialogClosed(result: ContractDialogResult | undefined): void {
    if (result?.outcome === 'closed') {
      this.contractsResource.reload();
      return;
    }
    if (result?.outcome !== 'created') {
      return;
    }

    this.contractsResource.reload();
    this.partialAssignmentError.set(null);
    if (result.assignmentError) {
      this.partialAssignmentError.set(
        `El contrato quedó creado, pero la tienda no se asignó: ${result.assignmentError}. ` +
          'Podés asignarla desde «Tiendas asignadas».',
      );
      this.assignmentsResource.reload();
      return;
    }
    if (result.assignment) {
      this.assignmentsResource.reload();
    }
  }

  onBackToList(): void {
    const companyId = this.selectedCompanyId();
    this.router.navigate(['/hr/employees'], {
      queryParams: companyId ? { companyId } : {},
    });
  }
}
