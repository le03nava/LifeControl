import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { CompanyInfoSection } from './company-info-section';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { ConfigService } from '@app/services/config.service';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import type {
  PurchaseOrderHeaderControl,
  PurchaseOrderCompanyControl,
} from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';

const TEST_API = 'http://test/api';

const mockCompanies = {
  content: [
    { id: 'comp-1', companyName: 'Empresa Uno', rfc: 'RFC-001' },
    { id: 'comp-2', companyName: 'Empresa Dos', rfc: 'RFC-002' },
  ],
  totalElements: 2,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

const mockCompanyById = {
  id: 'comp-1',
  companyKey: 'EMP-001',
  companyName: 'Empresa Uno',
  tipoPersonaId: 1,
  razonSocial: 'Empresa Uno S.A.',
  rfc: 'RFC-001',
  email: 'contacto@empresauno.com',
  phone: '555-1000',
  address: {
    street: 'Av. Principal',
    streetNumber: '123',
    neighborhood: 'Centro',
    zipCode: '10000',
    city: 'Ciudad de México',
    state: 'CDMX',
  },
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const mockCountries = [
  {
    id: 'cc-1',
    companyId: 'comp-1',
    countryId: 'c-1',
    countryCode: 'MX',
    countryName: 'México',
    localAlias: null,
  },
  {
    id: 'cc-2',
    companyId: 'comp-1',
    countryId: 'c-2',
    countryCode: 'US',
    countryName: 'Estados Unidos',
    localAlias: null,
  },
];

const mockRegions = [
  {
    id: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'comp-1',
    countryId: 'c-1',
    regionCode: 'R1',
    regionName: 'Norte',
  },
  {
    id: 'reg-2',
    companyCountryId: 'cc-1',
    companyId: 'comp-1',
    countryId: 'c-1',
    regionCode: 'R2',
    regionName: 'Sur',
  },
];

const mockZones = [
  {
    id: 'zone-1',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'comp-1',
    countryId: 'c-1',
    zoneCode: 'Z1',
    zoneName: 'Zona A',
  },
  {
    id: 'zone-2',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'comp-1',
    countryId: 'c-1',
    zoneCode: 'Z2',
    zoneName: 'Zona B',
  },
];

const mockStores = [
  {
    id: 'store-1',
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Centro',
    enabled: true,
  },
  {
    id: 'store-2',
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Norte',
    enabled: true,
  },
];

const mockProfile = {
  keycloakUserId: 'user-1',
  username: 'jperez',
  email: 'jperez@test.com',
  firstName: 'Juan',
  lastName: 'Pérez',
  companyId: 'comp-1',
  companyCountryId: 'cc-1',
  companyRegionId: 'reg-1',
  companyZoneId: 'zone-1',
  companyStoreId: 'store-1',
};

const mockOrder: PurchaseOrder = {
  id: 'po-1',
  orderNumber: 'PO-00001',
  supplierId: 'sup-1',
  supplierName: 'Acme Corp',
  companyStoreId: 'store-1',
  companyStoreName: 'Tienda Centro',
  companyId: 'comp-1',
  companyCountryId: 'cc-1',
  regionId: 'reg-1',
  zoneId: 'zone-1',
  paymentMethodId: 'pm-1',
  paymentMethodName: 'Transferencia',
  statusId: 'st-draft',
  statusName: 'Draft',
  comments: 'Test order',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  details: [],
};

describe('CompanyInfoSection', () => {
  let headerForm: FormGroup<PurchaseOrderHeaderControl>;
  let companyServiceMock: {
    getCompanies: ReturnType<typeof vi.fn>;
    getCompanyById: ReturnType<typeof vi.fn>;
  };
  let companyCountryServiceMock: {
    getCountries: ReturnType<typeof vi.fn>;
  };
  let companyRegionServiceMock: {
    getRegions: ReturnType<typeof vi.fn>;
  };
  let companyZoneServiceMock: {
    getZones: ReturnType<typeof vi.fn>;
  };
  let companyStoreServiceMock: {
    getStores: ReturnType<typeof vi.fn>;
  };
  let profileServiceMock: {
    getProfile: ReturnType<typeof vi.fn>;
  };

  function createForm(): FormGroup<PurchaseOrderHeaderControl> {
    return new FormGroup<PurchaseOrderHeaderControl>({
      supplierId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      companyStoreId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      paymentMethodId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      comments: new FormControl<string | null>(null),
      company: new FormGroup<PurchaseOrderCompanyControl>({
        companyId: new FormControl<string | null>(null),
        companyCountryId: new FormControl<string | null>(null),
        regionId: new FormControl<string | null>(null),
        zoneId: new FormControl<string | null>(null),
      }),
    });
  }

  function companyControls() {
    return headerForm.controls.company.controls;
  }

  beforeEach(async () => {
    companyServiceMock = {
      getCompanies: vi.fn().mockReturnValue(of(mockCompanies)),
      getCompanyById: vi.fn().mockReturnValue(of(mockCompanyById)),
    };
    companyCountryServiceMock = {
      getCountries: vi.fn().mockReturnValue(of(mockCountries)),
    };
    companyRegionServiceMock = {
      getRegions: vi.fn().mockReturnValue(of(mockRegions)),
    };
    companyZoneServiceMock = {
      getZones: vi.fn().mockReturnValue(of(mockZones)),
    };
    companyStoreServiceMock = {
      getStores: vi.fn().mockReturnValue(of(mockStores)),
    };
    profileServiceMock = {
      getProfile: vi.fn().mockReturnValue(of(mockProfile)),
    };

    await TestBed.configureTestingModule({
      imports: [CompanyInfoSection, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
        { provide: CompanyService, useValue: companyServiceMock },
        { provide: CompanyCountryService, useValue: companyCountryServiceMock },
        { provide: CompanyRegionService, useValue: companyRegionServiceMock },
        { provide: CompanyZoneService, useValue: companyZoneServiceMock },
        { provide: CompanyStoreService, useValue: companyStoreServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
      ],
    }).compileComponents();

    headerForm = createForm();
  });

  describe('create mode', () => {
    function createFixture() {
      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.detectChanges();
      return fixture;
    }

    it('should load companies (server-side search) and profile on init', () => {
      createFixture();

      expect(companyServiceMock.getCompanies).toHaveBeenCalledWith(0, 20, undefined);
      expect(profileServiceMock.getProfile).toHaveBeenCalled();
    });

    it('should reconstruct cascade from profile when profile has companyId', () => {
      createFixture();

      expect(companyServiceMock.getCompanies).toHaveBeenCalled();
      expect(profileServiceMock.getProfile).toHaveBeenCalled();

      expect(companyCountryServiceMock.getCountries).toHaveBeenCalledWith('comp-1');
      expect(companyServiceMock.getCompanyById).toHaveBeenCalledWith('comp-1');
      expect(companyControls().companyId.value).toBe('comp-1');
    });

    it('should select country from profile after countries load', () => {
      createFixture();

      expect(companyControls().companyCountryId.value).toBe('cc-1');
      expect(companyRegionServiceMock.getRegions).toHaveBeenCalledWith('comp-1', 'cc-1');
    });

    it('should select region from profile after regions load', () => {
      createFixture();

      expect(companyControls().regionId.value).toBe('reg-1');
      expect(companyZoneServiceMock.getZones).toHaveBeenCalledWith('comp-1', 'cc-1', 'reg-1');
    });

    it('should select zone from profile after zones load', () => {
      createFixture();

      expect(companyControls().zoneId.value).toBe('zone-1');
      expect(companyStoreServiceMock.getStores).toHaveBeenCalledWith(
        'comp-1',
        'cc-1',
        'reg-1',
        'zone-1',
      );
    });

    it('should patch store form value after full cascade', () => {
      createFixture();

      expect(headerForm.controls.companyStoreId.value).toBe('store-1');
    });

    it('should show company details when company is selected', () => {
      const { componentInstance: comp } = createFixture();

      expect(comp.companyDetail()).not.toBeNull();
      expect(comp.companyDetail()!.rfc).toBe('RFC-001');
      expect(comp.companyDetail()!.email).toBe('contacto@empresauno.com');
      expect(comp.companyDetail()!.phone).toBe('555-1000');
      expect(comp.companyDetail()!.address).toContain('Av. Principal');
    });

    it('should not crash when profile has no companyId', () => {
      profileServiceMock.getProfile = vi.fn().mockReturnValue(
        of({
          ...mockProfile,
          companyId: null,
          companyCountryId: null,
          companyRegionId: null,
          companyZoneId: null,
          companyStoreId: null,
        }),
      );

      const { componentInstance: comp } = createFixture();

      expect(companyControls().companyId.value).toBeNull();
      expect(comp.companyDetail()).toBeNull();
    });
  });

  describe('edit mode', () => {
    function createEditFixture(order: PurchaseOrder) {
      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.componentRef.setInput('isEditMode', true);
      fixture.componentRef.setInput('loadedOrder', order);
      fixture.detectChanges();
      return fixture;
    }

    it('should reconstruct cascade from loadedOrder', () => {
      createEditFixture(mockOrder);

      expect(companyServiceMock.getCompanies).toHaveBeenCalled();
      expect(profileServiceMock.getProfile).not.toHaveBeenCalled();

      expect(companyControls().companyId.value).toBe('comp-1');
      expect(companyControls().companyCountryId.value).toBe('cc-1');
      expect(companyControls().regionId.value).toBe('reg-1');
      expect(companyControls().zoneId.value).toBe('zone-1');
      expect(headerForm.controls.companyStoreId.value).toBe('store-1');
    });

    it('should not load profile in edit mode', () => {
      createEditFixture(mockOrder);

      expect(profileServiceMock.getProfile).not.toHaveBeenCalled();
    });

    it('should do nothing when loadedOrder has no companyId', () => {
      const orderNoCompany = { ...mockOrder, companyId: null };
      const { componentInstance: comp } = createEditFixture(orderNoCompany);

      expect(companyControls().companyId.value).toBeNull();
      expect(comp.companyDetail()).toBeNull();
    });
  });

  describe('cascade change handlers', () => {
    function createFixture() {
      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.detectChanges();
      return fixture;
    }

    it('onCompanyChange should clear lower levels and load countries', () => {
      const { componentInstance: comp } = createFixture();

      comp.onCompanyChange('comp-2');

      expect(companyControls().companyId.value).toBe('comp-2');
      expect(comp.countries().length).toBeGreaterThan(0);
      expect(companyControls().companyCountryId.value).toBeNull();
      expect(comp.regions().length).toBe(0);
      expect(comp.stores().length).toBe(0);
    });

    it('onCompanyChange with empty string should clear everything', () => {
      const { componentInstance: comp } = createFixture();

      comp.onCompanyChange('');

      expect(companyControls().companyId.value).toBeNull();
      expect(comp.countries().length).toBe(0);
      expect(comp.companyDetail()).toBeNull();
    });

    it('onCountryChange should clear lower levels and load regions', () => {
      const { componentInstance: comp } = createFixture();

      comp.onCountryChange('cc-2');

      expect(companyControls().companyCountryId.value).toBe('cc-2');
      expect(comp.regions().length).toBeGreaterThan(0);
      expect(companyControls().regionId.value).toBeNull();
      expect(comp.zones().length).toBe(0);
    });

    it('onRegionChange should clear lower levels and load zones', () => {
      const { componentInstance: comp } = createFixture();

      comp.onRegionChange('reg-2');

      expect(companyControls().regionId.value).toBe('reg-2');
      expect(comp.zones().length).toBeGreaterThan(0);
      expect(companyControls().zoneId.value).toBeNull();
      expect(comp.stores().length).toBe(0);
    });

    it('onZoneChange should load stores', () => {
      const { componentInstance: comp } = createFixture();

      comp.onZoneChange('zone-2');

      expect(companyControls().zoneId.value).toBe('zone-2');
      expect(comp.stores().length).toBeGreaterThan(0);
    });
  });

  describe('store resolution output', () => {
    // The cascade service patches `companyStoreId` with `emitEvent: false`, so
    // `valueChanges` is blind to the profile prefill and the resets. The output
    // is the channel the picker relies on, so both paths are covered here.

    function createFixtureWithEmissions() {
      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      const emitted: string[] = [];
      fixture.componentInstance.storeResolved.subscribe((storeId) => emitted.push(storeId));
      fixture.detectChanges();
      fixture.detectChanges();
      return { fixture, emitted };
    }

    it('should emit the store silently applied by the profile prefill', () => {
      const { emitted } = createFixtureWithEmissions();

      expect(headerForm.controls.companyStoreId.value).toBe('store-1');
      expect(emitted).toContain('store-1');
    });

    it('should emit an empty store when a zone change resets it', () => {
      const { fixture, emitted } = createFixtureWithEmissions();
      expect(emitted.at(-1)).toBe('store-1');

      fixture.componentInstance.onZoneChange('zone-2');
      fixture.detectChanges();

      expect(headerForm.controls.companyStoreId.value).toBe('');
      expect(emitted.at(-1)).toBe('');
    });

    it('should emit an empty store when a company change resets it', () => {
      const { fixture, emitted } = createFixtureWithEmissions();

      fixture.componentInstance.onCompanyChange('comp-2');
      fixture.detectChanges();

      expect(headerForm.controls.companyStoreId.value).toBe('');
      expect(emitted.at(-1)).toBe('');
    });
  });

  describe('form helpers', () => {
    function createFixture() {
      // Override profile mock so cascade doesn't auto-fill form values
      profileServiceMock.getProfile = vi.fn().mockReturnValue(
        of({
          ...mockProfile,
          companyId: null,
          companyCountryId: null,
          companyRegionId: null,
          companyZoneId: null,
          companyStoreId: null,
        }),
      );

      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.detectChanges();
      return fixture;
    }

    it('fieldError should return required message when invalid and touched', () => {
      const { componentInstance: comp } = createFixture();

      headerForm.controls.companyStoreId.markAsTouched();
      const error = comp.fieldError('companyStoreId');
      expect(error).toBe('Este campo es requerido.');
    });

    it('fieldError should return null when control is valid', () => {
      const { componentInstance: comp } = createFixture();

      headerForm.controls.companyStoreId.setValue('store-1');
      headerForm.controls.companyStoreId.markAsTouched();
      const error = comp.fieldError('companyStoreId');
      expect(error).toBeNull();
    });

    it('serverFieldError should return server error for a field', () => {
      const fixture = TestBed.createComponent(CompanyInfoSection);
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.componentRef.setInput('serverErrors', { companyStoreId: 'Tienda no disponible' });
      const comp = fixture.componentInstance;
      fixture.detectChanges();

      expect(comp.serverFieldError('companyStoreId')).toBe('Tienda no disponible');
    });
  });
});
