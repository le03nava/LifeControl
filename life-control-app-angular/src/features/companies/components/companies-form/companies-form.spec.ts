import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { CompaniesForm } from './companies-form';
import { CompanyControl } from '../../models/company.models';

describe('CompaniesForm', () => {
  let component: CompaniesForm;
  let fixture: ComponentFixture<CompaniesForm>;

  function createFormGroup(): FormGroup<CompanyControl> {
    return new FormGroup<CompanyControl>({
      id: new FormControl('', { nonNullable: true }),
      companyId: new FormControl<number | null>(null, Validators.required),
      companyName: new FormControl('', { nonNullable: true, validators: Validators.required }),
      tipoPersonaId: new FormControl<number>(1, { nonNullable: true, validators: Validators.required }),
      razonSocial: new FormControl('', { nonNullable: true, validators: Validators.required }),
      rfc: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-ZÑ&]{3,4}\d{6}[A-Z\d]{3}$/)] }),
      email: new FormControl('', { nonNullable: true, validators: [Validators.email] }),
      phone: new FormControl('', { nonNullable: true, validators: [Validators.pattern(/^\+?\d{10,13}$/)] }),
      address: new FormControl('', { nonNullable: true }),
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompaniesForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(CompaniesForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render app-form-error for each tracked field', () => {
    const formErrors = fixture.nativeElement.querySelectorAll('app-form-error');
    // 7 tracked fields: companyId, companyName, tipoPersonaId, razonSocial, rfc, email, phone
    expect(formErrors.length).toBe(7);
  });

  it('should render customMessages on rfc, email, and phone form-errors', () => {
    const formErrors = fixture.nativeElement.querySelectorAll('app-form-error');

    // rfc has customMessages
    const rfcForm = formErrors[4].closest('mat-form-field');
    expect(rfcForm?.querySelector('input')?.getAttribute('formControlName')).toBe('rfc');

    // email has customMessages
    const emailForm = formErrors[5].closest('mat-form-field');
    expect(emailForm?.querySelector('input')?.getAttribute('formControlName')).toBe('email');

    // phone has customMessages
    const phoneForm = formErrors[6].closest('mat-form-field');
    expect(phoneForm?.querySelector('input')?.getAttribute('formControlName')).toBe('phone');
  });

  it('should preserve mat-icon prefixes on email and phone fields', () => {
    const matIcons = fixture.nativeElement.querySelectorAll('mat-icon[matPrefix]');
    expect(matIcons.length).toBe(2);
    expect(matIcons[0].textContent).toContain('mail');
    expect(matIcons[1].textContent).toContain('phone');
  });

  it('should leave address field unchanged (no form-error)', () => {
    const addressField = fixture.nativeElement.querySelector('[formControlName="address"]');
    expect(addressField).toBeTruthy();

    const addressForm = addressField.closest('mat-form-field');
    const addressError = addressForm?.querySelector('app-form-error');
    expect(addressError).toBeNull();
  });
});
