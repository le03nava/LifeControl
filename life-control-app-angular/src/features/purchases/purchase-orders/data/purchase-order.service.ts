import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type {
  PurchaseOrder,
  PurchaseOrderRequest,
  PurchaseOrderDetail,
  PurchaseOrderDetailRequest,
  UpdatePurchaseOrderStatusRequest,
  Page,
} from '../models/purchase-order.models';

/**
 * Optional filters of the order list. Every level is independent: an absent value does not narrow
 * anything, and the API combines the levels it receives with `AND`.
 *
 * The location levels (`companyId` … `companyStoreId`) come from the store the order belongs to: the
 * API reaches them through `purchase_orders.company_store_id → zone → region → country → company`.
 */
export interface PurchaseOrderListQuery {
  /** Free text over the supplier and the store name, the two fields the API matches. */
  search?: string;
  companyId?: string;
  companyCountryId?: string;
  regionId?: string;
  zoneId?: string;
  companyStoreId?: string;
}

/** HTTP service for the `/api/purchase-orders` REST endpoints. */
@Injectable({
  providedIn: 'root',
})
export class PurchaseOrderService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/purchase-orders`;
  }

  /**
   * Fetch paginated purchase orders with the optional search and location filters.
   * Search matches against `supplierName` and the store name on the backend. A filter that is absent
   * or empty is not sent, so the list is never narrowed by an accidental empty value.
   */
  getPurchaseOrders(
    page = 0,
    size = 12,
    query: PurchaseOrderListQuery = {},
  ): Observable<Page<PurchaseOrder>> {
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

  /**
   * Add a line to an existing order. The API answers the **line** it created, not the document.
   *
   * `request.statusId` may be omitted: the server names the line's initial state itself, so a caller that has no
   * document-state catalog in hand still creates a line.
   */
  addDetail(
    purchaseOrderId: string,
    request: PurchaseOrderDetailRequest,
  ): Observable<PurchaseOrderDetail> {
    return this.http.post<PurchaseOrderDetail>(
      `${this.baseUrl}/${purchaseOrderId}/details`,
      request,
    );
  }

  /**
   * Update an existing line. The API answers the updated **line**.
   *
   * An omitted `statusId` keeps the line's stored state: the caller that edits a quantity does not have to restate a
   * state it is not changing.
   */
  updateDetail(
    purchaseOrderId: string,
    detailId: string,
    request: PurchaseOrderDetailRequest,
  ): Observable<PurchaseOrderDetail> {
    return this.http.put<PurchaseOrderDetail>(
      `${this.baseUrl}/${purchaseOrderId}/details/${detailId}`,
      request,
    );
  }

  /**
   * Remove a line from an order (the backend enforces Draft-only).
   *
   * The route answers **204 No Content**: there is no body to type, so this returns `void` rather than a document the
   * server never sent.
   */
  deleteDetail(purchaseOrderId: string, detailId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${purchaseOrderId}/details/${detailId}`);
  }
}
