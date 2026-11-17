import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { Customer } from '../../models/customer.models';

/**
 * One customer, with the action that opens it for editing.
 *
 * The sibling of `features/companies/companies/components/companies-card`, and — since
 * `odd/tasks/customers-fiscal-form.md` — with the same shape: it stays presentational, so it emits the id of the
 * row it is about and never injects `Router`. The list is what navigates, which is also where the navigation is
 * asserted.
 */
@Component({
  selector: 'app-customer-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './customer-card.html',
  styleUrl: './customer-card.scss',
})
export class CustomerCard {
  readonly customer = input.required<Customer>();
  editCustomer = output<string>();

  readonly statusLabel = computed(() => (this.customer().enabled ? 'Activo' : 'Inactivo'));

  onEditCustomer(event: Event): void {
    event.stopPropagation();
    this.editCustomer.emit(this.customer().id);
  }
}
