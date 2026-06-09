import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
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

@Component({
  standalone: true,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './purchases-admin.component.html',
  styleUrl: './purchases-admin.component.scss',
})
export class PurchasesAdminComponent {
  readonly cards: DashboardCard[] = [
    {
      title: 'Purchase Orders',
      icon: 'shopping_cart',
      description: 'Create, view and manage purchase orders with status tracking.',
      route: '/purchases/orders',
      disabled: false,
    },
    {
      title: 'Receipts',
      icon: 'inventory_2',
      description: 'Manage procurement receipts and inventory reception.',
      route: null,
      disabled: true,
    },
  ];
}
