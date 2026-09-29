import { Routes } from '@angular/router';
import { keycloakRoleGuard } from '@core/guards/auth-keycloak-guard';
import { unsavedChangesGuard } from '@core/guards/unsaved-changes.guard';
import { CLIENT_ID, SCHEDULING_READ_ROLES, SCHEDULING_WRITE_ROLES } from '@core/security/roles';

/**
 * Feature routes for `/scheduling`.
 *
 * The parent admits every scheduling read role so the list is reachable. The
 * write screens are narrower (`lc-scheduling-read` is absent), so each child
 * re-declares both the guard and its own `data`: `keycloakRoleGuard` only reads
 * `route.data` when it is itself listed in `canActivate`, and a `data` block
 * without its own guard is inert.
 */
export const schedulingRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: SCHEDULING_READ_ROLES, clientId: CLIENT_ID },
    children: [
      {
        path: 'list',
        canActivate: [keycloakRoleGuard],
        data: { roles: SCHEDULING_READ_ROLES, clientId: CLIENT_ID },
        loadComponent: () =>
          import('./pages/scheduling-activity-list/scheduling-activity-list').then(
            (m) => m.SchedulingActivityList,
          ),
      },
      {
        path: 'create',
        canActivate: [keycloakRoleGuard],
        data: { roles: SCHEDULING_WRITE_ROLES, clientId: CLIENT_ID },
        // A half-filled new activity must not be discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./pages/scheduling-activity-edit/scheduling-activity-edit').then(
            (m) => m.SchedulingActivityEdit,
          ),
      },
      {
        path: 'edit/:id',
        canActivate: [keycloakRoleGuard],
        data: { roles: SCHEDULING_WRITE_ROLES, clientId: CLIENT_ID },
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./pages/scheduling-activity-edit/scheduling-activity-edit').then(
            (m) => m.SchedulingActivityEdit,
          ),
      },
      {
        path: 'activities/:id/availability',
        canActivate: [keycloakRoleGuard],
        data: { roles: SCHEDULING_WRITE_ROLES, clientId: CLIENT_ID },
        // A half-edited window set must not be discarded by a stray navigation.
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./pages/scheduling-activity-availability/scheduling-activity-availability').then(
            (m) => m.SchedulingActivityAvailability,
          ),
      },
    ],
  },
];
