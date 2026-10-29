import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
  computed,
} from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { observeMobileViewport } from '@shared/responsive/mobile-viewport';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { map } from 'rxjs';
import { SupplierService } from '../../data/supplier.service';
import { productsCompanyId } from '../../../data/products-company';
import { SuppliersCard } from '../../components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { DeleteSupplierDialogComponent } from '../../ui/delete-supplier-dialog/delete-supplier-dialog';

@Component({
  selector: 'app-supplier-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    SuppliersCard,
    MatIconModule,
    MatPaginatorModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './supplier-list.html',
  styleUrl: './supplier-list.scss',
})
export class SupplierList {
  supplierService = inject(SupplierService);
  private readonly companyService = inject(CompanyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The companies the `Empresa` filter can name.
   *
   * The claim-authorized selector, read exactly as `ProductList` and `ProductCategoryList` read it: any
   * authenticated caller may paint a company choice, so the filter offers precisely the companies whose
   * catalog it can then read.
   */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /**
   * The company whose catalog is listed, seeded from `?companyId=` and kept there.
   *
   * A supplier belongs to one company (issue #672), and `GET /api/companies/{companyId}/suppliers` is
   * the only endpoint, so this is a filter and not a view toggle. `null` is therefore a real state and
   * not an error: the screen asks for a company instead of rendering an empty grid, which would claim
   * that a company nobody named has no suppliers.
   *
   * The company source is the products area's own ({@link productsCompanyId}): the query parameter the
   * products list got in #669, chosen inside the screen and written back with `replaceUrl`.
   */
  readonly selectedCompanyId = signal<string | null>(productsCompanyId(this.route));

  // Pagination
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive: mobile detection via matchMedia
  readonly isMobile = observeMobileViewport();
  readonly pageSizeOptions = computed(() => (this.isMobile() ? [6, 12] : [6, 12, 24, 48]));

  // Search: raw input updated on each keystroke (for template binding)
  readonly searchQuery = signal('');

  // Search debounced: used for the API call (300ms after user stops typing)
  private readonly _debouncedSearch = signal('');

  // rxResource: calls the backend automatically when params change.
  // `undefined` params es el estado "sin empresa elegida" y no emite ninguna request.
  readonly suppliersResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      return companyId
        ? {
            companyId,
            page: this.pageIndex(),
            size: this.pageSize(),
            search: this._debouncedSearch(),
          }
        : undefined;
    },
    stream: ({ params }) =>
      this.supplierService.getSuppliers(
        params.companyId,
        params.page,
        params.size,
        params.search || undefined,
      ),
  });

  // Computed helpers
  readonly suppliers = computed(() =>
    this.suppliersResource.hasValue() ? this.suppliersResource.value() : undefined,
  );
  readonly loading = this.suppliersResource.isLoading;
  readonly error = this.suppliersResource.error;

  protected readonly httpErrorMessage = httpErrorMessage;

  constructor() {
    // Debounce effect: when searchQuery changes, wait 300ms and update _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset to page 0 when the debounced search changes
    effect(() => {
      this._debouncedSearch();
      if (this.pageIndex() !== 0) {
        this.pageIndex.set(0);
      }
    });
  }

  /**
   * Keeps the choice in the URL and lets the request follow it.
   *
   * `replaceUrl` because choosing a company is not a step in a flow: it is the same page with a
   * different filter, and a history entry per pick would make the back button walk the operator through
   * every company they looked at. `merge` so the other params on the screen survive.
   *
   * The page and the search are cleared because both belonged to the previous company: keeping them
   * would page into, and filter by, a catalog the operator just left.
   */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    this.pageIndex.set(0);
    this.searchQuery.set('');
    this._debouncedSearch.set('');
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /**
   * Opens the create form under the selected company, which is the same company it will be saved under.
   *
   * The form has no `Empresa` field of its own (S6: the supplier form does not change), so the link is
   * the only way the company reaches it — and with none selected there is nothing to link to.
   */
  createSupplier(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    void this.router.navigate(['/products/suppliers/create'], { queryParams: { companyId } });
  }

  editSupplier(id: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    void this.router.navigate(['/products/suppliers/edit', id], { queryParams: { companyId } });
  }

  confirmDelete(supplierInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteSupplierDialogComponent, {
      data: { supplierName: supplierInfo.name },
    });
    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result: boolean) => {
        const companyId = this.selectedCompanyId();
        if (result && companyId) {
          this.supplierService
            .deleteSupplier(companyId, supplierInfo.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.suppliersResource.reload(),
            });
        }
      });
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }
}
