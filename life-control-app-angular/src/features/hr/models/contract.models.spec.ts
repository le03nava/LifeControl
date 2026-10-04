import { CONTRACT_TYPE_LABELS, ContractType, contractTypeLabel } from './contract.models';

describe('contract models', () => {
  describe('CONTRACT_TYPE_LABELS', () => {
    it('should map the five legal forms to their Spanish labels', () => {
      // Literal pin: these five are the Java enum `ContractType` of D13, and this map is the
      // only place the UI turns them into Spanish copy.
      expect(CONTRACT_TYPE_LABELS).toEqual({
        PERMANENT: 'Permanente',
        FIXED_TERM: 'Plazo fijo',
        TEMPORARY: 'Temporal',
        INTERNSHIP: 'Pasantía',
        CONTRACTOR: 'Contratista',
      });
    });

    it('should not carry PART_TIME, which is deliberately not a legal form', () => {
      // G9/D13: `contract_type` names the legal form, not the workload.
      expect(Object.keys(CONTRACT_TYPE_LABELS)).toHaveLength(5);
      expect(CONTRACT_TYPE_LABELS).not.toHaveProperty('PART_TIME');
    });
  });

  describe('contractTypeLabel', () => {
    it('should translate every contract type', () => {
      const types: ContractType[] = [
        'PERMANENT',
        'FIXED_TERM',
        'TEMPORARY',
        'INTERNSHIP',
        'CONTRACTOR',
      ];
      for (const type of types) {
        expect(contractTypeLabel(type)).toBe(CONTRACT_TYPE_LABELS[type]);
      }
    });

    it('should fall back to the raw value for an unknown type', () => {
      expect(contractTypeLabel('PART_TIME' as ContractType)).toBe('PART_TIME');
    });

    it('should not resolve a prototype member', () => {
      // A bare lookup would reach Object.prototype and the fallback would never fire.
      expect(contractTypeLabel('constructor' as ContractType)).toBe('constructor');
    });
  });
});
