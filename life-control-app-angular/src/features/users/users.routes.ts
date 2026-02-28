import { Routes } from '@angular/router';

export const userRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/user-list/user-list').then((m) => m.UserList),
  },

  {
    path: 'edit/:id',
    loadComponent: () => import('./pages/user-edit/user-edit').then((m) => m.UserEdit),
  },
  {
    path: 'create',
    loadComponent: () => import('./pages/user-edit/user-edit').then((m) => m.UserEdit),
  },
];
