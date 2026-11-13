import { Routes } from '@angular/router';

/**
 * The `Configuración` subtree: one landing, and one card per catalog it indexes (`S1`).
 *
 * **No guard is declared here on purpose.** `app.routes.ts` gates the whole subtree on
 * `TAX_RATE_ROLES`, and a guard re-declared on a child can only ever *disagree* with the parent — the
 * shape `reports.routes.ts` chose for the same reason. Adding the next catalog is a card and one child
 * route, never a second gate to keep in sync.
 */
export const configurationRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/configuration-landing/configuration-landing.component').then(
        (m) => m.ConfigurationLanding,
      ),
    title: 'Configuración',
  },
  {
    path: 'taxes',
    loadComponent: () =>
      import('@features/tax/pages/tax-rate-list/tax-rate-list').then((m) => m.TaxRateList),
    title: 'Impuestos',
  },
];
