import { SchedulingCalendarEntry } from '../models/scheduling-calendar.models';

/**
 * The calendar's **only** date/week arithmetic (D56).
 *
 * The app owns no date utility (E18) and no date library, and this is the layer
 * most likely to drift silently: an off-by-one in the weekday mapping moves every
 * block one column and still renders plausibly. Everything here is pure and
 * local-time based — this domain is store-local wall-clock with no timezone
 * conversion (D12) — so `new Date(y, m, d)` and `setDate` are used instead of
 * any UTC arithmetic that would shift a boundary around midnight.
 */

/**
 * ISO-8601 weekday labels, index 0 = Monday (ISO 1) .. index 6 = Sunday (ISO 7).
 *
 * Hardcoded rather than locale-formatted: the test environment does not carry
 * the app's `LOCALE_ID`, and the availability editor already hardcodes the same
 * Spanish weekday names, so this keeps the two surfaces consistent and the specs
 * deterministic.
 */
export const ISO_WEEKDAY_LABELS = [
  'Lunes',
  'Martes',
  'Miércoles',
  'Jueves',
  'Viernes',
  'Sábado',
  'Domingo',
] as const;

/** One visible day of the week, with the entries whose slot starts on it. */
export interface SchedulingWeekDay {
  /** `YYYY-MM-DD`, the day's own key and what a day selection carries. */
  date: string;
  /** ISO-8601 `1..7`, `MONDAY = 1` (D17). */
  weekdayIndex: number;
  dayOfMonth: number;
  /** `1..12`. */
  month: number;
  entries: SchedulingCalendarEntry[];
}

function pad(value: number, length: number): string {
  return value.toString().padStart(length, '0');
}

/**
 * ISO-8601 weekday of a date, `1..7` with `MONDAY = 1` (D17).
 *
 * `Date.getDay()` returns `0` for Sunday, so `0` maps to `7` before any ISO
 * arithmetic. Skipping that mapping is the D56 trap.
 */
export function isoWeekday(date: Date): number {
  const day = date.getDay();
  return day === 0 ? 7 : day;
}

/**
 * The Monday `00:00` local of the ISO week containing `anchor`.
 *
 * Normalizes the time to local midnight so the same anchor date always yields
 * the same instant for the request range, whatever time of day it arrived at.
 */
export function startOfIsoWeek(anchor: Date): Date {
  const start = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate());
  start.setDate(start.getDate() - (isoWeekday(start) - 1));
  return start;
}

/** A new date `days` after `date`, at local midnight, leaving the argument intact. */
export function addDays(date: Date, days: number): Date {
  const result = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  result.setDate(result.getDate() + days);
  return result;
}

/** `YYYY-MM-DD`, zero-padded. */
export function toIsoDate(date: Date): string {
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1, 2)}-${pad(date.getDate(), 2)}`;
}

/**
 * `YYYY-MM-DDTHH:mm:ss`, zero-padded, with **no offset and no `Z`**.
 *
 * That is exactly the server's `LocalDateTime` contract, so the range bound is
 * never reinterpreted by a timezone the domain does not model (D12).
 */
export function toIsoDateTime(date: Date): string {
  return `${toIsoDate(date)}T${pad(date.getHours(), 2)}:${pad(date.getMinutes(), 2)}:${pad(
    date.getSeconds(),
    2,
  )}`;
}

/**
 * `YYYY-MM-DDT00:00:00`: the local-midnight wire bound for a calendar date.
 *
 * Built from the **calendar date** and a literal time, never read off the
 * instant's time-of-day. When local midnight does not exist on a date (a DST
 * transition), `new Date(y, m, d)` and `startOfIsoWeek` return the first existing
 * instant — `01:00` under `TZ=America/Santiago` on 2026-08-31 — so turning that
 * instant back into a wall-clock string would move the week's `from` bound an hour
 * late and drop a real Monday 00:00–01:00 slot from the materialization and the
 * projection. The date part is DST-proof; the time part is fixed here.
 */
export function toIsoMidnight(date: Date): string {
  return `${toIsoDate(date)}T00:00:00`;
}

/**
 * `HH:mm` display form of a wire date-time, or of an already-display value.
 *
 * Total on purpose: a value carrying no time is returned unchanged instead of
 * rendering `NaN` or an empty slice.
 */
export function toTimeLabel(value: string): string {
  const time = value.includes('T') ? value.slice(value.indexOf('T') + 1) : value;
  return /^\d{2}:\d{2}/.test(time) ? time.slice(0, 5) : value;
}

/**
 * Total parser of the `?date=` anchor.
 *
 * Falls back to `today` (local midnight) for an absent, malformed or impossible
 * value, so a hand-typed URL cannot produce an `Invalid Date` and therefore an
 * `NaN` range on the wire. `today` is injectable only so the spec can pin the
 * fallback; production callers omit it.
 */
export function parseAnchorDate(value: string | null | undefined, today = new Date()): Date {
  if (value && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [year, month, day] = value.split('-').map(Number);
    const parsed = new Date(year, month - 1, day);
    // Rejects impossible calendar dates such as 2026-02-30, which `Date` would
    // silently roll over into March.
    if (
      parsed.getFullYear() === year &&
      parsed.getMonth() === month - 1 &&
      parsed.getDate() === day
    ) {
      return parsed;
    }
  }
  return new Date(today.getFullYear(), today.getMonth(), today.getDate());
}

/** `dd/MM/yyyy – dd/MM/yyyy`, Monday to Sunday, for the week navigator's label. */
export function formatWeekLabel(weekStart: Date): string {
  const end = addDays(weekStart, 6);
  const display = (date: Date): string =>
    `${pad(date.getDate(), 2)}/${pad(date.getMonth() + 1, 2)}/${date.getFullYear()}`;
  return `${display(weekStart)} – ${display(end)}`;
}

/**
 * Groups entries by the date part of their **slot's** `startAt` (D62).
 *
 * The slot's start alone decides the day: `endAt` never moves an entry into the
 * next column, an entry that ends after midnight stays on the day it starts.
 */
export function groupEntriesByDate(
  entries: SchedulingCalendarEntry[],
): Map<string, SchedulingCalendarEntry[]> {
  const grouped = new Map<string, SchedulingCalendarEntry[]>();
  for (const entry of entries) {
    const date = entry.startAt.slice(0, 10);
    const bucket = grouped.get(date);
    if (bucket) {
      bucket.push(entry);
    } else {
      grouped.set(date, [entry]);
    }
  }
  return grouped;
}

/**
 * The seven visible days of the week anchored at `weekStart`, Monday first, each
 * carrying the entries whose slot starts on it.
 *
 * Entries outside the week are dropped rather than folded into a column, so a
 * slot the projection returned off-range cannot silently render on a neighbouring
 * day.
 */
export function buildWeek(
  weekStart: Date,
  entries: SchedulingCalendarEntry[],
): SchedulingWeekDay[] {
  const grouped = groupEntriesByDate(entries);
  return Array.from({ length: 7 }, (_unused, offset) => {
    const day = addDays(weekStart, offset);
    const date = toIsoDate(day);
    return {
      date,
      weekdayIndex: isoWeekday(day),
      dayOfMonth: day.getDate(),
      month: day.getMonth() + 1,
      entries: grouped.get(date) ?? [],
    };
  });
}
