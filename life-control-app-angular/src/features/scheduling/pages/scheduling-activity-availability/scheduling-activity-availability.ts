import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormGroup,
  NonNullableFormBuilder,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { forkJoin } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { ApiError } from '@shared/models';
import { hasAnyClientRole, SCHEDULING_WRITE_ROLES } from '@core/security/roles';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';
import { SchedulingAvailabilityEditor } from '../../components/scheduling-availability-editor/scheduling-availability-editor';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import {
  createDefaultRow,
  fromWireWindow,
  toWireRequest,
} from '../../data/scheduling-availability-wire';
import {
  MAX_AVAILABILITY_WINDOWS,
  SchedulingActivity,
  SchedulingAvailabilityFormControl,
  SchedulingAvailabilityRowControl,
  SchedulingAvailabilityRowValue,
  SchedulingAvailabilityWindow,
} from '../../models/scheduling-activity.models';

/** Matches the server's indexed bean-validation keys, e.g. `windows[0].dayOfWeek`. */
const WINDOW_ERROR_KEY = /^windows\[(\d+)]\.(.+)$/;

/**
 * Mirrors `SchedulingAvailabilityService.validateWindows`'s per-window rules:
 * `endTime` strictly after `startTime`, and `validTo` not before `validFrom`.
 * `"HH:mm"` and `"yyyy-MM-dd"` are zero-padded, so their lexicographic order
 * equals the server's `LocalTime`/`LocalDate` order.
 */
