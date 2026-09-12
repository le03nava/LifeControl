import { inject, Injectable, signal } from '@angular/core';
import { Country } from '../../companies/companies/models/company.models';
import { Observable, of, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CountryService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _countries = signal<Country[]>([]);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly countries = this._countries.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/countries`;
  }

  getCountries(force = false): Observable<Country[]> {
    if (!force && this._loaded()) {
      return of(this._countries());
    }
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<Country[]>(this.apiUrl).pipe(
      tap((countries) => {
        this._countries.set(countries);
        this._loaded.set(true);
      }),
      catchError((err) => {
        this._loaded.set(false);
        this._error.set('Error al cargar los países');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
