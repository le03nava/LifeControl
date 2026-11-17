import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MatSelect } from '@angular/material/select';
import { CustomerForm } from './customer-form';
import { CustomerControl, FiscalProfileControl } from '../../models/customer.models';
import {
  FISCAL_PROFILE_INCOMPLETE,
  FiscalProfileField,
  fiscalProfileCompleteValidator,
} from '../../validation/fiscal-profile-complete';
import { Country } from '@features/companies/countries/models/country.models';

/**
 * The presentational half of `S2`/`S3` of `odd/tasks/customers-fiscal-form.md`: every field renders, and the
 * all-or-nothing refusal is painted **beside the field it is about**.
 *
 * The page owns the form group, so this spec builds one the way the page does — the four fiscal facts with no
 * `Validators.required` on them, and the all-or-nothing rule attached to the fiscal sub-group.
 */
describe('CustomerForm', () => {
  let component: CustomerForm;
  let fixture: ComponentFixture<CustomerForm>;

  const countries: Country[] = [
    {
      id: 'country-1',
      countryCode: 'MX',
      countryName: 'México',
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  function createFiscalGroup(): FormGroup<FiscalProfileControl> {
    return new FormGroup<FiscalProfileControl>(
      {
        legalName: new FormControl('', { nonNullable: true }),
        regimenFiscal: new FormControl('', { nonNullable: true }),
        fiscalStreet: new FormControl('', { nonNullable: true }),
        fiscalZipCode: new FormControl('', {
          nonNullable: true,
          validators: [Validators.pattern(/^\d{5}$/)],
        }),
        fiscalState: new FormControl('', { nonNullable: true }),
        fiscalCountryId: new FormControl('', { nonNullable: true }),
        defaultUsoCfdi: new FormControl('', { nonNullable: true }),
      },
      { validators: [fiscalProfileCompleteValidator] },
    );
  }

  function createFormGroup(): FormGroup<CustomerControl> {
    return new FormGroup<CustomerControl>({
      id: new FormControl('', { nonNullable: true }),
      name: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      email: new FormControl('', { nonNullable: true, validators: [Validators.email] }),
      phone: new FormControl('', { nonNullable: true }),
      rfc: new FormControl('', {
        nonNullable: true,
        validators: [Validators.pattern(/^[A-ZÑ&]{3,4}\d{6}[A-Za-z0-9]{3}$/)],
      }),
      salesChannel: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      enabled: new FormControl(true, { nonNullable: true }),
      fiscal: createFiscalGroup(),
    });
  }

  function render(formGroup = createFormGroup()): void {
    fixture = TestBed.createComponent(CustomerForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', formGroup);
    fixture.componentRef.setInput('countries', countries);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CustomerForm],
    }).compileComponents();

    render();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders a control for each of the thirteen request fields', () => {
    const el: HTMLElement = fixture.nativeElement;
    const names = Array.from(el.querySelectorAll('[formControlName]')).map((control) =>
      control.getAttribute('formControlName'),
    );

    expect(names).toEqual(
      expect.arrayContaining([
        'name',
        'email',
        'phone',
        'rfc',
        'salesChannel',
        'enabled',
        'legalName',
        'regimenFiscal',
        'fiscalStreet',
        'fiscalZipCode',
        'fiscalState',
        'fiscalCountryId',
        'defaultUsoCfdi',
      ]),
    );
  });

  /** A `mat-select` renders its options only once it is open, so the panel is what gets measured. */
  function openSelect(controlName: string): MatSelect {
    const select = fixture.debugElement.query(
      By.css(`mat-select[formControlName="${controlName}"]`),
    ).componentInstance as MatSelect;
    select.open();
    fixture.detectChanges();
    return select;
  }

  function optionLabels(): string[] {
    return Array.from(document.querySelectorAll('mat-option') as NodeListOf<Element>).map(
      (option) => option.textContent?.trim() ?? '',
    );
  }

  it('offers the nineteen régimes from the shared vocabulary, empty option first', () => {
    const select = openSelect('regimenFiscal');
    const labels = optionLabels();
    select.close();
    fixture.detectChanges();

    expect(labels).toHaveLength(20);
    expect(labels[0]).toBe('— No declarado —');
    expect(labels[1]).toBe('601 — General de Ley Personas Morales');
  });

  it('offers the twenty-five CFDI uses from the shared vocabulary, four-character codes included', () => {
    const select = openSelect('defaultUsoCfdi');
    const labels = optionLabels();
    select.close();
    fixture.detectChanges();

    expect(labels).toHaveLength(26);
    expect(labels[0]).toBe('— No declarado —');
    expect(labels).toContain('G03 — Gastos en general');
    // `CP01` and `CN01` are four characters wide: a select fed by a narrow vocabulary drops them.
    expect(labels).toContain('CP01 — Pagos');
    expect(labels).toContain('CN01 — Nómina');
  });

  it('offers the fiscal country from the countries service, not a free-text field', () => {
    expect(
      fixture.debugElement.query(By.css('mat-select[formControlName="fiscalCountryId"]')),
    ).not.toBeNull();

    const select = openSelect('fiscalCountryId');
    const labels = optionLabels();
    select.close();
    fixture.detectChanges();

    expect(labels).toEqual(['— No declarado —', 'México']);
  });

  it('paints one message beside each missing fact, naming it', () => {
    const form = component.formGroup();
    form.controls.name.setValue('Cliente Uno');
    form.controls.salesChannel.setValue('TODOS');
    form.controls.fiscal.controls.legalName.setValue('Cliente Uno SA de CV');
    form.markAllAsTouched();
    fixture.detectChanges();

    const fiscal = form.controls.fiscal;
    expect(fiscal.controls.regimenFiscal.getError(FISCAL_PROFILE_INCOMPLETE)).toContain(
      'régimen fiscal',
    );
    expect(fiscal.controls.fiscalZipCode.getError(FISCAL_PROFILE_INCOMPLETE)).toContain(
      'código postal fiscal',
    );
    expect(fiscal.controls.defaultUsoCfdi.getError(FISCAL_PROFILE_INCOMPLETE)).toContain(
      'uso de CFDI',
    );
    // The one that is stated is not refused.
    expect(fiscal.controls.legalName.getError(FISCAL_PROFILE_INCOMPLETE)).toBeNull();

    const messages = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('mat-error'),
    ).map((error) => error.textContent ?? '');

    expect(messages.some((message) => message.includes('régimen fiscal'))).toBe(true);
    expect(messages.some((message) => message.includes('código postal fiscal'))).toBe(true);
    expect(messages.some((message) => message.includes('uso de CFDI'))).toBe(true);
  });

  it('drops the refusal from a field once the profile becomes complete', () => {
    const fiscal = component.formGroup().controls.fiscal;
    fiscal.controls.legalName.setValue('Cliente Uno SA de CV');
    fiscal.controls.regimenFiscal.setValue('601');
    fiscal.controls.fiscalZipCode.setValue('06600');

    expect(fiscal.controls.fiscalZipCode.getError(FISCAL_PROFILE_INCOMPLETE)).toBeDefined();

    fiscal.controls.defaultUsoCfdi.setValue('G03');

    for (const field of Object.keys(fiscal.controls) as FiscalProfileField[]) {
      expect(fiscal.controls[field].getError(FISCAL_PROFILE_INCOMPLETE)).toBeNull();
    }
  });

  it('accepts a cash customer: nothing declared in the fiscal block is not an error', () => {
    const form = component.formGroup();
    form.controls.name.setValue('Cliente Uno');
    form.controls.salesChannel.setValue('TODOS');
    form.markAllAsTouched();
    fixture.detectChanges();

    expect(form.valid).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('mat-error')).toHaveLength(0);
  });

  it('refuses a postal code that is not five digits, the shape the API enforces', () => {
    const fiscal = component.formGroup().controls.fiscal;
    fiscal.controls.legalName.setValue('Cliente Uno SA de CV');
    fiscal.controls.regimenFiscal.setValue('601');
    fiscal.controls.defaultUsoCfdi.setValue('G03');

    fiscal.controls.fiscalZipCode.setValue('6600');
    expect(fiscal.controls.fiscalZipCode.hasError('pattern')).toBe(true);

    fiscal.controls.fiscalZipCode.setValue('06600');
    expect(fiscal.controls.fiscalZipCode.hasError('pattern')).toBe(false);
  });

  it('emits the form value on save and the cancel event on cancel', () => {
    const saved = vi.fn();
    const cancelled = vi.fn();
    component.saveCustomer.subscribe(saved);
    component.cancelForm.subscribe(cancelled);

    component.formGroup().controls.name.setValue('Cliente Uno');
    component.formGroup().controls.salesChannel.setValue('TODOS');
    component.onSave();

    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Cliente Uno', salesChannel: 'TODOS' }),
    );

    component.onCancel();
    expect(cancelled).toHaveBeenCalled();
  });

  it('does not emit a partial fiscal profile, and marks every control as touched instead', () => {
    const saved = vi.fn();
    component.saveCustomer.subscribe(saved);

    component.formGroup().controls.name.setValue('Cliente Uno');
    component.formGroup().controls.salesChannel.setValue('TODOS');
    component.formGroup().controls.fiscal.controls.legalName.setValue('Cliente Uno SA de CV');
    component.onSave();

    expect(saved).not.toHaveBeenCalled();
    expect(component.formGroup().controls.fiscal.controls.regimenFiscal.touched).toBe(true);
  });
});
