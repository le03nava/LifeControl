/// <reference types="vitest/globals" />
import {
  SCOPE_ASSIGNMENT_LEVELS,
  ScopeAssignment,
  ScopeAssignmentDerivedScope,
  scopeAssignmentLevelLabel,
  scopeAssignmentRangeLabel,
  scopeAssignmentStatus,
  scopeAssignmentStatusLabel,
  scopeDerivedChainLabel,
} from './scope-assignment.models';

describe('scope assignment models', () => {
  const scope = (
    overrides: Partial<ScopeAssignmentDerivedScope> = {},
  ): ScopeAssignmentDerivedScope => ({
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

  const assignment = (overrides: Partial<ScopeAssignment> = {}): ScopeAssignment => ({
    id: 'assignment-1',
    level: 'ZONE',
    companyCountryId: 'company-country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId: null,
    nodeName: 'Zona Norte',
    validFrom: '2026-01-01',
    validTo: null,
    enabled: true,
    derived: scope(),
    ...overrides,
  });

  describe('SCOPE_ASSIGNMENT_LEVELS', () => {
    it('lists the four assignable levels in the order the tree descends', () => {
      // S2: the store is this table's fourth level, not a table of its own.
      expect(SCOPE_ASSIGNMENT_LEVELS).toEqual(['COUNTRY', 'REGION', 'ZONE', 'STORE']);
    });

    it('carries no company level, because the company is derived and never assigned', () => {
      // S3: `company_id` is exactly one and comes from `employees.company_id`, so there is no
      // assignable company row — a level here would be a second copy of a fact the employee owns.
      expect(SCOPE_ASSIGNMENT_LEVELS).not.toContain('COMPANY');
    });
  });

  describe('scopeAssignmentLevelLabel', () => {
    it('labels the four levels in Spanish', () => {
      expect(scopeAssignmentLevelLabel('COUNTRY')).toBe('País');
      expect(scopeAssignmentLevelLabel('REGION')).toBe('Región');
      expect(scopeAssignmentLevelLabel('ZONE')).toBe('Zona');
      expect(scopeAssignmentLevelLabel('STORE')).toBe('Tienda');
    });
  });

  describe('a STORE row', () => {
    it('carries the store as its node and everything above it in the derived chain', () => {
      const store = assignment({
        level: 'STORE',
        companyStoreId: 'store-1',
        nodeName: 'Tienda Centro',
        derived: scope({ companyZoneName: 'Zona Norte' }),
      });

      expect(scopeAssignmentLevelLabel(store.level)).toBe('Tienda');
      expect(store.companyStoreId).toBe('store-1');
      expect(store.nodeName).toBe('Tienda Centro');
      // One row shape for the merged table: a store row resolves the same four ancestors.
      expect(scopeDerivedChainLabel(store.derived)).toBe(
        'Acme Corp / México / Centro / Zona Norte',
      );
    });

    it('is open, closed and disabled exactly like any other level', () => {
      const store = assignment({ level: 'STORE', companyStoreId: 'store-1' });

      expect(scopeAssignmentStatus(store)).toBe('open');
      expect(scopeAssignmentStatus({ ...store, validTo: '2026-06-30' })).toBe('closed');
      expect(scopeAssignmentStatus({ ...store, enabled: false })).toBe('disabled');
    });
  });

  describe('scopeAssignmentStatus', () => {
    it('reads an enabled row with no end date as open', () => {
      expect(scopeAssignmentStatus(assignment())).toBe('open');
    });

    it('reads an enabled row with an end date as closed', () => {
      expect(scopeAssignmentStatus(assignment({ validTo: '2026-06-30' }))).toBe('closed');
    });

    it('reads a soft-deleted row as disabled whatever its dates say', () => {
      expect(scopeAssignmentStatus(assignment({ enabled: false }))).toBe('disabled');
      expect(scopeAssignmentStatus(assignment({ enabled: false, validTo: '2026-06-30' }))).toBe(
        'disabled',
      );
    });
  });

  describe('scopeAssignmentStatusLabel', () => {
    it('labels the three states in Spanish', () => {
      expect(scopeAssignmentStatusLabel(assignment())).toBe('Abierta');
      expect(scopeAssignmentStatusLabel(assignment({ validTo: '2026-06-30' }))).toBe('Cerrada');
      expect(scopeAssignmentStatusLabel(assignment({ enabled: false }))).toBe('Deshabilitada');
    });
  });

  describe('scopeAssignmentRangeLabel', () => {
    it('names an open-ended assignment as such instead of inventing an end date', () => {
      expect(scopeAssignmentRangeLabel(assignment())).toBe('2026-01-01 – Sin fecha de fin');
    });

    it('renders both covered days when the row is closed', () => {
      expect(scopeAssignmentRangeLabel(assignment({ validTo: '2026-06-30' }))).toBe(
        '2026-01-01 – 2026-06-30',
      );
    });
  });

  describe('scopeDerivedChainLabel', () => {
    it('renders the four levels in the order the token carries them', () => {
      expect(scopeDerivedChainLabel(scope())).toBe('Acme Corp / México / Centro / Zona Norte');
    });

    it('keeps an unresolved level in place instead of shifting the chain', () => {
      expect(
        scopeDerivedChainLabel(scope({ companyCountryName: null, companyRegionName: null })),
      ).toBe('Acme Corp / — / — / Zona Norte');
    });

    it('treats a blank name as unresolved', () => {
      expect(scopeDerivedChainLabel(scope({ companyCountryName: '   ' }))).toBe(
        'Acme Corp / — / Centro / Zona Norte',
      );
    });
  });
});
