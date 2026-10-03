import {
  EMPLOYEE_STATUS_LABELS,
  EMPLOYEE_STATUS_TYPE_NAME,
  employeeStatusLabel,
} from './employee-status';

describe('employee-status', () => {
  describe('EMPLOYEE_STATUS_TYPE_NAME', () => {
    it('should be the seeded family name', () => {
      expect(EMPLOYEE_STATUS_TYPE_NAME).toBe('EMPLOYEE_STATUS');
    });
  });

  describe('EMPLOYEE_STATUS_LABELS', () => {
    it('should map the four seeded English names to Spanish labels', () => {
      // Literal pin: these are the four names V20 seeds, and this map is the only
      // place the UI turns them into Spanish copy.
      expect(EMPLOYEE_STATUS_LABELS).toEqual({
        Active: 'Activo',
        Inactive: 'Inactivo',
        OnLeave: 'Con licencia',
        Terminated: 'Dado de baja',
      });
    });
  });

  describe('employeeStatusLabel', () => {
    it('should translate a known status name', () => {
      expect(employeeStatusLabel('Active')).toBe('Activo');
      expect(employeeStatusLabel('Terminated')).toBe('Dado de baja');
    });

    it('should fall back to the raw name for an unknown status', () => {
      expect(employeeStatusLabel('Sabbatical')).toBe('Sabbatical');
    });

    it('should not resolve a prototype member for a name like constructor', () => {
      // A bare lookup would reach Object.prototype and the fallback would never fire.
      expect(employeeStatusLabel('constructor')).toBe('constructor');
    });
  });
});
