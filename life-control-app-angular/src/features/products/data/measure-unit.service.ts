import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { MeasureUnit } from '../models/product-variant.models';

/**
 * Sole owner of the measure-unit catalog read.
 *
 * `GET /api/measure-units` answers with a **plain array** (not a page) of the enabled
 * units, which is exactly the set the variant picker offers: no `includeDisabled`, no
 * paging, and no client-side filtering by `unitType` (S4).
 *
 * There is deliberately no cache here. The endpoint is already cached server-side, so a
 * client-side copy would only add a second lifetime to reason about, and the catalog is
 * read once per variant dialog.
 */
@Injectable({
  providedIn: 'root',
})
export class MeasureUnitService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  /** Base of the measure-unit catalog routes. */
  private get measureUnitsUrl(): string {
    return `${this.configService.apiUrl}/measure-units`;
  }

  /** Reads the enabled catalog the variant picker is built from. */
  getMeasureUnits(): Observable<MeasureUnit[]> {
    return this.http.get<MeasureUnit[]>(this.measureUnitsUrl);
  }
}
