import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { TaxCodeService } from '../../data/tax-code.service';
import { TaxRate } from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';
import { TaxRateDeclareDialog, REGION_SCOPE_WARNING } from './tax-rate-declare-dialog';

const COMPANY_ID = 'company-1';
const REGION_ID = 'region-norte';

function declared(overrides: Partial<TaxRate> = {}): TaxRate {
  return {
    id: 'rate-new',
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

/** A button by the label the operator reads, because that is the only name the control has on screen. */
function buttonByText(root: HTMLElement, label: string): HTMLButtonElement | undefined {
  return Array.from(root.querySelectorAll('button')).find(
    (button) => button.textContent?.trim() === label,
  );
}

describe('TaxRateDeclareDialog', () => {
  let fixture: ComponentFixture<TaxRateDeclareDialog>;
  let component: TaxRateDeclareDialog;
  let taxRateService: { declareTaxRate: ReturnType<typeof vi.fn> };
  let taxCodeService: { getTaxCodes: ReturnType<typeof vi.fn> };
  let countryService: { getCountries: ReturnType<typeof vi.fn> };
  let regionService: { getRegions: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };

  /**
   * Builds the dialog over the mocks the test set up.
   *
   * The mocks are stated per test instead of inside `beforeEach` because the two reads this dialog
   * starts with — the tax-code catalog and the scope cascade — are the state under test: a slow one
   * and a failed one are cases, not accidents.
   */
  async function build(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [TaxRateDeclareDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { companyId: COMPANY_ID } },
        { provide: TaxRateService, useValue: taxRateService },
        { provide: TaxCodeService, useValue: taxCodeService },
        { provide: CompanyCountryService, useValue: countryService },
        { provide: CompanyRegionService, useValue: regionService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TaxRateDeclareDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const saveButton = (): HTMLButtonElement | undefined =>
    buttonByText(fixture.nativeElement, 'Declarar tasa');

  const cancelButton = (): HTMLButtonElement | undefined =>
    buttonByText(fixture.nativeElement, 'Cancelar');

  beforeEach(() => {
    taxRateService = { declareTaxRate: vi.fn(() => of(declared())) };
    taxCodeService = {
      getTaxCodes: vi.fn(() =>
        of([
          { code: 'IVA', label: 'IVA', enabled: true },
          { code: 'RETIRED', label: 'Retirado', enabled: false },
        ]),
      ),
    };
    countryService = { getCountries: vi.fn(() => of([{ id: 'cc-mx', countryName: 'México' }])) };
    regionService = {
      getRegions: vi.fn(() => of([{ id: REGION_ID, regionName: 'Norte', enabled: true }])),
    };
    dialogRef = { close: vi.fn(), disableClose: false };
  });

  /** The one thing the operator types as a percentage and the server reads as a fraction (`S4`). */
  it('sends the percentage typed as the fraction the contract takes', async () => {
    await build();
    component.form().patchValue({
      taxCode: 'IVA',
      ratePercent: 16,
      effectiveFrom: '2026-11-10',
    });

    component.onSave();

    expect(taxRateService.declareTaxRate).toHaveBeenCalledWith(COMPANY_ID, {
      taxCode: 'IVA',
      regionId: null,
      rate: 0.16,
      effectiveFrom: '2026-11-10',
      enabled: true,
    });
  });

  it('converts a fractional percentage without leaking float noise beyond the column scale', async () => {
    await build();
    component
      .form()
      .patchValue({ taxCode: 'IVA', ratePercent: 12.3456, effectiveFrom: '2026-11-10' });

    component.onSave();

    expect(taxRateService.declareTaxRate).toHaveBeenCalledWith(
      COMPANY_ID,
      expect.objectContaining({ rate: 0.123456 }),
    );
  });

  it('declares a regional rate by sending the region id it was given, never the empty scope', async () => {
    await build();
    component.form().patchValue({ taxCode: 'IVA', regionId: REGION_ID, ratePercent: 8 });

    component.onSave();

    expect(taxRateService.declareTaxRate).toHaveBeenCalledWith(
      COMPANY_ID,
      expect.objectContaining({ regionId: REGION_ID, rate: 0.08 }),
    );
  });

  it('states the regional commitment where the region is chosen, and not for the company-wide rate', async () => {
    await build();
    const warning = (): string | null =>
      (fixture.nativeElement.querySelector('.scope-warning') as HTMLElement | null)?.textContent ??
      null;

    expect(warning()).toBeNull();

    component.form().controls.regionId.setValue(REGION_ID);
    fixture.detectChanges();

    expect(warning()).toContain(REGION_SCOPE_WARNING);
  });

  it('offers the enabled tax codes and the company-wide scope from the catalog', async () => {
    await build();

    expect(component.taxCodes().map((code) => code.code)).toEqual(['IVA']);
    expect(component.scopeOptions()).toEqual([
      { id: '', label: 'Toda la compañía' },
      { id: REGION_ID, label: 'Norte' },
    ]);
  });

  it('refuses an empty form without calling the server', async () => {
    await build();
    component.form().patchValue({ taxCode: '', ratePercent: null });
    fixture.detectChanges();

    component.onSave();
    await fixture.whenStable();

    expect(taxRateService.declareTaxRate).not.toHaveBeenCalled();
  });

  it('keeps the dialog open and names the colliding day when the server refuses a duplicate', async () => {
    taxRateService.declareTaxRate.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, statusText: 'Conflict' })),
    );
    await build();
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16, effectiveFrom: '2026-11-10' });

    component.onSave();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.formError()).toContain('esa fecha de vigencia');
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('closes with the rate the server wrote, which is what the list re-reads', async () => {
    await build();
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16 });

    component.onSave();

    expect(dialogRef.close).toHaveBeenCalledWith(declared());
  });

  it('closes with null on cancel, so the page reloads nothing', async () => {
    await build();

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  // S1. Material closes the ref itself on Esc and on a backdrop click, and that close answers
  // `undefined`, which the page reads as "no write" — so a POST that settles after one of them
  // appended a row the list kept denying. `MatDialogRef`'s own ESC/backdrop subscription reads
  // `disableClose` **on the ref**, so flipping it around the write is what refuses both.
  it('refuses Esc, the backdrop and the close button while its write is in flight', async () => {
    const write = new Subject<TaxRate>();
    taxRateService.declareTaxRate.mockReturnValue(write.asObservable());
    await build();
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16 });

    component.onSave();
    fixture.detectChanges();

    expect(component.saving()).toBe(true);
    expect(dialogRef.disableClose).toBe(true);
    expect(cancelButton()?.disabled).toBe(true);
  });

  it('gives the block an exit: a refused write restores dismissal and names the refusal', async () => {
    const write = new Subject<TaxRate>();
    taxRateService.declareTaxRate.mockReturnValue(write.asObservable());
    await build();
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16 });
    component.onSave();
    fixture.detectChanges();

    write.error(new HttpErrorResponse({ status: 500, statusText: 'Server Error' }));
    fixture.detectChanges();

    expect(dialogRef.disableClose).toBe(false);
    expect(cancelButton()?.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  // S2. A region read used to be swallowed by `catchError(() => of([]))`, so the scope select showed
  // only "Toda la compañía" and read as a complete list: a company-wide rate could be declared by an
  // operator who never saw the regions. The failure now names the country and blocks the save.
  it('keeps the save unavailable and names the country whose regions it could not read', async () => {
    regionService.getRegions.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    await build();

    expect(saveButton()?.disabled).toBe(true);
    expect(component.regionsError()).toContain('México');
    expect(component.regionsError()).toContain('no se puede guardar');
    expect(buttonByText(fixture.nativeElement, 'Reintentar')).not.toBeUndefined();
  });

  it('keeps the save unavailable while a region read is still pending', async () => {
    regionService.getRegions.mockReturnValue(new Subject().asObservable());
    await build();

    // No region answered yet, so the only scope on screen is the company-wide one: the operator would
    // be declaring the empty scope having never seen the regions it might belong to.
    expect(component.scopeOptions().map((option) => option.id)).toEqual(['']);
    expect(saveButton()?.disabled).toBe(true);
  });

  it('keeps the save unavailable when the tax-code catalog itself could not be read', async () => {
    taxCodeService.getTaxCodes.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    await build();

    expect(saveButton()?.disabled).toBe(true);
  });

  it('re-reads the scope on Reintentar, and only then becomes savable', async () => {
    regionService.getRegions
      .mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
      )
      .mockReturnValue(of([{ id: REGION_ID, regionName: 'Norte', enabled: true }]));
    await build();

    expect(component.regionsError()).not.toBeNull();
    expect(saveButton()?.disabled).toBe(true);

    buttonByText(fixture.nativeElement, 'Reintentar')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(regionService.getRegions).toHaveBeenCalledTimes(2);
    expect(component.regionsError()).toBeNull();
    expect(component.scopeOptions().map((option) => option.id)).toEqual(['', REGION_ID]);
    // The retry is worth nothing if the save stays dead: this is the assertion that it does not.
    expect(saveButton()?.disabled).toBe(false);
  });
});
