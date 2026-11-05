import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MatSelect } from '@angular/material/select';
import { CompaniesForm } from './companies-form';
import { CompanyControl, CompanyFormValue } from '../../models/company.models';
import { AddressControl } from '@shared/models/address.models';
import { Currency } from '../../data/currency.models';

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
      tipoPersonaId: new FormControl<number>(1, {
        nonNullable: true,
        validators: Validators.required,
      }),
      razonSocial: new FormControl('', { nonNullable: true, validators: Validators.required }),
      rfc: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^[A-ZÑ&]{3,4}\d{6}[A-Z\d]{3}$/)],
      }),
      email: new FormControl('', { nonNullable: true, validators: [Validators.email] }),
      emailDomain: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.pattern(
            /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/,
          ),
          Validators.maxLength(255),
        ],
      }),
      phone: new FormControl('', {
        nonNullable: true,
        validators: [Validators.pattern(/^\+?\d{10,13}$/)],
      }),
      currency: new FormControl('', { nonNullable: true, validators: Validators.required }),
      address: createAddressGroup(),
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompaniesForm],
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
    ['companyKey', 'companyName', 'tipoPersonaId', 'razonSocial', 'rfc', 'email', 'phone'].forEach(
      (key) => {
        const control = component.formGroup().get(key);
        control?.markAsTouched();
        control?.setErrors({ required: true });
      },
    );
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

  it('should render the emailDomain field with its label and hint', () => {
    const formField = fixture.nativeElement
      .querySelector('input[formControlName="emailDomain"]')
      ?.closest('mat-form-field');

    expect(formField).not.toBeNull();
    expect(formField?.querySelector('mat-label')?.textContent).toContain('Dominio de correo');
    expect(formField?.querySelector('mat-hint')?.textContent).toContain(
      'Se usa para generar el correo corporativo de los empleados',
    );
  });

  it('should cap the emailDomain input at the backend limit', () => {
    const input = fixture.nativeElement.querySelector('input[formControlName="emailDomain"]');

    expect(input).not.toBeNull();
    expect(input?.getAttribute('maxlength')).toBe('255');
  });

  it('should render the maxlength message on the emailDomain mat-error', () => {
    const control = component.formGroup().controls.emailDomain;
    control.setValue(`${'a'.repeat(252)}.com`);
    control.markAsTouched();
    fixture.detectChanges();

    const formField = fixture.nativeElement
      .querySelector('input[formControlName="emailDomain"]')
      ?.closest('mat-form-field');

    expect(control.hasError('maxlength')).toBe(true);
    // The exact sentence, not a substring: it pins the custom entry against the generic
    // `defaultErrorMessages.maxlength` fallback, which reads "No puede superar los 255 caracteres.".
    expect(formField?.querySelector('mat-error')?.textContent?.trim()).toBe(
      'El dominio no puede superar los 255 caracteres.',
    );
  });

  describe('currency select', () => {
    const catalog: Currency[] = [
      { code: 'MXN', minorUnits: 2, symbol: '$', enabled: true },
      { code: 'USD', minorUnits: 2, symbol: 'US$', enabled: true },
    ];

    function currencySelect(): Element | null {
      return fixture.nativeElement.querySelector('mat-select[formControlName="currency"]');
    }

    function currencyFormField(): HTMLElement | null {
      return fixture.nativeElement
        .querySelector('mat-select[formControlName="currency"]')
        ?.closest('mat-form-field');
    }

    /** Material renders the options lazily, so the panel has to be open to read the labels. */
    function openCurrencySelect(): MatSelect {
      const select = fixture.debugElement.query(By.css('mat-select[formControlName="currency"]'))
        .componentInstance as MatSelect;
      select.open();
      fixture.detectChanges();
      return select;
    }

    function optionLabels(): string[] {
      return Array.from(document.querySelectorAll('mat-option') as NodeListOf<Element>).map(
        (option) => option.textContent?.trim() ?? '',
      );
    }

    it('should render the currency select from the catalog input', () => {
      fixture.componentRef.setInput('currencies', catalog);
      fixture.detectChanges();

      expect(currencySelect()).not.toBeNull();
      expect(currencyFormField()?.querySelector('mat-label')?.textContent?.trim()).toBe('Moneda');

      const select = openCurrencySelect();

      // The empty option is how the operator's first answer stays "no currency yet" (D1).
      expect(optionLabels()).toEqual(['— Seleccionar moneda —', 'MXN — $', 'USD — US$']);

      select.close();
      fixture.detectChanges();
    });

    it('should leave the currency empty on a new form', () => {
      const control = component.formGroup().controls.currency;

      expect(control.value).toBe('');
      expect(control.hasError('required')).toBe(true);
    });

    it('should render the shared required message when the currency is empty', () => {
      const control = component.formGroup().controls.currency;
      control.markAsTouched();
      fixture.detectChanges();

      // No bespoke message: the field reuses `defaultErrorMessages.required`.
      expect(currencyFormField()?.querySelector('mat-error')?.textContent?.trim()).toBe(
        'Este campo es obligatorio.',
      );
    });

    it('should not emit when every required field is complete except the currency', () => {
      const fields = component.formGroup().controls;
      fields.companyKey.setValue('KEY01');
      fields.companyName.setValue('Test Corp');
      fields.razonSocial.setValue('Test Corp SA');
      fields.rfc.setValue('XAXX010101000');
      fields.email.setValue('test@test.com');
      fields.phone.setValue('5551234567');

      const emitted: CompanyFormValue[] = [];
      component.saveCompany.subscribe((value: CompanyFormValue) => emitted.push(value));

      component.onSave();

      expect(emitted).toEqual([]);
    });

    it('should emit the selected currency', () => {
      fixture.componentRef.setInput('currencies', catalog);
      fixture.detectChanges();

      fillRequiredFields();

      let emitted: CompanyFormValue | undefined;
      component.saveCompany.subscribe((value: CompanyFormValue) => {
        emitted = value;
      });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.currency).toBe('MXN');
    });

    it('should not block a stored code the catalog no longer offers', () => {
      // A retired code is unreachable today: the catalog holds one row and it is enabled. If it ever
      // happens, the select has no option to render, but the control keeps the stored value, so the
      // form still states what the company states instead of demanding a new answer.
      fixture.componentRef.setInput('currencies', catalog);
      component.formGroup().controls.currency.setValue('ARS');
      fixture.detectChanges();

      const select = openCurrencySelect();

      expect(optionLabels()).toEqual(['— Seleccionar moneda —', 'MXN — $', 'USD — US$']);
      expect(component.formGroup().controls.currency.value).toBe('ARS');
      expect(component.formGroup().controls.currency.valid).toBe(true);

      select.close();
      fixture.detectChanges();
    });
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

      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('nonexistent'));

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

  /**
   * Every control the page marks `Validators.required`, in one place: a form that is complete
   * except one of them must not emit, and that is only provable if "complete" is written once.
   */
  function fillRequiredFields(): void {
    const fields = component.formGroup().controls;
    fields.companyKey.setValue('KEY01');
    fields.companyName.setValue('Test Corp');
    fields.razonSocial.setValue('Test Corp SA');
    fields.rfc.setValue('XAXX010101000');
    fields.email.setValue('test@test.com');
    fields.phone.setValue('5551234567');
    fields.currency.setValue('MXN');
  }

  describe('onSave — address emission', () => {
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

      let emitted: CompanyFormValue | undefined;
      component.saveCompany.subscribe((c: CompanyFormValue) => {
        emitted = c;
      });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.address).toBeDefined();
      expect(emitted!.address!.street).toBe('Av. Reforma');
      expect(emitted!.address!.streetNumber).toBe('222');
      expect(emitted!.address!.city).toBe('CDMX');
    });

    it('should emit Company without address object when all address fields empty', () => {
      fillRequiredFields();

      let emitted: CompanyFormValue | undefined;
      component.saveCompany.subscribe((c: CompanyFormValue) => {
        emitted = c;
      });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.address).toBeUndefined();
    });

    it('should emit the typed emailDomain', () => {
      fillRequiredFields();
      component.formGroup().controls.emailDomain.setValue('acme.com');

      let emitted: CompanyFormValue | undefined;
      component.saveCompany.subscribe((c: CompanyFormValue) => {
        emitted = c;
      });

      component.onSave();

      expect(emitted).toBeDefined();
      expect(emitted!.emailDomain).toBe('acme.com');
    });
  });
});
