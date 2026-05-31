import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ZonesForm } from './zones-form';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone, ZoneSaveEvent } from '../../models/zone.models';

describe('ZonesForm', () => {
  let component: ZonesForm;
  let fixture: ComponentFixture<ZonesForm>;

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

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      regionCode: 'US-CA', regionName: 'California',
      enabled: true, createdAt: '', updatedAt: '',
    },
    {
      id: 'reg-2', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1',
      regionCode: 'US-TX', regionName: 'Texas',
      enabled: true, createdAt: '', updatedAt: '',
    },
  ];

  const mockZone: CompanyZone = {
    id: 'zone-1',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'comp-1',
    countryId: '1',
    zoneCode: 'US-CA-DT',
    zoneName: 'Downtown',
    description: 'Zona céntrica',
    displayOrder: 1,
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ZonesForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ZonesForm);
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
    it('should render "Nueva Zona" title', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title).toBeTruthy();
      expect(title.textContent).toContain('Nueva Zona');
    });

    it('should render company, country, and region selectors', () => {
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBe(3);
    });

    it('should render form fields always', () => {
      const codeInput = fixture.nativeElement.querySelector('[formControlName="zoneCode"]');
      const nameInput = fixture.nativeElement.querySelector('[formControlName="zoneName"]');
      const descInput = fixture.nativeElement.querySelector('[formControlName="description"]');
      const orderInput = fixture.nativeElement.querySelector('[formControlName="displayOrder"]');
      expect(codeInput).toBeTruthy();
      expect(nameInput).toBeTruthy();
      expect(descInput).toBeTruthy();
      expect(orderInput).toBeTruthy();
    });

    it('should render slide-toggle for enabled', () => {
      const toggle = fixture.nativeElement.querySelector('mat-slide-toggle');
      expect(toggle).toBeTruthy();
      expect(toggle.textContent).toContain('Activo');
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

    it('should reset country and region selection when company changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);

      expect(component.selectedCompanyCountryId()).toBe('cc-1');
      expect(component.selectedRegionId()).toBe('reg-1');

      // Changing company resets country and region
      component.onCompanyChange('comp-2');
      expect(component.selectedCompanyCountryId()).toBeNull();
      expect(component.selectedRegionId()).toBeNull();
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

    it('should reset region selection when country changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);

      expect(component.selectedRegionId()).toBe('reg-1');

      component.onCountryChange(mockCountries[1]);
      expect(component.selectedRegionId()).toBeNull();
    });
  });

  // ─── Region selector ──────────────────────────────────────────
  describe('region selector', () => {
    it('should emit selectedRegionChange when region is selected', () => {
      let emitted: CompanyRegion | undefined;
      component.selectedRegionChange.subscribe((r: CompanyRegion) => { emitted = r; });

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      component.onRegionChange(mockRegions[0]);

      expect(emitted).toEqual(mockRegions[0]);
      expect(component.selectedRegionId()).toBe('reg-1');
    });
  });

  // ─── Edit mode ────────────────────────────────────────────────
  describe('edit mode', () => {
    it('should render "Editar Zona" title when zoneToEdit is set', () => {
      fixture.componentRef.setInput('zoneToEdit', mockZone);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Zona');
    });

    it('should pre-fill form fields from zoneToEdit', () => {
      fixture.componentRef.setInput('zoneToEdit', mockZone);
      fixture.detectChanges();

      expect(component.formGroup.controls.zoneCode.value).toBe('US-CA-DT');
      expect(component.formGroup.controls.zoneName.value).toBe('Downtown');
      expect(component.formGroup.controls.description.value).toBe('Zona céntrica');
      expect(component.formGroup.controls.displayOrder.value).toBe(1);
      expect(component.formGroup.controls.enabled.value).toBe(true);
    });

    it('should pre-select company, country, and region from zoneToEdit', () => {
      fixture.componentRef.setInput('zoneToEdit', mockZone);
      fixture.detectChanges();

      expect(component.selectedCompanyId()).toBe('comp-1');
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
      expect(component.selectedRegionId()).toBe('reg-1');
    });

    it('should show "Actualizar" button text in edit mode', () => {
      fixture.componentRef.setInput('zoneToEdit', mockZone);
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.componentRef.setInput('regions', mockRegions);
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
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      fixture.detectChanges();
    });

    it('should mark zoneCode as required', () => {
      const control = component.formGroup.controls.zoneCode;
      control.markAsTouched();
      expect(control.errors?.['required']).toBeTruthy();
    });

    it('should mark zoneName as required', () => {
      const control = component.formGroup.controls.zoneName;
      control.markAsTouched();
      expect(control.errors?.['required']).toBeTruthy();
    });

    it('should reject zoneCode with special characters', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('INVALID!!!');
      expect(control.errors?.['pattern']).toBeTruthy();
    });

    it('should accept zoneCode with letters, numbers, and hyphens', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('US-CA-DT-2');
      expect(control.valid).toBe(true);
    });

    it('should enforce maxLength 10 on zoneCode', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('ABCDEFGHIJK');
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce maxLength 100 on zoneName', () => {
      const control = component.formGroup.controls.zoneName;
      control.setValue('A'.repeat(101));
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce maxLength 255 on description', () => {
      const control = component.formGroup.controls.description;
      control.setValue('A'.repeat(256));
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should enforce min 1 on displayOrder', () => {
      const control = component.formGroup.controls.displayOrder;
      control.setValue(0);
      expect(control.errors?.['min']).toBeTruthy();
    });

    it('should accept displayOrder as null (optional)', () => {
      const control = component.formGroup.controls.displayOrder;
      control.setValue(null);
      expect(control.valid).toBe(true);
    });

    it('should render "Solo letras, números y guiones" for pattern error', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('INVALID!!!');
      control.markAsTouched();
      fixture.detectChanges();

      const matError = fixture.nativeElement.querySelector('mat-error');
      expect(matError).toBeTruthy();
      expect(matError.textContent).toContain('Solo letras');
    });

    it('should render "Este campo es obligatorio" for required error', () => {
      const control = component.formGroup.controls.zoneName;
      control.markAsTouched();
      control.setErrors({ required: true });
      fixture.detectChanges();

      const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
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
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      fixture.detectChanges();
    });

    it('should emit save with valid data including regionId', () => {
      component.formGroup.patchValue({
        zoneCode: 'US-NY-DT',
        zoneName: 'Downtown NY',
        description: 'Zona céntrica NY',
        displayOrder: 2,
        enabled: false,
      });

      let emitted: ZoneSaveEvent | undefined;
      component.save.subscribe((ev: ZoneSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.companyId).toBe('comp-1');
      expect(emitted!.countryId).toBe('cc-1');
      expect(emitted!.regionId).toBe('reg-1');
      expect(emitted!.request.zoneCode).toBe('US-NY-DT');
      expect(emitted!.request.zoneName).toBe('Downtown NY');
      expect(emitted!.request.description).toBe('Zona céntrica NY');
      expect(emitted!.request.displayOrder).toBe(2);
    });

    it('should omit optional fields when not provided', () => {
      component.formGroup.patchValue({
        zoneCode: 'US-NY',
        zoneName: 'New York',
        description: '',
        displayOrder: null,
      });

      let emitted: ZoneSaveEvent | undefined;
      component.save.subscribe((ev: ZoneSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted!.request.description).toBeUndefined();
      expect(emitted!.request.displayOrder).toBeUndefined();
    });

    it('should trim whitespace from form values', () => {
      component.formGroup.patchValue({
        zoneCode: 'US-NY',
        zoneName: '  New York  ',
        description: '  NY Zone  ',
      });

      let emitted: ZoneSaveEvent | undefined;
      component.save.subscribe((ev: ZoneSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted!.request.zoneCode).toBe('US-NY');
      expect(emitted!.request.zoneName).toBe('New York');
      expect(emitted!.request.description).toBe('NY Zone');
    });

    it('should NOT emit save when form is invalid', () => {
      component.formGroup.controls.zoneCode.setValue('');

      let emitted = false;
      component.save.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should NOT emit save when no region is selected', () => {
      component.selectedRegionId.set(null);

      let emitted = false;
      component.save.subscribe(() => { emitted = true; });

      component.onSave();

      expect(emitted).toBe(false);
    });

    it('should mark all controls as touched on invalid submit', () => {
      component.formGroup.controls.zoneCode.setValue('');
      component.formGroup.controls.zoneName.setValue('');

      component.onSave();

      expect(component.formGroup.controls.zoneCode.touched).toBe(true);
      expect(component.formGroup.controls.zoneName.touched).toBe(true);
    });
  });

  // ─── Cancel ───────────────────────────────────────────────────
  describe('onCancel', () => {
    it('should emit cancel void', () => {
      let emitted = false;
      component.cancel.subscribe(() => { emitted = true; });

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
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      fixture.detectChanges();
    });

    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        zoneCode: 'Código ya existe',
        zoneName: 'Nombre duplicado',
      });
      fixture.detectChanges();

      const codeControl = component.formGroup.controls.zoneCode;
      const nameControl = component.formGroup.controls.zoneName;

      expect(codeControl.errors?.['serverError']).toBe('Código ya existe');
      expect(nameControl.errors?.['serverError']).toBe('Nombre duplicado');
    });

    it('should clear serverError on valueChanges while preserving other validators', () => {
      const codeControl = component.formGroup.controls.zoneCode;
      codeControl.setValue('US-CA');

      fixture.componentRef.setInput('serverErrors', {
        zoneCode: 'Código ya existe',
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
