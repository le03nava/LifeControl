import { Component, computed, effect, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';

interface DashboardCard {
  title: string;
  icon: string;
  description: string;
  route: string | null;
  disabled: boolean;
  requiredRoles: string[];
}

/**
 * Static card definitions with role requirements.
 * Enabled/disabled state is derived from user roles.
 */
const STATIC_CARDS: Omit<DashboardCard, 'disabled'>[] = [
  {
    title: 'Companies',
    icon: 'business',
    description: 'View, create, edit and manage your registered companies.',
    route: '/companies/list',
    requiredRoles: ['lc-admin', 'lc-company'],
  },
  {
    title: 'Countries',
    icon: 'public',
    description: 'Configure supported countries for company registration.',
    route: '/companies/countries',
    requiredRoles: ['lc-admin', 'lc-company', 'lc-company-country'],
  },
  {
    title: 'Regions',
    icon: 'location_on',
    description: 'Manage geographic regions and their assignments.',
    route: '/companies/regions',
    requiredRoles: ['lc-admin', 'lc-company', 'lc-company-country', 'lc-company-region'],
  },
  {
    title: 'Zones',
    icon: 'map',
    description: 'Define operational zones for your company structure.',
    route: '/companies/zones',
    requiredRoles: [
      'lc-admin', 'lc-company', 'lc-company-country',
      'lc-company-region', 'lc-company-zone',
    ],
  },
  {
    title: 'Stores',
    icon: 'store',
    description: 'Manage operational store locations and their details.',
    route: '/companies/stores',
    requiredRoles: [
      'lc-admin', 'lc-company', 'lc-company-country',
      'lc-company-region', 'lc-company-zone', 'lc-company-store',
    ],
  },
];

@Component({
  standalone: true,
  imports: [RouterLink, MatCardModule, MatIconModule],
  templateUrl: './companies-admin.component.html',
  styleUrl: './companies-admin.component.scss',
})
export class CompaniesAdminComponent {
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);

  /**
   * Current client roles from the Keycloak token.
   * Reactive: updates when KEYCLOAK_EVENT_SIGNAL emits a Ready event.
   */
  private readonly userRoles = computed(() => {
    const event = this.keycloakSignal();
    if (event?.type !== KeycloakEventType.Ready) return [] as string[];
    const token = this.keycloak.tokenParsed;
    return (token?.resource_access?.['life-control-client']?.roles as string[]) ?? ([] as string[]);
  });

  /**
   * Dashboard cards with enabled/disabled state derived from user roles.
   */
  readonly cards = computed<DashboardCard[]>(() => {
    const roles = this.userRoles();

    return STATIC_CARDS
      .map((card) => ({
        ...card,
        disabled: card.requiredRoles.length > 0 && !card.requiredRoles.some((r) => roles.includes(r)),
      }))
      .filter((card) => !card.disabled);
  });

  constructor() {
    // Trigger reactivity when token refreshes
    effect(() => {
      const _ = this.keycloakSignal();
      // Re-evaluates userRoles → cards on every Keycloak event
    });
  }
}
