import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Observable, from, of, throwError } from 'rxjs';
import {
  concatMap,
  defaultIfEmpty,
  ignoreElements,
  map,
  mergeMap,
  switchMap,
  toArray,
} from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { SKIP_ERROR_NOTIFICATION } from '@shared/data';
import { SchedulingActivityService } from './scheduling-activity.service';
import { SchedulingActivity } from '../models/scheduling-activity.models';
import {
  SchedulingCalendarEntry,
  SchedulingCalendarWeekRequest,
  SchedulingSlot,
} from '../models/scheduling-calendar.models';

/**
 * How many `GET /slots` requests the fan-out may hold open at once (D55).
 *
 * `GET /slots` takes a **single** `activityId` and is this domain's only
 * materializing read (D16, G19), so one week change costs N+1 requests where N is
 * the store's enabled activities. Firing them all at once would open up to 100
 * sockets against the gateway for a single view; the cap keeps the request count
 * identical and the concurrency honest.
 */
export const SLOT_MATERIALIZATION_CONCURRENCY = 4;

/**
 * How many activities one catalogue page may carry.
 *
 * Pinned to `spring.data.web.pageable.max-page-size` (100, `application.properties:46`):
 * the server clamps a larger `size`, so asking for more would be a silent under-read
 * rather than a bigger page.
 */
export const MAX_CATALOGUE_PAGE_SIZE = 100;

/**
 * How many catalogue pages the week read will page through before it fails closed.
 *
 * `GET /activities` orders by `activityName ASC` and the server clamps `size` to
 * 100, so a store with more enabled activities than this needs more pages than the
 * week read will open. Reading only page 0 there would silently drop every later
 * activity, and its not-yet-materialized slots would render as nothing — the exact
 * state D59 exists to prevent. The read therefore pages to the end and **refuses**
 * a catalogue past this cap instead of truncating it.
 */
export const MAX_CATALOGUE_PAGES = 10;

/**
 * Thrown when the store's enabled-activity catalogue is larger than
 * {@link MAX_CATALOGUE_PAGES} allows the week read to page through.
 *
 * A dedicated type, not an `HttpErrorResponse`: nothing failed on the wire, the
 * catalogue is simply too large to prepare completely, and the page renders this
 * message rather than a generic transport error.
 */
export class SchedulingCatalogueTooLargeError extends Error {
  constructor(
    readonly storeId: string,
    readonly totalElements: number,
    readonly pageCap: number,
  ) {
    super(
      `La tienda tiene ${totalElements} actividades activas, más de las ${pageCap * MAX_CATALOGUE_PAGE_SIZE} que el calendario puede preparar de una vez. Deshabilitá las actividades que ya no uses y volvé a intentar.`,
    );
    this.name = 'SchedulingCatalogueTooLargeError';
  }
}

/**
 * HTTP access to the calendar surface of `/api/scheduling`.
 *
 * The projection (`GET /calendar`) **never materializes** (D33, G19), so a week
 * read is a chain, not one request: read the store's enabled activities, fan out
 * one materializing `GET /slots` per activity over the visible range, and only
 * then read the projection. {@link loadCalendarWeek} owns that ordering and is
 * what the page calls.
 *
 * The activity list is read through the existing
 * {@link SchedulingActivityService} so this service introduces no second
 * catalogue contract.
 */
