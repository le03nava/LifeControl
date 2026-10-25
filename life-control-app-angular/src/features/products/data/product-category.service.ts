import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { ProductCategory, ProductCategoryRequest } from '../models/product-category.models';

/**
 * HTTP access to the company-scoped product category tree.
 *
 * Deliberately thin: no signal-backed cache and no error mapping, like its neighbours in this
 * feature (`ProductSupplierService`, `ProductService`). The tree is a single company-scoped read
 * that must re-run its scope check on every call, so caching it in a root service would keep a
 * stale tree alive across company switches — the list page owns its own `rxResource` instead.
 *
 * Every path nests under `/companies/{companyId}` because the API's own contract does: the company
 * is part of the wire, not a header or an ambient session value.
 *
 * Soft delete: `DELETE` sets `enabled = false` on the node **and its whole subtree**, and the
 * inverse is `PATCH /{id}/enable` with an explicit flag.
 */
@Injectable({
  providedIn: 'root',
})
export class ProductCategoryService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private categoriesUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/product-categories`;
  }

  /**
   * Reads the company's whole tree as a depth-first ordered flat list.
   *
   * `includeDisabled` reaches the request: the API already excludes disabled nodes and the subtree
   * below them, so filtering client-side would have nothing left to filter.
   */
  getCategories(companyId: string, includeDisabled = false): Observable<ProductCategory[]> {
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<ProductCategory[]>(this.categoriesUrl(companyId), { params });
  }

  getCategoryById(companyId: string, id: string): Observable<ProductCategory> {
    return this.http.get<ProductCategory>(`${this.categoriesUrl(companyId)}/${id}`);
  }

  createCategory(companyId: string, request: ProductCategoryRequest): Observable<ProductCategory> {
    return this.http.post<ProductCategory>(this.categoriesUrl(companyId), request);
  }

  /** Also used to move a node: `parentCategoryId` carries the new parent, or `null` for the root. */
  updateCategory(
    companyId: string,
    id: string,
    request: ProductCategoryRequest,
  ): Observable<ProductCategory> {
    return this.http.put<ProductCategory>(`${this.categoriesUrl(companyId)}/${id}`, request);
  }

  /** Soft delete: the node and its subtree are disabled, never removed. */
  deleteCategory(companyId: string, id: string): Observable<void> {
    return this.http.delete<void>(`${this.categoriesUrl(companyId)}/${id}`);
  }

  setCategoryEnabled(companyId: string, id: string, enabled: boolean): Observable<ProductCategory> {
    return this.http.patch<ProductCategory>(`${this.categoriesUrl(companyId)}/${id}/enable`, {
      enabled,
    });
  }
}
