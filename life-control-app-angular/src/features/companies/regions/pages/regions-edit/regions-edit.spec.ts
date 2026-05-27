import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RegionsEdit } from './regions-edit';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../data/company-region.service';
import { CompanyRegion, CompanyRegionRequest, RegionSaveEvent } from '../../models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('RegionsEdit', () => {
  let component: RegionsEdit;
  let fixture: ComponentFixture<RegionsEdit>;

  let routeMock: {
    snapshot: { paramMap: { get: ReturnType<typeof vi.fn> } };
  };
  let routerMock: {
    navigate: ReturnType<typeof vi.fn>;
    getCurrentNavigation: ReturnType<typeof vi.fn>;
  };
  let companyRegionServiceMock: {
    addRegion: ReturnType<typeof vi.fn>;
    updateRegion: ReturnType<typeof vi.fn>;
  };

  const mockCompanies: Company[] = [
    {
      id: 'company-1',
      companyKey: 'COMP001',
      companyName: 'Test Company',
      tipoPersonaId: 1,
      razonSocial: 'Test Company SA',
      rfc: 'RFC123456789',
      email: 'test@test.com',
      phone: '+521234567890',
      enabled: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockCompaniesPage: Page<Company> = {
    content: mockCompanies,
    totalElements: 1,
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
      localAlias: null,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    },
  ];

  const mockRegion: CompanyRegion = {
    id: 'r-1',
    companyCountryId: 'cc-1',
    companyId: 'company-1',
    countryId: 'c1',
    regionCode: 'CENTRO',
    regionName: 'Zona Centro',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
  };

  beforeEach(async () => {
    routeMock = {
      snapshot: {
        paramMap: {
          get: vi.fn(),
        },
      },
    };
    routerMock = {
      navigate: vi.fn(),
      getCurrentNavigation: vi.fn(),
    };
    companyRegionServiceMock = {
      addRegion: vi.fn().mockReturnValue(of(mockRegion)),
      updateRegion: vi.fn().mockReturnValue(of(mockRegion)),
    };

    await TestBed.configureTestingModule({
      imports: [RegionsEdit, NoopAnimationsModule],
      providers: [
        {
          provide: CompanyService,
          useValue: {
            getCompanies: vi.fn().mockReturnValue(of(mockCompaniesPage)),
          },
        },
        {
          provide: CompanyCountryService,
          useValue: {
            assignedCountries: signal(mockCountries).asReadonly(),
            getCountries: vi.fn().mockReturnValue(of(mockCountries)),
          },
        },
        { provide: CompanyRegionService, useValue: companyRegionServiceMock },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: Router, useValue: routerMock },
      ],
    }).compileComponents();
  });

  /** Helper: create component with current mocks and detect changes. */
  function createComponent(): { component: RegionsEdit; fixture: ComponentFixture<RegionsEdit> } {
    const f = TestBed.createComponent(RegionsEdit);
    const c = f.componentInstance;
    f.detectChanges();
    return { component: c, fixture: f };
  }

  // ─── Creation ──────────────────────────────────────────

  it('should create', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();
    expect(component).toBeTruthy();
  });

  // ─── Create mode ───────────────────────────────────────

  it('should be in create mode when no id param is present', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    expect(component.isEditMode()).toBe(false);
    expect(component.regionId()).toBeNull();
  });

  it('should not navigate away in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    createComponent();

    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  // ─── Edit mode ─────────────────────────────────────────

  it('should detect edit mode from route param', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('r-1');
    routerMock.getCurrentNavigation.mockReturnValue({
      extras: { state: { region: mockRegion } },
    });
    const { component } = createComponent();

    expect(component.isEditMode()).toBe(true);
    expect(component.regionId()).toBe('r-1');
  });

  it('should restore region from router state in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('r-1');
    routerMock.getCurrentNavigation.mockReturnValue({
      extras: { state: { region: mockRegion } },
    });
    const { component } = createComponent();

    expect(component.regionToEdit()).toEqual(mockRegion);
  });

  it('should redirect to regions list when edit mode has no router state', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('r-1');
    routerMock.getCurrentNavigation.mockReturnValue(null);
    createComponent();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/regions']);
  });

  // ─── Save: addRegion (create mode) ─────────────────────

  it('should call addRegion when saving in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'NORTE', regionName: 'Zona Norte' },
    };
    component.onSaveRegion(event);

    expect(companyRegionServiceMock.addRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      event.request,
    );
    expect(companyRegionServiceMock.updateRegion).not.toHaveBeenCalled();
  });

  it('should navigate to regions list after successful create', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'NORTE', regionName: 'Zona Norte' },
    };
    component.onSaveRegion(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/regions']);
  });

  // ─── Save: updateRegion (edit mode) ────────────────────

  it('should call updateRegion when saving in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('r-1');
    routerMock.getCurrentNavigation.mockReturnValue({
      extras: { state: { region: mockRegion } },
    });
    const { component } = createComponent();

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'NORTE', regionName: 'Zona Norte' },
    };
    component.onSaveRegion(event);

    expect(companyRegionServiceMock.updateRegion).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'r-1',
      event.request,
    );
    expect(companyRegionServiceMock.addRegion).not.toHaveBeenCalled();
  });

  it('should navigate to regions list after successful update', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('r-1');
    routerMock.getCurrentNavigation.mockReturnValue({
      extras: { state: { region: mockRegion } },
    });
    const { component } = createComponent();

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'NORTE', regionName: 'Zona Norte' },
    };
    component.onSaveRegion(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/regions']);
  });

  // ─── Cancel navigation ─────────────────────────────────

  it('should navigate to regions list on cancel', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    component.onCancelForm();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/regions']);
  });

  // ─── Server errors ─────────────────────────────────────

  it('should set serverErrors when save fails with field-level errors', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const httpError = new HttpErrorResponse({
      error: {
        status: 400,
        message: 'Error de validación',
        errors: { regionCode: 'Código ya existe' },
        path: '/api/companies/company-1/countries/cc-1/regions',
        timestamp: '2026-05-27T20:00:00Z',
        correlationId: 'abc-123',
      },
      status: 400,
      statusText: 'Bad Request',
    });
    companyRegionServiceMock.addRegion.mockReturnValue(throwError(() => httpError));

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'CENTRO', regionName: 'Zona Centro' },
    };
    component.onSaveRegion(event);

    expect(component.serverErrors()).toEqual({ regionCode: 'Código ya existe' });
  });

  it('should set empty serverErrors when API error has no field-level errors', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const httpError = new HttpErrorResponse({
      error: {
        status: 500,
        message: 'Error interno',
        errors: undefined,
        path: '/api/companies/company-1/countries/cc-1/regions',
        timestamp: '2026-05-27T20:00:00Z',
        correlationId: 'abc-123',
      },
      status: 500,
      statusText: 'Internal Server Error',
    });
    companyRegionServiceMock.addRegion.mockReturnValue(throwError(() => httpError));

    const event: RegionSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      request: { regionCode: 'CENTRO', regionName: 'Zona Centro' },
    };
    component.onSaveRegion(event);

    expect(component.serverErrors()).toEqual({});
  });
});
