import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { catchError, finalize, Observable, tap, throwError } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { ActivityLogFilters, ActivityLogPage } from '../models/activity-log.models';

/** Copy shown when the read fails; the page maps its own resource error the same way. */
const LOAD_ERROR_MESSAGE = 'Error al cargar la actividad registrada';

/** The six filter names the endpoint accepts, exactly. */
const FILTER_KEYS = ['from', 'to', 'process', 'event', 'userId', 'httpMethod'] as const;

/**
 * Read-only access to the audit trail (`GET /api/activity-logs`).
 *
 * The endpoint is `@PreAuthorize("hasRole('lc-admin')")`, so the screen that
 * calls this service is admin-only too. It answers a Spring Data `Page`, which
 * is why pagination is **server-side**: `page` and `size` travel on every read
 * and the answer's `totalElements` is what the paginator's `length` renders.
 *
 * The shape mirrors `UsersAdminService`: `ConfigService.apiUrl` builds the base,
 * the last page read is exposed as a signal, and `loading`/`error` are signals of
 * their own. `HttpParams` carries the filters and **only the non-empty ones are
 * ever sent** — a blank value means "no filter", not "filter by blank".
 */
@Injectable({
  providedIn: 'root',
})
export class ActivityLogService {
  private readonly http = inject(HttpClient);
  private readonly configService = inject(ConfigService);

  private readonly _page = signal<ActivityLogPage | null>(null);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** The last page read, or `null` before the first successful read. */
  readonly page = this._page.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/activity-logs`;
  }

  /**
   * One page of the trail, filtered by the six accepted filters.
   *
   * Every filter is optional and each one is trimmed before it is sent; a blank
   * one is omitted from the query entirely.
   */
  search(
    page: number,
    size: number,
    filters: ActivityLogFilters = {},
  ): Observable<ActivityLogPage> {
    this._loading.set(true);
    this._error.set(null);

    return this.http
      .get<ActivityLogPage>(this.baseUrl, { params: this.buildParams(page, size, filters) })
      .pipe(
        tap((result) => this._page.set(result)),
        catchError((err) => {
          this._error.set(LOAD_ERROR_MESSAGE);
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  private buildParams(page: number, size: number, filters: ActivityLogFilters): HttpParams {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    for (const key of FILTER_KEYS) {
      const value = filters[key]?.trim();
      if (value) {
        params = params.set(key, value);
      }
    }

    return params;
  }
}
