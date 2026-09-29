import { TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { SCHEDULING_READ_ROLES, SCHEDULING_WRITE_ROLES } from '@core/security/roles';
import { schedulingRoutes } from './scheduling.routes';

/** The single `/scheduling` parent route. */
const parent = schedulingRoutes[0];

function children(): Route[] {
  return parent.children ?? [];
}

function child(path: string): Route | undefined {
  return children().find((route) => route.path === path);
}

describe('schedulingRoutes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  it('should pin the role aggregates to the backend @PreAuthorize sets', () => {
    // Literal wire strings, not the imported constants: this is the assertion
    // that pins the role names the backend actually matches.
    expect(SCHEDULING_READ_ROLES).toEqual(['lc-admin', 'lc-scheduling', 'lc-scheduling-read']);
    expect(SCHEDULING_WRITE_ROLES).toEqual(['lc-admin', 'lc-scheduling']);
  });

  it('should gate the parent with the read roles union', () => {
    expect(parent.canActivate).toEqual([keycloakRoleGuard]);
    expect(parent.data).toEqual({
      roles: ['lc-admin', 'lc-scheduling', 'lc-scheduling-read'],
      clientId: 'life-control-client',
    });
  });

  it('should declare list, create, edit/:id and the availability editor children', () => {
    expect(children().map((route) => route.path)).toEqual([
      'list',
      'create',
      'edit/:id',
      'activities/:id/availability',
    ]);
  });

  it('should gate the list with the read roles and no discard guard', () => {
    const list = child('list');

    expect(list).toBeDefined();
    expect(list?.canActivate).toEqual([keycloakRoleGuard]);
    expect(list?.data).toEqual({
      roles: ['lc-admin', 'lc-scheduling', 'lc-scheduling-read'],
      clientId: 'life-control-client',
    });
    expect(list?.canDeactivate).toBeUndefined();
  });

  it('should gate create and edit with the write roles and the discard guard', () => {
    for (const path of ['create', 'edit/:id']) {
      const route = child(path);

      expect(route).toBeDefined();
      expect(route?.canActivate).toEqual([keycloakRoleGuard]);
      expect(route?.data).toEqual({
        roles: ['lc-admin', 'lc-scheduling'],
        clientId: 'life-control-client',
      });
      expect(route?.canDeactivate).toEqual([unsavedChangesGuard]);
    }
  });

  it('should gate the availability editor with the write roles and the discard guard (D42)', () => {
    const route = child('activities/:id/availability');

    expect(route).toBeDefined();
    expect(route?.canActivate).toEqual([keycloakRoleGuard]);
    expect(route?.data).toEqual({
      roles: ['lc-admin', 'lc-scheduling'],
      clientId: 'life-control-client',
    });
    expect(route?.canDeactivate).toEqual([unsavedChangesGuard]);
  });
});
