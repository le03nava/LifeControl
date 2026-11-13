import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CompanyContextService, NotificationService } from '@shared/data';
import { TaxRate } from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';
import { TaxRateList, todayIso } from './tax-rate-list';

/** A `YYYY-MM-DD` day shifted from *today*, so the "resolves today" mark is never a fixed date. */
function dayShift(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return todayIso(date);
}

function taxRate(overrides: Partial<TaxRate> = {}): TaxRate {
  return {
    id: 'rate-1',
    companyId: 'company-1',
    taxCode: 'IVA',
    rate: 0.16,
    effectiveFrom: dayShift(-1),
    enabled: true,
    regionId: null,
    regionName: null,
    ...overrides,
  };
}

describe('TaxRateList', () => {
  let fixture: ComponentFixture<TaxRateList>;
  let service: {
    getTaxRates: ReturnType<typeof vi.fn>;
    supersedeTaxRate: ReturnType<typeof vi.fn>;
    setTaxRateEnabled: ReturnType<typeof vi.fn>;
  };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn>; showError: ReturnType<typeof vi.fn> };

  async function render(rates: TaxRate[] | Error): Promise<void> {
    service.getTaxRates.mockReturnValue(
      rates instanceof Error ? throwError(() => rates) : of(rates),
    );
    fixture = TestBed.createComponent(TaxRateList);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = {
      getTaxRates: vi.fn(),
      supersedeTaxRate: vi.fn(() => of(taxRate({ id: 'rate-successor' }))),
      setTaxRateEnabled: vi.fn(() => of(taxRate({ enabled: false }))),
    };
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    notifications = { showSuccess: vi.fn(), showError: vi.fn() };
    TestBed.configureTestingModule({
      imports: [TaxRateList],
      providers: [
        provideRouter([]),
        { provide: TaxRateService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: notifications },
        {
          provide: CompanyContextService,
          useValue: { currentCompany: signal({ id: 'company-1', companyName: 'Demo' }) },
        },
      ],
    });
  });

  it('renders one row per rate with its code, explicit scope, rate as a percentage, day and state', async () => {
    await render([
      taxRate({ id: 'a', rate: 0.16, effectiveFrom: '2026-11-10' }),
      taxRate({
        id: 'b',
        taxCode: 'NA',
        rate: 0,
        effectiveFrom: '2020-01-01',
        enabled: false,
        regionId: 'region-norte',
        regionName: 'Norte',
      }),
    ]);

    const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
    expect(rows.length).toBe(2);

    // The scope is a column, never implied: the company-wide row says so in words.
    expect(rows[0].textContent).toContain('IVA');
    expect(rows[0].textContent).toContain('Toda la compañía');
    expect(rows[0].textContent).toContain('16 %');
    expect(rows[0].textContent).toContain('10/11/2026');
    expect(rows[0].textContent).toContain('Vigente');

    expect(rows[1].textContent).toContain('Norte');
    expect(rows[1].textContent).toContain('0 %');
    expect(rows[1].textContent).toContain('Retirada');
  });

  it('marks exactly the enabled row that resolves today, per code and per scope', async () => {
    await render([
      // Company-wide: the newest enabled row dated up to today wins.
      taxRate({ id: 'applies', rate: 0.16, effectiveFrom: dayShift(-1) }),
      // Dated after today: it does not answer any document yet.
      taxRate({ id: 'future', rate: 0.2, effectiveFrom: dayShift(1) }),
      // Another scope is its own race: a region row answers its own places.
      taxRate({
        id: 'region',
        rate: 0.08,
        effectiveFrom: dayShift(-30),
        regionId: 'region-norte',
        regionName: 'Norte',
      }),
      // A retired row never answers, whatever its date.
      taxRate({ id: 'retired', rate: 0.5, effectiveFrom: '2020-01-01', enabled: false }),
    ]);

    const marked = fixture.nativeElement.querySelectorAll('.resolves-today');
    expect(marked.length).toBe(2);

    const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
    const markedRows = Array.from(rows).filter((row) =>
      (row as HTMLElement).querySelector('.resolves-today'),
    );
    expect(markedRows.length).toBe(2);
    expect((markedRows[0] as HTMLElement).textContent).toContain('16 %');
    expect((markedRows[1] as HTMLElement).textContent).toContain('Norte');
  });

  it('orders by code, then by scope, then newest first — never by the arrival order of the answer', async () => {
    await render([
      taxRate({ id: 'region', rate: 0.08, effectiveFrom: '2026-01-01', regionName: 'Norte' }),
      taxRate({ id: 'newer', rate: 0.2, effectiveFrom: '2026-11-01' }),
      taxRate({ id: 'older', rate: 0.16, effectiveFrom: '2026-01-01' }),
      taxRate({ id: 'na', taxCode: 'NA', rate: 0, effectiveFrom: '2026-01-01' }),
    ]);

    // The code is read from its own cell: slicing the row's flattened text would read into the scope
    // column of the last row, which is a neighbour's value and not the order this test pins.
    const codes = Array.from(fixture.nativeElement.querySelectorAll('tr.mat-mdc-row')).map((row) =>
      (row as HTMLElement).querySelector('td')?.textContent?.trim(),
    );
    const rows = Array.from(fixture.nativeElement.querySelectorAll('tr.mat-mdc-row')).map(
      (row) => (row as HTMLElement).textContent ?? '',
    );
    expect(codes).toEqual(['IVA', 'IVA', 'IVA', 'NA']);
    expect(rows[0]).toContain('20 %');
    expect(rows[1]).toContain('16 %');
    expect(rows[2]).toContain('Norte');
  });

  it('re-reads with includeDisabled when the toggle asks for retired rows', async () => {
    await render([taxRate()]);
    expect(service.getTaxRates).toHaveBeenCalledWith('company-1', false);

    const toggle: HTMLElement | null =
      fixture.nativeElement.querySelector('mat-slide-toggle button');
    expect(toggle).not.toBeNull();
    toggle?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(service.getTaxRates).toHaveBeenCalledWith('company-1', true);
  });

  it('states the failure instead of an empty list when the read fails', async () => {
    await render(new Error('Http failure response for …: 500 Server Error'));

    expect(fixture.nativeElement.querySelector('.error-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tr.mat-mdc-row').length).toBe(0);
  });

  it('renders an empty state rather than an empty table when the company declares no rate', async () => {
    await render([]);

    expect(fixture.nativeElement.querySelector('.empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tr.mat-mdc-row').length).toBe(0);
  });

  it('quotes the refusal this screen exists to resolve, so the empty state names the fix', async () => {
    await render([]);

    const empty = fixture.nativeElement.querySelector('.empty-state') as HTMLElement;
    expect(empty.textContent).toContain(
      "Cannot write the line: tax code IVA has no enabled rate applicable on 2026-11-10, the document's date.",
    );
  });

  it('opens the declare dialog with the company, and re-reads the list when a rate comes back', async () => {
    await render([taxRate()]);
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);
    dialog.open.mockReturnValue({ afterClosed: () => of(taxRate({ id: 'rate-new' })) });

    const action = fixture.nativeElement.querySelector(
      '.declare-action',
    ) as HTMLButtonElement | null;
    expect(action).not.toBeNull();
    action?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ data: { companyId: 'company-1' } }),
    );
    // Re-read after a write: the list never keeps a cached answer the operator's own write contradicts.
    expect(service.getTaxRates).toHaveBeenCalledTimes(2);
    expect(notifications.showSuccess).toHaveBeenCalled();
  });

  it('does not re-read the list when the declare dialog is dismissed', async () => {
    await render([taxRate()]);

    const action = fixture.nativeElement.querySelector(
      '.declare-action',
    ) as HTMLButtonElement | null;
    action?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalled();
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);
    expect(notifications.showSuccess).not.toHaveBeenCalled();
  });

  it('supersedes a row through the dialog and re-reads the list afterwards', async () => {
    // The dialog closes with the rate the server appended; `undefined` is a dismissal, pinned separately.
    dialog.open.mockReturnValue({ afterClosed: () => of(taxRate({ id: 'rate-successor' })) });
    await render([taxRate({ id: 'rate-1', rate: 0.16 })]);
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);

    const supersede = fixture.nativeElement.querySelector(
      '.supersede-action',
    ) as HTMLButtonElement | null;
    expect(supersede).not.toBeNull();
    supersede?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    // The dialog receives the row it supersedes: the code, the scope and the day the successor must be later
    // than all come from that row, and the server inherits the scope rather than trusting what a form says.
    expect(dialog.open).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        data: expect.objectContaining({
          companyId: 'company-1',
          rate: expect.objectContaining({ id: 'rate-1' }),
        }),
      }),
    );
    expect(service.getTaxRates).toHaveBeenCalledTimes(2);
    expect(notifications.showSuccess).toHaveBeenCalled();
  });

  it('retires a row through the confirmation, sending the flag the row does not have', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    await render([taxRate({ id: 'rate-1', enabled: true })]);

    const retire = fixture.nativeElement.querySelector(
      '.retire-action',
    ) as HTMLButtonElement | null;
    expect(retire).not.toBeNull();
    retire?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(service.setTaxRateEnabled).toHaveBeenCalledWith('company-1', 'rate-1', false);
    expect(service.getTaxRates).toHaveBeenCalledTimes(2);
  });

  it('reinstates a retired row, labelled as a reinstatement and never as a deletion', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    await render([taxRate({ id: 'rate-1', enabled: false })]);

    const reinstate = fixture.nativeElement.querySelector(
      '.reinstate-action',
    ) as HTMLButtonElement | null;
    expect(reinstate).not.toBeNull();
    expect((reinstate as HTMLElement).textContent).toContain('Reincorporar');
    reinstate?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(service.setTaxRateEnabled).toHaveBeenCalledWith('company-1', 'rate-1', true);
  });

  it('writes nothing when the retire confirmation is dismissed', async () => {
    await render([taxRate({ id: 'rate-1', enabled: true })]);

    const retire = fixture.nativeElement.querySelector(
      '.retire-action',
    ) as HTMLButtonElement | null;
    retire?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalled();
    expect(service.setTaxRateEnabled).not.toHaveBeenCalled();
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);
  });

  it('writes nothing when the supersede dialog is cancelled', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(null) });
    await render([taxRate({ id: 'rate-1' })]);

    const supersede = fixture.nativeElement.querySelector(
      '.supersede-action',
    ) as HTMLButtonElement | null;
    supersede?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalled();
    expect(notifications.showSuccess).not.toHaveBeenCalled();
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);
  });

  it('states what retiring keeps — the row as history — and never offers a deletion', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    await render([taxRate({ id: 'rate-1', enabled: true })]);

    (fixture.nativeElement.querySelector('.retire-action') as HTMLButtonElement | null)?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const { data } = dialog.open.mock.calls[0][1] as {
      data: { title: string; message: string; confirmLabel: string };
    };
    expect(data.title).toBe('Retirar tasa');
    expect(data.confirmLabel).toBe('Retirar');
    expect(data.message).toContain('queda como historia');
    expect(data.message).toContain('deja de resolver');
    expect(data.message).toContain('la siguen resolviendo');
    // The row is kept, so the word that promises a deletion never appears on this path.
    expect(JSON.stringify(data)).not.toContain('Eliminar');
  });

  it('surfaces a refused retire instead of a silent no-op', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    service.setTaxRateEnabled.mockReturnValue(throwError(() => new Error('boom')));
    await render([taxRate({ id: 'rate-1', enabled: true })]);

    (fixture.nativeElement.querySelector('.retire-action') as HTMLButtonElement | null)?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(notifications.showError).toHaveBeenCalled();
    expect(notifications.showSuccess).not.toHaveBeenCalled();
  });

  it('confirms nothing when the supersede dialog is dismissed with ESC or a backdrop click', async () => {
    // `MatDialogRef.afterClosed()` answers `undefined` for every dismissal that is not an explicit cancel.
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    await render([taxRate({ id: 'rate-1' })]);

    const supersede = fixture.nativeElement.querySelector(
      '.supersede-action',
    ) as HTMLButtonElement | null;
    supersede?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalled();
    expect(notifications.showSuccess).not.toHaveBeenCalled();
    expect(service.getTaxRates).toHaveBeenCalledTimes(1);
  });
});
