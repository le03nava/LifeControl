import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProductSupplier, ProductSupplierRequest } from '../models/product-supplier.models';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class ProductSupplierService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  /**
   * The products collection of one company, which is the owner of every assignment below.
   *
   * A product-supplier link is company-scoped even though the supplier itself is global (a decision of
   * its own unit): the link hangs off the product, and the product belongs to a company.
   */
  productsUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/products`;
  }

  getSuppliers(companyId: string, productId: string): Observable<ProductSupplier[]> {
    return this.http.get<ProductSupplier[]>(
      `${this.productsUrl(companyId)}/${productId}/suppliers`,
    );
  }

  addSupplier(
    companyId: string,
    productId: string,
    data: ProductSupplierRequest,
  ): Observable<ProductSupplier> {
    return this.http.post<ProductSupplier>(
      `${this.productsUrl(companyId)}/${productId}/suppliers`,
      data,
    );
  }

  updateSupplier(
    companyId: string,
    productId: string,
    psId: string,
    data: ProductSupplierRequest,
  ): Observable<ProductSupplier> {
    return this.http.put<ProductSupplier>(
      `${this.productsUrl(companyId)}/${productId}/suppliers/${psId}`,
      data,
    );
  }

  removeSupplier(companyId: string, productId: string, psId: string): Observable<void> {
    return this.http.delete<void>(`${this.productsUrl(companyId)}/${productId}/suppliers/${psId}`);
  }
}
