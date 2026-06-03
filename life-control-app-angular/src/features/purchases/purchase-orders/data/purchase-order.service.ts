import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type {
  PurchaseOrder,
  PurchaseOrderRequest,
  PurchaseOrderDetailRequest,
  UpdatePurchaseOrderStatusRequest,
  Page,
} from '../models/purchase-order.models';

/** HTTP service for the `/api/purchase-orders` REST endpoints. */
@Injectable({
  providedIn: 'root',
})
export class PurchaseOrderService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/purchase-orders`;
  }

  /**
   * Fetch paginated purchase orders with optional search filtering.
   * Search matches against `orderNumber` and `supplierName` on the backend.
   */
  getPurchaseOrders(page = 0, size = 12, search?: string): Observable<Page<PurchaseOrder>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search) {
      params = params.set('search', search);
    }
    return this.http.get<Page<PurchaseOrder>>(this.baseUrl, { params });
  }

  /** Fetch a single purchase order by ID, including its detail lines. */
  getPurchaseOrder(id: string): Observable<PurchaseOrder> {
    return this.http.get<PurchaseOrder>(`${this.baseUrl}/${id}`);
  }

  /** Create a new purchase order (status defaults to Draft on the backend). */
  create(request: PurchaseOrderRequest): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(this.baseUrl, request);
  }

  /** Update the header fields of an existing purchase order. */
  update(id: string, request: PurchaseOrderRequest): Observable<PurchaseOrder> {
    return this.http.put<PurchaseOrder>(`${this.baseUrl}/${id}`, request);
  }

  /** Advance or reject the purchase order status via PATCH. */
  updateStatus(id: string, request: UpdatePurchaseOrderStatusRequest): Observable<PurchaseOrder> {
    return this.http.patch<PurchaseOrder>(`${this.baseUrl}/${id}/status`, request);
  }

  /** Add a new line item to an existing purchase order. */
  addDetail(purchaseOrderId: string, request: PurchaseOrderDetailRequest): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`${this.baseUrl}/${purchaseOrderId}/details`, request);
  }

  /** Update an existing line item. */
  updateDetail(
    purchaseOrderId: string,
    detailId: string,
    request: PurchaseOrderDetailRequest,
  ): Observable<PurchaseOrder> {
    return this.http.put<PurchaseOrder>(
      `${this.baseUrl}/${purchaseOrderId}/details/${detailId}`,
      request,
    );
  }

  /** Remove a line item from a purchase order (backend enforces Draft-only). */
  deleteDetail(purchaseOrderId: string, detailId: string): Observable<PurchaseOrder> {
    return this.http.delete<PurchaseOrder>(
      `${this.baseUrl}/${purchaseOrderId}/details/${detailId}`,
    );
  }
}
