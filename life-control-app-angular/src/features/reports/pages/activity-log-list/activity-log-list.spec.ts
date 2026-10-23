import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ActivityLogService } from '../../data/activity-log.service';
import { ActivityLogEntry, ActivityLogPage } from '../../models/activity-log.models';
import { ActivityLogList } from './activity-log-list';

const entry = (overrides: Partial<ActivityLogEntry> = {}): ActivityLogEntry => ({
  id: 'log-1',
  userId: null,
  username: null,
  process: 'HR',
  event: 'CREATE',
  httpMethod: 'POST',
  httpStatus: 201,
  requestPath: '/api/companies/c-1/employees',
  ipAddress: '10.0.0.7',
  userAgent: 'vitest',
  payloadJson: null,
  createdAt: '2026-01-02T03:04:05',
  ...overrides,
});

const page = (content: ActivityLogEntry[], totalElements = content.length): ActivityLogPage => ({
  content,
  totalElements,
  totalPages: 1,
  number: 0,
  size: 25,
  first: true,
  last: true,
});

describe('ActivityLogList', () => {
  let fixture: ComponentFixture<ActivityLogList>;
  let component: ActivityLogList;
  let search: ReturnType<typeof vi.fn>;

  /** Renders the screen over a mock read that answers exactly `rows`. */
  const render = async (rows: ActivityLogEntry[], totalElements?: number): Promise<HTMLElement> => {
    search = vi.fn().mockReturnValue(of(page(rows, totalElements)));
    TestBed.configureTestingModule({
      imports: [ActivityLogList],
      providers: [provideRouter([]), { provide: ActivityLogService, useValue: { search } }],
    });
    fixture = TestBed.createComponent(ActivityLogList);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('should read the first page with no filters on creation', async () => {
    await render([entry()]);

    expect(search).toHaveBeenCalledWith(0, 25, {});
  });

  it('should render the date range and the four remaining filters the endpoint accepts', async () => {
    const el = await render([entry()]);
    const text = el.textContent ?? '';

    expect(el.querySelectorAll('input[type="date"]').length).toBe(2);
    for (const label of ['Desde', 'Hasta', 'Proceso', 'Evento', 'Usuario', 'Método HTTP']) {
      expect(text).toContain(label);
    }
  });

  it('should render one row per entry, falling back from username to userId to a dash', async () => {
    const el = await render([
      entry({ id: 'log-1', username: 'ana.gomez', userId: 'u-1' }),
      entry({ id: 'log-2', username: null, userId: 'u-2' }),
      entry({ id: 'log-3', username: null, userId: null }),
    ]);
    const text = el.textContent ?? '';

    expect(el.querySelectorAll('tr.mat-mdc-row').length).toBe(3);
    expect(text).toContain('ana.gomez');
    expect(text).toContain('u-2');
    expect(text).toContain('—');
    // The date is formatted for the screen, not the raw wire timestamp.
    expect(text).not.toContain('2026-01-02T03:04:05');
  });

  it('should render the process, event, method, status, path and IP of a row', async () => {
    const el = await render([
      entry({
        httpMethod: 'PATCH',
        httpStatus: 409,
        requestPath: '/api/hr/x',
        ipAddress: '10.1.2.3',
      }),
    ]);
    const text = el.textContent ?? '';

    for (const value of ['HR', 'CREATE', 'PATCH', '409', '/api/hr/x', '10.1.2.3']) {
      expect(text).toContain(value);
    }
  });

  it('should render the empty state message when the page has no entries', async () => {
    const el = await render([]);

    expect(el.textContent).toContain('No hay actividad registrada para estos filtros');
    expect(el.querySelectorAll('tr.mat-mdc-row').length).toBe(0);
  });

  it('should request the next page from the server when the paginator changes page', async () => {
    await render([entry()], 41);

    component.onPageChange({ pageIndex: 1, pageSize: 50 });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(search).toHaveBeenLastCalledWith(1, 50, {});
  });

  it('should keep totalElements as the paginator length', async () => {
    await render([entry()], 41);

    expect(component.totalElements()).toBe(41);
  });

  it('should debounce a free-text filter instead of reading on every keystroke', async () => {
    await render([entry()]);
    expect(search).toHaveBeenCalledTimes(1);

    vi.useFakeTimers();
    try {
      component.process.set('HR');
      fixture.detectChanges();

      // A keystroke alone issues nothing: the read waits for the debounce window.
      expect(search).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(300);
      fixture.detectChanges();

      expect(search).toHaveBeenLastCalledWith(0, 25, { process: 'HR' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('should send the date range and the HTTP method as soon as they change', async () => {
    await render([entry()]);

    component.fromDate.set('2026-01-01');
    component.toDate.set('2026-01-31');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(search).toHaveBeenLastCalledWith(0, 25, { from: '2026-01-01', to: '2026-01-31' });

    component.httpMethod.set('POST');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(search).toHaveBeenLastCalledWith(0, 25, {
      from: '2026-01-01',
      to: '2026-01-31',
      httpMethod: 'POST',
    });
  });
});
