import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { map } from 'rxjs/operators';
import { catchError, of } from 'rxjs';
import { ConfirmDialog, ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { hasAnyClientRole, EMPLOYEE_WRITE_ROLES } from '@core/security/roles';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { EmployeeService } from '../../data/employee.service';
import { EmployeeStatusService } from '../../data/employee-status.service';
import { employeeStatusLabel } from '../../data/employee-status';
import { Employee } from '../../models/employee.models';

/** The search input debounce window, matching the app's list pattern. */
const SEARCH_DEBOUNCE_MS = 300;

/** Shown when the status catalogue cannot be resolved; the filter fails closed. */
const CATALOGUE_FAILED_MESSAGE = 'No se pudo cargar el catálogo de estados.';

type CatalogueState = 'loading' | 'resolved' | 'failed';

interface StatusOption {
  id: string;
  name: string;
  label: string;
}

/**
 * Company-scoped list of the employee registry.
 *
 * The company is the one `department-list` reads: an inline `mat-select` fed by
 * `CompanyService` and keyed by `company.id`, seeded from the `companyId` query
 * parameter and written back to the URL on change so a shared link reproduces
 * the screen. No request is issued until a company is selected.
 *
 * Every filter is **server-side**: the search term reaches `getEmployees`
 * debounced (a keystroke must not issue a request), the status dropdown sends
 * the resolved `statusId`, and `includeDisabled` re-queries the API. The page
 * never filters or paginates the array it received — the endpoint answers a
 * plain, ordered, unpaginated array (`G17`).
 *
 * The status catalogue is resolved with `EmployeeStatusService` once, from the
 * constructor, and fails **closed**: a failed resolution leaves the filter empty
 * and says so, instead of offering an option that cannot resolve a status id.
 *
 * The list route carries no `roles` (any authenticated caller reads), so the
 * informative text renders for everyone and `canWrite` gates only the actions.
 *
 * Disable is a soft delete (`enabled = false`) and is **not** the `Terminated`
 * status (`T3`): the copy says "deshabilitar", the inverse action is offered for
 * a disabled row, and neither reads nor writes the life-cycle status.
 *
 * There is deliberately no position column (`G15`): `EmployeeResponse` carries
 * no position and the contracts are only reachable per employee, so the column
 * would cost an N+1.
 */
@Component({
  selector: 'app-employee-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    PageHeader,
    ErrorBanner,
  ],
  templateUrl: './employee-list.html',
  styleUrl: './employee-list.scss',
})
export class EmployeeList {
  private readonly companyService = inject(CompanyService);
  private readonly employeeService = inject(EmployeeService);
  private readonly statusService = inject(EmployeeStatusService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-employee` and `lc-admin` reach the write endpoints; every other
   * authenticated caller reads the list but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(EMPLOYEE_WRITE_ROLES);

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Company selection: seeded from the URL and kept there (T21). */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  // ─── Filter state ────────────────────────────────────────────
  /** The value bound to the input; the resource reads {@link debouncedSearch}. */
  readonly search = signal('');
  /** The debounced search term that actually reaches the server. */
  private readonly debouncedSearch = signal('');
  /** The selected status id; `''` means "every status". */
  readonly selectedStatusId = signal<string>('');
  readonly includeDisabled = signal(false);

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  // ─── Status catalogue (fail-closed, D88 idiom) ───────────────
  private readonly statusMap = signal<ReadonlyMap<string, string>>(new Map());
  private readonly catalogueState = signal<CatalogueState>('loading');
  /** True when the catalogue could not be resolved; no option is offered then. */
  readonly catalogueFailed = computed(() => this.catalogueState() === 'failed');
  /** The copy shown beside the empty status filter when the resolution failed. */
  readonly catalogueError = computed(() =>
    this.catalogueFailed() ? CATALOGUE_FAILED_MESSAGE : null,
  );

  /** `statusName -> statusId` as labelled options; empty while unresolved or failed. */
  readonly statusOptions = computed<StatusOption[]>(() => {
    if (this.catalogueFailed()) {
      return [];
    }
    return [...this.statusMap()].map(([name, id]) => ({
      id,
      name,
      label: employeeStatusLabel(name),
    }));
  });

  readonly displayedColumns = [
    'employeeNumber',
    'fullName',
    'email',
    'status',
    'hireDate',
    'actions',
  ];

  /**
   * The read re-runs when any filter changes: the debounced search, the status
   * id and `includeDisabled` are all resource params. `search`/`statusId` are
   * passed as `undefined` when empty, so the service can drop them from the
   * query string instead of serializing an empty filter.
   */
  readonly employeesResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      if (!companyId) {
        return undefined;
      }
      return {
        companyId,
        search: this.debouncedSearch().trim(),
        statusId: this.selectedStatusId(),
        includeDisabled: this.includeDisabled(),
      };
    },
    stream: ({ params }) =>
      this.employeeService
        .getEmployees(
          params.companyId,
          params.search || undefined,
          params.statusId || undefined,
          params.includeDisabled,
        )
        .pipe(catchError(() => of([] as Employee[]))),
    defaultValue: [] as Employee[],
  });

  readonly employees = computed(() =>
    this.employeesResource.hasValue() ? this.employeesResource.value() : [],
  );
  readonly loading = this.employeesResource.isLoading;

  /** Friendly load message owned by the service (set on a failed read). */
  readonly loadError = computed(() => this.employeeService.error());

  constructor() {
    // Debounce effect: `search` → 300ms → `debouncedSearch`, which the resource
    // params above read. Cleanup cancels the pending timer on every keystroke,
    // so typing a word issues one request, not one per character.
    effect((onCleanup) => {
      const term = this.search();
      const timer = setTimeout(() => this.debouncedSearch.set(term), SEARCH_DEBOUNCE_MS);
      onCleanup(() => clearTimeout(timer));
    });

    this.loadStatusCatalogue();
  }

  // ─── Event handlers ──────────────────────────────────────────

  /**
   * Writes the selection back to the URL (replace, so the back button is not
   * polluted by mid-page selector changes) and clears the dependent filter.
   */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    this.includeDisabled.set(false);
    this.actionError.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  onSearchChange(value: string): void {
    this.search.set(value);
  }

  onStatusChange(statusId: string): void {
    this.selectedStatusId.set(statusId);
  }

  onIncludeDisabledChange(checked: boolean): void {
    this.includeDisabled.set(checked);
  }

  /** Re-runs a list read that failed. */
  reloadList(): void {
    this.employeesResource.reload();
  }

  /** `firstName` plus both last names, tolerating the nullable maternal one. */
  fullName(employee: Employee): string {
    return [employee.firstName, employee.paternalLastName, employee.maternalLastName]
      .filter((part): part is string => !!part)
      .join(' ');
  }

  /** The Spanish status label; never the raw server name for a seeded status. */
  statusLabel(employee: Employee): string {
    return employeeStatusLabel(employee.statusName);
  }

  onCreate(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.router.navigate(['/hr/employees/create'], { queryParams: { companyId } });
  }

  onEdit(employee: Employee): void {
    this.router.navigate(['/hr/employees/edit', employee.id], {
      queryParams: { companyId: employee.companyId },
    });
  }

  onDisable(employee: Employee): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar empleado',
        message: `¿Confirmás que querés deshabilitar a "${this.fullName(employee)}"? Deja de aparecer en el registro, pero se conserva y podés habilitarlo más adelante.`,
        confirmLabel: 'Deshabilitar',
        destructive: true,
      },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed: boolean) => {
        if (!confirmed) return;
        this.actionError.set(null);
        this.employeeService
          .removeEmployee(employee.companyId, employee.id)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.showSuccess('Empleado deshabilitado correctamente.');
              this.employeesResource.reload();
            },
            error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
          });
      });
  }

  onEnable(employee: Employee): void {
    this.actionError.set(null);
    this.employeeService
      .enableEmployee(employee.companyId, employee.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Empleado habilitado correctamente.');
          this.employeesResource.reload();
        },
        error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
      });
  }

  /** Resolves the `EMPLOYEE_STATUS` family once; a failure fails the filter closed. */
  private loadStatusCatalogue(): void {
    this.statusService
      .loadEmployeeStatusIds()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (map) => {
          this.statusMap.set(map);
          this.catalogueState.set('resolved');
        },
        error: () => this.catalogueState.set('failed'),
      });
  }
}
