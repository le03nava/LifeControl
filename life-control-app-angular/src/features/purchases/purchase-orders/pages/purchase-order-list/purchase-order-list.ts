import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { StatusChip } from '../../components/status-chip/status-chip';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-purchase-order-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    DatePipe,
    StatusChip,
    MatTableModule,
    MatIconModule,
    MatPaginatorModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatCardModule,
  ],
  templateUrl: './purchase-order-list.html',
  styleUrl: './purchase-order-list.scss',
})
export class PurchaseOrderList {
  private readonly purchaseOrderService = inject(PurchaseOrderService);
  private readonly router = inject(Router);

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
  readonly orders = computed(() =>
    this.ordersResource.hasValue() ? this.ordersResource.value() : undefined,
  );
  readonly loading = this.ordersResource.isLoading;
  readonly error = this.ordersResource.error;

  protected readonly httpErrorMessage = httpErrorMessage;

  // Table columns
  readonly displayedColumns: string[] = [
    'orderNumber',
    'supplierName',
    'status',
    'companyStoreName',
    'createdAt',
    'actions',
  ];

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
