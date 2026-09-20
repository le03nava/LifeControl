import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import type { StoreChain, StoreLocationSummary } from '../models/store-location-summary.models';

/**
 * HTTP access to the enabled locations of one store.
 *
 * The list read is nested under the full company → country → region → zone →
 * store chain, and `store-locations` hangs directly off `stores` (NOT off a
 * store zone).
 */
@Injectable({
  providedIn: 'root',
})
export class StoreLocationLookupService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private storeLocationsUrl(chain: StoreChain): string {
    return `${this.configService.apiUrl}/companies/${chain.companyId}/countries/${chain.companyCountryId}/regions/${chain.regionId}/zones/${chain.zoneId}/stores/${chain.storeId}/store-locations`;
  }

  /** List the enabled locations of a store, ordered by area, zone and location. */
  getStoreLocations(chain: StoreChain): Observable<StoreLocationSummary[]> {
    return this.http.get<StoreLocationSummary[]>(this.storeLocationsUrl(chain));
  }
}
