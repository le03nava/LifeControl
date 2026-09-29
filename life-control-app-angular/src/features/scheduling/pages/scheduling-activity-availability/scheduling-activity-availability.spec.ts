/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { NotificationService } from '@shared/data/notification';
import { ApiError } from '@shared/models';
import { SchedulingActivityAvailability } from './scheduling-activity-availability';
import { SchedulingActivityService } from '../../data/scheduling-activity.service';
import {
  SchedulingActivity,
  SchedulingAvailabilityResponse,
  SchedulingAvailabilityWindow,
} from '../../models/scheduling-activity.models';

describe('SchedulingActivityAvailability', () => {
  let fixture: ComponentFixture<SchedulingActivityAvailability>;
  let component: SchedulingActivityAvailability;
  let activityService: {
    getActivityById: ReturnType<typeof vi.fn>;
    getAvailability: ReturnType<typeof vi.fn>;
    replaceAvailability: ReturnType<typeof vi.fn>;
  };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };

  const WRITE_ROLES = ['lc-scheduling'];
  const READ_ROLES = ['lc-scheduling-read'];

  const activity = (overrides: Partial<SchedulingActivity> = {}): SchedulingActivity => ({
    id: 'activity-1',
    companyStoreId: 'store-1',
    userId: 'user-1',
    activityName: 'Yoga',
    description: 'Clase de yoga',
    durationMinutes: 60,
    capacityPerSlot: 8,
    enabled: true,
    version: 3,
    createdAt: '2026-09-28T12:00:00',
    updatedAt: '2026-09-28T12:00:00',
    ...overrides,
  });

  const window = (
    overrides: Partial<SchedulingAvailabilityWindow> = {},
  ): SchedulingAvailabilityWindow => ({
    id: 'db-row-id',
    dayOfWeek: 1,
    startTime: '09:00:00',
    endTime: '13:00:00',
    validFrom: '2026-09-28',
    validTo: '2027-09-28',
    ...overrides,
  });

  const availability = (
    windows: SchedulingAvailabilityWindow[],
  ): SchedulingAvailabilityResponse => ({ activityId: 'activity-1', windows });

  function httpError(status: number, body: Partial<ApiError> = {}): HttpErrorResponse {
    return new HttpErrorResponse({
      error: {
        status,
        message: '',
        path: '/api/scheduling/activities/activity-1/availability',
        timestamp: '2026-09-28T12:00:00',
        correlationId: 'abc',
        ...body,
      },
      status,
      statusText: 'Error',
    });
  }

  interface SetupOptions {
    roles?: string[];
    loadResults?: (SchedulingAvailabilityResponse | HttpErrorResponse)[];
    activityResult?: SchedulingActivity | HttpErrorResponse;
    saveResult?: SchedulingAvailabilityResponse | HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    const loadResults = options.loadResults ?? [availability([window()])];
    const activityResult = options.activityResult ?? activity();
    const saveResult = options.saveResult ?? availability([window()]);
    let loadCallCount = 0;

    activityService = {
      getActivityById:
        activityResult instanceof HttpErrorResponse
          ? vi.fn(() => throwError(() => activityResult))
          : vi.fn(() => of(activityResult)),
      getAvailability: vi.fn(() => {
        const result = loadResults[Math.min(loadCallCount, loadResults.length - 1)];
        loadCallCount += 1;
        return result instanceof HttpErrorResponse ? throwError(() => result) : of(result);
      }),
      replaceAvailability:
        saveResult instanceof HttpErrorResponse
          ? vi.fn(() => throwError(() => saveResult))
          : vi.fn(() => of(saveResult)),
    };

    router = { navigate: vi.fn() };
    notifications = { showSuccess: vi.fn() };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingActivityAvailability, NoopAnimationsModule],
      providers: [
        { provide: SchedulingActivityService, useValue: activityService },
        { provide: Router, useValue: router },
        { provide: NotificationService, useValue: notifications },
        {
          provide: Keycloak,
          useValue: {
            tokenParsed: {
              resource_access: { 'life-control-client': { roles: options.roles ?? WRITE_ROLES } },
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: 'activity-1' }) },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(SchedulingActivityAvailability);
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

  function buttonByText(label: string): HTMLButtonElement | undefined {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === label,
    );
  }

  function retryButton(): HTMLButtonElement | undefined {
    return buttonByText('Reintentar');
  }

  function rowCount(): number {
    return component.form().controls.windows.length;
  }

  function rowAt(index: number) {
    return component.form().controls.windows.at(index);
  }

  describe('load', () => {
    it('should seed one row per window with the native time format and the activity name', async () => {
      setup({ loadResults: [availability([window({ dayOfWeek: 3, startTime: '08:30:00' })])] });
      await settle();

      expect(component.activity()?.activityName).toBe('Yoga');
      expect(text()).toContain('Yoga');
      expect(rowCount()).toBe(1);
      expect(rowAt(0)?.get('dayOfWeek')?.value).toBe(3);
      expect(rowAt(0)?.get('startTime')?.value).toBe('08:30');
      expect(rowAt(0)?.get('validFrom')?.value).toBe('2026-09-28');
    });

    it('should treat an empty window set as a legitimate state and not an error', async () => {
      setup({ loadResults: [availability([])] });
      await settle();

      expect(component.loadError()).toBeNull();
      expect(component.generalError()).toBeNull();
      expect(rowCount()).toBe(0);
      expect(text()).toContain('No hay ventanas cargadas');
    });

    it('should render a load failure with a retry and recover on it', async () => {
      setup({
        loadResults: [httpError(500, { message: 'boom' }), availability([window()])],
      });
      await settle();

      expect(component.loadError()).toBeTruthy();
      expect(retryButton()).toBeDefined();

      retryButton()!.click();
      await settle();

      expect(component.loadError()).toBeNull();
      expect(rowCount()).toBe(1);
    });
  });

  describe('row add / remove', () => {
    it('should add a Monday 09:00-13:00 default row and mark the form dirty', async () => {
      setup({ loadResults: [availability([])] });
      await settle();

      component.onAddWindow();
      await settle();

      expect(rowCount()).toBe(1);
      expect(rowAt(0)?.get('dayOfWeek')?.value).toBe(1);
      expect(rowAt(0)?.get('startTime')?.value).toBe('09:00');
      expect(rowAt(0)?.get('endTime')?.value).toBe('13:00');
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should remove a row by index and mark the form dirty', async () => {
      setup({ loadResults: [availability([window(), window({ dayOfWeek: 3 })])] });
      await settle();

      component.onRemoveWindow(0);
      await settle();

      expect(rowCount()).toBe(1);
      expect(rowAt(0)?.get('dayOfWeek')?.value).toBe(3);
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should block the add at the server cap of 50 rather than silently dropping it', async () => {
      const fifty = Array.from({ length: 50 }, (_, index) =>
        window({ id: `w-${index}`, dayOfWeek: (index % 7) + 1 }),
      );
      setup({ loadResults: [availability(fifty)] });
      await settle();

      expect(rowCount()).toBe(50);
      component.onAddWindow();
      await settle();

      expect(rowCount()).toBe(50);
      expect(buttonByText('Agregar ventana')?.disabled).toBe(true);
      expect(text()).toContain('máximo de 50 ventanas');
    });
  });

  describe('client validation mirroring the server', () => {
    it('should reject an end time not strictly after the start time without calling the service', async () => {
      setup({
        loadResults: [availability([window({ startTime: '13:00:00', endTime: '09:00:00' })])],
      });
      await settle();

      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).not.toHaveBeenCalled();
      expect(rowAt(0)?.hasError('endTimeNotAfterStart')).toBe(true);
      expect(text()).toContain('La hora de fin debe ser posterior a la hora de inicio.');
    });

    it('should reject an inverted validity range without calling the service', async () => {
      setup({
        loadResults: [availability([window({ validFrom: '2027-01-01', validTo: '2026-01-01' })])],
      });
      await settle();

      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).not.toHaveBeenCalled();
      expect(rowAt(0)?.hasError('validToBeforeValidFrom')).toBe(true);
      expect(text()).toContain('La fecha de fin no puede ser anterior a la fecha de inicio.');
    });

    it('should reject overlapping windows on the same weekday', async () => {
      setup({
        loadResults: [
          availability([
            window({ startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', startTime: '12:00:00', endTime: '15:00:00' }),
          ]),
        ],
      });
      await settle();

      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).not.toHaveBeenCalled();
      expect(component.form().controls.windows.hasError('overlappedWindows')).toBe(true);
      expect(text()).toContain('Dos ventanas del mismo día no pueden superponerse.');
    });

    it('should allow touching windows, exactly like the server', async () => {
      setup({
        loadResults: [
          availability([
            window({ startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', startTime: '13:00:00', endTime: '17:00:00' }),
          ]),
        ],
      });
      await settle();

      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);
    });
  });

  describe('overlap message discoverability', () => {
    const OVERLAP_MESSAGE = 'Dos ventanas del mismo día no pueden superponerse.';

    it('should reveal the overlap reason and disable Guardar after adding an overlapping row', async () => {
      setup({
        loadResults: [
          availability([window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' })]),
        ],
      });
      await settle();

      // The default row is Monday 09:00-13:00, so it overlaps the loaded window.
      component.onAddWindow();
      await settle();

      const windows = component.form().controls.windows;
      expect(windows.hasError('overlappedWindows')).toBe(true);
      // The page's add intent marks the form dirty, never the array touched, so
      // the explanation cannot depend on a touched flag to become visible.
      expect(windows.touched).toBe(false);
      expect(text()).toContain(OVERLAP_MESSAGE);
      expect(buttonByText('Guardar')?.disabled).toBe(true);
    });

    it('should reveal the overlap reason and disable Guardar after editing a time into an overlap', async () => {
      setup({
        loadResults: [
          availability([
            window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', dayOfWeek: 1, startTime: '14:00:00', endTime: '18:00:00' }),
          ]),
        ],
      });
      await settle();

      const edited = rowAt(1)?.get('startTime');
      edited?.setValue('12:00');
      // The field value changes (and the set flips to inconsistent) before any
      // blur: `setValue` never touches the array, and Angular's `markAsTouched`
      // only propagates up on blur. The reason must be readable in this state, not
      // only after an interaction that may never happen.
      expect(edited?.touched).toBe(false);
      await settle();

      const windows = component.form().controls.windows;
      expect(windows.hasError('overlappedWindows')).toBe(true);
      expect(windows.touched).toBe(false);
      expect(text()).toContain(OVERLAP_MESSAGE);
      expect(buttonByText('Guardar')?.disabled).toBe(true);
    });

    it('should keep the overlap reason visible after the edited field is blurred', async () => {
      setup({
        loadResults: [
          availability([
            window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', dayOfWeek: 1, startTime: '14:00:00', endTime: '18:00:00' }),
          ]),
        ],
      });
      await settle();

      // Blurring the field touches the child, and that visited state propagates up
      // to the array; the reason stays readable and Guardar stays unavailable.
      const edited = rowAt(1)?.get('startTime');
      edited?.setValue('12:00');
      edited?.markAsTouched();
      await settle();

      expect(component.form().controls.windows.hasError('overlappedWindows')).toBe(true);
      expect(text()).toContain(OVERLAP_MESSAGE);
      expect(buttonByText('Guardar')?.disabled).toBe(true);
    });

    it('should not reveal the overlap message while the set is consistent', async () => {
      setup({
        loadResults: [
          availability([
            window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', dayOfWeek: 3, startTime: '09:00:00', endTime: '13:00:00' }),
          ]),
        ],
      });
      await settle();

      // A pristine, consistent set has nothing to explain and Guardar is available.
      expect(text()).not.toContain(OVERLAP_MESSAGE);
      expect(buttonByText('Guardar')?.disabled).toBe(false);

      // Touching every control must not manufacture an overlap message either.
      component.form().markAllAsTouched();
      await settle();

      expect(component.form().controls.windows.hasError('overlappedWindows')).toBe(false);
      expect(text()).not.toContain(OVERLAP_MESSAGE);
      expect(buttonByText('Guardar')?.disabled).toBe(false);
    });
  });

  describe('save', () => {
    it('should send the whole set with the wire time format and adopt the ordered response', async () => {
      // Local order is Wednesday then Monday; the server answers Monday then
      // Wednesday, so the display must adopt the response order, not the request.
      setup({
        loadResults: [
          availability([
            window({ id: 'w-3', dayOfWeek: 3, startTime: '10:00:00', endTime: '12:00:00' }),
            window({ id: 'w-1', dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
          ]),
        ],
        saveResult: availability([
          window({ id: 're-1', dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
          window({ id: 're-3', dayOfWeek: 3, startTime: '10:00:00', endTime: '12:00:00' }),
        ]),
      });
      await settle();

      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);
      expect(activityService.replaceAvailability.mock.calls[0][0]).toBe('activity-1');
      expect(activityService.replaceAvailability.mock.calls[0][1]).toEqual({
        windows: [
          {
            dayOfWeek: 3,
            startTime: '10:00:00',
            endTime: '12:00:00',
            validFrom: '2026-09-28',
            validTo: '2027-09-28',
          },
          {
            dayOfWeek: 1,
            startTime: '09:00:00',
            endTime: '13:00:00',
            validFrom: '2026-09-28',
            validTo: '2027-09-28',
          },
        ],
      });

      // Server order wins in the local state.
      expect(rowAt(0)?.get('dayOfWeek')?.value).toBe(1);
      expect(rowAt(1)?.get('dayOfWeek')?.value).toBe(3);
    });

    it('should toast the success and mark the form pristine', async () => {
      setup({ loadResults: [availability([])] });
      await settle();
      component.onAddWindow();
      await settle();
      expect(component.form().dirty).toBe(true);

      component.onSave();
      await settle();

      expect(notifications.showSuccess).toHaveBeenCalledWith(
        'Disponibilidad guardada correctamente.',
      );
      expect(component.form().pristine).toBe(true);
      expect(component.hasUnsavedChanges()).toBe(false);
    });

    it('should map an indexed server error onto the offending row and field', async () => {
      setup({
        loadResults: [
          availability([
            window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', dayOfWeek: 2, startTime: '09:00:00', endTime: '13:00:00' }),
          ]),
        ],
        saveResult: httpError(400, {
          message: 'Validation failed',
          errors: { 'windows[1].startTime': 'startTime must not be empty' },
        }),
      });
      await settle();

      component.onSave();
      await settle();

      // The index names the second row, so the message must land there and not on
      // row 0 — a single-row fixture cannot tell `at(index)` from `at(0)`.
      expect(rowAt(1)?.get('startTime')?.errors?.['serverError']).toBe(
        'startTime must not be empty',
      );
      expect(rowAt(0)?.get('startTime')?.errors?.['serverError']).toBeUndefined();
      expect(component.generalError()).toBeNull();
      expect(text()).toContain('startTime must not be empty');
    });

    it('should surface a non-indexed errors key as a page-level error and blame no row', async () => {
      setup({
        saveResult: httpError(400, {
          message: 'Validation failed',
          errors: { windows: 'windows must contain at most 50 elements' },
        }),
      });
      await settle();

      component.onSave();
      await settle();

      // The plain `windows` key is not `windows[i].<field>`, so it cannot be placed
      // on a row and stays page-level.
      expect(component.generalError()).toBe('windows must contain at most 50 elements');
      expect(text()).toContain('windows must contain at most 50 elements');
      expect(rowAt(0)?.get('startTime')?.errors?.['serverError']).toBeUndefined();
      expect(rowAt(0)?.get('dayOfWeek')?.errors?.['serverError']).toBeUndefined();
    });

    it('should keep a field server error, block the repeat save, and clear it once that field is edited', async () => {
      setup({
        loadResults: [
          availability([
            window({ dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' }),
            window({ id: 'w2', dayOfWeek: 2, startTime: '09:00:00', endTime: '13:00:00' }),
          ]),
        ],
        saveResult: httpError(400, {
          message: 'Validation failed',
          errors: { 'windows[1].startTime': 'startTime must not be empty' },
        }),
      });
      await settle();

      // First save: the server rejects the second row and its error lands there.
      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);
      expect(rowAt(1)?.get('startTime')?.errors?.['serverError']).toBe(
        'startTime must not be empty',
      );
      expect(text()).toContain('startTime must not be empty');

      // Repeat save without touching the offending field: no second `PUT`, the
      // field keeps its message and the submit control stays unavailable, so the
      // operator is told which field is wrong instead of getting a silent no-op.
      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);
      expect(rowAt(1)?.get('startTime')?.errors?.['serverError']).toBe(
        'startTime must not be empty',
      );
      expect(text()).toContain('startTime must not be empty');
      expect(buttonByText('Guardar')?.disabled).toBe(true);

      // Editing that field clears its server error and reopens the save path.
      rowAt(1)?.get('startTime')?.setValue('10:00');
      await settle();

      expect(rowAt(1)?.get('startTime')?.errors?.['serverError']).toBeUndefined();
      expect(buttonByText('Guardar')?.disabled).toBe(false);

      // The operator submits again through the real control, not a direct call.
      buttonByText('Guardar')?.click();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(2);
    });

    it('should ignore a repeated save while the first request is still in flight', async () => {
      setup();
      await settle();

      const inFlight = new Subject<SchedulingAvailabilityResponse>();
      activityService.replaceAvailability.mockReturnValue(inFlight.asObservable());

      component.onSave();
      await settle();

      expect(component.saving()).toBe(true);
      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);

      // A second save while the first `PUT` is unresolved must not open another one.
      component.onSave();
      await settle();

      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);

      inFlight.next(availability([window()]));
      inFlight.complete();
      await settle();

      expect(component.saving()).toBe(false);
    });

    it('should surface a domain-rule 400 as a page-level error', async () => {
      setup({
        saveResult: httpError(400, { message: 'endTime must be after startTime' }),
      });
      await settle();

      component.onSave();
      await settle();

      expect(component.generalError()).toBe('endTime must be after startTime');
      expect(text()).toContain('endTime must be after startTime');
    });

    it('should surface a 404 as a page-level error', async () => {
      setup({ saveResult: httpError(404, { message: 'missing' }) });
      await settle();

      component.onSave();
      await settle();

      expect(component.generalError()).toBe('No se encontró el recurso solicitado.');
    });

    it('should keep a save failure visible while a load failure is also on screen', async () => {
      setup({
        // The first read establishes the known set; the failed re-read leaves a load
        // error on screen without erasing that set, so the save path stays open. The
        // save failure is a plain-message `400`, so its own copy is distinguishable
        // from the `500` load banner: a coupling that hid the save banner behind the
        // load banner would still pass if both failures rendered the same sentence.
        loadResults: [availability([window()]), httpError(500, { message: 'boom' })],
        saveResult: httpError(400, { message: 'Las ventanas no pueden superponerse.' }),
      });
      await settle();

      // The retry path the retry button drives, invoked directly to stage the
      // second (failing) read.
      component.retryLoad();
      await settle();

      expect(component.loadError()).toBeTruthy();

      component.onSave();
      await settle();

      expect(component.loadError()).toBeTruthy();
      expect(component.generalError()).toBe('Las ventanas no pueden superponerse.');
      expect(text()).toContain('Las ventanas no pueden superponerse.');
      // The load banner keeps its own condition and stays visible too.
      expect(text()).toContain('Ocurrió un error en el servidor. Intentá de nuevo más tarde.');
    });
  });

  describe('unknown-set guard', () => {
    it('should not issue a PUT when the initial read failed, even from a direct save call', async () => {
      setup({ loadResults: [httpError(500, { message: 'boom' })] });
      await settle();

      expect(component.loadError()).toBeTruthy();
      // No read completed, so the set is unknown and the save path must refuse.
      expect(component.hasLoadedAvailability()).toBe(false);

      component.onSave();
      await settle();

      // Zero calls at all: an empty body here would delete a template that was
      // never read, and the whole-set PUT deletes whatever it does not receive.
      expect(activityService.replaceAvailability).not.toHaveBeenCalled();
      const emptySetCalls = activityService.replaceAvailability.mock.calls.filter(
        (call) => JSON.stringify(call[1]) === '{"windows":[]}',
      );
      expect(emptySetCalls).toHaveLength(0);

      // Refusing the save must not invent a save error either.
      expect(component.generalError()).toBeNull();
      expect(component.loadError()).toBeTruthy();
    });

    it('should send an empty set after a successful read when every row is removed', async () => {
      setup({ loadResults: [availability([window()])], saveResult: availability([]) });
      await settle();
      expect(component.hasLoadedAvailability()).toBe(true);

      component.onRemoveWindow(0);
      await settle();
      expect(rowCount()).toBe(0);

      component.onSave();
      await settle();

      // A deliberate clear stays legitimate: the exact empty body must reach the
      // service behind a successful read. This is why the guard cannot be
      // "fixed" by forbidding an empty set.
      expect(activityService.replaceAvailability).toHaveBeenCalledTimes(1);
      expect(JSON.stringify(activityService.replaceAvailability.mock.calls[0][1])).toBe(
        '{"windows":[]}',
      );
      expect(activityService.replaceAvailability.mock.calls[0][1]).toEqual({ windows: [] });
      expect(notifications.showSuccess).toHaveBeenCalledWith(
        'Disponibilidad guardada correctamente.',
      );
      expect(component.generalError()).toBeNull();
      expect(component.hasUnsavedChanges()).toBe(false);
    });
  });

  describe('discard guard and navigation', () => {
    it('should report the dirty state to the unsaved-changes guard', async () => {
      setup();
      await settle();

      expect(component.hasUnsavedChanges()).toBe(false);

      component.onAddWindow();
      expect(component.hasUnsavedChanges()).toBe(true);
    });

    it('should navigate back to the list on cancel', async () => {
      setup();
      await settle();

      component.onCancel();

      expect(router.navigate).toHaveBeenCalledWith(['/scheduling/list']);
    });
  });

  describe('write-role gating', () => {
    it('should hide the editor controls for a read-only role', async () => {
      setup({ roles: READ_ROLES });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(buttonByText('Agregar ventana')).toBeUndefined();
      expect(buttonByText('Guardar')).toBeUndefined();
      expect(buttonByText('Volver')).toBeDefined();
    });
  });
});
