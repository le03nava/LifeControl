import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { PageHeader } from '@shared/ui';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { PO_STATUS_COLORS } from '../../data/status-config';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-purchase-order-list',
  standalone: true,
  imports: [
    PageHeader,
    DatePipe,
    MatTableModule,
    MatIconModule,
    MatPaginatorModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatChipsModule,
    MatCardModule,
  ],
  templateUrl: './purchase-order-list.html',
  styleUrl: './purchase-order-list.scss',
})
export class PurchaseOrderList {
  private purchaseOrderService = inject(PurchaseOrderService);
  private router = inject(Router);

  // Pagination signals
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Search signals
  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  // rxResource: auto-fetches when params change
  readonly ordersResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.purchaseOrderService.getPurchaseOrders(
        params.page,
        params.size,
        params.search || undefined,
      ),
  });

  // Computed helpers
  readonly orders = this.ordersResource.value;
  readonly loading = this.ordersResource.isLoading;
  readonly error = this.ordersResource.error;

  // Table columns
  readonly displayedColumns: string[] = [
    'orderNumber',
    'supplierName',
    'status',
    'companyStoreName',
    'createdAt',
    'actions',
  ];

  // Status color lookup
  readonly statusColor = PO_STATUS_COLORS;

  constructor() {
    // Debounce effect: searchQuery → 300ms → _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset page to 0 when debounced search changes
    effect(() => {
      this._debouncedSearch();
      if (this.pageIndex() !== 0) {
        this.pageIndex.set(0);
      }
    });
  }

  editOrder(id: string): void {
    this.router.navigate(['/purchases/orders', id]);
  }

  createOrder(): void {
    this.router.navigate(['/purchases/orders/create']);
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  onRetry(): void {
    this.ordersResource.reload();
  }
}
