import {
  SO_STATUS_TRANSITIONS,
  SO_ITEM_STATUS_TRANSITIONS,
  SO_STATUS_COLORS,
  SO_STATUS_LABELS,
} from './status-config';

describe('SO_STATUS_TRANSITIONS', () => {
  it('should have 4 status keys (Draft, Pending, Completed, Cancelled)', () => {
    expect(Object.keys(SO_STATUS_TRANSITIONS)).toHaveLength(4);
  });

  describe('Draft', () => {
    it('should have valid transitions: Pending, Cancelled', () => {
      const transitions = SO_STATUS_TRANSITIONS['Draft'];
      expect(transitions).toContain('Pending');
      expect(transitions).toContain('Cancelled');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include invalid transitions', () => {
      const transitions = SO_STATUS_TRANSITIONS['Draft'];
      expect(transitions).not.toContain('Completed');
      expect(transitions).not.toContain('Draft');
    });
  });

  describe('Pending', () => {
    it('should have valid transitions: Completed, Cancelled', () => {
      const transitions = SO_STATUS_TRANSITIONS['Pending'];
      expect(transitions).toContain('Completed');
      expect(transitions).toContain('Cancelled');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include backwards transitions', () => {
      const transitions = SO_STATUS_TRANSITIONS['Pending'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Pending');
    });
  });

  describe('Completed (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = SO_STATUS_TRANSITIONS['Completed'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });

    it('should NOT include any transitions', () => {
      const transitions = SO_STATUS_TRANSITIONS['Completed'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Pending');
      expect(transitions).not.toContain('Cancelled');
    });
  });

  describe('Cancelled (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = SO_STATUS_TRANSITIONS['Cancelled'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });

    it('should NOT include any transitions', () => {
      const transitions = SO_STATUS_TRANSITIONS['Cancelled'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Pending');
      expect(transitions).not.toContain('Completed');
    });
  });

  describe('cross-status validation', () => {
    it('Cancelled should be reachable from Draft and Pending', () => {
      expect(SO_STATUS_TRANSITIONS['Draft']).toContain('Cancelled');
      expect(SO_STATUS_TRANSITIONS['Pending']).toContain('Cancelled');
    });

    it('Cancelled should NOT be reachable from terminal states', () => {
      expect(SO_STATUS_TRANSITIONS['Completed']).not.toContain('Cancelled');
      expect(SO_STATUS_TRANSITIONS['Cancelled']).not.toContain('Cancelled');
    });

    it('Completed should only be reachable from Pending', () => {
      expect(SO_STATUS_TRANSITIONS['Draft']).not.toContain('Completed');
      expect(SO_STATUS_TRANSITIONS['Pending']).toContain('Completed');
      expect(SO_STATUS_TRANSITIONS['Completed']).not.toContain('Completed');
      expect(SO_STATUS_TRANSITIONS['Cancelled']).not.toContain('Completed');
    });
  });
});

describe('SO_ITEM_STATUS_TRANSITIONS', () => {
  it('should have 3 status keys (Pending, Added, Cancelled)', () => {
    expect(Object.keys(SO_ITEM_STATUS_TRANSITIONS)).toHaveLength(3);
  });

  describe('Pending', () => {
    it('should have valid transitions: Added, Cancelled', () => {
      const transitions = SO_ITEM_STATUS_TRANSITIONS['Pending'];
      expect(transitions).toContain('Added');
      expect(transitions).toContain('Cancelled');
      expect(transitions).toHaveLength(2);
    });
  });

  describe('Added (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = SO_ITEM_STATUS_TRANSITIONS['Added'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });
  });

  describe('Cancelled (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = SO_ITEM_STATUS_TRANSITIONS['Cancelled'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });
  });
});

describe('SO_STATUS_COLORS', () => {
  it('should have 4 color entries', () => {
    expect(Object.keys(SO_STATUS_COLORS)).toHaveLength(4);
  });

  it('should have valid hex colors for all statuses', () => {
    const hexPattern = /^#[0-9A-Fa-f]{6}$/;
    for (const color of Object.values(SO_STATUS_COLORS)) {
      expect(color).toMatch(hexPattern);
    }
  });

  it('Draft should be gray (#9e9e9e)', () => {
    expect(SO_STATUS_COLORS['Draft']).toBe('#9e9e9e');
  });

  it('Pending should be orange (#ff9800)', () => {
    expect(SO_STATUS_COLORS['Pending']).toBe('#ff9800');
  });

  it('Completed should be blue-grey (#607d8b)', () => {
    expect(SO_STATUS_COLORS['Completed']).toBe('#607d8b');
  });

  it('Cancelled should be red (#f44336)', () => {
    expect(SO_STATUS_COLORS['Cancelled']).toBe('#f44336');
  });
});

describe('SO_STATUS_LABELS', () => {
  it('should have 4 label entries', () => {
    expect(Object.keys(SO_STATUS_LABELS)).toHaveLength(4);
  });

  it('Draft label should be "Draft" (English)', () => {
    expect(SO_STATUS_LABELS['Draft']).toBe('Draft');
  });

  it('Pending label should be "Pending" (English)', () => {
    expect(SO_STATUS_LABELS['Pending']).toBe('Pending');
  });

  it('Completed label should be "Completed" (English)', () => {
    expect(SO_STATUS_LABELS['Completed']).toBe('Completed');
  });

  it('Cancelled label should be "Cancelled" (English)', () => {
    expect(SO_STATUS_LABELS['Cancelled']).toBe('Cancelled');
  });

  it('all keys in TRANSITIONS should have a corresponding label', () => {
    for (const key of Object.keys(SO_STATUS_TRANSITIONS)) {
      expect(SO_STATUS_LABELS[key]).toBeDefined();
      expect(typeof SO_STATUS_LABELS[key]).toBe('string');
    }
  });
});
