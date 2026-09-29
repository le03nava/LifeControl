import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  Page,
  SchedulingActivity,
  SchedulingActivityRequest,
} from '../models/scheduling-activity.models';

/**
 * HTTP access to `/api/scheduling/activities`.
 *
 * Flat and store-derived: the endpoints are addressed by activity id and the
 * store travels as a query or body parameter, so no store segment is in the path.
 *
 * Follows `product.service.ts`'s error-state convention: the `loading` and
 * `error` signals are owned by the mutations (create / update / enable /
 * disable), while the list and the by-id read are plain `Observable` reads whose
 * error state belongs to the page's `rxResource`. Returning observables (never
 * promises) is what lets `rxResource` and the subscriptions drive them.
 */
@Injectable({
  providedIn: 'root',
})
export class SchedulingActivityService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/scheduling/activities`;
  }

  /**
   * Paginated list for one store.
   *
   * Sends exactly `storeId`, `includeDisabled`, `page` and `size`: there is no
   * `search` param on the endpoint, and the ordering is the backend's own
   * `activityName ASC`, so no `sort` is sent either.
   */
  listActivities(
    storeId: string,
    page = 0,
    size = 12,
    includeDisabled = false,
  ): Observable<Page<SchedulingActivity>> {
    const params = new HttpParams()
      .set('storeId', storeId)
      .set('includeDisabled', String(includeDisabled))
      .set('page', page.toString())
      .set('size', size.toString());

    return this.http.get<Page<SchedulingActivity>>(this.apiUrl, { params });
  }

  getActivityById(id: string): Observable<SchedulingActivity> {
    return this.http.get<SchedulingActivity>(`${this.apiUrl}/${id}`);
  }

  createActivity(request: SchedulingActivityRequest): Observable<SchedulingActivity> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<SchedulingActivity>(this.apiUrl, request).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al crear la actividad');
        return throwError(() => err);
      }),
    );
  }

  updateActivity(id: string, request: SchedulingActivityRequest): Observable<SchedulingActivity> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<SchedulingActivity>(`${this.apiUrl}/${id}`, request).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al actualizar la actividad');
        return throwError(() => err);
      }),
    );
  }

  /** Re-enables a soft-deleted activity: `PATCH /{id}/enable` with no body. */
  enableActivity(id: string): Observable<SchedulingActivity> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.patch<SchedulingActivity>(`${this.apiUrl}/${id}/enable`, null).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al reactivar la actividad');
        return throwError(() => err);
      }),
    );
  }

  /**
   * Soft delete: the backend sets `enabled = false`. It removes nothing and does
   * not touch the activity's slots, which is why the UI says "deshabilitar".
   */
  disableActivity(id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.apiUrl}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError((err) => {
        this._error.set('Error al deshabilitar la actividad');
        return throwError(() => err);
      }),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
