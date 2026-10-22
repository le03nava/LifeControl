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
import { MatTabsModule } from '@angular/material/tabs';
import { NgTemplateOutlet } from '@angular/common';
import { map } from 'rxjs/operators';
import { catchError, of } from 'rxjs';
import { ConfirmDialog, ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { hasAnyClientRole, ACCESS_READ_ROLES, EMPLOYEE_WRITE_ROLES } from '@core/security/roles';
import { CurrentUser } from '@core/security/current-user';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { EmployeeService } from '../../data/employee.service';
import { EmployeeStatusService } from '../../data/employee-status.service';
import { AccessProvisioningService } from '../../data/access-provisioning.service';
import { employeeStatusLabel } from '../../data/employee-status';
import { Employee } from '../../models/employee.models';
import {
  AccessProvisioningOverview,
  AccessProvisioningQueueEntry,
} from '../../models/access-provisioning.models';
import { AccessRequestsList } from '../../components/access-requests-list/access-requests-list';
import { composeEmployeeFullName } from '../../components/access-requests-list/employee-name';
import {
  AccessReviewDialog,
  AccessReviewDialogData,
  AccessReviewDialogResult,
} from '../../components/access-review-dialog/access-review-dialog';

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
 * informative text renders for everyone and `canWrite` gates only the write
 * actions. The row action **"Ver"** is deliberately outside that gate: it opens
 * the read-only detail (`/hr/employees/:id`), which is a read like this list, so
 * every caller that can see a row can open it.
 *
 * Disable is a soft delete (`enabled = false`) and is **not** the `Terminated`
 * status (`T3`): the copy says "deshabilitar", the inverse action is offered for
 * a disabled row, and neither reads nor writes the life-cycle status. The
 * confirmation states that disabling **revokes access** (`G41`, record
 * `employee-disable-revokes-access`): the backend now derives no required roles
 * for a soft-deleted employee and enqueues the `DEACTIVATE` that disables the
 * account and removes its roles and claims, so the copy describes the revocation
 * instead of denying it, and names the way back — re-enabling restores the
 * access the covering contract grants.
 *
 * There is deliberately no position column (`G15`): `EmployeeResponse` carries
 * no position and the contracts are only reachable per employee, so the column
 * would cost an N+1.
 *
 * The pending decisions live in a **`Solicitudes` tab** beside the list (record T96). The tab is a
 * `mat-tab-group` rendered **only** for a caller holding `ACCESS_READ_ROLES`: for anyone else the
 * screen is exactly the list it was, with no tab, no queue request and no layout change, because the
 * queue endpoint carries that role's `@PreAuthorize` and would answer 403. The company selector
 * keeps driving **both** tabs — the queue is per company, like the list — and the decision flow
 * belongs to the page: a row's `reviewRequested` reads that employee's overview and opens the shared
 * `AccessReviewDialog` (record T95), then the page calls the service and reloads the queue.
 */
@Component({
  selector: 'app-employee-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgTemplateOutlet,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTabsModule,
    PageHeader,
    ErrorBanner,
    AccessRequestsList,
  ],
  templateUrl: './employee-list.html',
  styleUrl: './employee-list.scss',
})
export class EmployeeList {
  private readonly companyService = inject(CompanyService);
  private readonly employeeService = inject(EmployeeService);
  private readonly statusService = inject(EmployeeStatusService);
  private readonly accessService = inject(AccessProvisioningService);
  private readonly currentUser = inject(CurrentUser);
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

  /**
   * The Access read gate: `lc-admin` and `lc-employee-access` (record T96). A caller without either
   * renders no `Solicitudes` tab and issues no queue request, because the queue endpoint carries
   * that very `@PreAuthorize` and would answer 403.
   */
  readonly canReadAccess = hasAnyClientRole(ACCESS_READ_ROLES);

  /** The caller's own `sub`, so the review dialog can hide `Aprobar` on their own request (O3). */
  get currentUserId(): string | null {
    return this.currentUser.sub;
  }

  /**
   * The company's decision queue (record T94): the tasks in `APPROVAL_PENDING` waiting for a human.
   *
   * Keyed on the selected company and gated on the read role, exactly like the list read: a caller
   * without `lc-admin`/`lc-employee-access`, or with no company selected, produces no params, so no
   * request is ever issued. `getDecisionQueue` is a pure read, so this resource never clobbers the
   * list's own state.
   */
  readonly queueResource = rxResource({
    params: () => {
      if (!this.canReadAccess) {
        return undefined;
      }
      const companyId = this.selectedCompanyId();
      if (!companyId) {
        return undefined;
      }
      return { companyId };
    },
    stream: ({ params }) => this.accessService.getDecisionQueue(params.companyId),
  });

  /** The queue rows, or an empty array while loading and on a failed read. */
  readonly queueEntries = computed(() =>
    this.queueResource.hasValue() ? this.queueResource.value() : [],
  );

  /** The queue's own loading flag; drives only the `Solicitudes` tab. */
  readonly queueLoading = this.queueResource.isLoading;

