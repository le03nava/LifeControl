import { Component, signal, computed, effect, inject, DestroyRef } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal, PageHeader } from '@shared/ui';
import { ProductService } from '@features/products/data/product.service';
import { ProductsCard } from '@features/products/components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { rxResource } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-product-list',
  imports: [
    RouterLink, Button, Modal, PageHeader, ProductsCard,
    MatIconModule, MatPaginatorModule,
  ],
  templateUrl: './product-list.html',
  styleUrl: './product-list.scss',
})
export class ProductList {
  productService = inject(ProductService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  // Paginación
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive page size options via matchMedia
  private mql = window.matchMedia('(max-width: 575.98px)');
  isMobile = signal(this.mql.matches);
  readonly pageSizeOptions = computed(() =>
    this.isMobile() ? [6, 12] : [12, 24, 48],
  );

  // Search: input actualiza searchQuery en cada keystroke
  readonly searchQuery = signal('');

  // Search debounced: usado para la llamada API (300ms después de que el user deja de tipear)
  private readonly _debouncedSearch = signal('');

  constructor() {
    const listener = (e: MediaQueryListEvent) => this.isMobile.set(e.matches);
    this.mql.addEventListener('change', listener);
    this.destroyRef.onDestroy(() => this.mql.removeEventListener('change', listener));

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

  readonly productsResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.productService.getProductsPaged(params.page, params.size, params.search || undefined),
  });

  // Delete modal state
  showDeleteModal = signal(false);
  productToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  editProduct(id: string): void {
    this.router.navigate(['/products/edit', id]);
  }

  confirmDelete(productInfo: { id: string; name: string }): void {
    this.productToDelete.set({ id: productInfo.id, name: productInfo.name });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.productToDelete.set(null);
  }

  executeDelete(): void {
    const product = this.productToDelete();
    if (!product || this.isDeleting()) return;

    this.isDeleting.set(true);
    this.productService.deleteProduct(product.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.showDeleteModal.set(false);
        this.productToDelete.set(null);
        this.productsResource.reload();
      },
      error: () => {
        this.isDeleting.set(false);
      },
    });
  }

  trackById(_index: number, item: { id: string }): string {
    return item.id;
  }
}
