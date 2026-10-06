import { TestBed } from '@angular/core/testing';
import type { ComponentFixture } from '@angular/core/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideLocationMocks } from '@angular/common/testing';
import { By } from '@angular/platform-browser';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import { MatSelect } from '@angular/material/select';

import { UserProfileComponent } from './user-profile.component';
import { ProfileService } from './data/profile.service';
import { ProfileResponse, AssignedStore } from './data/profile.models';
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
    companyCountryId: null,
    companyId: null,
    companyRegionId: null,
    companyZoneId: null,
    companyStoreId: null,
    assignedStores: null,
  };

  const mockProfileWithLocation: ProfileResponse = {
    ...mockProfile,
    companyId: 'comp-1',
    companyCountryId: 'cc-1',
    companyRegionId: 'reg-1',
    companyZoneId: 'zone-1',
    companyStoreId: 'store-1',
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
    companyId: 'comp-1',
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
    };

    TestBed.configureTestingModule({
      imports: [UserProfileComponent],
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        provideHttpClient(),
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

    it('should pre-populate form with location fields', () => {
      const { component } = setup({ edit: 'true' }, mockProfileWithLocation, true);
      const formValues = component.form().getRawValue();
      expect(formValues.companyId).toBe('comp-1');
      expect(formValues.companyCountryId).toBe('cc-1');
      expect(formValues.companyRegionId).toBe('reg-1');
    });
  });

  // ─── Save / Cancel ─────────────────────────────────────────

  describe('save', () => {
    it('should call updateProfile on save', () => {
      const { component, profileServiceMock } = setup({ edit: 'true' });
      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalled();
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
          provideRouter([]),
          provideLocationMocks(),
          provideHttpClient(),
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

    it('posts the chosen store together with its own ancestor chain', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      chooseAssignedStore(fixture, 'Store Two');

      expect(component.form().getRawValue()).toMatchObject({
        companyId: 'comp-1',
        companyCountryId: 'cc-2',
        companyRegionId: 'reg-2',
        companyZoneId: 'zone-2',
        companyStoreId: 'store-2',
      });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({
          companyId: 'comp-1',
          companyCountryId: 'cc-2',
          companyRegionId: 'reg-2',
          companyZoneId: 'zone-2',
          companyStoreId: 'store-2',
        }),
      );
    });

    // Fresh-load path: the form already holds the profile's tuple, so D16's "restore" and the old
    // "leave them alone" reading coincide here. The pick-store→pick-None sequence below is the case
    // that distinguishes them.
    it('clears only the store on — None — from a fresh load (both readings coincide)', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      chooseAssignedStore(fixture, '— None —');

      expect(component.form().getRawValue()).toMatchObject({
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: null,
      });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({
          companyId: 'comp-1',
          companyCountryId: 'cc-1',
          companyRegionId: 'reg-1',
          companyZoneId: 'zone-1',
          companyStoreId: null,
        }),
      );
    });

    it('restores the tuple the GET returned after pick store → pick — None — (D16/T30)', () => {
      const { fixture, component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedProfile,
      );

      // Store Two carries a different chain (cc-2/reg-2/zone-2) than the profile's own tuple
      // (cc-1/reg-1/zone-1), so the restore is observable.
      chooseAssignedStore(fixture, 'Store Two');
      expect(component.form().getRawValue()).toMatchObject({
        companyCountryId: 'cc-2',
        companyRegionId: 'reg-2',
        companyZoneId: 'zone-2',
        companyStoreId: 'store-2',
      });

      // An unsaved basic-information edit must survive the — None — pick untouched.
      component.form().patchValue({ firstName: 'Edited' });

      chooseAssignedStore(fixture, '— None —');

      expect(component.form().getRawValue()).toMatchObject({
        firstName: 'Edited',
        companyId: 'comp-1',
        companyCountryId: 'cc-1',
        companyRegionId: 'reg-1',
        companyZoneId: 'zone-1',
        companyStoreId: null,
      });

      component.save();
      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({
          firstName: 'Edited',
          companyId: 'comp-1',
          companyCountryId: 'cc-1',
          companyRegionId: 'reg-1',
          companyZoneId: 'zone-1',
          companyStoreId: null,
        }),
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

    it('saves companyStoreId: null from the empty constrained state', () => {
      const { component, profileServiceMock } = setup(
        { edit: 'true' },
        mockConstrainedEmptyProfile,
      );

      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({ companyStoreId: null }),
      );
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

    it('posts the unchanged location tuple in free mode', () => {
      const { component, profileServiceMock } = setup(
        { edit: 'true' },
        mockProfileWithLocation,
        true,
      );

      component.save();

      expect(profileServiceMock.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({
          companyId: 'comp-1',
          companyCountryId: 'cc-1',
          companyRegionId: 'reg-1',
          companyZoneId: 'zone-1',
          companyStoreId: 'store-1',
        }),
      );
    });
  });
});
