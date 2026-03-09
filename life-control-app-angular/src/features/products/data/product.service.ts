import { inject, Injectable, signal } from '@angular/core';
import { Product } from '../models/product.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signal para almacenar los productos
  private _products = signal<Product[]>([]);
  
  // Signal de solo lectura para usar en componentes
  readonly products = this._products.asReadonly();

  get apiUrl(): string {
    return this.configService.apiUrl;
  }

  getFormattedProducts(): Product[] {
    return this._products();
  }

  getProducts(): void {
    this.http.get<Product[]>(this.apiUrl).subscribe({
      next: (data) => this._products.set(data),
      error: (err) => {
        console.error('[ProductService] Error loading products:', err);
        this._products.set([]);
      }
    });
  }

  getProductList(): void {
    this.getProducts();
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
