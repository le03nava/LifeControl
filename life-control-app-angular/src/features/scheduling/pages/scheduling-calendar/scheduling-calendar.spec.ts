/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { SchedulingCalendar } from './scheduling-calendar';
import {
  MAX_CATALOGUE_PAGES,
  SchedulingCalendarService,
  SchedulingCatalogueTooLargeError,
} from '../../data/scheduling-calendar.service';
import {
  isoWeekday,
  parseAnchorDate,
  addDays,
  toIsoDate,
} from '../../data/scheduling-calendar-week';
import { SchedulingActivity } from '../../models/scheduling-activity.models';
import {
  SchedulingCalendarEntry,
  SchedulingCalendarWeekRequest,
} from '../../models/scheduling-calendar.models';

describe('SchedulingCalendar', () => {
  let fixture: ComponentFixture<SchedulingCalendar>;
  let component: SchedulingCalendar;
  let calendarService: {
    loadCalendarWeek: ReturnType<typeof vi.fn>;
    listEnabledActivities: ReturnType<typeof vi.fn>;
  };
  let profileService: { getProfile: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let queryParams$: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  const activity = (id: string, activityName = `Actividad ${id}`): SchedulingActivity => ({
    id,
    companyStoreId: 'store-1',
    userId: null,
    activityName,
    description: null,
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 0,
    createdAt: '2026-09-28T12:00:00',
    updatedAt: '2026-09-28T12:00:00',
  });

  const entry = (overrides: Partial<SchedulingCalendarEntry> = {}): SchedulingCalendarEntry => ({
    slotId: 'slot-1',
    activityId: 'activity-1',
    activityName: 'Yoga',
    activityEnabled: true,
    startAt: '2026-09-30T09:00:00',
    endAt: '2026-09-30T10:00:00',
    capacity: 8,
    booked: 1,
    available: 7,
    status: 'Available',
    appointments: [
      {
        id: 'appointment-1',
        userId: 'user-1',
        customerId: null,
        customerName: 'Ana Pérez',
        statusId: 'status-1',
        statusName: 'Confirmed',
        notes: null,
        enabled: true,
      },
    ],
    ...overrides,
  });

  const profile = (companyStoreId: string | null): ProfileResponse => ({
    keycloakUserId: 'user-1',
    username: 'operator',
    email: 'operator@lifecontrol.test',
    firstName: 'Oper',
    lastName: 'Ator',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId,
  });

  interface SetupOptions {
    queryParams?: Record<string, string>;
    profileResult?: ProfileResponse | HttpErrorResponse;
    profileObservable?: Observable<ProfileResponse>;
    weekResult?: SchedulingCalendarEntry[];
    weekObservable?: Observable<SchedulingCalendarEntry[]>;
    weekError?: boolean;
    activities?: SchedulingActivity[];
  }

  function setup(options: SetupOptions = {}): void {
    // Reset usage data on every mock (including the `Date.prototype` spy a spec may
    // have installed), so each test counts only its own calls. Implementations are
    // kept: `clearAllMocks`, not `resetAllMocks`.
    vi.clearAllMocks();
    const initialParams = options.queryParams ?? { date: '2026-09-30', storeId: 'store-1' };
    queryParams$ = new BehaviorSubject(convertToParamMap(initialParams));

    calendarService = {
      loadCalendarWeek: vi
        .fn()
        .mockReturnValue(options.weekObservable ?? of(options.weekResult ?? [entry()])),
      listEnabledActivities: vi
        .fn()
        .mockReturnValue(of(options.activities ?? [activity('activity-1', 'Yoga')])),
    };
    if (options.weekError) {
      calendarService.loadCalendarWeek = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    }

    const profileResult = options.profileResult ?? profile('store-from-profile');
    const profileStream = options.profileObservable;
    router = { navigate: vi.fn().mockResolvedValue(true) };
    profileService = {
      getProfile: profileStream
        ? vi.fn((): Observable<ProfileResponse> => profileStream)
        : profileResult instanceof HttpErrorResponse
          ? vi.fn((): Observable<ProfileResponse> => throwError(() => profileResult))
          : vi.fn((): Observable<ProfileResponse> => of(profileResult)),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingCalendar, NoopAnimationsModule],
      providers: [
        { provide: Router, useValue: router },
        { provide: SchedulingCalendarService, useValue: calendarService },
        { provide: ProfileService, useValue: profileService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap(initialParams),
            },
            queryParamMap: queryParams$.asObservable(),
          },
        },
      ],
    });

    fixture = TestBed.createComponent(SchedulingCalendar);
    component = fixture.componentInstance;
  }

  async function settle(): Promise<void> {
    // Deliberately not `fixture.whenStable()`: a resource held open by a
    // never-emitting observable leaves the fixture unstable forever, so the
    // pending-state specs would time out instead of asserting.
    for (let i = 0; i < 6; i += 1) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function weekRequest(call = 0): SchedulingCalendarWeekRequest {
    return calendarService.loadCalendarWeek.mock.calls[call][0] as SchedulingCalendarWeekRequest;
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should report pending — not empty — in the synchronous pre-load frame (round-2 F2)', () => {
    setup();

    // No `detectChanges`, no flush, no emission: the resource has not started, so
    // `isLoading()` is still `false`. This is the frame the `|| !hasValue()` term
    // of `weekPending` exists for; without it an empty grid would render before the
    // first read starts. Reading the computeds here forces their first evaluation.
    expect(component.weekPending()).toBe(true);
    expect(component.weekWithoutSlots()).toBe(false);
  });

  describe('the four states of D58, rendered distinctly', () => {
    it('should render storePending while the store resolution is in flight', async () => {
      const pending$ = new Subject<ProfileResponse>();
      setup({ queryParams: {}, profileObservable: pending$ });
      await settle();

      expect(text()).toContain('Resolviendo la tienda');
      expect(text()).not.toContain('No pudimos determinar la tienda');
      expect(text()).not.toContain('No hay una tienda configurada');
      expect(text()).not.toContain('No hay horarios esta semana');
      expect(calendarService.loadCalendarWeek).not.toHaveBeenCalled();
    });

    it('should render storeError with a retry and issue no week read', async () => {
      setup({ queryParams: {}, profileResult: new HttpErrorResponse({ status: 500 }) });
      await settle();

      expect(text()).toContain('No pudimos determinar la tienda');
      expect(text()).not.toContain('No hay una tienda configurada');
      expect(text()).not.toContain('No hay horarios esta semana');
      expect(calendarService.loadCalendarWeek).not.toHaveBeenCalled();
      expect(text()).toContain('Reintentar');
    });

    it('should render storeUnconfigured when the resolution settled without a store', async () => {
      setup({ queryParams: {}, profileResult: profile(null) });
      await settle();

      expect(text()).toContain('No hay una tienda configurada');
      expect(text()).not.toContain('No pudimos determinar la tienda');
      expect(text()).not.toContain('No hay horarios esta semana');
      expect(calendarService.loadCalendarWeek).not.toHaveBeenCalled();
    });

    it('should render weekWithoutSlots for a settled read that returned nothing, naming the materialize-first cause', async () => {
      setup({ weekResult: [] });
      await settle();

      expect(text()).toContain('No hay horarios esta semana');
      expect(text()).toContain('filtro de actividad');
      // F8: the copy describes the real behaviour — the week is prepared on read
      // from each activity's availability — and no longer claims the schedule
      // "was never generated".
      expect(text()).toContain('prepara la semana a partir de la disponibilidad');
      expect(text()).not.toContain('ya generó');
      expect(text()).not.toContain('No hay una tienda configurada');
      expect(text()).not.toContain('No pudimos determinar la tienda');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-week-grid'),
      ).toBeNull();
    });

    it('should retry the store resolution after a failure', async () => {
      setup({ queryParams: {}, profileResult: new HttpErrorResponse({ status: 500 }) });
      await settle();
      expect(profileService.getProfile).toHaveBeenCalledTimes(1);

      component.retryStore();
      await settle();

      expect(profileService.getProfile).toHaveBeenCalledTimes(2);
    });

    it('should derive the empty state from what the grid would draw, not from the raw response (F5)', async () => {
      // A response whose only entry starts outside the visible week: `buildWeek`
      // drops it, so the grid would draw seven empty columns. The empty state must
      // describe that, not disappear behind an empty grid.
      setup({
        weekResult: [
          entry({
            slotId: 'slot-off-range',
            startAt: '2026-11-10T09:00:00',
            endAt: '2026-11-10T10:00:00',
          }),
        ],
      });
      await settle();

      expect(text()).toContain('No hay horarios esta semana');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-week-grid'),
      ).toBeNull();
    });

    it('should render the loading state, not the empty state, while a retry is in flight (F4)', async () => {
      const pendingRetry$ = new Subject<SchedulingCalendarEntry[]>();
      setup({ weekResult: [] });
      await settle();
      expect(text()).toContain('No hay horarios esta semana');

      calendarService.loadCalendarWeek.mockReturnValue(pendingRetry$);
      component.retryWeek();
      await settle();

      expect(component.weekPending()).toBe(true);
      expect(text()).toContain('Cargando la semana');
      expect(text()).not.toContain('No hay horarios esta semana');
    });

    it('should state the catalogue cap in its own words, with a retry (F1)', async () => {
      setup();
      calendarService.loadCalendarWeek.mockReturnValue(
        throwError(
          () => new SchedulingCatalogueTooLargeError('store-1', 1200, MAX_CATALOGUE_PAGES),
        ),
      );
      await settle();

      expect(text()).toContain('actividades activas');
      expect(text()).toContain('Reintentar');
      // Round-2 F4: the state names the remedy the operator actually has, not just
      // the condition.
      expect(text()).toContain('Deshabilitá las actividades que ya no uses');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-week-grid'),
      ).toBeNull();
    });
  });

  describe('the week read', () => {
    it('should resolve the query store and read the Monday-anchored range with an exclusive `to`', async () => {
      setup();
      await settle();

      expect(profileService.getProfile).not.toHaveBeenCalled();
      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(1);
      expect(weekRequest()).toEqual({
        storeId: 'store-1',
        from: '2026-09-28T00:00:00',
        to: '2026-10-05T00:00:00',
        activityId: null,
      });
    });

    it('should build both bounds as pure local-midnight strings (F2)', async () => {
      setup();
      await settle();

      // Never a time-of-day read off an instant: a DST transition at local midnight
      // would otherwise move `from` to 01:00 and drop a real Monday 00:00–01:00 slot.
      expect(component.rangeFrom()).toBe('2026-09-28T00:00:00');
      expect(component.rangeTo()).toBe('2026-10-05T00:00:00');
      // `to` stays exclusive, exactly one week after `from`.
      expect(component.rangeTo().slice(0, 10)).toBe('2026-10-05');
    });

    it('should express both bounds as a calendar date plus midnight, never an instant read (round-2 F1)', async () => {
      // Installed before construction so it captures the whole page lifetime.
      const getHoursSpy = vi.spyOn(Date.prototype, 'getHours');
      setup();
      await settle();

      const from = component.rangeFrom();
      const to = component.rangeTo();

      // Zone-independent invariant: both bounds are literally midnight, whatever
      // the host zone, and they land on the calendar dates the week implies (one
      // ISO week apart). The literal spec above only bites where the anchor week
      // crosses a nonexistent local midnight; this one asserts the shape.
      expect(from.endsWith('T00:00:00')).toBe(true);
      expect(to.endsWith('T00:00:00')).toBe(true);
      expect(from.slice(0, 10)).toBe(toIsoDate(component.weekStart()));
      expect(to.slice(0, 10)).toBe(toIsoDate(addDays(component.weekStart(), 7)));

      // The shape alone cannot distinguish the buggy derivation in a zone where
      // midnight exists (both are the same string), so the invariant is also
      // pinned on the instant read itself: `Date.prototype.getHours` is what
      // `toIsoDateTime` uses to turn an instant into a wall-clock time, and the
      // bounds must not consult it. (A module-level `vi.mock` cannot intercept
      // here: the Angular unit-test builder bundles the spec and its imports.)
      expect(getHoursSpy).not.toHaveBeenCalled();
      getHoursSpy.mockRestore();
    });

    it('should fall back to the profile store when the URL carries none', async () => {
      setup({ queryParams: { date: '2026-09-30' }, profileResult: profile('store-from-profile') });
      await settle();

      expect(weekRequest().storeId).toBe('store-from-profile');
    });

    it('should fail closed and render no partial grid when the materialization fails (D59)', async () => {
      setup({ weekError: true });
      await settle();

      expect(text()).toContain('No pudimos cargar la semana');
      expect(text()).toContain('Reintentar');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-week-grid'),
      ).toBeNull();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-day-agenda'),
      ).toBeNull();
    });

    it('should re-run the failed week read on retry', async () => {
      setup({ weekError: true });
      await settle();
      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(1);

      component.retryWeek();
      await settle();

      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(2);
    });

    it('should read the new range when the queryParamMap changes (D64)', async () => {
      setup();
      await settle();
      expect(weekRequest(0).from).toBe('2026-09-28T00:00:00');

      queryParams$.next(convertToParamMap({ date: '2026-10-07', storeId: 'store-1' }));
      await settle();

      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(2);
      expect(weekRequest(1)).toEqual({
        storeId: 'store-1',
        from: '2026-10-05T00:00:00',
        to: '2026-10-12T00:00:00',
        activityId: null,
      });
    });
  });

  describe('the activity filter (D52, D63)', () => {
    it('should offer only the store enabled activities', async () => {
      setup({
        activities: [activity('activity-1', 'Yoga'), activity('activity-2', 'Pilates')],
      });
      await settle();

      expect(calendarService.listEnabledActivities).toHaveBeenCalledWith('store-1');
      expect(component.activities().map((a) => a.activityName)).toEqual(['Yoga', 'Pilates']);
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('.activity-filter'),
      ).not.toBeNull();
    });

    it('should keep the filter rendered while the week read is in flight', async () => {
      const pendingWeek$ = new Subject<SchedulingCalendarEntry[]>();
      setup({ weekObservable: pendingWeek$ });
      await settle();

      expect(component.weekPending()).toBe(true);
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('.activity-filter'),
      ).not.toBeNull();
      expect(component.activities()).toHaveLength(1);
    });

    it('should narrow the week read to the selected activity', async () => {
      setup({ activities: [activity('activity-1'), activity('activity-2')] });
      await settle();

      component.onActivityFilterChange('activity-2');
      await settle();

      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(2);
      expect(weekRequest(1).activityId).toBe('activity-2');
      expect(weekRequest(1).from).toBe('2026-09-28T00:00:00');
    });

    it('should send no activityId once the filter is cleared', async () => {
      setup({ activities: [activity('activity-1')] });
      await settle();

      component.onActivityFilterChange('activity-1');
      await settle();
      expect(weekRequest(1).activityId).toBe('activity-1');

      component.onActivityFilterChange(null);
      await settle();

      expect(weekRequest(2).activityId).toBeNull();
    });
    it('should not re-read the catalogue when only the week changes (F6)', async () => {
      setup();
      await settle();
      expect(calendarService.listEnabledActivities).toHaveBeenCalledTimes(1);

      queryParams$.next(convertToParamMap({ date: '2026-10-07', storeId: 'store-1' }));
      await settle();

      // The options are read once per store. Coupling this resource to the week
      // params would re-issue the catalogue read on every week change (and is
      // exactly the mutation this spec exists to catch).
      expect(calendarService.listEnabledActivities).toHaveBeenCalledTimes(1);
      expect(calendarService.loadCalendarWeek).toHaveBeenCalledTimes(2);
    });

    it('should clear the filter when the store changes (round-2 F3)', async () => {
      setup({ activities: [activity('activity-1'), activity('activity-2')] });
      await settle();

      component.onActivityFilterChange('activity-1');
      await settle();
      expect(weekRequest(1).activityId).toBe('activity-1');

      // Same page, same component, different store: the filter named a store-1
      // activity and would otherwise survive into store-2 as a blank selection.
      queryParams$.next(convertToParamMap({ date: '2026-09-30', storeId: 'store-2' }));
      await settle();

      expect(component.activityFilter()).toBeNull();
      const last = weekRequest(calendarService.loadCalendarWeek.mock.calls.length - 1);
      expect(last.storeId).toBe('store-2');
      expect(last.activityId).toBeNull();

      // And no read ever combines the new store with the old filter, not even a
      // superseded intermediate one.
      const stale = calendarService.loadCalendarWeek.mock.calls
        .map((call) => call[0] as SchedulingCalendarWeekRequest)
        .filter((request) => request.storeId === 'store-2' && request.activityId === 'activity-1');
      expect(stale).toHaveLength(0);
    });
  });

  describe('week navigation (D64)', () => {
    it('should move to the previous week, merging the query params', async () => {
      setup();
      await settle();

      component.onPreviousWeek();

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: expect.anything(),
        queryParams: { date: '2026-09-21' },
        queryParamsHandling: 'merge',
      });
    });

    it('should move to the next week, merging the query params', async () => {
      setup();
      await settle();

      component.onNextWeek();

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: expect.anything(),
        queryParams: { date: '2026-10-05' },
        queryParamsHandling: 'merge',
      });
    });

    it('should move to the current week and keep the store param (merge)', async () => {
      setup();
      await settle();

      component.onToday();

      const call = router.navigate.mock.calls.at(-1) as [
        unknown[],
        { queryParamsHandling: string; queryParams: { date: string } },
      ];
      expect(call[1].queryParamsHandling).toBe('merge');
      expect(isoWeekday(parseAnchorDate(call[1].queryParams.date))).toBe(1);
    });

    it('should label the visible week Monday..Sunday', async () => {
      setup();
      await settle();

      expect(text()).toContain('28/09/2026 – 04/10/2026');
    });
  });

  describe('grid and agenda', () => {
    it('should render the week grid and the agenda of the anchor day', async () => {
      setup();
      await settle();

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('app-scheduling-week-grid'),
      ).not.toBeNull();
      expect(text()).toContain('Yoga');
      expect(text()).toContain('Ana Pérez');
    });

    it('should move the agenda to the selected day', async () => {
      setup({
        weekResult: [
          entry({ slotId: 'slot-wed', startAt: '2026-09-30T09:00:00' }),
          entry({
            slotId: 'slot-thu',
            startAt: '2026-10-01T11:00:00',
            endAt: '2026-10-01T12:00:00',
            appointments: [
              {
                id: 'appointment-thu',
                userId: 'user-2',
                customerId: null,
                customerName: 'Bruno Díaz',
                statusId: 'status-1',
                statusName: 'Scheduled',
                notes: null,
                enabled: true,
              },
            ],
          }),
        ],
      });
      await settle();
      expect(text()).toContain('Ana Pérez');

      component.onDaySelected('2026-10-01');
      await settle();

      expect(text()).toContain('Bruno Díaz');
      expect(text()).not.toContain('Ana Pérez');
    });

    it('should render no write affordance at all (D65)', async () => {
      setup();
      await settle();

      const labels = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
      ).map((button) => button.textContent?.trim() ?? '');
      expect(labels).not.toContain('Reservar');
      expect(labels).not.toContain('Nuevo turno');
      expect(text()).not.toContain('Reservar');
      const blocks = (fixture.nativeElement as HTMLElement).querySelectorAll('.slot-block');
      expect(blocks.length).toBeGreaterThan(0);
      for (const block of Array.from(blocks)) {
        expect(block.querySelector('button')).toBeNull();
      }
    });
  });
});
