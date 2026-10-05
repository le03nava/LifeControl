/// <reference types="vitest/globals" />
import {
  StoreAssignment,
  StoreAssignmentDerivedScope,
  assignmentRangeLabel,
  assignmentStatus,
  assignmentStatusLabel,
  derivedChainLabel,
} from './store-assignment.models';

describe('store assignment models', () => {
  const scope = (
    overrides: Partial<StoreAssignmentDerivedScope> = {},
  ): StoreAssignmentDerivedScope => ({
    companyId: 'company-1',
    companyName: 'Acme Corp',
    companyCountryId: 'company-country-1',
    companyCountryName: 'México',
    companyRegionId: 'region-1',
    companyRegionName: 'Centro',
    companyZoneId: 'zone-1',
    companyZoneName: 'Zona Norte',
    ...overrides,
  });

  const assignment = (overrides: Partial<StoreAssignment> = {}): StoreAssignment => ({
    id: 'assignment-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    validFrom: '2026-01-01',
    validTo: null,
    enabled: true,
    derived: scope(),
    ...overrides,
  });

  describe('assignmentStatus', () => {
    it('reads an enabled row with no end date as open', () => {
      expect(assignmentStatus(assignment())).toBe('open');
    });

    it('reads an enabled row with an end date as closed', () => {
      expect(assignmentStatus(assignment({ validTo: '2026-06-30' }))).toBe('closed');
    });

    it('reads a soft-deleted row as disabled whatever its dates say', () => {
      // The soft-delete flag wins over the dates: a disabled row is neither open nor closed.
      expect(assignmentStatus(assignment({ enabled: false }))).toBe('disabled');
      expect(assignmentStatus(assignment({ enabled: false, validTo: '2026-06-30' }))).toBe(
        'disabled',
      );
    });
  });

  describe('assignmentStatusLabel', () => {
    it('labels the three states in Spanish', () => {
      expect(assignmentStatusLabel(assignment())).toBe('Abierta');
      expect(assignmentStatusLabel(assignment({ validTo: '2026-06-30' }))).toBe('Cerrada');
      expect(assignmentStatusLabel(assignment({ enabled: false }))).toBe('Deshabilitada');
    });
  });

  describe('assignmentRangeLabel', () => {
    it('names an open-ended assignment as such instead of inventing an end date', () => {
      expect(assignmentRangeLabel(assignment())).toBe('2026-01-01 – Sin fecha de fin');
    });

    it('renders both covered days when the row is closed', () => {
      // `validTo` is the last day covered, inclusive (D5), so it is rendered verbatim.
      expect(assignmentRangeLabel(assignment({ validTo: '2026-06-30' }))).toBe(
        '2026-01-01 – 2026-06-30',
      );
    });
  });

  describe('derivedChainLabel', () => {
    it('renders the four levels in the order the token carries them', () => {
      expect(derivedChainLabel(scope())).toBe('Acme Corp / México / Centro / Zona Norte');
    });

    it('keeps an unresolved level in place instead of shifting the chain', () => {
      // T14: a chain the entity graph does not resolve in one hop is omitted and reported, so the
      // display keeps each level in its own position rather than mislabelling the survivor.
      expect(derivedChainLabel(scope({ companyCountryName: null, companyRegionName: null }))).toBe(
        'Acme Corp / — / — / Zona Norte',
      );
    });

    it('renders a wholly unresolved chain as four placeholders', () => {
      expect(
        derivedChainLabel({
          companyId: null,
          companyName: null,
          companyCountryId: null,
          companyCountryName: null,
          companyRegionId: null,
          companyRegionName: null,
          companyZoneId: null,
          companyZoneName: null,
        }),
      ).toBe('— / — / — / —');
    });

    it('treats a blank name as unresolved', () => {
      expect(derivedChainLabel(scope({ companyCountryName: '   ' }))).toBe(
        'Acme Corp / — / Centro / Zona Norte',
      );
    });
  });
});
