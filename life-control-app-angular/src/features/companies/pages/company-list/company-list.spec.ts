import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CompanyList } from './company-list';
import { CompanyService } from '@features/companies/data/company.service';
import { Company, Page } from '@features/companies/models/company.models';
import { of } from 'rxjs';

describe('CompanyList', () => {
  let component: CompanyList;
  let fixture: ComponentFixture<CompanyList>;
  let companyService: jasmine.SpyObj<CompanyService>;
  let router: jasmine.SpyObj<Router>;

  const mockCompanies: Company[] = [
    { id: '1', companyId: 1, companyName: 'Alpha Corp', tipoPersonaId: 1, razonSocial: 'Razon Alpha', rfc: 'RFC0000000001', email: 'alpha@test.com', phone: '5551112222', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', companyId: 2, companyName: 'Beta Inc', tipoPersonaId: 1, razonSocial: 'Razon Beta', rfc: 'RFC0000000002', email: 'beta@test.com', phone: '5553334444', enabled: true, createdAt: '', updatedAt: '' },
    { id: '3', companyId: 3, companyName: 'Gamma SA', tipoPersonaId: 1, razonSocial: 'Razon Gamma', rfc: 'RFC0000000003', email: 'gamma@test.com', phone: '5555556666', enabled: false, createdAt: '', updatedAt: '' },
  ];

  const createMockPage = (companies: Company[], page: number = 0, size: number = 12): Page<Company> => ({
    content: companies,
    totalElements: companies.length,
    totalPages: Math.ceil(companies.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= companies.length,
    empty: companies.length === 0,
  });

  beforeEach(async () => {
    companyService = jasmine.createSpyObj('CompanyService', ['getCompanies', 'deleteCompany']);
    companyService.getCompanies.and.returnValue(of(createMockPage(mockCompanies)));
    companyService.deleteCompany.and.returnValue(of(void 0));

    router = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [CompanyList, MatIconModule, MatPaginatorModule, NoopAnimationsModule],
      providers: [
        { provide: CompanyService, useValue: companyService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CompanyList);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load companies on creation via rxResource', () => {
    fixture.detectChanges();
    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, undefined);
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

  it('should delete company', () => {
    component.confirmDelete({ id: '1', name: 'Alpha Corp' });
    component.executeDelete();
    expect(companyService.deleteCompany).toHaveBeenCalledWith('1');
  });

  it('should clear search query', () => {
    component.searchQuery.set('alpha');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should update page index and size on page change', () => {
    component.onPageChange({ pageIndex: 2, pageSize: 24 });
    expect(component.pageIndex()).toBe(2);
    expect(component.pageSize()).toBe(24);
  });

  it('should reset page to 0 when search changes (debounced)', fakeAsync(() => {
    // Start on page 2
    component.pageIndex.set(2);
    expect(component.pageIndex()).toBe(2);

    // Type a search query
    component.searchQuery.set('Alpha');

    // Fast-forward past debounce (300ms)
    tick(300);

    // Page should reset to 0
    expect(component.pageIndex()).toBe(0);

    // API should be called with search param
    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, 'Alpha');
  }));

  it('should load companies with correct params', fakeAsync(() => {
    fixture.detectChanges();
    tick();

    expect(companyService.getCompanies).toHaveBeenCalledWith(0, 12, undefined);

    // Change page
    component.onPageChange({ pageIndex: 1, pageSize: 12 });
    tick();

    expect(companyService.getCompanies).toHaveBeenCalledWith(1, 12, undefined);
  }));
});
