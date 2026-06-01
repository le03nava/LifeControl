import { inject, Injectable, signal } from '@angular/core';
import { Product, Page } from '../models/product.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signal para estado de carga
  private _loading = signal(false);

  // Signal para errores
  private _error = signal<string | null>(null);

  // Signals de solo lectura para usar en componentes
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/products`;
  }

  getProducts(page: number = 0, size: number = 12, search?: string): Observable<Page<Product>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Product>>(this.apiUrl, { params });
  }

  getProductById(id: string): Observable<Product> {
    return this.http.get<Product>(`${this.apiUrl}/${id}`);
  }

  createProduct(data: Product): Observable<Product> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<Product>(this.apiUrl, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al crear el producto');
        return throwError(() => err);
      }),
    );
  }

  updateProduct(id: string, data: Product): Observable<Product> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<Product>(`${this.apiUrl}/${id}`, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al actualizar el producto');
        return throwError(() => err);
      }),
    );
  }

  deleteProduct(id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.apiUrl}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al eliminar el producto');
        return throwError(() => err);
      }),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
