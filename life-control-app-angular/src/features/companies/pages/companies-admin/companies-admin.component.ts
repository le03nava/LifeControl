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
  templateUrl: './companies-admin.component.html',
  styleUrl: './companies-admin.component.scss',
})
export class CompaniesAdminComponent {
  readonly cards: DashboardCard[] = [
    {
      title: 'Companies',
      icon: 'business',
      description: 'View, create, edit and manage your registered companies.',
      route: '/companies/list',
      disabled: false,
    },
    {
      title: 'Countries',
      icon: 'public',
      description: 'Configure supported countries for company registration.',
      route: null,
      disabled: true,
    },
    {
      title: 'Regions',
      icon: 'location_on',
      description: 'Manage geographic regions and their assignments.',
      route: null,
      disabled: true,
    },
    {
      title: 'Zones',
      icon: 'map',
      description: 'Define operational zones for your company structure.',
      route: null,
      disabled: true,
    },
    {
      title: 'Branches',
      icon: 'account_balance',
      description: 'Configure company branches and their relationships.',
      route: null,
      disabled: true,
    },
  ];
}
