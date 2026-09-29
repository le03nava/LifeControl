/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { SchedulingWeekGrid } from './scheduling-week-grid';
import { buildWeek, SchedulingWeekDay } from '../../data/scheduling-calendar-week';
import { SchedulingCalendarEntry } from '../../models/scheduling-calendar.models';

describe('SchedulingWeekGrid', () => {
  let fixture: ComponentFixture<SchedulingWeekGrid>;
  let component: SchedulingWeekGrid;

  const weekStart = new Date(2026, 8, 28);

  const entry = (overrides: Partial<SchedulingCalendarEntry> = {}): SchedulingCalendarEntry => ({
    slotId: 'slot-1',
    activityId: 'activity-1',
    activityName: 'Yoga',
    activityEnabled: true,
    startAt: '2026-09-28T09:00:00',
    endAt: '2026-09-28T10:30:00',
    capacity: 8,
    booked: 3,
    available: 5,
    status: 'Available',
    appointments: [],
    ...overrides,
  });

  function setup(
    days: SchedulingWeekDay[],
    selectedDate: string | null = null,
    canBook = false,
  ): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingWeekGrid, NoopAnimationsModule],
    });
    fixture = TestBed.createComponent(SchedulingWeekGrid);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('days', days);
    fixture.componentRef.setInput('selectedDate', selectedDate);
    fixture.componentRef.setInput('canBook', canBook);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function columns(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.day-column'));
  }

  it('should create', () => {
    setup(buildWeek(weekStart, []));
    expect(component).toBeTruthy();
  });

  it('should render seven columns labelled Monday to Sunday in ISO order', () => {
    setup(buildWeek(weekStart, []));

    expect(columns()).toHaveLength(7);
    const labels = columns().map(
      (column) => column.querySelector('.day-name')?.textContent?.trim() ?? '',
    );
    expect(labels).toEqual([
      'Lunes',
      'Martes',
      'Miércoles',
      'Jueves',
      'Viernes',
      'Sábado',
      'Domingo',
    ]);
  });

  it('should render a block from the slot with its HH:mm range and its booked / capacity', () => {
    setup(buildWeek(weekStart, [entry()]));

    const block = (fixture.nativeElement as HTMLElement).querySelector('.slot-block');
    expect(block).not.toBeNull();
    expect(block?.querySelector('.slot-activity')?.textContent).toContain('Yoga');
    expect(block?.querySelector('.slot-time')?.textContent?.trim()).toBe('09:00–10:30');
    expect(block?.querySelector('.slot-capacity')?.textContent?.trim()).toBe('3 / 8');
  });

  it('should render the time from the slot, never from an appointment', () => {
    // An appointment carrying a different hour is not part of the wire shape, but
    // the assertion pins that the block reads the entry's own startAt/endAt.
    setup(
      buildWeek(weekStart, [
        entry({
          startAt: '2026-09-28T08:00:00',
          endAt: '2026-09-28T08:45:00',
          appointments: [
            {
              id: 'appointment-1',
              userId: null,
              customerId: null,
              customerName: null,
              statusId: 'status-1',
              statusName: 'Scheduled',
              notes: null,
              enabled: true,
            },
          ],
        }),
      ]),
    );

    expect(text()).toContain('08:00–08:45');
  });

  it('should still render a retired activity and mark it (D57)', () => {
    setup(buildWeek(weekStart, [entry({ activityEnabled: false })]));

    expect(text()).toContain('Yoga');
    expect(text()).toContain('Actividad retirada');
  });

  it('should not mark an enabled activity as retired', () => {
    setup(buildWeek(weekStart, [entry()]));

    expect(text()).not.toContain('Actividad retirada');
  });

  it('should render no clickable block and no write affordance (D65)', () => {
    setup(buildWeek(weekStart, [entry()]));

    const block = (fixture.nativeElement as HTMLElement).querySelector('.slot-block');
    expect(block?.querySelector('button')).toBeNull();
    expect(block?.querySelector('a')).toBeNull();
    expect(block?.querySelector('[aria-label]')).toBeNull();
    // The only interactive elements are the day headers that select the agenda.
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    expect(buttons).toHaveLength(7);
    expect(buttons.every((button) => button.classList.contains('day-header'))).toBe(true);
  });

  it('should emit the selected day when a day header is clicked', () => {
    setup(buildWeek(weekStart, [entry()]));
    const spy = vi.fn();
    component.daySelected.subscribe(spy);

    (columns()[2].querySelector('.day-header') as HTMLButtonElement).click();

    expect(spy).toHaveBeenCalledWith('2026-09-30');
  });

  it('should mark the selected day column', () => {
    setup(buildWeek(weekStart, []), '2026-09-30');

    const selected = columns().filter((column) => column.classList.contains('selected'));
    expect(selected).toHaveLength(1);
    expect(selected[0].querySelector('.day-date')?.textContent).toContain('30');
  });

  describe('the slot selection (D57, D65, D74)', () => {
    it('should emit the selected slot when a bookable block is clicked', () => {
      const slot = entry();
      setup(buildWeek(weekStart, [slot]), null, true);
      const spy = vi.fn();
      component.slotSelected.subscribe(spy);

      const control = (fixture.nativeElement as HTMLElement).querySelector(
        '.slot-block.bookable',
      ) as HTMLButtonElement;
      expect(control?.tagName).toBe('BUTTON');
      control.click();

      expect(spy).toHaveBeenCalledWith(slot);
    });

    it('should give the booking control its own accessible name', () => {
      setup(buildWeek(weekStart, [entry()]), null, true);

      const control = (fixture.nativeElement as HTMLElement).querySelector('.slot-block.bookable');
      const label = control?.getAttribute('aria-label') ?? '';
      expect(label).toContain('Yoga');
      expect(label).toContain('09:00');
    });

    it('should render no interactive block and emit nothing when the user cannot book (D74)', () => {
      setup(buildWeek(weekStart, [entry()]), null, false);
      const spy = vi.fn();
      component.slotSelected.subscribe(spy);

      const block = (fixture.nativeElement as HTMLElement).querySelector('.slot-block');
      expect(block?.tagName).toBe('ARTICLE');
      expect(block?.querySelector('button')).toBeNull();
      (block as HTMLElement).click();

      // The only interactive elements remain the seven day headers.
      const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
      expect(buttons).toHaveLength(7);
      expect(buttons.every((button) => button.classList.contains('day-header'))).toBe(true);
      expect(spy).not.toHaveBeenCalled();
    });

    it('should not offer booking on a retired activity, even when the user can book (D57)', () => {
      setup(buildWeek(weekStart, [entry({ activityEnabled: false })]), null, true);
      const spy = vi.fn();
      component.slotSelected.subscribe(spy);

      const block = (fixture.nativeElement as HTMLElement).querySelector('.slot-block');
      expect(block?.tagName).toBe('ARTICLE');
      expect(block?.querySelector('button')).toBeNull();
      (block as HTMLElement).click();

      // The block still renders its facts, marker included (D37, D57).
      expect(text()).toContain('Yoga');
      expect(text()).toContain('Actividad retirada');
      expect(spy).not.toHaveBeenCalled();
    });

    it('should not offer booking on a full slot, whose 409 would read as stale (D21)', () => {
      setup(buildWeek(weekStart, [entry({ capacity: 8, booked: 8, available: 0 })]), null, true);
      const spy = vi.fn();
      component.slotSelected.subscribe(spy);

      const block = (fixture.nativeElement as HTMLElement).querySelector('.slot-block');
      expect(block?.tagName).toBe('ARTICLE');
      expect(block?.querySelector('button')).toBeNull();
      (block as HTMLElement).click();

      // The booked / capacity readout still renders for every block.
      expect(block?.querySelector('.slot-capacity')?.textContent?.trim()).toBe('8 / 8');
      expect(spy).not.toHaveBeenCalled();
    });

    it('should keep the day header working while blocks are bookable', () => {
      setup(buildWeek(weekStart, [entry()]), null, true);
      const spy = vi.fn();
      component.daySelected.subscribe(spy);

      (columns()[2].querySelector('.day-header') as HTMLButtonElement).click();

      expect(spy).toHaveBeenCalledWith('2026-09-30');
    });
  });
});
