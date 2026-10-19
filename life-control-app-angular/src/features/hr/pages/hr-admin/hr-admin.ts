import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PageHeader } from '@shared/ui';

/** One catalog the `/hr` landing page offers, and where it leads. */
interface HrCatalogCard {
  title: string;
  icon: string;
  description: string;
  route: string;
}

/**
 * The three catalogs this feature has shipped, in the order the header submenu used to
 * list them: Departamentos and Puestos with their own screens, and Empleados with
 * `employee-registry`. The global seniority-level catalog is deliberately absent: it is
 * a secondary screen reached from Puestos (`hr-org-structure` D5), not a fourth area.
 *
 * There is no per-card role filter: all three targets are reads any authenticated
 * caller may open, and each screen gates its own write controls on the narrower HR
 * write set, so the page shows the same three destinations the submenu showed to every
 * user that could see it.
 */
const CATALOG_CARDS: HrCatalogCard[] = [
  {
    title: 'Departamentos',
    icon: 'account_tree',
    description: 'Catálogo de departamentos de la empresa.',
    route: '/hr/departments',
  },
  {
    title: 'Puestos',
    icon: 'badge',
    description: 'Puestos de la empresa, sus bandas salariales y su plantilla de roles.',
    route: '/hr/positions',
  },
  {
    title: 'Empleados',
    icon: 'groups',
    description: 'Legajo de empleados, contratos y tiendas asignadas.',
    route: '/hr/employees',
  },
];

/**
 * Landing page of `/hr`: the destination of the `Recursos Humanos` header entry, which
 * navigates here exactly like `Compras` navigates to `/purchases`. It renders the
 * catalogs as cards instead of as a submenu, so the entry needs no caret and no panel.
 *
 * The route that loads this component carries the HR write union, so the page is
 * reachable exactly where the entry is shown.
 */
@Component({
  selector: 'app-hr-admin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './hr-admin.html',
  styleUrl: './hr-admin.scss',
})
export class HrAdmin {
  readonly cards: HrCatalogCard[] = CATALOG_CARDS;
}
