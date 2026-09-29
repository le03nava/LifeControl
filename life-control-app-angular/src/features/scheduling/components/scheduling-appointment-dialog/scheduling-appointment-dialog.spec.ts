import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { httpErrorMessage } from '@shared/data';
import { ConfirmDialog } from '@shared/ui';
import {
  SchedulingAppointmentDialog,
  SchedulingAppointmentDialogData,
} from './scheduling-appointment-dialog';
import { SchedulingAppointmentService } from '../../data/scheduling-appointment.service';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';
import { SchedulingAppointment } from '../../models/scheduling-appointment.models';

/**
 * The two specific copies the dialog must show for the failures that prove the
 * grid's numbers are stale. Pinned literally on purpose: the discrimination is by
 * free text because the error envelope carries no code (E32), so a copy edit on
 * the server would silently turn a specific message into the generic one.
 */
const CAPACITY_CONFLICT_COPY =
  'El horario se llenó mientras trabajabas: alguien ocupó la última vacante. Volvé a leer la semana y elegí un horario libre.';
const SLOT_GONE_COPY = 'Ese horario ya no existe. Volvé a leer la semana y elegí un horario libre.';
// The neutral copy for the 404s whose cause the client cannot see (activity, status or
// store), where the week is still stale but the slot is not the thing that is gone.
const WEEK_STALE_COPY = 'La semana que ves quedó desactualizada. Volvé a leerla.';

