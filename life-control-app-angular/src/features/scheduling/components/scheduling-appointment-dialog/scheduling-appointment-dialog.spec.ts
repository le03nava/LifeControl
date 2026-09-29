import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSelect } from '@angular/material/select';
import { of, Observable, Subject, throwError } from 'rxjs';
import { httpErrorMessage } from '@shared/data';
import { ConfirmDialog } from '@shared/ui';
import {
  SchedulingAppointmentDialog,
  SchedulingAppointmentDialogData,
} from './scheduling-appointment-dialog';
import { SchedulingAppointmentService } from '../../data/scheduling-appointment.service';
import { SchedulingStatusService } from '../../data/scheduling-status.service';
import {
  SchedulingCalendarAppointment,
  SchedulingCalendarEntry,
} from '../../models/scheduling-calendar.models';
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

/**
 * The manage-mode copies (D87), pinned literally for the same reason: the
 * discrimination is by the server's free text, so a server copy edit degrades a
 * specific message into the generic one silently and nothing else would fail.
 */
const STATUS_CHANGED_COPY =
  'El estado del turno cambió mientras lo mirabas. Volvé a leer la semana para ver su situación actual.';
const APPOINTMENT_CLOSED_COPY =
  'Este turno ya está cerrado y no admite cambios. Volvé a leer la semana para ver su estado actual.';
const DESTINATION_UNAVAILABLE_COPY =
  'El horario de destino dejó de estar disponible. Volvé a leer la semana y elegí otro.';
const CATALOGUE_FAILED_COPY =
  'No se pudo cargar el catálogo de estados del turno. Cerrá el diálogo y volvé a abrirlo para intentarlo de nuevo.';

/** The `statusName -> statusId` map the catalogue read resolves to in every happy spec. */
const STATUS_IDS: ReadonlyMap<string, string> = new Map([
  ['Scheduled', 'status-scheduled-id'],
  ['Confirmed', 'status-confirmed-id'],
  ['Completed', 'status-completed-id'],
  ['Cancelled', 'status-cancelled-id'],
  ['NoShow', 'status-noshow-id'],
]);

type BookData = Extract<SchedulingAppointmentDialogData, { mode: 'book' }>;
type ManageData = Extract<SchedulingAppointmentDialogData, { mode: 'manage' }>;

