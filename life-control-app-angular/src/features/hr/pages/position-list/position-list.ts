import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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
import { hasAnyClientRole, POSITION_WRITE_ROLES } from '@core/security/roles';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { PositionService } from '../../data/position.service';
import { DepartmentService } from '../../data/department.service';
import { Position } from '../../models/position.models';
import { Department } from '../../models/department.models';

/**
 * Company-scoped list of the position catalog.
 *
 * The company is the one `regions-page` reads (record T21): an inline
 * `mat-select` fed by `CompanyService` and keyed by `company.id`, seeded from
 * the `companyId` query parameter and written back to the URL on change so a
 * shared link reproduces the screen. No request is issued until a company is
 * selected.
 *
 * The list is **flat with a department filter** rather than nested (record T11):
 * a position belongs to exactly one department, so nesting adds a level and
 * loses nothing. The filter is a real query parameter — it reaches the API
 * instead of narrowing rows already fetched — and the disabled toggle does the
 * same, which is the trap the live regions page falls into.
 *
 * The department **column** renders the name, which the wire does not carry:
 * `PositionResponse` has `departmentId` only, so the names come from the
 * company's department catalog. `reportsTo` is resolved the same way from the
 * rows in hand, so a parent that the current filter excludes renders as "—".
 *
 * Two absences are deliberate. There is **no version precondition**: positions
 * have no `version` column, so there is no 412 branch (T2). And there is **no
 * `config`/state field on the row**: the position's policy (its salary bands and
 * its role template) lives in the settings screen, which the **`Configuración`**
 * action opens — it ships with that destination, because a rendered control with
 * no destination behind it is the defect D16 was written to prevent.
 */
@Component({
  selector: 'app-position-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
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
  templateUrl: './position-list.html',
  styleUrl: './position-list.scss',
})
export class PositionList {
  private readonly companyService = inject(CompanyService);
  private readonly positionService = inject(PositionService);
  private readonly departmentService = inject(DepartmentService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-position` and `lc-admin` reach the write endpoints; every other
   * authenticated caller reads the list but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(POSITION_WRITE_ROLES);

  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
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
  /** Empty means "every department": the filter is optional (T11). */
  readonly selectedDepartmentId = signal('');

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  /**
   * A failed **catalogue** read, kept apart from the list's own load error.
   *
   * The positions still render, but an empty department column would read as
   * "this company has no departments" instead of "that read failed" — which is
   * why this is named instead of degraded silently.
   */
  readonly catalogueError = signal<string | null>(null);

  readonly displayedColumns = [
    'positionCode',
    'positionName',
    'departmentName',
    'reportsTo',
    'displayOrder',
    'enabled',
    'actions',
  ];

  /**
   * Both filters are part of the resource params, so changing either one
   * **re-issues the request** instead of narrowing an already-fetched list.
   */
  readonly positionsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      if (!companyId) {
        return undefined;
      }
      return {
        companyId,
        departmentId: this.selectedDepartmentId(),
        includeDisabled: this.includeDisabled(),
      };
    },
    stream: ({ params }) =>
      this.positionService
        .getPositions(params.companyId, params.departmentId || undefined, params.includeDisabled)
        .pipe(catchError(() => of([] as Position[]))),
    defaultValue: [] as Position[],
  });

  readonly positions = computed(() =>
    this.positionsResource.hasValue() ? this.positionsResource.value() : [],
  );
  readonly loading = this.positionsResource.isLoading;

  /** Friendly load message owned by the service (set on a failed read). */
  readonly loadError = computed(() => this.positionService.error());

  /** The company's department catalog: the filter's options and the column's names. */
  readonly departmentsResource = rxResource({
    params: () => this.selectedCompanyId() ?? undefined,
    stream: ({ params: companyId }) =>
      this.departmentService.getDepartments(companyId).pipe(
        catchError(() => {
          this.catalogueError.set('No se pudieron cargar los departamentos de la empresa');
          return of([] as Department[]);
        }),
      ),
    defaultValue: [] as Department[],
  });

  readonly departments = computed(() =>
    this.departmentsResource.hasValue() ? this.departmentsResource.value() : [],
  );

  readonly departmentNames = computed(
    () =>
      new Map(this.departments().map((department) => [department.id, department.departmentName])),
  );

  /** `reportsTo` is an id on the wire, so the label comes from the rows in hand. */
  readonly positionLabels = computed(
    () => new Map(this.positions().map((p) => [p.id, `${p.positionCode} — ${p.positionName}`])),
  );

  readonly filteredPositions = computed(() => {
    const term = this.search().trim().toLowerCase();
    const rows = this.positions();
    if (!term) {
      return rows;
    }
    return rows.filter(
      (position) =>
        position.positionCode.toLowerCase().includes(term) ||
        position.positionName.toLowerCase().includes(term),
    );
  });

  // ─── Event handlers ──────────────────────────────────────────

  /**
   * Writes the selection back to the URL (replace, so the back button is not
   * polluted by mid-page selector changes) and clears the filters that depend
   * on the company.
   */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    this.includeDisabled.set(false);
    this.selectedDepartmentId.set('');
    this.actionError.set(null);
    // The catalogue failure names a company, so it is reset when that changes.
    this.catalogueError.set(null);
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

  onDepartmentFilterChange(departmentId: string): void {
    this.selectedDepartmentId.set(departmentId ?? '');
  }

  /** Re-runs a list read that failed. */
  reloadList(): void {
    this.positionsResource.reload();
  }

  onCreate(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.router.navigate(['/hr/positions/create'], { queryParams: { companyId } });
  }

  onEdit(position: Position): void {
    this.router.navigate(['/hr/positions/edit', position.id], {
      queryParams: { companyId: position.companyId },
    });
  }

  /**
   * Opens the position's policy screen — the destination D16 ties this action to.
   *
   * It is gated by the same `canWrite` as every other row action of this list,
   * because that screen is a write destination of the same roles; the URL stays
   * reachable for any authenticated caller, which is D17's declared disagreement
   * between a narrower menu and a wider read.
   */
  onConfigure(position: Position): void {
    this.router.navigate(['/hr/positions', position.id, 'settings'], {
      queryParams: { companyId: position.companyId },
    });
  }

  onDisable(position: Position): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar puesto',
        message: `¿Confirmás que querés deshabilitar el puesto "${position.positionName}"? Deja de estar disponible para asignar contratos, pero se conserva y podés reactivarlo más adelante.`,
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
        this.positionService
          .removePosition(position.companyId, position.id)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.showSuccess('Puesto deshabilitado correctamente.');
              this.positionsResource.reload();
            },
            error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
          });
      });
  }

  onEnable(position: Position): void {
    this.actionError.set(null);
    this.positionService
      .enablePosition(position.companyId, position.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Puesto reactivado correctamente.');
          this.positionsResource.reload();
        },
        error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
      });
  }
}
