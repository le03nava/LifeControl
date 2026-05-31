import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ZonesEdit } from './zones-edit';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../data/company-zone.service';
import { CompanyZone, CompanyZoneRequest, ZoneSaveEvent } from '../../models/zone.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { Company, Page } from '../../../companies/models/company.models';

describe('ZonesEdit', () => {
  let component: ZonesEdit;
  let fixture: ComponentFixture<ZonesEdit>;

  let routeMock: {
    snapshot: {
      paramMap: { get: ReturnType<typeof vi.fn> };
      queryParamMap: { get: ReturnType<typeof vi.fn> };
    };
  };
  let routerMock: {
    navigate: ReturnType<typeof vi.fn>;
  };
  let companyZoneServiceMock: {
    addZone: ReturnType<typeof vi.fn>;
    updateZone: ReturnType<typeof vi.fn>;
  };
  let companyRegionServiceMock: {
    getRegions: ReturnType<typeof vi.fn>;
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
  ];

  const mockZone: CompanyZone = {
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
  };

  beforeEach(async () => {
    // Reset history.state para cada test
    window.history.replaceState(null, '');

    routeMock = {
      snapshot: {
        paramMap: {
          get: vi.fn(),
        },
        queryParamMap: {
          get: vi.fn().mockReturnValue(null),
        },
      },
    };
    routerMock = {
      navigate: vi.fn(),
    };
    companyZoneServiceMock = {
      addZone: vi.fn().mockReturnValue(of(mockZone)),
      updateZone: vi.fn().mockReturnValue(of(mockZone)),
    };
    companyRegionServiceMock = {
      getRegions: vi.fn().mockReturnValue(of(mockRegions)),
    };

    await TestBed.configureTestingModule({
      imports: [ZonesEdit, NoopAnimationsModule],
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
        { provide: CompanyZoneService, useValue: companyZoneServiceMock },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: Router, useValue: routerMock },
      ],
    }).compileComponents();
  });

  /** Helper: create component with current mocks and detect changes. */
  function createComponent(): { component: ZonesEdit; fixture: ComponentFixture<ZonesEdit> } {
    const f = TestBed.createComponent(ZonesEdit);
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
    expect(component.zoneId()).toBeNull();
  });

  it('should not navigate away in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    createComponent();

    expect(routerMock.navigate).not.toHaveBeenCalled();
  });

  // ─── Edit mode ─────────────────────────────────────────

  it('should detect edit mode from route param', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState({ zone: mockZone }, '');
    const { component } = createComponent();

    expect(component.isEditMode()).toBe(true);
    expect(component.zoneId()).toBe('zone-1');
  });

  it('should restore zone from router state in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState({ zone: mockZone }, '');
    const { component } = createComponent();

    expect(component.zoneToEdit()).toEqual(mockZone);
  });

  it('should redirect to zones list when edit mode has no history state', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState(null, '');
    createComponent();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones']);
  });

  // ─── Save: addZone (create mode) ─────────────────────

  it('should call addZone when saving in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'NORTE', zoneName: 'Zona Norte' },
    };
    component.onSaveZone(event);

    expect(companyZoneServiceMock.addZone).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      event.request,
    );
    expect(companyZoneServiceMock.updateZone).not.toHaveBeenCalled();
  });

  it('should navigate to zones list after successful create', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'NORTE', zoneName: 'Zona Norte' },
    };
    component.onSaveZone(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1' },
    });
  });

  // ─── Save: updateZone (edit mode) ────────────────────

  it('should call updateZone when saving in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState({ zone: mockZone }, '');
    const { component } = createComponent();

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'NORTE', zoneName: 'Zona Norte' },
    };
    component.onSaveZone(event);

    expect(companyZoneServiceMock.updateZone).toHaveBeenCalledWith(
      'company-1',
      'cc-1',
      'reg-1',
      'zone-1',
      event.request,
    );
    expect(companyZoneServiceMock.addZone).not.toHaveBeenCalled();
  });

  it('should navigate to zones list after successful update', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState({ zone: mockZone }, '');
    const { component } = createComponent();

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'NORTE', zoneName: 'Zona Norte' },
    };
    component.onSaveZone(event);

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1' },
    });
  });

  // ─── Cancel navigation ─────────────────────────────────

  it('should navigate to zones list on cancel in create mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    component.onCancelForm();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones']);
  });

  it('should preserve query params on cancel in create mode when companyId is set', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    routeMock.snapshot.queryParamMap.get.mockImplementation((key: string) => {
      if (key === 'companyId') return 'company-1';
      if (key === 'countryId') return 'cc-1';
      if (key === 'regionId') return 'reg-1';
      return null;
    });
    const { component } = createComponent();

    component.onCancelForm();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1' },
    });
  });

  it('should preserve context on cancel in edit mode', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue('zone-1');
    window.history.replaceState({ zone: mockZone }, '');
    const { component } = createComponent();

    component.onCancelForm();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/companies/zones'], {
      queryParams: { companyId: 'company-1', countryId: 'cc-1', regionId: 'reg-1' },
    });
  });

  // ─── Server errors ─────────────────────────────────────

  it('should set serverErrors when save fails with field-level errors', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const httpError = new HttpErrorResponse({
      error: {
        status: 400,
        message: 'Error de validación',
        errors: { zoneCode: 'Código ya existe' },
        path: '/api/companies/company-1/countries/cc-1/regions/reg-1/zones',
        timestamp: '2026-05-27T20:00:00Z',
        correlationId: 'abc-123',
      },
      status: 400,
      statusText: 'Bad Request',
    });
    companyZoneServiceMock.addZone.mockReturnValue(throwError(() => httpError));

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'CDMX-DT', zoneName: 'Downtown' },
    };
    component.onSaveZone(event);

    expect(component.serverErrors()).toEqual({ zoneCode: 'Código ya existe' });
  });

  it('should set empty serverErrors when API error has no field-level errors', () => {
    routeMock.snapshot.paramMap.get.mockReturnValue(null);
    const { component } = createComponent();

    const httpError = new HttpErrorResponse({
      error: {
        status: 500,
        message: 'Error interno',
        errors: undefined,
        path: '/api/companies/company-1/countries/cc-1/regions/reg-1/zones',
        timestamp: '2026-05-27T20:00:00Z',
        correlationId: 'abc-123',
      },
      status: 500,
      statusText: 'Internal Server Error',
    });
    companyZoneServiceMock.addZone.mockReturnValue(throwError(() => httpError));

    const event: ZoneSaveEvent = {
      companyId: 'company-1',
      countryId: 'cc-1',
      regionId: 'reg-1',
      request: { zoneCode: 'CDMX-DT', zoneName: 'Downtown' },
    };
    component.onSaveZone(event);

    expect(component.serverErrors()).toEqual({});
  });
});
