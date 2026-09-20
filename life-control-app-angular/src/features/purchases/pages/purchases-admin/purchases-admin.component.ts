import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { hasAnyClientRole, LC_ADMIN, LC_RECEIVING } from '@core/security/roles';
import { PageHeader } from '@shared/ui';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

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
 * Enabled/disabled state is derived from user roles, read once at construction.
 */
const STATIC_CARDS: Omit<DashboardCard, 'disabled'>[] = [
  {
    title: 'Purchase Orders',
    icon: 'shopping_cart',
    description: 'Create, view and manage purchase orders with status tracking.',
    route: '/purchases/orders',
    requiredRoles: [LC_ADMIN],
  },
  {
    title: 'Receipts',
    icon: 'inventory_2',
    description: 'Manage procurement receipts and inventory reception.',
    route: '/purchases/receipts',
    requiredRoles: [LC_ADMIN, LC_RECEIVING],
  },
];

@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './purchases-admin.component.html',
  styleUrl: './purchases-admin.component.scss',
})
export class PurchasesAdminComponent {
  /**
   * Dashboard cards visible to the current user. Cards without access are
   * hidden, not disabled; `hasAnyClientRole` runs once per card in this field
   * initializer, inside an Angular injection context.
   */
  readonly cards: DashboardCard[] = STATIC_CARDS.map((card) => ({
    ...card,
    disabled: !hasAnyClientRole(card.requiredRoles),
  })).filter((card) => !card.disabled);
}
