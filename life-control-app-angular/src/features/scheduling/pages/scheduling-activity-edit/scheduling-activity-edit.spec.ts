/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { NotificationService } from '@shared/data/notification';
import { ApiError } from '@shared/models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { SchedulingActivityEdit } from './scheduling-activity-edit';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import { SchedulingActivity } from '../../models/scheduling-activity.models';

describe('SchedulingActivityEdit', () => {
  let fixture: ComponentFixture<SchedulingActivityEdit>;
  let component: SchedulingActivityEdit;
  let activityService: {
    createActivity: ReturnType<typeof vi.fn>;
    updateActivity: ReturnType<typeof vi.fn>;
    getActivityById: ReturnType<typeof vi.fn>;
  };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };

  const activity = (overrides: Partial<SchedulingActivity> = {}): SchedulingActivity => ({
    id: 'activity-1',
    companyStoreId: 'store-1',
    userId: 'user-1',
    activityName: 'Yoga',
    description: 'Clase de yoga',
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 5,
    createdAt: '2026-09-28T12:00:00',
    updatedAt: '2026-09-28T12:00:00',
    ...overrides,
  });

  const formValue = {
    activityName: 'Yoga',
    description: null,
    durationMinutes: 60,
    capacityPerSlot: 8,
    userId: null,
  };

  function httpError(status: number, body: Partial<ApiError> = {}): HttpErrorResponse {
    return new HttpErrorResponse({
      error: {
        status,
        message: '',
        path: '/api/scheduling/activities',
        timestamp: '2026-09-28T12:00:00',
        correlationId: 'abc',
        ...body,
      },
      status,
      statusText: 'Error',
    });
  }

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
    edit?: boolean;
    loaded?: SchedulingActivity;
    /** Sequential by-id read results; the last one is reused for further calls. */
    loadResults?: (SchedulingActivity | HttpErrorResponse)[];
    queryStoreId?: string | null;
    profileStoreId?: string | null;
    createResult?: SchedulingActivity | HttpErrorResponse;
    updateResult?: SchedulingActivity | HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    const edit = options.edit ?? false;
    const loaded = options.loaded ?? activity();
    const createResult = options.createResult ?? activity({ id: 'created-1' });
    const updateResult = options.updateResult ?? activity({ version: 6 });
    const loadResults = options.loadResults ?? [loaded];
    let loadCallCount = 0;

    activityService = {
      createActivity:
        createResult instanceof HttpErrorResponse
          ? vi.fn(() => throwError(() => createResult))
          : vi.fn(() => of(createResult)),
      updateActivity:
        updateResult instanceof HttpErrorResponse
          ? vi.fn(() => throwError(() => updateResult))
          : vi.fn(() => of(updateResult)),
      getActivityById: vi.fn(() => {
        const result = loadResults[Math.min(loadCallCount, loadResults.length - 1)];
        loadCallCount += 1;
        return result instanceof HttpErrorResponse ? throwError(() => result) : of(result);
      }),
    };

    router = { navigate: vi.fn() };
    notifications = { showSuccess: vi.fn() };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingActivityEdit, NoopAnimationsModule],
      providers: [
        { provide: SchedulingActivityService, useValue: activityService },
        { provide: Router, useValue: router },
        { provide: NotificationService, useValue: notifications },
        {
          provide: ProfileService,
          useValue: {
            getProfile: vi.fn((): Observable<ProfileResponse> =>
              of(profile(options.profileStoreId ?? null)),
            ),
          },
        },
        {
          provide: Keycloak,
          useValue: {
            tokenParsed: {
              resource_access: { 'life-control-client': { roles: ['lc-scheduling'] } },
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(edit ? { id: 'activity-1' } : {}),
              queryParamMap: convertToParamMap(
                options.queryStoreId ? { storeId: options.queryStoreId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(SchedulingActivityEdit);
    component = fixture.componentInstance;
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

  function retryButton(): HTMLButtonElement | null {
    return (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Reintentar',
      ) ?? null
    );
  }

  function submitButton(): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('button[type="submit"]');
  }

  describe('create', () => {
    it('should send companyStoreId and never send enabled or version', async () => {
      setup({ queryStoreId: 'store-1' });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(activityService.createActivity).toHaveBeenCalledTimes(1);
      const request = activityService.createActivity.mock.calls[0][0];
      expect(request).toEqual({
        companyStoreId: 'store-1',
        activityName: 'Yoga',
        description: null,
        durationMinutes: 60,
        capacityPerSlot: 8,
        userId: null,
      });
      expect(request).not.toHaveProperty('enabled');
      expect(request).not.toHaveProperty('version');
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/scheduling/list']);
    });

    it('should fail closed and not create when no store is configured', async () => {
      setup({ queryStoreId: null, profileStoreId: null });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(activityService.createActivity).not.toHaveBeenCalled();
      expect(component.generalError()).toContain('No hay una tienda configurada');
    });

    it('should map a 400 errors map onto the form fields', async () => {
      setup({
        queryStoreId: 'store-1',
        createResult: httpError(400, {
          message: 'Validation failed',
          errors: { activityName: 'activityName is required' },
        }),
      });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(component.serverErrors()).toEqual({ activityName: 'activityName is required' });
      expect(component.generalError()).toBeNull();
      expect(component.activityForm().get('activityName')?.errors?.['serverError']).toBe(
        'activityName is required',
      );
    });
  });

  describe('edit', () => {
    it('should echo the loaded version and omit companyStoreId and enabled', async () => {
      setup({ edit: true, loaded: activity({ version: 5 }) });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(activityService.updateActivity).toHaveBeenCalledTimes(1);
      const request = activityService.updateActivity.mock.calls[0][1];
      expect(request).toEqual({
        activityName: 'Yoga',
        description: null,
        durationMinutes: 60,
        capacityPerSlot: 8,
        userId: null,
        version: 5,
      });
      expect(request).not.toHaveProperty('companyStoreId');
      expect(request).not.toHaveProperty('enabled');
      expect(router.navigate).toHaveBeenCalledWith(['/scheduling/list']);
    });

    it('should recover from a 412 by reloading the flat entity', async () => {
      setup({ edit: true, updateResult: httpError(412, { message: 'version mismatch' }) });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(component.generalError()).toContain('Otra sesión modificó esta actividad');
      // The first read on init plus the recovery read after the 412.
      expect(activityService.getActivityById).toHaveBeenCalledTimes(2);
      expect(component.activityForm().pristine).toBe(true);
    });

    it('should not claim the values were reloaded when the 412 recovery read failed', async () => {
      setup({
        edit: true,
        // First read (init) succeeds; the recovery read triggered by the 412 fails.
        loadResults: [activity(), httpError(500, { message: 'boom' })],
        updateResult: httpError(412, { message: 'version mismatch' }),
      });
      await settle();

      component.onSave(formValue);
      await settle();

      // The 412 recovery depends on a reload that did not happen, so the page must not
      // state that the values were reloaded: that sentence would be false. The message
      // is owned by the reload's success path, never by the 412 branch itself.
      expect(component.generalError()).toBeNull();
      expect(text()).not.toContain('Se recargaron los valores actuales');

      // The honest report is the load-failure banner, with its retry.
      expect(component.loadError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
      expect(text()).toContain('Ocurrió un error en el servidor. Intentá de nuevo más tarde.');
      expect(retryButton()).not.toBeNull();
    });

    it('should re-seed the form and the version from the entity reloaded after a 412', async () => {
      const stale = activity({ activityName: 'Yoga', durationMinutes: 60, version: 5 });
      const fresh = activity({ activityName: 'Yoga avanzado', durationMinutes: 90, version: 9 });
      setup({
        edit: true,
        loadResults: [stale, fresh],
        updateResult: httpError(412, { message: 'version mismatch' }),
      });
      await settle();

      expect(component.activityForm().getRawValue()).toEqual({
        activityName: 'Yoga',
        description: 'Clase de yoga',
        durationMinutes: 60,
        capacityPerSlot: 8,
        userId: 'user-1',
      });

      component.onSave(formValue);
      await settle();

      // The recovery read re-seeds the form with the server's current values...
      expect(component.activityForm().getRawValue()).toEqual({
        activityName: 'Yoga avanzado',
        description: 'Clase de yoga',
        durationMinutes: 90,
        capacityPerSlot: 8,
        userId: 'user-1',
      });

      // ...and the version, so the retry sends the fresh precondition instead of
      // the stale one that just failed.
      activityService.updateActivity.mockReturnValue(of(fresh));
      component.onSave({
        activityName: 'Yoga avanzado',
        description: null,
        durationMinutes: 90,
        capacityPerSlot: 8,
        userId: null,
      });
      await settle();

      expect(activityService.updateActivity).toHaveBeenLastCalledWith('activity-1', {
        activityName: 'Yoga avanzado',
        description: null,
        durationMinutes: 90,
        capacityPerSlot: 8,
        userId: null,
        version: 9,
      });
    });

    it('should surface a failed by-id load and recover on retry', async () => {
      setup({
        edit: true,
        loadResults: [httpError(500, { message: 'boom' }), activity({ activityName: 'Yoga' })],
      });
      await settle();

      expect(component.loadError()).toBeTruthy();
      expect(text()).toContain('Reintentar');
      expect(activityService.getActivityById).toHaveBeenCalledTimes(1);

      const retry = retryButton();
      expect(retry).not.toBeNull();
      retry!.click();
      await settle();

      expect(component.loadError()).toBeNull();
      expect(activityService.getActivityById).toHaveBeenCalledTimes(2);
      expect(component.activityForm().get('activityName')?.value).toBe('Yoga');
    });

    it('should render a failed save even when the by-id read also failed', async () => {
      setup({
        edit: true,
        loadResults: [httpError(500, { message: 'boom' })],
        updateResult: httpError(500, { message: 'falló el guardado' }),
      });
      await settle();

      // The read failure stays on screen (retry is its recovery route), and a write
      // failure is a different event that must not be swallowed by it. The save is
      // driven directly because the submit control is disabled in this state.
      expect(component.loadError()).toBeTruthy();

      component.onSave(formValue);
      await settle();

      expect(component.generalError()).toBe('falló el guardado');
      expect(text()).toContain('falló el guardado');
    });

    it('should keep the save unavailable while the read failed and restore it after a successful retry', async () => {
      setup({
        edit: true,
        loadResults: [httpError(500, { message: 'boom' }), activity({ activityName: 'Yoga' })],
      });
      await settle();

      expect(component.loadError()).toBeTruthy();

      // The operator can still fill the form; a valid form must not re-enable the
      // save, because a PUT from this state carries no version precondition and
      // would overwrite another session's write instead of conflicting with it.
      component.activityForm().setValue(formValue);
      await settle();

      expect(text()).toContain('reintentá la lectura antes de editar');
      const blockedSubmit = submitButton();
      expect(blockedSubmit).not.toBeNull();
      expect(blockedSubmit!.disabled).toBe(true);

      const retry = retryButton();
      expect(retry).not.toBeNull();
      retry!.click();
      await settle();

      expect(component.loadError()).toBeNull();
      expect(activityService.getActivityById).toHaveBeenCalledTimes(2);
      expect(submitButton()?.disabled).toBe(false);
    });

    it('should map a duplicate-name 409 onto activityName', async () => {
      setup({
        edit: true,
        updateResult: httpError(409, {
          message: "SchedulingActivity with name 'Yoga' already exists in the store",
        }),
      });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(component.serverErrors()).toEqual({
        activityName: 'Ya existe una actividad con ese nombre en esta tienda.',
      });
      expect(component.generalError()).toBeNull();
    });

    it('should keep a generic 409 as a generic conflict, not a field error', async () => {
      setup({
        edit: true,
        updateResult: httpError(409, { message: 'could not execute statement' }),
      });
      await settle();

      component.onSave(formValue);
      await settle();

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toContain('El dato cambió');
    });

    it('should expose the dirty state to the unsaved-changes guard', async () => {
      setup({ edit: true });
      await settle();

      expect(component.hasUnsavedChanges()).toBe(false);

      component.activityForm().get('activityName')?.setValue('Yoga avanzado');
      component.activityForm().markAsDirty();
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should mark the form pristine before navigating after a successful save', async () => {
      setup({ edit: true });
      await settle();
      component.activityForm().get('activityName')?.setValue('Yoga avanzado');
      component.activityForm().markAsDirty();
      expect(component.activityForm().dirty).toBe(true);

      component.onSave({ ...formValue, activityName: 'Yoga avanzado' });
      await settle();

      expect(component.activityForm().pristine).toBe(true);
      expect(router.navigate).toHaveBeenCalledWith(['/scheduling/list']);
    });
  });

  it('should navigate back to the list on cancel', async () => {
    setup({ edit: true });
    await settle();

    component.onCancel();

    expect(router.navigate).toHaveBeenCalledWith(['/scheduling/list']);
  });

  it('should expose the read-role gating', async () => {
    setup({ edit: true });
    await settle();

    expect(component.canWrite).toBe(true);
  });
});
