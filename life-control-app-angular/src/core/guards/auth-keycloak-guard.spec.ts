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
});
