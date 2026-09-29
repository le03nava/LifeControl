import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  ISO_WEEKDAY_LABELS,
  SchedulingWeekDay,
  toTimeLabel,
} from '../../data/scheduling-calendar-week';

/**
 * The visible week's grid: seven day columns (Monday to Sunday), each holding the
 * slot blocks whose slot starts on it (D62).
 *
 * Presentational and **read-only by construction** (D65): a block shows its facts
 * and nothing more — no button, no dialog, no write. The only interactive element
 * is the day header, which selects the day the agenda shows. A retired activity's
 * block still renders, marked as retired (D57).
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

  /** The day whose agenda should be shown. */
  readonly daySelected = output<string>();

  /** The `HH:mm` display form of a wire date-time, from the single helper (D56). */
  protected readonly timeLabel = toTimeLabel;

  protected weekdayLabel(weekdayIndex: number): string {
    return ISO_WEEKDAY_LABELS[weekdayIndex - 1] ?? '';
  }

  onSelectDay(date: string): void {
    this.daySelected.emit(date);
  }
}
