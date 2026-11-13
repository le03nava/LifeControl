import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { TaxRate } from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';
import { REGION_SCOPE_WARNING } from '../tax-rate-declare-dialog/tax-rate-declare-dialog';
import { TaxRateSupersedeDialog } from './tax-rate-supersede-dialog';

const COMPANY_ID = 'company-1';

function superseded(overrides: Partial<TaxRate> = {}): TaxRate {
  return {
    id: 'rate-1',
    companyId: COMPANY_ID,
    taxCode: 'IVA',
    rate: 0.16,
    effectiveFrom: '2026-11-10',
    enabled: true,
    regionId: null,
    regionName: null,
    ...overrides,
  };
}

function successor(overrides: Partial<TaxRate> = {}): TaxRate {
  return {
    ...superseded(),
    id: 'rate-2',
    rate: 0.2,
    effectiveFrom: '2027-01-01',
    ...overrides,
  };
}

describe('TaxRateSupersedeDialog', () => {
  let fixture: ComponentFixture<TaxRateSupersedeDialog>;
  let component: TaxRateSupersedeDialog;
  let taxRateService: { supersedeTaxRate: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let data: { companyId: string; rate: TaxRate };

  /** Builds the dialog over one superseded row; the row is the only input the form is allowed to read. */
  async function build(rate: TaxRate): Promise<void> {
    data = { companyId: COMPANY_ID, rate };
    await TestBed.configureTestingModule({
      imports: [TaxRateSupersedeDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: TaxRateService, useValue: taxRateService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TaxRateSupersedeDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    taxRateService = { supersedeTaxRate: vi.fn(() => of(successor())) };
    dialogRef = { close: vi.fn() };
  });

  it('presents the superseded code, scope, rate and day as read-only text, never as form controls', async () => {
    await build(superseded({ regionId: 'region-norte', regionName: 'Norte', rate: 0.08 }));

    const readonly = fixture.nativeElement.querySelector('.superseded-row') as HTMLElement;
    expect(readonly).not.toBeNull();
    expect(readonly.textContent).toContain('IVA');
    expect(readonly.textContent).toContain('Norte');
    expect(readonly.textContent).toContain('8 %');
    expect(readonly.textContent).toContain('10/11/2026');

    // An operator who believes he is editing the row is the failure S5 exists to prevent: those four facts
    // cannot be typed in, so the row on screen has no control behind it.
    expect(readonly.querySelectorAll('input, textarea, select, mat-select').length).toBe(0);
  });

  it('names the company-wide scope in words when the superseded row has no region', async () => {
    await build(superseded());

    const readonly = fixture.nativeElement.querySelector('.superseded-row') as HTMLElement;
    expect(readonly.textContent).toContain('Toda la compañía');
  });

  it('sends the percentage typed as the fraction the contract takes', async () => {
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2027-01-01' });

    component.onSave();

    expect(taxRateService.supersedeTaxRate).toHaveBeenCalledWith(COMPANY_ID, 'rate-1', {
      rate: 0.2,
      effectiveFrom: '2027-01-01',
      enabled: true,
    });
  });

  it('carries no tax code and no region: the successor inherits both from the row it succeeds', async () => {
    await build(superseded({ regionId: 'region-norte', regionName: 'Norte' }));
    component.form().patchValue({ ratePercent: 12.3456, effectiveFrom: '2027-01-01' });

    component.onSave();

    const [, , request] = taxRateService.supersedeTaxRate.mock.calls[0];
    expect(Object.keys(request as object).sort()).toEqual(['effectiveFrom', 'enabled', 'rate']);
    expect(request.rate).toBe(0.123456);
  });

  it('refuses the superseded day itself without calling the server', async () => {
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2026-11-10' });
    fixture.detectChanges();

    component.onSave();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(taxRateService.supersedeTaxRate).not.toHaveBeenCalled();
    expect(component.form().controls.effectiveFrom.hasError('notAfter')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('posterior al 10/11/2026');
  });

  it('refuses any day earlier than the superseded one too, since those documents are its answer', async () => {
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2026-11-09' });

    component.onSave();
    await fixture.whenStable();

    expect(taxRateService.supersedeTaxRate).not.toHaveBeenCalled();
    expect(component.form().controls.effectiveFrom.hasError('notAfter')).toBe(true);
  });

  it('states what the successor does to the row, from the rows themselves', async () => {
    await build(superseded());
    component.form().controls.effectiveFrom.setValue('2027-01-01');
    fixture.detectChanges();

    const consequence = fixture.nativeElement.querySelector(
      '.superseded-consequence',
    ) as HTMLElement;
    expect(consequence.textContent).toContain('10/11/2026');
    expect(consequence.textContent).toContain('01/01/2027');
    expect(consequence.textContent).toContain('queda intacta');
  });

  it('repeats the regional commitment when the scope it inherits is a region', async () => {
    await build(superseded({ regionId: 'region-norte', regionName: 'Norte' }));

    const warning = fixture.nativeElement.querySelector('.scope-warning') as HTMLElement;
    expect(warning.textContent).toContain(REGION_SCOPE_WARNING);
  });

  it('closes with the successor the server wrote, which is what the list re-reads', async () => {
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2027-01-01' });

    component.onSave();

    expect(dialogRef.close).toHaveBeenCalledWith(successor());
  });

  it('keeps the dialog open and names the day rule when the server refuses the date', async () => {
    taxRateService.supersedeTaxRate.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, statusText: 'Bad Request' })),
    );
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2027-01-01' });

    component.onSave();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.formError()).toContain('posterior al 10/11/2026');
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('names the missing row, not the code, when the superseded rate is a 404', async () => {
    taxRateService.supersedeTaxRate.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 404, statusText: 'Not Found' })),
    );
    await build(superseded());
    component.form().patchValue({ ratePercent: 20, effectiveFrom: '2027-01-01' });

    component.onSave();
    await fixture.whenStable();

    expect(component.formError()).toContain('ya no existe');
  });

  it('closes with null on cancel, so the page reloads nothing', async () => {
    await build(superseded());

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });
});
