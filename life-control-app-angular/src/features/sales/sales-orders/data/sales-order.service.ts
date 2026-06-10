import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type {
  SalesOrder,
  SalesOrderItem,
  SalesOrderRequest,
  SalesOrderItemRequest,
  UpdateSalesOrderStatusRequest,
  Page,
} from '../models/sales-order.models';

/** HTTP service for the `/api/sales-orders` REST endpoints. */
@Injectable({
  providedIn: 'root',
})
export class SalesOrderService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/sales-orders`;
  }

  // ── Orders ────────────────────────────────────────────────────────

  /**
   * Fetch paginated sales orders with optional search filtering.
   * Search matches against `orderNumber` and `customerName` on the backend.
   */
  getSalesOrders(page = 0, size = 12, search?: string): Observable<Page<SalesOrder>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search) {
      params = params.set('search', search);
    }
    return this.http.get<Page<SalesOrder>>(this.baseUrl, { params });
  }

  /** Fetch a single sales order by ID, including its line items. */
  getSalesOrder(id: string): Observable<SalesOrder> {
    return this.http.get<SalesOrder>(`${this.baseUrl}/${id}`);
  }

  /** Create a new sales order (status defaults to Draft on the backend). */
  create(request: SalesOrderRequest): Observable<SalesOrder> {
    return this.http.post<SalesOrder>(this.baseUrl, request);
  }

  /** Update the header fields of an existing sales order. */
  update(id: string, request: SalesOrderRequest): Observable<SalesOrder> {
    return this.http.put<SalesOrder>(`${this.baseUrl}/${id}`, request);
  }

  /** Advance or cancel the sales order status via PATCH. */
  updateStatus(id: string, request: UpdateSalesOrderStatusRequest): Observable<SalesOrder> {
    return this.http.patch<SalesOrder>(`${this.baseUrl}/${id}/status`, request);
  }

  /** Enable a previously disabled sales order. */
  enable(id: string): Observable<SalesOrder> {
    return this.http.patch<SalesOrder>(`${this.baseUrl}/${id}/enable`, null);
  }

  /** Soft-delete (disable) a sales order. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  // ── Items ─────────────────────────────────────────────────────────

  /** Fetch all line items for a given sales order. */
  getItems(orderId: string): Observable<SalesOrderItem[]> {
    return this.http.get<SalesOrderItem[]>(`${this.baseUrl}/${orderId}/items`);
  }

  /** Add a new line item to an existing sales order. */
  addItem(orderId: string, request: SalesOrderItemRequest): Observable<SalesOrderItem> {
    return this.http.post<SalesOrderItem>(`${this.baseUrl}/${orderId}/items`, request);
  }

  /** Update an existing line item. */
  updateItem(
    orderId: string,
    itemId: string,
    request: SalesOrderItemRequest,
  ): Observable<SalesOrderItem> {
    return this.http.put<SalesOrderItem>(
      `${this.baseUrl}/${orderId}/items/${itemId}`,
      request,
    );
  }

  /** Remove a line item from a sales order (backend enforces Draft-only). */
  deleteItem(orderId: string, itemId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${orderId}/items/${itemId}`);
  }

  /** Update the status of a single line item. */
  updateItemStatus(
    orderId: string,
    itemId: string,
    statusId: string,
  ): Observable<SalesOrderItem> {
    return this.http.patch<SalesOrderItem>(
      `${this.baseUrl}/${orderId}/items/${itemId}/status`,
      { statusId },
    );
  }
}
