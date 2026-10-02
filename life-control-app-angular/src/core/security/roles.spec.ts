import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import {
  CLIENT_ID,
  CLIENT_ROLES,
  DEPARTMENT_WRITE_ROLES,
  hasAnyClientRole,
  HR_WRITE_ROLES,
  LC_ADMIN,
  LC_DEPARTMENT,
  LC_POSITION,
  LC_SALES,
  LC_SENIORITY_LEVEL,
  POSITION_WRITE_ROLES,
  SENIORITY_LEVEL_WRITE_ROLES,
  VARIANT_ROLES,
} from './roles';

describe('roles', () => {
  let keycloakMock: Partial<Keycloak>;

  beforeEach(() => {
    keycloakMock = { tokenParsed: undefined };
    TestBed.configureTestingModule({
      providers: [{ provide: Keycloak, useValue: keycloakMock }],
    });
  });

  /** Shapes a token like a real Keycloak one: client roles live under resource_access. */
  function withClientRoles(roles: string[]): void {
    keycloakMock.tokenParsed = {
      resource_access: { [CLIENT_ID]: { roles } },
    } as unknown as Keycloak['tokenParsed'];
  }

  describe('LC_SALES', () => {
    it('should equal the wire role name', () => {
      expect(LC_SALES).toBe('lc-sales');
    });

    it('should be registered in CLIENT_ROLES', () => {
      expect(CLIENT_ROLES).toContain(LC_SALES);
    });
  });

  describe('VARIANT_ROLES', () => {
    it('should be the flat [lc-admin, lc-sales] allow-list', () => {
      // Literal wire strings, not the imported constants: the route gates and the
      // backend @PreAuthorize both match on these exact names.
      expect(VARIANT_ROLES).toEqual(['lc-admin', 'lc-sales']);
    });
  });

  describe('department, position and seniority-level roles', () => {
    it.each([
      ['lc-department', LC_DEPARTMENT],
      ['lc-position', LC_POSITION],
      ['lc-seniority-level', LC_SENIORITY_LEVEL],
    ])('should register %s in CLIENT_ROLES', (wire, constant) => {
      expect(constant).toBe(wire);
      expect(CLIENT_ROLES).toContain(constant);
    });

    it('should keep DEPARTMENT_WRITE_ROLES as the flat [lc-admin, lc-department] allow-list', () => {
      // Literal wire strings: the route data and DepartmentController's
      // @PreAuthorize both match these exact names.
      expect(DEPARTMENT_WRITE_ROLES).toEqual(['lc-admin', 'lc-department']);
    });

    it('should keep POSITION_WRITE_ROLES as the flat [lc-admin, lc-position] allow-list', () => {
      expect(POSITION_WRITE_ROLES).toEqual(['lc-admin', 'lc-position']);
    });

    it('should keep SENIORITY_LEVEL_WRITE_ROLES as the flat [lc-admin, lc-seniority-level] allow-list', () => {
      expect(SENIORITY_LEVEL_WRITE_ROLES).toEqual(['lc-admin', 'lc-seniority-level']);
    });

    it('should make HR_WRITE_ROLES the union of the three write sets', () => {
      // The menu-visibility set (D17). A literal pin because it is the exact
      // condition the header entry renders on.
      expect(HR_WRITE_ROLES).toEqual([
        'lc-admin',
        'lc-department',
        'lc-position',
        'lc-seniority-level',
      ]);
      const union = new Set([
        ...DEPARTMENT_WRITE_ROLES,
        ...POSITION_WRITE_ROLES,
        ...SENIORITY_LEVEL_WRITE_ROLES,
      ]);
      expect([...HR_WRITE_ROLES].sort()).toEqual([...union].sort());
    });
  });

  describe('hasAnyClientRole', () => {
    it('should return true when the caller holds one of the listed roles', () => {
      withClientRoles([LC_SALES]);

      const result = TestBed.runInInjectionContext(() => hasAnyClientRole(VARIANT_ROLES));

      expect(result).toBe(true);
    });

    it('should return false when the caller holds none of the listed roles', () => {
      withClientRoles(['lc-company']);

      const result = TestBed.runInInjectionContext(() => hasAnyClientRole(VARIANT_ROLES));

      expect(result).toBe(false);
    });

    it('should return false for an empty role list', () => {
      withClientRoles([LC_ADMIN]);

      const result = TestBed.runInInjectionContext(() => hasAnyClientRole([]));

      expect(result).toBe(false);
    });

    it('should read client roles from resource_access, not realm roles', () => {
      keycloakMock.tokenParsed = {
        realm_access: { roles: [LC_ADMIN] },
        resource_access: { [CLIENT_ID]: { roles: [LC_SALES] } },
      } as unknown as Keycloak['tokenParsed'];

      expect(TestBed.runInInjectionContext(() => hasAnyClientRole([LC_SALES]))).toBe(true);
      expect(TestBed.runInInjectionContext(() => hasAnyClientRole([LC_ADMIN]))).toBe(false);
    });

    it('should return false without throwing when the token has no resource_access', () => {
      keycloakMock.tokenParsed = {} as unknown as Keycloak['tokenParsed'];

      const result = TestBed.runInInjectionContext(() => hasAnyClientRole(VARIANT_ROLES));

      expect(result).toBe(false);
    });
  });
});
