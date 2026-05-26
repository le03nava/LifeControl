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
      companyKey: new FormControl('', { nonNullable: true, validators: Validators.required }),
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

  function triggerErrorsOnTrackedFields(): void {
    ['companyKey', 'companyName', 'tipoPersonaId', 'razonSocial', 'rfc', 'email', 'phone']
      .forEach(key => {
        const control = component.formGroup().get(key);
        control?.markAsTouched();
        control?.setErrors({ required: true });
      });
    fixture.detectChanges();
  }

  it('should render mat-error for each tracked field', () => {
    triggerErrorsOnTrackedFields();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
    // 7 tracked fields: companyKey, companyName, tipoPersonaId, razonSocial, rfc, email, phone
    expect(matErrors.length).toBe(7);
  });

  it('should render customMessages on rfc, email, and phone mat-errors', () => {
    triggerErrorsOnTrackedFields();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');

    // rfc has customMessages
    const rfcForm = matErrors[4].closest('mat-form-field');
    expect(rfcForm?.querySelector('input')?.getAttribute('formControlName')).toBe('rfc');

    // email has customMessages
    const emailForm = matErrors[5].closest('mat-form-field');
    expect(emailForm?.querySelector('input')?.getAttribute('formControlName')).toBe('email');

    // phone has customMessages
    const phoneForm = matErrors[6].closest('mat-form-field');
    expect(phoneForm?.querySelector('input')?.getAttribute('formControlName')).toBe('phone');
  });

  it('should preserve mat-icon prefixes on email and phone fields', () => {
    const matIcons = fixture.nativeElement.querySelectorAll('mat-icon[matPrefix]');
    expect(matIcons.length).toBe(2);
    expect(matIcons[0].textContent).toContain('mail');
    expect(matIcons[1].textContent).toContain('phone');
  });

  it('should leave address field unchanged (no mat-error)', () => {
    const addressField = fixture.nativeElement.querySelector('[formControlName="address"]');
    expect(addressField).toBeTruthy();

    const addressForm = addressField.closest('mat-form-field');
    const addressError = addressForm?.querySelector('mat-error');
    expect(addressError).toBeNull();
  });

  describe('serverErrors', () => {
    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        rfc: 'RFC inválido',
        email: 'Correo ya registrado',
      });
      fixture.detectChanges();

      const rfcControl = component.formGroup().controls.rfc;
      const emailControl = component.formGroup().controls.email;

      expect(rfcControl.errors?.['serverError']).toBe('RFC inválido');
      expect(emailControl.errors?.['serverError']).toBe('Correo ya registrado');
    });

    it('should warn on unmatched server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      fixture.componentRef.setInput('serverErrors', {
        nonexistent: 'No existe',
      });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalledWith(
        expect.stringContaining('nonexistent'),
      );

      warnSpy.mockRestore();
    });

    it('should clear serverError on valueChanges while preserving other validators', () => {
      const rfcControl = component.formGroup().controls.rfc;

      fixture.componentRef.setInput('serverErrors', {
        rfc: 'RFC inválido',
      });
      fixture.detectChanges();

      expect(rfcControl.errors?.['serverError']).toBe('RFC inválido');

      // Set a short value that satisfies required but fails pattern
      rfcControl.setValue('AB');
      fixture.detectChanges();

      // serverError should be cleared by valueChanges
      expect(rfcControl.errors?.['serverError']).toBeUndefined();

      // Pattern validator should still fire (AB is too short)
      expect(rfcControl.errors?.['pattern']).toBeDefined();
    });
  });
});
