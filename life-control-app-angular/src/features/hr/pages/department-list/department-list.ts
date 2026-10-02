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
import { hasAnyClientRole, DEPARTMENT_WRITE_ROLES } from '@core/security/roles';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { DepartmentService } from '../../data/department.service';
import { Department } from '../../models/department.models';

/**
 * Company-scoped list of the department catalog.
 *
 * The company is the one `regions-page` reads: an inline `mat-select` fed by
 * `CompanyService` and keyed by `company.id`, seeded from the `companyId` query
 * parameter and written back to the URL on change so a shared link reproduces
 * the screen. No request is issued until a company is selected.
 *
 * The list route carries no `roles` (any authenticated caller reads), so the
 * informative text renders for everyone and `canWrite` gates only the actions.
 *
 * Disable is a soft delete (`enabled = false`), so the copy says
 * "deshabilitar" and the inverse action ("reactivar") is offered for a disabled
 * row. There is no position-count column: the API carries no such field and no
 * endpoint computes one.
 */
@Component({
  selector: 'app-department-list',
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
  templateUrl: './department-list.html',
  styleUrl: './department-list.scss',
})
export class DepartmentList {
  private readonly companyService = inject(CompanyService);
  private readonly departmentService = inject(DepartmentService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-department` and `lc-admin` reach the write endpoints; every other
   * authenticated caller reads the list but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(DEPARTMENT_WRITE_ROLES);

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Company selection: seeded from the URL and kept there (T21). */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  // ─── Filter state ────────────────────────────────────────────
  /** Free-text filter over code and name, applied to the rows the API returned. */
  readonly search = signal('');
  readonly includeDisabled = signal(false);

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  readonly displayedColumns = [
    'departmentCode',
    'departmentName',
    'description',
    'displayOrder',
    'enabled',
    'actions',
  ];

  /**
   * `includeDisabled` is part of the resource params, so flipping the toggle
   * **re-issues the request** instead of filtering an already-enabled-only list
   * client-side (the trap the live regions page falls into).
   */
  readonly departmentsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      if (!companyId) {
        return undefined;
      }
      return { companyId, includeDisabled: this.includeDisabled() };
    },
    stream: ({ params }) =>
      this.departmentService
        .getDepartments(params.companyId, params.includeDisabled)
        .pipe(catchError(() => of([] as Department[]))),
    defaultValue: [] as Department[],
  });

  readonly departments = computed(() =>
    this.departmentsResource.hasValue() ? this.departmentsResource.value() : [],
  );
  readonly loading = this.departmentsResource.isLoading;

  /** Friendly load message owned by the service (set on a failed read). */
  readonly loadError = computed(() => this.departmentService.error());

  readonly filteredDepartments = computed(() => {
    const term = this.search().trim().toLowerCase();
    const rows = this.departments();
    if (!term) {
      return rows;
    }
    return rows.filter(
      (department) =>
        department.departmentCode.toLowerCase().includes(term) ||
        department.departmentName.toLowerCase().includes(term),
    );
  });

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

  onIncludeDisabledChange(checked: boolean): void {
    this.includeDisabled.set(checked);
  }

  /** Re-runs a list read that failed. */
  reloadList(): void {
    this.departmentsResource.reload();
  }

  onCreate(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.router.navigate(['/hr/departments/create'], { queryParams: { companyId } });
  }

  onEdit(department: Department): void {
    this.router.navigate(['/hr/departments/edit', department.id], {
      queryParams: { companyId: department.companyId },
    });
  }

  onDisable(department: Department): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar departamento',
        message: `¿Confirmás que querés deshabilitar el departamento "${department.departmentName}"? Deja de estar disponible para asignar posiciones, pero se conserva y podés reactivarlo más adelante.`,
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
        this.departmentService
          .removeDepartment(department.companyId, department.id)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.showSuccess('Departamento deshabilitado correctamente.');
              this.departmentsResource.reload();
            },
            error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
          });
      });
  }

  onEnable(department: Department): void {
    this.actionError.set(null);
    this.departmentService
      .enableDepartment(department.companyId, department.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Departamento reactivado correctamente.');
          this.departmentsResource.reload();
        },
        error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
      });
  }
}
