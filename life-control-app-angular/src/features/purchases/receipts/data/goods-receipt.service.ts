import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type { Page } from '../../purchase-orders/models/purchase-order.models';
import type { GoodsReceipt, GoodsReceiptRequest } from '../models/receipt.models';

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
   * Fetch paginated goods receipts with optional search filtering.
   * Search matches against `receiptNumber` and `orderNumber` on the backend.
   */
  getReceipts(page = 0, size = 12, search?: string): Observable<Page<GoodsReceipt>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search) {
      params = params.set('search', search);
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
