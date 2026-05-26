import { inject, Injectable, signal } from '@angular/core';
import { CompanyCountry, CompanyCountryRequest } from '../models/country.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyCountryService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private _assignedCountries = signal<CompanyCountry[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  readonly assignedCountries = this._assignedCountries.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private countriesUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries`;
  }

  getCountries(companyId: string): Observable<CompanyCountry[]> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<CompanyCountry[]>(this.countriesUrl(companyId)).pipe(
      tap(countries => this._assignedCountries.set(countries)),
      catchError(err => {
        this._error.set('Error al cargar los países de la empresa');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  addCountry(companyId: string, request: CompanyCountryRequest): Observable<CompanyCountry> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<CompanyCountry>(this.countriesUrl(companyId), request).pipe(
      tap(cc => {
        const current = this._assignedCountries();
        this._assignedCountries.set([...current, cc]);
      }),
      catchError(err => {
        if (err.status === 409) {
          this._error.set('Este país ya está asignado a esta empresa');
        } else {
          this._error.set('Error al agregar país');
        }
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  removeCountry(companyId: string, companyCountryId: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.countriesUrl(companyId)}/${companyCountryId}`).pipe(
      tap(() => {
        const current = this._assignedCountries();
        this._assignedCountries.set(current.filter(cc => cc.id !== companyCountryId));
      }),
      catchError(err => {
        this._error.set('Error al eliminar país');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
