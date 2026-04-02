import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CompaniesCard } from './companies-card';
import { Company } from '../models/company.models';

describe('CompaniesCard', () => {
  let component: CompaniesCard;
  let fixture: ComponentFixture<CompaniesCard>;

  const mockCompany: Company = {
    id: '123',
    companyKey: 'C001',
    companyName: 'Test Company',
    tipoPersonaId: 1,
    razonSocial: 'Test Razon Social',
    rfc: 'RFC1234567890',
    email: 'test@company.com',
    phone: '5551234567',
    address: 'Test Address 123',
    enabled: true,
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01'
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompaniesCard]
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesCard);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display company information', () => {
    component.company = mockCompany;
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Test Company');
    expect(compiled.textContent).toContain('RFC1234567890');
    expect(compiled.textContent).toContain('test@company.com');
  });

  it('should emit editCompany with id', (done) => {
    component.company = mockCompany;
    component.editCompany.subscribe(id => {
      expect(id).toBe('123');
      done();
    });
    component.onEditCompany();
  });

  it('should emit deleteCompany with id and name', (done) => {
    component.company = mockCompany;
    component.deleteCompany.subscribe(data => {
      expect(data.id).toBe('123');
      expect(data.name).toBe('Test Company');
      done();
    });
    component.onDeleteCompany(new MouseEvent('click'));
  });

  it('should emit viewCompany with id', (done) => {
    component.company = mockCompany;
    component.viewCompany.subscribe(id => {
      expect(id).toBe('123');
      done();
    });
    component.onViewCompany(new MouseEvent('click'));
  });

  it('should stop event propagation on edit', () => {
    component.company = mockCompany;
    const event = new MouseEvent('click');
    const stopPropagationSpy = jest.spyOn(event, 'stopPropagation');
    
    component.onEditCompany(event);
    
    expect(stopPropagationSpy).toHaveBeenCalled();
  });

  it('should stop event propagation on delete', () => {
    component.company = mockCompany;
    const event = new MouseEvent('click');
    const stopPropagationSpy = jest.spyOn(event, 'stopPropagation');
    
    component.onDeleteCompany(event);
    
    expect(stopPropagationSpy).toHaveBeenCalled();
  });

  it('should handle undefined company gracefully', () => {
    component.company = undefined;
    fixture.detectChanges();

    let emitted = false;
    component.editCompany.subscribe(() => emitted = true);
    component.onEditCompany();
    
    expect(emitted).toBe(false);
  });
});
