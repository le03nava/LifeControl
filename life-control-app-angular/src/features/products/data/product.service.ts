import { inject, Injectable, signal } from '@angular/core';
import { Product, Page } from '../models/product.models';
import { Observable } from 'rxjs';
import { HttpClient, HttpParams } from '@angular/common/http';
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
    return `${this.configService.apiUrl}/products`;
  }

  getFormattedProducts(): Product[] {
    return this._products();
  }

  getProductsPaged(page: number, size: number, search?: string): Observable<Page<Product>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Product>>(this.apiUrl, { params });
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
