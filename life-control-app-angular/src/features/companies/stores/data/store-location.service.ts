import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  UpdateStoreLocationRequest,
} from '../models/store-location.models';

/**
 * HTTP access to store locations (level 4 of the store location tree, the leaf).
 *
 * Writes and list reads are nested under the full
 * company → country → region → zone → store → area → store zone path; the edit
 * page resolves a single store location through the flat
 * `/api/store-locations/{storeLocationId}` lookup.
 */
@Injectable({
  providedIn: 'root',
})
export class StoreLocationService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _error = signal<string | null>(null);

  readonly error = this._error.asReadonly();

  private storeLocationsUrl(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
  ): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones/${zoneId}/stores/${storeId}/areas/${areaId}/store-zones/${storeZoneId}/store-locations`;
  }

  getStoreLocations(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    includeDisabled = false,
  ): Observable<StoreLocation[]> {
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http
      .get<StoreLocation[]>(
        this.storeLocationsUrl(
          companyId,
          companyCountryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
        ),
        { params },
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al cargar las ubicaciones');
          return throwError(() => err);
        }),
      );
  }

  /**
   * Flat lookup by store location id. Returns the store location **including**
   * its full chain and intentionally does not touch the list signal — the edit
   * page owns this data.
   */
  getLocationById(storeLocationId: string): Observable<StoreLocation> {
    this._error.set(null);
    return this.http
      .get<StoreLocation>(`${this.configService.apiUrl}/store-locations/${storeLocationId}`)
      .pipe(
        catchError((err) => {
          this._error.set('Error al cargar la ubicación');
          return throwError(() => err);
        }),
      );
  }

  createLocation(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    request: CreateStoreLocationRequest,
  ): Observable<StoreLocation> {
    this._error.set(null);
    return this.http
      .post<StoreLocation>(
        this.storeLocationsUrl(
          companyId,
          companyCountryId,
          regionId,
          zoneId,
          storeId,
          areaId,
          storeZoneId,
        ),
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al crear la ubicación');
          return throwError(() => err);
        }),
      );
  }

  updateLocation(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    storeLocationId: string,
    request: UpdateStoreLocationRequest,
  ): Observable<StoreLocation> {
    this._error.set(null);
    return this.http
      .put<StoreLocation>(
        `${this.storeLocationsUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)}/${storeLocationId}`,
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al actualizar la ubicación');
          return throwError(() => err);
        }),
      );
  }

  /** Soft delete: the backend sets `enabled = false`; the row is kept locally. */
  removeLocation(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    storeLocationId: string,
  ): Observable<void> {
    this._error.set(null);
    return this.http
      .delete<void>(
        `${this.storeLocationsUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)}/${storeLocationId}`,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al deshabilitar la ubicación');
          return throwError(() => err);
        }),
      );
  }

  /** Re-enable: the backend exposes the `/{storeLocationId}/enable` suffix for store locations. */
  enableLocation(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
    storeZoneId: string,
    storeLocationId: string,
  ): Observable<StoreLocation> {
    this._error.set(null);
    return this.http
      .patch<StoreLocation>(
        `${this.storeLocationsUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)}/${storeLocationId}/enable`,
        {},
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al reactivar la ubicación');
          return throwError(() => err);
        }),
      );
  }
}
