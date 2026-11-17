import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { CustomerEdit } from './customer-edit';
import { CustomerForm } from '../../components/customer-form/customer-form';
import { CustomerService } from '../../data/customer.service';
import { Customer, CustomerFormValue, CustomerRequest } from '../../models/customer.models';
import { CountryService } from '@features/countries/data/country.service';
import { Country } from '@features/companies/countries/models/country.models';

/**
 * `T2`/`T3` of `odd/tasks/customers-fiscal-form.md`: one page for both modes, the wire body built key by key,
 * and the all-or-nothing refusal stated before the request leaves the browser.
 */
describe('CustomerEdit', () => {
  let fixture: ComponentFixture<CustomerEdit>;
  let component: CustomerEdit;
  let customerService: {
    getCustomerById: ReturnType<typeof vi.fn>;
    createCustomer: ReturnType<typeof vi.fn>;
    updateCustomer: ReturnType<typeof vi.fn>;
  };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let routeId: string | null;

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

  const storedCustomer: Customer = {
    id: 'c-1',
    name: 'Alpha Comercial',
    email: 'alpha@test.com',
    phone: '5551112222',
    rfc: 'RFC010101AB1',
    salesChannel: 'TODOS',
    enabled: true,
    legalName: 'Alpha Comercial SA de CV',
    regimenFiscal: '601',
    fiscalStreet: 'Av. Reforma 100',
    fiscalZipCode: '06600',
    fiscalState: 'Ciudad de México',
    fiscalCountryId: 'country-1',
    defaultUsoCfdi: 'G03',
    createdAt: '2026-11-17T10:00:00',
    updatedAt: '2026-11-17T10:00:00',
  };

  /** The form value both modes emit, so each case states only what it is about. */
  function formValue(overrides: Partial<CustomerFormValue> = {}): CustomerFormValue {
    return {
      id: '',
      name: 'Cliente Tres',
      email: 'tres@test.com',
      phone: '5559998888',
      rfc: 'RFC010101AB1',
      salesChannel: 'TODOS',
      enabled: true,
      fiscal: {
        legalName: 'Cliente Tres SA de CV',
        regimenFiscal: '601',
        fiscalStreet: 'Calle 1',
        fiscalZipCode: '06600',
        fiscalState: 'Ciudad de México',
        fiscalCountryId: 'country-1',
        defaultUsoCfdi: 'G03',
      },
      ...overrides,
    };
  }

  /** The thirteen keys `CustomerRequest.java` declares — and nothing else. */
  const requestKeys = [
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
  ];

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(CustomerEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    routeId = null;
    customerService = {
      getCustomerById: vi.fn().mockReturnValue(of(storedCustomer)),
      createCustomer: vi.fn().mockReturnValue(of({ ...storedCustomer, id: 'c-new' })),
      updateCustomer: vi.fn().mockReturnValue(of(storedCustomer)),
    };
    router = { navigate: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [CustomerEdit],
      providers: [
        { provide: CustomerService, useValue: customerService },
        {
          provide: CountryService,
          useValue: {
            countries: signal<Country[]>(countries).asReadonly(),
            loading: signal(false).asReadonly(),
            error: signal<string | null>(null).asReadonly(),
            getCountries: vi.fn().mockReturnValue(of(countries)),
          },
        },
        { provide: Router, useValue: router },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => routeId } } },
        },
      ],
    }).compileComponents();
  });

  it('should create', async () => {
    await render();
    expect(component).toBeTruthy();
  });

  describe('the two modes', () => {
    it('starts a create page with nothing declared in the fiscal block', async () => {
      await render();

      expect(component.id()).toBeNull();
      expect(component.form.controls.name.value).toBe('');
      expect(component.form.controls.fiscal.controls.legalName.value).toBe('');
    });

    it('reads the customer the route names and hydrates all thirteen fields', async () => {
      routeId = 'c-1';
      await render();

      expect(component.id()).toBe('c-1');
      expect(customerService.getCustomerById).toHaveBeenCalledWith('c-1');

      const form = component.form;
      expect(form.controls.name.value).toBe('Alpha Comercial');
      expect(form.controls.email.value).toBe('alpha@test.com');
      expect(form.controls.phone.value).toBe('5551112222');
      expect(form.controls.rfc.value).toBe('RFC010101AB1');
      expect(form.controls.salesChannel.value).toBe('TODOS');
      expect(form.controls.enabled.value).toBe(true);
      expect(form.controls.fiscal.controls.legalName.value).toBe('Alpha Comercial SA de CV');
      expect(form.controls.fiscal.controls.regimenFiscal.value).toBe('601');
      expect(form.controls.fiscal.controls.fiscalStreet.value).toBe('Av. Reforma 100');
      expect(form.controls.fiscal.controls.fiscalZipCode.value).toBe('06600');
      expect(form.controls.fiscal.controls.fiscalState.value).toBe('Ciudad de México');
      expect(form.controls.fiscal.controls.fiscalCountryId.value).toBe('country-1');
      expect(form.controls.fiscal.controls.defaultUsoCfdi.value).toBe('G03');
    });

    it('hydrates an absent fiscal profile as empty, not as a placeholder', async () => {
      routeId = 'c-1';
      customerService.getCustomerById = vi.fn().mockReturnValue(
        of({
          ...storedCustomer,
          legalName: null,
          regimenFiscal: null,
          fiscalStreet: null,
          fiscalZipCode: null,
          fiscalState: null,
          fiscalCountryId: null,
          defaultUsoCfdi: null,
        }),
      );
      await render();

      const fiscal = component.form.controls.fiscal;
      for (const control of Object.values(fiscal.controls)) {
        expect(control.value).toBe('');
      }
      expect(component.form.valid).toBe(true);
    });
  });

  describe('the wire body', () => {
    it('sends only the thirteen fields CustomerRequest declares, and nowhere the row id', async () => {
      await render();

      component.onSaveCustomer({
        id: '',
        name: 'Cliente Tres',
        email: 'tres@test.com',
        phone: '5559998888',
        rfc: 'RFC010101AB1',
        salesChannel: 'TODOS',
        enabled: true,
        fiscal: {
          legalName: 'Cliente Tres SA de CV',
          regimenFiscal: '601',
          fiscalStreet: 'Calle 1',
          fiscalZipCode: '06600',
          fiscalState: 'Ciudad de México',
          fiscalCountryId: 'country-1',
          defaultUsoCfdi: 'G03',
        },
      });

      const body = customerService.createCustomer.mock.calls[0][0] as Record<string, unknown>;
      expect(Object.keys(body).sort()).toEqual([...requestKeys].sort());
      expect(body['id']).toBeUndefined();
      expect(body['createdAt']).toBeUndefined();
      expect(body['updatedAt']).toBeUndefined();
    });

    it('round-trips every request field with the value the operator stated', async () => {
      await render();

      const value = {
        id: '',
        name: 'Cliente Tres',
        email: 'tres@test.com',
        phone: '5559998888',
        rfc: 'RFC010101AB1',
        salesChannel: 'TODOS',
        enabled: false,
        fiscal: {
          legalName: 'Cliente Tres SA de CV',
          regimenFiscal: '612',
          fiscalStreet: 'Calle 1',
          fiscalZipCode: '44100',
          fiscalState: 'Jalisco',
          fiscalCountryId: 'country-1',
          defaultUsoCfdi: 'CP01',
        },
      };

      component.onSaveCustomer(value);

      const expected: CustomerRequest = {
        name: 'Cliente Tres',
        email: 'tres@test.com',
        phone: '5559998888',
        rfc: 'RFC010101AB1',
        salesChannel: 'TODOS',
        enabled: false,
        legalName: 'Cliente Tres SA de CV',
        regimenFiscal: '612',
        fiscalStreet: 'Calle 1',
        fiscalZipCode: '44100',
        fiscalState: 'Jalisco',
        fiscalCountryId: 'country-1',
        defaultUsoCfdi: 'CP01',
      };
      expect(customerService.createCustomer).toHaveBeenCalledWith(expected);
    });

    it('sends the empty fiscal profile as null, which is what a cash customer declares', async () => {
      await render();

      component.onSaveCustomer({
        id: '',
        name: 'Cliente Mostrador',
        email: '',
        phone: '',
        rfc: '',
        salesChannel: 'TODOS',
        enabled: true,
        fiscal: {
          legalName: '',
          regimenFiscal: '',
          fiscalStreet: '',
          fiscalZipCode: '',
          fiscalState: '',
          fiscalCountryId: '',
          defaultUsoCfdi: '',
        },
      });

      expect(customerService.createCustomer).toHaveBeenCalledWith({
        name: 'Cliente Mostrador',
        email: null,
        phone: null,
        rfc: null,
        salesChannel: 'TODOS',
        enabled: true,
        legalName: null,
        regimenFiscal: null,
        fiscalStreet: null,
        fiscalZipCode: null,
        fiscalState: null,
        fiscalCountryId: null,
        defaultUsoCfdi: null,
      });
    });

    it('updates the row the route names, with the same body the create sends', async () => {
      routeId = 'c-1';
      await render();

      component.onSaveCustomer({
        id: 'c-1',
        name: 'Alpha Comercial',
        email: 'alpha@test.com',
        phone: '5551112222',
        rfc: 'RFC010101AB1',
        salesChannel: 'TODOS',
        enabled: true,
        fiscal: {
          legalName: 'Alpha Comercial SA de CV',
          regimenFiscal: '601',
          fiscalStreet: 'Av. Reforma 100',
          fiscalZipCode: '06600',
          fiscalState: 'Ciudad de México',
          fiscalCountryId: 'country-1',
          defaultUsoCfdi: 'G03',
        },
      });

      expect(customerService.updateCustomer).toHaveBeenCalledWith(
        'c-1',
        expect.objectContaining({ name: 'Alpha Comercial', regimenFiscal: '601' }),
      );
      expect(customerService.createCustomer).not.toHaveBeenCalled();
    });
  });

  describe('where saving goes', () => {
    it('opens the created customer in edit mode, so the operator stays on the row just created', async () => {
      await render();

      component.onSaveCustomer(formValue({ id: '' }));

      expect(router.navigate).toHaveBeenCalledWith(['/customers/edit', 'c-new']);
    });

    it('returns to the list after an update', async () => {
      routeId = 'c-1';
      await render();

      component.onSaveCustomer(formValue({ id: 'c-1' }));

      expect(router.navigate).toHaveBeenCalledWith(['/customers']);
    });

    it('returns to the list on cancel', async () => {
      await render();

      component.cancelForm();

      expect(router.navigate).toHaveBeenCalledWith(['/customers']);
    });
  });

  describe('the all-or-nothing rule, asserted through the form', () => {
    function formComponent(): CustomerForm {
      return fixture.debugElement.query(By.directive(CustomerForm)).componentInstance;
    }

    it('refuses a partial fiscal profile before the request leaves the browser', async () => {
      await render();
      const form = component.form;
      form.controls.name.setValue('Cliente Cuatro');
      form.controls.salesChannel.setValue('TODOS');
      form.controls.fiscal.controls.fiscalZipCode.setValue('06600');

      formComponent().onSave();
      fixture.detectChanges();

      // Nothing was sent: the screen refuses the shape the API would refuse with a 400.
      expect(customerService.createCustomer).not.toHaveBeenCalled();

      // One violation per missing fact, keyed by the API's own property name, each naming the fact.
      const fiscal = form.controls.fiscal;
      expect(fiscal.controls.legalName.errors).toMatchObject({
        fiscalProfileIncomplete: expect.stringContaining('razón social'),
      });
      expect(fiscal.controls.regimenFiscal.errors).toMatchObject({
        fiscalProfileIncomplete: expect.stringContaining('régimen fiscal'),
      });
      expect(fiscal.controls.defaultUsoCfdi.errors).toMatchObject({
        fiscalProfileIncomplete: expect.stringContaining('uso de CFDI'),
      });
      expect(fiscal.controls.fiscalZipCode.errors).toBeNull();
    });

    it('accepts a customer that declares no fiscal profile at all', async () => {
      await render();
      const form = component.form;
      form.controls.name.setValue('Cliente Mostrador');
      form.controls.salesChannel.setValue('TODOS');

      formComponent().onSave();
      fixture.detectChanges();

      expect(customerService.createCustomer).toHaveBeenCalledTimes(1);
      expect(customerService.createCustomer).toHaveBeenCalledWith(
        expect.objectContaining({ legalName: null, regimenFiscal: null, defaultUsoCfdi: null }),
      );
    });

    it('sends a complete fiscal profile', async () => {
      await render();
      const form = component.form;
      form.controls.name.setValue('Cliente Cinco');
      form.controls.salesChannel.setValue('TODOS');
      form.controls.fiscal.setValue({
        legalName: 'Cliente Cinco SA de CV',
        regimenFiscal: '601',
        fiscalStreet: '',
        fiscalZipCode: '06600',
        fiscalState: '',
        fiscalCountryId: 'country-1',
        defaultUsoCfdi: 'G03',
      });

      formComponent().onSave();
      fixture.detectChanges();

      expect(customerService.createCustomer).toHaveBeenCalledWith(
        expect.objectContaining({
          legalName: 'Cliente Cinco SA de CV',
          regimenFiscal: '601',
          fiscalZipCode: '06600',
          defaultUsoCfdi: 'G03',
          fiscalStreet: null,
          fiscalState: null,
        }),
      );
    });
  });

  describe('server errors', () => {
    it('shows the field-level errors the API answers with', async () => {
      await render();
      customerService.createCustomer = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: {
                status: 400,
                message: 'Validation failed',
                errors: { salesChannel: 'salesChannel is required' },
                path: '/api/customers',
                timestamp: '2026-11-17T10:00:00',
                correlationId: 'abc-123',
              },
              status: 400,
              statusText: 'Bad Request',
            }),
        ),
      );

      component.onSaveCustomer({
        id: '',
        name: 'Cliente Seis',
        email: '',
        phone: '',
        rfc: '',
        salesChannel: 'TODOS',
        enabled: true,
        fiscal: {
          legalName: '',
          regimenFiscal: '',
          fiscalStreet: '',
          fiscalZipCode: '',
          fiscalState: '',
          fiscalCountryId: '',
          defaultUsoCfdi: '',
        },
      });
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({ salesChannel: 'salesChannel is required' });
    });

    it('falls back to a general message when the API names no field', async () => {
      await render();
      customerService.createCustomer = vi.fn().mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              error: { status: 500 },
              status: 500,
              statusText: 'Internal Server Error',
            }),
        ),
      );

      component.onSaveCustomer({
        id: '',
        name: 'Cliente Siete',
        email: '',
        phone: '',
        rfc: '',
        salesChannel: 'TODOS',
        enabled: true,
        fiscal: {
          legalName: '',
          regimenFiscal: '',
          fiscalStreet: '',
          fiscalZipCode: '',
          fiscalState: '',
          fiscalCountryId: '',
          defaultUsoCfdi: '',
        },
      });
      fixture.detectChanges();

      expect(component.generalError()).toBe('Error inesperado. Intente de nuevo más tarde.');
    });
  });
});
