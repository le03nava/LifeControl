import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

interface DashboardCard {
  title: string;
  icon: string;
  description: string;
  route: string | null;
  disabled: boolean;
}

/**
 * The products hub for **one company** (`/products/c/:companyId`).
 *
 * The company is a route segment and nothing else: the cards below are built from it, so there is no
 * card that could navigate into a company other than the one the URL named, and the two cards whose
 * destination is deliberately company-independent (`Categories`, which chooses its company inside the
 * page, and `Suppliers`, which is a global catalog) are the only ones without the segment.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './products-admin.component.html',
  styleUrl: './products-admin.component.scss',
})
export class ProductsAdminComponent {
  private readonly route = inject(ActivatedRoute);

  /**
   * The company this hub belongs to, read once.
   *
   * A snapshot read is correct here and not a shortcut: the route param is part of the path, so it
   * cannot change without the router destroying this component.
   */
  readonly companyId = this.route.snapshot.paramMap.get('companyId') ?? '';

  readonly cards = computed<DashboardCard[]>(() => {
    const scoped = (suffix: string): string => `/products/c/${this.companyId}${suffix}`;

    return [
      {
        title: 'All Products',
        icon: 'inventory_2',
        description: 'View, search and manage your product catalog.',
        route: scoped('/list'),
        disabled: false,
      },
      {
        title: 'New Product',
        icon: 'add',
        description: 'Add a new product to your catalog.',
        route: scoped('/create'),
        disabled: false,
      },
      {
        // Company-independent on purpose: the tree is company-scoped on the wire, but this browser
        // chooses its company inside the page, so its URL carries no segment (see `products.routes.ts`).
        title: 'Categories',
        icon: 'category',
        description: 'Organize products by categories.',
        route: '/products/categories',
        disabled: false,
      },
      {
        title: 'Product Types',
        icon: 'label',
        description: 'Configure product type classifications.',
        route: null,
        disabled: true,
      },
      {
        // Also company-independent: suppliers are a global catalog, and only a product's links to them
        // belong to a company (those live in the product workspace's `Proveedores` tab).
        title: 'Suppliers',
        icon: 'local_shipping',
        description: 'Manage procurement partners.',
        route: '/products/suppliers',
        disabled: false,
      },
    ];
  });
}
