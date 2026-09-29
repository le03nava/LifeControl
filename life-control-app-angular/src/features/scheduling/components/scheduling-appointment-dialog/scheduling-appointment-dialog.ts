import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { ConfirmDialog, ErrorBanner } from '@shared/ui';
import { toTimeLabel } from '../../data/scheduling-calendar-week';
import { SchedulingAppointmentService } from '../../data/scheduling-appointment.service';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';
import {
  SchedulingAppointment,
  SchedulingAppointmentBookingRequest,
} from '../../models/scheduling-appointment.models';

/**
 * Copy for the failures that prove the numbers the grid rendered are no longer
 * true.
 *
 * All are discriminated by the server's **free-text message**, not by a code:
 * the error envelope (`{status, message, path, timestamp, correlationId}`)
 * carries no machine-readable code (E32), exactly as `scheduling-activity-edit.ts`
 * reads its duplicate-name 409. A copy edit on the server therefore degrades a
 * specific message into the generic {@link httpErrorMessage} one instead of
 * mislabelling the failure as something it is not.
 */
const CAPACITY_CONFLICT_MESSAGE =
  'El horario se llenó mientras trabajabas: alguien ocupó la última vacante. Volvé a leer la semana y elegí un horario libre.';
const SLOT_GONE_MESSAGE =
  'Ese horario ya no existe. Volvé a leer la semana y elegí un horario libre.';
// The neutral copy for the 404s that are not the slot itself: the verdict is the
// same (re-read the week) but the cause is one the client cannot see, so this copy
// names none.
const WEEK_STALE_MESSAGE = 'La semana que ves quedó desactualizada. Volvé a leerla.';

/** The two fragments that identify a full-slot 409 among the server's 409s (E32). */
const SLOT_NOT_BOOKABLE_FRAGMENT = 'is not bookable';
const SLOT_FULL_FRAGMENT = 'it is full';

/**
 * The one fragment that identifies the slot-not-found 404 among the server's 404s
 * (E32). Only that 404 can honestly name its cause; the activity-not-found,
 * missing-status and store-not-found 404s all share the neutral copy.
 */
const SLOT_NOT_FOUND_FRAGMENT = 'Scheduling slot not found';

/** Everything the dialog renders or prefills; it performs **no** read of its own. */
export interface SchedulingAppointmentDialogData {
  /** The slot block that was clicked in the week grid. */
  readonly entry: SchedulingCalendarEntry;
  /** The selected activity's `userId`, used to prefill the field (D73). */
  readonly activityUserId: string | null;
}

/**
 * What the opener must react to.
 *
 * `'booked'` carries the created appointment so the page can reload the week
 * (D72); `'stale'` means the failure proved the week the grid rendered is no
 * longer true (a capacity conflict or a slot that no longer exists), so the page
 * must re-read it; `null` means closed without a write and without a conflict.
 */
export type SchedulingAppointmentDialogResult =
  | { readonly outcome: 'booked'; readonly appointment: SchedulingAppointment }
  | { readonly outcome: 'stale' }
  | null;

/**
 * The appointment dialog in **booking** mode: the write surface reached from a
 * free slot in the week grid (D69, D74).
 *
 * It renders the slot's facts and two optional inputs — the attending employee's
 * Keycloak `sub` and the notes — and issues exactly one write,
 * `SchedulingAppointmentService.bookAppointment`. It performs **no read**:
 * everything it displays arrives through {@link SchedulingAppointmentDialogData},
 * so it injects no calendar/activity service, no store context and no route.
 *
 * It carries **no** status controls, reschedule or cancel: those are the lifecycle
 * (W6c). Pre-building them here would be dead code.
 *
 * A failure keeps the dialog open and is reported in its own banner, mapping the
 * server's message onto specific copy where the failure proves the grid is stale.
 */
