import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CreateStoreAreaRequest,
  StoreArea,
  UpdateStoreAreaRequest,
} from '../models/store-area.models';

/**
 * HTTP access to store areas (level 2 of the store location tree).
 *
 * Writes and list reads are nested under the full
 * company → country → region → zone → store path; the edit page resolves a
 * single area through the flat `/api/store-areas/{areaId}` lookup.
 */
@Injectable({
  providedIn: 'root',
})
export class StoreAreaService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _areas = signal<StoreArea[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly areas = this._areas.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private areasUrl(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
  ): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas`;
  }

  getAreas(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    includeDisabled = false,
  ): Observable<StoreArea[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http
      .get<StoreArea[]>(this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId), {
        params,
      })
      .pipe(
        tap((areas) => this._areas.set(areas)),
        catchError((err) => {
          this._error.set('Error al cargar las áreas');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Flat lookup by area id. Returns the area **including** its full chain and
   * intentionally does not touch the list signal — the edit page owns this data.
   */
  getAreaById(areaId: string): Observable<StoreArea> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<StoreArea>(`${this.configService.apiUrl}/store-areas/${areaId}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el área');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  createArea(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    request: CreateStoreAreaRequest,
  ): Observable<StoreArea> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .post<StoreArea>(
        this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId),
        request,
      )
      .pipe(
        tap((area) => {
          const current = this._areas();
          this._areas.set([...current, area]);
        }),
        catchError((err) => {
          this._error.set('Error al crear el área');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  updateArea(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    request: UpdateStoreAreaRequest,
  ): Observable<StoreArea> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .put<StoreArea>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}`,
        request,
      )
      .pipe(
        tap((updated) => {
          const current = this._areas();
          this._areas.set(current.map((a) => (a.id === areaId ? updated : a)));
        }),
        catchError((err) => {
          this._error.set('Error al actualizar el área');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Soft delete: the backend sets `enabled = false`; the row is kept locally. */
  removeArea(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
  ): Observable<void> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .delete<void>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}`,
      )
      .pipe(
        tap(() => {
          const current = this._areas();
          this._areas.set(current.map((a) => (a.id === areaId ? { ...a, enabled: false } : a)));
        }),
        catchError((err) => {
          this._error.set('Error al deshabilitar el área');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /** Re-enable: the backend exposes the `/{areaId}/enable` suffix for areas. */
  enableArea(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
  ): Observable<StoreArea> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<StoreArea>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}/enable`,
        {},
      )
      .pipe(
        tap((updated) => {
          const current = this._areas();
          this._areas.set(current.map((a) => (a.id === areaId ? updated : a)));
        }),
        catchError((err) => {
          this._error.set('Error al reactivar el área');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
  }
}
