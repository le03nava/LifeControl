import {
  addOneYear,
  createDefaultRow,
  fromWireTime,
  fromWireWindow,
  toLocalIsoDate,
  toWireRequest,
  toWireTime,
  toWireWindow,
} from './scheduling-availability-wire';
import {
  SchedulingAvailabilityRowValue,
  SchedulingAvailabilityWindow,
} from '../models/scheduling-activity.models';

describe('scheduling-availability-wire', () => {
  const row = (
    overrides: Partial<SchedulingAvailabilityRowValue> = {},
  ): SchedulingAvailabilityRowValue => ({
    key: 'availability-row-1',
    dayOfWeek: 1,
    startTime: '09:00',
    endTime: '13:00',
    validFrom: '2026-09-28',
    validTo: '2027-09-28',
    ...overrides,
  });

  describe('toWireTime', () => {
    it('should append the seconds the API expects', () => {
      expect(toWireTime('09:00')).toBe('09:00:00');
      expect(toWireTime('13:05')).toBe('13:05:00');
      expect(toWireTime('00:00')).toBe('00:00:00');
    });

    it('should leave an empty value untouched rather than invent a time', () => {
      expect(toWireTime('')).toBe('');
    });
  });

  describe('fromWireTime', () => {
    it('should strip the seconds a native time input cannot hold', () => {
      expect(fromWireTime('09:00:00')).toBe('09:00');
      expect(fromWireTime('13:05:30')).toBe('13:05');
    });

    it('should leave a value without seconds untouched', () => {
      expect(fromWireTime('09:00')).toBe('09:00');
      expect(fromWireTime('')).toBe('');
    });
  });

  describe('toWireWindow / toWireRequest', () => {
    it('should send exactly the request fields with the time wire format', () => {
      expect(toWireWindow(row())).toEqual({
        dayOfWeek: 1,
        startTime: '09:00:00',
        endTime: '13:00:00',
        validFrom: '2026-09-28',
        validTo: '2027-09-28',
      });
    });

    it('should drop the client key, the row id and any enabled flag', () => {
      const window = toWireWindow(row());
      expect(window).not.toHaveProperty('key');
      expect(window).not.toHaveProperty('id');
      expect(window).not.toHaveProperty('enabled');
    });

    it('should wrap every row in the whole-set request body', () => {
      expect(toWireRequest([row(), row({ key: 'availability-row-2', dayOfWeek: 3 })])).toEqual({
        windows: [
          {
            dayOfWeek: 1,
            startTime: '09:00:00',
            endTime: '13:00:00',
            validFrom: '2026-09-28',
            validTo: '2027-09-28',
          },
          {
            dayOfWeek: 3,
            startTime: '09:00:00',
            endTime: '13:00:00',
            validFrom: '2026-09-28',
            validTo: '2027-09-28',
          },
        ],
      });
    });

    it('should clear the template with an empty window list', () => {
      expect(toWireRequest([])).toEqual({ windows: [] });
    });
  });

  describe('fromWireWindow', () => {
    const wire: SchedulingAvailabilityWindow = {
      id: 'db-row-id',
      dayOfWeek: 5,
      startTime: '08:30:00',
      endTime: '12:00:00',
      validFrom: '2026-10-01',
      validTo: '2026-12-31',
    };

    it('should read the native input values and drop the server id', () => {
      expect(fromWireWindow(wire)).toEqual({
        dayOfWeek: 5,
        startTime: '08:30',
        endTime: '12:00',
        validFrom: '2026-10-01',
        validTo: '2026-12-31',
      });
    });
  });

  describe('date helpers', () => {
    it('should format the browser local date as yyyy-MM-dd', () => {
      expect(toLocalIsoDate(new Date(2026, 8, 28))).toBe('2026-09-28');
    });

    it('should add one calendar year, keeping month and day', () => {
      expect(addOneYear('2026-09-28')).toBe('2027-09-28');
      expect(addOneYear('2027-01-01')).toBe('2028-01-01');
    });
  });

  describe('createDefaultRow', () => {
    it('should default to Monday 09:00-13:00 valid from today to one year later (D47)', () => {
      expect(createDefaultRow('local-1', '2026-09-28')).toEqual({
        key: 'local-1',
        dayOfWeek: 1,
        startTime: '09:00',
        endTime: '13:00',
        validFrom: '2026-09-28',
        validTo: '2027-09-28',
      });
    });
  });
});
