import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { APPOINTMENT_STATUS_TYPE_NAME } from './scheduling-appointment-status';

/** Raw shape of a status type returned by `GET /api/status-types`. */
interface StatusTypeResponse {
  id: string;
  statusTypeName: string;
}

/** Raw shape of a status returned by `GET /api/statuses`. */
interface StatusResponse {
  id: string;
  statusName: string;
}

interface Page<T> {
  content: T[];
}

/**
 * The page size the status-type read asks for: the app-wide cap
 * `spring.data.web.pageable.max-page-size` (100).
 *
 * This is a deliberate deviation from **D73**, which said `size=1`. The server's
 * `search=` is a case-insensitive **substring** filter over `status_type_name`
 * (`StatusTypeService.java:32-39`), so a `size=1` request can return an unrelated
 * type whose name merely contains `APPOINTMENT` and silently drop the real one —
 * fail-closed, but invisible. Asking for the cap lets the exact match below see
 * every candidate; the parent record notes this as a refinement of D73.
 */
const STATUS_TYPE_PAGE_SIZE = '100';

/**
 * HTTP access to the shared `/api/status-types` and `/api/statuses` catalogues,
 * narrowed to the appointment status family.
 *
 * This is the **third per-feature copy** of the catalogue resolver (D73): the
 * `purchases` `StatusService` and the `sales-orders` `status-transition`
 * component already do this, and no shared status service exists in
 * `@shared/data`. Promoting one is a cross-feature refactor of merged features,
 * so this slice declares the copy instead of widening its own diff.
 */
@Injectable({
  providedIn: 'root',
})
export class SchedulingStatusService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get statusTypesUrl(): string {
    return `${this.configService.apiUrl}/status-types`;
  }

  private get statusesUrl(): string {
    return `${this.configService.apiUrl}/statuses`;
  }

  /**
   * Resolves the appointment status catalogue as a `statusName -> statusId` map.
   *
   * Two authenticated reads: `GET /status-types` (searched for
   * {@link APPOINTMENT_STATUS_TYPE_NAME}, matched **exactly** and
   * case-insensitively client-side, because `search=` is only a substring filter),
   * then `GET /statuses?statusTypeId=`.
   *
   * When no returned type matches the name, the observable **fails** and never
   * resolves an empty map: the caller must distinguish "the catalogue could not
   * be resolved" from "this appointment's status is unknown", and both must offer
   * no transition button (D88, G40).
   *
   * The statuses read returns enabled **and** disabled rows (its
   * `includeDisabled` parameter is accepted and never read, `StatusController.java:45-49`),
   * so no `enabled` filter is applied and no client-side filter is invented.
   */
  loadAppointmentStatusIds(): Observable<ReadonlyMap<string, string>> {
    const typeParams = new HttpParams()
      .set('search', APPOINTMENT_STATUS_TYPE_NAME)
      .set('size', STATUS_TYPE_PAGE_SIZE);

    return this.http
      .get<Page<StatusTypeResponse>>(this.statusTypesUrl, { params: typeParams })
      .pipe(
        switchMap((page) => {
          const match = page.content.find(
            (type) =>
              type.statusTypeName.toUpperCase() === APPOINTMENT_STATUS_TYPE_NAME.toUpperCase(),
          );
          if (!match) {
            throw new Error(`${APPOINTMENT_STATUS_TYPE_NAME} status type not found`);
          }

          const statusParams = new HttpParams().set('statusTypeId', match.id);
          return this.http.get<StatusResponse[]>(this.statusesUrl, { params: statusParams }).pipe(
            map((statuses) => {
              const byName = new Map<string, string>();
              for (const status of statuses) {
                byName.set(status.statusName, status.id);
              }
              return byName;
            }),
          );
        }),
      );
  }
}
