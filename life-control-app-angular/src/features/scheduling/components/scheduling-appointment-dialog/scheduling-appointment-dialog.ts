import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
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
import { MatSelectModule } from '@angular/material/select';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { ConfirmDialog, ErrorBanner } from '@shared/ui';
import { toTimeLabel } from '../../data/scheduling-calendar-week';
import {
  APPOINTMENT_STATUS_LABELS,
  APPOINTMENT_TRANSITION_LABELS,
  allowedTransitionNames,
  isTerminalStatus,
} from '../../data/scheduling-appointment-status';
import { SchedulingAppointmentService } from '../../data/scheduling-appointment.service';
import { SchedulingStatusService } from '../../data/scheduling-status.service';
import {
  SchedulingCalendarAppointment,
  SchedulingCalendarEntry,
} from '../../models/scheduling-calendar.models';
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

/**
 * Manage-mode stale copies (D87).
 *
 * The lifecycle's 409 causes are not the booking's, so naming them with the
 * booking copy would describe a conflict the operator did not have. Each copy
 * keeps the one thing all of them share: the rendered week is out of date and a
 * re-read is the reaction (D72). None claims the client can repair the state
 * locally, because it cannot — the appointment or the destination moved under it.
 */
const STATUS_CHANGED_MESSAGE =
  'El estado del turno cambió mientras lo mirabas. Volvé a leer la semana para ver su situación actual.';
const APPOINTMENT_CLOSED_MESSAGE =
  'Este turno ya está cerrado y no admite cambios. Volvé a leer la semana para ver su estado actual.';
const DESTINATION_UNAVAILABLE_MESSAGE =
  'El horario de destino dejó de estar disponible. Volvé a leer la semana y elegí otro.';
// Fail-closed (D88): with no catalogue there is no id, so no transition button can
// resolve one. The copy says the offer is unavailable, never that the appointment is.
const CATALOGUE_FAILED_MESSAGE =
  'No se pudo cargar el catálogo de estados del turno. Cerrá el diálogo y volvé a abrirlo para intentarlo de nuevo.';
const CLOSED_STATUS_MESSAGE =
  'Este turno está cerrado: no admite cambios de estado ni reprogramación.';

/** The two fragments that identify a full-slot 409 among the server's 409s (E32). */
const SLOT_NOT_BOOKABLE_FRAGMENT = 'is not bookable';
const SLOT_FULL_FRAGMENT = 'it is full';

/**
 * The one fragment that identifies the slot-not-found 404 among the server's 404s
 * (E32). Only that 404 can honestly name its cause; the activity-not-found,
 * missing-status and store-not-found 404s all share the neutral copy.
 */
const SLOT_NOT_FOUND_FRAGMENT = 'Scheduling slot not found';

/**
 * Manage-mode discriminators of the server's free text (D87, E44).
 *
 * `Invalid status transition` means the edge the client mirrored is no longer
 * legal — the appointment's status moved. `cannot be modified while in status`
 * means the appointment closed between the read and the write. Both reuse none of
 * the booking fragments: a manage failure that is not bookability has nothing to
 * do with a slot's capacity.
 */
const INVALID_TRANSITION_FRAGMENT = 'Invalid status transition';
const NOT_MODIFIABLE_FRAGMENT = 'cannot be modified while in status';

/** One offered transition: the server's status name, its Spanish verb and its id. */
interface TransitionOption {
  readonly name: string;
  readonly label: string;
  /** The resolved catalogue id; `null` while the catalogue is loading or unknown. */
  readonly statusId: string | null;
}

/**
 * The three states of the per-open catalogue resolution (D88).
 *
 * `loading` and `failed` are distinct because they render differently: while the
 * catalogue loads the edges are rendered but disabled; when it failed **no** edge
 * is rendered at all. Collapsing them into one `boolean` would force the failure
 * to look like the loading state, which is exactly the affordance fail-closed forbids.
 */
type CatalogueState = 'loading' | 'resolved' | 'failed';

/**
 * Everything the dialog renders or prefills; it performs **no** read of its own.
 *
 * A discriminated union of the two modes (D81): `book` is the write surface reached
 * from a free slot in the grid; `manage` is the lifecycle surface reached from an
 * appointment in the agenda. Both carry the slot's {@link SchedulingCalendarEntry},
 * because it is what names the slot's time and its booked/capacity pair; only
 * `manage` carries the appointment and the loaded week the reschedule destination
 * is resolved from (D85).
 */
