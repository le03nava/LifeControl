import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionsForm } from './regions-form';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion, RegionSaveEvent } from '../../models/region.models';

describe('RegionsForm', () => {
  let component: RegionsForm;
  let fixture: ComponentFixture<RegionsForm>;

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

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1', companyId: 'comp-1', countryId: '1',
      countryCode: 'US', countryName: 'United States',
      localAlias: null, createdAt: '', updatedAt: '',
    },
    {
      id: 'cc-2', companyId: 'comp-1', countryId: '2',
      countryCode: 'MX', countryName: 'Mexico',
      localAlias: null, createdAt: '', updatedAt: '',
    },
  ];

  const mockRegion: CompanyRegion = {
    id: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
    regionCode: 'US-CA', regionName: 'California',
    enabled: true, createdAt: '', updatedAt: '',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegionsForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(RegionsForm);
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
    it('should render "Nueva Región" title', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title).toBeTruthy();
      expect(title.textContent).toContain('Nueva Región');
    });

    it('should render company and country selectors', () => {
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBe(2);
    });

    it('should show placeholder when no country selected', () => {
      const placeholder = fixture.nativeElement.querySelector('.placeholder-text');
      expect(placeholder).toBeTruthy();
      expect(placeholder.textContent).toContain('Seleccioná un país');
    });

    it('should NOT render form fields when no country selected', () => {
      const codeInput = fixture.nativeElement.querySelector('[formControlName="regionCode"]');
      const nameInput = fixture.nativeElement.querySelector('[formControlName="regionName"]');
      expect(codeInput).toBeNull();
      expect(nameInput).toBeNull();
    });

    it('should render empty form fields after country selection', () => {
      // Simulate company + country selection
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.detectChanges();

      const codeInput = fixture.nativeElement.querySelector('[formControlName="regionCode"]');
      const nameInput = fixture.nativeElement.querySelector('[formControlName="regionName"]');
      expect(codeInput).toBeTruthy();
      expect(nameInput).toBeTruthy();
    });
  });

  // ─── Company selector ─────────────────────────────────────────
  describe('company selector', () => {
    it('should emit selectedCompanyChange when company is selected', () => {
      let emittedId = '';
      component.selectedCompanyChange.subscribe((id: string) => { emittedId = id; });

      component.onCompanyChange('comp-1');
      expect(emittedId).toBe('comp-1');
      expect(component.selectedCompanyId()).toBe('comp-1');
    });

    it('should reset country selection when company changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);

      expect(component.selectedCompanyCountryId()).toBe('cc-1');

      // Changing company resets country
      component.onCompanyChange('comp-2');
      expect(component.selectedCompanyCountryId()).toBeNull();
    });
  });

  // ─── Country selector ─────────────────────────────────────────
  describe('country selector', () => {
    it('should emit selectedCountryChange when country is selected', () => {
      let emitted: CompanyCountry | undefined;
      component.selectedCountryChange.subscribe((cc: CompanyCountry) => { emitted = cc; });

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);

      expect(emitted).toEqual(mockCountries[0]);
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
    });
  });

  // ─── Edit mode ────────────────────────────────────────────────
  describe('edit mode', () => {
    it('should render "Editar Región" title when regionToEdit is set', () => {
      fixture.componentRef.setInput('regionToEdit', mockRegion);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Región');
    });

    it('should pre-fill form fields from regionToEdit', () => {
      fixture.componentRef.setInput('regionToEdit', mockRegion);
      fixture.detectChanges();

      expect(component.formGroup.controls.regionCode.value).toBe('US-CA');
      expect(component.formGroup.controls.regionName.value).toBe('California');
    });

    it('should pre-select company and country from regionToEdit', () => {
      fixture.componentRef.setInput('regionToEdit', mockRegion);
      fixture.detectChanges();

      expect(component.selectedCompanyId()).toBe('comp-1');
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
    });

    it('should show "Actualizar" button text in edit mode', () => {
      // Setup edit mode with full selector state
      fixture.componentRef.setInput('regionToEdit', mockRegion);
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();

      const submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn).toBeTruthy();
      expect(submitBtn.textContent?.trim()).toContain('Actualizar');
    });
  });

  // ─── Validation ───────────────────────────────────────────────
  describe('validation', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.detectChanges();
    });

    it('should mark regionCode as required', () => {
      const control = component.formGroup.controls.regionCode;
      control.markAsTouched();
      expect(control.errors?.['required']).toBeTruthy();
    });

    it('should mark regionName as required', () => {
      const control = component.formGroup.controls.regionName;
      control.markAsTouched();
      expect(control.errors?.['required']).toBeTruthy();
    });

    it('should reject regionCode with special characters', () => {
      const control = component.formGroup.controls.regionCode;
      control.setValue('INVALID!!!');
      expect(control.errors?.['pattern']).toBeTruthy();
    });

    it('should accept regionCode with letters, numbers, and hyphens', () => {
      const control = component.formGroup.controls.regionCode;
      control.setValue('US-CA-2');
      expect(control.valid).toBe(true);
    });

    it('should enforce maxLength 10 on regionCode', () => {
      const control = component.formGroup.controls.regionCode;
      control.setValue('ABCDEFGHIJK');
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce maxLength 100 on regionName', () => {
      const control = component.formGroup.controls.regionName;
      control.setValue('A'.repeat(101));
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should render "Solo letras, números y guiones" for pattern error', () => {
      const control = component.formGroup.controls.regionCode;
      control.setValue('INVALID!!!');
      control.markAsTouched();
      fixture.detectChanges();

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Solo letras');
    });

    it('should render "Este campo es obligatorio" for required error', () => {
      const control = component.formGroup.controls.regionName;
      control.markAsTouched();
      control.setErrors({ required: true });
      fixture.detectChanges();

      const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
      // There should be at least a mat-error for regionName
      let found = false;
      matErrors.forEach((e: Element) => {
        if (e.textContent?.includes('Este campo es obligatorio')) found = true;
      });
      expect(found).toBe(true);
    });
  });

  // ─── Save ─────────────────────────────────────────────────────
  describe('onSave', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.detectChanges();
    });

    it('should emit saveRegion with valid data', () => {
      component.formGroup.patchValue({ regionCode: 'US-NY', regionName: 'New York' });

      let emitted: RegionSaveEvent | undefined;
      component.saveRegion.subscribe((ev: RegionSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.companyId).toBe('comp-1');
      expect(emitted!.countryId).toBe('cc-1');
      expect(emitted!.request.regionCode).toBe('US-NY');
      expect(emitted!.request.regionName).toBe('New York');
    });

    it('should trim whitespace from form values', () => {
      component.formGroup.patchValue({ regionCode: 'US-NY', regionName: '  New York  ' });

      let emitted: RegionSaveEvent | undefined;
      component.saveRegion.subscribe((ev: RegionSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.request.regionCode).toBe('US-NY');
      expect(emitted!.request.regionName).toBe('New York');
    });

    it('should NOT emit saveRegion when form is invalid', () => {
      component.formGroup.controls.regionCode.setValue('');

      let emitted = false;
      component.saveRegion.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should mark all controls as touched on invalid submit', () => {
      component.formGroup.controls.regionCode.setValue('');
      component.formGroup.controls.regionName.setValue('');

      component.onSave();

      expect(component.formGroup.controls.regionCode.touched).toBe(true);
      expect(component.formGroup.controls.regionName.touched).toBe(true);
    });

    it('should disable submit button when form is invalid', () => {
      const submitBtn = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
      expect(submitBtn.disabled).toBe(true);
    });

    it('should enable submit button when form is valid', () => {
      component.formGroup.patchValue({ regionCode: 'US-NY', regionName: 'New York' });
      fixture.detectChanges();

      const submitBtn = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
      expect(submitBtn.disabled).toBe(false);
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

  // ─── Server errors ────────────────────────────────────────────
  describe('serverErrors', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.detectChanges();
    });

    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        regionCode: 'Código ya existe',
        regionName: 'Nombre duplicado',
      });
      fixture.detectChanges();

      const codeControl = component.formGroup.controls.regionCode;
      const nameControl = component.formGroup.controls.regionName;

      expect(codeControl.errors?.['serverError']).toBe('Código ya existe');
      expect(nameControl.errors?.['serverError']).toBe('Nombre duplicado');
    });

    it('should clear serverError on valueChanges while preserving other validators', () => {
      const codeControl = component.formGroup.controls.regionCode;
      codeControl.setValue('US-CA');

      fixture.componentRef.setInput('serverErrors', {
        regionCode: 'Código ya existe',
      });
      fixture.detectChanges();

      expect(codeControl.errors?.['serverError']).toBe('Código ya existe');

      // Change value to trigger serverError clear
      codeControl.setValue('US-NY');
      fixture.detectChanges();

      expect(codeControl.errors?.['serverError']).toBeUndefined();
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
