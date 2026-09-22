import { TestBed } from '@angular/core/testing';
import Keycloak from 'keycloak-js';
import {
  CLIENT_ID,
  CLIENT_ROLES,
  hasAnyClientRole,
  LC_ADMIN,
  LC_SALES,
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
