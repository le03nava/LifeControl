import { inject, Injectable, signal } from '@angular/core';
import { Supplier, Page, SupplierRequest } from '../models/supplier.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

/**
 * The supplier catalog, read and written **per company**.
 *
 * A supplier belongs to exactly one company (`/api/companies/{companyId}/suppliers`, issue #672), so
 * every method takes the company and there is no company-blind path to fall back to: the unscoped
 * `/api/suppliers` answers 404. `ProductSupplierService` has the same shape for a product's supplier
 * links, because the link hangs off the product and the product belongs to a company.
 */
@Injectable({
  providedIn: 'root',
})
export class SupplierService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  // Signal para estado de carga
  private readonly _loading = signal(false);

  // Signal para errores
  private readonly _error = signal<string | null>(null);

  // Signals de solo lectura para usar en componentes
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  /** The supplier collection of one company: the only address the catalog answers on. */
  suppliersUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/suppliers`;
  }

  getSuppliers(
    companyId: string,
    page = 0,
    size = 12,
    search?: string,
  ): Observable<Page<Supplier>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Supplier>>(this.suppliersUrl(companyId), { params });
  }

  getSupplierById(companyId: string, id: string): Observable<Supplier> {
    return this.http.get<Supplier>(`${this.suppliersUrl(companyId)}/${id}`);
  }

  createSupplier(companyId: string, data: SupplierRequest): Observable<Supplier> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<Supplier>(this.suppliersUrl(companyId), data).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al crear el proveedor');
        return throwError(() => err);
      }),
    );
  }

  updateSupplier(companyId: string, id: string, data: SupplierRequest): Observable<Supplier> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<Supplier>(`${this.suppliersUrl(companyId)}/${id}`, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al actualizar el proveedor');
        return throwError(() => err);
      }),
    );
  }

  deleteSupplier(companyId: string, id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.suppliersUrl(companyId)}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al eliminar el proveedor');
        return throwError(() => err);
      }),
    );
  }

  getAllSuppliers(
    companyId: string,
    page = 0,
    size = 1000,
    search?: string,
  ): Observable<Page<Supplier>> {
    return this.getSuppliers(companyId, page, size, search);
  }

  clearError(): void {
    this._error.set(null);
  }
}
