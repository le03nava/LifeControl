import { PO_STATUS_TRANSITIONS, PO_STATUS_COLORS, PO_STATUS_LABELS } from './status-config';

describe('PO_STATUS_TRANSITIONS', () => {
  it('should have 8 status keys', () => {
    expect(Object.keys(PO_STATUS_TRANSITIONS)).toHaveLength(8);
  });

  describe('Draft', () => {
    it('should have valid transitions: Sent, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['Draft'];
      expect(transitions).toContain('Sent');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include invalid transitions', () => {
      const transitions = PO_STATUS_TRANSITIONS['Draft'];
      expect(transitions).not.toContain('Accepted');
      expect(transitions).not.toContain('In Transit');
      expect(transitions).not.toContain('Received');
      expect(transitions).not.toContain('Billed');
      expect(transitions).not.toContain('Closed');
    });
  });

  describe('Sent', () => {
    it('should have valid transitions: Accepted, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['Sent'];
      expect(transitions).toContain('Accepted');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include backwards transitions', () => {
      const transitions = PO_STATUS_TRANSITIONS['Sent'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Closed');
    });
  });

  describe('Accepted', () => {
    it('should have valid transitions: In Transit, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['Accepted'];
      expect(transitions).toContain('In Transit');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include sent or draft', () => {
      const transitions = PO_STATUS_TRANSITIONS['Accepted'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Sent');
    });
  });

  describe('In Transit', () => {
    it('should have valid transitions: Received, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['In Transit'];
      expect(transitions).toContain('Received');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include accepted or earlier', () => {
      const transitions = PO_STATUS_TRANSITIONS['In Transit'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Sent');
      expect(transitions).not.toContain('Accepted');
    });
  });

  describe('Received', () => {
    it('should have valid transitions: Billed, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['Received'];
      expect(transitions).toContain('Billed');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include In Transit or earlier', () => {
      const transitions = PO_STATUS_TRANSITIONS['Received'];
      expect(transitions).not.toContain('In Transit');
      expect(transitions).not.toContain('Accepted');
    });
  });

  describe('Billed', () => {
    it('should have valid transitions: Closed, Rejected', () => {
      const transitions = PO_STATUS_TRANSITIONS['Billed'];
      expect(transitions).toContain('Closed');
      expect(transitions).toContain('Rejected');
      expect(transitions).toHaveLength(2);
    });

    it('should NOT include Received or earlier', () => {
      const transitions = PO_STATUS_TRANSITIONS['Billed'];
      expect(transitions).not.toContain('Received');
      expect(transitions).not.toContain('In Transit');
    });
  });

  describe('Closed (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = PO_STATUS_TRANSITIONS['Closed'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });

    it('should NOT include any transitions', () => {
      const transitions = PO_STATUS_TRANSITIONS['Closed'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Sent');
      expect(transitions).not.toContain('Accepted');
      expect(transitions).not.toContain('Billed');
      expect(transitions).not.toContain('Rejected');
    });
  });

  describe('Rejected (terminal)', () => {
    it('should have an empty transitions array', () => {
      const transitions = PO_STATUS_TRANSITIONS['Rejected'];
      expect(transitions).toEqual([]);
      expect(transitions).toHaveLength(0);
    });

    it('should NOT include any transitions', () => {
      const transitions = PO_STATUS_TRANSITIONS['Rejected'];
      expect(transitions).not.toContain('Draft');
      expect(transitions).not.toContain('Sent');
      expect(transitions).not.toContain('Accepted');
      expect(transitions).not.toContain('Closed');
    });
  });

  describe('cross-status validation', () => {
    it('Rejected should be reachable from Draft through Billed (6 states)', () => {
      const reachableFrom: string[] = [
        'Draft',
        'Sent',
        'Accepted',
        'In Transit',
        'Received',
        'Billed',
      ];
      for (const status of reachableFrom) {
        expect(PO_STATUS_TRANSITIONS[status]).toContain('Rejected');
      }
    });

    it('Rejected should NOT be reachable from terminal states', () => {
      expect(PO_STATUS_TRANSITIONS['Closed']).not.toContain('Rejected');
      expect(PO_STATUS_TRANSITIONS['Rejected']).not.toContain('Rejected');
    });
  });
});

describe('PO_STATUS_COLORS', () => {
  it('should have 8 color entries', () => {
    expect(Object.keys(PO_STATUS_COLORS)).toHaveLength(8);
  });

  it('should have valid hex colors for all statuses', () => {
    const hexPattern = /^#[0-9A-Fa-f]{6}$/;
    for (const color of Object.values(PO_STATUS_COLORS)) {
      expect(color).toMatch(hexPattern);
    }
  });

  it('Draft should be gray (#9e9e9e)', () => {
    expect(PO_STATUS_COLORS['Draft']).toBe('#9e9e9e');
  });

  it('Sent should be orange (#ff9800)', () => {
    expect(PO_STATUS_COLORS['Sent']).toBe('#ff9800');
  });

  it('Accepted should be blue (#2196f3)', () => {
    expect(PO_STATUS_COLORS['Accepted']).toBe('#2196f3');
  });

  it('In Transit should be cyan (#00bcd4)', () => {
    expect(PO_STATUS_COLORS['In Transit']).toBe('#00bcd4');
  });

  it('Received should be green (#4caf50)', () => {
    expect(PO_STATUS_COLORS['Received']).toBe('#4caf50');
  });

  it('Billed should be teal (#009688)', () => {
    expect(PO_STATUS_COLORS['Billed']).toBe('#009688');
  });

  it('Closed should be blue-grey (#607d8b)', () => {
    expect(PO_STATUS_COLORS['Closed']).toBe('#607d8b');
  });

  it('Rejected should be red (#f44336)', () => {
    expect(PO_STATUS_COLORS['Rejected']).toBe('#f44336');
  });
});

describe('PO_STATUS_LABELS', () => {
  it('should have 8 label entries', () => {
    expect(Object.keys(PO_STATUS_LABELS)).toHaveLength(8);
  });

  it('Draft label should be "Borrador"', () => {
    expect(PO_STATUS_LABELS['Draft']).toBe('Borrador');
  });

  it('Sent label should be "Enviada"', () => {
    expect(PO_STATUS_LABELS['Sent']).toBe('Enviada');
  });

  it('Accepted label should be "Aceptada"', () => {
    expect(PO_STATUS_LABELS['Accepted']).toBe('Aceptada');
  });

  it('In Transit label should be "En Tránsito"', () => {
    expect(PO_STATUS_LABELS['In Transit']).toBe('En Tránsito');
  });

  it('Received label should be "Recibida"', () => {
    expect(PO_STATUS_LABELS['Received']).toBe('Recibida');
  });

  it('Billed label should be "Facturada"', () => {
    expect(PO_STATUS_LABELS['Billed']).toBe('Facturada');
  });

  it('Closed label should be "Cerrada"', () => {
    expect(PO_STATUS_LABELS['Closed']).toBe('Cerrada');
  });

  it('Rejected label should be "Rechazada"', () => {
    expect(PO_STATUS_LABELS['Rejected']).toBe('Rechazada');
  });

  it('all keys in TRANSITIONS should have a corresponding label', () => {
    for (const key of Object.keys(PO_STATUS_TRANSITIONS)) {
      expect(PO_STATUS_LABELS[key]).toBeDefined();
      expect(typeof PO_STATUS_LABELS[key]).toBe('string');
    }
  });
});
