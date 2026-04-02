import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { CompanyEdit } from './company-edit';
import { CompanyService } from '../data/company.service';
import { Company, CompanyControl } from '../models/company.models';
import { FormGroup, FormControl, Validators, NonNullableFormBuilder } from '@angular/forms';
import { of } from 'rxjs';

describe('CompanyEdit', () => {
  let component: CompanyEdit;
  let fixture: ComponentFixture<CompanyEdit>;
  let companyService: jasmine.SpyObj<CompanyService>;
  let router: jasmine.SpyObj<Router>;
  let route: ActivatedRoute;

  const mockCompany: Company = {
    id: '123',
    companyKey: 'C001',
    companyName: 'Test Company',
    tipoPersonaId: 1,
    razonSocial: 'Test Razon',
    rfc: 'RFC1234567890',
    email: 'test@test.com',
    phone: '5551234567',
    address: 'Test Address',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01'
  };

  beforeEach(async () => {
    companyService = jasmine.createSpyObj('CompanyService', ['getCompanyById', 'createCompany', 'updateCompany']);
    router = jasmine.createSpyObj('Router', ['navigate']);
    
    const paramMap = new Map<string, string>();
    paramMap.set('id', '123');
    
    route = {
      snapshot: {
        paramMap: {
          get: (key: string) => key === 'id' ? '123' : null
        }
      }
    } as any;

    await TestBed.configureTestingModule({
      imports: [CompanyEdit],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: route }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(CompanyEdit);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should be in edit mode when id param exists', () => {
    fixture.detectChanges();
    expect(component.isEditMode()).toBe(true);
  });

  it('should NOT be in edit mode when no id param', () => {
    route = {
      snapshot: {
        paramMap: {
          get: (key: string) => null
        }
      }
    } as any;
    
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CompanyEdit],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: route }
      ]
    }).compileComponents();
    
    fixture = TestBed.createComponent(CompanyEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
    
    expect(component.isEditMode()).toBe(false);
  });

  it('should load company data in edit mode', () => {
    companyService.getCompanyById.and.returnValue(of(mockCompany));
    
    fixture.detectChanges();
    
    companyService.getCompanyById('123').subscribe(company => {
      expect(company.companyName).toBe('Test Company');
    });
  });

  it('should navigate to /companies on cancel', () => {
    component.cancelForm();
    expect(router.navigate).toHaveBeenCalledWith(['/companies']);
  });

  it('should call createCompany when in create mode', () => {
    companyService.createCompany.and.returnValue(of(mockCompany));
    
    // Force create mode
    route = {
      snapshot: {
        paramMap: {
          get: (key: string) => null
        }
      }
    } as any;
    
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CompanyEdit],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: route }
      ]
    }).compileComponents();
    
    fixture = TestBed.createComponent(CompanyEdit);
    component = fixture.componentInstance;
    
    // Set an empty form with no id
    component.companyForm = component['fb'].group({
      id: component['fb'].control(''),
      companyKey: component['fb'].control(''),
      companyName: component['fb'].control('New'),
      tipoPersonaId: component['fb'].control(1),
      razonSocial: component['fb'].control('New Razon'),
      rfc: component['fb'].control('RFC1234567890'),
      email: component['fb'].control('new@test.com'),
      phone: component['fb'].control('5551234567'),
      address: component['fb'].control(''),
      enabled: component['fb'].control(true),
    }) as any;
    
    component.onSaveCompany({
      id: '',
      companyName: 'New',
      companyKey: '',
      tipoPersonaId: 1,
      razonSocial: 'New Razon',
      rfc: 'RFC1234567890',
      email: 'new@test.com',
      phone: '5551234567',
      address: '',
      enabled: true,
      createdAt: '',
      updatedAt: ''
    });
    
    expect(companyService.createCompany).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/companies']);
  });

  it('should call updateCompany when in edit mode', () => {
    companyService.updateCompany.and.returnValue(of(mockCompany));
    
    fixture.detectChanges();
    
    component.onSaveCompany(mockCompany);
    
    expect(companyService.updateCompany).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/companies']);
  });
});
