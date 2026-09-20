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
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { StatusChip } from '../../../purchase-orders/components/status-chip/status-chip';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-receipt-list',
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
  templateUrl: './receipt-list.html',
  styleUrl: './receipt-list.scss',
})
export class ReceiptList {
  private readonly goodsReceiptService = inject(GoodsReceiptService);
  private readonly router = inject(Router);

  // Pagination signals
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Search signals
  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  // rxResource: auto-fetches when params change
  readonly receiptsResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.goodsReceiptService.getReceipts(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly receipts = computed(() =>
    this.receiptsResource.hasValue() ? this.receiptsResource.value() : undefined,
  );
  readonly loading = this.receiptsResource.isLoading;
  readonly error = this.receiptsResource.error;
  /** Whether the paginator is worth rendering (more than one page of results). */
  readonly hasMultiplePages = computed(() => (this.receipts()?.totalPages ?? 0) > 1);

  protected readonly httpErrorMessage = httpErrorMessage;

  // Table columns
  readonly displayedColumns: string[] = [
    'receiptNumber',
    'orderNumber',
    'receivedAt',
    'receivedBy',
    'status',
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

  viewReceipt(id: string): void {
    this.router.navigate(['/purchases/receipts', id]);
  }

  createReceipt(): void {
    this.router.navigate(['/purchases/receipts/create']);
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  onRetry(): void {
    this.receiptsResource.reload();
  }
}
