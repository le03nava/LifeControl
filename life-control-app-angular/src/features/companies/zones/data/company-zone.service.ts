import { inject, Injectable, signal } from '@angular/core';
import { CompanyZone, CompanyZoneRequest } from '../models/zone.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyZoneService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private _zones = signal<CompanyZone[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  readonly zones = this._zones.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private zonesUrl(companyId: string, companyCountryId: string, regionId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${companyCountryId}/regions/${regionId}/zones`;
  }

  getZones(companyId: string, countryId: string, regionId: string, includeDisabled = false): Observable<CompanyZone[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<CompanyZone[]>(this.zonesUrl(companyId, countryId, regionId), { params }).pipe(
      tap(zones => this._zones.set(zones)),
      catchError(err => {
        this._error.set('Error al cargar las zonas');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  getZone(companyId: string, countryId: string, regionId: string, id: string): Observable<CompanyZone> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<CompanyZone>(`${this.zonesUrl(companyId, countryId, regionId)}/${id}`).pipe(
      catchError(err => {
        this._error.set('Error al cargar la zona');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  addZone(companyId: string, countryId: string, regionId: string, request: CompanyZoneRequest): Observable<CompanyZone> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<CompanyZone>(this.zonesUrl(companyId, countryId, regionId), request).pipe(
      tap(zone => {
        const current = this._zones();
        this._zones.set([...current, zone]);
      }),
      catchError(err => {
        if (err.status === 409) {
          this._error.set('Ya existe una zona con ese código');
        } else {
          this._error.set('Error al crear la zona');
        }
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateZone(companyId: string, countryId: string, regionId: string, id: string, request: CompanyZoneRequest): Observable<CompanyZone> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<CompanyZone>(`${this.zonesUrl(companyId, countryId, regionId)}/${id}`, request).pipe(
      tap(updated => {
        const current = this._zones();
        this._zones.set(current.map(z => (z.id === id ? updated : z)));
      }),
      catchError(err => {
        this._error.set('Error al actualizar la zona');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  removeZone(companyId: string, countryId: string, regionId: string, id: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.zonesUrl(companyId, countryId, regionId)}/${id}`).pipe(
      tap(() => {
        const current = this._zones();
        this._zones.set(current.filter(z => z.id !== id));
      }),
      catchError(err => {
        this._error.set('Error al eliminar la zona');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableZone(companyId: string, countryId: string, regionId: string, id: string): Observable<CompanyZone> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.patch<CompanyZone>(`${this.zonesUrl(companyId, countryId, regionId)}/${id}`, {}).pipe(
      tap(updated => {
        const current = this._zones();
        this._zones.set(current.map(z => (z.id === id ? updated : z)));
      }),
      catchError(err => {
        this._error.set('Error al reactivar la zona');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
