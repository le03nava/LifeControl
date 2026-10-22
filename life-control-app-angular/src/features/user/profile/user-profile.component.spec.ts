import { TestBed } from '@angular/core/testing';
import type { ComponentFixture } from '@angular/core/testing';
import { provideRouter, withRouterConfig, ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideLocationMocks } from '@angular/common/testing';
import { By } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { MatSelect } from '@angular/material/select';

import { UserProfileComponent } from './user-profile.component';
import { ProfileService } from './data/profile.service';
import { LocationPreference } from './data/location-preference.service';
import { ProfileResponse, AssignedStore, LocationChain } from './data/profile.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import type { Company, Page } from '@features/companies/companies/models/company.models';
import type { CompanyCountry } from '@features/companies/countries/models/country.models';
import type { CompanyRegion } from '@features/companies/regions/models/region.models';
import type { CompanyZone } from '@features/companies/zones/models/zone.models';
import type { CompanyStore } from '@features/companies/stores/models/store.models';

describe('UserProfileComponent', () => {
  const mockProfile: ProfileResponse = {
    keycloakUserId: 'user-123',
    username: 'johndoe',
    email: 'john@example.com',
    firstName: 'John',
    lastName: 'Doe',
    companyStoreId: null,
    assignedStores: null,
    location: null,
  };

  /**
   * The chain `GET /api/profile` derives from the stored store (`S1`), as the response carries it.
   *
   * `mockProfileWithLocation` below carries it because `G14` recorded the gap: this spec held
   * `location: null` beside a stored `companyStoreId`, so the view-mode label could not be exercised
   * against the field it now reads (`S11`).
   */
  const mockLocationChain: LocationChain = {
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    companyRegionId: 'reg-1',
    companyZoneId: 'zone-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Store One',
  };

  // T14: the four tenancy ancestors left the wire, so this fixture no longer carries them. The chain a
  // caller needs in order to preselect a cascade is resolved from `companyStoreId` through the flat
  // store read (T20), which is what the cascade specs now assert against.
  const mockProfileWithLocation: ProfileResponse = {
    ...mockProfile,
    companyStoreId: 'store-1',
    location: mockLocationChain,
  };

  /**
   * The legitimate `S2` pair `L14` recorded, kept as its own fixture instead of hidden inside the one
   * above: a stored `companyStoreId` whose chain did not resolve (not assigned any more, or no longer
   * readable). The read still succeeds, and the screen must fail closed on the absence.
   */
  const mockProfileWithUnresolvedLocation: ProfileResponse = {
    ...mockProfileWithLocation,
    location: null,
  };

  /**
   * The two stores a constrained caller is assigned to. Store Two deliberately lives in a different
   * country/region/zone than the profile's own scaffolding, so a payload that kept the previous
   * ancestors instead of taking them from the chosen store is observable.
   */
  const mockAssignedStores: AssignedStore[] = [
    {
      companyStoreId: 'store-1',
      companyStoreName: 'Store One',
      companyId: 'comp-1',
      companyName: 'Company One',
      companyCountryId: 'cc-1',
      companyCountryName: 'Mexico',
      companyRegionId: 'reg-1',
      companyRegionName: 'Norte',
      companyZoneId: 'zone-1',
      companyZoneName: 'Zone One',
    },
    {
      companyStoreId: 'store-2',
      companyStoreName: 'Store Two',
      companyId: 'comp-1',
      companyName: 'Company One',
      companyCountryId: 'cc-2',
      companyCountryName: 'United States',
      companyRegionId: 'reg-2',
      companyRegionName: 'Sur',
      companyZoneId: 'zone-2',
      companyZoneName: 'Zone Two',
    },
  ];

  /** A constrained caller (an array, so not `null`): the preference is narrowed to the assigned set. */
  const mockConstrainedProfile: ProfileResponse = {
    ...mockProfileWithLocation,
    assignedStores: mockAssignedStores,
  };

  /** An employee with **zero** assignments: still constrained, but with nothing to choose (D14). */
  const mockConstrainedEmptyProfile: ProfileResponse = {
    ...mockProfile,
    assignedStores: [],
  };

  const mockCompanies: Company[] = [
    {
      id: 'comp-1',
      companyKey: 'C1',
      companyName: 'Company One',
      tipoPersonaId: 1,
      razonSocial: 'Razon 1',
      rfc: 'RFC1',
      email: 'c1@test.com',
      phone: '555-0001',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
    {
      id: 'comp-2',
      companyKey: 'C2',
      companyName: 'Company Two',
      tipoPersonaId: 1,
      razonSocial: 'Razon 2',
      rfc: 'RFC2',
      email: 'c2@test.com',
      phone: '555-0002',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockCountries: CompanyCountry[] = [
    {
      id: 'cc-1',
      companyId: 'comp-1',
      countryId: 'ctry-1',
      countryCode: 'MX',
      countryName: 'Mexico',
      localAlias: null,
      createdAt: '',
      updatedAt: '',
    },
    {
      id: 'cc-2',
      companyId: 'comp-1',
      countryId: 'ctry-2',
      countryCode: 'US',
      countryName: 'United States',
      localAlias: null,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockRegions: CompanyRegion[] = [
    {
      id: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'comp-1',
      countryId: 'ctry-1',
      regionCode: 'NORTE',
      regionName: 'Norte',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
    {
      id: 'reg-2',
      companyCountryId: 'cc-1',
      companyId: 'comp-1',
      countryId: 'ctry-1',
      regionCode: 'SUR',
      regionName: 'Sur',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockZones: CompanyZone[] = [
    {
      id: 'zone-1',
      companyRegionId: 'reg-1',
      companyCountryId: 'cc-1',
      companyId: 'comp-1',
      countryId: 'ctry-1',
      zoneCode: 'Z1',
      zoneName: 'Zone One',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const mockStores: CompanyStore[] = [
    {
      id: 'store-1',
      companyId: 'comp-1',
      companyCountryId: 'cc-1',
      regionId: 'reg-1',
      zoneId: 'zone-1',
      storeName: 'Store One',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      version: 0,
    },
  ];

  function setup(
    queryParams: Record<string, string> = {},
    profileData: ProfileResponse = mockProfile,
    hasLocationServices = false,
  ) {
    // Mock ProfileService
    const profileServiceMock = {
      getProfile: vi.fn().mockReturnValue(of(profileData)),
      updateProfile: vi.fn().mockReturnValue(of(profileData)),
    };

    // Mock CompanyService
    const companyServiceMock = {
      getCompanies: vi.fn().mockReturnValue(
        of({
          content: mockCompanies,
          totalElements: 2,
          totalPages: 1,
          size: 1000,
          number: 0,
          first: true,
          last: true,
          empty: false,
        } as Page<Company>),
      ),
    };

    // Mock CompanyCountryService
    const companyCountryServiceMock = {
      getCountries: vi.fn().mockReturnValue(of(hasLocationServices ? mockCountries : [])),
    };

    // Mock CompanyRegionService
    const companyRegionServiceMock = {
      getRegions: vi.fn().mockReturnValue(of(hasLocationServices ? mockRegions : [])),
    };

    // Mock CompanyZoneService
    const companyZoneServiceMock = {
      getZones: vi.fn().mockReturnValue(of(hasLocationServices ? mockZones : [])),
    };

    // Mock CompanyStoreService
    const companyStoreServiceMock = {
      getStores: vi.fn().mockReturnValue(of(hasLocationServices ? mockStores : [])),
      // The flat, id-addressed read the free-mode preselect used to seed from (`e5430b8`, W3/T20),
      // kept on the mock so the spec can pin that the screen no longer pays for it (`G17`).
      getStoreById: vi.fn().mockReturnValue(of(mockStores[0])),
    };

    TestBed.configureTestingModule({
      imports: [UserProfileComponent],
      providers: [
        provideRouter([], withRouterConfig({ resolveNavigationPromiseOnError: true })),
        provideLocationMocks(),
        provideHttpClient(withXhr()),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: () => null },
              queryParamMap: { get: (key: string) => queryParams[key] ?? null },
            },
          },
        },
        { provide: ProfileService, useValue: profileServiceMock },
        { provide: CompanyService, useValue: companyServiceMock },
        { provide: CompanyCountryService, useValue: companyCountryServiceMock },
        { provide: CompanyRegionService, useValue: companyRegionServiceMock },
        { provide: CompanyZoneService, useValue: companyZoneServiceMock },
        { provide: CompanyStoreService, useValue: companyStoreServiceMock },
        {
          provide: Keycloak,
          useValue: {
            authenticated: false,
            login: vi.fn(),
            logout: vi.fn(),
            hasRealmRole: vi.fn().mockReturnValue(false),
          },
        },
        {
          provide: KEYCLOAK_EVENT_SIGNAL,
          useValue: signal({ type: KeycloakEventType.Ready, token: null }),
        },
      ],
    });

    const fixture = TestBed.createComponent(UserProfileComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    return {
      fixture,
      component,
      profileServiceMock,
      companyServiceMock,
      companyCountryServiceMock,
      companyRegionServiceMock,
      companyZoneServiceMock,
      companyStoreServiceMock,
    };
  }

  // ─── View mode ─────────────────────────────────────────────

  describe('view mode (default)', () => {
    it('should create the component', () => {
      const { component } = setup();
      expect(component).toBeTruthy();
    });

    it('should NOT be in edit mode by default', () => {
      const { component } = setup();
      expect(component.isEditMode()).toBe(false);
    });

    it('should load profile on init', () => {
      const { profileServiceMock } = setup();
      expect(profileServiceMock.getProfile).toHaveBeenCalled();
    });

    it('should display the user full name', () => {
      const { fixture } = setup();
      expect(fixture.nativeElement.textContent).toContain('John Doe');
    });

    it('should display the user email', () => {
      const { fixture } = setup();
      expect(fixture.nativeElement.textContent).toContain('john@example.com');
    });

    it('should display the username', () => {
      const { fixture } = setup();
      expect(fixture.nativeElement.textContent).toContain('johndoe');
    });

    it('should show Edit Profile button', () => {
      const { fixture } = setup();
      const button = fixture.nativeElement.querySelector('button');
      expect(button).toBeTruthy();
      expect(button.textContent).toContain('Edit Profile');
    });

    it('should display a Material card', () => {
      const { fixture } = setup();
      const card = fixture.nativeElement.querySelector('mat-card');
      expect(card).toBeTruthy();
    });

    it('should display a profile icon', () => {
      const { fixture } = setup();
      const icon = fixture.nativeElement.querySelector('mat-icon');
      expect(icon).toBeTruthy();
      expect(icon.textContent).toContain('account_circle');
    });
  });

  // ─── Edit mode ─────────────────────────────────────────────

  describe('edit mode (?edit=true)', () => {
    it('should be in edit mode when ?edit=true is set', () => {
      const { component } = setup({ edit: 'true' });
      expect(component.isEditMode()).toBe(true);
    });

    it('should load companies on init when in edit mode', () => {
      const { companyServiceMock } = setup({ edit: 'true' });
      expect(companyServiceMock.getCompanies).toHaveBeenCalled();
    });

    it('should render the edit form', () => {
      const { fixture } = setup({ edit: 'true' });
      const form = fixture.nativeElement.querySelector('form');
      expect(form).toBeTruthy();
    });

    it('should render the Edit Profile title', () => {
      const { fixture } = setup({ edit: 'true' });
      expect(fixture.nativeElement.textContent).toContain('Edit Profile');
    });

    it('should have firstName form field', () => {
      const { fixture } = setup({ edit: 'true' });
      const label = fixture.nativeElement.querySelector('mat-label');
      expect(label).toBeTruthy();
    });

    it('should pre-populate the form from profile', () => {
      const { component } = setup({ edit: 'true' });
      const formValues = component.form().getRawValue();
      expect(formValues.firstName).toBe('John');
      expect(formValues.lastName).toBe('Doe');
      expect(formValues.email).toBe('john@example.com');
    });

    it('should show Cancel button', () => {
      const { fixture } = setup({ edit: 'true' });
      const buttons: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
      const cancelBtn = buttons.find((b) => b.textContent?.includes('Cancel'));
      expect(cancelBtn).toBeTruthy();
    });

    it('should show Save Changes button', () => {
      const { fixture } = setup({ edit: 'true' });
      const buttons: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
      const saveBtn = buttons.find((b) => b.textContent?.includes('Save Changes'));
      expect(saveBtn).toBeTruthy();
    });

    it('should disable country select when no company selected', () => {
      const { fixture } = setup({ edit: 'true' });
      // The second mat-select (country) should have mat-select-disabled class
      // when companyId is null because the template uses [disabled]="!form().get('companyId')?.value"
      const countrySelect = fixture.nativeElement.querySelectorAll('.mat-mdc-select')[1];
      expect(countrySelect).toBeTruthy();
      expect(countrySelect.classList.contains('mat-mdc-select-disabled')).toBe(true);
    });

    it('should show company options from CompanyService', () => {
      const { fixture } = setup({ edit: 'true' });
      // The mat-select for company should be rendered
      const selects = fixture.nativeElement.querySelectorAll('mat-select');
      expect(selects.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ─── Edit mode with location data ──────────────────────────

  describe('edit mode with location pre-populated', () => {
    it('should load cascade when profile has companyId', () => {
      const { companyCountryServiceMock } = setup({ edit: 'true' }, mockProfileWithLocation, true);
      expect(companyCountryServiceMock.getCountries).toHaveBeenCalledWith('comp-1');
    });

    // G17: the five levels come from the chain the response already carries (`location`), so the
    // screen neither walks the cascade by hand nor pays a second read for the chain it was handed.
    it('preselects all five levels from the location chain (G17)', () => {
      const { component } = setup({ edit: 'true' }, mockProfileWithLocation, true);

      expect(component.form().getRawValue()).toEqual({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: 'store-1',
      });
    });

    // The same defect seen from the DOM: with the ancestors unpatched the country, region, zone and
    // store selects render disabled, because each binds `[disabled]` to the level above it.
    it('leaves every level of the free-mode cascade enabled after the preselect (G17)', () => {
      const { fixture } = setup({ edit: 'true' }, mockProfileWithLocation, true);

      const selects = Array.from(
        fixture.nativeElement.querySelectorAll('.mat-mdc-select'),
      ) as HTMLElement[];
      expect(selects.length).toBe(5);
      expect(selects.map((select) => select.classList.contains('mat-mdc-select-disabled'))).toEqual(
        [false, false, false, false, false],
      );
    });

    it('issues no store read of its own for the preselect (G17)', () => {
      const { companyStoreServiceMock } = setup({ edit: 'true' }, mockProfileWithLocation, true);

      expect(companyStoreServiceMock.getStoreById).not.toHaveBeenCalled();
    });

    // S2: a stored `companyStoreId` whose chain did not resolve is a real pair, and the screen fails
    // closed on it — no preselect, no second read, and no error the caller did not cause.
    it('preselects nothing for the S2 pair and asks for no store (S2)', () => {
      const { component, companyCountryServiceMock, companyStoreServiceMock } = setup(
        { edit: 'true' },
        mockProfileWithUnresolvedLocation,
        true,
      );

      expect(component.form().getRawValue().companyId).toBeNull();
      expect(component.form().getRawValue().companyStoreId).toBe('store-1');
      expect(companyCountryServiceMock.getCountries).not.toHaveBeenCalled();
      expect(companyStoreServiceMock.getStoreById).not.toHaveBeenCalled();
      expect(component.error()).toBeNull();
    });
  });

  // ─── Save / Cancel ─────────────────────────────────────────

  describe('save', () => {
    it('should call updateProfile on save', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' });
      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalled();
    });

    it('posts no tenancy ancestor and only the store the caller touched (W3/T14/T15/T16)', () => {
      // T14: the four ancestors are not writable any more, so the payload must not carry a single one
      // of them. T15/T16: the store is the only location value posted, and only because it was touched.
      const { component, profileServiceMock } = setup({ edit: 'true' }, mockProfileWithLocation);
      component.onStoreChange('store-2');
      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        companyStoreId: 'store-2',
      });
    });

    it('omits companyStoreId when the caller never touched it, so a stored preference survives (W3/T16)', () => {
      // T16: the response no longer carries the ancestors, so a free-mode load cannot rebuild the
      // cascade; an untouched control must therefore be omitted instead of posting null, which would
      // silently clear the preference the row still holds.
      const { component, profileServiceMock } = setup({ edit: 'true' }, mockProfileWithLocation);
      component.save();

      const payload = profileServiceMock.updateProfile.mock.calls[0][0];
      expect(payload).not.toHaveProperty('companyStoreId');
      expect(payload).not.toHaveProperty('companyId');
    });

    // S9: the only writer is the subject, from /profile. A load that resolves a stored store patches
    // the form and walks the cascade, and none of that may reach the API.
    it('never writes from a load, even one that preselects a stored store (S9)', () => {
      const { companyStoreServiceMock, profileServiceMock } = setup(
        { edit: 'true' },
        mockProfileWithLocation,
        true,
      );

      expect(companyStoreServiceMock.getStoreById).not.toHaveBeenCalled();
      expect(profileServiceMock.updateProfile).not.toHaveBeenCalled();
    });

    // S10, free mode: the store control's own `— None —` (onStoreChange(null)) is a deliberate clear,
    // and the wire distinguishes it from an omitted key with `clearStorePreference` — never with
    // `companyStoreId: null`, which the server cannot tell apart from an absent key.
    it('posts clearStorePreference alone when the free-mode store select is cleared (S10)', () => {
      const { component, profileServiceMock } = setup(
        { edit: 'true' },
        mockProfileWithLocation,
        true,
      );

      component.onStoreChange(null);
      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        clearStorePreference: true,
      });
      expect(profileServiceMock.updateProfile.mock.calls[0][0]).not.toHaveProperty(
        'companyStoreId',
      );
    });

    it('should navigate to /profile after successful save', () => {
      const { component } = setup({ edit: 'true' });
      const router = TestBed.inject(Router);
      const navigateSpy = vi.spyOn(router, 'navigate');
      component.save();
      expect(navigateSpy).toHaveBeenCalledWith(['/profile']);
    });

    it('should not save when form is invalid', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' });
      component.form().patchValue({ firstName: '' });
      component.save();
      expect(profileServiceMock.updateProfile).not.toHaveBeenCalled();
    });

    it('should handle save error', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' });
      profileServiceMock.updateProfile = vi
        .fn()
        .mockReturnValue(throwError(() => new Error('Update failed')));
      component.save();
      expect(component.error()).toBeTruthy();
    });
  });

  describe('cancel', () => {
    it('should navigate to /profile on cancel', () => {
      const { component } = setup({ edit: 'true' });
      expect(() => component.cancel()).not.toThrow();
    });
  });

  // ─── Error state ──────────────────────────────────────────

  describe('error state', () => {
    it('should show error when profile load fails', () => {
      TestBed.resetTestingModule();

      const profileServiceMock = {
        getProfile: vi.fn().mockReturnValue(throwError(() => new Error('Load failed'))),
        updateProfile: vi.fn(),
      };

      TestBed.configureTestingModule({
        imports: [UserProfileComponent],
        providers: [
          provideRouter([], withRouterConfig({ resolveNavigationPromiseOnError: true })),
          provideLocationMocks(),
          provideHttpClient(withXhr()),
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: {
                paramMap: { get: () => null },
                queryParamMap: { get: () => null },
              },
            },
          },
          { provide: ProfileService, useValue: profileServiceMock },
          {
            provide: CompanyService,
            useValue: { getCompanies: vi.fn() },
          },
          {
            provide: CompanyCountryService,
            useValue: { getCountries: vi.fn() },
          },
          {
            provide: CompanyRegionService,
            useValue: { getRegions: vi.fn() },
          },
          {
            provide: CompanyZoneService,
            useValue: { getZones: vi.fn() },
          },
          {
            provide: CompanyStoreService,
            useValue: { getStores: vi.fn() },
          },
          {
            provide: Keycloak,
            useValue: {
              authenticated: false,
              login: vi.fn(),
              logout: vi.fn(),
              hasRealmRole: vi.fn().mockReturnValue(false),
            },
          },
          {
            provide: KEYCLOAK_EVENT_SIGNAL,
            useValue: signal({ type: KeycloakEventType.Ready, token: null }),
          },
        ],
      });

      const fixture = TestBed.createComponent(UserProfileComponent);
      const component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.error()).toBeTruthy();
    });
  });

  // ─── Cascading select handlers ────────────────────────────

  describe('cascading select handlers', () => {
    it('onCompanyChange should reset downstream selects', () => {
      const { component } = setup({ edit: 'true' });
      // First populate some values
      component.form().patchValue({
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: 'store-1',
      });

      component.onCompanyChange('comp-2');

      const formValues = component.form().getRawValue();
      expect(formValues.companyId).toBe('comp-2');
      expect(formValues.companyCountryId).toBeNull();
      expect(formValues.companyRegionId).toBeNull();
      expect(formValues.companyZoneId).toBeNull();
      expect(formValues.companyStoreId).toBeNull();
    });

    it('onCompanyChange should load countries when company selected', () => {
      const { component, companyCountryServiceMock } = setup({ edit: 'true' });
      component.onCompanyChange('comp-1');
      expect(companyCountryServiceMock.getCountries).toHaveBeenCalledWith('comp-1');
    });

    it('onCountryChange should load regions', () => {
      const { component, companyRegionServiceMock } = setup({ edit: 'true' });
      component.form().patchValue({ companyId: 'comp-1' });
      component.onCountryChange('cc-1');
      expect(companyRegionServiceMock.getRegions).toHaveBeenCalledWith('comp-1', 'cc-1');
    });

    it('onRegionChange should load zones', () => {
      const { component, companyZoneServiceMock } = setup({ edit: 'true' });
      component.form().patchValue({ companyId: 'comp-1', companyCountryId: 'cc-1' });
      component.onRegionChange('reg-1');
      expect(companyZoneServiceMock.getZones).toHaveBeenCalledWith('comp-1', 'cc-1', 'reg-1');
    });

    it('onZoneChange should load stores', () => {
      const { component, companyStoreServiceMock } = setup({ edit: 'true' });
      component.form().patchValue({
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
      });
      component.onZoneChange('zone-1');
      expect(companyStoreServiceMock.getStores).toHaveBeenCalledWith(
        'comp-1',
        'cc-1',
        'reg-1',
        'zone-1',
      );
    });

    it('onStoreChange should update the form value', () => {
      const { component } = setup({ edit: 'true' });
      component.onStoreChange('store-2');
      expect(component.form().get('companyStoreId')?.value).toBe('store-2');
    });
  });

  // ─── View-mode location row (S11) ─────────────────────────

  describe('view-mode location row (S11)', () => {
    /** The label/value pairs the view mode rendered, whitespace-normalised. */
    function infoRows(fixture: ComponentFixture<UserProfileComponent>): [string, string][] {
      const rows = Array.from(
        fixture.nativeElement.querySelectorAll('.info-row') as NodeListOf<Element>,
      );
      return rows.map((row) => [
        (row.querySelector('.info-label')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
        (row.querySelector('.info-value')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ]);
    }

    it('shows the store name the chain resolved in the free mode (S11, G14)', () => {
      const { fixture } = setup({}, mockProfileWithLocation);

      expect(infoRows(fixture)).toContainEqual(['Location', 'Store One']);
      // The literal is gone: it could never say which store, which is the whole of S11.
      expect(fixture.nativeElement.textContent).not.toContain('Store assigned');
    });

    it("keeps the assigned store's derived chain in the constrained mode (S11)", () => {
      const { fixture } = setup({}, mockConstrainedProfile);

      expect(infoRows(fixture)).toContainEqual([
        'Location',
        'Company One / Mexico / Norte / Zone One',
      ]);
      expect(fixture.nativeElement.textContent).not.toContain('Store assigned');
    });

    it('hides the row when the stored store has no resolved chain (S2 pair, S11)', () => {
      const { fixture } = setup({}, mockProfileWithUnresolvedLocation);

      expect(infoRows(fixture).some(([label]) => label === 'Location')).toBe(false);
    });

    it('hides the row when there is no stored preference at all', () => {
      const { fixture } = setup({}, mockProfile);

      expect(infoRows(fixture).some(([label]) => label === 'Location')).toBe(false);
    });
  });

  // ─── Constrained location mode (assignedStores) ────────────

  describe('constrained store selection (assignedStores)', () => {
    /** Whitespace-normalised text, so template indentation never breaks an assertion. */
    function textContent(element: Element | null): string {
      return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
    }

    /**
     * The labels of the single constrained store select, read from the Material component. Material
     * renders the options lazily, so they are read from the select's own option query rather than
     * from the projected DOM.
     */
    function assignedStoreOptionLabels(fixture: ComponentFixture<UserProfileComponent>): string[] {
      const select = fixture.debugElement.query(By.css('mat-select'))
        .componentInstance as MatSelect;
      return select.options.map((option) => option.viewValue.replace(/\s+/g, ' ').trim());
    }

    /** Opens the constrained store select and clicks the option whose label contains `label`. */
    function chooseAssignedStore(
      fixture: ComponentFixture<UserProfileComponent>,
      label: string,
    ): void {
      const select = fixture.debugElement.query(By.css('mat-select'))
        .componentInstance as MatSelect;
      select.open();
      fixture.detectChanges();
      const options = Array.from(
        (select.panel.nativeElement as HTMLElement).querySelectorAll('mat-option'),
      ) as HTMLElement[];
      const option = options.find((candidate) => textContent(candidate).includes(label));
      expect(option).toBeTruthy();
      option?.click();
      fixture.detectChanges();
      select.close();
      fixture.detectChanges();
    }

    it('renders one store select over the assigned set and never walks the cascade', () => {
      const {
        fixture,
        companyServiceMock,
        companyCountryServiceMock,
        companyRegionServiceMock,
        companyZoneServiceMock,
        companyStoreServiceMock,
      } = setup({ edit: 'true' }, mockConstrainedProfile, true);

      // The four ancestor selects are replaced by a single store select (T26).
      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(1);
      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('mat-label') as NodeListOf<Element>,
      ).map((label) => textContent(label));
      expect(labels).not.toContain('Company');
      expect(labels).not.toContain('Country');
      expect(labels).not.toContain('Region');
      expect(labels).not.toContain('Zone');
      expect(labels).toContain('Store');

      // Constrained mode performs no four-level walk (T29).
      expect(companyServiceMock.getCompanies).not.toHaveBeenCalled();
      expect(companyCountryServiceMock.getCountries).not.toHaveBeenCalled();
      expect(companyRegionServiceMock.getRegions).not.toHaveBeenCalled();
      expect(companyZoneServiceMock.getZones).not.toHaveBeenCalled();
      expect(companyStoreServiceMock.getStores).not.toHaveBeenCalled();
    });

    it('labels every option with the store and its derived chain', () => {
      const { fixture } = setup({ edit: 'true' }, mockConstrainedProfile);

      expect(assignedStoreOptionLabels(fixture)).toEqual([
        '— None —',
        'Store One — Company One / Mexico / Norte / Zone One',
        'Store Two — Company One / United States / Sur / Zone Two',
      ]);
    });

    it('posts only the chosen store, and no tenancy ancestor (W3/T15)', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      chooseAssignedStore(fixture, 'Store Two');

      // T15: the four ancestor controls are navigator state, never a payload, so the handler patches the
      // store alone and the request carries exactly what the API accepts.
      expect(component.form().getRawValue()).toMatchObject({ companyStoreId: 'store-2' });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        companyStoreId: 'store-2',
      });
    });

    // S10, constrained mode: the same `— None —` reaches the wire through `clearStorePreference`, and
    // `companyStoreId` is absent — sending both is a 400, so the form sends exactly one of the two.
    it('clears the store on — None — and posts clearStorePreference alone (S10)', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      chooseAssignedStore(fixture, '— None —');

      expect(component.form().getRawValue()).toMatchObject({ companyStoreId: null });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        clearStorePreference: true,
      });
      expect(profileServiceMock.updateProfile.mock.calls[0][0]).not.toHaveProperty(
        'companyStoreId',
      );
    });

    it('posts the clear after pick store → pick — None —, and never a restored tuple (W3/T16 supersedes D16)', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      chooseAssignedStore(fixture, 'Store Two');
      expect(component.form().getRawValue()).toMatchObject({ companyStoreId: 'store-2' });

      // An unsaved basic-information edit must survive the — None — pick untouched.
      component.form().patchValue({ firstName: 'Edited' });

      chooseAssignedStore(fixture, '— None —');

      // D16 said this sequence posts back the tuple the GET returned. T14 removed those four fields from
      // the request, so what the sequence posts is the store's own absence — and the basic-information
      // edit still travels.
      expect(component.form().getRawValue()).toMatchObject({
        firstName: 'Edited',
        companyStoreId: null,
      });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'Edited',
        lastName: 'Doe',
        email: 'john@example.com',
        clearStorePreference: true,
      });
      expect(profileServiceMock.updateProfile.mock.calls[0][0]).not.toHaveProperty(
        'companyStoreId',
      );
    });

    it('renders the empty assigned set as a disabled select explaining the state', () => {
      const { fixture, component } = setup({ edit: 'true' }, mockConstrainedEmptyProfile);

      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(1);

      const selectElement = fixture.nativeElement.querySelector('.mat-mdc-select') as HTMLElement;
      expect(selectElement.classList.contains('mat-mdc-select-disabled')).toBe(true);

      expect(assignedStoreOptionLabels(fixture)).toEqual([
        'Sin tiendas asignadas, así que no hay preferencia de ubicación para elegir.',
      ]);

      // Disabled, never invalid (T31): nothing blocks a caller with nothing to choose.
      expect(component.form().invalid).toBe(false);
    });

    it('omits the store when the empty constrained set left nothing to touch (W3/T16 supersedes D14)', () => {
      const { component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedEmptyProfile,
      );

      component.save();

      // D14 said this state posts `companyStoreId: null`. T16's touched rule omits the key instead: the
      // select is disabled, so the caller made no choice to send. The stored column is left alone, which
      // D10 already tolerates — a read never writes, and a preference that no longer resolves is already
      // reported as null by the read itself.
      const payload = profileServiceMock.updateProfile.mock.calls[0][0];
      expect(payload).not.toHaveProperty('companyStoreId');
    });

    it('describes the constrained mode with its own sentence (T34)', () => {
      const { fixture } = setup({ edit: 'true' }, mockConstrainedProfile);

      expect(textContent(fixture.nativeElement.querySelector('.section-description'))).toBe(
        'Your store preference is limited to the stores you are currently assigned to.',
      );
      expect(fixture.nativeElement.textContent).not.toContain(
        'Each selection filters the available options for the next level.',
      );
    });

    it('keeps the four-level cascade untouched in free mode (assignedStores: null)', () => {
      const {
        fixture,
        companyServiceMock,
        companyCountryServiceMock,
        companyRegionServiceMock,
        companyZoneServiceMock,
        companyStoreServiceMock,
      } = setup({ edit: 'true' }, mockProfileWithLocation, true);

      expect(fixture.nativeElement.querySelectorAll('mat-select').length).toBe(5);

      expect(companyServiceMock.getCompanies).toHaveBeenCalled();
      expect(companyCountryServiceMock.getCountries).toHaveBeenCalledWith('comp-1');
      expect(companyRegionServiceMock.getRegions).toHaveBeenCalledWith('comp-1', 'cc-1');
      expect(companyZoneServiceMock.getZones).toHaveBeenCalledWith('comp-1', 'cc-1', 'reg-1');
      expect(companyStoreServiceMock.getStores).toHaveBeenCalledWith(
        'comp-1',
        'cc-1',
        'reg-1',
        'zone-1',
      );
      // T20/G17: the walk is seeded from the chain the response carries, never from a store read.
      expect(companyStoreServiceMock.getStoreById).not.toHaveBeenCalled();

      const callOrder = [
        companyServiceMock.getCompanies.mock.invocationCallOrder[0],
        companyCountryServiceMock.getCountries.mock.invocationCallOrder[0],
        companyRegionServiceMock.getRegions.mock.invocationCallOrder[0],
        companyZoneServiceMock.getZones.mock.invocationCallOrder[0],
        companyStoreServiceMock.getStores.mock.invocationCallOrder[0],
      ];
      expect(callOrder).toEqual([...callOrder].sort((a, b) => a - b));
    });

    it('keeps the free-mode description byte-for-byte (assignedStores: null)', () => {
      const { fixture } = setup({ edit: 'true' }, mockProfileWithLocation, true);

      expect(textContent(fixture.nativeElement.querySelector('.section-description'))).toBe(
        'Select your location hierarchy. Each selection filters the available options for the next level.',
      );
      expect(fixture.nativeElement.textContent).not.toContain(
        'Your store preference is limited to the stores you are currently assigned to.',
      );
    });

    it('posts nothing location-shaped in free mode when the store was untouched (W3/T16)', () => {
      const { component, profileServiceMock } = setup(
        { edit: 'true' },
        mockProfileWithLocation,
        true,
      );

      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
      });
    });
  });

  // ─── Announcing the write to the shared resolution (S8/S9) ──

  describe('announcing the saved preference to the shared resolution (S8/S9)', () => {
    it('should invalidate the shared location preference after a successful save, before it navigates', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' }, mockProfileWithLocation);
      const invalidateSpy = vi.spyOn(TestBed.inject(LocationPreference), 'invalidate');
      const navigateSpy = vi.spyOn(TestBed.inject(Router), 'navigate');

      // «— None —» is the write this whole unit exists for. The shared resolution answered the
      // `'profile'` key before it, so the one writer has to announce it: otherwise every mounted
      // screen keeps the chain it resolved and goes on preselecting the store the caller just cleared.
      component.onStoreChange(null);
      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith({
        firstName: 'John',
        lastName: 'Doe',
        email: 'john@example.com',
        clearStorePreference: true,
      });
      expect(invalidateSpy).toHaveBeenCalledTimes(1);
      // Before the navigation, so no screen the navigation renders can read the chain this save
      // invalidated — the announcement cannot race the screen that acts on it.
      expect(invalidateSpy.mock.invocationCallOrder[0]).toBeLessThan(
        navigateSpy.mock.invocationCallOrder[0],
      );
    });

    it('should not invalidate the shared resolution when the save failed', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' });
      profileServiceMock.updateProfile = vi
        .fn()
        .mockReturnValue(throwError(() => new Error('Update failed')));
      const invalidateSpy = vi.spyOn(TestBed.inject(LocationPreference), 'invalidate');

      component.save();

      expect(component.error()).toBeTruthy();
      // The stored value did not change, so there is nothing to announce: a failed write must not cost
      // every mounted screen the settled chain it already holds.
      expect(invalidateSpy).not.toHaveBeenCalled();
    });
  });
});
