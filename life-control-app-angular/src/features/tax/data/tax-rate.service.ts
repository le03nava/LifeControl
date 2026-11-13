import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { TaxRate } from './tax-rate.models';

/**
 * A company's tax-rate book (`/api/companies/{companyId}/tax-rates`, issue #858).
 *
 * **Why this one does not cache, unlike `TaxCodeService`.** That catalog is platform reference data seeded by
 * migration and read-only, so one read per session cannot rot. A tax rate is configuration this same feature
 * writes: declaring, superseding and retiring all change what the list must show, and a cached read would make
 * the operator's own write invisible for the rest of the session. The list therefore reads on every parameter
 * change, and the write routes re-read when they are done.
 *
 * **Why the company is a parameter and never inferred here.** The endpoints nest the tenant in the path —
 * `TenantScopeFilter` verifies it against the caller's claims — so the id has to come from the route or the
 * session's selected company, which is the caller's decision and not this service's.
 *
 * The error is kept as a readable sentence beside the rethrown failure, in the shape `TaxCodeService` uses: the
 * page decides how to render it and never invents a message of its own.
 */
@Injectable({
  providedIn: 'root',
})
export class TaxRateService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _error = signal<string | null>(null);

  readonly error = this._error.asReadonly();

  /** The tenant-scoped root: every call nests under a company, never beside it. */
  get apiUrl(): string {
    return `${this.configService.apiUrl}/companies`;
  }

  /**
   * Reads the company's rates, retired rows included only when `includeDisabled` is true.
   *
   * The answer arrives ordered by tax code and newest `effectiveFrom` first, but **not** grouped by scope: the
   * server cannot, because scope is a nullable region and the screen is what knows how to name it. The page
   * therefore re-sorts for display and derives the "resolves today" mark from these same rows.
   */
  getTaxRates(companyId: string, includeDisabled = false): Observable<TaxRate[]> {
    this._error.set(null);
    return this.http
      .get<TaxRate[]>(`${this.apiUrl}/${companyId}/tax-rates`, {
        params: { includeDisabled: String(includeDisabled) },
      })
      .pipe(
        catchError((err) => {
          this._error.set('Error al cargar las tasas de impuesto');
          return throwError(() => err);
        }),
      );
  }
}
