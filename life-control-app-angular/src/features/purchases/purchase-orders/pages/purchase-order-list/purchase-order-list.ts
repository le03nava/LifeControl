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
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { StatusChip } from '../../components/status-chip/status-chip';
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
  selector: 'app-purchase-order-list',
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

  /**
   * The cascade's current selection. `null` until the filter component emits for the first time, so
   * the list cannot be fetched with a stale or half-resolved location.
   */
  readonly location = signal<PurchaseLocationSelection | null>(null);

  /**
   * Empresa, País y Región are the only required levels: they are what turns the list into one
   * region's orders. Zona and Tienda stay optional, so they never gate the read.
   */
  readonly hasRequiredLocation = computed(() => {
    const location = this.location();
    return !!location?.companyId && !!location?.companyCountryId && !!location?.regionId;
  });

  // rxResource: auto-fetches when params change, and stays idle until the required levels are chosen
  readonly ordersResource = rxResource({
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
      this.purchaseOrderService.getPurchaseOrders(params.page, params.size, {
        search: params.query.search || undefined,
        companyId: params.query.companyId || undefined,
        companyCountryId: params.query.companyCountryId || undefined,
        regionId: params.query.regionId || undefined,
        zoneId: params.query.zoneId || undefined,
        companyStoreId: params.query.companyStoreId || undefined,
      }),
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

    // Back to the first page whenever the applied filters change. `pageIndex` is read untracked: a
    // tracked read would make this effect its own trigger and every page change would snap back to 0.
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
