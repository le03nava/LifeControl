import { inject, Injectable } from '@angular/core';
import { Product } from '../models/product.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { httpResource } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Usar httpResource para obtener la lista de productos
  private _productsResource = httpResource<Product[]>(
    () => this.apiUrl,
    { defaultValue: [] }
  );

  // Signal derivado de la resource
  readonly products = this._productsResource.value;

  get apiUrl(): string {
    return this.configService.apiUrl;
  }

  getFormattedProducts(): Product[] {
    return this._productsResource.value() ?? [];
  }

  getProducts(): void {
    this._productsResource.reload();
  }

  getProductList(): void {
    this._productsResource.reload();
  }

  getProductById(id: string): Observable<Product> {
    return this.http.get<Product>(`${this.apiUrl}/${id}`);
  }

  createProduct(data: Product): Observable<Product> {
    return this.http.post<Product>(`${this.apiUrl}`, data);
  }

  updateProduct(data: Product): Observable<Product> {
    return this.http.put<Product>(`${this.apiUrl}`, data);
  }

  deleteProduct(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
