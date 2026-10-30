import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type { Page } from '../../purchase-orders/models/purchase-order.models';
import type { PurchaseLocationSelection } from '../../models/purchase-location-filter.models';
import type { GoodsReceipt, GoodsReceiptRequest } from '../models/receipt.models';

/**
 * Optional filters of the receipt list: the free-text search plus the five levels of the store
 * hierarchy (`S1`, `S3` of `odd/tasks/receipt-location-filter.md`).
 *
 * Every level is independent and absent by default: a value that is absent, `null` or empty is not
 * sent at all, so an unfiltered list is never narrowed by an accidental empty value. The levels come
 * from the store the receipt was registered at: the API reaches them through
 * `goods_receipts.company_store_id → zone → region → country → company`.
 *
 * The five ids are the shared `PurchaseLocationSelection` shape, the same one `/purchases/orders`
 * sends, so the two lists cannot drift apart on the filter names.
 */
export interface GoodsReceiptListQuery extends Partial<PurchaseLocationSelection> {
  /** Free text over `receiptNumber` and `orderNumber`, the two fields the API matches. */
  search?: string;
}

/** HTTP service for the `/api/goods-receipts` REST endpoints. */
@Injectable({
  providedIn: 'root',
})
export class GoodsReceiptService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/goods-receipts`;
  }

  /**
   * Fetch paginated goods receipts with the optional search and location filters.
   * Search matches against `receiptNumber` and `orderNumber` on the backend, and each location level
   * narrows the page on top of the caller's own scope, never instead of it (`S3`).
   */
  getReceipts(
    page = 0,
    size = 12,
    query: GoodsReceiptListQuery = {},
  ): Observable<Page<GoodsReceipt>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const key of [
      'search',
      'companyId',
      'companyCountryId',
      'regionId',
      'zoneId',
      'companyStoreId',
    ] as const) {
      const value = query[key];
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<GoodsReceipt>>(this.baseUrl, { params });
  }

  /** Fetch a single goods receipt by ID, including its lines. */
  getReceipt(id: string): Observable<GoodsReceipt> {
    return this.http.get<GoodsReceipt>(`${this.baseUrl}/${id}`);
  }

  /** Register a new reception against a purchase order. */
  createReceipt(request: GoodsReceiptRequest): Observable<GoodsReceipt> {
    return this.http.post<GoodsReceipt>(this.baseUrl, request);
  }
}
