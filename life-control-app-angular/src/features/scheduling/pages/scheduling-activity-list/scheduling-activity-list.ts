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
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { ConfirmDialog, ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { hasAnyClientRole, SCHEDULING_WRITE_ROLES } from '@core/security/roles';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import { SchedulingStoreContext } from '../../data/scheduling-store-context.service';
import { SchedulingActivity } from '../../models/scheduling-activity.models';

/**
 * Store-scoped list of the scheduling activity catalog.
 *
 * The store is resolved through {@link SchedulingStoreContext} (`?storeId=` ->
 * the operator's configured store -> `null`, fail closed) and **no list request
 * is issued while it is unresolved**. The three fail-closed states the context
 * exposes are rendered apart on purpose: a failed resolution is not a store that
 * was never configured, and collapsing them would state something the server
 * never said.
 *
 * Disable is a soft delete (`enabled = false`), so the copy says "deshabilitar"
 * and the inverse action ("reactivar") is offered for a disabled row.
 */
@Component({
  selector: 'app-scheduling-activity-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Per screen: this page owns its own store resolution.
  providers: [SchedulingStoreContext],
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatPaginatorModule,
    MatSlideToggleModule,
    MatTableModule,
    PageHeader,
    ErrorBanner,
  ],
  templateUrl: './scheduling-activity-list.html',
  styleUrl: './scheduling-activity-list.scss',
})
export class SchedulingActivityList {
  private readonly activityService = inject(SchedulingActivityService);
  private readonly storeContext = inject(SchedulingStoreContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-scheduling-read` reaches this page but the backend answers 403 on every
   * write, so the create / edit / disable / re-enable controls are not rendered
   * for it.
   */
  readonly canWrite = hasAnyClientRole(SCHEDULING_WRITE_ROLES);

  readonly storeId = this.storeContext.storeId;
  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;

  readonly pageIndex = signal(0);
  readonly pageSize = signal(12);
  readonly includeDisabled = signal(false);

  /** A failed disable / re-enable, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  protected readonly httpErrorMessage = httpErrorMessage;

  readonly displayedColumns = [
    'activityName',
    'durationMinutes',
    'capacityPerSlot',
    'userId',
    'description',
    'enabled',
    'actions',
  ];

  readonly activitiesResource = rxResource({
    params: () => {
      // No store-scoped read without a store. `storeId` is `null` while the
      // resolution is still in flight as well as when it settled without a store,
      // so this one guard covers both; a separate `pending()` branch would decide
      // nothing a caller can observe.
      const storeId = this.storeId();
      if (!storeId) {
        return undefined;
      }
      return {
        storeId,
        page: this.pageIndex(),
        size: this.pageSize(),
        includeDisabled: this.includeDisabled(),
      };
    },
    stream: ({ params }) =>
      this.activityService.listActivities(
        params.storeId,
        params.page,
        params.size,
        params.includeDisabled,
      ),
  });

  readonly activitiesPage = computed(() =>
    this.activitiesResource.hasValue() ? this.activitiesResource.value() : undefined,
  );
  readonly loading = this.activitiesResource.isLoading;
  readonly error = this.activitiesResource.error;

  /** Whether the paginator has a second page to offer; keeps `>` out of the template. */
  readonly hasMultiplePages = computed(() => (this.activitiesPage()?.totalPages ?? 0) > 1);

  constructor() {
    this.storeContext.resolve(this.route.snapshot.queryParamMap.get('storeId'));

    // A disable / re-enable shrinks the population, so the page the operator was
    // on can stop existing. Clamp to the last page the server reports instead of
    // leaving them on an empty page while earlier pages still hold rows, which
    // would render "no activities registered" about a non-empty collection.
    effect(() => {
      const page = this.activitiesPage();
      if (!page) return;

      const lastIndex = Math.max(0, page.totalPages - 1);
      if (this.pageIndex() > lastIndex) {
        this.pageIndex.set(lastIndex);
      }
    });
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  /** The toggle changes the population, so the paginator returns to the first page. */
  onIncludeDisabledChange(checked: boolean): void {
    this.includeDisabled.set(checked);
    this.pageIndex.set(0);
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  /** Re-runs a list read that failed. */
  reloadList(): void {
    this.activitiesResource.reload();
  }

  onCreate(): void {
    this.router.navigate(['/scheduling/create'], { queryParams: this.storeQueryParams() });
  }

  onEdit(activity: SchedulingActivity): void {
    this.router.navigate(['/scheduling/edit', activity.id]);
  }

  onEditAvailability(activity: SchedulingActivity): void {
    this.router.navigate(['/scheduling/activities', activity.id, 'availability']);
  }

  onDisable(activity: SchedulingActivity): void {
    const dialogRef = this.dialog.open(ConfirmDialog, {
      data: {
        title: 'Deshabilitar actividad',
        message: `¿Confirmás que querés deshabilitar la actividad "${activity.activityName}"? Deja de estar disponible para reservar, pero se conserva y podés reactivarla más adelante.`,
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
        this.activityService
          .disableActivity(activity.id)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.showSuccess('Actividad deshabilitada correctamente.');
              this.activitiesResource.reload();
            },
            error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
          });
      });
  }

  onEnable(activity: SchedulingActivity): void {
    this.actionError.set(null);
    this.activityService
      .enableActivity(activity.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Actividad reactivada correctamente.');
          this.activitiesResource.reload();
        },
        error: (err: HttpErrorResponse) => this.actionError.set(httpErrorMessage(err)),
      });
  }

  /** Carries the resolved store to the create screen only when there is one. */
  private storeQueryParams(): { storeId: string } | undefined {
    const storeId = this.storeId();
    return storeId ? { storeId } : undefined;
  }
}