@Injectable({
  providedIn: 'root',
})
export class SchedulingCalendarService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly activityService = inject(SchedulingActivityService);

  private get slotsUrl(): string {
    return `${this.configService.apiUrl}/scheduling/slots`;
  }

  private get calendarUrl(): string {
    return `${this.configService.apiUrl}/scheduling/calendar`;
  }

  /**
   * Materializes (idempotently) and returns one activity's slots for `[from, to)`.
   *
   * One `activityId` per call is mandatory on this endpoint, and calling it is a
   * write: it inserts the slots the activity's availability implies. `from` is
   * inclusive and `to` exclusive.
   */
  getSlots(activityId: string, from: string, to: string): Observable<SchedulingSlot[]> {
    const params = new HttpParams().set('activityId', activityId).set('from', from).set('to', to);
    // The fan-out may hold up to SLOT_MATERIALIZATION_CONCURRENCY requests open, so
    // one failed week could raise several duplicate toasts on top of the page's own
    // banner and retry. The page owns this failure; the interceptor's toast is
    // silenced here and nowhere else, and the error is still rethrown.
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    return this.http.get<SchedulingSlot[]>(this.slotsUrl, { params, context });
  }

  /**
   * The store's projection over `[from, to)`: a **bare array**, one entry per
   * already-materialized slot, every entry whatever its activity's `enabled`
   * flag says (D37).
   *
   * `activityId` narrows the projection to one activity; it is omitted when the
   * filter is off so the week is complete.
   */
  getCalendar(
    storeId: string,
    from: string,
    to: string,
    activityId?: string | null,
  ): Observable<SchedulingCalendarEntry[]> {
    let params = new HttpParams().set('storeId', storeId).set('from', from).set('to', to);
    if (activityId) {
      params = params.set('activityId', activityId);
    }
    return this.http.get<SchedulingCalendarEntry[]>(this.calendarUrl, { params });
  }

  /**
   * The store's **enabled** activities, read across **every** page.
   *
   * `includeDisabled = false` is what keeps the fan-out from minting new slots for
   * a retired activity (D50); a retired activity's already-materialized slots still
   * reach the grid through the projection (D37).
   *
   * Page 0 reports the envelope's `totalPages`, and every later page is read before
   * the fan-out runs: stopping at page 0 would silently omit activities past the
   * first 100, whose unprepared slots would then render as an empty week (F1). A
   * catalogue wider than {@link MAX_CATALOGUE_PAGES} fails closed with
   * {@link SchedulingCatalogueTooLargeError} rather than being truncated.
   */
  listEnabledActivities(storeId: string): Observable<SchedulingActivity[]> {
    return this.activityService.listActivities(storeId, 0, MAX_CATALOGUE_PAGE_SIZE, false).pipe(
      switchMap((firstPage) => {
        const totalPages = Math.max(firstPage.totalPages, 1);
        if (totalPages > MAX_CATALOGUE_PAGES) {
          return throwError(
            () =>
              new SchedulingCatalogueTooLargeError(
                storeId,
                firstPage.totalElements,
                MAX_CATALOGUE_PAGES,
              ),
          );
        }
        if (totalPages === 1) {
          return of(firstPage.content);
        }

        const remainingPages = Array.from(
          { length: totalPages - 1 },
          (_unused, index) => index + 1,
        );
        return from(remainingPages).pipe(
          concatMap((page) =>
            this.activityService.listActivities(storeId, page, MAX_CATALOGUE_PAGE_SIZE, false),
          ),
          map((page) => page.content),
          toArray(),
          map((pages) => [...firstPage.content, ...pages.flat()]),
        );
      }),
    );
  }

  /**
   * The whole week read: materialize first, project second.
   *
   * Self-contained on purpose — it reads the activity list itself and does not
   * depend on any other resource having already emitted. Fail-closed: if **any**
   * `GET /slots` fails, the returned stream errors and the projection is never
   * read, so the page cannot render a week that is silently missing one
   * activity's slots (D59).
   *
   * When `activityId` is set the fan-out narrows to that activity (D63). A filter
   * naming an activity that is not among the store's enabled ones materializes
   * nothing and still reads that activity's projection: minting new slots for a
   * retired activity is the one thing D50 refuses.
   */
  loadCalendarWeek(request: SchedulingCalendarWeekRequest): Observable<SchedulingCalendarEntry[]> {
    const { storeId, from, to, activityId } = request;

    return this.listEnabledActivities(storeId).pipe(
      switchMap((activities) => {
        const enabledIds = activities.map((activity) => activity.id);
        const toMaterialize = activityId
          ? enabledIds.filter((id) => id === activityId)
          : enabledIds;
        return this.materializeSlots(toMaterialize, from, to).pipe(
          switchMap(() => this.getCalendar(storeId, from, to, activityId)),
        );
      }),
    );
  }

  /**
   * Issues one `GET /slots` per activity, at most
   * {@link SLOT_MATERIALIZATION_CONCURRENCY} in flight, and discards the
   * responses: the materializing read is called for its side effect, and the
   * facts come from the projection afterwards.
   *
   * `mergeMap`'s concurrency argument is the bound; its error semantics are what
   * makes the chain fail-closed, since the first failure unsubscribes the rest and
   * the interposed `switchMap` is never reached.
   *
   * `ignoreElements()` drops the materialization responses, but it also means the
   * stream would complete **without a value**, and `switchMap` only subscribes to
   * the projection on a `next`. `defaultIfEmpty` restores exactly one emission, so
   * the projection is always read — including when the fan-out is empty.
   */
  private materializeSlots(
    activityIds: string[],
    rangeFrom: string,
    rangeTo: string,
  ): Observable<void> {
    return from(activityIds).pipe(
      mergeMap(
        (activityId) => this.getSlots(activityId, rangeFrom, rangeTo),
        SLOT_MATERIALIZATION_CONCURRENCY,
      ),
      ignoreElements(),
      defaultIfEmpty(undefined),
    );
  }
}