export type SchedulingAppointmentDialogData =
  | {
      readonly mode: 'book';
      /** The slot block that was clicked in the week grid. */
      readonly entry: SchedulingCalendarEntry;
      /** The selected activity's `userId`, used to prefill the field (D73). */
      readonly activityUserId: string | null;
    }
  | {
      readonly mode: 'manage';
      /** The slot the appointment lives in, as the agenda rendered it. */
      readonly entry: SchedulingCalendarEntry;
      /** The appointment whose lifecycle is being managed. */
      readonly appointment: SchedulingCalendarAppointment;
      /** The loaded week, the only source of reschedule destinations (D67, D85). */
      readonly weekEntries: readonly SchedulingCalendarEntry[];
    };

/**
 * What the opener must react to.
 *
 * `'booked'` carries the created appointment so the page can reload the week
 * (D72); `'updated'` is the single success member of every manage write (D86) —
 * a status change, a reschedule or a removal — because the page's reaction is
 * identical for all three (reload and patch nothing) and none returns an entity
 * the page reads; `'stale'` means the failure proved the week the grid rendered is
 * no longer true, so the page must re-read it (D72, D87); `null` means closed
 * without a write and without a conflict.
 */
export type SchedulingAppointmentDialogResult =
  | { readonly outcome: 'booked'; readonly appointment: SchedulingAppointment }
  | { readonly outcome: 'updated' }
  | { readonly outcome: 'stale' }
  | null;

