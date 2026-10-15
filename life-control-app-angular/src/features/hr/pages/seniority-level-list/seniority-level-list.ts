import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { catchError, of } from 'rxjs';
import { ConfirmDialog, ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { SENIORITY_LEVEL_WRITE_ROLES, hasAnyClientRole } from '@core/security/roles';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import { SeniorityLevel } from '../../models/seniority-level.models';

/**
 * The global seniority-level catalog, as a **secondary screen reached from
 * Puestos** (record D5) rather than a fourth HR menu child: this repository's
 * global catalogs carry no menu entry at all, and the entry point is the link
 * the positions list renders.
 *
 * It therefore has **no company selector and no company context**: seniority
 * levels are global reference data (D1), the path carries no `companyId`, and
 * disabling a level affects every company. The per-company policy lives in the
 * salary bands of a position.
 *
 * The list is ordered by `rank` on the server, which is the fact the row is
 * read for — the salary-band editor walks the catalog in that order.
 *
 * The read route carries no `roles` (any authenticated caller reads, matching
 * `isAuthenticated()` on the API), so `canWrite` gates only the actions.
 */
@Component({
  selector: 'app-seniority-level-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    MatTableModule,
    PageHeader,
    ErrorBanner,
  ],
  templateUrl: './seniority-level-list.html',
  styleUrl: './seniority-level-list.scss',
})
export class SeniorityLevelList {
  private readonly seniorityLevelService = inject(SeniorityLevelService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-seniority-level` and `lc-admin` reach the write endpoints; every other
   * authenticated caller reads the catalog but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(SENIORITY_LEVEL_WRITE_ROLES);

  // ─── Filter state ────────────────────────────────────────────
  /** Free-text filter over code and name, applied to the rows the API returned. */
  readonly search = signal('');
  readonly includeDisabled = signal(false);

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  readonly displayedColumns = ['rank', 'levelCode', 'levelName', 'enabled', 'actions'];

  /**
   * The toggle is part of the resource params, so flipping it **re-issues the
   * request** instead of filtering an already-enabled-only list client-side.
   */
  readonly levelsResource = rxResource({
    params: () => ({ includeDisabled: this.includeDisabled() }),
    stream: ({ params }) =>
      this.seniorityLevelService
        .getSeniorityLevels(params.includeDisabled)
        .pipe(catchError(() => of([] as SeniorityLevel[]))),
    defaultValue: [] as SeniorityLevel[],
  });

  readonly levels = computed(() =>
    this.levelsResource.hasValue() ? this.levelsResource.value() : [],
  );
  readonly loading = this.levelsResource.isLoading;

  /** Friendly load message owned by the service (set on a failed read). */
  readonly loadError = computed(() => this.seniorityLevelService.error());

  readonly filteredLevels = computed(() => {
    const term = this.search().trim().toLowerCase();
    const rows = this.levels();
    if (!term) {
      return rows;
    }
    return rows.filter(
      (level) =>
        level.levelCode.toLowerCase().includes(term) || level.levelName.toLowerCase().includes(term),
    );
  });

  // ─── Event handlers ──────────────────────────────────────────

  onSearchChange(value: string): void {
    this.search.set(value);
  }

  onIncludeDisabledChange(checked: boolean): void {
    this.includeDisabled.set(checked);
  }

  /** Re-runs a list read that failed. */
  reloadList(): void {
    this.levelsResource.reload();
  }

  onCreate(): void {
    this.router.navigate(['/hr/seniority-levels/create']);
  }

  onEdit(level: SeniorityLevel): void {
    this.router.navigate(['/hr/seniority-levels/edit', level.id]);
  }

  onDisable(level: SeniorityLevel): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar nivel de antigüedad',
        message: `¿Confirmás que querés deshabilitar el nivel "${level.levelName}"? Es un catálogo global: deja de ofrecerse en todas las empresas, y las bandas salariales que lo referencian se conservan.`,
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
        this.seniorityLevelService
          .removeSeniorityLevel(level.id)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.showSuccess('Nivel de antigüedad deshabilitado correctamente.');
              this.levelsResource.reload();
            },
            error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
          });
      });
  }

  onEnable(level: SeniorityLevel): void {
    this.actionError.set(null);
    this.seniorityLevelService
      .enableSeniorityLevel(level.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Nivel de antigüedad reactivado correctamente.');
          this.levelsResource.reload();
        },
        error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
      });
  }
}
