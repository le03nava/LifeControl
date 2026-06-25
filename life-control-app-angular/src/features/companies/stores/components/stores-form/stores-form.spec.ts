import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { StoresForm } from './stores-form';
import { CompanyStore, StoreSaveEvent } from '../../models/store.models';

describe('StoresForm', () => {
  let component: StoresForm;
  let fixture: ComponentFixture<StoresForm>;

  const mockCompanies = [
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

  const mockCountries = [
    { id: 'cc-1', companyId: 'comp-1', countryId: '1', countryCode: 'US', countryName: 'United States', localAlias: null, createdAt: '', updatedAt: '' },
    { id: 'cc-2', companyId: 'comp-1', countryId: '2', countryCode: 'MX', countryName: 'Mexico', localAlias: null, createdAt: '', updatedAt: '' },
  ];

  const mockRegions = [
    { id: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1', regionCode: 'US-CA', regionName: 'California', enabled: true, createdAt: '', updatedAt: '' },
    { id: 'reg-2', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1', regionCode: 'US-TX', regionName: 'Texas', enabled: true, createdAt: '', updatedAt: '' },
  ];

  const mockZones = [
    { id: 'zone-1', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1', zoneCode: 'US-CA-DT', zoneName: 'Downtown', description: 'Centro', displayOrder: 1, enabled: true, createdAt: '', updatedAt: '' },
    { id: 'zone-2', companyRegionId: 'reg-1', companyCountryId: 'cc-1', companyId: 'comp-1', countryId: '1', zoneCode: 'US-CA-SF', zoneName: 'San Fernando', description: 'Norte', displayOrder: 2, enabled: true, createdAt: '', updatedAt: '' },
  ];

  const mockStore: CompanyStore = {
    id: 'store-1',
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Central',
    email: 'central@store.com',
    phoneNumber: '+525512345678',
    address: {
      street: 'Av. Reforma',
      streetNumber: '222',
      internalNumber: 'A-101',
      neighborhood: 'Juárez',
      zipCode: '06600',
      city: 'CDMX',
      state: 'CDMX',
      countryId: 'MX',
    },
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoresForm, NoopAnimationsModule, HttpClientTestingModule],
    }).compileComponents();

    fixture = TestBed.createComponent(StoresForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('companies', mockCompanies);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─── Rendering: add mode ──────────────────────────────────
  describe('add mode', () => {
    it('should render "Nueva Tienda" title', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title).toBeTruthy();
      expect(title.textContent).toContain('Nueva Tienda');
    });

    it('should render company, country, region, zone, and address country selectors', () => {
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBe(4);
    });

    it('should render storeName field', () => {
      const nameInput = fixture.nativeElement.querySelector('[formControlName="storeName"]');
      expect(nameInput).toBeTruthy();
    });

    it('should render email and phoneNumber fields', () => {
      const emailInput = fixture.nativeElement.querySelector('[formControlName="email"]');
      const phoneInput = fixture.nativeElement.querySelector('[formControlName="phoneNumber"]');
      expect(emailInput).toBeTruthy();
      expect(phoneInput).toBeTruthy();
    });

    it('should render address-form component', () => {
      const addressForm = fixture.nativeElement.querySelector('app-address-form');
      expect(addressForm).toBeTruthy();
    });

    it('should render slide-toggle for enabled', () => {
      const toggle = fixture.nativeElement.querySelector('mat-slide-toggle');
      expect(toggle).toBeTruthy();
    });
  });

  // ─── Company selector ─────────────────────────────────────
  describe('company selector', () => {
    it('should emit selectedCompanyChange when company is selected', () => {
      let emittedId = '';
      component.selectedCompanyChange.subscribe((id: string) => { emittedId = id; });

      component.onCompanyChange('comp-1');
      expect(emittedId).toBe('comp-1');
      expect(component.selectedCompanyId()).toBe('comp-1');
    });

    it('should reset country, region, and zone selection when company changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      component.selectedZoneId.set('zone-1');

      expect(component.selectedCompanyCountryId()).toBe('cc-1');
      expect(component.selectedRegionId()).toBe('reg-1');
      expect(component.selectedZoneId()).toBe('zone-1');

      component.onCompanyChange('comp-2');
      expect(component.selectedCompanyCountryId()).toBeNull();
      expect(component.selectedRegionId()).toBeNull();
      expect(component.selectedZoneId()).toBeNull();
    });
  });

  // ─── Country selector ─────────────────────────────────────
  describe('country selector', () => {
    it('should emit selectedCountryChange when country is selected', () => {
      let emitted: any;
      component.selectedCountryChange.subscribe((cc: any) => { emitted = cc; });

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);

      expect(emitted).toEqual(mockCountries[0]);
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
    });

    it('should reset region and zone when country changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      component.selectedZoneId.set('zone-1');

      expect(component.selectedRegionId()).toBe('reg-1');

      component.onCountryChange(mockCountries[1]);
      expect(component.selectedRegionId()).toBeNull();
      expect(component.selectedZoneId()).toBeNull();
    });
  });

  // ─── Region selector ──────────────────────────────────────
  describe('region selector', () => {
    it('should emit selectedRegionChange when region is selected', () => {
      let emitted: any;
      component.selectedRegionChange.subscribe((r: any) => { emitted = r; });

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      component.onRegionChange(mockRegions[0]);

      expect(emitted).toEqual(mockRegions[0]);
      expect(component.selectedRegionId()).toBe('reg-1');
    });

    it('should load zones when region changes', () => {
      const loadSpy = vi.spyOn(component as any, 'loadZonesForRegion');

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      component.onRegionChange(mockRegions[0]);

      expect(loadSpy).toHaveBeenCalledWith('reg-1');
    });

    it('should reset zone when region changes', () => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      component.selectedZoneId.set('zone-1');

      component.onRegionChange(mockRegions[1]);
      expect(component.selectedZoneId()).toBeNull();
    });
  });

  // ─── Zone selector ────────────────────────────────────────
  describe('zone selector', () => {
    it('should emit selectedZoneChange when zone is selected', () => {
      let emittedId = '';
      component.selectedZoneChange.subscribe((id: string) => { emittedId = id; });

      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      component.onRegionChange(mockRegions[0]);
      (component as any)._zones.set(mockZones);
      fixture.detectChanges();

      component.onZoneChange('zone-1');
      expect(emittedId).toBe('zone-1');
      expect(component.selectedZoneId()).toBe('zone-1');
    });
  });

  // ─── Edit mode ────────────────────────────────────────────
  describe('edit mode', () => {
    it('should render "Editar Tienda" title when storeToEdit is set', () => {
      fixture.componentRef.setInput('storeToEdit', mockStore);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Tienda');
    });

    it('should pre-fill form fields from storeToEdit', () => {
      fixture.componentRef.setInput('storeToEdit', mockStore);
      fixture.detectChanges();

      expect(component.formGroup.controls.storeName.value).toBe('Tienda Central');
      expect(component.formGroup.controls.email.value).toBe('central@store.com');
      expect(component.formGroup.controls.phoneNumber.value).toBe('+525512345678');
      expect(component.formGroup.controls.address.get('street')?.value).toBe('Av. Reforma');
      expect(component.formGroup.controls.address.get('streetNumber')?.value).toBe('222');
      expect(component.formGroup.controls.enabled.value).toBe(true);
    });

    it('should pre-select company, country, region, zone from storeToEdit', () => {
      fixture.componentRef.setInput('storeToEdit', mockStore);
      fixture.detectChanges();

      expect(component.selectedCompanyId()).toBe('comp-1');
      expect(component.selectedCompanyCountryId()).toBe('cc-1');
      expect(component.selectedRegionId()).toBe('reg-1');
      expect(component.selectedZoneId()).toBe('zone-1');
    });

    it('should show "Actualizar" button text in edit mode', () => {
      fixture.componentRef.setInput('storeToEdit', mockStore);
      fixture.detectChanges();

      const submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn).toBeTruthy();
      expect(submitBtn.textContent?.trim()).toContain('Actualizar');
    });
  });

  // ─── Validation ───────────────────────────────────────────
  describe('validation', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      (component as any)._zones.set(mockZones);
      fixture.detectChanges();
      component.onZoneChange('zone-1');
      fixture.detectChanges();
    });

    it('should mark storeName as required', () => {
      const control = component.formGroup.controls.storeName;
      control.markAsTouched();
      expect(control.errors?.['required']).toBeTruthy();
    });

    it('should accept storeName with valid input', () => {
      const control = component.formGroup.controls.storeName;
      control.setValue('Tienda Principal');
      expect(control.valid).toBe(true);
    });

    it('should enforce maxLength 100 on storeName', () => {
      const control = component.formGroup.controls.storeName;
      control.setValue('A'.repeat(101));
      expect(control.errors?.['maxlength']).toBeTruthy();
    });

    it('should reject invalid email format', () => {
      const control = component.formGroup.controls.email;
      control.setValue('invalid-email');
      expect(control.errors?.['email']).toBeTruthy();
    });

    it('should accept valid email', () => {
      const control = component.formGroup.controls.email;
      control.setValue('valid@email.com');
      expect(control.valid).toBe(true);
    });

    it('should render "Este campo es obligatorio" for required error', () => {
      const control = component.formGroup.controls.storeName;
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

  // ─── Save ─────────────────────────────────────────────────
  describe('onSave', () => {
    beforeEach(() => {
      component.onCompanyChange('comp-1');
      fixture.componentRef.setInput('companyCountries', mockCountries);
      fixture.detectChanges();
      component.onCountryChange(mockCountries[0]);
      fixture.componentRef.setInput('regions', mockRegions);
      fixture.detectChanges();
      component.onRegionChange(mockRegions[0]);
      (component as any)._zones.set(mockZones);
      fixture.detectChanges();
      component.onZoneChange('zone-1');
      fixture.detectChanges();
    });

    it('should emit save with valid data', () => {
      component.formGroup.patchValue({
        storeName: 'Mi Tienda',
        email: 'tienda@store.com',
        phoneNumber: '+525511223344',
        address: {
          street: 'Calle Principal',
          streetNumber: '123',
          city: 'Monterrey',
          state: 'NL',
        },
      });

      let emitted: StoreSaveEvent | undefined;
      component.save.subscribe((ev: StoreSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.companyId).toBe('comp-1');
      expect(emitted!.countryId).toBe('cc-1');
      expect(emitted!.regionId).toBe('reg-1');
      expect(emitted!.zoneId).toBe('zone-1');
      expect(emitted!.request.storeName).toBe('Mi Tienda');
    });

    it('should NOT emit save when form is invalid', () => {
      component.formGroup.controls.storeName.setValue('');
      let emitted = false;
      component.save.subscribe(() => { emitted = true; });
      component.onSave();
      expect(emitted).toBe(false);
    });

    it('should NOT emit save when no zone is selected', () => {
      component.selectedZoneId.set(null);
      let emitted = false;
      component.save.subscribe(() => { emitted = true; });
      component.onSave();
      expect(emitted).toBe(false);
    });

    it('should mark all controls as touched on invalid submit', () => {
      component.formGroup.controls.storeName.setValue('');
      component.onSave();
      expect(component.formGroup.controls.storeName.touched).toBe(true);
    });

    it('should include storeId when storeToEdit is set', () => {
      fixture.componentRef.setInput('storeToEdit', mockStore);
      fixture.detectChanges();

      let emitted: StoreSaveEvent | undefined;
      component.save.subscribe((ev: StoreSaveEvent) => { emitted = ev; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.storeId).toBe('store-1');
    });
  });

  // ─── Cancel ──────────────────────────────────────────────
  describe('onCancel', () => {
    it('should emit cancel void', () => {
      let emitted = false;
      component.cancel.subscribe(() => { emitted = true; });
      component.onCancel();
      expect(emitted).toBe(true);
    });
  });
});
