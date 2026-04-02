import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormGroup, FormControl, Validators, ReactiveFormsModule } from '@angular/forms';
import { CompaniesForm } from './companies-form';
import { CompanyControl } from '../models/company.models';

describe('CompaniesForm', () => {
  let component: CompaniesForm;
  let fixture: ComponentFixture<CompaniesForm>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReactiveFormsModule, CompaniesForm]
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesForm);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should detect create mode (no id)', () => {
    const form = new FormGroup<CompanyControl>({
      id: new FormControl(''),
      companyKey: new FormControl(''),
      companyName: new FormControl('', Validators.required),
      tipoPersonaId: new FormControl(1),
      razonSocial: new FormControl('', Validators.required),
      rfc: new FormControl('', [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
      email: new FormControl('', [Validators.required, Validators.email]),
      phone: new FormControl(''),
      address: new FormControl(''),
      enabled: new FormControl(true),
    });
    
    component.formGroup = form as any;
    fixture.detectChanges();
    
    expect(component.isEditMode()).toBe(false);
  });

  it('should detect edit mode (with id)', () => {
    const form = new FormGroup<CompanyControl>({
      id: new FormControl('123'),
      companyKey: new FormControl('C001'),
      companyName: new FormControl('Company A', Validators.required),
      tipoPersonaId: new FormControl(1),
      razonSocial: new FormControl('Razon A', Validators.required),
      rfc: new FormControl('RFC123456789', [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
      email: new FormControl('test@test.com', [Validators.required, Validators.email]),
      phone: new FormControl('5551234567'),
      address: new FormControl('Address 123'),
      enabled: new FormControl(true),
    });
    
    component.formGroup = form as any;
    fixture.detectChanges();
    
    expect(component.isEditMode()).toBe(true);
  });

  it('should emit saveCompany on valid submit', (done) => {
    const form = new FormGroup<CompanyControl>({
      id: new FormControl(''),
      companyKey: new FormControl(''),
      companyName: new FormControl('Test Company', Validators.required),
      tipoPersonaId: new FormControl(1),
      razonSocial: new FormControl('Test Razon', Validators.required),
      rfc: new FormControl('RFC1234567890', [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
      email: new FormControl('test@test.com', [Validators.required, Validators.email]),
      phone: new FormControl('5551234567'),
      address: new FormControl('Test Address'),
      enabled: new FormControl(true),
    });
    
    component.formGroup = form as any;
    component.saveCompany.subscribe(company => {
      expect(company.companyName).toBe('Test Company');
      done();
    });
    
    component.onSave();
  });

  it('should NOT emit saveCompany on invalid submit', () => {
    const form = new FormGroup<CompanyControl>({
      id: new FormControl(''),
      companyKey: new FormControl(''),
      companyName: new FormControl('', Validators.required), // Invalid
      tipoPersonaId: new FormControl(1),
      razonSocial: new FormControl('', Validators.required), // Invalid
      rfc: new FormControl('', Validators.required), // Invalid
      email: new FormControl('', Validators.required), // Invalid
      phone: new FormControl(''),
      address: new FormControl(''),
      enabled: new FormControl(true),
    });
    
    component.formGroup = form as any;
    let emitted = false;
    component.saveCompany.subscribe(() => {
      emitted = true;
    });
    
    component.onSave();
    expect(emitted).toBe(false);
  });

  it('should emit cancelForm on cancel', (done) => {
    component.cancelForm.subscribe(() => {
      done();
    });
    
    component.onCancel();
  });

  it('should getControl return correct control', () => {
    const form = new FormGroup<CompanyControl>({
      id: new FormControl(''),
      companyKey: new FormControl(''),
      companyName: new FormControl('', Validators.required),
      tipoPersonaId: new FormControl(1),
      razonSocial: new FormControl('', Validators.required),
      rfc: new FormControl('', [Validators.required, Validators.minLength(12), Validators.maxLength(13)]),
      email: new FormControl('', [Validators.required, Validators.email]),
      phone: new FormControl(''),
      address: new FormControl(''),
      enabled: new FormControl(true),
    });
    
    component.formGroup = form as any;
    
    const control = component.getControl('companyName');
    expect(control).toBe(form.controls.companyName);
  });
});
