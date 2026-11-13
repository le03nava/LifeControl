import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
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

describe('TaxRateDeclareDialog', () => {
  let fixture: ComponentFixture<TaxRateDeclareDialog>;
  let component: TaxRateDeclareDialog;
  let taxRateService: { declareTaxRate: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    taxRateService = { declareTaxRate: vi.fn(() => of(declared())) };
    dialogRef = { close: vi.fn() };

    TestBed.configureTestingModule({
      imports: [TaxRateDeclareDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { companyId: COMPANY_ID } },
        { provide: TaxRateService, useValue: taxRateService },
        {
          provide: TaxCodeService,
          useValue: {
            getTaxCodes: vi.fn(() =>
              of([
                { code: 'IVA', label: 'IVA', enabled: true },
                { code: 'RETIRED', label: 'Retirado', enabled: false },
              ]),
            ),
          },
        },
        {
          provide: CompanyCountryService,
          useValue: {
            getCountries: vi.fn(() => of([{ id: 'cc-mx', countryName: 'México' }])),
          },
        },
        {
          provide: CompanyRegionService,
          useValue: {
            getRegions: vi.fn(() => of([{ id: REGION_ID, regionName: 'Norte', enabled: true }])),
          },
        },
      ],
    });

    fixture = TestBed.createComponent(TaxRateDeclareDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  /** The one thing the operator types as a percentage and the server reads as a fraction (`S4`). */
  it('sends the percentage typed as the fraction the contract takes', () => {
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

  it('converts a fractional percentage without leaking float noise beyond the column scale', () => {
    component
      .form()
      .patchValue({ taxCode: 'IVA', ratePercent: 12.3456, effectiveFrom: '2026-11-10' });

    component.onSave();

    expect(taxRateService.declareTaxRate).toHaveBeenCalledWith(
      COMPANY_ID,
      expect.objectContaining({ rate: 0.123456 }),
    );
  });

  it('declares a regional rate by sending the region id it was given, never the empty scope', () => {
    component.form().patchValue({ taxCode: 'IVA', regionId: REGION_ID, ratePercent: 8 });

    component.onSave();

    expect(taxRateService.declareTaxRate).toHaveBeenCalledWith(
      COMPANY_ID,
      expect.objectContaining({ regionId: REGION_ID, rate: 0.08 }),
    );
  });

  it('states the regional commitment where the region is chosen, and not for the company-wide rate', () => {
    const warning = (): string | null =>
      (fixture.nativeElement.querySelector('.scope-warning') as HTMLElement | null)?.textContent ??
      null;

    expect(warning()).toBeNull();

    component.form().controls.regionId.setValue(REGION_ID);
    fixture.detectChanges();

    expect(warning()).toContain(REGION_SCOPE_WARNING);
  });

  it('offers the enabled tax codes and the company-wide scope from the catalog', () => {
    expect(component.taxCodes().map((code) => code.code)).toEqual(['IVA']);
    expect(component.scopeOptions()).toEqual([
      { id: '', label: 'Toda la compañía' },
      { id: REGION_ID, label: 'Norte' },
    ]);
  });

  it('refuses an empty form without calling the server', async () => {
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
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16, effectiveFrom: '2026-11-10' });

    component.onSave();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.formError()).toContain('esa fecha de vigencia');
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('closes with the rate the server wrote, which is what the list re-reads', () => {
    component.form().patchValue({ taxCode: 'IVA', ratePercent: 16 });

    component.onSave();

    expect(dialogRef.close).toHaveBeenCalledWith(declared());
  });

  it('closes with null on cancel, so the page reloads nothing', () => {
    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });
});