function windowRowValidator(group: AbstractControl): ValidationErrors | null {
  const startTime = group.get('startTime')?.value as string | null;
  const endTime = group.get('endTime')?.value as string | null;
  const validFrom = group.get('validFrom')?.value as string | null;
  const validTo = group.get('validTo')?.value as string | null;

  const errors: ValidationErrors = {};
  if (startTime && endTime && endTime <= startTime) {
    errors['endTimeNotAfterStart'] = true;
  }
  if (validFrom && validTo && validTo < validFrom) {
    errors['validToBeforeValidFrom'] = true;
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

/**
 * Mirrors the server's overlap rule: windows are grouped by weekday, sorted by
 * start time, and each must start at or after the previous one ends. Touching
 * windows (`previous end == next start`) are allowed and the comparison is
 * time-only, exactly as on the server: the validity dates do not participate.
 * Rows that fail the per-row time rule are ignored because the server rejects
 * those before it reaches the overlap scan.
 */
function windowsOverlapValidator(array: AbstractControl): ValidationErrors | null {
  const rows: { dayOfWeek: number; startTime: string; endTime: string }[] = [];

  for (const control of (array as FormArray).controls) {
    const group = control as FormGroup<SchedulingAvailabilityRowControl>;
    const dayOfWeek = group.get('dayOfWeek')?.value as number | null;
    const startTime = group.get('startTime')?.value as string | null;
    const endTime = group.get('endTime')?.value as string | null;

    if (dayOfWeek != null && startTime && endTime && endTime > startTime) {
      rows.push({ dayOfWeek, startTime, endTime });
    }
  }

  const byDay = new Map<number, { startTime: string; endTime: string }[]>();
  for (const row of rows) {
    const list = byDay.get(row.dayOfWeek) ?? [];
    list.push({ startTime: row.startTime, endTime: row.endTime });
    byDay.set(row.dayOfWeek, list);
  }

  for (const list of byDay.values()) {
    list.sort((a, b) => a.startTime.localeCompare(b.startTime));
    for (let i = 1; i < list.length; i += 1) {
      if (list[i].startTime < list[i - 1].endTime) {
        return { overlappedWindows: true };
      }
    }
  }

  return null;
}

/** Parses an indexed server error key into its row index and field. */
function parseWindowErrorKey(key: string): { index: number; field: string } | null {
  const match = WINDOW_ERROR_KEY.exec(key);
  if (!match) {
    return null;
  }
  return { index: Number(match[1]), field: match[2] };
}

/**
 * Weekly availability editor for one activity (D42).
 *
 * A routed page rather than a form section: the window set is a separate resource
 * with its own endpoint, so it gets its own screen, its own write-role route and
 * the same discard guard as the other edit pages.
 *
 * The whole set is replaced on save (`PUT`), and the server answers its ordered
 * re-read, not an echo: the page adopts that response verbatim so the display
 * order is the server's, and re-seeds the rows with fresh client-side keys (D44).
 * The response window `id` is never sent back and never used as UI identity.
 *
 * A failed load and a failed save are rendered on independent conditions: a write
 * failure must never be hidden by a stale load failure (the W5a lesson).
 */
@Component({
  selector: 'app-scheduling-activity-availability',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, ErrorBanner, SchedulingAvailabilityEditor, MatButtonModule],
  templateUrl: './scheduling-activity-availability.html',
  styleUrl: './scheduling-activity-availability.scss',
})
export class SchedulingActivityAvailability implements UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly activityService = inject(SchedulingActivityService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The write-role route already blocks a read-only principal, but the editor
   * still hides its controls instead of rendering ones that cannot be used.
   */
  readonly canWrite = hasAnyClientRole(SCHEDULING_WRITE_ROLES);

  readonly activityId = signal(this.route.snapshot.paramMap.get('id') ?? '');

  /** The loaded activity, kept only as page context for its name. */
  readonly activity = signal<SchedulingActivity | null>(null);

  readonly form = signal<FormGroup<SchedulingAvailabilityFormControl>>(this.createForm([]));

  readonly loading = signal(false);
  readonly saving = signal(false);

  /** A failed read; independent from the save failure below so each survives. */
  readonly loadError = signal<string | null>(null);

  /** A failed save, or a domain / array-level `400`. */
  readonly generalError = signal<string | null>(null);

  /**
   * Positive signal that an authoritative window set was read into the form.
   *
   * `loadError()` being `null` is not enough to save: it is also `null` while the
   * first read is still in flight, so a failed read followed by a pending retry
   * would look saveable even though the set is unknown. Only a completed read
   * flips this on, and only a save behind it may replace the whole set.
   *
   * It is never cleared: a later failed (or in-flight) re-read does not make a set
   * that was already read into the form unknown again.
   */
  readonly hasLoadedAvailability = signal(false);

  readonly maxWindows = MAX_AVAILABILITY_WINDOWS;

  /**
   * Monotonic source of row keys. Never reused after a re-seed, so the server's
   * response cannot collide with the keys the previous rows held (D44).
   */
  private rowKeySequence = 0;

  constructor() {
    this.load();
  }

  onSave(): void {
    if (this.saving()) {
      return;
    }

    // The whole-set `PUT` deletes whatever it does not receive, so an empty body
    // from a state whose template was never read would clear a real schedule the
    // operator never saw. `hasLoadedAvailability` is the only positive proof that
    // the current set is known; the hidden editor is a convenience on top of this,
    // not the guard. A deliberate clear stays legal because a successful read
    // turns the signal on and this check passes.
    if (!this.hasLoadedAvailability()) {
      return;
    }

    this.form().markAllAsTouched();
    // `updateValueAndValidity()` recomputes only the receiver's own errors — the
    // validators registered on it — and then propagates *upward* to its parent; it
    // never descends into its children. Called here on the root `FormGroup`, which
    // has no validators of its own, it therefore refreshes the root status from the
    // children's statuses and leaves a hand-set `serverError` on a row control
    // untouched, so the form stays invalid and the repeat-save guard below keeps
    // refusing the `PUT`. That is intended: the row's rendered `serverError` is what
    // tells the operator which field to fix. The row's own `setValue` is what clears
    // it, because editing re-runs that row's validators and replaces its `errors`.
    this.form().updateValueAndValidity({ emitEvent: false });
    this.generalError.set(null);

    if (this.form().invalid) {
      return;
    }

    const request = toWireRequest(this.currentRows());
    this.saving.set(true);
    this.activityService
      .replaceAvailability(this.activityId(), request)
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => {
          // The server's ordered re-read becomes the local state, and the fresh
          // form is pristine so the discard guard no longer fires for a save.
          this.form.set(this.createForm(response.windows));
          this.notifications.showSuccess('Disponibilidad guardada correctamente.');
        },
        error: (err: HttpErrorResponse) => this.handleError(err),
      });
  }

  /** Exposed to `unsavedChangesGuard`. */
  hasUnsavedChanges(): boolean {
    return this.form().dirty;
  }

  onCancel(): void {
    this.router.navigate(['/scheduling/list']);
  }

  /** Re-runs a load that failed: a failed read is not an activity without a schedule. */
  retryLoad(): void {
    this.load();
  }

  /** Appends a default row (Monday, 09:00-13:00, today to today + 1 year). */
  onAddWindow(): void {
    const windows = this.windowsArray();
    if (windows.length >= this.maxWindows) {
      return;
    }
    windows.push(this.createRowGroup(createDefaultRow(this.nextRowKey())));
    this.form().markAsDirty();
  }

  /** Removes one row by its current position. */
  onRemoveWindow(index: number): void {
    this.windowsArray().removeAt(index);
    this.form().markAsDirty();
  }

  private load(): void {
    const id = this.activityId();
    this.loadError.set(null);
    this.loading.set(true);

    forkJoin({
      activity: this.activityService.getActivityById(id),
      availability: this.activityService.getAvailability(id),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ activity, availability }) => {
          this.activity.set(activity);
          this.form.set(this.createForm(availability.windows));
          this.hasLoadedAvailability.set(true);
        },
        error: (err: HttpErrorResponse) => this.loadError.set(httpErrorMessage(err)),
      });
  }

  private windowsArray(): FormArray<FormGroup<SchedulingAvailabilityRowControl>> {
    return this.form().controls.windows;
  }

  private currentRows(): SchedulingAvailabilityRowValue[] {
    return this.windowsArray().controls.map((control) => control.getRawValue());
  }

  private nextRowKey(): string {
    this.rowKeySequence += 1;
    return `availability-row-${this.rowKeySequence}`;
  }

  private createForm(
    windows: SchedulingAvailabilityWindow[],
  ): FormGroup<SchedulingAvailabilityFormControl> {
    const rows = windows.map((window) =>
      this.createRowGroup({ key: this.nextRowKey(), ...fromWireWindow(window) }),
    );
    return this.fb.group<SchedulingAvailabilityFormControl>({
      windows: this.fb.array(rows, windowsOverlapValidator),
    });
  }

  private createRowGroup(
    value: SchedulingAvailabilityRowValue,
  ): FormGroup<SchedulingAvailabilityRowControl> {
    return this.fb.group(
      {
        key: this.fb.control(value.key),
        dayOfWeek: this.fb.control(value.dayOfWeek, [
          Validators.required,
          Validators.min(1),
          Validators.max(7),
        ]),
        startTime: this.fb.control(value.startTime, Validators.required),
        endTime: this.fb.control(value.endTime, Validators.required),
        validFrom: this.fb.control(value.validFrom, Validators.required),
        validTo: this.fb.control(value.validTo, Validators.required),
      },
      { validators: windowRowValidator },
    );
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;

    // Bean validation: each `windows[i].<field>` key lands on the offending row
    // and field. A key that is not an indexed window path (the plain `windows`
    // array key) cannot be placed on a row, so it stays page-level.
    if (err.status === 400 && apiError?.errors && Object.keys(apiError.errors).length > 0) {
      const pageErrors: string[] = [];

      for (const [key, message] of Object.entries(apiError.errors)) {
        const parsed = parseWindowErrorKey(key);
        const control = parsed
          ? this.windowsArray().at(parsed.index)?.get(parsed.field)
          : undefined;

        if (parsed && control) {
          control.setErrors({ ...control.errors, serverError: message }, { emitEvent: false });
        } else {
          pageErrors.push(message);
        }
      }

      this.generalError.set(pageErrors.length > 0 ? pageErrors.join(' ') : null);
      return;
    }

    // Domain-rule 400 (same envelope, no `errors` map): the server's message is
    // the authoritative description of the rule that failed, so it is the page
    // copy rather than a generic "invalid request".
    if (err.status === 400) {
      this.generalError.set(apiError?.message ? apiError.message : httpErrorMessage(err));
      return;
    }

    // A 404 and every other status keep the generic path; `httpErrorMessage` owns
    // the Spanish copy per status.
    this.generalError.set(httpErrorMessage(err));
  }
}
