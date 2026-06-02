import { inject, Injectable, signal } from '@angular/core';
import { Supplier, Page, SupplierRequest } from '../models/supplier.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class SupplierService {
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
    return `${this.configService.apiUrl}/suppliers`;
  }

  getSuppliers(page: number = 0, size: number = 12, search?: string): Observable<Page<Supplier>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Supplier>>(this.apiUrl, { params });
  }

  getSupplierById(id: string): Observable<Supplier> {
    return this.http.get<Supplier>(`${this.apiUrl}/${id}`);
  }

  createSupplier(data: SupplierRequest): Observable<Supplier> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<Supplier>(this.apiUrl, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al crear el proveedor');
        return throwError(() => err);
      }),
    );
  }

  updateSupplier(id: string, data: SupplierRequest): Observable<Supplier> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<Supplier>(`${this.apiUrl}/${id}`, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al actualizar el proveedor');
        return throwError(() => err);
      }),
    );
  }

  deleteSupplier(id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.apiUrl}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al eliminar el proveedor');
        return throwError(() => err);
      }),
    );
  }

  getAllSuppliers(page = 0, size = 1000, search?: string): Observable<Page<Supplier>> {
    return this.getSuppliers(page, size, search);
  }

  clearError(): void {
    this._error.set(null);
  }
}
