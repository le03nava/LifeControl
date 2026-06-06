import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import Keycloak from 'keycloak-js';
import { keycloakRoleGuard } from './auth-keycloak-guard';

describe('keycloakRoleGuard', () => {
  let keycloakMock: Partial<Keycloak>;
  let routerMock: Partial<Router>;

  beforeEach(() => {
    keycloakMock = {
      authenticated: true,
      tokenParsed: undefined,
      login: vi.fn().mockResolvedValue(undefined),
    };

    routerMock = {
      navigate: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: Keycloak, useValue: keycloakMock },
        { provide: Router, useValue: routerMock },
      ],
    });
  });

  function createRouteSnapshot(data: Record<string, unknown>): ActivatedRouteSnapshot {
    return { data } as unknown as ActivatedRouteSnapshot;
  }

  function createStateSnapshot(url: string): RouterStateSnapshot {
    return { url } as unknown as RouterStateSnapshot;
  }

  // ─── Authenticated User with admin role ────────────────────

  it('should allow user with admin role when data.role is "admin"', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      realm_access: { roles: ['admin'] },
    };
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  it('should allow user with admin role when data.roles is ["admin", "manager"]', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      realm_access: { roles: ['admin', 'manager', 'user'] },
    };
    const route = createRouteSnapshot({ roles: ['admin', 'manager'] });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  // ─── User without required role ────────────────────────────

  it('should deny user without admin role', async () => {
    keycloakMock.authenticated = true;
    keycloakMock.tokenParsed = {
      realm_access: { roles: ['user'] },
    } as unknown as Keycloak['tokenParsed'];
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/unauthorized']);
  });

  // ─── Missing realm_access claim ────────────────────────────

  it('should deny access when realm_access claim is missing from token', async () => {
    keycloakMock.authenticated = true;
    keycloakMock.tokenParsed = {} as unknown as Keycloak['tokenParsed'];
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/unauthorized']);
  });

  // ─── No required roles specified ───────────────────────────

  it('should allow access when no required roles are specified in route data', async () => {
    keycloakMock.authenticated = true;
    keycloakMock.tokenParsed = {
      realm_access: { roles: ['user'] },
    } as unknown as Keycloak['tokenParsed'];
    const route = createRouteSnapshot({});
    const state = createStateSnapshot('/any-route');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  // ─── Unauthenticated user ──────────────────────────────────

  it('should redirect to login when user is not authenticated', async () => {
    keycloakMock.authenticated = false;
    (keycloakMock as any).tokenParsed = null;
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(keycloakMock.login).toHaveBeenCalled();
  });

  // ─── Client role resolution (with clientId) ─────────────────

  it('should allow user with client role when clientId is present in route data', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      resource_access: {
        'life-control-client': { roles: ['lc-admin', 'lc-company'] },
      },
    };
    const route = createRouteSnapshot({
      roles: ['lc-admin', 'lc-company'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  it('should deny user without required client role when clientId is present', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      resource_access: {
        'life-control-client': { roles: ['some-other-role'] },
      },
    };
    const route = createRouteSnapshot({
      roles: ['lc-admin', 'lc-company'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/unauthorized']);
  });

  // ─── Missing resource_access claim with clientId ─────────────

  it('should deny access safely when resource_access claim is missing but clientId present', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {}; // no resource_access
    const route = createRouteSnapshot({
      roles: ['lc-admin'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/unauthorized']);
  });

  // ─── lc-company-country client role access ─────────────────────

  it('should allow user with lc-company-country client role when clientId is present', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      resource_access: {
        'life-control-client': { roles: ['lc-company-country'] },
      },
    };
    const route = createRouteSnapshot({
      roles: ['lc-admin', 'lc-company', 'lc-company-country'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  it('should allow user with lc-company-country + lc-company roles (union)', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      resource_access: {
        'life-control-client': { roles: ['lc-company-country', 'lc-company'] },
      },
    };
    const route = createRouteSnapshot({
      roles: ['lc-admin', 'lc-company', 'lc-company-country'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(true);
  });

  // ─── Old realm role denied for Companies (no backward compat) ─

  it('should deny access when user has old realm roles but no client roles for Companies route', async () => {
    keycloakMock.authenticated = true;
    (keycloakMock as any).tokenParsed = {
      realm_access: { roles: ['life-control-admin', 'life-control-country'] },
      resource_access: {
        'life-control-client': { roles: [] }, // no lc-admin or lc-company
      },
    };
    const route = createRouteSnapshot({
      roles: ['lc-admin', 'lc-company'],
      clientId: 'life-control-client',
    });
    const state = createStateSnapshot('/companies');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBe(false);
    expect(routerMock.navigate).toHaveBeenCalledWith(['/unauthorized']);
  });
});
