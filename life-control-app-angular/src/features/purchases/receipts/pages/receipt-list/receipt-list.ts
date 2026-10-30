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
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { StatusChip } from '../../../purchase-orders/components/status-chip/status-chip';
import { PurchaseLocationFilter } from '../../../components/location-filter/location-filter';
import type { PurchaseLocationSelection } from '../../../models/purchase-location-filter.models';
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
    PurchaseLocationFilter,
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

  /**
   * The cascade's current selection. `null` until the filter component emits for the first time, so
   * the list cannot be fetched with a stale or half-resolved location.
   */
  readonly location = signal<PurchaseLocationSelection | null>(null);

  /**
   * Empresa, País y Región are the only required levels (`S2`): they are what turns the list into one
   * region's receipts. Zona and Tienda stay optional, so they never gate the read.
   */
  readonly hasRequiredLocation = computed(() => {
    const location = this.location();
    return !!location?.companyId && !!location?.companyCountryId && !!location?.regionId;
  });

  // rxResource: auto-fetches when params change, and stays idle until the required levels are chosen
  readonly receiptsResource = rxResource({
    params: () => {
      const location = this.location();
      if (!this.hasRequiredLocation() || !location) return undefined;
      return {
        page: this.pageIndex(),
        size: this.pageSize(),
        query: {
          search: this._debouncedSearch(),
          companyId: location.companyId,
          companyCountryId: location.companyCountryId,
          regionId: location.regionId,
          zoneId: location.zoneId,
          companyStoreId: location.companyStoreId,
        },
      };
    },
    stream: ({ params }) =>
      this.goodsReceiptService.getReceipts(params.page, params.size, {
        search: params.query.search || undefined,
        companyId: params.query.companyId || undefined,
        companyCountryId: params.query.companyCountryId || undefined,
        regionId: params.query.regionId || undefined,
        zoneId: params.query.zoneId || undefined,
        companyStoreId: params.query.companyStoreId || undefined,
      }),
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

    // Back to the first page whenever the applied filters change (`S5`). `pageIndex` is read
    // untracked: a tracked read would make this effect its own trigger and every page change would
    // snap back to 0.
    effect(() => {
      this._debouncedSearch();
      this.location();
      untracked(() => {
        if (this.pageIndex() !== 0) {
          this.pageIndex.set(0);
        }
      });
    });
  }

  onLocationChange(selection: PurchaseLocationSelection): void {
    this.location.set(selection);
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
