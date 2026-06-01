import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

interface DashboardCard {
  title: string;
  icon: string;
  description: string;
  route: string | null;
  disabled: boolean;
}

@Component({
  standalone: true,
  imports: [RouterLink, MatCardModule, MatIconModule],
  templateUrl: './products-admin.component.html',
  styleUrl: './products-admin.component.scss',
})
export class ProductsAdminComponent {
  readonly cards: DashboardCard[] = [
    {
      title: 'All Products',
      icon: 'inventory_2',
      description: 'View, search and manage your product catalog.',
      route: '/products/list',
      disabled: false,
    },
    {
      title: 'New Product',
      icon: 'add',
      description: 'Add a new product to your catalog.',
      route: '/products/create',
      disabled: false,
    },
    {
      title: 'Categories',
      icon: 'category',
      description: 'Organize products by categories.',
      route: null,
      disabled: true,
    },
    {
      title: 'Product Types',
      icon: 'label',
      description: 'Configure product type classifications.',
      route: null,
      disabled: true,
    },
  ];
}
