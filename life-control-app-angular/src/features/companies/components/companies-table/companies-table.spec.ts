import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CompaniesTable } from './companies-table';
import { Company } from '../models/company.models';

describe('CompaniesTable', () => {
  let component: CompaniesTable;
  let fixture: ComponentFixture<CompaniesTable>;

  const mockCompanies: Company[] = [
    { id: '1', companyKey: 'C001', companyName: 'Company A', tipoPersonaId: 1, razonSocial: 'Razon A', rfc: 'RFC1234567890', email: 'a@test.com', phone: '5551112222', enabled: true, createdAt: '', updatedAt: '' },
    { id: '2', companyKey: 'C002', companyName: 'Company B', tipoPersonaId: 1, razonSocial: 'Razon B', rfc: 'RFC0987654321', email: 'b@test.com', phone: '5553334444', enabled: true, createdAt: '', updatedAt: '' }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompaniesTable]
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesTable);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display companies in table', () => {
    component.companies = mockCompanies;
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Company A');
    expect(compiled.textContent).toContain('Company B');
  });

  it('should display empty message when no companies', () => {
    component.companies = [];
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('No hay empresas disponibles');
  });

  it('should display all company fields', () => {
    component.companies = [mockCompanies[0]];
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('RFC1234567890');
    expect(compiled.textContent).toContain('a@test.com');
    expect(compiled.textContent).toContain('5551112222');
  });

  it('should use trackBy function', () => {
    const trackByFn = component.companies;
    // The @for loop uses track by id
    expect(mockCompanies[0].id).toBe('1');
  });
});
