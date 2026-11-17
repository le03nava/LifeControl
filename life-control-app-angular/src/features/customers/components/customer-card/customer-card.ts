import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { Customer } from '../../models/customer.models';

/**
 * One customer, read-only.
 *
 * The sibling of `features/companies/companies/components/companies-card`, with its other half removed:
 * it has no outputs, no buttons and no `mat-card-actions`, because this unit adds no write path (`S5` of
 * `odd/tasks/customers-screen.md`). The edit page arrives with the fiscal form, and a control that
 * navigates nowhere is not built.
 */
@Component({
  selector: 'app-customer-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatIconModule],
  templateUrl: './customer-card.html',
  styleUrl: './customer-card.scss',
})
export class CustomerCard {
  readonly customer = input.required<Customer>();

  readonly statusLabel = computed(() => (this.customer().enabled ? 'Activo' : 'Inactivo'));
}
