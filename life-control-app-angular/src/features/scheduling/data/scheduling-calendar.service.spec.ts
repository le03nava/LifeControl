import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { firstValueFrom, Observable, of } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { SKIP_ERROR_NOTIFICATION } from '@shared/data';
import { SchedulingActivityService } from './scheduling-activity.service';
import {
  MAX_CATALOGUE_PAGES,
  MAX_CATALOGUE_PAGE_SIZE,
  SLOT_MATERIALIZATION_CONCURRENCY,
  SchedulingCalendarService,
  SchedulingCatalogueTooLargeError,
} from './scheduling-calendar.service';
import { Page, SchedulingActivity } from '../models/scheduling-activity.models';
import { SchedulingCalendarEntry, SchedulingSlot } from '../models/scheduling-calendar.models';

describe('SchedulingCalendarService', () => {
  let service: SchedulingCalendarService;
  let httpMock: HttpTestingController;
  let activityService: { listActivities: ReturnType<typeof vi.fn> };

  const base = 'http://api.test/api/scheduling';
  const slotsUrl = `${base}/slots`;
  const calendarUrl = `${base}/calendar`;
  const from = '2026-09-28T00:00:00';
  const to = '2026-10-05T00:00:00';

  // String matchers compare against the URL *with* params, so a param-carrying
  // endpoint is matched on its bare URL instead.
  const isSlots = (request: { url: string }): boolean => request.url === slotsUrl;
  const isCalendar = (request: { url: string }): boolean => request.url === calendarUrl;

  const activity = (id: string): SchedulingActivity => ({
    id,
    companyStoreId: 'store-1',
    userId: null,
    activityName: `Actividad ${id}`,
    description: null,
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 0,
    createdAt: '2026-09-28T12:00:00',
    updatedAt: '2026-09-28T12:00:00',
  });

  const pageOf = (
    ids: string[],
    {
      number = 0,
      totalPages = 1,
      totalElements = ids.length,
      size = MAX_CATALOGUE_PAGE_SIZE,
    }: { number?: number; totalPages?: number; totalElements?: number; size?: number } = {},
  ): Page<SchedulingActivity> => ({
    content: ids.map(activity),
    totalElements,
    totalPages,
    size,
    number,
    first: number === 0,
    last: number === totalPages - 1,
    empty: ids.length === 0,
  });

  const slot = (id: string): SchedulingSlot => ({
    id,
    activityId: 'activity-a',
    startAt: '2026-09-28T09:00:00',
    endAt: '2026-09-28T10:00:00',
    capacity: 8,
    booked: 0,
    available: 8,
    status: 'Available',
    enabled: true,
  });

  const calendarEntry = (slotId: string): SchedulingCalendarEntry => ({
    slotId,
    activityId: 'activity-a',
    activityName: 'Yoga',
    activityEnabled: true,
    startAt: '2026-09-28T09:00:00',
    endAt: '2026-09-28T10:00:00',
    capacity: 8,
    booked: 0,
    available: 8,
    status: 'Available',
    appointments: [],
  });

  function setup(enabledActivityIds: string[] = ['activity-a']): void {
    activityService = {
      listActivities: vi.fn().mockReturnValue(of(pageOf(enabledActivityIds))),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        SchedulingCalendarService,
        { provide: ConfigService, useValue: { apiUrl: 'http://api.test/api' } },
        { provide: SchedulingActivityService, useValue: activityService },
      ],
    });
    service = TestBed.inject(SchedulingCalendarService);
    httpMock = TestBed.inject(HttpTestingController);
  }

  /** Subscribes without awaiting, so a fire-and-forget read cannot reject unhandled. */
  function drain(observable: Observable<unknown>): void {
    observable.subscribe({ next: () => undefined, error: () => undefined });
  }

  /**
   * Lets the materialization chain settle from `mergeMap` into the projection
   * read. The hand-off is deferred by a microtask under zone.js, so the calendar
   * request does not exist in the same tick the last `GET /slots` is flushed.
   */
  const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    setup();
    expect(service).toBeTruthy();
  });
  describe('getSlots', () => {
    it('should GET the single-activity range with exactly the documented query params', async () => {
      setup();
      const promise = firstValueFrom(service.getSlots('activity-a', from, to));

      const req = httpMock.expectOne(isSlots);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('activityId')).toBe('activity-a');
      expect(req.request.params.get('from')).toBe(from);
      expect(req.request.params.get('to')).toBe(to);
      expect(req.request.params.keys().sort()).toEqual(['activityId', 'from', 'to']);
      req.flush([slot('slot-1')]);

      const slots = await promise;
      expect(slots).toEqual([slot('slot-1')]);
    });

    it('should send a store-less request: the endpoint takes one activityId, never a storeId', async () => {
      setup();
      drain(service.getSlots('activity-a', from, to));

      const req = httpMock.expectOne(isSlots);
      expect(req.request.params.has('storeId')).toBe(false);
      req.flush([]);
    });
  });

  describe('getCalendar', () => {
    it('should GET the store range with `to` exclusive and no activityId when unfiltered', async () => {
      setup();
      const promise = firstValueFrom(service.getCalendar('store-1', from, to));

      const req = httpMock.expectOne(isCalendar);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('storeId')).toBe('store-1');
      // `to` is the *next* Monday 00:00: exclusive, so the client never trims a
      // boundary row (D53).
      expect(req.request.params.get('from')).toBe('2026-09-28T00:00:00');
      expect(req.request.params.get('to')).toBe('2026-10-05T00:00:00');
      expect(req.request.params.has('activityId')).toBe(false);
      req.flush([calendarEntry('slot-1')]);

      const entries = await promise;
      // The projection answers a bare array, not a `Page` wrapper.
      expect(Array.isArray(entries)).toBe(true);
      expect(entries).toHaveLength(1);
    });

    it('should forward the activityId when the filter is active', async () => {
      setup();
      drain(service.getCalendar('store-1', from, to, 'activity-b'));

      const req = httpMock.expectOne(isCalendar);
      expect(req.request.params.get('activityId')).toBe('activity-b');
      req.flush([]);
    });
  });

  describe('listEnabledActivities', () => {
    it('should reuse the activity service at the server page cap and enabled-only', async () => {
      setup(['activity-a', 'activity-b']);
      const promise = firstValueFrom(service.listEnabledActivities('store-1'));

      expect(activityService.listActivities).toHaveBeenCalledWith(
        'store-1',
        0,
        MAX_CATALOGUE_PAGE_SIZE,
        false,
      );
      const activities = await promise;
      expect(activities.map((a) => a.id)).toEqual(['activity-a', 'activity-b']);
    });

    it('should read every page of a multi-page catalogue (F1)', async () => {
      setup([]);
      activityService.listActivities.mockImplementation((_storeId, page) =>
        of(
          page === 0
            ? pageOf(['activity-a'], { number: 0, totalPages: 2, totalElements: 2 })
            : pageOf(['activity-b'], { number: 1, totalPages: 2, totalElements: 2 }),
        ),
      );

      const activities = await firstValueFrom(service.listEnabledActivities('store-1'));

      // Truncating at page 0 would drop `activity-b` and render its week as empty.
      expect(activities.map((a) => a.id)).toEqual(['activity-a', 'activity-b']);
      expect(activityService.listActivities).toHaveBeenCalledWith(
        'store-1',
        1,
        MAX_CATALOGUE_PAGE_SIZE,
        false,
      );
    });

    it('should fail closed, without reading further, when the catalogue exceeds the page cap (F1)', () => {
      setup([]);
      activityService.listActivities.mockReturnValue(
        of(
          pageOf(['activity-a'], {
            totalPages: MAX_CATALOGUE_PAGES + 1,
            totalElements: (MAX_CATALOGUE_PAGES + 1) * MAX_CATALOGUE_PAGE_SIZE,
          }),
        ),
      );

      let caught: unknown;
      service.listEnabledActivities('store-1').subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      expect(caught).toBeInstanceOf(SchedulingCatalogueTooLargeError);
      // The cap is a refusal, not a licence to keep paging.
      expect(activityService.listActivities).toHaveBeenCalledTimes(1);
    });
  });

  describe('loadCalendarWeek', () => {
    it('should fan out one GET /slots per enabled activity, then read the projection', async () => {
      setup(['activity-a', 'activity-b']);
      const promise = firstValueFrom(service.loadCalendarWeek({ storeId: 'store-1', from, to }));

      const slotRequests = httpMock.match(isSlots);
      expect(slotRequests).toHaveLength(2);
      expect(slotRequests.map((r) => r.request.params.get('activityId')).sort()).toEqual([
        'activity-a',
        'activity-b',
      ]);
      // The projection must not be read while materialization is still open.
      expect(httpMock.match(isCalendar)).toHaveLength(0);

      for (const req of slotRequests) {
        req.flush([slot(`slot-${req.request.params.get('activityId')}`)]);
      }
      await tick();

      const calendarRequest = httpMock.expectOne(isCalendar);
      expect(calendarRequest.request.params.get('storeId')).toBe('store-1');
      expect(calendarRequest.request.params.get('from')).toBe(from);
      expect(calendarRequest.request.params.get('to')).toBe(to);
      expect(calendarRequest.request.params.has('activityId')).toBe(false);
      calendarRequest.flush([calendarEntry('slot-1')]);

      const entries = await promise;
      expect(entries.map((e) => e.slotId)).toEqual(['slot-1']);
    });

    it('should never hold more than the concurrency cap in flight', async () => {
      const ids = ['a', 'b', 'c', 'd', 'e', 'f'];
      setup(ids);
      drain(service.loadCalendarWeek({ storeId: 'store-1', from, to }));

      expect(SLOT_MATERIALIZATION_CONCURRENCY).toBeLessThan(ids.length);

      // `match()` removes what it returns, so the requests it yields are kept and
      // flushed rather than re-queried. The cap bounds every observation, not just
      // the initial batch: draining a request admits at most one more.
      const observed = new Set<string>();
      let round = httpMock.match(isSlots);
      expect(round).toHaveLength(SLOT_MATERIALIZATION_CONCURRENCY);

      let guard = 0;
      while (round.length > 0 && guard < 20) {
        expect(round.length).toBeLessThanOrEqual(SLOT_MATERIALIZATION_CONCURRENCY);
        for (const req of round) {
          observed.add(req.request.params.get('activityId') as string);
          req.flush([]);
        }
        await tick();
        round = httpMock.match(isSlots);
        guard += 1;
      }

      expect(observed.size).toBe(ids.length);
      expect(round).toHaveLength(0);
      await tick();
      httpMock.expectOne(isCalendar).flush([]);
    });

    it('should issue exactly one request per listed activity', async () => {
      setup(['a', 'a', 'b']);
      drain(service.loadCalendarWeek({ storeId: 'store-1', from, to }));

      const requests = httpMock.match(isSlots);
      // The fan-out is driven by the activity list as the server returned it; the
      // spec pins that it issues exactly one call per listed id.
      expect(requests.map((r) => r.request.params.get('activityId'))).toEqual(['a', 'a', 'b']);
      for (const req of requests) req.flush([]);
      await tick();
      httpMock.expectOne(isCalendar).flush([]);
    });

    it('should narrow the fan-out and the projection to the filtered activity (D63)', async () => {
      setup(['activity-a', 'activity-b']);
      drain(
        service.loadCalendarWeek({
          storeId: 'store-1',
          from,
          to,
          activityId: 'activity-b',
        }),
      );

      const slotRequests = httpMock.match(isSlots);
      expect(slotRequests).toHaveLength(1);
      expect(slotRequests[0].request.params.get('activityId')).toBe('activity-b');
      slotRequests[0].flush([]);
      await tick();

      const calendarRequest = httpMock.expectOne(isCalendar);
      expect(calendarRequest.request.params.get('activityId')).toBe('activity-b');
      calendarRequest.flush([]);
    });

    it('should materialize nothing for a filter that is not among the enabled activities, and still read that activity', async () => {
      setup(['activity-a']);
      drain(
        service.loadCalendarWeek({
          storeId: 'store-1',
          from,
          to,
          activityId: 'retired-activity',
        }),
      );

      // D50: minting new slots for a retired activity is the one thing the rule
      // refuses, so the fan-out is empty and only the projection runs.
      expect(httpMock.match(isSlots)).toHaveLength(0);
      await tick();
      const calendarRequest = httpMock.expectOne(isCalendar);
      expect(calendarRequest.request.params.get('activityId')).toBe('retired-activity');
      calendarRequest.flush([]);
    });

    it('should read the projection without any materialization when the store has no enabled activity', async () => {
      setup([]);
      drain(service.loadCalendarWeek({ storeId: 'store-1', from, to }));

      expect(httpMock.match(isSlots)).toHaveLength(0);
      await tick();
      httpMock.expectOne(isCalendar).flush([]);
    });

    it('should materialize the activities of every catalogue page (F1)', async () => {
      setup([]);
      activityService.listActivities.mockImplementation((_storeId, page) =>
        of(
          page === 0
            ? pageOf(['activity-a'], { number: 0, totalPages: 2, totalElements: 2 })
            : pageOf(['activity-b'], { number: 1, totalPages: 2, totalElements: 2 }),
        ),
      );
      drain(service.loadCalendarWeek({ storeId: 'store-1', from, to }));
      await tick();

      const slotRequests = httpMock.match(isSlots);
      expect(slotRequests.map((r) => r.request.params.get('activityId')).sort()).toEqual([
        'activity-a',
        'activity-b',
      ]);
      for (const req of slotRequests) req.flush([]);
      await tick();
      httpMock.expectOne(isCalendar).flush([]);
    });

    it('should fail closed at the catalogue cap and never read the projection (F1)', async () => {
      setup([]);
      activityService.listActivities.mockReturnValue(
        of(
          pageOf(['activity-a'], {
            totalPages: MAX_CATALOGUE_PAGES + 1,
            totalElements: (MAX_CATALOGUE_PAGES + 1) * MAX_CATALOGUE_PAGE_SIZE,
          }),
        ),
      );

      let caught: unknown;
      service.loadCalendarWeek({ storeId: 'store-1', from, to }).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });
      await tick();

      expect(caught).toBeInstanceOf(SchedulingCatalogueTooLargeError);
      // No silent truncation: neither a partial fan-out nor a truncated projection.
      expect(httpMock.match(isSlots)).toHaveLength(0);
      expect(httpMock.match(isCalendar)).toHaveLength(0);
    });

    it('should fail closed when any GET /slots fails: no projection request, error propagated (D59)', async () => {
      setup(['activity-a', 'activity-b']);
      let caught: unknown;
      service.loadCalendarWeek({ storeId: 'store-1', from, to }).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      const slotRequests = httpMock.match(isSlots);
      expect(slotRequests).toHaveLength(2);
      slotRequests[0].flush({}, { status: 500, statusText: 'Server Error' });
      await tick();

      expect(caught).toBeTruthy();
      // A partial week would be indistinguishable from "that activity has no
      // availability this week", so the whole read must not read the projection.
      expect(httpMock.match(isCalendar)).toHaveLength(0);
    });

    it('should surface a projection failure too', async () => {
      setup(['activity-a']);
      let caught: unknown;
      service.loadCalendarWeek({ storeId: 'store-1', from, to }).subscribe({
        error: (err: unknown) => {
          caught = err;
        },
      });

      httpMock.expectOne(isSlots).flush([]);
      await tick();
      httpMock.expectOne(isCalendar).flush({}, { status: 400, statusText: 'Bad Request' });

      expect(caught).toBeTruthy();
    });
  });

  describe('error-notification context (F3)', () => {
    it('should silence the global toast on the fan-out GET /slots calls', () => {
      setup();
      drain(service.getSlots('activity-a', from, to));

      const req = httpMock.expectOne(isSlots);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(true);
      req.flush([]);
    });

    it('should leave the projection request untouched, since the page owns that failure', () => {
      setup();
      drain(service.getCalendar('store-1', from, to));

      const req = httpMock.expectOne(isCalendar);
      expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(false);
      req.flush([]);
    });
  });
});
