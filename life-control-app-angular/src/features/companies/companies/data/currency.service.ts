import { inject, Injectable, signal } from '@angular/core';
import { Currency } from './currency.models';
import { Observable, of, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

/**
 * Reads the platform currency catalog (`GET /api/currencies`) through the same cached-signal shape
 * `CountryService` uses: one read per session unless a caller forces a refetch.
 *
 * The endpoint answers the enabled codes only, and that is exactly what the company form may offer (D2):
 * a retired code would be refused on save by `CompanyService.resolveCurrency` with a 400, so offering it
 * would turn a data question into a validation error. No `includeDisabled` here, on purpose.
 */
@Injectable({
  providedIn: 'root',
})
export class CurrencyService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _currencies = signal<Currency[]>([]);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly currencies = this._currencies.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/currencies`;
  }

  getCurrencies(force = false): Observable<Currency[]> {
    if (!force && this._loaded()) {
      return of(this._currencies());
    }
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<Currency[]>(this.apiUrl).pipe(
      tap((currencies) => {
        this._currencies.set(currencies);
        this._loaded.set(true);
      }),
      catchError((err) => {
        this._loaded.set(false);
        this._error.set('Error al cargar las monedas');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
