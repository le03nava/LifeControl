/// <reference types="vitest/globals" />
import { SchedulingCalendarEntry } from '../models/scheduling-calendar.models';
import {
  addDays,
  buildWeek,
  formatWeekLabel,
  groupEntriesByDate,
  isoWeekday,
  parseAnchorDate,
  startOfIsoWeek,
  toIsoDate,
  toIsoDateTime,
  toIsoMidnight,
  toTimeLabel,
} from './scheduling-calendar-week';

describe('scheduling-calendar-week', () => {
  const entry = (
    startAt: string,
    endAt = startAt,
    overrides: Partial<SchedulingCalendarEntry> = {},
  ): SchedulingCalendarEntry => ({
    slotId: `slot-${startAt}`,
    activityId: 'activity-1',
    activityName: 'Yoga',
    activityEnabled: true,
    startAt,
    endAt,
    capacity: 8,
    booked: 2,
    available: 6,
    status: 'Available',
    appointments: [],
    ...overrides,
  });

  describe('isoWeekday', () => {
    it('maps Sunday (Date.getDay() === 0) to ISO 7, the trap D17 names', () => {
      const sunday = new Date(2026, 8, 27);

      // The premise of the mapping, asserted rather than assumed: if this ever
      // stops being a Sunday the mapping assertion below proves nothing.
      expect(sunday.getDay()).toBe(0);
      expect(isoWeekday(sunday)).toBe(7);
    });

    it('maps Monday to ISO 1 and Saturday to ISO 6', () => {
      expect(isoWeekday(new Date(2026, 8, 28))).toBe(1);
      expect(isoWeekday(new Date(2026, 9, 3))).toBe(6);
    });
  });

  describe('startOfIsoWeek', () => {
    it('returns the same Monday for a Monday anchor', () => {
      expect(toIsoDate(startOfIsoWeek(new Date(2026, 8, 28)))).toBe('2026-09-28');
    });

    it('returns the previous Monday for a Sunday anchor (the 0 -> 7 rule)', () => {
      expect(toIsoDate(startOfIsoWeek(new Date(2026, 8, 27)))).toBe('2026-09-21');
    });

    it('returns the Monday of mid-week anchors, crossing a month boundary', () => {
      expect(toIsoDate(startOfIsoWeek(new Date(2026, 9, 1)))).toBe('2026-09-28');
      expect(toIsoDate(startOfIsoWeek(new Date(2026, 9, 4)))).toBe('2026-09-28');
    });

    it('normalizes the time to local midnight', () => {
      const start = startOfIsoWeek(new Date(2026, 8, 30, 17, 45, 3));

      expect(toIsoDateTime(start)).toBe('2026-09-28T00:00:00');
    });
  });

  describe('addDays', () => {
    it('advances across a month boundary and keeps local midnight', () => {
      expect(toIsoDateTime(addDays(new Date(2026, 8, 28), 7))).toBe('2026-10-05T00:00:00');
      expect(toIsoDate(addDays(new Date(2026, 8, 30), 1))).toBe('2026-10-01');
    });

    it('never mutates its argument', () => {
      const anchor = new Date(2026, 8, 28);
      addDays(anchor, 7);
      expect(toIsoDate(anchor)).toBe('2026-09-28');
    });
  });

  describe('toIsoDate / toIsoDateTime', () => {
    it('zero-pads month, day, hours, minutes and seconds', () => {
      expect(toIsoDate(new Date(2026, 0, 5))).toBe('2026-01-05');
      expect(toIsoDateTime(new Date(2026, 0, 5, 9, 5, 7))).toBe('2026-01-05T09:05:07');
      expect(toIsoDateTime(new Date(2026, 11, 31, 0, 0, 0))).toBe('2026-12-31T00:00:00');
    });

    it('emits no offset and no Z, matching the server LocalDateTime contract', () => {
      const wire = toIsoDateTime(new Date(2026, 8, 28, 12, 34, 56));

      expect(wire).toBe('2026-09-28T12:34:56');
      expect(wire).not.toContain('Z');
      expect(wire).not.toMatch(/[+-]\d{2}:\d{2}$/);
    });
  });

  describe('toIsoMidnight', () => {
    it('always emits local midnight, whatever time-of-day the instant carries (F2)', () => {
      // The DST shape this pins: on a day whose local midnight does not exist,
      // `new Date(y, m, d)` and `startOfIsoWeek` return 01:00, so a bound read off
      // that instant would be `T01:00:00` and drop a real Monday 00:00–01:00 slot
      // from both the materialization and the projection.
      expect(toIsoMidnight(new Date(2026, 7, 31, 1, 0, 0))).toBe('2026-08-31T00:00:00');
      expect(toIsoMidnight(new Date(2026, 8, 28, 17, 45, 3))).toBe('2026-09-28T00:00:00');
    });

    it('keeps the calendar date the instant reports', () => {
      expect(toIsoMidnight(new Date(2026, 8, 28))).toBe('2026-09-28T00:00:00');
      expect(toIsoMidnight(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31T00:00:00');
    });
  });

  describe('toTimeLabel', () => {
    it('renders HH:mm from a wire date-time', () => {
      expect(toTimeLabel('2026-09-28T09:05:00')).toBe('09:05');
      expect(toTimeLabel('2026-09-28T23:59:59')).toBe('23:59');
    });

    it('accepts an already-display value unchanged', () => {
      expect(toTimeLabel('09:05')).toBe('09:05');
    });

    it('falls back to the raw value when it carries no time', () => {
      expect(toTimeLabel('')).toBe('');
      expect(toTimeLabel('not-a-time')).toBe('not-a-time');
    });
  });

  describe('parseAnchorDate', () => {
    const today = new Date(2026, 8, 30, 15, 20, 0);

    it('parses a valid YYYY-MM-DD at local midnight', () => {
      expect(toIsoDateTime(parseAnchorDate('2026-10-01', today))).toBe('2026-10-01T00:00:00');
    });

    it('falls back to today for an absent value', () => {
      expect(toIsoDate(parseAnchorDate(null, today))).toBe('2026-09-30');
      expect(toIsoDate(parseAnchorDate(undefined, today))).toBe('2026-09-30');
      expect(toIsoDate(parseAnchorDate('', today))).toBe('2026-09-30');
    });

    it('falls back to today for garbage a hand-typed URL can carry', () => {
      expect(toIsoDate(parseAnchorDate('not-a-date', today))).toBe('2026-09-30');
      expect(toIsoDate(parseAnchorDate('01/10/2026', today))).toBe('2026-09-30');
      expect(toIsoDate(parseAnchorDate('2026-9-1', today))).toBe('2026-09-30');
    });

    it('falls back to today for an impossible calendar date', () => {
      expect(toIsoDate(parseAnchorDate('2026-02-30', today))).toBe('2026-09-30');
      expect(toIsoDate(parseAnchorDate('2026-13-01', today))).toBe('2026-09-30');
    });

    it('never produces an Invalid Date (NaN), whatever the input', () => {
      for (const value of ['', 'x', '2026-02-30', '9999-99-99', null, undefined]) {
        expect(Number.isNaN(parseAnchorDate(value, today).getTime())).toBe(false);
      }
    });
  });

  describe('groupEntriesByDate', () => {
    it('keys entries by the date part of their slot startAt', () => {
      const grouped = groupEntriesByDate([
        entry('2026-09-30T09:00:00'),
        entry('2026-09-30T11:00:00'),
        entry('2026-10-01T08:00:00'),
      ]);

      expect(grouped.get('2026-09-30')).toHaveLength(2);
      expect(grouped.get('2026-10-01')).toHaveLength(1);
      expect(grouped.get('2026-10-02')).toBeUndefined();
    });
  });

  describe('buildWeek', () => {
    it('builds Monday..Sunday with ISO weekday indexes and day numbers', () => {
      const week = buildWeek(new Date(2026, 8, 28), []);

      expect(week.map((day) => day.date)).toEqual([
        '2026-09-28',
        '2026-09-29',
        '2026-09-30',
        '2026-10-01',
        '2026-10-02',
        '2026-10-03',
        '2026-10-04',
      ]);
      expect(week.map((day) => day.weekdayIndex)).toEqual([1, 2, 3, 4, 5, 6, 7]);
      expect(week[0]).toMatchObject({ dayOfMonth: 28, month: 9 });
      expect(week[6]).toMatchObject({ dayOfMonth: 4, month: 10 });
    });

    it('attaches each entry to the day its slot starts on', () => {
      const week = buildWeek(new Date(2026, 8, 28), [
        entry('2026-09-30T09:00:00'),
        entry('2026-09-30T11:00:00'),
        entry('2026-10-01T08:00:00'),
      ]);

      expect(week.find((day) => day.date === '2026-09-30')?.entries).toHaveLength(2);
      expect(week.find((day) => day.date === '2026-10-01')?.entries).toHaveLength(1);
      expect(week.find((day) => day.date === '2026-09-28')?.entries).toEqual([]);
    });

    it('never moves an entry into the next day because of its endAt (D62)', () => {
      const week = buildWeek(new Date(2026, 8, 28), [
        entry('2026-09-30T23:30:00', '2026-10-01T00:30:00'),
      ]);

      expect(week.find((day) => day.date === '2026-09-30')?.entries).toHaveLength(1);
      expect(week.find((day) => day.date === '2026-10-01')?.entries).toHaveLength(0);
    });

    it('drops an entry whose date is outside the week instead of inventing a column', () => {
      const week = buildWeek(new Date(2026, 8, 28), [entry('2026-11-10T09:00:00')]);

      expect(week.every((day) => day.entries.length === 0)).toBe(true);
    });
  });

  describe('formatWeekLabel', () => {
    it('renders the Monday..Sunday span as dd/MM/yyyy', () => {
      expect(formatWeekLabel(new Date(2026, 8, 28))).toBe('28/09/2026 – 04/10/2026');
    });
  });
});
