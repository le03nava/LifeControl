import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import Keycloak from 'keycloak-js';
import { keycloakRoleGuard } from './auth-keycloak-guard';

describe('keycloakRoleGuard', () => {
  let keycloakMock: jasmine.SpyObj<Keycloak>;
  let routerMock: jasmine.SpyObj<Router>;

  beforeEach(() => {
    keycloakMock = jasmine.createSpyObj<Keycloak>('Keycloak', ['login'], {
      authenticated: true,
      tokenParsed: undefined,
    });
    routerMock = jasmine.createSpyObj<Router>('Router', ['navigate']);

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
    keycloakMock.tokenParsed = {
      realm_access: { roles: ['admin'] },
    } as unknown as Keycloak['tokenParsed'];
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBeTrue();
  });

  it('should allow user with admin role when data.roles is ["admin", "manager"]', async () => {
    keycloakMock.authenticated = true;
    keycloakMock.tokenParsed = {
      realm_access: { roles: ['user', 'manager'] },
    } as unknown as Keycloak['tokenParsed'];
    const route = createRouteSnapshot({ roles: ['admin', 'manager'] });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBeTrue();
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

    expect(result).toBeFalse();
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

    expect(result).toBeFalse();
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

    expect(result).toBeTrue();
  });

  // ─── Unauthenticated user ──────────────────────────────────

  it('should redirect to login when user is not authenticated', async () => {
    keycloakMock.authenticated = false;
    const route = createRouteSnapshot({ role: 'admin' });
    const state = createStateSnapshot('/users-admin');

    const result = await TestBed.runInInjectionContext(() =>
      keycloakRoleGuard(route, state),
    );

    expect(result).toBeFalse();
    expect(keycloakMock.login).toHaveBeenCalled();
  });
});
