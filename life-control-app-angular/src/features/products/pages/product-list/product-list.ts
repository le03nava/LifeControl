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
import { ProductService } from '../../data/product.service';
import { productsCompanyId } from '../../data/products-company';
import { ProductsCard } from '../../components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { DeleteProductDialogComponent } from '../../ui/delete-product-dialog/delete-product-dialog';

@Component({
  selector: 'app-product-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    ProductsCard,
    MatIconModule,
    MatPaginatorModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './product-list.html',
  styleUrl: './product-list.scss',
})
export class ProductList {
  productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The companies the `Empresa` filter can name.
   *
   * The claim-authorized selector, exactly as `ProductCategoryList` reads it: any authenticated caller
   * is allowed to paint a company choice, so the filter offers precisely the companies whose catalog it
   * can then read.
   */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /**
   * The company whose catalog is listed, seeded from `?companyId=` and kept there.
   *
   * The list has no cross-company read (S2): `GET /api/companies/{companyId}/products` is the only
   * endpoint, so this is a filter and not a view toggle. `null` is therefore a real state and not an
   * error — the screen asks for a company instead of rendering an empty grid, which would claim that a
   * company nobody named has no products.
   *
   * A signal and not the route's plain value, because the operator changes it here: the choice is
   * written back into the URL by {@link onCompanyChange}, so a copied link reproduces the list.
   */
  readonly selectedCompanyId = signal<string | null>(productsCompanyId(this.route));

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

  // rxResource: llama al backend automáticamente cuando cambian pageIndex, pageSize o _debouncedSearch.
  // `undefined` params es el estado "sin empresa elegida" y no emite ninguna request.
  readonly productsResource = rxResource({
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
      this.productService.getProducts(
        params.companyId,
        params.page,
        params.size,
        params.search || undefined,
      ),
  });

  // Computed helpers
  readonly products = computed(() =>
    this.productsResource.hasValue() ? this.productsResource.value() : undefined,
  );
  readonly loading = this.productsResource.isLoading;
  readonly error = this.productsResource.error;

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

    // Reset a página 0 cuando cambia la búsqueda debounced
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
   * Opens the create form for the chosen company, which is the same company it will be saved under.
   *
   * The form asks for it too (its `Empresa` field, preselected from here), so this is a convenience and
   * not the only source: a product created from a bare `/products/create` is still possible.
   */
  createProduct(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    void this.router.navigate(['/products/create'], { queryParams: { companyId } });
  }

  editProduct(id: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    void this.router.navigate(['/products/edit', id], { queryParams: { companyId } });
  }

  /**
   * Opens the product's variant definitions list.
   *
   * Additive row action for the page's existing audience: it does not change the
   * page's route gate, its visibility rules or any other action.
   */
  manageVariants(id: string): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return;
    }
    void this.router.navigate(['/products/edit', id, 'variants'], { queryParams: { companyId } });
  }

  confirmDelete(productInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteProductDialogComponent, {
      data: { productName: productInfo.name },
    });
    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result: boolean) => {
        const companyId = this.selectedCompanyId();
        if (result && companyId) {
          this.productService
            .deleteProduct(companyId, productInfo.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.productsResource.reload(),
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
