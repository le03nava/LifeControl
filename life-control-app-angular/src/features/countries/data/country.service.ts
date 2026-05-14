import { inject, Injectable, signal } from '@angular/core';
import { Country } from '../../companies/models/company.models';
import { Observable, of, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CountryService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private _countries = signal<Country[]>([]);
  private _loading = signal(false);
  private _loaded = signal(false);
  private _error = signal<string | null>(null);

  readonly countries = this._countries.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/countries`;
  }

  getCountries(): Observable<Country[]> {
    if (this._loaded()) {
      return of(this._countries());
    }
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<Country[]>(this.apiUrl).pipe(
      tap(countries => {
        this._countries.set(countries);
        this._loaded.set(true);
      }),
      catchError(err => {
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
