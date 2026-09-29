import {
  APPOINTMENT_STATUS_LABELS,
  APPOINTMENT_STATUS_TYPE_NAME,
  APPOINTMENT_TRANSITION_LABELS,
  APPOINTMENT_TRANSITIONS,
  allowedTransitionNames,
  isTerminalStatus,
} from './scheduling-appointment-status';

/**
 * The server's five status names, transcribed here so the spec pins the mirror
 * against the server's own vocabulary rather than against the mirror itself.
 */
const ALL_STATUS_NAMES = ['Scheduled', 'Confirmed', 'Completed', 'Cancelled', 'NoShow'] as const;

const TERMINAL_STATUS_NAMES = ['Completed', 'Cancelled', 'NoShow'] as const;

describe('appointment status mirror', () => {
  it('should name the APPOINTMENT status type', () => {
    expect(APPOINTMENT_STATUS_TYPE_NAME).toBe('APPOINTMENT');
  });

  describe('APPOINTMENT_TRANSITIONS', () => {
    it('should carry exactly the server map rows (SchedulingAppointmentService.java:102-107)', () => {
      // Each edge is asserted on its own so a drift on one row names that row.
      expect(APPOINTMENT_TRANSITIONS['Scheduled']).toEqual([
        'Confirmed',
        'Completed',
        'Cancelled',
        'NoShow',
      ]);
      expect(APPOINTMENT_TRANSITIONS['Confirmed']).toEqual(['Completed', 'Cancelled', 'NoShow']);
      expect(APPOINTMENT_TRANSITIONS['Completed']).toEqual([]);
      expect(APPOINTMENT_TRANSITIONS['Cancelled']).toEqual([]);
      expect(APPOINTMENT_TRANSITIONS['NoShow']).toEqual([]);
    });

    it('should expose exactly the server five names and no extra key', () => {
      expect(Object.keys(APPOINTMENT_TRANSITIONS).sort()).toEqual([...ALL_STATUS_NAMES].sort());
    });
  });

  describe('allowedTransitionNames', () => {
    it('should return the declared edges for a known non-terminal status', () => {
      expect(allowedTransitionNames('Scheduled')).toEqual([
        'Confirmed',
        'Completed',
        'Cancelled',
        'NoShow',
      ]);
      expect(allowedTransitionNames('Confirmed')).toEqual(['Completed', 'Cancelled', 'NoShow']);
    });

    it('should return an empty list for every terminal status', () => {
      for (const name of TERMINAL_STATUS_NAMES) {
        expect(allowedTransitionNames(name)).toEqual([]);
      }
    });

    it('should fail closed to an empty list for an unknown status name', () => {
      // G40: a name the mirror does not know offers no action. Returning the whole
      // catalogue or `undefined` would turn a silent server-side addition into a
      // guaranteed 409 or a broken render.
      expect(allowedTransitionNames('Rescheduled')).toEqual([]);
      expect(allowedTransitionNames('')).toEqual([]);
      expect(allowedTransitionNames('scheduled')).toEqual([]);
    });

    it('should not read through the prototype chain for an inherited key like constructor', () => {
      // A bare lookup would return `Object.prototype.constructor`, so `?? []` would never
      // fire and the mirror would fail open, while the server's
      // `APPOINTMENT_TRANSITIONS.getOrDefault(currentName, Set.of()).isEmpty()`
      // (`SchedulingAppointmentService.java:533`) is terminal for every missing key.
      expect(allowedTransitionNames('constructor')).toEqual([]);
      expect(isTerminalStatus('constructor')).toBe(true);
    });
  });

  describe('isTerminalStatus', () => {
    it('should be true for the three terminal statuses', () => {
      for (const name of TERMINAL_STATUS_NAMES) {
        expect(isTerminalStatus(name)).toBe(true);
      }
    });

    it('should be false for the two non-terminal statuses', () => {
      expect(isTerminalStatus('Scheduled')).toBe(false);
      expect(isTerminalStatus('Confirmed')).toBe(false);
    });

    it('should treat an unknown status name as terminal, matching the server default', () => {
      // The server map's own comment: a missing key or an empty set means terminal.
      expect(isTerminalStatus('Rescheduled')).toBe(true);
    });
  });

  describe('APPOINTMENT_STATUS_LABELS', () => {
    it('should label every one of the server five names', () => {
      expect(Object.keys(APPOINTMENT_STATUS_LABELS).sort()).toEqual([...ALL_STATUS_NAMES].sort());
      for (const name of ALL_STATUS_NAMES) {
        expect(APPOINTMENT_STATUS_LABELS[name]).toBeTruthy();
      }
    });

    it('should translate every name instead of echoing the raw English key', () => {
      // A missing entry would fall back to the raw English status name in the chip.
      for (const name of ALL_STATUS_NAMES) {
        expect(APPOINTMENT_STATUS_LABELS[name]).not.toBe(name);
      }
      expect(APPOINTMENT_STATUS_LABELS['Scheduled']).toBe('Agendado');
      expect(APPOINTMENT_STATUS_LABELS['Cancelled']).toBe('Cancelado');
    });
  });

  describe('APPOINTMENT_TRANSITION_LABELS', () => {
    it('should label exactly the four transition targets and never Scheduled', () => {
      const targets = [...new Set(Object.values(APPOINTMENT_TRANSITIONS).flat())].sort();
      expect(Object.keys(APPOINTMENT_TRANSITION_LABELS).sort()).toEqual(targets);
      expect(Object.keys(APPOINTMENT_TRANSITION_LABELS)).not.toContain('Scheduled');
    });

    it('should use action verbs in Spanish, so a button never reads as a state', () => {
      for (const name of Object.keys(APPOINTMENT_TRANSITION_LABELS)) {
        expect(APPOINTMENT_TRANSITION_LABELS[name]).not.toBe(name);
      }
      expect(APPOINTMENT_TRANSITION_LABELS['Confirmed']).toBe('Confirmar');
      expect(APPOINTMENT_TRANSITION_LABELS['Cancelled']).toBe('Cancelar');
    });
  });
});
