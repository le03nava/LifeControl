import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { CompaniesForm } from './companies-form';
import { Company, CompanyControl } from '../../models/company.models';
import { CountryService } from '../../../../countries/data/country.service';
import { Country } from '../../../countries/models/country.models';

describe('CompaniesForm', () => {
  let component: CompaniesForm;
  let fixture: ComponentFixture<CompaniesForm>;
  let countryServiceMock: Partial<CountryService>;

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
      street: new FormControl<string | null>(null, { nonNullable: false }),
      streetNumber: new FormControl<string | null>(null, { nonNullable: false }),
      internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
      neighborhood: new FormControl<string | null>(null, { nonNullable: false }),
      zipCode: new FormControl<string | null>(null, { nonNullable: false }),
      city: new FormControl<string | null>(null, { nonNullable: false }),
      state: new FormControl<string | null>(null, { nonNullable: false }),
      countryId: new FormControl<string | null>(null, { nonNullable: false }),
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
    });
  }

  beforeEach(async () => {
    countryServiceMock = {
      countries: signal<Country[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      error: signal<string | null>(null).asReadonly(),
      getCountries: vi.fn().mockReturnValue(of([])),
    };

    await TestBed.configureTestingModule({
      imports: [CompaniesForm, NoopAnimationsModule],
      providers: [
        { provide: CountryService, useValue: countryServiceMock },
      ],
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

  it('should render address section heading', () => {
    const heading = fixture.nativeElement.querySelector('.address-heading');
    expect(heading).toBeTruthy();
    expect(heading.textContent).toContain('Dirección');
  });

  it('should render all 8 address fields', () => {
    const addressFields = ['street', 'streetNumber', 'internalNumber', 'neighborhood', 'zipCode', 'city', 'state', 'countryId'];
    for (const field of addressFields) {
      const el = fixture.nativeElement.querySelector(`[formControlName="${field}"]`);
      expect(el).toBeTruthy();
    }
  });

  it('should keep address fields optional (no required indicator)', () => {
    const addressFields = ['street', 'streetNumber', 'internalNumber', 'neighborhood', 'zipCode', 'city', 'state', 'countryId'];
    for (const field of addressFields) {
      const control = component.formGroup().get(field);
      control?.markAsTouched();
      expect(control?.errors?.['required']).toBeUndefined();
    }
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

  describe('onSave — address emission', () => {
    function fillRequiredFields(): void {
      const fields = component.formGroup().controls;
      fields.companyKey.setValue('KEY01');
      fields.companyName.setValue('Test Corp');
      fields.razonSocial.setValue('Test Corp SA');
      fields.rfc.setValue('XAXX010101000');
      fields.email.setValue('test@test.com');
      fields.phone.setValue('5551234567');
    }

    it('should emit Company with address fields when filled', () => {
      fillRequiredFields();
      const fields = component.formGroup().controls;
      fields.street.setValue('Av. Reforma');
      fields.streetNumber.setValue('222');
      fields.internalNumber.setValue('A-101');
      fields.neighborhood.setValue('Juárez');
      fields.zipCode.setValue('06600');
      fields.city.setValue('CDMX');
      fields.state.setValue('CDMX');
      fields.countryId.setValue('MX');

      let emitted: Company | undefined;
      component.saveCompany.subscribe((c: Company) => { emitted = c; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.street).toBe('Av. Reforma');
      expect(emitted!.streetNumber).toBe('222');
      expect(emitted!.internalNumber).toBe('A-101');
      expect(emitted!.neighborhood).toBe('Juárez');
      expect(emitted!.zipCode).toBe('06600');
      expect(emitted!.city).toBe('CDMX');
      expect(emitted!.state).toBe('CDMX');
      expect(emitted!.countryId).toBe('MX');
    });

    it('should emit Company without address fields when empty', () => {
      fillRequiredFields();

      let emitted: Company | undefined;
      component.saveCompany.subscribe((c: Company) => { emitted = c; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.street).toBeUndefined();
      expect(emitted!.city).toBeUndefined();
      expect(emitted!.countryId).toBeUndefined();
    });
  });
});
