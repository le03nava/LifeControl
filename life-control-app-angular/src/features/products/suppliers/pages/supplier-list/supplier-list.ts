import { Component, DestroyRef, effect, inject, signal, computed } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { SupplierService } from '../../data/supplier.service';
import { SuppliersCard } from '../../components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialog } from '@angular/material/dialog';
import { DeleteSupplierDialogComponent } from '../../ui/delete-supplier-dialog/delete-supplier-dialog';

@Component({
  selector: 'app-supplier-list',
  imports: [RouterLink, PageHeader, SuppliersCard, MatIconModule, MatPaginatorModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './supplier-list.html',
  styleUrl: './supplier-list.scss',
})
export class SupplierList {
  supplierService = inject(SupplierService);
  private router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  // Pagination
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive: mobile detection via matchMedia
  readonly isMobile = signal(false);
  readonly pageSizeOptions = computed(() => this.isMobile() ? [6, 12] : [6, 12, 24, 48]);

  // Search: raw input updated on each keystroke (for template binding)
  readonly searchQuery = signal('');

  // Search debounced: used for the API call (300ms after user stops typing)
  private readonly _debouncedSearch = signal('');

  // rxResource: calls the backend automatically when params change
  readonly suppliersResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.supplierService.getSuppliers(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly suppliers = this.suppliersResource.value;
  readonly loading = this.suppliersResource.isLoading;
  readonly error = this.suppliersResource.error;

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

    // MatchMedia for mobile paginator adaptation
    if (typeof window !== 'undefined') {
      const mql = window.matchMedia('(max-width: 575.98px)');
      this.isMobile.set(mql.matches);
      mql.addEventListener('change', (e) => this.isMobile.set(e.matches));
    }
  }

  editSupplier(id: string): void {
    this.router.navigate([`/products/suppliers/edit/${id}`]);
  }

  confirmDelete(supplierInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteSupplierDialogComponent, {
      data: { supplierName: supplierInfo.name },
    });
    dialogRef.afterClosed().subscribe((result: boolean) => {
      if (result) {
        this.supplierService.deleteSupplier(supplierInfo.id).pipe(
          takeUntilDestroyed(this.destroyRef),
        ).subscribe({
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
