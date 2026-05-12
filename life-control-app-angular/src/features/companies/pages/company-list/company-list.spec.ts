import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { CompanyList } from './company-list';
import { CompanyService } from '@features/companies/data/company.service';
import { Company } from '@features/companies/models/company.models';
import { signal, Signal } from '@angular/core';

// Mock para el servicio
class MockCompanyService {
  private _companies = signal<Company[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  readonly companies: Signal<Company[]> = this._companies.asReadonly();
  readonly loading: Signal<boolean> = this._loading.asReadonly();
  readonly error: Signal<string | null> = this._error.asReadonly();

  getCompanies = jasmine.createSpy('getCompanies').and.callFake(() => {
    this._loading.set(true);
    setTimeout(() => this._loading.set(false), 0);
  });

  deleteCompany = jasmine.createSpy('deleteCompany').and.returnValue({
    subscribe: (cb: any) => {
      cb({});
      return { unsubscribe: () => {} };
    }
  });

  // Helper para setear datos de test
  setMockCompanies(companies: Company[]) {
    this._companies.set(companies);
  }
}

describe('CompanyList', () => {
  let component: CompanyList;
  let fixture: ComponentFixture<CompanyList>;
  let companyService: MockCompanyService;
  let router: jasmine.SpyObj<Router>;

  const mockCompanies: Company[] = [
    { id: '1', companyId: 1, companyName: 'Alpha Corp', tipoPersonaId: 1, razonSocial: 'Razon Alpha', rfc: 'RFC0000000001', email: 'alpha@test.com', phone: '5551112222', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', companyId: 2, companyName: 'Beta Inc', tipoPersonaId: 1, razonSocial: 'Razon Beta', rfc: 'RFC0000000002', email: 'beta@test.com', phone: '5553334444', enabled: true, createdAt: '', updatedAt: '' },
    { id: '3', companyId: 3, companyName: 'Gamma SA', tipoPersonaId: 1, razonSocial: 'Razon Gamma', rfc: 'RFC0000000003', email: 'gamma@test.com', phone: '5555556666', enabled: false, createdAt: '', updatedAt: '' }
  ];

  beforeEach(async () => {
    companyService = new MockCompanyService();
    router = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [CompanyList, MatIconModule],
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
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    expect(component.showDeleteModal()).toBe(true);
    expect(component.companyToDelete()?.id).toBe('1');
  });

  it('should close delete modal', () => {
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    component.cancelDelete();
    expect(component.showDeleteModal()).toBe(false);
    expect(component.companyToDelete()).toBeNull();
  });

  it('should delete company and refresh list', () => {
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    component.executeDelete();

    expect(companyService.deleteCompany).toHaveBeenCalledWith('1');
    expect(companyService.getCompanies).toHaveBeenCalled();
  });

  it('should clear search query', () => {
    component.searchQuery.set('alpha');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  describe('filteredCompanies computed', () => {
    beforeEach(() => {
      companyService.setMockCompanies(mockCompanies);
      fixture.detectChanges();
    });

    it('should return all companies when search is empty', () => {
      expect(component.filteredCompanies().length).toBe(3);
    });

    it('should filter by companyName (case insensitive)', () => {
      component.searchQuery.set('alpha');
      expect(component.filteredCompanies().length).toBe(1);
      expect(component.filteredCompanies()[0].companyName).toBe('Alpha Corp');
    });

    it('should filter by RFC', () => {
      component.searchQuery.set('RFC0000000002');
      expect(component.filteredCompanies().length).toBe(1);
      expect(component.filteredCompanies()[0].companyName).toBe('Beta Inc');
    });

    it('should filter by email', () => {
      component.searchQuery.set('gamma@test.com');
      expect(component.filteredCompanies().length).toBe(1);
      expect(component.filteredCompanies()[0].companyName).toBe('Gamma SA');
    });

    it('should filter by razonSocial partial match', () => {
      component.searchQuery.set('Razon');
      expect(component.filteredCompanies().length).toBe(3);
    });

    it('should return empty when no matches', () => {
      component.searchQuery.set('xyz-nonexistent');
      expect(component.filteredCompanies().length).toBe(0);
    });
  });
});
