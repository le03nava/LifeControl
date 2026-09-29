import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { toTimeLabel } from '../../data/scheduling-calendar-week';
import {
  SchedulingCalendarAppointment,
  SchedulingCalendarEntry,
} from '../../models/scheduling-calendar.models';

/**
 * The pair the agenda emits when a manager activates an appointment row (D90).
 *
 * The projection appointment carries no `slotId` and no time of its own, so only
 * the wrapping entry names the slot the action targets (E41): emitting the
 * appointment alone would force the page to re-derive which entry it came from.
 */
export interface SchedulingAgendaSelection {
  readonly entry: SchedulingCalendarEntry;
  readonly appointment: SchedulingCalendarAppointment;
}

/**
 * The selected day's agenda: **the day's appointments**, not its empty slots
 * (D62).
 *
 * The grid answers "what can be booked" (the slot list) and the agenda answers
 * "who is coming" (the appointment list) — two readings of one projection
 * response, which is why the rows render only where a slot actually carries an
 * appointment. The row's time comes from the **slot**, because an appointment's
 * time is its slot's.
 *
 * Presentational and role-free (D71): it issues no HTTP, reads no role and knows
 * no `MatDialog`. The page says whether the operator may manage, and an enabled
 * row then becomes a control that emits the appointment it carries (D90). A
 * soft-deleted appointment stays a plain, non-interactive record (D89).
 */
@Component({
  selector: 'app-scheduling-day-agenda',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './scheduling-day-agenda.html',
  styleUrl: './scheduling-day-agenda.scss',
})
export class SchedulingDayAgenda {
  readonly date = input.required<string>();
  readonly entries = input.required<SchedulingCalendarEntry[]>();

  /** Whether the page's operator may manage, passed down so the agenda reads no role. */
  readonly canManage = input(false);

  /** The appointment row that was chosen for management (D90). */
  readonly appointmentSelected = output<SchedulingAgendaSelection>();

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  /** Whether the day has any appointment at all; drives the empty message. */
  readonly hasAppointments = computed(() =>
    this.entries().some((entry) => entry.appointments.length > 0),
  );

  /** The manage control's own accessible name: the appointment's status and its slot's time. */
  protected manageLabel(
    entry: SchedulingCalendarEntry,
    appointment: SchedulingCalendarAppointment,
  ): string {
    return `Gestionar el turno ${appointment.statusName} de ${toTimeLabel(
      entry.startAt,
    )} a ${toTimeLabel(entry.endAt)}`;
  }

  onSelectAppointment(
    entry: SchedulingCalendarEntry,
    appointment: SchedulingCalendarAppointment,
  ): void {
    this.appointmentSelected.emit({ entry, appointment });
  }
}