describe('SchedulingAppointmentDialog', () => {
  let component: SchedulingAppointmentDialog;
  let fixture: ComponentFixture<SchedulingAppointmentDialog>;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let appointmentServiceMock: { bookAppointment: ReturnType<typeof vi.fn> };

  const entry: SchedulingCalendarEntry = {
    slotId: 'slot-1',
    activityId: 'act-1',
    activityName: 'Clase de yoga',
    activityEnabled: true,
    startAt: '2026-09-30T09:00:00',
    endAt: '2026-09-30T10:00:00',
    capacity: 4,
    booked: 1,
    available: 3,
    status: 'Scheduled',
    appointments: [],
  };

  const appointment: SchedulingAppointment = {
    id: 'appt-1',
    slotId: 'slot-1',
    startAt: '2026-09-30T09:00:00',
    endAt: '2026-09-30T10:00:00',
    activityId: 'act-1',
    companyStoreId: 'store-1',
    userId: 'ana.gomez',
    customerId: null,
    statusId: 'status-scheduled',
    statusName: 'Scheduled',
    notes: null,
    enabled: true,
    version: 0,
    createdAt: '2026-09-30T09:00:00',
    updatedAt: '2026-09-30T09:00:00',
  };

  function setup(
    overrides: {
      data?: Partial<SchedulingAppointmentDialogData>;
      entry?: SchedulingCalendarEntry;
    } = {},
  ): void {
    dialogRef = { close: vi.fn(), disableClose: false };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
    appointmentServiceMock = { bookAppointment: vi.fn().mockReturnValue(of(appointment)) };

    TestBed.configureTestingModule({
      imports: [SchedulingAppointmentDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialogMock },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            entry: overrides.entry ?? entry,
            activityUserId: null,
            ...overrides.data,
          } satisfies SchedulingAppointmentDialogData,
        },
        { provide: SchedulingAppointmentService, useValue: appointmentServiceMock },
      ],
    });

    fixture = TestBed.createComponent(SchedulingAppointmentDialog);
    component = fixture.componentInstance;
  }

  function normalized(selector: string): string {
    return (fixture.nativeElement.querySelector(selector)?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function buttonByText(label: string): HTMLButtonElement | undefined {
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ) as HTMLButtonElement[];
    return buttons.find((button) => (button.textContent ?? '').trim().includes(label));
  }

  it('should create', () => {
    setup();
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should render the slot facts: activity, day, time and booked / capacity', () => {
    setup();
    fixture.detectChanges();

    expect(normalized('.slot-activity')).toBe('Clase de yoga');
    expect(normalized('.slot-when')).toContain('2026-09-30');
    expect(normalized('.slot-when')).toContain('09:00');
    expect(normalized('.slot-when')).toContain('10:00');
    expect(normalized('.slot-capacity')).toContain('1 / 4');
    expect(normalized('.slot-capacity')).toContain('quedan 3');
  });

  it('should show the retired-activity condition when the activity is retired', () => {
    setup({ entry: { ...entry, activityEnabled: false } });
    fixture.detectChanges();

    expect(normalized('.slot-retired')).toContain('retirada');
  });

  it('should prefill the responsable from the selected activity userId', () => {
    setup({ data: { activityUserId: 'ana.gomez' } });
    fixture.detectChanges();

    expect(component.form.controls.userId.value).toBe('ana.gomez');
  });

  it('should prefill the responsable with an empty string when the activity has no userId', () => {
    setup();
    fixture.detectChanges();

    expect(component.form.controls.userId.value).toBe('');
  });

  describe('submitting', () => {
    it('should send exactly { slotId, userId, notes } with no customerId key', () => {
      setup();
      fixture.detectChanges();
      component.form.patchValue({ userId: 'ana.gomez', notes: 'Vino con muletas' });

      component.onBook();

      const body = appointmentServiceMock.bookAppointment.mock.calls[0][0] as Record<
        string,
        unknown
      >;
      expect(body).toEqual({
        slotId: 'slot-1',
        userId: 'ana.gomez',
        notes: 'Vino con muletas',
      });
      // D66: GET /api/customers is behind lc-admin/lc-sales, so the only role that
      // can book answers 403 on a customer read. The omission is deliberate, and this
      // is the guard the service-level spec cannot provide for the dialog's own body.
      expect(body).not.toHaveProperty('customerId');
      expect(Object.keys(body)).toEqual(['slotId', 'userId', 'notes']);
    });

    it('should send an empty responsable and notes as null, never as an empty string', () => {
      setup();
      fixture.detectChanges();
      component.form.patchValue({ userId: '', notes: '' });

      component.onBook();

      const body = appointmentServiceMock.bookAppointment.mock.calls[0][0] as {
        userId: unknown;
        notes: unknown;
      };
      expect(body.userId).toBeNull();
      expect(body.notes).toBeNull();
      expect(body.userId).not.toBe('');
      expect(body.userId).not.toBeUndefined();
    });

    it('should send a whitespace-only responsable and notes as null', () => {
      setup();
      fixture.detectChanges();
      component.form.patchValue({ userId: '   ', notes: ' \n\t ' });

      component.onBook();

      const body = appointmentServiceMock.bookAppointment.mock.calls[0][0] as {
        userId: unknown;
        notes: unknown;
      };
      expect(body.userId).toBeNull();
      expect(body.notes).toBeNull();
    });

    it('should close with the created appointment on success', () => {
      setup();
      fixture.detectChanges();

      component.onBook();

      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'booked', appointment });
    });

    it('should not fire a second request while the first is in flight', () => {
      setup();
      fixture.detectChanges();
      const pending = new Subject<SchedulingAppointment>();
      appointmentServiceMock.bookAppointment.mockReturnValue(pending.asObservable());

      component.onBook();
      component.onBook();

      expect(appointmentServiceMock.bookAppointment).toHaveBeenCalledTimes(1);
      expect(component.saving()).toBe(true);
    });
  });

  describe('failures that prove the grid is stale', () => {
    it('should show the capacity-conflict copy, stay open and close stale', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: {
                status: 409,
                message: 'Slot slot-1 is not bookable: it is full (booked 4 of capacity 4)',
                path: '/api/scheduling/appointments',
                timestamp: '2026-09-30T09:01:00',
                correlationId: 'corr-1',
              },
            }),
        ),
      );

      component.onBook();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.generalError()).toBe(CAPACITY_CONFLICT_COPY);
      expect(component.generalError()).not.toBe(
        httpErrorMessage(new HttpErrorResponse({ status: 409 })),
      );
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-error-banner')).toBeTruthy();

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
    });

    it('should show the slot-gone copy on a 404, stay open and close stale', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 404,
              error: {
                status: 404,
                message: 'Scheduling slot not found: slot-1',
                path: '/api/scheduling/appointments',
                timestamp: '2026-09-30T09:01:00',
                correlationId: 'corr-2',
              },
            }),
        ),
      );

      component.onBook();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.generalError()).toBe(SLOT_GONE_COPY);

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
    });

    it('should show the neutral copy, not the slot-gone one, for a 404 that is not the slot', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 404,
              error: {
                status: 404,
                message: 'Scheduling activity not found with id: act-1',
                path: '/api/scheduling/appointments',
                timestamp: '2026-09-30T09:01:00',
                correlationId: 'corr-3',
              },
            }),
        ),
      );

      component.onBook();

      // The staleness verdict is unchanged; only the cause the copy named was wrong.
      expect(component.generalError()).toBe(WEEK_STALE_COPY);
      expect(component.generalError()).not.toBe(SLOT_GONE_COPY);

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
    });
  });

  describe('close guard for a stale week or an in-flight write', () => {
    it('should guard the close and keep the stale result after a conflict on an untouched form', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: {
                status: 409,
                message: 'Slot slot-1 is not bookable: it is full (booked 4 of capacity 4)',
                correlationId: 'corr-1',
              },
            }),
        ),
      );

      component.onBook();
      fixture.detectChanges();

      // The form was never touched, so the original dirty-only guard left `disableClose`
      // false: Material would close the ref itself with `undefined` and the stale result
      // would never reach the page.
      expect(component.formDirty()).toBe(false);
      expect(dialogRef.disableClose).toBe(true);

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
    });

    it('should disable cancel and guard the close while a booking is in flight', () => {
      setup();
      fixture.detectChanges();
      const pending = new Subject<SchedulingAppointment>();
      appointmentServiceMock.bookAppointment.mockReturnValue(pending.asObservable());

      component.onBook();
      fixture.detectChanges();

      expect(buttonByText('Cancelar')?.disabled).toBe(true);
      expect(dialogRef.disableClose).toBe(true);

      pending.next(appointment);
      fixture.detectChanges();

      expect(buttonByText('Cancelar')?.disabled).toBe(false);
      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'booked', appointment });
    });
  });

  describe('any other failure', () => {
    it('should show the shared httpErrorMessage copy, stay open and close null', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      component.onBook();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.generalError()).toBe(
        httpErrorMessage(new HttpErrorResponse({ status: 500 })),
      );

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });

    it('should not treat a 409 without the full-capacity text as stale', () => {
      setup();
      fixture.detectChanges();
      appointmentServiceMock.bookAppointment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { status: 409, message: 'Slot slot-1 is not bookable', correlationId: 'c' },
            }),
        ),
      );

      component.onBook();
      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });
  });

  describe('dirty-close guard', () => {
    it('should close directly when the form was never edited', () => {
      setup();
      fixture.detectChanges();

      component.cancel();

      expect(dialogMock.open).not.toHaveBeenCalled();
      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });

    it('should disable close and confirm before discarding typed input', () => {
      setup();
      fixture.detectChanges();

      component.form.controls.notes.setValue('Vino con muletas');
      fixture.detectChanges();

      expect(component.formDirty()).toBe(true);
      expect(dialogRef.disableClose).toBe(true);

      component.cancel();

      expect(dialogMock.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({ destructive: true }),
        }),
      );
      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });

    it('should keep the dialog open when the discard is declined', () => {
      setup();
      fixture.detectChanges();
      dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });
      component.form.controls.notes.setValue('Vino con muletas');

      component.cancel();

      expect(dialogRef.close).not.toHaveBeenCalled();
    });
  });
});
