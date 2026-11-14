import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { map } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import type { CustomerOption, Page } from '../../models/sales-order.models';

/**
 * Autocomplete component for searching and selecting customers.
 *
 * Fetches customers from `GET /api/customers?search=` when the user types
 * at least 2 characters. Emits the selected customer via `customerSelected`.
 */
@Component({
  selector: 'app-customer-selector',
  standalone: true,
  imports: [MatFormFieldModule, MatInputModule, MatAutocompleteModule],
  templateUrl: './customer-selector.html',
  styleUrl: './customer-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerSelector {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The customer to show as already chosen: the receiver the loaded order names.
   *
   * <p><b>Null means a counter sale, and nothing is substituted for it.</b> This input used to be seeded with a
   * fixed {@code 'PUBLICO EN GENERAL'} customer the page fetched by id before the order existed; `V105` deleted
   * that row and the page no longer invents a receiver (`S2`/`S6` of `odd/tasks/customer-tenant-isolation.md`).</p>
   */
  readonly initialCustomer = input<CustomerOption | null>(null);

  /** Emits the selected customer when an option is chosen. */
  readonly customerSelected = output<CustomerOption>();

  /** Current search query typed by the user. */
  readonly searchQuery = signal('');

  /** Customers fetched from the API matching the search query. */
  readonly filteredCustomers = computed(() => this._customers());

  constructor() {
    effect(() => {
      const customer = this.initialCustomer();
      if (customer) {
        this.searchQuery.set(customer.name);
        this._customers.set([customer]);
      }
    });
  }

  // ── Internal state ──────────────────────────────────────
  private readonly _customers = signal<CustomerOption[]>([]);

  /**
   * Called on every keystroke in the autocomplete input.
   * Triggers an API search when the query has at least 2 characters.
   */
  onSearchChange(value: string): void {
    this.searchQuery.set(value);

    if (!value || value.trim().length < 2) {
      this._customers.set([]);
      return;
    }

    const params = { search: value.trim(), page: '0', size: '20' };
    this.http
      .get<Page<CustomerOption>>(`${this.configService.apiUrl}/customers`, { params })
      .pipe(
        map((page) => page.content),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (customers) => this._customers.set(customers),
        error: () => this._customers.set([]),
      });
  }

  /** Called when the user selects a customer from the autocomplete dropdown. */
  onCustomerSelect(customer: CustomerOption): void {
    this.searchQuery.set(customer.name);
    this._customers.set([]);
    this.customerSelected.emit(customer);
  }
}
