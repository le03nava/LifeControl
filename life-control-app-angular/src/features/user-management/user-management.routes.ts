import { Routes } from '@angular/router';

export const userRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./user-list').then((m) => m.UserList),
  },
  {
    path: 'create',
    loadComponent: () => import('./user-form').then((m) => m.UserForm),
  },
  {
    path: ':id',
    loadComponent: () => import('./user-detail').then((m) => m.UserDetail),
  },
  {
    path: ':id/edit',
    loadComponent: () => import('./user-form').then((m) => m.UserForm),
  },
];
