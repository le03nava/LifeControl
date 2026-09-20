import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { catchError, Observable, of, throwError } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { SKIP_ERROR_NOTIFICATION } from '@shared/data/skip-error-notification';
import type { StoreChain, StoreInventorySettings } from '../models/store-location-summary.models';

/**
 * HTTP access to a store's inventory settings (its receiving and sales
 * locations).
 *
 * `getSettings` treats an unconfigured store as a normal state: the backend
 * answers **404** when the store has no settings yet, and this method maps that
 * exact status to `null` instead of an error. The request also opts out of the
 * global error toast (see {@link SKIP_ERROR_NOTIFICATION}) so the expected 404
 * never surfaces as a red notification. Every other error is rethrown unchanged.
 */
@Injectable({
  providedIn: 'root',
})
export class StoreInventorySettingsService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private settingsUrl(chain: StoreChain): string {
    return `${this.configService.apiUrl}/companies/${chain.companyId}/countries/${chain.companyCountryId}/regions/${chain.regionId}/zones/${chain.zoneId}/stores/${chain.storeId}/inventory-settings`;
  }

  /**
   * Read the store's inventory settings. A store that has never been configured
   * answers 404, which is mapped to `null`; any other error is rethrown.
   */
  getSettings(chain: StoreChain): Observable<StoreInventorySettings | null> {
    return this.http
      .get<StoreInventorySettings>(this.settingsUrl(chain), {
        context: new HttpContext().set(SKIP_ERROR_NOTIFICATION, true),
      })
      .pipe(
        catchError((error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 404) {
            return of(null);
          }
          return throwError(() => error);
        }),
      );
  }
}