describe('SchedulingAppointmentDialog', () => {
  let component: SchedulingAppointmentDialog;
  let fixture: ComponentFixture<SchedulingAppointmentDialog>;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let appointmentServiceMock: {
    bookAppointment: ReturnType<typeof vi.fn>;
    updateStatus: ReturnType<typeof vi.fn>;
    reschedule: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  };
  let statusServiceMock: { loadAppointmentStatusIds: ReturnType<typeof vi.fn> };
  let statusIds$: Observable<ReadonlyMap<string, string>>;

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

  // The embedded projection appointment: it carries no `slotId` and no time of its
  // own (E41), which is why manage mode also receives the wrapping entry and the week.
  const calendarAppointment: SchedulingCalendarAppointment = {
    id: 'appt-1',
    userId: 'ana.gomez',
    customerId: null,
    customerName: 'María López',
    statusId: 'status-scheduled-id',
    statusName: 'Scheduled',
    notes: null,
    enabled: true,
  };

  beforeEach(() => {
    statusIds$ = of(STATUS_IDS);
  });

  function build(data: SchedulingAppointmentDialogData): void {
    dialogRef = { close: vi.fn(), disableClose: false };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
    appointmentServiceMock = {
      bookAppointment: vi.fn().mockReturnValue(of(appointment)),
      updateStatus: vi.fn().mockReturnValue(of(appointment)),
      reschedule: vi.fn().mockReturnValue(of(appointment)),
      remove: vi.fn().mockReturnValue(of(undefined)),
    };
    statusServiceMock = {
      loadAppointmentStatusIds: vi.fn().mockReturnValue(statusIds$),
    };

    TestBed.configureTestingModule({
      imports: [SchedulingAppointmentDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialogMock },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: SchedulingAppointmentService, useValue: appointmentServiceMock },
        { provide: SchedulingStatusService, useValue: statusServiceMock },
      ],
    });

    fixture = TestBed.createComponent(SchedulingAppointmentDialog);
    component = fixture.componentInstance;
  }

  function setup(
    overrides: {
      data?: Partial<BookData>;
      entry?: SchedulingCalendarEntry;
    } = {},
  ): void {
    build({
      mode: 'book',
      entry: overrides.entry ?? entry,
      activityUserId: null,
      ...overrides.data,
    });
  }

  function setupManage(overrides: Partial<ManageData> = {}): void {
    build({
      mode: 'manage',
      entry,
      appointment: calendarAppointment,
      weekEntries: [entry],
      ...overrides,
    });
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

  it('should not read the status catalogue in book mode (D88: once per manage open)', () => {
    setup();
    fixture.detectChanges();

    expect(statusServiceMock.loadAppointmentStatusIds).not.toHaveBeenCalled();
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

  it('should render the no-room state and still submit when the slot has no room left', () => {
    // The page only opens the dialog for `available > 0` (D67), so this is a defensive
    // render of the component's own contract (it renders the slot it is given, D21):
    // `booked === capacity` makes `slotHasRoom` false and the readout says `sin lugar`
    // instead of the remaining count.
    setup({ entry: { ...entry, booked: 4, available: 0 } });
    fixture.detectChanges();

    expect(component.slotHasRoom).toBe(false);
    expect(normalized('.slot-capacity')).toContain('4 / 4');
    expect(normalized('.slot-capacity')).toContain('sin lugar');
    expect(normalized('.slot-capacity')).not.toContain('quedan');

    // No client-side room guard: the dialog still issues its one write and the
    // server's capacity 409 is the authority, so the submit path stays coherent.
    component.form.patchValue({ userId: 'ana.gomez' });
    component.onBook();

    expect(appointmentServiceMock.bookAppointment).toHaveBeenCalledTimes(1);
    expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'booked', appointment });
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

  describe('manage mode (D81, D83, D85)', () => {
    const serverError = (status: number, message: string): HttpErrorResponse =>
      new HttpErrorResponse({
        status,
        error: { status, message, path: '/api/scheduling/appointments', correlationId: 'corr' },
      });

    function firstEdge(name: string) {
      const edge = component.transitions().find((transition) => transition.name === name);
      if (!edge) {
        throw new Error(`expected a ${name} edge on the resolved catalogue`);
      }
      return edge;
    }

    it('should render the appointment facts with the Spanish state noun, not the raw statusName', () => {
      setupManage();
      fixture.detectChanges();

      expect(normalized('.slot-activity')).toBe('Clase de yoga');
      expect(normalized('.slot-when')).toContain('09:00');
      expect(normalized('.slot-when')).toContain('10:00');
      expect(normalized('.slot-capacity')).toContain('1 / 4');
      expect(normalized('.status-label')).toBe('Agendado');
      expect(normalized('.appointment-status')).not.toContain('Scheduled');
      expect(normalized('.appointment-user')).toContain('ana.gomez');
      expect(normalized('.appointment-customer')).toContain('María López');
    });

    it('should render the honest fallbacks when the appointment has no user or customer', () => {
      setupManage({
        appointment: { ...calendarAppointment, userId: null, customerName: null },
      });
      fixture.detectChanges();

      expect(normalized('.appointment-user')).toContain('Sin responsable');
      expect(normalized('.appointment-customer')).toContain('Sin cliente');
    });

    it('should render the notes when the appointment carries them', () => {
      setupManage({ appointment: { ...calendarAppointment, notes: 'Vino con muletas' } });
      fixture.detectChanges();

      expect(normalized('.appointment-notes')).toContain('Vino con muletas');
    });

    it('should offer exactly the four Scheduled edges, labelled from the transition map', () => {
      setupManage();
      fixture.detectChanges();

      expect(component.transitions().map((transition) => transition.label)).toEqual([
        'Confirmar',
        'Completar',
        'Cancelar',
        'Marcar ausente',
      ]);
      for (const label of ['Confirmar', 'Completar', 'Cancelar', 'Marcar ausente']) {
        expect(buttonByText(label)).toBeTruthy();
      }
    });

    it('should offer exactly the three Confirmed edges and no way back to Confirmed', () => {
      setupManage({ appointment: { ...calendarAppointment, statusName: 'Confirmed' } });
      fixture.detectChanges();

      expect(component.transitions().map((transition) => transition.label)).toEqual([
        'Completar',
        'Cancelar',
        'Marcar ausente',
      ]);
      expect(buttonByText('Confirmar')).toBeUndefined();
    });

    it('should render the closed copy, no edge and no reschedule for a terminal status, but still offer removal', () => {
      setupManage({ appointment: { ...calendarAppointment, statusName: 'Completed' } });
      fixture.detectChanges();

      expect(component.isTerminal()).toBe(true);
      expect(component.transitions()).toEqual([]);
      expect(buttonByText('Confirmar')).toBeUndefined();
      expect(buttonByText('Completar')).toBeUndefined();
      expect(normalized('.status-closed')).toContain('cerrado');
      expect(fixture.nativeElement.querySelector('.reschedule')).toBeNull();
      expect(buttonByText('Eliminar turno')).toBeTruthy();
    });

    it('should render the raw server name beside the closed copy when the status is outside the mirror (declared drift)', () => {
      // Unreachable except through drift between this mirror and the server: no seeded
      // appointment carries a name outside the five-name map (G32/G40).
      setupManage({ appointment: { ...calendarAppointment, statusName: 'Rescheduled' } });
      fixture.detectChanges();

      expect(component.isTerminal()).toBe(true);
      expect(component.currentStatusLabel()).toBe('Rescheduled');
      expect(normalized('.status-label')).toBe('Rescheduled');
      expect(normalized('.status-closed')).toContain('cerrado');
    });

    it('should fail closed: a catalogue failure renders no edge button and shows its copy', () => {
      statusIds$ = throwError(() => new HttpErrorResponse({ status: 500 }));
      setupManage();
      fixture.detectChanges();

      expect(component.catalogueFailed()).toBe(true);
      expect(component.transitions()).toEqual([]);
      expect(buttonByText('Confirmar')).toBeUndefined();
      expect(component.generalError()).toBe(CATALOGUE_FAILED_COPY);
      expect(normalized('app-error-banner')).toContain('catálogo de estados');
    });

    it('should disable the edge buttons while the catalogue is loading because no edge has a resolved statusId yet', () => {
      statusIds$ = new Subject<ReadonlyMap<string, string>>();
      setupManage();
      fixture.detectChanges();

      // The mechanism the disabled button actually reads: an unresolved map leaves
      // every edge's `statusId` null, so `!transition.statusId` disables it.
      expect(component.transitions()[0]?.statusId).toBeNull();
      const confirm = buttonByText('Confirmar');
      expect(confirm).toBeTruthy();
      expect(confirm?.disabled).toBe(true);
    });

    it('should PATCH the resolved statusId, not the status name, and close with updated', () => {
      setupManage();
      fixture.detectChanges();

      buttonByText('Confirmar')?.click();

      expect(appointmentServiceMock.updateStatus).toHaveBeenCalledWith('appt-1', {
        statusId: 'status-confirmed-id',
      });
      expect(appointmentServiceMock.updateStatus).not.toHaveBeenCalledWith('appt-1', {
        statusId: 'Confirmed',
      });
      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'updated' });
    });

    it('should list only same-activity, enabled, roomy, different slots as destinations', () => {
      const ownSlot = entry;
      const valid: SchedulingCalendarEntry = {
        ...entry,
        slotId: 'slot-9',
        activityId: 'act-1',
        activityEnabled: true,
        available: 2,
      };
      const foreign: SchedulingCalendarEntry = { ...entry, slotId: 'slot-2', activityId: 'act-2' };
      const retired: SchedulingCalendarEntry = {
        ...entry,
        slotId: 'slot-3',
        activityEnabled: false,
      };
      const full: SchedulingCalendarEntry = { ...entry, slotId: 'slot-4', available: 0 };
      setupManage({ weekEntries: [ownSlot, valid, foreign, retired, full] });
      fixture.detectChanges();

      expect(component.destinations().map((destination) => destination.slotId)).toEqual(['slot-9']);
    });

    it('should render each destination option with its day, its HH:mm range and its remaining capacity', () => {
      const valid: SchedulingCalendarEntry = {
        ...entry,
        slotId: 'slot-9',
        activityId: 'act-1',
        activityEnabled: true,
        available: 2,
        startAt: '2026-10-01T11:00:00',
        endAt: '2026-10-01T12:00:00',
      };
      setupManage({ weekEntries: [entry, valid] });
      fixture.detectChanges();

      const select = fixture.debugElement.query(By.directive(MatSelect))
        .componentInstance as MatSelect;
      select.open();
      fixture.detectChanges();

      const options = Array.from(document.querySelectorAll('mat-option')) as HTMLElement[];
      expect(options.length).toBe(1);
      const text = (options[0].textContent ?? '').replace(/\s+/g, ' ').trim();
      expect(text).toContain('2026-10-01');
      expect(text).toContain('11:00');
      expect(text).toContain('12:00');
      expect(text).toContain('quedan 2');

      select.close();
      fixture.detectChanges();
    });

    it('should render an honest empty state and no select when no destination is available', () => {
      setupManage({ weekEntries: [entry] });
      fixture.detectChanges();

      expect(component.destinations()).toEqual([]);
      expect(fixture.nativeElement.querySelector('mat-select')).toBeNull();
      expect(normalized('.reschedule-empty')).toContain('No hay otro horario');
    });

    it('should PUT the chosen destination and close with updated on success', () => {
      const valid: SchedulingCalendarEntry = { ...entry, slotId: 'slot-9', available: 2 };
      setupManage({ weekEntries: [entry, valid] });
      component.rescheduleTarget.set('slot-9');
      fixture.detectChanges();

      buttonByText('Reprogramar')?.click();

      expect(appointmentServiceMock.reschedule).toHaveBeenCalledWith('appt-1', {
        slotId: 'slot-9',
      });
      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'updated' });
    });

    it('should open ConfirmDialog with destructive: true and issue no request when dismissed', () => {
      setupManage();
      fixture.detectChanges();
      dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });

      component.onRemove();

      expect(dialogMock.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({ data: expect.objectContaining({ destructive: true }) }),
      );
      expect(appointmentServiceMock.remove).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('should DELETE only on an exact true and close with updated', () => {
      setupManage();
      fixture.detectChanges();
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      component.onRemove();

      expect(appointmentServiceMock.remove).toHaveBeenCalledWith('appt-1');
      expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'updated' });
    });

    describe('lifecycle failure vocabulary (D87)', () => {
      it('should treat Invalid status transition as stale with the status-changed copy', () => {
        setupManage();
        fixture.detectChanges();
        appointmentServiceMock.updateStatus.mockReturnValue(
          throwError(() => serverError(409, 'Invalid status transition: Scheduled → NoShow')),
        );

        component.onTransition(firstEdge('Confirmed'));
        fixture.detectChanges();

        expect(component.generalError()).toBe(STATUS_CHANGED_COPY);
        expect(dialogRef.disableClose).toBe(true);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
      });

      it('should treat cannot-be-modified as stale with the already-closed copy', () => {
        setupManage();
        fixture.detectChanges();
        appointmentServiceMock.updateStatus.mockReturnValue(
          throwError(() =>
            serverError(
              409,
              "Scheduling appointment appt-1 cannot be modified while in status 'Completed'",
            ),
          ),
        );

        component.onTransition(firstEdge('Confirmed'));
        fixture.detectChanges();

        expect(component.generalError()).toBe(APPOINTMENT_CLOSED_COPY);
        expect(dialogRef.disableClose).toBe(true);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
      });

      it('should treat a full destination as stale with the capacity copy', () => {
        const valid: SchedulingCalendarEntry = { ...entry, slotId: 'slot-9', available: 2 };
        setupManage({ weekEntries: [entry, valid] });
        component.rescheduleTarget.set('slot-9');
        fixture.detectChanges();
        appointmentServiceMock.reschedule.mockReturnValue(
          throwError(() =>
            serverError(409, 'Slot slot-9 is not bookable: it is full (booked 4 of capacity 4)'),
          ),
        );

        component.onReschedule();
        fixture.detectChanges();

        expect(component.generalError()).toBe(CAPACITY_CONFLICT_COPY);
        expect(dialogRef.disableClose).toBe(true);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
      });

      it('should treat a non-bookable destination alone as stale with the unavailable copy', () => {
        const valid: SchedulingCalendarEntry = { ...entry, slotId: 'slot-9', available: 2 };
        setupManage({ weekEntries: [entry, valid] });
        component.rescheduleTarget.set('slot-9');
        fixture.detectChanges();
        appointmentServiceMock.reschedule.mockReturnValue(
          throwError(() => serverError(409, 'Slot slot-9 is not bookable: the slot is disabled')),
        );

        component.onReschedule();
        fixture.detectChanges();

        expect(component.generalError()).toBe(DESTINATION_UNAVAILABLE_COPY);
        expect(dialogRef.disableClose).toBe(true);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
      });

      it('should treat any 404 as stale with the neutral week copy', () => {
        setupManage();
        fixture.detectChanges();
        appointmentServiceMock.updateStatus.mockReturnValue(
          throwError(() => serverError(404, 'Scheduling appointment not found: appt-1')),
        );

        component.onTransition(firstEdge('Confirmed'));
        fixture.detectChanges();

        expect(component.generalError()).toBe(WEEK_STALE_COPY);
        expect(component.generalError()).not.toBe(SLOT_GONE_COPY);
        expect(dialogRef.disableClose).toBe(true);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'stale' });
      });

      it('should show the banner without a stale verdict on a 403', () => {
        setupManage();
        fixture.detectChanges();
        const forbidden = new HttpErrorResponse({ status: 403 });
        appointmentServiceMock.updateStatus.mockReturnValue(throwError(() => forbidden));

        component.onTransition(firstEdge('Confirmed'));
        fixture.detectChanges();

        expect(component.generalError()).toBe(httpErrorMessage(forbidden));
        expect(dialogRef.disableClose).toBe(false);
        component.cancel();
        expect(dialogRef.close).toHaveBeenCalledWith(null);
      });
    });

    it('should disable every manage action and issue one request on a double click', () => {
      const valid: SchedulingCalendarEntry = {
        ...entry,
        slotId: 'slot-9',
        booked: 2,
        available: 2,
      };
      setupManage({ weekEntries: [entry, valid] });
      const pending = new Subject<SchedulingAppointment>();
      appointmentServiceMock.updateStatus.mockReturnValue(pending.asObservable());
      component.rescheduleTarget.set('slot-9');
      fixture.detectChanges();

      const edge = firstEdge('Confirmed');
      component.onTransition(edge);
      component.onTransition(edge);
      fixture.detectChanges();

      expect(appointmentServiceMock.updateStatus).toHaveBeenCalledTimes(1);
      expect(component.saving()).toBe(true);
      expect(buttonByText('Confirmar')?.disabled).toBe(true);
      expect(buttonByText('Cancelar')?.disabled).toBe(true);
      expect(buttonByText('Reprogramar')?.disabled).toBe(true);
      expect(buttonByText('Eliminar turno')?.disabled).toBe(true);
    });
  });
});
