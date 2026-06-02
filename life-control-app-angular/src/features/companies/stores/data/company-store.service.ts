import { inject, Injectable, signal } from '@angular/core';
import { CompanyStore, StoreRequest } from '../models/store.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyStoreService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private _stores = signal<CompanyStore[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  readonly stores = this._stores.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private storesUrl(companyId: string, companyCountryId: string, regionId: string, zoneId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones/${zoneId}/stores`;
  }

  getStores(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    includeDisabled = false,
  ): Observable<CompanyStore[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<CompanyStore[]>(this.storesUrl(companyId, companyCountryId, regionId, zoneId), { params }).pipe(
      tap(stores => this._stores.set(stores)),
      catchError(err => {
        this._error.set('Error al cargar las tiendas');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  getStore(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    id: string,
  ): Observable<CompanyStore> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<CompanyStore>(`${this.storesUrl(companyId, companyCountryId, regionId, zoneId)}/${id}`).pipe(
      catchError(err => {
        this._error.set('Error al cargar la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  addStore(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    request: StoreRequest,
  ): Observable<CompanyStore> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<CompanyStore>(this.storesUrl(companyId, companyCountryId, regionId, zoneId), request).pipe(
      tap(store => {
        const current = this._stores();
        this._stores.set([...current, store]);
      }),
      catchError(err => {
        this._error.set('Error al crear la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateStore(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    id: string,
    request: StoreRequest,
  ): Observable<CompanyStore> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<CompanyStore>(
      `${this.storesUrl(companyId, companyCountryId, regionId, zoneId)}/${id}`,
      request,
    ).pipe(
      tap(updated => {
        const current = this._stores();
        this._stores.set(current.map(s => (s.id === id ? updated : s)));
      }),
      catchError(err => {
        this._error.set('Error al actualizar la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  removeStore(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    id: string,
  ): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(
      `${this.storesUrl(companyId, companyCountryId, regionId, zoneId)}/${id}`,
    ).pipe(
      tap(() => {
        const current = this._stores();
        this._stores.set(current.map(s => (s.id === id ? { ...s, enabled: false } : s)));
      }),
      catchError(err => {
        this._error.set('Error al deshabilitar la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableStore(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    id: string,
  ): Observable<CompanyStore> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.patch<CompanyStore>(
      `${this.storesUrl(companyId, companyCountryId, regionId, zoneId)}/${id}`,
      {},
    ).pipe(
      tap(updated => {
        const current = this._stores();
        this._stores.set(current.map(s => (s.id === id ? updated : s)));
      }),
      catchError(err => {
        this._error.set('Error al reactivar la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
