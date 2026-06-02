import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProductSupplier, ProductSupplierRequest } from '../models/product-supplier.models';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class ProductSupplierService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  get apiUrl(): string {
    return `${this.configService.apiUrl}/products`;
  }

  getSuppliers(productId: string): Observable<ProductSupplier[]> {
    return this.http.get<ProductSupplier[]>(`${this.apiUrl}/${productId}/suppliers`);
  }

  addSupplier(productId: string, data: ProductSupplierRequest): Observable<ProductSupplier> {
    return this.http.post<ProductSupplier>(`${this.apiUrl}/${productId}/suppliers`, data);
  }

  updateSupplier(
    productId: string,
    psId: string,
    data: ProductSupplierRequest,
  ): Observable<ProductSupplier> {
    return this.http.put<ProductSupplier>(`${this.apiUrl}/${productId}/suppliers/${psId}`, data);
  }

  removeSupplier(productId: string, psId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${productId}/suppliers/${psId}`);
  }
}
