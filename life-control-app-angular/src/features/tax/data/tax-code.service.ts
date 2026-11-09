import { inject, Injectable, signal } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';
import { TaxCode } from './tax-code.models';

/**
 * Reads the platform tax-code catalog (`GET /api/tax-codes`) through the same cached-signal shape
 * `CurrencyService` uses: one read per session unless a caller forces a refetch.
 *
 * The sibling chosen deliberately is the **currency** catalog and not `MeasureUnitService`'s plain read,
 * because the two catalogs are the same kind of thing: platform reference data seeded by migration, read
 * only, addressed at `/api/{plural}` and filtered to the enabled rows by the endpoint itself (S4). The
 * product form is opened and closed many times per session and the catalog cannot change under it, so a
 * per-screen read would be a second lifetime to reason about for no freshness — the argument that made
 * `MeasureUnitService` plain (one dialog, one unit list) argues the other way here.
 *
 * The endpoint answers the enabled codes only, and that is exactly what the product form may offer: a
 * retired code is not a choice a form should present. No `includeDisabled` here, on purpose.
 */
@Injectable({
  providedIn: 'root',
})
export class TaxCodeService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _taxCodes = signal<TaxCode[]>([]);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly taxCodes = this._taxCodes.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/tax-codes`;
  }

  getTaxCodes(force = false): Observable<TaxCode[]> {
    if (!force && this._loaded()) {
      return of(this._taxCodes());
    }
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<TaxCode[]>(this.apiUrl).pipe(
      tap((taxCodes) => {
        this._taxCodes.set(taxCodes);
        this._loaded.set(true);
      }),
      catchError((err) => {
        this._loaded.set(false);
        this._error.set('Error al cargar los códigos de impuesto');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
