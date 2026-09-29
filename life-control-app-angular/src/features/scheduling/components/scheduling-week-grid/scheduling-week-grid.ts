import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  ISO_WEEKDAY_LABELS,
  SchedulingWeekDay,
  toTimeLabel,
} from '../../data/scheduling-calendar-week';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';

/**
 * The visible week's grid: seven day columns (Monday to Sunday), each holding the
 * slot blocks whose slot starts on it (D62).
 *
 * Presentational (D71): a block shows its facts, and — when the page says the
 * operator may write — offers the booking action. The grid never reads roles
 * itself: it receives `canBook` and emits the slot that was chosen. A retired
 * activity's block still renders, marked as retired (D57).
 *
 * A block is positioned and labelled from the **slot's** `startAt`/`endAt`; the
 * appointment carries no second time of its own.
 */
@Component({
  selector: 'app-scheduling-week-grid',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './scheduling-week-grid.html',
  styleUrl: './scheduling-week-grid.scss',
})
export class SchedulingWeekGrid {
  readonly days = input.required<SchedulingWeekDay[]>();
  readonly selectedDate = input<string | null>(null);

  /** Whether the page's operator may book, passed down so the grid reads no role. */
  readonly canBook = input(false);

  /** The day whose agenda should be shown. */
  readonly daySelected = output<string>();

  /** The slot block that was chosen for booking (D74). */
  readonly slotSelected = output<SchedulingCalendarEntry>();

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  protected weekdayLabel(weekdayIndex: number): string {
    return ISO_WEEKDAY_LABELS[weekdayIndex - 1] ?? '';
  }

  /**
   * Whether the block at hand offers the booking action.
   *
   * All three must hold: the operator may write (D74), the activity is not retired
   * (D57), and the slot still has a vacancy. The room term is part of the same
   * gate because booking a full slot is a guaranteed 409 and the dialog turns a
   * capacity 409 into a `'stale'` verdict — offering the affordance on a slot the
   * page already knows is full would manufacture a false staleness on every click.
   */
  protected isBookable(entry: SchedulingCalendarEntry): boolean {
    return this.canBook() && entry.activityEnabled && entry.available > 0;
  }

  /** The booking control's own accessible name: what it books, and when. */
  protected bookLabel(entry: SchedulingCalendarEntry, day: SchedulingWeekDay): string {
    return `Reservar ${entry.activityName} el ${day.dayOfMonth}/${day.month} de ${toTimeLabel(
      entry.startAt,
    )} a ${toTimeLabel(entry.endAt)}`;
  }

  onSelectDay(date: string): void {
    this.daySelected.emit(date);
  }

  onSelectSlot(entry: SchedulingCalendarEntry): void {
    this.slotSelected.emit(entry);
  }
}
