import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { CompanyList } from './company-list';
import { CompanyService } from '../data/company.service';
import { Company } from '../models/company.models';
import { signal } from '@angular/core';

describe('CompanyList', () => {
  let component: CompanyList;
  let fixture: ComponentFixture<CompanyList>;
  let companyService: jasmine.SpyObj<CompanyService>;
  let router: jasmine.SpyObj<Router>;

  const mockCompanies: Company[] = [
    { id: '1', companyKey: 'C001', companyName: 'Company A', tipoPersonaId: 1, razonSocial: 'Razon A', rfc: 'RFC1234567890', email: 'a@test.com', phone: '5551112222', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', companyKey: 'C002', companyName: 'Company B', tipoPersonaId: 1, razonSocial: 'Razon B', rfc: 'RFC0987654321', email: 'b@test.com', phone: '5553334444', enabled: true, createdAt: '', updatedAt: '' }
  ];

  beforeEach(async () => {
    companyService = jasmine.createSpyObj('CompanyService', ['getCompanies', 'deleteCompany', 'companies']);
    companyService.companies = signal(mockCompanies) as any;
    router = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [CompanyList],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(CompanyList);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call getCompanies on init', () => {
    fixture.detectChanges();
    expect(companyService.getCompanies).toHaveBeenCalled();
  });

  it('should navigate to edit page', () => {
    component.editCompany('123');
    expect(router.navigate).toHaveBeenCalledWith(['/companies/edit/123']);
  });

  it('should open delete modal', () => {
    component.confirmDelete({ id: '1', name: 'Company A' });
    expect(component.showDeleteModal()).toBe(true);
    expect(component.companyToDelete()?.id).toBe('1');
  });

  it('should close delete modal', () => {
    component.confirmDelete({ id: '1', name: 'Company A' });
    component.cancelDelete();
    expect(component.showDeleteModal()).toBe(false);
    expect(component.companyToDelete()).toBeNull();
  });

  it('should delete company and refresh list', () => {
    companyService.deleteCompany.and.returnValue({
      subscribe: (cb: any) => {
        cb({ next: () => {} });
        return { unsubscribe: () => {} };
      }
    } as any);

    component.confirmDelete({ id: '1', name: 'Company A' });
    component.executeDelete();

    expect(companyService.deleteCompany).toHaveBeenCalledWith('1');
    expect(companyService.getCompanies).toHaveBeenCalled();
  });

  it('should display companies from service', () => {
    fixture.detectChanges();
    expect(component.companies().length).toBe(2);
  });
});
