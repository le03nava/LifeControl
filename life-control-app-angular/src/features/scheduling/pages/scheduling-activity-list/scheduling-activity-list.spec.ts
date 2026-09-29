/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { Observable, Subject, of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { ConfirmDialog } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { SchedulingActivityList } from './scheduling-activity-list';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import { Page, SchedulingActivity } from '../../models/scheduling-activity.models';

describe('SchedulingActivityList', () => {
  let fixture: ComponentFixture<SchedulingActivityList>;
  let component: SchedulingActivityList;
  let activityService: {
    listActivities: ReturnType<typeof vi.fn>;
    enableActivity: ReturnType<typeof vi.fn>;
    disableActivity: ReturnType<typeof vi.fn>;
  };
  let profileService: { getProfile: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;

  const WRITE_ROLES = ['lc-scheduling'];
  const READ_ROLES = ['lc-scheduling-read'];

  const activity = (overrides: Partial<SchedulingActivity> = {}): SchedulingActivity => ({
    id: 'activity-1',
    companyStoreId: 'store-1',
    userId: null,
    activityName: 'Yoga',
    description: null,
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 0,
    createdAt: '2026-09-28T12:00:00',
    updatedAt: '2026-09-28T12:00:00',
    ...overrides,
  });

  const pageOf = (
    content: SchedulingActivity[],
    totalPages = 1,
    overrides: Partial<Page<SchedulingActivity>> = {},
  ): Page<SchedulingActivity> => ({
    content,
    totalElements: content.length,
    totalPages,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
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
    roles?: string[];
    queryStoreId?: string | null;
    profileResult?: ProfileResponse | HttpErrorResponse;
    /** A controllable profile read; used to hold the store resolution in flight. */
    profileObservable?: Observable<ProfileResponse>;
    listResult?: Page<SchedulingActivity>;
    listError?: boolean;
    dialogConfirmed?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    activityService = {
      listActivities: vi.fn().mockReturnValue(of(options.listResult ?? pageOf([activity()]))),
      enableActivity: vi.fn().mockReturnValue(of(activity())),
      disableActivity: vi.fn().mockReturnValue(of(void 0)),
    };

    if (options.listError) {
      activityService.listActivities = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    }

    const profileResult = options.profileResult ?? profile('store-from-profile');
    const profileStream = options.profileObservable;
    profileService = {
      getProfile: profileStream
        ? vi.fn((): Observable<ProfileResponse> => profileStream)
        : profileResult instanceof HttpErrorResponse
          ? vi.fn((): Observable<ProfileResponse> => throwError(() => profileResult))
          : vi.fn((): Observable<ProfileResponse> => of(profileResult)),
    };

    notifications = { showSuccess: vi.fn() };
    dialog = {
      open: vi.fn().mockReturnValue({ afterClosed: () => of(options.dialogConfirmed ?? false) }),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingActivityList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: SchedulingActivityService, useValue: activityService },
        { provide: ProfileService, useValue: profileService },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap(
                options.queryStoreId ? { storeId: options.queryStoreId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(SchedulingActivityList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function actionButton(label: string): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[aria-label="${label}"]`);
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should issue no store-scoped read while the store resolution is in flight and exactly one after it settles', async () => {
    const profile$ = new Subject<ProfileResponse>();
    setup({ queryStoreId: null, profileObservable: profile$ });

    // Flush once so the profile read actually starts; asserting on the pending
    // flag would prove nothing, the observable contract is the request count.
    fixture.detectChanges();
    fixture.detectChanges();

    expect(profileService.getProfile).toHaveBeenCalledTimes(1);
    expect(activityService.listActivities).not.toHaveBeenCalled();

    profile$.next(profile('store-from-profile'));
    profile$.complete();
    await settle();

    expect(activityService.listActivities).toHaveBeenCalledTimes(1);
    expect(activityService.listActivities).toHaveBeenCalledWith('store-from-profile', 0, 12, false);
  });

  it('should resolve the query store, skip the profile and load the first page', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    expect(profileService.getProfile).not.toHaveBeenCalled();
    expect(activityService.listActivities).toHaveBeenCalledWith('store-1', 0, 12, false);
    expect(text()).toContain('Yoga');
  });

  it('should fall back to the profile store when the URL carries none', async () => {
    setup({ queryStoreId: null, profileResult: profile('store-from-profile') });
    await settle();

    expect(activityService.listActivities).toHaveBeenCalledWith('store-from-profile', 0, 12, false);
  });

  it('should re-query when the page changes', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    // A two-page response, so the requested page exists and the request is the
    // page change under test rather than the clamp of an out-of-range page.
    activityService.listActivities.mockImplementation((_storeId: string, page: number) =>
      of(
        pageOf([activity()], 2, {
          totalElements: 2,
          number: page,
          first: page === 0,
          last: page === 1,
        }),
      ),
    );
    component.onPageChange({ pageIndex: 1, pageSize: 24 });
    await settle();

    expect(activityService.listActivities).toHaveBeenLastCalledWith('store-1', 1, 24, false);
  });

  it('should reset to the first page and include disabled rows when the toggle turns on', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    component.onPageChange({ pageIndex: 2, pageSize: 12 });
    await settle();

    component.onIncludeDisabledChange(true);
    await settle();

    expect(component.includeDisabled()).toBe(true);
    expect(component.pageIndex()).toBe(0);
    expect(activityService.listActivities).toHaveBeenLastCalledWith('store-1', 0, 12, true);
  });

  describe('write-role gating', () => {
    it('should hide every write control for a read-only role', async () => {
      setup({ roles: READ_ROLES, queryStoreId: 'store-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).not.toContain('Nueva actividad');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Editar disponibilidad')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });

    it('should render the write controls for a write role', async () => {
      setup({ roles: WRITE_ROLES, queryStoreId: 'store-1' });
      await settle();

      expect(component.canWrite).toBe(true);
      expect(text()).toContain('Nueva actividad');
      expect(actionButton('Editar')).not.toBeNull();
      expect(actionButton('Editar disponibilidad')).not.toBeNull();
      expect(actionButton('Deshabilitar')).not.toBeNull();
    });
  });

  describe('disable / re-enable', () => {
    it('should confirm before disabling and not call the service when cancelled', async () => {
      setup({ queryStoreId: 'store-1', dialogConfirmed: false });
      await settle();
      activityService.listActivities.mockClear();

      component.onDisable(activity());
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(ConfirmDialog, expect.anything());
      expect(activityService.disableActivity).not.toHaveBeenCalled();
    });

    it('should disable, notify and reload when confirmed', async () => {
      setup({ queryStoreId: 'store-1', dialogConfirmed: true });
      await settle();
      activityService.listActivities.mockClear();

      component.onDisable(activity());
      await settle();

      expect(activityService.disableActivity).toHaveBeenCalledWith('activity-1');
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(activityService.listActivities).toHaveBeenCalledTimes(1);
    });

    it('should surface a disable failure instead of swallowing it', async () => {
      setup({ queryStoreId: 'store-1', dialogConfirmed: true });
      await settle();
      activityService.disableActivity = vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));

      component.onDisable(activity());
      await settle();

      expect(component.actionError()).toContain('No tenés permisos');
    });

    it('should offer re-enable only for a disabled row when includeDisabled is on', async () => {
      setup({
        queryStoreId: 'store-1',
        listResult: pageOf([activity({ id: 'disabled-1', enabled: false })]),
      });
      await settle();

      // includeDisabled is off by default: the disabled row is shown only because
      // the mock returns it, but the inverse action is not offered.
      expect(actionButton('Reactivar')).toBeNull();

      component.onIncludeDisabledChange(true);
      await settle();

      expect(actionButton('Reactivar')).not.toBeNull();
    });

    it('should re-enable and reload', async () => {
      setup({
        queryStoreId: 'store-1',
        listResult: pageOf([activity({ id: 'disabled-1', enabled: false })]),
      });
      await settle();
      activityService.listActivities.mockClear();

      component.onEnable(activity({ id: 'disabled-1', enabled: false }));
      await settle();

      expect(activityService.enableActivity).toHaveBeenCalledWith('disabled-1');
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(activityService.listActivities).toHaveBeenCalledTimes(1);
    });

    it('should not strand the operator on a page that a disable emptied', async () => {
      let secondPageRemoved = false;
      const firstOfTwo = pageOf([activity({ id: 'activity-1', activityName: 'Yoga' })], 2, {
        totalElements: 2,
        number: 0,
        first: true,
        last: false,
      });
      const secondOfTwo = pageOf([activity({ id: 'activity-2', activityName: 'Pilates' })], 2, {
        totalElements: 2,
        number: 1,
        first: false,
        last: true,
      });
      const survivingFirst = pageOf([activity({ id: 'activity-1', activityName: 'Yoga' })], 1, {
        totalElements: 1,
        number: 0,
        first: true,
        last: true,
      });
      // Page index 1 after the disable: the collection is non-empty (one row on
      // page 1) yet this page has no rows, the state that must never be shown.
      const emptiedSecond = pageOf([], 1, {
        totalElements: 1,
        number: 1,
        first: false,
        last: true,
      });

      setup({ queryStoreId: 'store-1', dialogConfirmed: true });
      activityService.listActivities.mockImplementation((_storeId: string, page: number) =>
        of(
          secondPageRemoved
            ? page === 0
              ? survivingFirst
              : emptiedSecond
            : page === 0
              ? firstOfTwo
              : secondOfTwo,
        ),
      );
      await settle();

      component.onPageChange({ pageIndex: 1, pageSize: 12 });
      await settle();
      expect(component.pageIndex()).toBe(1);
      expect(text()).toContain('Pilates');

      activityService.disableActivity.mockImplementation(() => {
        secondPageRemoved = true;
        return of(void 0);
      });
      component.onDisable(activity({ id: 'activity-2', activityName: 'Pilates' }));
      await settle();

      expect(component.pageIndex()).toBe(0);
      expect(text()).toContain('Yoga');
      expect(text()).not.toContain('No hay actividades registradas');
      expect(activityService.listActivities).toHaveBeenLastCalledWith('store-1', 0, 12, false);
    });
  });

  it('should navigate to create carrying the resolved store', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    component.onCreate();

    expect(router.navigate).toHaveBeenCalledWith(['/scheduling/create'], {
      queryParams: { storeId: 'store-1' },
    });
  });

  it('should navigate to edit for a row', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    component.onEdit(activity());

    expect(router.navigate).toHaveBeenCalledWith(['/scheduling/edit', 'activity-1']);
  });

  it('should navigate to the availability editor for a row', async () => {
    setup({ queryStoreId: 'store-1' });
    await settle();

    component.onEditAvailability(activity());

    expect(router.navigate).toHaveBeenCalledWith([
      '/scheduling/activities',
      'activity-1',
      'availability',
    ]);
  });

  describe('fail-closed and list states', () => {
    it('should report a store that was never configured without reading the list', async () => {
      setup({ queryStoreId: null, profileResult: profile(null) });
      await settle();

      expect(component.storeUnconfigured()).toBe(true);
      expect(text()).toContain('No hay una tienda configurada');
      expect(activityService.listActivities).not.toHaveBeenCalled();
    });

    it('should keep a failed resolution apart from an unconfigured store and offer a retry', async () => {
      setup({
        queryStoreId: null,
        profileResult: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      expect(component.storeUnconfigured()).toBe(false);
      expect(text()).toContain('No pudimos determinar la tienda');
      expect(profileService.getProfile).toHaveBeenCalledTimes(1);

      component.retryStore();
      await settle();
      expect(profileService.getProfile).toHaveBeenCalledTimes(2);
    });

    it('should render the empty state when the store has no activities', async () => {
      setup({ queryStoreId: 'store-1', listResult: pageOf([]) });
      await settle();

      expect(text()).toContain('No hay actividades registradas');
    });

    it('should render the list error state and offer a retry', async () => {
      setup({ queryStoreId: 'store-1', listError: true });
      await settle();

      expect(component.error()).toBeTruthy();
      expect(text()).toContain('Reintentar');
    });
  });
});
