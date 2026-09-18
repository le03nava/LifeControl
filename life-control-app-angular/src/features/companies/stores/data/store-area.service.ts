import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
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
 *
 * The list/loading signals were never read by the application and were removed
 * (slice 3, step 2c). The only state left is the last error message; the listing
 * page reads it through a page-scoped instance so it cannot leak across routes
 * (step 2d).
 */
@Injectable({
  providedIn: 'root',
})
export class StoreAreaService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _error = signal<string | null>(null);

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
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http
      .get<StoreArea[]>(this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId), {
        params,
      })
      .pipe(
        catchError((err) => {
          this._error.set('Error al cargar las áreas');
          return throwError(() => err);
        }),
      );
  }

  /**
   * Flat lookup by area id. Returns the area **including** its full chain and
   * intentionally does not read the list — the edit page owns this data.
   */
  getAreaById(areaId: string): Observable<StoreArea> {
    this._error.set(null);
    return this.http.get<StoreArea>(`${this.configService.apiUrl}/store-areas/${areaId}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el área');
        return throwError(() => err);
      }),
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
    this._error.set(null);
    return this.http
      .post<StoreArea>(
        this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId),
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al crear el área');
          return throwError(() => err);
        }),
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
    this._error.set(null);
    return this.http
      .put<StoreArea>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}`,
        request,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al actualizar el área');
          return throwError(() => err);
        }),
      );
  }

  /** Soft delete: the backend sets `enabled = false`. */
  removeArea(
    companyId: string,
    companyCountryId: string,
    regionId: string,
    zoneId: string,
    storeId: string,
    areaId: string,
  ): Observable<void> {
    this._error.set(null);
    return this.http
      .delete<void>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}`,
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al deshabilitar el área');
          return throwError(() => err);
        }),
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
    this._error.set(null);
    return this.http
      .patch<StoreArea>(
        `${this.areasUrl(companyId, companyCountryId, regionId, zoneId, storeId)}/${areaId}/enable`,
        {},
      )
      .pipe(
        catchError((err) => {
          this._error.set('Error al reactivar el área');
          return throwError(() => err);
        }),
      );
  }
}
