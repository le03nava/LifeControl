import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
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
  private readonly _storeZones = signal<StoreZone[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly storeZones = this._storeZones.asReadonly();
  readonly loading = this._loading.asReadonly();
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
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http
      .get<StoreZone[]>(
        this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId),
        { params },
      )
      .pipe(
        tap((storeZones) => this._storeZones.set(storeZones)),
        catchError((err) => {
          this._error.set('Error al cargar las zonas de la tienda');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Flat lookup by store zone id. Returns the store zone **including** its full
   * chain and intentionally does not touch the list signal — the edit page owns
   * this data.
   */
  getZoneById(storeZoneId: string): Observable<StoreZone> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<StoreZone>(`${this.configService.apiUrl}/store-zones/${storeZoneId}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar la zona de la tienda');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
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
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .post<StoreZone>(
        this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId),
        request,
      )
      .pipe(
        tap((storeZone) => {
          const current = this._storeZones();
          this._storeZones.set([...current, storeZone]);
        }),
        catchError((err) => {
          this._error.set('Error al crear la zona de la tienda');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
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
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .put<StoreZone>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}`,
        request,
      )
      .pipe(
        tap((updated) => {
          const current = this._storeZones();
          this._storeZones.set(current.map((z) => (z.id === storeZoneId ? updated : z)));
        }),
        catchError((err) => {
          this._error.set('Error al actualizar la zona de la tienda');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
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
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .delete<void>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}`,
      )
      .pipe(
        tap(() => {
          const current = this._storeZones();
          this._storeZones.set(
            current.map((z) => (z.id === storeZoneId ? { ...z, enabled: false } : z)),
          );
        }),
        catchError((err) => {
          this._error.set('Error al deshabilitar la zona de la tienda');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
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
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<StoreZone>(
        `${this.storeZonesUrl(companyId, companyCountryId, regionId, zoneId, storeId, areaId)}/${storeZoneId}/enable`,
        {},
      )
      .pipe(
        tap((updated) => {
          const current = this._storeZones();
          this._storeZones.set(current.map((z) => (z.id === storeZoneId ? updated : z)));
        }),
        catchError((err) => {
          this._error.set('Error al reactivar la zona de la tienda');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
  }
}