/**
 * The appointment dialog: the write surface reached from a free slot in the week
 * grid (booking, D69, D74) and the lifecycle surface reached from an appointment in
 * the day agenda (manage, D81).
 *
 * **Book mode** renders the slot's facts and two optional inputs — the attending
 * employee's Keycloak `sub` and the notes — and issues exactly one write,
 * `SchedulingAppointmentService.bookAppointment`.
 *
 * **Manage mode** renders the appointment's facts and offers the lifecycle (D83):
 * one button per edge from the client mirror of the server's transition map, a
 * reschedule against the loaded week's free slots (D85) and a destructive removal
 * behind `ConfirmDialog` (D82). The status catalogue is resolved here, once per
 * open (D88), and a resolution failure offers no transition button at all.
 *
 * It performs **no read** of its own: the entry, the appointment and the week all
 * arrive through {@link SchedulingAppointmentDialogData}, so it injects no
 * calendar/activity service, no store context and no route.
 *
 * A failure keeps the dialog open and is reported in its own banner, mapping the
 * server's message onto specific copy where the failure proves the grid is stale
 * (D80, D87).
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
    MatSelectModule,
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
  private readonly statusService = inject(SchedulingStatusService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<SchedulingAppointmentDialogData>(MAT_DIALOG_DATA);

  /**
   * The narrowed views of the two modes. Plain fields, computed once: `data` never
   * changes after injection, and the template needs a non-null member to reach the
   * appointment and the week without re-narrowing on every binding.
   */
  protected readonly manage = this.data.mode === 'manage' ? this.data : null;

  /** The slot both modes render; it is what names the time and the capacity. */
  protected readonly entry = this.data.entry;

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  /**
   * The slot's day as the `YYYY-MM-DD` part of its `startAt` (D56).
   *
   * Taken straight off the wire value rather than re-formatted: the dialog adds no
   * date arithmetic and no formatter of its own. The reschedule options reuse it so
   * a destination's day is written the same way the appointment's own day is.
   */
  protected readonly dayLabel = (value: string): string => value.slice(0, 10);

  readonly day = this.entry.startAt.slice(0, 10);

  /**
   * Whether the slot still has room, read off the server's own `available`
   * (`capacity - booked`, D21) rather than recomputed here.
   */
  readonly slotHasRoom = this.entry.available > 0;

  /**
   * The two optional inputs. Neither carries a validator: an empty responsable is
   * legal (D30, D45) — the appointment exists with nobody assigned — so the hint
   * copy is what informs and nothing is stricter than the server. The group exists
   * in both modes because the close guard reads its dirty flag; in manage mode it is
   * never rendered and never edited, so that term is simply always false.
   */
  readonly form = this.fb.group({
    userId: this.fb.control(this.data.mode === 'book' ? (this.data.activityUserId ?? '') : ''),
    notes: this.fb.control(''),
  });

  /** A failed write shown in the banner; the dialog stays open. */
  readonly generalError = signal<string | null>(null);

  /**
   * True while **any** write is in flight — a booking in book mode, or a status
   * change, a reschedule or a removal in manage mode (D87's in-flight guard).
   *
   * One state on purpose: a second flag would let a manage write start while the
   * write it replaces is still settling, and W6b's F2 is the record of what that
   * costs. It is exposed under exactly one public name, `saving`, which is what the
   * rest of the feature standardises on (`scheduling-activity-form.ts`,
   * `scheduling-activity-availability.ts`, `scheduling-activity-edit.ts`) and what
   * the pre-existing W6b specs pin.
   */
  private readonly workingState = signal(false);
  readonly saving = this.workingState.asReadonly();

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

  // --- Manage mode state (D83, D88) ------------------------------------------

  /** The resolved `statusName -> statusId` catalogue; empty until it resolves. */
  private readonly statusMap = signal<ReadonlyMap<string, string>>(new Map());

  /** The per-open resolution state; starts loading because the read is kicked off here. */
  private readonly catalogueState = signal<CatalogueState>('loading');
  /** True when the catalogue could not be resolved; no edge button is rendered (D88). */
  readonly catalogueFailed = computed(() => this.catalogueState() === 'failed');

  /**
   * The current status as its Spanish state noun, from the declared client mirror.
   *
   * The `?? appointment.statusName` fallback is the declared drift case (G32/G40):
   * a `statusName` outside the five-name mirror has no state noun, so the raw server
   * name is rendered rather than nothing. That name is outside the transition map
   * too, so {@link isTerminal} reads it as terminal and the row renders the raw name
   * beside the closed copy — the same fail-closed path the mirror's other callers
   * take, reachable only through drift between this mirror and the server.
   */
  readonly currentStatusLabel = computed(() => {
    const appointment = this.manage?.appointment;
    if (!appointment) {
      return '';
    }
    return APPOINTMENT_STATUS_LABELS[appointment.statusName] ?? appointment.statusName;
  });

  /**
   * Whether the appointment is in a status with no outgoing edge (D83).
   *
   * Mirrors the server through {@link isTerminalStatus}, so a name the mirror does
   * not know counts as terminal too (G40). A terminal appointment renders the closed
   * copy and withholds both the edge buttons and the reschedule control.
   */
  readonly isTerminal = computed(() => {
    const appointment = this.manage?.appointment;
    return appointment ? isTerminalStatus(appointment.statusName) : false;
  });

  /**
   * One option per edge the server allows from the current status, labelled in
   * Spanish from the declared client mirror (D83).
   *
   * A failed catalogue returns none — fail-closed, never an affordance that cannot
   * resolve a status id (D88). While it is loading the options are still built so
   * the disabled buttons are visible, but their `statusId` is `null` and they issue
   * no write.
   */
  readonly transitions = computed<readonly TransitionOption[]>(() => {
    const appointment = this.manage?.appointment;
    if (!appointment || this.catalogueFailed()) {
      return [];
    }
    const map = this.statusMap();
    return allowedTransitionNames(appointment.statusName).map((name) => ({
      name,
      label: APPOINTMENT_TRANSITION_LABELS[name] ?? name,
      statusId: map.get(name) ?? null,
    }));
  });

  /**
   * The reschedule destinations resolved from the loaded week (D85).
   *
   * Four conditions, all required: the same activity as the appointment, an enabled
   * activity, real room, and a different slot than the appointment's own. Excluding
   * the current slot keeps a no-op reschedule off the list — the server accepts it
   * but a control that changes nothing spends a click.
   */
  readonly destinations = computed<readonly SchedulingCalendarEntry[]>(() => {
    const manage = this.manage;
    if (!manage) {
      return [];
    }
    const { weekEntries, entry: ownEntry } = manage;
    return weekEntries.filter(
      (candidate) =>
        // The embedded appointment carries no `activityId` (E41); the appointment's
        // own slot does, and it is the same activity by construction.
        candidate.activityId === ownEntry.activityId &&
        candidate.activityEnabled &&
        candidate.available > 0 &&
        candidate.slotId !== ownEntry.slotId,
    );
  });

  /** The chosen destination slot id; `null` until the operator picks one. */
  readonly rescheduleTarget = signal<string | null>(null);

  /** The closed-status copy, exposed for the template. */
  protected readonly closedStatusMessage = CLOSED_STATUS_MESSAGE;

  constructor() {
    this.form.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.dirty.set(true));

    // Esc and the backdrop must not discard typed input silently. `disableClose` is
    // a mutable `MatDialogRef` property, so it is driven reactively from the signal.
    // Material closes the ref itself on Esc/backdrop (`_closeDialogVia`) and that
    // close carries no result, so once the week is known stale — or while a write is
    // in flight — the dialog is dismissed only through `close()`. Both buttons stay
    // visible, so the operator is never trapped in the dialog. The form-dirty term is
    // meaningless in manage mode (the form is not rendered) and harmless there; the
    // other two are the ones that matter for the lifecycle.
    effect(() => {
      this.dialogRef.disableClose = this.formDirty() || this.staleState() || this.workingState();
    });

    // The catalogue is resolved once per dialog open (D88), from the constructor so
    // it starts before the first render. It is manage-only: book mode has no edges.
    if (this.manage) {
      this.loadStatusCatalogue();
    }
  }

  onBook(): void {
    // A second click while the first request is in flight must not create a second
    // appointment; the submit control reads the same state, and this guard covers
    // any other caller.
    if (this.workingState()) return;

    // The body is exactly the three fields of the wire contract. It carries **no**
    // `customerId`: `GET /api/customers` is behind `lc-admin`/`lc-sales`, so the only
    // role that books answers 403 on a customer read (D66). Do not add it back.
    const request: SchedulingAppointmentBookingRequest = {
      slotId: this.entry.slotId,
      userId: this.normalized(this.form.controls.userId.value),
      notes: this.normalized(this.form.controls.notes.value),
    };

    this.workingState.set(true);

    this.appointmentService
      .bookAppointment(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (appointment) => {
          this.workingState.set(false);
          this.dialogRef.close({ outcome: 'booked', appointment });
        },
        error: (err: HttpErrorResponse) => {
          this.workingState.set(false);
          this.handleError(err);
        },
      });
  }

  /**
   * Issues the status change for one offered edge (D83).
   *
   * The body is the resolved catalogue id, never the status name: the server
   * resolves by id and rejects a name with a 400. Guarded on the in-flight state and
   * on a resolved id, so a disabled button can never issue a write even if it is
   * reached from another caller.
   */
  onTransition(transition: TransitionOption): void {
    const appointment = this.manage?.appointment;
    if (this.workingState() || !appointment || !transition.statusId) {
      return;
    }

    this.workingState.set(true);

    this.appointmentService
      .updateStatus(appointment.id, { statusId: transition.statusId })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.workingState.set(false);
          this.dialogRef.close({ outcome: 'updated' });
        },
        error: (err: HttpErrorResponse) => {
          this.workingState.set(false);
          this.handleManageError(err);
        },
      });
  }

  /** Issues the reschedule to the chosen destination (D85). */
  onReschedule(): void {
    const appointment = this.manage?.appointment;
    const slotId = this.rescheduleTarget();
    if (this.workingState() || !appointment || !slotId) {
      return;
    }

    this.workingState.set(true);

    this.appointmentService
      .reschedule(appointment.id, { slotId })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.workingState.set(false);
          this.dialogRef.close({ outcome: 'updated' });
        },
        error: (err: HttpErrorResponse) => {
          this.workingState.set(false);
          this.handleManageError(err);
        },
      });
  }

  /**
   * Confirms and issues the destructive removal (D82).
   *
   * The prompt is the shared {@link ConfirmDialog} with `destructive: true`, the
   * same call shape as `product-variant-list.ts`. The write happens only on an exact
   * `true` close; `undefined` (dismiss) and `false` issue nothing.
   */
  onRemove(): void {
    const appointment = this.manage?.appointment;
    if (this.workingState() || !appointment) {
      return;
    }

    this.dialog
      .open(ConfirmDialog, {
        data: {
          title: 'Eliminar turno',
          message:
            'El turno dejará de estar disponible y no se puede deshacer. ¿Querés eliminarlo?',
          confirmLabel: 'Eliminar turno',
          cancelLabel: 'Cancelar',
          destructive: true,
        },
        autoFocus: false,
        restoreFocus: false,
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.workingState.set(true);
          this.appointmentService
            .remove(appointment.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => {
                this.workingState.set(false);
                this.dialogRef.close({ outcome: 'updated' });
              },
              error: (err: HttpErrorResponse) => {
                this.workingState.set(false);
                this.handleManageError(err);
              },
            });
        }
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

  /** Resolves the appointment status catalogue once per open (D88). */
  private loadStatusCatalogue(): void {
    this.statusService
      .loadAppointmentStatusIds()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (map) => {
          this.statusMap.set(map);
          this.catalogueState.set('resolved');
        },
        error: () => {
          this.catalogueState.set('failed');
          this.generalError.set(CATALOGUE_FAILED_MESSAGE);
        },
      });
  }

  /** Whitespace-only input becomes `null`, never `''`, so the wire stays honest. */
  private normalized(value: string): string | null {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
  }

  /**
   * The message the server sent, or `''` when the body is not the API envelope.
   *
   * Every mapping in this dialog reads the server's own free text (E32, G34), so the
   * extraction is shared and never re-derived per branch.
   */
  private apiMessage(err: HttpErrorResponse): string {
    const apiError = err.error as ApiError | undefined;
    return typeof apiError?.message === 'string' ? apiError.message : '';
  }

  private handleError(err: HttpErrorResponse): void {
    const message = this.apiMessage(err);

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

  /**
   * The lifecycle failure vocabulary (D87).
   *
   * Every reachable 409 is a stale verdict: the fact the client rendered is false,
   * so a re-read is the correct reaction. A 400 (a status of the wrong type) and a
   * 403 are **not** stale — re-reading cannot fix them — and fall through to
   * `httpErrorMessage` on D75's path.
   */
  private handleManageError(err: HttpErrorResponse): void {
    const message = this.apiMessage(err);

    // The edge the client mirrored is no longer legal: the appointment's status
    // moved under it. The copy names that, not a capacity conflict.
    if (err.status === 409 && message.includes(INVALID_TRANSITION_FRAGMENT)) {
      this.staleState.set(true);
      this.generalError.set(STATUS_CHANGED_MESSAGE);
      return;
    }

    // The appointment closed between the read and the write. Same verdict, and the
    // copy says the turn is already closed rather than blaming the destination.
    if (err.status === 409 && message.includes(NOT_MODIFIABLE_FRAGMENT)) {
      this.staleState.set(true);
      this.generalError.set(APPOINTMENT_CLOSED_MESSAGE);
      return;
    }

    // The destination filled up: the booking capacity copy still describes it, and
    // a reschedule shares the same cause.
    if (
      err.status === 409 &&
      message.includes(SLOT_NOT_BOOKABLE_FRAGMENT) &&
      message.includes(SLOT_FULL_FRAGMENT)
    ) {
      this.staleState.set(true);
      this.generalError.set(CAPACITY_CONFLICT_MESSAGE);
      return;
    }

    // `is not bookable` without the full-slot text: the destination was disabled or
    // belongs to another activity. Stale with a copy that names no cause it cannot see.
    if (err.status === 409 && message.includes(SLOT_NOT_BOOKABLE_FRAGMENT)) {
      this.staleState.set(true);
      this.generalError.set(DESTINATION_UNAVAILABLE_MESSAGE);
      return;
    }

    // Any 404 keeps D80's neutral copy: the week is stale but the cause (a missing
    // appointment, a missing status row) is one the client cannot see.
    if (err.status === 404) {
      this.staleState.set(true);
      this.generalError.set(WEEK_STALE_MESSAGE);
      return;
    }

    // 400, 403, 5xx: the shared copy, no stale verdict.
    this.generalError.set(httpErrorMessage(err));
  }

  /** The single close path: a pending stale result survives any way of closing. */
  private close(): void {
    this.dialogRef.close(this.staleState() ? { outcome: 'stale' } : null);
  }
}
