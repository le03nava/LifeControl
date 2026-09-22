import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { Page } from '../models/product.models';
import {
  ProductVariant,
  ProductVariantRequest,
  ProductVariantSearchResult,
  ProductVariantStoreStock,
  ProductVariantStoreStockRequest,
} from '../models/product-variant.models';

/**
 * Sole owner of every product-variant endpoint.
 *
 * The backend splits the resource in two path roots: the GLOBAL definition lives
 * under `/api/products/{productId}/variants` (create, read, update, delete,
 * re-enable), while the PER-STORE stock and pricing row lives under
 * `/api/variants/{variantId}/stores/{storeId}`. Both are addressed from here so
 * no other service duplicates variant calls.
 *
 * The list endpoint paginates and, when `storeId` is given, populates the
 * store-scoped fields of each variant; omitting it returns the product's global
 * definitions with those fields `null`.
 */
@Injectable({
  providedIn: 'root',
})
export class ProductVariantService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  // Signal para estado de carga
  private readonly _loading = signal(false);

  // Signal para errores
  private readonly _error = signal<string | null>(null);

  // Signals de solo lectura para usar en componentes
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  /** Base of the product-nested variant routes. */
  private get productsUrl(): string {
    return `${this.configService.apiUrl}/products`;
  }

  /** Base of the store-scoped variant routes. */
  private get variantsUrl(): string {
    return `${this.configService.apiUrl}/variants`;
  }

  /**
   * Lists a product's variants, optionally scoped to one store.
   *
   * `storeId` is sent only when provided: the backend treats its absence as "no
   * store in play" and answers with the global definitions. An empty string would
   * be rejected as an invalid UUID, so it is never sent.
   *
   * By default the global-definition read excludes the soft-deleted definitions
   * (`enabled = false`). `includeDisabled` opts back into them and is sent only
   * when `true`, so a default call stays byte-identical to the original URL. The
   * flag is honored only on the global-definition branch (no `storeId`): the
   * store-scoped and search reads always exclude disabled variants, so callers
   * must never combine it with `storeId`.
   *
   * @param productId the product whose variants are wanted
   * @param storeId the store that narrows the list and fills in prices and stock
   * @param page zero-based page index
   * @param size page size
   * @param includeDisabled when `true`, the global-definition read also returns the
   * soft-deleted definitions; defaults to `false` (excludes them)
   */
  getVariants(
    productId: string,
    storeId?: string,
    page = 0,
    size = 12,
    includeDisabled = false,
  ): Observable<Page<ProductVariant>> {
    this._loading.set(true);
    this._error.set(null);

    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (storeId) {
      params = params.set('storeId', storeId);
    }

    if (includeDisabled) {
      params = params.set('includeDisabled', 'true');
    }

    return this.http
      .get<Page<ProductVariant>>(`${this.productsUrl}/${productId}/variants`, { params })
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al cargar las variantes'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Reads one variant scoped to its product. */
  getVariantById(productId: string, variantId: string): Observable<ProductVariant> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .get<ProductVariant>(`${this.productsUrl}/${productId}/variants/${variantId}`)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al cargar la variante'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Creates the global definition of a variant (barcode + name). */
  createVariant(productId: string, request: ProductVariantRequest): Observable<ProductVariant> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .post<ProductVariant>(`${this.productsUrl}/${productId}/variants`, request)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al crear la variante'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Updates the global definition of a variant. Store values are not part of it. */
  updateVariant(
    productId: string,
    variantId: string,
    request: ProductVariantRequest,
  ): Observable<ProductVariant> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .put<ProductVariant>(`${this.productsUrl}/${productId}/variants/${variantId}`, request)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al actualizar la variante'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Soft-deletes a variant by disabling it. */
  deleteVariant(productId: string, variantId: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.productsUrl}/${productId}/variants/${variantId}`).pipe(
      catchError((err: HttpErrorResponse) => {
        this._error.set(this.mapError(err, 'Error al eliminar la variante'));
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /** Re-enables a soft-deleted variant. */
  enableVariant(productId: string, variantId: string): Observable<ProductVariant> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .patch<ProductVariant>(`${this.productsUrl}/${productId}/variants/${variantId}/enable`, {})
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al habilitar la variante'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Upserts the per-store stock and pricing row of a variant.
   *
   * Omitted fields keep their stored value (see
   * {@link ProductVariantStoreStockRequest}); this is not a reset to zero.
   */
  upsertStoreStock(
    variantId: string,
    storeId: string,
    request: ProductVariantStoreStockRequest,
  ): Observable<ProductVariantStoreStock> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .put<ProductVariantStoreStock>(`${this.variantsUrl}/${variantId}/stores/${storeId}`, request)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al guardar el stock de la variante'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Searches the variants of ONE store.
   *
   * `q` matches the barcode, the product sku, the variant name or the product name
   * (`ProductVariantRepository.searchByQuery`). The barcode predicate is an equality
   * and a barcode is globally unique, so searching a variant's barcode scoped to a
   * store returns that variant's store row or nothing at all — which is how the
   * per-store editor reads the row it edits.
   *
   * Distinct from {@link getVariants}: that read lists a product's variants and only
   * populates the store fields when a store is passed, while this one always runs
   * inside a store and only returns variants that already have a row there.
   */
  searchVariants(
    q: string,
    storeId: string,
    page = 0,
    size = 20,
  ): Observable<Page<ProductVariantSearchResult>> {
    const params = new HttpParams()
      .set('q', q)
      .set('storeId', storeId)
      .set('page', String(page))
      .set('size', String(size));

    this._loading.set(true);
    this._error.set(null);

    return this.http
      .get<Page<ProductVariantSearchResult>>(
        `${this.configService.apiUrl}/product-variants/search`,
        {
          params,
        },
      )
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this._error.set(this.mapError(err, 'Error al buscar las variantes de la tienda'));
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
  }

  /**
   * Maps an HTTP failure to the Spanish message shown to the user.
   *
   * 409 covers both uniqueness rules the backend enforces on the global
   * definition (duplicate barcode, duplicate variant name within the product);
   * the fallback distinguishes a read failure from a write failure.
   */
  private mapError(err: HttpErrorResponse, fallback: string): string {
    switch (err.status) {
      case 409:
        return 'Ya existe una variante con ese código de barras o ese nombre para este producto';
      case 404:
        return 'No se encontró la variante';
      case 403:
        return 'No tenés permisos para administrar las variantes de este producto';
      default:
        return fallback;
    }
  }
}
