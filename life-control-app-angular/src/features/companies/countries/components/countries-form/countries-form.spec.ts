import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { CountriesForm } from './countries-form';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry, Country, CountrySaveEvent } from '../../models/country.models';
import { CountryService } from '@features/countries/data/country.service';

describe('CountriesForm', () => {
  let component: CountriesForm;
  let fixture: ComponentFixture<CountriesForm>;
  let countryServiceMock: Partial<Record<keyof CountryService, unknown>>;

  const mockCompanies: Company[] = [
    {
      id: 'comp-1', companyKey: 'ACME', companyName: 'ACME Corp',
      tipoPersonaId: 1, razonSocial: 'ACME Corp SA',
      rfc: 'ACME010101', email: 'a@a.com', phone: '1234567890',
      enabled: true, createdAt: '', updatedAt: '',
    },
    {
      id: 'comp-2', companyKey: 'BETA', companyName: 'Beta Inc',
      tipoPersonaId: 2, razonSocial: 'Beta Inc SA',
      rfc: 'BETA010101', email: 'b@b.com', phone: '0987654321',
      enabled: true, createdAt: '', updatedAt: '',
    },
  ];

  const mockCatalogCountries: Country[] = [
    { id: '1', countryCode: 'US', countryName: 'United States', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', countryCode: 'MX', countryName: 'Mexico', enabled: true, createdAt: '', updatedAt: '' },
  ];

  const mockCc: CompanyCountry = {
    id: 'cc-1', companyId: 'comp-1', countryId: '1',
    countryCode: 'US', countryName: 'United States',
    localAlias: 'USA Office', createdAt: '', updatedAt: '',
  };

  beforeEach(async () => {
    countryServiceMock = {
      countries: signal(mockCatalogCountries).asReadonly(),
      getCountries: vi.fn().mockReturnValue(of([])),
    };

    await TestBed.configureTestingModule({
      imports: [CountriesForm, NoopAnimationsModule],
      providers: [
        { provide: CountryService, useValue: countryServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CountriesForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('companies', mockCompanies);
    fixture.detectChanges();
  });

  // ─── Creation ─────────────────────────────────────────────────
  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─── Rendering: add mode ──────────────────────────────────────
  describe('add mode', () => {
    it('should render "Nuevo País" title', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title).toBeTruthy();
      expect(title.textContent).toContain('Nuevo País');
    });

    it('should render company and catalog country selectors', () => {
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBe(2);
    });

    it('should render localAlias field', () => {
      const aliasInput = fixture.nativeElement.querySelector('[formControlName="localAlias"]');
      expect(aliasInput).toBeTruthy();
    });
  });

  // ─── Company selector ─────────────────────────────────────────
  describe('company selector', () => {
    it('should set selectedCompanyId when company is selected', () => {
      component.onCompanyChange('comp-1');
      expect(component.selectedCompanyId()).toBe('comp-1');
    });
  });

  // ─── Catalog country selector ─────────────────────────────────
  describe('catalog country selector', () => {
    it('should set selectedCatalogCountry when country is selected', () => {
      component.onCountryChange(mockCatalogCountries[0]);
      expect(component.selectedCatalogCountry()).toEqual(mockCatalogCountries[0]);
    });
  });

  // ─── Validation / blocked submit ──────────────────────────────
  describe('validation blocks submit', () => {
    it('should NOT emit saveCountry when form is submitted without selections', () => {
      component.formGroup.markAllAsTouched();

      let emitted = false;
      component.saveCountry.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should NOT emit saveCountry when no company selected', () => {
      component.onCountryChange(mockCatalogCountries[0]);

      let emitted = false;
      component.saveCountry.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should NOT emit saveCountry when no catalog country selected', () => {
      component.onCompanyChange('comp-1');

      let emitted = false;
      component.saveCountry.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });
  });

  // ─── Save ─────────────────────────────────────────────────────
  describe('onSave', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      component.onCountryChange(mockCatalogCountries[0]);
    });

    it('should emit saveCountry with valid data', () => {
      component.formGroup.patchValue({ localAlias: 'My Alias' });

      let emitted: CountrySaveEvent | undefined;
      component.saveCountry.subscribe((ev: CountrySaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.companyId).toBe('comp-1');
      expect(emitted!.request.countryCode).toBe('US');
      expect(emitted!.request.localAlias).toBe('My Alias');
      expect(emitted!.countryId).toBeUndefined();
    });

    it('should trim whitespace from localAlias', () => {
      component.formGroup.patchValue({ localAlias: '  Alias con espacios  ' });

      let emitted: CountrySaveEvent | undefined;
      component.saveCountry.subscribe((ev: CountrySaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.request.localAlias).toBe('Alias con espacios');
    });

    it('should set localAlias as undefined when empty', () => {
      component.formGroup.patchValue({ localAlias: '' });

      let emitted: CountrySaveEvent | undefined;
      component.saveCountry.subscribe((ev: CountrySaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.request.localAlias).toBeUndefined();
    });

    it('should include countryId in edit mode', () => {
      fixture.componentRef.setInput('ccToEdit', mockCc);
      fixture.detectChanges();

      let emitted: CountrySaveEvent | undefined;
      component.saveCountry.subscribe((ev: CountrySaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.countryId).toBe('cc-1');
    });
  });

  // ─── Cancel ───────────────────────────────────────────────────
  describe('onCancel', () => {
    it('should emit cancelForm void', () => {
      let emitted = false;
      component.cancelForm.subscribe(() => { emitted = true; });

      component.onCancel();

      expect(emitted).toBe(true);
    });
  });

  // ─── Edit mode ────────────────────────────────────────────────
  describe('edit mode', () => {
    it('should render "Editar País" title when ccToEdit is set', () => {
      fixture.componentRef.setInput('ccToEdit', mockCc);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar País');
    });

    it('should pre-fill localAlias from ccToEdit', () => {
      fixture.componentRef.setInput('ccToEdit', mockCc);
      fixture.detectChanges();

      expect(component.formGroup.controls.localAlias.value).toBe('USA Office');
    });

    it('should pre-select company from ccToEdit', () => {
      fixture.componentRef.setInput('ccToEdit', mockCc);
      fixture.detectChanges();

      expect(component.selectedCompanyId()).toBe('comp-1');
    });

    it('should show "Actualizar" button text in edit mode', () => {
      fixture.componentRef.setInput('ccToEdit', mockCc);
      fixture.detectChanges();

      const submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn).toBeTruthy();
      expect(submitBtn.textContent?.trim()).toContain('Actualizar');
    });
  });

  // ─── Server errors ────────────────────────────────────────────
  describe('serverErrors', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      component.onCountryChange(mockCatalogCountries[0]);
      fixture.detectChanges();
    });

    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        localAlias: 'Alias duplicado',
      });
      fixture.detectChanges();

      const control = component.formGroup.controls.localAlias;
      expect(control.errors?.['serverError']).toBe('Alias duplicado');
    });

    it('should clear serverError on valueChanges while preserving form state', () => {
      const control = component.formGroup.controls.localAlias;
      control.setValue('Original');

      fixture.componentRef.setInput('serverErrors', {
        localAlias: 'Alias duplicado',
      });
      fixture.detectChanges();

      expect(control.errors?.['serverError']).toBe('Alias duplicado');

      // Change value to trigger serverError clear
      control.setValue('New Alias');
      fixture.detectChanges();

      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should not throw on unmatched server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      fixture.componentRef.setInput('serverErrors', {
        nonexistent: 'No existe',
      });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalledWith(
        expect.stringContaining('nonexistent'),
      );

      warnSpy.mockRestore();
    });
  });
});
