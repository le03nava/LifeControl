import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormGroup, NonNullableFormBuilder, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Observable } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { ApiError } from '@shared/models';
import { hasAnyClientRole, SCHEDULING_WRITE_ROLES } from '@core/security/roles';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';
import { SchedulingActivityForm } from '../../components/scheduling-activity-form/scheduling-activity-form';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import { SchedulingStoreContext } from '../../data/scheduling-store-context.service';
import {
  SchedulingActivity,
  SchedulingActivityControl,
  SchedulingActivityFormValue,
} from '../../models/scheduling-activity.models';

/**
 * Create / edit page for a scheduling activity.
 *
 * The form never sends `enabled`: the flag is owned by `PATCH /{id}/enable` and
 * `DELETE /{id}`. Sending it from here would create a second writer for one piece
 * of state and would silently re-enable a disabled activity that someone merely
 * edited; omitting it is also what guarantees that editing a disabled row leaves
 * it disabled.
 *
 * `companyStoreId` is sent on create only. The backend ignores it on update, so
 * sending it there would be a value that means nothing.
 *
 * On edit the request echoes the loaded `version`. A 412 is reclaimed by
 * reloading (the store-zones precedent); a 409 is discriminated by its message
 * because the endpoint overloads it.
 */
@Component({
  selector: 'app-scheduling-activity-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Per screen in create mode: the page resolves the store it creates in.
  providers: [SchedulingStoreContext],
  imports: [PageHeader, ErrorBanner, SchedulingActivityForm, MatButtonModule, MatIconModule],
  templateUrl: './scheduling-activity-edit.html',
  styleUrl: './scheduling-activity-edit.scss',
})
export class SchedulingActivityEdit implements UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly activityService = inject(SchedulingActivityService);
  private readonly storeContext = inject(SchedulingStoreContext);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The write-role route already blocks a read-only principal, but the form still
   * hides its submit control instead of rendering one that cannot be used.
   */
  readonly canWrite = hasAnyClientRole(SCHEDULING_WRITE_ROLES);

  readonly activityId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.activityId());

  readonly activityForm = signal<FormGroup<SchedulingActivityControl>>(this.createForm());

  /** The loaded activity, kept for the pre-edit identity and the version signal. */
  readonly activity = signal<SchedulingActivity | null>(null);

  /** Optimistic-locking version of the activity being edited; `null` sends no precondition. */
  private readonly version = signal<number | null>(null);

  readonly saving = signal(false);
  readonly serverErrors = signal<Record<string, string>>({});
  readonly generalError = signal<string | null>(null);

  /**
   * A failed by-id read, kept apart from the write errors so the page can offer a
   * retry instead of leaving the operator on an empty form with no way forward.
   */
  readonly loadError = signal<string | null>(null);

  /**
   * Why the save control is unavailable while the authoritative read is failing.
   *
   * `version` is the optimistic-lock precondition. A failed load leaves it `null`,
   * so a save from this state would send a `PUT` with no precondition: the server
   * accepts it unconditionally and it silently overwrites whatever another session
   * wrote, which is exactly the overwrite the precondition exists to prevent. The
   * control stays visible but disabled, so `Reintentar` and `Cancelar` remain the
   * reachable escape hatches.
   */
  readonly saveBlockedReason = computed(() =>
    this.loadError()
      ? 'La lectura de la actividad falló, así que guardar está deshabilitado: reintentá la lectura antes de editar.'
      : null,
  );

  // ─── Store resolution (create mode only) ───────────────
  readonly storeId = this.storeContext.storeId;
  readonly storePending = this.storeContext.pending;
  readonly storeError = this.storeContext.storeError;
  readonly storeUnconfigured = this.storeContext.unconfigured;

  constructor() {
    const id = this.activityId();
    if (id) {
      this.loadActivity(id);
      return;
    }
    // Create mode needs a store to send as `companyStoreId`; the list page carries
    // the resolved id in `?storeId=`, and a deep link falls back to the profile.
    this.storeContext.resolve(this.route.snapshot.queryParamMap.get('storeId'));
  }

  onSave(value: SchedulingActivityFormValue): void {
    if (this.saving()) return;

    this.generalError.set(null);
    this.serverErrors.set({});

    const id = this.activityId();
    let request$: Observable<SchedulingActivity>;

    if (id) {
      const version = this.version();
      request$ = this.activityService.updateActivity(id, {
        activityName: value.activityName,
        description: value.description,
        durationMinutes: value.durationMinutes,
        capacityPerSlot: value.capacityPerSlot,
        userId: value.userId,
        ...(version !== null ? { version } : {}),
      });
    } else {
      const storeId = this.storeId();
      if (!storeId) {
        this.generalError.set(
          'No hay una tienda configurada para tu usuario, así que no podés crear la actividad.',
        );
        return;
      }
      request$ = this.activityService.createActivity({
        companyStoreId: storeId,
        activityName: value.activityName,
        description: value.description,
        durationMinutes: value.durationMinutes,
        capacityPerSlot: value.capacityPerSlot,
        userId: value.userId,
      });
    }

    this.saving.set(true);
    request$
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.notifications.showSuccess(
            id ? 'Actividad actualizada correctamente.' : 'Actividad creada correctamente.',
          );
          // The route's `canDeactivate` guard must not block the navigation after a save.
          this.activityForm().markAsPristine();
          this.router.navigate(['/scheduling/list']);
        },
        error: (err: HttpErrorResponse) => this.handleError(err),
      });
  }

  /** Exposed to `unsavedChangesGuard`. */
  hasUnsavedChanges(): boolean {
    return this.activityForm().dirty;
  }

  onCancel(): void {
    this.router.navigate(['/scheduling/list']);
  }

  /** Opens the availability editor for the activity being edited. */
  onEditAvailability(): void {
    const id = this.activityId();
    if (!id) {
      return;
    }
    this.router.navigate(['/scheduling/activities', id, 'availability']);
  }

  /** Re-runs a store resolution that failed: a failed read is not "no store set". */
  retryStore(): void {
    this.storeContext.reload();
  }

  /** Re-runs a by-id read that failed: a failed read is not a deleted activity. */
  retryLoad(): void {
    const id = this.activityId();
    if (!id) return;
    this.loadActivity(id);
  }

  private createForm(): FormGroup<SchedulingActivityControl> {
    return this.fb.group({
      activityName: this.fb.control('', [Validators.required, Validators.maxLength(150)]),
      description: this.fb.control<string | null>(null, [Validators.maxLength(2000)]),
      durationMinutes: this.fb.control(60, [Validators.required, Validators.min(1)]),
      capacityPerSlot: this.fb.control(1, [Validators.required, Validators.min(1)]),
      userId: this.fb.control<string | null>(null),
    });
  }

  /**
   * Runs the authoritative by-id read. Called once on init and again after a 412,
   * so the page recovers a fresh entity and a fresh version instead of staying
   * stuck on a stale precondition.
   */
  private loadActivity(id: string, onLoaded?: () => void): void {
    this.loadError.set(null);
    this.activityService
      .getActivityById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (activity) => {
          this.activity.set(activity);
          this.version.set(activity.version);
          this.activityForm().setValue({
            activityName: activity.activityName,
            description: activity.description,
            durationMinutes: activity.durationMinutes,
            capacityPerSlot: activity.capacityPerSlot,
            userId: activity.userId,
          });
          this.activityForm().markAsPristine();
          // `onLoaded` runs only here, on the success path: a message that asserts
          // something happened can be set only once the event it describes did.
          onLoaded?.();
        },
        error: (err: HttpErrorResponse) => {
          this.loadError.set(httpErrorMessage(err));
        },
      });
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    const id = this.activityId();

    // A 412 is the version precondition failing: another session saved this activity
    // first. This page has a flat GET, so it re-runs its load and recovers a fresh
    // entity and a fresh version in place. The reload re-seeds the form with the
    // server's current values, and the form is marked pristine so the guard no longer
    // asks to discard changes that now match the server.
    //
    // The copy below asserts that the reload happened, so it is set from the reload's
    // success path and never before it. Setting it first would state as fact something
    // a failed read turns into a lie, and the operator would be sent to re-save on a
    // stale form. A failed recovery read reports itself through the load-failure banner
    // and its `Reintentar`, which is the honest description of what happened.
    if (err.status === 412 && id) {
      this.serverErrors.set({});
      this.activityForm().markAsPristine();
      this.loadActivity(id, () => {
        this.generalError.set(
          'Otra sesión modificó esta actividad mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
        );
      });
      return;
    }

    // A 409 is overloaded: the duplicate-name rule and a lost concurrent flush both
    // answer 409. Only the message that names the duplicate identifies a field
    // conflict; every other 409 keeps the generic conflict copy rather than claiming
    // a cause it cannot distinguish.
    if (
      err.status === 409 &&
      typeof apiError?.message === 'string' &&
      apiError.message.includes('already exists')
    ) {
      this.serverErrors.set({
        activityName: 'Ya existe una actividad con ese nombre en esta tienda.',
      });
      this.generalError.set(null);
      return;
    }

    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }

    this.serverErrors.set({});
    if (err.status === 409) {
      this.generalError.set(httpErrorMessage(err));
      return;
    }
    this.generalError.set(apiError?.message ?? 'Error inesperado. Intente de nuevo más tarde.');
  }
}
