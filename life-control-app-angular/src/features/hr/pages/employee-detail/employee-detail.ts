import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { map } from 'rxjs/operators';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { EmployeeService } from '../../data/employee.service';
import { ContractService } from '../../data/contract.service';
import { employeeStatusLabel } from '../../data/employee-status';
import {
  ContractHistory,
  isContractCurrent,
} from '../../components/contract-history/contract-history';
import { Contract } from '../../models/contract.models';
import { Employee } from '../../models/employee.models';

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
 * The page owns the two reads (`getEmployee` + `getContracts`) **independently**,
 * so a failure of one degrades only its half of the screen: the employee read
 * keeps the three states — loading, error and **not-found** (a 404 is told apart
 * from every other failure, because the employee may simply not exist in the
 * selected company) — while the contracts section owns its own error and retry.
 * Joining the two reads would let a failed contracts call discard a successfully
 * read employee, which is the coupling this split removes.
 *
 * The header's **"Puesto actual"** is the `D15`/`G15` fact only this screen can
 * show: the position comes from the employee's **current contract**, which the
 * list response does not carry. It resolves through the same
 * `isContractCurrent` predicate the history's validity chip uses, so the header
 * and the table can never disagree.
 *
 * There is deliberately **no write control and no dialog** in this unit: `W4b`
 * adds "Nuevo contrato" / "Cerrar contrato vigente" and the contract dialog to
 * the page header. The page is where that dialog will be opened and the page is
 * what reloads afterwards, which is why `contract-history` stays presentational.
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
    ContractHistory,
  ],
  templateUrl: './employee-detail.html',
  styleUrl: './employee-detail.scss',
})
export class EmployeeDetail {
  private readonly companyService = inject(CompanyService);
  private readonly employeeService = inject(EmployeeService);
  private readonly contractService = inject(ContractService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

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

  onBackToList(): void {
    const companyId = this.selectedCompanyId();
    this.router.navigate(['/hr/employees'], {
      queryParams: companyId ? { companyId } : {},
    });
  }
}
