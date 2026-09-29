/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { SchedulingAgendaSelection, SchedulingDayAgenda } from './scheduling-day-agenda';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';

describe('SchedulingDayAgenda', () => {
  let fixture: ComponentFixture<SchedulingDayAgenda>;
  let component: SchedulingDayAgenda;

  const appointment = (
    overrides: Partial<SchedulingCalendarEntry['appointments'][number]> = {},
  ): SchedulingCalendarEntry['appointments'][number] => ({
    id: 'appointment-1',
    userId: 'user-1',
    customerId: 'customer-1',
    customerName: 'Ana Pérez',
    statusId: 'status-1',
    statusName: 'Confirmed',
    notes: null,
    enabled: true,
    ...overrides,
  });

  const entry = (overrides: Partial<SchedulingCalendarEntry> = {}): SchedulingCalendarEntry => ({
    slotId: 'slot-1',
    activityId: 'activity-1',
    activityName: 'Yoga',
    activityEnabled: true,
    startAt: '2026-09-30T09:00:00',
    endAt: '2026-09-30T10:30:00',
    capacity: 8,
    booked: 1,
    available: 7,
    status: 'Available',
    appointments: [appointment()],
    ...overrides,
  });

  function setup(entries: SchedulingCalendarEntry[], date = '2026-09-30', canManage = false): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingDayAgenda, NoopAnimationsModule],
    });
    fixture = TestBed.createComponent(SchedulingDayAgenda);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('date', date);
    fixture.componentRef.setInput('entries', entries);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function items(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.agenda-item'));
  }

  it('should create', () => {
    setup([entry()]);
    expect(component).toBeTruthy();
  });

  it('should render one row per appointment with the slot time and the status', () => {
    setup([entry()]);

    expect(items()).toHaveLength(1);
    expect(items()[0].querySelector('.agenda-time')?.textContent?.trim()).toBe('09:00–10:30');
    expect(items()[0].querySelector('.agenda-status')?.textContent).toContain('Confirmed');
  });

  it('should name the day it lists, so the agenda is self-describing (F7)', () => {
    setup([entry()], '2026-10-01');

    const heading = (fixture.nativeElement as HTMLElement).querySelector('.agenda-date');
    expect(heading?.textContent?.trim()).toBe('2026-10-01');
  });

  it('should name the day even when the day has no appointments', () => {
    setup([entry({ appointments: [] })], '2026-10-02');

    expect(text()).toContain('2026-10-02');
  });

  it('should render the customer name and the assigned employee', () => {
    setup([entry()]);

    expect(text()).toContain('Ana Pérez');
    expect(text()).toContain('user-1');
  });

  it('should fall back when there is no customer or employee', () => {
    setup([
      entry({
        appointments: [appointment({ userId: null, customerId: null, customerName: null })],
      }),
    ]);

    expect(text()).toContain('Sin cliente');
    expect(text()).toContain('Sin responsable');
  });

  it('should not list an empty slot as an agenda row (D62)', () => {
    setup([entry({ appointments: [] })]);

    expect(items()).toHaveLength(0);
    expect(text()).toContain('No hay turnos agendados');
  });

  it('should list the appointments of the slots that have them and skip the empty ones', () => {
    setup([
      entry({ slotId: 'slot-empty', startAt: '2026-09-30T08:00:00', appointments: [] }),
      entry({
        slotId: 'slot-full',
        startAt: '2026-09-30T11:00:00',
        endAt: '2026-09-30T12:00:00',
        appointments: [appointment({ id: 'appointment-2' })],
      }),
    ]);

    expect(items()).toHaveLength(1);
    expect(items()[0].querySelector('.agenda-time')?.textContent?.trim()).toBe('11:00–12:00');
  });

  it('should mark a soft-deleted appointment', () => {
    setup([entry({ appointments: [appointment({ enabled: false })] })]);

    expect(text()).toContain('Inactiva');
  });

  it('should not mark an enabled appointment', () => {
    setup([entry()]);

    expect(text()).not.toContain('Inactiva');
  });

  it('should render no write affordance', () => {
    setup([entry()]);

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('button')).toHaveLength(0);
  });

  it('should not render a control for a reader, and emit nothing on a row click (D60)', () => {
    setup([entry()], '2026-09-30', false);
    const emitted = vi.fn();
    component.appointmentSelected.subscribe(emitted);

    const item = items()[0];
    expect(item.querySelector('button')).toBeNull();
    item.click();

    expect(emitted).not.toHaveBeenCalled();
  });

  it('should emit the entry and the appointment when a manager activates an enabled row (D90)', () => {
    setup([entry()], '2026-09-30', true);
    const emitted = vi.fn();
    component.appointmentSelected.subscribe(emitted);

    const button = items()[0].querySelector('button');
    expect(button).not.toBeNull();
    (button as HTMLButtonElement).click();

    expect(emitted).toHaveBeenCalledTimes(1);
    const selection = emitted.mock.calls[0][0] as SchedulingAgendaSelection;
    expect(selection.entry.slotId).toBe('slot-1');
    expect(selection.appointment.id).toBe('appointment-1');
  });

  it('should keep every fact visible in the interactive row, like the reader row', () => {
    setup([entry()], '2026-09-30', true);

    const item = items()[0];
    expect(item.querySelector('.agenda-time')?.textContent?.trim()).toBe('09:00–10:30');
    expect(item.querySelector('.agenda-status')?.textContent).toContain('Confirmed');
    expect(item.textContent).toContain('Ana Pérez');
    expect(item.textContent).toContain('user-1');
  });

  it('should fall back to the same literals in the interactive row when customer and employee are missing', () => {
    setup(
      [
        entry({
          appointments: [appointment({ userId: null, customerId: null, customerName: null })],
        }),
      ],
      '2026-09-30',
      true,
    );

    const item = items()[0];
    expect(item.querySelector('button')).not.toBeNull();
    expect(item.textContent).toContain('Sin cliente');
    expect(item.textContent).toContain('Sin responsable');
  });

  it('should keep a soft-deleted appointment non-interactive and silent (D89)', () => {
    setup([entry({ appointments: [appointment({ enabled: false })] })], '2026-09-30', true);
    const emitted = vi.fn();
    component.appointmentSelected.subscribe(emitted);

    const item = items()[0];
    expect(item.textContent).toContain('Inactiva');
    expect(item.querySelector('button')).toBeNull();
    item.click();

    expect(emitted).not.toHaveBeenCalled();
  });

  it('should name the appointment status and time in the manage control label', () => {
    setup([entry()], '2026-09-30', true);

    const button = items()[0].querySelector('button');
    const label = button?.getAttribute('aria-label') ?? '';
    expect(label).toContain('Confirmed');
    expect(label).toContain('09:00');
    expect(label).toContain('10:30');
  });
});
