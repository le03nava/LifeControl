import { inject, Injectable, signal } from '@angular/core';
import { Product, ProductAttributeProfile, ProductRequest, Page } from '../models/product.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, map } from 'rxjs/operators';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

export interface SupplierProduct {
  productId: string;
  productName: string;
  sku: string;
}

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  // Signal para estado de carga
  private readonly _loading = signal(false);

  // Signal para errores
  private readonly _error = signal<string | null>(null);

  // Signals de solo lectura para usar en componentes
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  /**
   * The products collection of one company: the only base this service builds.
   *
   * The company is a path segment because it is the resource's owner. There is deliberately no
   * company-blind base to fall back to — a `/products` URL is no longer served, and a method that still
   * built one would send every list, read and write at a path that answers nothing.
   */
  productsUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/products`;
  }

  /**
   * The attribute profile of a category: a company-scoped resource, deliberately read here.
   *
   * The URL nests under `product-categories`, not under `products`, because the profile is the
   * category's and both screens — the product form and the variant dialog — ask for the same one.
   */
  private attributeProfileUrl(companyId: string, categoryId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/product-categories/${categoryId}/attribute-profile`;
  }

  getProducts(companyId: string, page = 0, size = 12, search?: string): Observable<Page<Product>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Product>>(this.productsUrl(companyId), { params });
  }

  getProductById(companyId: string, id: string): Observable<Product> {
    return this.http.get<Product>(`${this.productsUrl(companyId)}/${id}`);
  }

  /**
   * The attribute profile the category declares, or `null` when its branch declares none.
   *
   * Nested under the category on the wire (`/companies/{companyId}/product-categories/{categoryId}`),
   * so the company scope is part of the contract and a category of another company is a `404`. The
   * `204` is mapped to `null` here rather than left to every caller: it is a legitimate answer — the
   * seeded catalog attaches profiles to a handful of roots and leaves the rest without one — and its
   * only meaning is "render no attribute inputs", which is not an error and must not reach the error
   * signal below.
   */
  getAttributeProfile(
    companyId: string,
    categoryId: string,
  ): Observable<ProductAttributeProfile | null> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .get<ProductAttributeProfile>(this.attributeProfileUrl(companyId, categoryId), {
        observe: 'response',
      })
      .pipe(
        map((response) => response.body ?? null),
        finalize(() => this._loading.set(false)),
        catchError((err) => {
          this._error.set('Error al cargar los atributos de la categoría');
          return throwError(() => err);
        }),
      );
  }

  getProductsBySupplier(
    companyId: string,
    supplierId: string,
    search?: string,
  ): Observable<SupplierProduct[]> {
    let params = new HttpParams();
    if (search) {
      params = params.set('search', search);
    }
    return this.http.get<SupplierProduct[]>(
      `${this.productsUrl(companyId)}/by-supplier/${supplierId}`,
      { params },
    );
  }

  createProduct(companyId: string, data: ProductRequest): Observable<Product> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<Product>(this.productsUrl(companyId), data).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al crear el producto');
        return throwError(() => err);
      }),
    );
  }

  updateProduct(companyId: string, id: string, data: ProductRequest): Observable<Product> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<Product>(`${this.productsUrl(companyId)}/${id}`, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al actualizar el producto');
        return throwError(() => err);
      }),
    );
  }

  deleteProduct(companyId: string, id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.productsUrl(companyId)}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al eliminar el producto');
        return throwError(() => err);
      }),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
