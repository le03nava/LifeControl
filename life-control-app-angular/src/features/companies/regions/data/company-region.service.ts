import { inject, Injectable, signal } from '@angular/core';
import { CompanyRegion, CompanyRegionRequest } from '../models/region.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyRegionService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private _regions = signal<CompanyRegion[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  readonly regions = this._regions.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private regionsUrl(companyId: string, countryId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${countryId}/regions`;
  }

  getRegions(companyId: string, countryId: string, includeDisabled = false): Observable<CompanyRegion[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<CompanyRegion[]>(this.regionsUrl(companyId, countryId), { params }).pipe(
      tap(regions => this._regions.set(regions)),
      catchError(err => {
        this._error.set('Error al cargar las regiones');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  addRegion(companyId: string, countryId: string, request: CompanyRegionRequest): Observable<CompanyRegion> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<CompanyRegion>(this.regionsUrl(companyId, countryId), request).pipe(
      tap(region => {
        const current = this._regions();
        this._regions.set([...current, region]);
      }),
      catchError(err => {
        if (err.status === 409) {
          this._error.set('Ya existe una región con ese código');
        } else {
          this._error.set('Error al crear la región');
        }
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateRegion(companyId: string, countryId: string, regionId: string, request: CompanyRegionRequest): Observable<CompanyRegion> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<CompanyRegion>(`${this.regionsUrl(companyId, countryId)}/${regionId}`, request).pipe(
      tap(updated => {
        const current = this._regions();
        this._regions.set(current.map(r => (r.id === regionId ? updated : r)));
      }),
      catchError(err => {
        this._error.set('Error al actualizar la región');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  removeRegion(companyId: string, countryId: string, regionId: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.regionsUrl(companyId, countryId)}/${regionId}`).pipe(
      tap(() => {
        const current = this._regions();
        this._regions.set(current.filter(r => r.id !== regionId));
      }),
      catchError(err => {
        this._error.set('Error al eliminar la región');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableRegion(companyId: string, countryId: string, regionId: string): Observable<CompanyRegion> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.patch<CompanyRegion>(`${this.regionsUrl(companyId, countryId)}/${regionId}`, {}).pipe(
      tap(updated => {
        const current = this._regions();
        this._regions.set(current.map(r => (r.id === regionId ? updated : r)));
      }),
      catchError(err => {
        this._error.set('Error al reactivar la región');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
