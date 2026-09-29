import {
  SchedulingAvailabilityRequest,
  SchedulingAvailabilityRowValue,
  SchedulingAvailabilityWindow,
  SchedulingAvailabilityWindowRequest,
} from '../models/scheduling-activity.models';

/**
 * The one place where availability rows cross the wire (D43).
 *
 * The API answers `LocalTime` as `"HH:mm:ss"` and `LocalDate` as `"yyyy-MM-dd"`,
 * while a native `<input type="time">` holds `"HH:mm"` and a native
 * `<input type="date">` already holds `"yyyy-MM-dd"`. Reading strips the seconds
 * and writing appends them; nothing else converts. Keeping the conversion here
 * means the editor never scatters inline string surgery through its template.
 *
 * The dates are store-local wall-clock with no timezone conversion anywhere in
 * this domain (a known, declared gap in the feature record), so building a
 * default row from the browser's local date is acceptable and stated here rather
 * than derived silently.
 */

/** Pads a non-negative integer to two digits. */
function pad2(value: number): string {
  return value.toString().padStart(2, '0');
}

/** The browser's **local** calendar date as `yyyy-MM-dd`. */
export function toLocalIsoDate(date: Date = new Date()): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

/** Adds one calendar year to a `yyyy-MM-dd` date, keeping the same month and day. */
export function addOneYear(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  return toLocalIsoDate(new Date(year + 1, month - 1, day));
}

/** `"HH:mm"` (a native time input value) -> `"HH:mm:ss"` (the API format). */
export function toWireTime(nativeTime: string): string {
  const parts = nativeTime.split(':');
  return parts.length >= 2 ? `${parts[0]}:${parts[1]}:00` : nativeTime;
}

/** `"HH:mm:ss"` (the API format) -> `"HH:mm"` (a native time input value). */
export function fromWireTime(wireTime: string): string {
  const parts = wireTime.split(':');
  return parts.length >= 2 ? `${parts[0]}:${parts[1]}` : wireTime;
}

/**
 * Converts one editor row into a request window.
 *
 * The `key` is deliberately dropped, so the wire body carries exactly
 * `dayOfWeek`, `startTime`, `endTime`, `validFrom` and `validTo` — no `id`, no
 * `enabled`, and no client key.
 */
export function toWireWindow(
  row: SchedulingAvailabilityRowValue,
): SchedulingAvailabilityWindowRequest {
  return {
    dayOfWeek: row.dayOfWeek,
    startTime: toWireTime(row.startTime),
    endTime: toWireTime(row.endTime),
    validFrom: row.validFrom,
    validTo: row.validTo,
  };
}

/** The whole-set request body: the rows are the entire new template. */
export function toWireRequest(
  rows: SchedulingAvailabilityRowValue[],
): SchedulingAvailabilityRequest {
  return { windows: rows.map(toWireWindow) };
}

/**
 * Converts one response window into the editable fields of a row.
 *
 * The response `id` is read and thrown away on purpose: the row is regenerated on
 * every save, so it cannot identify anything across saves (D44).
 */
export function fromWireWindow(
  window: SchedulingAvailabilityWindow,
): Omit<SchedulingAvailabilityRowValue, 'key'> {
  return {
    dayOfWeek: window.dayOfWeek,
    startTime: fromWireTime(window.startTime),
    endTime: fromWireTime(window.endTime),
    validFrom: window.validFrom,
    validTo: window.validTo,
  };
}

/**
 * A new row's defaults (D47): Monday, `09:00`-`13:00`, valid from `today` to one
 * year later. Both dates stay editable, so the derivation is a starting point and
 * never a hidden rule.
 */
export function createDefaultRow(
  key: string,
  today: string = toLocalIsoDate(),
): SchedulingAvailabilityRowValue {
  return {
    key,
    dayOfWeek: 1,
    startTime: '09:00',
    endTime: '13:00',
    validFrom: today,
    validTo: addOneYear(today),
  };
}
