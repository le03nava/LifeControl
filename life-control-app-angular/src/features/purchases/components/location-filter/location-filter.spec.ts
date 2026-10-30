import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MatSelect } from '@angular/material/select';
import { of } from 'rxjs';
import { PurchaseLocationFilter } from './location-filter';
import { LocationPreference } from '@features/user/profile/data/location-preference.service';
import { ProfileService } from '@features/user/profile/data/profile.service';
import type { LocationChain, ProfileResponse } from '@features/user/profile/data/profile.models';
import type { PurchaseLocationSelection } from '../../models/purchase-location-filter.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import type {
  CompanySelectorOption,
  Page,
} from '@features/companies/companies/models/company.models';
import type { CompanyCountry } from '@features/companies/countries/models/country.models';
import type { CompanyRegion } from '@features/companies/regions/models/region.models';
import type { CompanyZone } from '@features/companies/zones/models/zone.models';
import type { CompanyStore } from '@features/companies/stores/models/store.models';

const mockCompanies: CompanySelectorOption[] = [
  { id: 'company-1', companyKey: 'COMP001', companyName: 'Test Company One' },
  { id: 'company-2', companyKey: 'COMP002', companyName: 'Test Company Two' },
];

const mockCompaniesPage: Page<CompanySelectorOption> = {
  content: mockCompanies,
  totalElements: mockCompanies.length,
  totalPages: 1,
  size: 1000,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

const mockCountries: CompanyCountry[] = [
  {
    id: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    countryCode: 'MX',
    countryName: 'Mexico',
    localAlias: 'Sucursal CDMX',
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  },
];

const mockRegions: CompanyRegion[] = [
  {
    id: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    regionCode: 'CENTRO',
    regionName: 'Zona Centro',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  },
  {
    id: 'reg-2',
    companyCountryId: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    regionCode: 'NORTE',
    regionName: 'Zona Norte',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  },
];

const mockZones: CompanyZone[] = [
  {
    id: 'zone-1',
    companyRegionId: 'reg-1',
    companyCountryId: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    zoneCode: 'CDMX-DT',
    zoneName: 'Downtown',
    description: 'Centro histórico',
    displayOrder: 1,
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  },
];

const mockStores: CompanyStore[] = [
  {
    id: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Central',
    email: 'central@store.com',
    phoneNumber: '+525512345678',
    address: {
      street: 'Av. Reforma',
      streetNumber: '222',
      neighborhood: 'Juárez',
      zipCode: '06600',
      city: 'Ciudad de México',
      state: 'CDMX',
      countryId: 'MX',
    },
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-15',
    version: 0,
  },
];

const emptySelection: PurchaseLocationSelection = {
  companyId: null,
  companyCountryId: null,
  regionId: null,
  zoneId: null,
  companyStoreId: null,
};

/** A location chain as `GET /api/profile` derives it. */
const locationChain = (overrides: Partial<LocationChain> = {}): LocationChain => ({
  companyId: 'company-1',
  companyCountryId: 'cc-1',
  companyRegionId: 'reg-1',
  companyZoneId: 'zone-1',
  companyStoreId: 'store-1',
  companyStoreName: 'Tienda Central',
  ...overrides,
});

const profileResponse = (location: LocationChain | null): ProfileResponse => ({
  keycloakUserId: 'user-1',
  username: 'operator',
  email: 'operator@lifecontrol.test',
  firstName: 'Oper',
  lastName: 'Ator',
  companyStoreId: location?.companyStoreId ?? null,
  assignedStores: null,
  location,
});

class MockCompanyService {
  getCompanySelector = vi.fn().mockReturnValue(of(mockCompaniesPage));
}

class MockCompanyCountryService {
  getCountries = vi
    .fn()
    .mockImplementation((companyId: string) =>
      of(companyId === 'company-1' ? mockCountries : ([] as CompanyCountry[])),
    );
}

class MockCompanyRegionService {
  getRegions = vi
    .fn()
    .mockImplementation((_companyId: string, countryId: string) =>
      of(countryId === 'cc-1' ? mockRegions : ([] as CompanyRegion[])),
    );
}

class MockCompanyZoneService {
  getZones = vi
    .fn()
    .mockImplementation((_companyId: string, _countryId: string, regionId: string) =>
      of(regionId === 'reg-1' ? mockZones : ([] as CompanyZone[])),
    );
}

class MockCompanyStoreService {
  getStores = vi
    .fn()
    .mockImplementation(
      (_companyId: string, _countryId: string, _regionId: string, zoneId: string) =>
        of(zoneId === 'zone-1' ? mockStores : ([] as CompanyStore[])),
    );
}

describe('PurchaseLocationFilter', () => {
  let fixture: ComponentFixture<PurchaseLocationFilter>;
  let component: PurchaseLocationFilter;
  /** The chain the profile reports for the next mount; `null` means no preference. */
  let location: LocationChain | null;
  let companyService: MockCompanyService;
  let countryService: MockCompanyCountryService;
  let regionService: MockCompanyRegionService;
  let zoneService: MockCompanyZoneService;
  let storeService: MockCompanyStoreService;
  let emitted: PurchaseLocationSelection[];

  beforeEach(async () => {
    location = null;
    companyService = new MockCompanyService();
    countryService = new MockCompanyCountryService();
    regionService = new MockCompanyRegionService();
    zoneService = new MockCompanyZoneService();
    storeService = new MockCompanyStoreService();
    emitted = [];

    await TestBed.configureTestingModule({
      imports: [PurchaseLocationFilter],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: CompanyCountryService, useValue: countryService },
        { provide: CompanyRegionService, useValue: regionService },
        { provide: CompanyZoneService, useValue: zoneService },
        { provide: CompanyStoreService, useValue: storeService },
        // The component preselects through the shared location preference, which reads the profile.
        { provide: ProfileService, useValue: { getProfile: () => of(profileResponse(location)) } },
      ],
    }).compileComponents();
  });

  // Flush the reactive chain: the preference resolves through its own resource and each level loads
  // only once the one above it has an option, so a single settle is not enough.
  async function settle(): Promise<void> {
    for (let i = 0; i < 8; i++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function mount(): void {
    fixture = TestBed.createComponent(PurchaseLocationFilter);
    component = fixture.componentInstance;
    component.selection.subscribe((selection) => emitted.push(selection));
  }

  function selects(): MatSelect[] {
    return fixture.debugElement
      .queryAll(By.directive(MatSelect))
      .map((debug) => debug.componentInstance as unknown as MatSelect);
  }

  function lastSelection(): PurchaseLocationSelection | undefined {
    return emitted.at(-1);
  }

  describe('rendering', () => {
    it('renders the five filter labels in the required order', () => {
      mount();
      fixture.detectChanges();

      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('mat-label') as NodeListOf<HTMLElement>,
      ).map((label) => label.textContent?.trim());

      expect(labels).toEqual(['Empresa', 'País', 'Región', 'Zona', 'Tienda']);
      expect(selects().length).toBe(5);
    });
  });

  describe('cascade', () => {
    it('loads the countries of the chosen company and clears every level below it', async () => {
      mount();
      await settle();

      component.onCompanyChange(mockCompanies[0]);
      await settle();
      expect(countryService.getCountries).toHaveBeenCalledWith('company-1');

      component.onCountryChange(mockCountries[0]);
      await settle();
      component.onRegionChange(mockRegions[0]);
      await settle();
      component.onZoneChange(mockZones[0]);
      await settle();
      component.onStoreChange(mockStores[0]);
      await settle();
      expect(component.selectedStore()?.id).toBe('store-1');

      component.onCompanyChange(mockCompanies[1]);
      await settle();

      expect(component.selectedCountry()).toBeNull();
      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(lastSelection()).toEqual({ ...emptySelection, companyId: 'company-2' });
    });

    it('disables Zona without a Región and Tienda without a Zona', async () => {
      mount();
      await settle();

      expect(selects()[3].disabled).toBe(true);
      expect(selects()[4].disabled).toBe(true);

      component.onCompanyChange(mockCompanies[0]);
      await settle();
      component.onCountryChange(mockCountries[0]);
      await settle();
      component.onRegionChange(mockRegions[0]);
      await settle();

      expect(selects()[3].disabled).toBe(false);
      expect(selects()[4].disabled).toBe(true);

      component.onZoneChange(mockZones[0]);
      await settle();

      expect(selects()[4].disabled).toBe(false);
    });
  });

  describe('preference preselection', () => {
    it('preselects all five levels when every preference id is listed by its level', async () => {
      location = locationChain();

      mount();
      await settle();

      expect(component.selectedCompany()?.id).toBe('company-1');
      expect(component.selectedCountry()?.id).toBe('cc-1');
      expect(component.selectedRegion()?.id).toBe('reg-1');
      expect(component.selectedZone()?.id).toBe('zone-1');
      expect(component.selectedStore()?.id).toBe('store-1');
      expect(lastSelection()).toEqual({
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
        zoneId: 'zone-1',
        companyStoreId: 'store-1',
      });
    });

    it('resolves the preference exactly once', async () => {
      const resolve = vi.spyOn(TestBed.inject(LocationPreference), 'resolve');

      mount();
      await settle();

      expect(resolve).toHaveBeenCalledTimes(1);
    });

    it('does not select a preference id its level does not list, and guesses nothing', async () => {
      // `cc-99` is not a country of this company, while the region/zone/store ids below it do exist
      // under `cc-1`: that is exactly the cross-level guess this has to refuse.
      location = locationChain({ companyCountryId: 'cc-99' });

      mount();
      await settle();

      expect(component.selectedCompany()?.id).toBe('company-1');
      expect(component.selectedCountry()).toBeNull();
      expect(component.selectedRegion()).toBeNull();
      expect(component.selectedZone()).toBeNull();
      expect(component.selectedStore()).toBeNull();
      expect(regionService.getRegions).not.toHaveBeenCalled();
      expect(zoneService.getZones).not.toHaveBeenCalled();
      expect(storeService.getStores).not.toHaveBeenCalled();
      expect(lastSelection()).toEqual({ ...emptySelection, companyId: 'company-1' });
    });
  });

  describe('selection output', () => {
    it('emits the whole selection on a level change', async () => {
      mount();
      await settle();

      component.onCompanyChange(mockCompanies[0]);
      await settle();
      expect(lastSelection()).toEqual({ ...emptySelection, companyId: 'company-1' });

      component.onCountryChange(mockCountries[0]);
      await settle();
      expect(lastSelection()).toEqual({
        ...emptySelection,
        companyId: 'company-1',
        companyCountryId: 'cc-1',
      });

      component.onRegionChange(mockRegions[0]);
      await settle();
      expect(lastSelection()).toEqual({
        ...emptySelection,
        companyId: 'company-1',
        companyCountryId: 'cc-1',
        regionId: 'reg-1',
      });
    });
  });
});
