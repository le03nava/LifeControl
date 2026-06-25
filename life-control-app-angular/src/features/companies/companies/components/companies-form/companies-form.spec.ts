import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { CompaniesForm } from './companies-form';
import { Company, CompanyControl } from '../../models/company.models';
import { AddressControl } from '@shared/models/address.models';

describe('CompaniesForm', () => {
  let component: CompaniesForm;
  let fixture: ComponentFixture<CompaniesForm>;

  function createAddressGroup(): FormGroup<AddressControl> {
    return new FormGroup<AddressControl>({
      street: new FormControl<string | null>(null, { nonNullable: false }),
      streetNumber: new FormControl<string | null>(null, { nonNullable: false }),
      internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
      neighborhood: new FormControl<string | null>(null, { nonNullable: false }),
      zipCode: new FormControl<string | null>(null, { nonNullable: false }),
      city: new FormControl<string | null>(null, { nonNullable: false }),
      state: new FormControl<string | null>(null, { nonNullable: false }),
      countryId: new FormControl<string | null>(null, { nonNullable: false }),
    });
  }

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
      address: createAddressGroup(),
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
    expect(matErrors.length).toBe(7);
  });

  it('should render customMessages on rfc, email, and phone mat-errors', () => {
    triggerErrorsOnTrackedFields();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');

    const rfcForm = matErrors[4].closest('mat-form-field');
    expect(rfcForm?.querySelector('input')?.getAttribute('formControlName')).toBe('rfc');

    const emailForm = matErrors[5].closest('mat-form-field');
    expect(emailForm?.querySelector('input')?.getAttribute('formControlName')).toBe('email');

    const phoneForm = matErrors[6].closest('mat-form-field');
    expect(phoneForm?.querySelector('input')?.getAttribute('formControlName')).toBe('phone');
  });

  it('should preserve mat-icon prefixes on email, phone and address fields', () => {
    const matIcons = fixture.nativeElement.querySelectorAll('mat-icon[matPrefix]');
    expect(matIcons.length).toBe(3);
    expect(matIcons[0].textContent).toContain('mail');
    expect(matIcons[1].textContent).toContain('phone');
    expect(matIcons[2].textContent).toContain('location_on');
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

      rfcControl.setValue('AB');
      fixture.detectChanges();

      expect(rfcControl.errors?.['serverError']).toBeUndefined();
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

    it('should emit Company with nested address when filled', () => {
      fillRequiredFields();
      const addressGroup = component.formGroup().controls.address;
      addressGroup.controls.street.setValue('Av. Reforma');
      addressGroup.controls.streetNumber.setValue('222');
      addressGroup.controls.internalNumber.setValue('A-101');
      addressGroup.controls.neighborhood.setValue('Juárez');
      addressGroup.controls.zipCode.setValue('06600');
      addressGroup.controls.city.setValue('CDMX');
      addressGroup.controls.state.setValue('CDMX');
      addressGroup.controls.countryId.setValue('MX');

      let emitted: Company | undefined;
      component.saveCompany.subscribe((c: Company) => { emitted = c; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.address).toBeDefined();
      expect(emitted!.address!.street).toBe('Av. Reforma');
      expect(emitted!.address!.streetNumber).toBe('222');
      expect(emitted!.address!.city).toBe('CDMX');
    });

    it('should emit Company without address object when all address fields empty', () => {
      fillRequiredFields();

      let emitted: Company | undefined;
      component.saveCompany.subscribe((c: Company) => { emitted = c; });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.address).toBeUndefined();
    });
  });
});
