import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconModule } from '@angular/material/icon';
import { CompaniesCard } from './companies-card';
import { Company } from '../models/company.models';

describe('CompaniesCard', () => {
  let component: CompaniesCard;
  let fixture: ComponentFixture<CompaniesCard>;

  const mockCompany: Company = {
    id: '123',
    companyId: 1,
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
      imports: [CompaniesCard, MatIconModule]
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesCard);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display company information', () => {
    component.company.set(mockCompany);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Test Company');
    expect(compiled.textContent).toContain('RFC1234567890');
    expect(compiled.textContent).toContain('test@company.com');
  });

  it('should compute isActive correctly', () => {
    component.company.set(mockCompany);
    expect(component.isActive()).toBe(true);
    expect(component.statusLabel()).toBe('Activo');
  });

  it('should compute isActive false for disabled company', () => {
    component.company.set({ ...mockCompany, enabled: false });
    expect(component.isActive()).toBe(false);
    expect(component.statusLabel()).toBe('Inactivo');
  });

  it('should emit editCompany with id', (done) => {
    component.company.set(mockCompany);
    component.editCompany.subscribe(id => {
      expect(id).toBe('123');
      done();
    });
    component.onEditCompany();
  });

  it('should emit deleteCompany with id and name', (done) => {
    component.company.set(mockCompany);
    component.deleteCompany.subscribe(data => {
      expect(data.id).toBe('123');
      expect(data.name).toBe('Test Company');
      done();
    });
    component.onDeleteCompany(new MouseEvent('click'));
  });

  it('should stop event propagation on edit', () => {
    component.company.set(mockCompany);
    const event = new MouseEvent('click');
    const stopPropagationSpy = spyOn(event, 'stopPropagation');
    
    component.onEditCompany(event);
    
    expect(stopPropagationSpy).toHaveBeenCalled();
  });

  it('should stop event propagation on delete', () => {
    component.company.set(mockCompany);
    const event = new MouseEvent('click');
    const stopPropagationSpy = spyOn(event, 'stopPropagation');
    
    component.onDeleteCompany(event);
    
    expect(stopPropagationSpy).toHaveBeenCalled();
  });

  it('should handle undefined company gracefully', () => {
    component.company.set(undefined);
    fixture.detectChanges();

    let emitted = false;
    component.editCompany.subscribe(() => emitted = true);
    component.onEditCompany();
    
    expect(emitted).toBe(false);
  });

  describe('responsive icon-only buttons', () => {
    it('should render btn-label with button text', () => {
      component.company.set(mockCompany);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const editBtn = compiled.querySelector('.action-btn')!;
      const label = editBtn.querySelector('.btn-label');
      
      expect(label).toBeTruthy();
      expect(label?.textContent).toContain('Editar');
    });

    it('should have aria-label on action buttons for accessibility', () => {
      component.company.set(mockCompany);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const buttons = compiled.querySelectorAll('.action-btn');
      
      expect(buttons.length).toBe(2);
      expect(buttons[0].getAttribute('aria-label')).toBe('Editar empresa');
      expect(buttons[1].getAttribute('aria-label')).toBe('Eliminar empresa');
    });
  });
});
