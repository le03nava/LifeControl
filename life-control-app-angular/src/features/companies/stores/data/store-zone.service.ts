import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CreateStoreZoneRequest,
  StoreZone,
  UpdateStoreZoneRequest,
} from '../models/store-zone.models';

/**
 * HTTP access to store zones (level 3 of the store location tree).
 *
 * Writes and list reads are nested under the full
 * company → country → region → zone → store → area path; the edit page resolves
 * a single store zone through the flat `/api/store-zones/{storeZoneId}` lookup.
 */
@Injectable({
  providedIn: 'root',
})
export class StoreZoneService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _error = signal<string | null>(null);

  readonly error = this._error.asReadonly();

  private storeZonesUrl(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
  ): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas/${areaId}/store-zones`;
  }

  getStoreZones(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    includeDisabled = false,
  ): Observable<StoreZone[]> {
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http
      .get<StoreZone[]>(
        this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId),
        { params },
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al cargar las zonas de la tienda');
          return throwError(() => err);
        }),
      );
  }

  /**
   * Flat lookup by store zone id. Returns the store zone **including** its full
   * chain and intentionally does not read the list — the edit page owns this
   * data.
   */
  getZoneById(storeZoneId: string): Observable<StoreZone> {
    this._error.set(null);
    return this.http.get<StoreZone>(`${this.configService.apiUrl}/store-zones/${storeZoneId}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar la zona de la tienda');
        return throwError(() => err);
      }),
    );
  }

  createZone(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    request: CreateStoreZoneRequest,
  ): Observable<StoreZone> {
    this._error.set(null);
    return this.http
      .post<StoreZone>(
        this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId),
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al crear la zona de la tienda');
          return throwError(() => err);
        }),
      );
  }

  updateZone(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    request: UpdateStoreZoneRequest,
  ): Observable<StoreZone> {
    this._error.set(null);
    return this.http
      .put<StoreZone>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}`,
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al actualizar la zona de la tienda');
          return throwError(() => err);
        }),
      );
  }

  /** Soft delete: the backend sets `enabled = false`; the row is kept locally. */
  removeZone(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
  ): Observable<void> {
    this._error.set(null);
    return this.http
      .delete<void>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}`,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al deshabilitar la zona de la tienda');
          return throwError(() => err);
        }),
      );
  }

  /** Re-enable: the backend exposes the `/{storeZoneId}/enable` suffix for store zones. */
  enableZone(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
  ): Observable<StoreZone> {
    this._error.set(null);
    return this.http
      .patch<StoreZone>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}/enable`,
        {},
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al reactivar la zona de la tienda');
          return throwError(() => err);
        }),
      );
  }
}