  /**
   * The failed queue read's copy, or `null` while loading, on success or before selection.
   *
   * `getDecisionQueue` keeps no error signal of its own (it is a pure read), so the page reads the
   * resource's error. The API's envelope message wins when it carries one; the shared taxonomy is
   * the fallback.
   */
  readonly queueError = computed(() => {
    const error = this.queueResource.error();
    if (!error) {
      return null;
    }
    return this.serverEnvelope(error) ?? httpErrorMessage(error);
  });

  /** A failed decision, as the page resolved it; `null` when the last one succeeded. */
  readonly queueActionError = signal<string | null>(null);

  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
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

  /**
   * True when a server-side filter is narrowing the read, so an empty result
   * means "no match" rather than "empty registry". Derived from the same
   * signals the resource params read, not a separate source of truth.
   */
  readonly hasActiveFilters = computed(
    () => this.debouncedSearch().trim() !== '' || this.selectedStatusId() !== '',
  );

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  // ─── Status catalogue (fail-closed, D88 idiom) ───────────────
  private readonly statusMap = signal<ReadonlyMap<string, string>>(new Map());
  private readonly catalogueState = signal<CatalogueState>('loading');
  /** True when the catalogue could not be resolved; no option is offered then. */
  readonly catalogueFailed = computed(() => this.catalogueState() === 'failed');
  /** True once the catalogue resolved successfully, distinct from `loading`. */
  readonly catalogueResolved = computed(() => this.catalogueState() === 'resolved');
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
   * polluted by mid-page selector changes). Only the `includeDisabled`
   * widening is reset: the selected status is **kept**, because the status
   * catalogue is global and not company-scoped.
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

  /** Re-runs the queue read that failed. */
  reloadQueue(): void {
    this.queueResource.reload();
  }

  /**
   * Opens the shared review dialog for a queue row (record T95).
   *
   * The queue row deliberately carries no role set (record T95), so the page first reads **that
   * employee's overview** and feeds it to the dialog. The dialog collects the decision and performs
   * no call; the page then calls `approve`/`reject` and **reloads the queue**, because the row leaves
   * the decision set either way.
   */
  onReviewRequested(entry: AccessProvisioningQueueEntry): void {
    const companyId = this.selectedCompanyId();
    if (!this.canReadAccess || !companyId) {
      return;
    }
    this.queueActionError.set(null);
    this.accessService
      .getOverview(companyId, entry.employeeId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (overview) => this.openReviewDialog(companyId, entry, overview),
        error: (error: unknown) =>
          this.queueActionError.set(
            this.serverEnvelope(error) ?? this.accessService.error() ?? httpErrorMessage(error),
          ),
      });
  }

  /** Opens the shared dialog with the overview the page just read for the row's employee. */
  private openReviewDialog(
    companyId: string,
    entry: AccessProvisioningQueueEntry,
    overview: AccessProvisioningOverview,
  ): void {
    const data: AccessReviewDialogData = {
      overview,
      currentUserId: this.currentUserId,
      employeeNumber: entry.employeeNumber,
      employeeName: composeEmployeeFullName({
        firstName: entry.employeeFirstName,
        paternalLastName: entry.employeePaternalLastName,
        maternalLastName: entry.employeeMaternalLastName,
      }),
    };
    this.dialog
      .open<AccessReviewDialog, AccessReviewDialogData, AccessReviewDialogResult>(
        AccessReviewDialog,
        {
          data,
        },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        // `undefined` is a bare Material dismissal and means the same as `null`: no write.
        if (!result) {
          return;
        }
        const write =
          result.decision === 'approve'
            ? this.accessService.approve(companyId, entry.employeeId, entry.taskId)
            : this.accessService.reject(companyId, entry.employeeId, entry.taskId, {
                reason: result.reason,
              });
        this.queueActionError.set(null);
        write.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => this.queueResource.reload(),
          error: (error: unknown) =>
            this.queueActionError.set(
              this.serverEnvelope(error) ?? this.accessService.error() ?? httpErrorMessage(error),
            ),
        });
      });
  }

  /** The API envelope's own `message`, or `null` when the failure carried none. */
  private serverEnvelope(error: unknown): string | null {
    const message = (unwrapHttpError(error)?.error as { message?: unknown } | undefined)?.message;
    return typeof message === 'string' && message.trim() !== '' ? message : null;
  }

  /** `firstName` plus both last names, tolerating the nullable maternal one. */
  fullName(employee: Employee): string {
    return composeEmployeeFullName(employee);
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

  /**
   * Opens the read-only detail of the row, carrying the company the same way
   * `onEdit` does. It is deliberately outside the `canWrite` gate: the detail is a
   * read (`isAuthenticated()`), so every caller that can see the row can open it.
   */
  onView(employee: Employee): void {
    this.router.navigate(['/hr/employees', employee.id], {
      queryParams: { companyId: employee.companyId },
    });
  }

  onDisable(employee: Employee): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar empleado',
        message: `¿Confirmás que querés deshabilitar a "${this.fullName(employee)}"? Deja de aparecer en el registro y se le revoca el acceso: su cuenta y sus roles se desactivan. Si la revocación toca roles que requieren aprobación, queda pendiente en Acceso. Podés habilitarlo más adelante y recupera el acceso según el contrato que lo cubra.`,
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
