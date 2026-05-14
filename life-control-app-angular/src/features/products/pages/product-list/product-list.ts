import { Component, signal, computed, effect, inject, DestroyRef } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal, PageHeader } from '@shared/ui';
import { ProductService } from '@features/products/data/product.service';
import { ProductsCard } from '@features/products/components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-product-list',
  imports: [
    RouterLink, Button, Modal, PageHeader, ProductsCard,
    MatIconModule, MatPaginatorModule, FormsModule,
  ],
  templateUrl: './product-list.html',
  styleUrl: './product-list.scss',
})
export class ProductList {
  productService = inject(ProductService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  currentPage = signal(0);
  pageSize = signal(12);
  searchQuery = signal('');

  // Responsive page size options via matchMedia
  private mql = window.matchMedia('(max-width: 575.98px)');
  isMobile = signal(this.mql.matches);
  readonly pageSizeOptions = computed(() =>
    this.isMobile() ? [6, 12] : [12, 24, 48],
  );

  constructor() {
    const listener = (e: MediaQueryListEvent) => this.isMobile.set(e.matches);
    this.mql.addEventListener('change', listener);
    this.destroyRef.onDestroy(() => this.mql.removeEventListener('change', listener));
  }

  // Debounced search — 300ms after user stops typing
  debouncedSearch = signal('');

  private searchEffect = effect(() => {
    const value = this.searchQuery();
    const timeout = setTimeout(() => this.debouncedSearch.set(value), 300);
    this.destroyRef.onDestroy(() => clearTimeout(timeout));
  });

  readonly productsResource = rxResource({
    params: () => ({
      page: this.currentPage(),
      size: this.pageSize(),
      search: this.debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.productService.getProductsPaged(params.page, params.size, params.search || undefined),
  });

  // Delete modal state
  showDeleteModal = signal(false);
  productToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  onPageChange(event: PageEvent): void {
    this.currentPage.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  onSearchChange(value: string): void {
    this.searchQuery.set(value);
    this.currentPage.set(0); // Reset to first page on new search
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
