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
  templateUrl: './sales-admin.component.html',
  styleUrl: './sales-admin.component.scss',
})
export default class SalesAdminComponent {
  readonly cards: DashboardCard[] = [
    {
      title: 'Ventas',
      icon: 'point_of_sale',
      description: 'Gestionar órdenes de venta',
      route: '/sales/orders',
      disabled: false,
    },
    {
      title: 'Reportes',
      icon: 'bar_chart',
      description: 'Ver reportes de ventas',
      route: null,
      disabled: true,
    },
    {
      title: 'Dashboard',
      icon: 'dashboard',
      description: 'Dashboard de ventas',
      route: null,
      disabled: true,
    },
  ];
}
