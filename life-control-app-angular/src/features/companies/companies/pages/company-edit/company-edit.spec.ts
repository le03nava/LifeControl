import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CompanyEdit } from './company-edit';
import { Company } from '@features/companies/companies/models/company.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyContextService } from '@shared/data/company-context.service';
import { CompanyCountryService } from '@features/companies/countries/data';
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
          provide: CompanyCountryService,
          useValue: {
            getCountries: vi.fn().mockReturnValue(of([])),
            assignedCountries: vi.fn().mockReturnValue([]),
            loading: vi.fn().mockReturnValue(false),
            error: vi.fn().mockReturnValue(null),
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

});
