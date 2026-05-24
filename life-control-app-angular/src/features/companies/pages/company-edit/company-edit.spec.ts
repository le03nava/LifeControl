import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CompanyEdit } from './company-edit';
import { Company, CompanyCountry } from '@features/companies/models/company.models';
import { CompanyService } from '@features/companies/data/company.service';
import { CompanyContextService } from '@shared/data/company-context.service';
import { CountryService } from '@features/countries/data';
import { CompanyCountryService } from '@features/companies/data/company-country.service';
import { CompanyRegionService } from '@features/companies/data/company-region.service';
import { ActivatedRoute, Router } from '@angular/router';
import { ReactiveFormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

describe('CompanyEdit', () => {
  let component: CompanyEdit;
  let fixture: ComponentFixture<CompanyEdit>;
  let companyServiceMock: Partial<Record<keyof CompanyService, unknown>>;
  let routerMock: Partial<Router>;

  function createApiError(overrides: Partial<Record<string, unknown>> = {}): HttpErrorResponse {
    return new HttpErrorResponse({
      error: {
        status: 400,
        message: 'Error de validación',
        errors: undefined,
        path: '/api/companies',
        timestamp: '2026-05-22T20:00:00Z',
        correlationId: 'abc-123',
        ...overrides,
      },
      status: 400,
      statusText: 'Bad Request',
    });
  }

  function createCompanyData(overrides: Partial<Company> = {}): Company {
    return {
      id: '',
      companyKey: '',
      companyName: 'Test',
      tipoPersonaId: 1,
      razonSocial: 'Test SA',
      rfc: 'RFC123456789',
      email: 'test@test.com',
      phone: '5551234567',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      ...overrides,
    };
  }

  beforeEach(async () => {
    companyServiceMock = {
      createCompany: vi.fn(),
      updateCompany: vi.fn(),
    };
    routerMock = {
      navigate: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [CompanyEdit, NoopAnimationsModule, ReactiveFormsModule],
      providers: [
        { provide: CompanyService, useValue: companyServiceMock },
        { provide: CompanyContextService, useValue: { currentCompany: vi.fn().mockReturnValue(null) } },
        {
          provide: CountryService,
          useValue: {
            getCountries: vi.fn().mockReturnValue(of([])),
            countries: vi.fn().mockReturnValue([]),
          },
        },
        {
          provide: CompanyCountryService,
          useValue: {
            getCountries: vi.fn().mockReturnValue(of([])),
            assignedCountries: vi.fn().mockReturnValue([]),
            loading: vi.fn().mockReturnValue(false),
            error: vi.fn().mockReturnValue(null),
          },
        },
        {
          provide: CompanyRegionService,
          useValue: {
            regions: vi.fn().mockReturnValue([]),
            loading: vi.fn().mockReturnValue(false),
            error: vi.fn().mockReturnValue(null),
            getRegions: vi.fn().mockReturnValue(of([])),
            addRegion: vi.fn().mockReturnValue(of({ id: 'r1', companyCountryId: 'cc1', companyId: 'c1', countryId: 'cnt1', regionCode: 'US-CA', regionName: 'California', enabled: true, createdAt: '', updatedAt: '' })),
            updateRegion: vi.fn().mockReturnValue(of({ id: 'r1', companyCountryId: 'cc1', companyId: 'c1', countryId: 'cnt1', regionCode: 'US-CA', regionName: 'California Updated', enabled: true, createdAt: '', updatedAt: '' })),
            removeRegion: vi.fn().mockReturnValue(of(undefined)),
            enableRegion: vi.fn().mockReturnValue(of({ id: 'r1', companyCountryId: 'cc1', companyId: 'c1', countryId: 'cnt1', regionCode: 'US-CA', regionName: 'California', enabled: true, createdAt: '', updatedAt: '' })),
            clearError: vi.fn(),
          },
        },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => null } },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CompanyEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('serverErrors handling', () => {
    it('should set serverErrors signal and clear generalError when apiError has field-level errors', () => {
      const httpError = createApiError({
        errors: { rfc: 'RFC inválido', email: 'Correo ya registrado' },
      });
      companyServiceMock.createCompany = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveCompany(createCompanyData());

      expect(component.serverErrors()).toEqual({
        rfc: 'RFC inválido',
        email: 'Correo ya registrado',
      });
      expect(component.generalError()).toBeNull();
    });

    it('should set generalError signal when apiError has no field-level errors but has message', () => {
      const httpError = createApiError({
        errors: undefined,
        message: 'Error interno del servidor',
      });
      companyServiceMock.createCompany = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveCompany(createCompanyData());

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Error interno del servidor');
    });

    it('should set fallback generalError when apiError has neither errors nor message', () => {
      const httpError = new HttpErrorResponse({
        error: { status: 500 },
        status: 500,
        statusText: 'Internal Server Error',
      });
      companyServiceMock.createCompany = vi.fn().mockReturnValue(throwError(() => httpError));

      component.onSaveCompany(createCompanyData());

      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });

    it('should handle field-level errors on updateCompany as well', () => {
      const httpError = createApiError({
        errors: { companyName: 'Nombre ya existe' },
      });
      companyServiceMock.updateCompany = vi.fn().mockReturnValue(throwError(() => httpError));

      const existingCompany = createCompanyData({ id: 'existing-id' });
      component.onSaveCompany(existingCompany);

      expect(component.serverErrors()).toEqual({
        companyName: 'Nombre ya existe',
      });
      expect(component.generalError()).toBeNull();
    });
  });

  describe('region integration', () => {
    const mockCountry: CompanyCountry = {
      id: 'cc1',
      companyId: 'c1',
      countryId: 'cnt1',
      countryCode: 'MX',
      countryName: 'México',
      localAlias: 'Sucursal CDMX',
      createdAt: '',
      updatedAt: '',
    };

    beforeEach(() => {
      component.isEditMode.set(true);
      component.companyId.set('c1');
    });

    it('should have region section hidden when no country is selected', () => {
      fixture.detectChanges();
      const compiled = fixture.nativeElement as HTMLElement;
      expect(component.selectedCountry()).toBeNull();
      expect(compiled.querySelector('.regions-section')).toBeNull();
    });

    it('should set selectedCountry and call getRegions on country selection', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      const getRegionsSpy = vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));

      component.onSelectCountry(mockCountry);

      expect(component.selectedCountry()).toEqual(mockCountry);
      expect(getRegionsSpy).toHaveBeenCalledWith(mockCountry.companyId, mockCountry.id, false);
    });

    it('should show region section after country is selected', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));

      component.onSelectCountry(mockCountry);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const section = compiled.querySelector('.regions-section');
      expect(section).not.toBeNull();
      expect(section!.textContent).toContain('Sucursal CDMX');
    });

    it('should call addRegion on onAddRegion', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));
      const addSpy = vi.spyOn(regionService, 'addRegion').mockReturnValue(of({} as any));

      component.onSelectCountry(mockCountry);
      component.onAddRegion({ regionCode: 'US-CA', regionName: 'California' });

      expect(addSpy).toHaveBeenCalledWith('c1', 'cc1', { regionCode: 'US-CA', regionName: 'California' });
    });

    it('should not call addRegion when no country is selected', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      const addSpy = vi.spyOn(regionService, 'addRegion');

      component.onAddRegion({ regionCode: 'US-CA', regionName: 'California' });

      expect(addSpy).not.toHaveBeenCalled();
    });

    it('should call updateRegion on onUpdateRegion', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));
      const updateSpy = vi.spyOn(regionService, 'updateRegion').mockReturnValue(of({} as any));

      component.onSelectCountry(mockCountry);
      component.onUpdateRegion({ id: 'r1', data: { regionCode: 'US-CA', regionName: 'California Updated' } });

      expect(updateSpy).toHaveBeenCalledWith('c1', 'cc1', 'r1', { regionCode: 'US-CA', regionName: 'California Updated' });
    });

    it('should call removeRegion on onRemoveRegion', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));
      const removeSpy = vi.spyOn(regionService, 'removeRegion').mockReturnValue(of(undefined));

      component.onSelectCountry(mockCountry);
      component.onRemoveRegion('r1');

      expect(removeSpy).toHaveBeenCalledWith('c1', 'cc1', 'r1');
    });

    it('should call enableRegion on onEnableRegion', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      vi.spyOn(regionService, 'getRegions').mockReturnValue(of([]));
      const enableSpy = vi.spyOn(regionService, 'enableRegion').mockReturnValue(of({} as any));

      component.onSelectCountry(mockCountry);
      component.onEnableRegion('r1');

      expect(enableSpy).toHaveBeenCalledWith('c1', 'cc1', 'r1');
    });

    it('should not call enableRegion when no country is selected', () => {
      const regionService = TestBed.inject(CompanyRegionService);
      const enableSpy = vi.spyOn(regionService, 'enableRegion');

      component.onEnableRegion('r1');

      expect(enableSpy).not.toHaveBeenCalled();
    });
  });
});