@Component({
  selector: 'app-scheduling-appointment-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
    ErrorBanner,
  ],
  templateUrl: './scheduling-appointment-dialog.html',
  styleUrl: './scheduling-appointment-dialog.scss',
})
export class SchedulingAppointmentDialog {
  private readonly dialogRef =
    inject<MatDialogRef<SchedulingAppointmentDialog, SchedulingAppointmentDialogResult>>(
      MatDialogRef,
    );
  private readonly dialog = inject(MatDialog);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly appointmentService = inject(SchedulingAppointmentService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<SchedulingAppointmentDialogData>(MAT_DIALOG_DATA);

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  /**
   * The slot's day as the `YYYY-MM-DD` part of its `startAt` (D56).
   *
   * Taken straight off the wire value rather than re-formatted: the dialog adds no
   * date arithmetic and no formatter of its own.
   */
  readonly day = this.data.entry.startAt.slice(0, 10);

  /**
   * Whether the slot still has room, read off the server's own `available`
   * (`capacity - booked`, D21) rather than recomputed here.
   */
  readonly slotHasRoom = this.data.entry.available > 0;

  /**
   * The two optional inputs. Neither carries a validator: an empty responsable is
   * legal (D30, D45) — the appointment exists with nobody assigned — so the hint
   * copy is what informs and nothing is stricter than the server.
   */
  readonly form = this.fb.group({
    userId: this.fb.control(this.data.activityUserId ?? ''),
    notes: this.fb.control(''),
  });

  /** A failed write shown in the banner; the dialog stays open. */
  readonly generalError = signal<string | null>(null);

  private readonly savingState = signal(false);
  /** True while the booking request is in flight; disables the submit control. */
  readonly saving = this.savingState.asReadonly();

  /**
   * True once a failure proved the grid's numbers stale. Sticky on purpose: once
   * the week is known wrong it stays wrong until the page re-reads it, whatever the
   * next attempt returns.
   */
  private readonly staleState = signal(false);

  /** Mirrors the form's dirty flag as a signal: control state is not reactive. */
  private readonly dirty = signal(false);
  /** Whether the operator typed anything. Drives the close guard. */
  readonly formDirty = this.dirty.asReadonly();

  constructor() {
    this.form.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.dirty.set(true));

    // Esc and the backdrop must not discard typed input silently. `disableClose` is
    // a mutable `MatDialogRef` property, so it is driven reactively from the signal.
    // Material closes the ref itself on Esc/backdrop (`_closeDialogVia`) and that
    // close carries no result, so once the week is known stale — or while a write is
    // in flight — the dialog is dismissed only through `close()`. Both buttons stay
    // visible, so the operator is never trapped in the dialog.
    effect(() => {
      this.dialogRef.disableClose = this.formDirty() || this.staleState() || this.savingState();
    });
  }

  onBook(): void {
    // A second click while the first request is in flight must not create a second
    // appointment; the submit control reads the same state, and this guard covers
    // any other caller.
    if (this.savingState()) return;

    // The body is exactly the three fields of the wire contract. It carries **no**
    // `customerId`: `GET /api/customers` is behind `lc-admin`/`lc-sales`, so the only
    // role that books answers 403 on a customer read (D66). Do not add it back.
    const request: SchedulingAppointmentBookingRequest = {
      slotId: this.data.entry.slotId,
      userId: this.normalized(this.form.controls.userId.value),
      notes: this.normalized(this.form.controls.notes.value),
    };

    this.savingState.set(true);

    this.appointmentService
      .bookAppointment(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (appointment) => {
          this.savingState.set(false);
          this.dialogRef.close({ outcome: 'booked', appointment });
        },
        error: (err: HttpErrorResponse) => {
          this.savingState.set(false);
          this.handleError(err);
        },
      });
  }

  cancel(): void {
    if (!this.formDirty()) {
      this.close();
      return;
    }

    this.dialog
      .open(ConfirmDialog, {
        data: {
          title: 'Cambios sin guardar',
          message: 'Escribiste datos que todavía no se guardaron. Si cerrás ahora, se pierden.',
          confirmLabel: 'Descartar y cerrar',
          cancelLabel: 'Seguir editando',
          destructive: true,
        },
        autoFocus: false,
        restoreFocus: false,
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.close();
        }
      });
  }

  /** Whitespace-only input becomes `null`, never `''`, so the wire stays honest. */
  private normalized(value: string): string | null {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
  }

  private handleError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    const message = typeof apiError?.message === 'string' ? apiError.message : '';

    // A 409 that names the full slot is the capacity conflict: the last place went
    // while the operator was working. Both fragments are required so a different
    // 409 (slot disabled, appointment not modifiable, invalid transition) is not
    // mislabelled as a capacity conflict.
    if (
      err.status === 409 &&
      message.includes(SLOT_NOT_BOOKABLE_FRAGMENT) &&
      message.includes(SLOT_FULL_FRAGMENT)
    ) {
      this.staleState.set(true);
      this.generalError.set(CAPACITY_CONFLICT_MESSAGE);
      return;
    }

    // A 404 is stale by construction: whatever it names, the week the grid rendered
    // is out of date and a re-read is the right reaction, so the verdict is unchanged.
    // Only the slot-not-found 404 can name its cause; naming the slot for the
    // activity-not-found, missing-status and store-not-found 404s was the wrong
    // cause, not the wrong verdict.
    if (err.status === 404) {
      this.staleState.set(true);
      this.generalError.set(
        message.includes(SLOT_NOT_FOUND_FRAGMENT) ? SLOT_GONE_MESSAGE : WEEK_STALE_MESSAGE,
      );
      return;
    }

    // Anything else keeps the shared copy and does not claim the week is stale.
    this.generalError.set(httpErrorMessage(err));
  }

  /** The single close path: a pending stale result survives any way of closing. */
  private close(): void {
    this.dialogRef.close(this.staleState() ? { outcome: 'stale' } : null);
  }
}
