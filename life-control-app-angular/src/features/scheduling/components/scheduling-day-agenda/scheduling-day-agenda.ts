import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { toTimeLabel } from '../../data/scheduling-calendar-week';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';

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
 * Presentational: it issues no HTTP and renders no write affordance (D65).
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

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  /** Whether the day has any appointment at all; drives the empty message. */
  readonly hasAppointments = computed(() =>
    this.entries().some((entry) => entry.appointments.length > 0),
  );
}
