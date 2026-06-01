import { Component, DestroyRef, effect, inject, signal, computed } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { ProductService } from '../../data/product.service';
import { ProductsCard } from '../../components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialog } from '@angular/material/dialog';
import { DeleteProductDialogComponent } from '../../ui/delete-product-dialog/delete-product-dialog';

@Component({
  selector: 'app-product-list',
  imports: [RouterLink, PageHeader, ProductsCard, MatIconModule, MatPaginatorModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './product-list.html',
  styleUrl: './product-list.scss',
})
export class ProductList {
  productService = inject(ProductService);
  private router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  // Paginación
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive: mobile detection via matchMedia
  readonly isMobile = signal(false);
  readonly pageSizeOptions = computed(() => this.isMobile() ? [6, 12] : [6, 12, 24, 48]);

  // Search: el input actualiza searchQuery en cada keystroke (para el template)
  readonly searchQuery = signal('');

  // Search debounced: se usa para la llamada API (300ms después de que el user deja de tipear)
  private readonly _debouncedSearch = signal('');

  // rxResource: llama al backend automáticamente cuando cambian pageIndex, pageSize o _debouncedSearch
  readonly productsResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.productService.getProducts(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly products = this.productsResource.value;
  readonly loading = this.productsResource.isLoading;
  readonly error = this.productsResource.error;

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

    // MatchMedia for mobile paginator adaptation
    if (typeof window !== 'undefined') {
      const mql = window.matchMedia('(max-width: 575.98px)');
      this.isMobile.set(mql.matches);
      mql.addEventListener('change', (e) => this.isMobile.set(e.matches));
    }
  }

  editProduct(id: string): void {
    this.router.navigate([`/products/edit/${id}`]);
  }

  confirmDelete(productInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteProductDialogComponent, {
      data: { productName: productInfo.name },
    });
    dialogRef.afterClosed().subscribe((result: boolean) => {
      if (result) {
        this.productService.deleteProduct(productInfo.id).pipe(
          takeUntilDestroyed(this.destroyRef),
        ).subscribe({
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
