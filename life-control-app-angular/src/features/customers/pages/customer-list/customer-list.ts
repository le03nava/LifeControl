import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { observeMobileViewport } from '@shared/responsive/mobile-viewport';
import { CustomerCard } from '../../components';
import { CustomerService } from '../../data/customer.service';
import { Customer, Page } from '../../models/customer.models';

/**
 * The customer list, read-only over `GET /api/customers`.
 *
 * Modelled on `companies/pages/company-list/company-list.ts`, which is the closer sibling of the two
 * list precedents (`L3(b)` of `odd/tasks/customers-screen.md` chose cards over a table, once): the same
 * `rxResource` over page/size/search, the same 300ms debounce, the same paginator gated on
 * `totalPages > 1`. What it does **not** carry is the other half of that page: no delete dialog. The edit
 * navigation and the header's create action are here because the screen they lead to exists
 * (`odd/tasks/customers-fiscal-form.md`): a control that navigates nowhere is not built.
 */
@Component({
  selector: 'app-customer-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    PageHeader,
    CustomerCard,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
  ],
  templateUrl: './customer-list.html',
  styleUrl: './customer-list.scss',
})
export class CustomerList {
  private readonly customerService = inject(CustomerService);
  private readonly router = inject(Router);

  // Paginación
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive: mobile detection via matchMedia
  readonly isMobile = observeMobileViewport();
  readonly pageSizeOptions = computed(() => (this.isMobile() ? [6, 12] : [6, 12, 24, 48]));

  // Search: el input actualiza searchQuery en cada keystroke (para el template)
  readonly searchQuery = signal('');

  // Search debounced: se usa para la llamada API (300ms después de que el user deja de tipear)
  private readonly _debouncedSearch = signal('');

  // rxResource: llama al backend automáticamente cuando cambian pageIndex, pageSize o _debouncedSearch
  readonly customersResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.customerService.getCustomers(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly customers = computed<Page<Customer> | undefined>(() =>
    this.customersResource.hasValue() ? this.customersResource.value() : undefined,
  );
  readonly loading = this.customersResource.isLoading;
  readonly error = this.customersResource.error;

  protected readonly httpErrorMessage = httpErrorMessage;

  constructor() {
    // Debounce effect: cuando searchQuery cambia, espera 300ms y actualiza _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset a página 0 cuando cambia la búsqueda debounced.
    //
    // La lectura de `pageIndex` va por `untracked` a propósito: leerla de forma reactiva haría que el propio
    // `set(0)` vuelva a disparar este effect, y entonces **cada** cambio de página se desharía en el siguiente
    // change detection. Es lo que hace `company-list.ts:91-97`, donde el effect sólo sobrevive porque su spec
    // nunca vuelve a correr change detection después de paginar; acá el paginador tiene que funcionar (`S2`), así
    // que la dependencia declarada es la única que el comentario original decía: el término debounced.
    effect(() => {
      this._debouncedSearch();
      untracked(() => {
        if (this.pageIndex() !== 0) {
          this.pageIndex.set(0);
        }
      });
    });
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  /**
   * Opens the row the card names.
   *
   * `/customers/edit/:id` is a child of the same gated parent the list hangs from, so the operator's roles
   * decide both screens with one declaration.
   */
  onEditCustomer(id: string): void {
    this.router.navigate(['/customers/edit', id]);
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }
}
